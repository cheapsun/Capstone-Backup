package com.example.project_2.domain.repo

import android.util.Log
import com.example.project_2.data.KakaoLocalService
import com.example.project_2.data.NaverSearchService
import com.example.project_2.data.weather.WeatherService
import com.example.project_2.domain.GptRerankUseCase
import com.example.project_2.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*

// 디버그 플래그: true면 리밸런스 우회하고 GPT 순서 그대로 반환
private const val DEBUG_BYPASS_REBALANCE: Boolean = false

// 🔧 기본 추천 범위 / 후보 개수 (카카오용)
private const val BASE_RADIUS_METERS = 8_000          // ✅ 기본 8km
private const val BASE_SIZE_PER_CATEGORY = 15         // 카테고리당 최대 15개 (카카오 limit)
private const val MAX_TOTAL_CANDIDATES = 120          // 기본 recommend에서 최대 합계

// 🔧 GPT용: 카카오에서 뽑을 후보 수 & GPT로 넘길 개수
private const val MAX_KAKAO_CANDIDATES_FOR_GPT = 15   // 카카오 size 최대 15 (페이지 여러 번)
private const val NAVER_TOP_N_FOR_GPT = 30            // GPT 프롬프트로 넘길 최대 개수

// 🔧 최종 추천 리스트 최대/최소 개수
private const val MAX_LIST_RESULTS = 20               // 화면에 보여줄 추천 장소 최대 개수
private const val MIN_LIST_RESULTS = 15               // 화면에 최소 이 정도는 보여주고 싶다

// 🔧 장소들 서로 간 최소 거리 (추천이 너무 몰리지 않게 간격 띄우기)
private const val MIN_DISTANCE_BETWEEN_PLACES_METERS = 1500   // 1.5km 정도

private const val EXTRA_KEYWORD_RESULTS_PER_CENTER = 8

// 🔧 관공서/구내식당/체인점 등 여행용 추천으로 어색한 후보 거르기용 키워드 (소문자 기준)
private val BANNED_KEYWORDS = listOf(
    // 관공서/공공기관
    "시청", "구청", "군청", "청사", "법원",
    "공무원", "구내식당", "사내식당", "공무원연금",

    // 카페 체인
    "스타벅스", "starbucks",
    "이디야", "ediya",
    "투썸플레이스", "투썸",
    "메가커피", "메가mgc",
    "빽다방",
    "폴바셋", "paul bassett",
    "커피빈", "coffeebean",
    "할리스", "할리스커피", "hollys",
    "엔제리너스",
    "파스쿠찌",
    "탐앤탐스",

    // 도넛/아이스크림
    "던킨", "던킨도너츠", "dunkin",
    "배스킨라빈스", "배스킨", "br31",

    // 패스트푸드
    "맥도날드", "맥날", "mcdonald",
    "롯데리아",
    "버거킹",
    "kfc",
    "맘스터치",
    "서브웨이", "subway"
)

// 🔧 recommend()에서 쓸 검색 중심 좌표
private data class SearchCenter(val lat: Double, val lng: Double)

/**
 * 도시 중심 좌표 하나(보통 시청 근처)를 기준으로
 * 위/아래/좌/우로 퍼진 여러 검색 중심을 만든다.
 */
private fun buildSearchCenters(baseLat: Double, baseLng: Double): List<SearchCenter> {
    val delta = 0.25   // 위도/경도 약 25~30km 정도

    return listOf(
        SearchCenter(baseLat, baseLng),               // 중심
        SearchCenter(baseLat + delta, baseLng),       // 북쪽
        SearchCenter(baseLat - delta, baseLng),       // 남쪽
        SearchCenter(baseLat, baseLng + delta),       // 동쪽
        SearchCenter(baseLat, baseLng - delta)        // 서쪽
    )
}

/**
 * GPT가 쓴 상세 추천 문장을 보고 AI 추천도 라벨을 보정한다.
 * - "특별한 매력은 없는", "평범한 곳" 등 부정적인 표현이 있으면 상 → 중
 * - "비추천", "권하기 어렵" 같은 강한 부정이면 하로 내린다.
 */
private fun adjustAiFitByText(base: String?, detail: String): String? {
    if (base == null) return null
    if (detail.isBlank()) return base

    val lower = detail.lowercase()

    // 아주 강한 부정 표현 → 무조건 "하"
    val strongNegativeKeywords = listOf(
        "추천하지 않습니다",
        "비추천",
        "권하기 어렵",
        "실망",
        "별로 추천",
        "다시 찾지 않을"
    )
    if (strongNegativeKeywords.any { lower.contains(it) }) {
        return "하"
    }

    // “특별한 매력은 없는 곳”, “평범한 곳” 등 → 상이면 중으로 내리기
    val weakNegativeKeywords = listOf(
        "특별한 매력은 없는",
        "평범한 곳",
        "큰 특징은 없",
        "무난한 곳",
        "아주 특별하진 않",
        "그냥 평범한"
    )
    if (weakNegativeKeywords.any { lower.contains(it) }) {
        return if (base == "상") "중" else base
    }

    return base
}


// 위도/경도 직접 받는 버전
private fun distanceBetweenMeters(
    lat1: Double, lng1: Double,
    lat2: Double, lng2: Double
): Double {
    val R = 6371000.0 // 지구 반지름(m)

    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val rLat1 = Math.toRadians(lat1)
    val rLat2 = Math.toRadians(lat2)

    val sinDLat = sin(dLat / 2)
    val sinDLng = sin(dLng / 2)

    val h = sinDLat * sinDLat +
            cos(rLat1) * cos(rLat2) * sinDLng * sinDLng

    val c = 2 * atan2(sqrt(h), sqrt(1 - h))
    return R * c
}

// =========================
//  추천 이유(적합도) 헬퍼들
// =========================

// =========================
//  추천 이유(적합도) 헬퍼들
// =========================

/**
 * 날씨 + 카테고리 기준 간단 라벨: "상" / "중" / "하"
 *
 * - 비 + 실내 카테고리  → 상
 * - 비 + 야외 카테고리  → 하
 * - 18~26도 + 야외      → 상
 * - 30도 이상 + 야외    → 하
 * - 3도 이하 + 야외     → 하
 * 그 외는 대부분 "중"
 */
private fun computeWeatherFit(place: Place, weather: WeatherInfo?): String {
    if (weather == null) return "중"

    val t = weather.tempC
    val cond = weather.condition

    // 야외 활동이 중심인 카테고리
    val outdoorCats = setOf(
        Category.PHOTO,
        Category.HEALING,
        Category.EXPERIENCE
    )

    // 실내에 더 잘 어울리는 카테고리
    val indoorCats = setOf(
        Category.CAFE,
        Category.FOOD,
        Category.SHOPPING,
        Category.STAY,
        Category.CULTURE   // 있으면 포함, 없으면 빼도 됨
    )

    val isOutdoor = place.category in outdoorCats
    val isIndoor = place.category in indoorCats

    // 1) 비 / 소나기 / 눈
    if (cond.contains("비") || cond.contains("소나기") || cond.contains("눈")) {
        return when {
            isIndoor -> "상"
            isOutdoor -> "하"
            else -> "중"
        }
    }

    // 2) 아주 추운 날 (3도 이하) → 야외는 하, 실내는 중~상
    if (t <= 3.0) {
        return when {
            isIndoor -> "상"
            isOutdoor -> "하"
            else -> "중"
        }
    }

    // 3) 쌀쌀한 늦가을/초봄 (3~12도) → 실내 상, 야외는 중
    if (t in 3.0..12.0) {
        return when {
            isIndoor -> "상"
            isOutdoor -> "중"
            else -> "중"
        }
    }

    // 4) 선선한 날 (12~22도) → 야외 상
    if (t in 12.0..22.0) {
        return when {
            isOutdoor -> "상"
            isIndoor -> "중"
            else -> "중"
        }
    }

    // 5) 약간 더운 날 (22~28도) → 대부분 중
    if (t in 22.0..28.0) {
        return "중"
    }

    // 6) 무더운 날 (28도 이상) → 실내 상, 야외 하
    if (t >= 28.0) {
        return when {
            isIndoor -> "상"
            isOutdoor -> "하"
            else -> "중"
        }
    }

    // 혹시 위 조건에 안 걸리면 무난하게 중
    return "중"
}

/**
 * 동행자 적합도: 현재는 FilterState를 직접 사용하지 않고,
 * 카테고리 + 평점/네이버 블로그 수로 "품질" 위주로 상/중/하를 나눈다.
 *
 * (나중에 FilterState 에 동행자 필드(혼자/연인/친구/가족)를 붙여서
 *  그 값에 따라 가중치를 다르게 줄 수 있음)
 */
private fun computeCompanionFit(filter: FilterState, place: Place): String {
    var score = 0

    // 카테고리 기본 점수
    score += when (place.category) {
        Category.CAFE,
        Category.FOOD,
        Category.PHOTO,
        Category.HEALING,
        Category.EXPERIENCE -> 2   // 동행자랑 같이 가기 좋은 편
        Category.SHOPPING,
        Category.STAY -> 1
        else -> 0
    }

    // 평점 보정
    val rating = place.rating ?: 0.0
    if (rating >= 4.5) score += 2
    else if (rating >= 4.0) score += 1
    else if (rating in 0.1..3.5) score -= 1

    // 네이버 언급량 보정
    val blogs = place.naverBlogCount ?: 0
    if (blogs >= 200) score += 2
    else if (blogs >= 80) score += 1

    return when {
        score >= 4 -> "상"
        score <= 1 -> "하"
        else -> "중"
    }
}

/**
 * GPT / AI 추천도: 리스트 내 상대 순위 기반으로 상/중/하를 나눈다.
 *
 * - 상위 30%  → 상
 * - 중간 40% → 중
 * - 하위 30% → 하
 */
/**
 * 최종 순위 기준으로 AI 추천도 상/중/하 라벨 만들기
 * - 상위 20% → 상
 * - 중간 50% → 중
 * - 하위 30% → 하
 */
private fun buildAiFitLabels(ordered: List<Place>): Map<String, String> {
    if (ordered.isEmpty()) return emptyMap()
    if (ordered.size == 1) return mapOf(ordered[0].id to "상")

    val n = ordered.size
    val denom = (n - 1).coerceAtLeast(1)

    return ordered.mapIndexed { index, place ->
        val ratio = index.toDouble() / denom.toDouble()

        val label = when {
            ratio <= 0.2 -> "상"      // 상위 20%
            ratio <= 0.7 -> "중"      // 중간 50%
            else -> "하"             // 하위 30%
        }

        place.id to label
    }.toMap()
}




/**
 * 한 줄 요약 문구 생성:
 * 예) "날씨 적합도 상(☀️), 동행자 적합도 중(💕), AI 추천도 상(✨)"
 *
 * aiFit 이 null 이면 "AI 추천도" 부분은 생략된다.
 */
private fun buildSummaryLine(
    place: Place,
    filter: FilterState,
    weather: WeatherInfo?,
    aiFit: String?
): String {
    val w = computeWeatherFit(place, weather)
    val c = computeCompanionFit(filter, place)

    return buildString {
        append("날씨 적합도 ")
        append(
            if (w == "상") "상" else w     // 상일 때만 아이콘 붙임
        )

        append(", 동행자 적합도 ")
        append(
            if (c == "상") "상" else c     // 상일 때만 아이콘 붙임
        )

        if (aiFit != null) {
            append(", AI 추천도 ")
            append(
                if (aiFit == "상") "상" else aiFit
            )
        }
    }
}



private fun filterByCity(
    candidates: List<Place>,
    regionText: String
): List<Place> {
    if (candidates.isEmpty()) return emptyList()

    // "부산 해운대, 서면" -> "부산"
    val cityKeyword = regionText
        .split(',', '·', '/', ';', ' ')
        .firstOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    if (cityKeyword == null) return candidates

    val filtered = candidates.filter { p ->
        p.address?.contains(cityKeyword) == true
    }

    // 혹시 너무 빡세게 걸러져서 0개 나오면 원본 그대로 사용
    return if (filtered.isNotEmpty()) filtered else candidates
}

private fun filterByDistrict(
    candidates: List<Place>,
    regionText: String
): List<Place> {
    val tokens = regionText.trim().split(" ")

    if (tokens.size < 2) return candidates  // "서울", "부산"처럼 단일 입력이면 패스

    val districtKeyword = tokens[1]   // "성수동", "해운대구", "동명동" 등

    val filtered = candidates.filter { p ->
        p.address?.contains(districtKeyword) == true
    }

    return if (filtered.isNotEmpty()) filtered else candidates
}

/**
 * "광주 동명동, 상무동, 첨단" 처럼 여러 동네가 들어왔을 때
 * ["광주 동명동", "광주 상무동", "광주 첨단"] 형태로 정리.
 */
private fun splitMultiRegions(regionText: String): List<String> {
    val rawTokens = regionText.split(',', '·', '/', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    if (rawTokens.size <= 1) return rawTokens

    val first = rawTokens.first()
    val firstParts = first.split(' ')
    val cityHint = if (firstParts.size >= 2) firstParts[0] else null
    if (cityHint == null) return rawTokens

    return rawTokens.mapIndexed { idx, token ->
        if (idx == 0) {
            token
        } else if (token.contains(' ')) {
            token           // 이미 "광주 상무동" 형태면 그대로 사용
        } else {
            "$cityHint $token"   // "상무동" -> "광주 상무동"
        }
    }
}

/**
 * 여러 지역명이 들어올 수 있는 regionText 를 받아서
 *  - 1개면 기존 buildCentersForRegion 로 처리
 *  - 여러 개면 각 지역을 geocode 해서 각각 SearchCenter로 사용
 *    (다 실패하면 기존 방식으로 fallback)
 */
private suspend fun buildCentersForMultiRegion(   // ✅ suspend
    regionText: String,
    baseLat: Double,
    baseLng: Double
): List<SearchCenter> {
    val tokens = splitMultiRegions(regionText)
    if (tokens.size <= 1) {
        // 단일 지역이면 기존 로직 그대로
        return buildCentersForRegion(regionText, baseLat, baseLng)
    }

    val centers = mutableListOf<SearchCenter>()

    for (token in tokens) {
        var gc = KakaoLocalService.geocode(token)

        // ⚠️ "광주 상무지구" 같은 게 실패하면, "상무지구" 로 한 번 더 시도
        if (gc == null && token.contains(" ")) {
            val tail = token.substringAfterLast(" ").trim()   // "상무지구"
            if (tail.isNotEmpty()) {
                gc = KakaoLocalService.geocode(tail)
            }
        }

        if (gc != null) {
            centers += SearchCenter(gc.first, gc.second)
            Log.d("REGION", "center for '$token' => (${gc.first}, ${gc.second})")
        } else {
            Log.w("REGION", "geocode failed for token=$token")
        }
    }

    // 하나라도 성공했으면 그걸 사용, 전부 실패했으면 기존 fallback
    return if (centers.isNotEmpty()) {
        centers
    } else {
        buildCentersForRegion(regionText, baseLat, baseLng)
    }
}

/**
 * ✅ "제주 서귀포", "부산 해운대구", "광주 상무동" 같은
 * 좀 더 세밀한 지역명인지 판단하는 헬퍼.
 */
private fun isFineGrainedRegion(region: String): Boolean {
    val trimmed = region.trim()
    if (trimmed.isEmpty()) return false

    // 공백이 있으면 보통 "도시 + 동네" 형태라고 보고 상세 지역으로 간주
    if (trimmed.contains(" ")) return true

    // ~동, ~읍, ~면, ~리, ~구, ~가, ~로, ~길 등으로 끝나면 상세 지역으로 간주
    val suffixes = listOf("동", "읍", "면", "리", "구", "가", "로", "길", "타운")
    return suffixes.any { trimmed.endsWith(it) }
}

/**
 * ✅ 지역 문자열 + 기본 중심 좌표를 받아,
 *  - 상세 지역이면: 그 좌표 1개만 사용
 *  - 넓은 지역이면: 동/서/남/북까지 확장한 여러 센터 사용
 */
private fun buildCentersForRegion(
    regionText: String,
    baseLat: Double,
    baseLng: Double
): List<SearchCenter> {
    return if (isFineGrainedRegion(regionText)) {
        listOf(SearchCenter(baseLat, baseLng))
    } else {
        buildSearchCenters(baseLat, baseLng)
    }
}

/**
 * 관공서/구내식당/체인점 등 여행용 맛집 추천으로는 어색한 장소들을 1차 필터링.
 */
private fun filterWeirdPlaces(candidates: List<Place>): List<Place> {
    if (candidates.isEmpty()) return emptyList()

    return candidates.filterNot { p ->
        val text = buildString {
            append(p.name)
            append(' ')
            append(p.category.toString())
        }.lowercase()

        BANNED_KEYWORDS.any { kw -> text.contains(kw) }
    }
}

/** 두 장소 간 거리(m) 계산 (Haversine 공식 사용) */
private fun distanceBetweenMeters(a: Place, b: Place): Double {
    val R = 6371000.0 // 지구 반지름(m)

    val dLat = Math.toRadians(b.lat - a.lat)
    val dLng = Math.toRadians(b.lng - a.lng)
    val lat1 = Math.toRadians(a.lat)
    val lat2 = Math.toRadians(b.lat)

    val sinDLat = sin(dLat / 2)
    val sinDLng = sin(dLng / 2)

    val h = sinDLat * sinDLat +
            cos(lat1) * cos(lat2) * sinDLng * sinDLng

    val c = 2 * atan2(sqrt(h), sqrt(1 - h))
    return R * c
}

/**
 * 카테고리별 최소 minPerCategory 개수는 유지하면서,
 * 이미 뽑힌 장소들과 너무 가까운 후보는 가능한 한 스킵해서
 * 추천이 한 곳에만 몰리지 않게 퍼뜨린다.
 */
private fun spreadOutPlacesByCategory(
    ordered: List<Place>,
    selectedCats: Set<Category>,
    minPerCategory: Int,
    minDistanceMeters: Int
): List<Place> {
    if (ordered.isEmpty()) return emptyList()

    val result = mutableListOf<Place>()
    val counts = mutableMapOf<Category, Int>().apply {
        selectedCats.forEach { this[it] = 0 }
    }

    for (p in ordered) {
        val cat = p.category
        val currentCount = counts[cat] ?: 0
        val minNeed = if (cat in selectedCats) minPerCategory else 0

        val tooClose = result.any { already ->
            distanceBetweenMeters(p, already) < minDistanceMeters
        }

        if (!tooClose || currentCount < minNeed) {
            result += p
            counts[cat] = currentCount + 1
        }
    }

    return result
}

/**
 * 네이버 인기 점수 + 블로그 개수 기준으로 상위 targetCount개만 고른다.
 */
private fun pickTopForGptByNaver(
    candidates: List<Place>,
    targetCount: Int
): List<Place> {
    if (candidates.isEmpty()) return emptyList()

    val sorted = candidates.sortedWith(
        compareByDescending<Place> { it.naverPopularityScore ?: 0.0 }
            .thenByDescending { it.naverBlogCount ?: 0 }
            .thenBy { it.distanceMeters ?: Int.MAX_VALUE }
    )

    return sorted.take(targetCount.coerceAtMost(sorted.size))
}

/**
 * ✅ 우리만의 리밸런스:
 *  - 입력된 순서(이미 GPT/네이버로 정렬됨)를 최대한 유지
 *  - 카테고리당 최소 minPerCat개 확보(가능한 경우)
 *  - 전체 개수는 totalCap까지
 *  - 단, minTotal이 > 0이면, 가능한 경우 최소 minTotal개까지 채워줌
 */
private fun rebalanceAndSpread(
    ordered: List<Place>,
    selectedCats: Set<Category>,
    minPerCat: Int,
    totalCap: Int,
    minTotal: Int = 0
): Pair<List<Place>, List<Place>> {
    if (ordered.isEmpty()) return emptyList<Place>() to emptyList()

    val maxCap = totalCap.coerceAtMost(ordered.size)
    val pickedSet = LinkedHashSet<Place>()

    // 1) 카테고리별 최소 개수 먼저 확보
    for (cat in selectedCats) {
        ordered.filter { it.category == cat }
            .take(minPerCat)
            .forEach { pickedSet.add(it) }
    }

    // 2) 남는 자리에 나머지 후보를 순서대로 채운다
    for (p in ordered) {
        if (pickedSet.size >= maxCap) break
        pickedSet.add(p)
    }

    val finalList = pickedSet.toList()

    // 3) 카테고리별 Top (최초로 등장한 곳)
    val top = selectedCats.mapNotNull { cat ->
        finalList.firstOrNull { it.category == cat }
    }

    // 4) 거리 퍼뜨리기 적용
    val spread = spreadOutPlacesByCategory(
        ordered = finalList,
        selectedCats = selectedCats,
        minPerCategory = minPerCat,
        minDistanceMeters = MIN_DISTANCE_BETWEEN_PLACES_METERS
    )

    // 5) ✅ 최소 개수 보장: 거리 퍼뜨리면서 너무 줄어들었으면 다시 채워 넣기
    val ensuredSpread = if (minTotal <= 0 || spread.size >= minTotal || finalList.size <= spread.size) {
        spread
    } else {
        val targetSize = min(minTotal, finalList.size)
        val result = spread.toMutableList()

        for (p in finalList) {
            if (result.size >= targetSize) break
            if (result.none { it.id == p.id }) {
                result += p
            }
        }
        result
    }

    return top to ensuredSpread
}

class RealTravelRepository(
    private val reranker: GptRerankUseCase
) : TravelRepository {

    // ================= 날씨 =================

    override suspend fun getWeather(region: String): WeatherInfo? = withContext(Dispatchers.IO) {
        val center = KakaoLocalService.geocode(region) ?: run {
            Log.w("WEATHER", "geocode failed for region=$region")
            return@withContext null
        }
        val (lat, lng) = center
        runCatching { WeatherService.currentByLatLng(lat, lng) }
            .onFailure { Log.e("WEATHER", "WeatherService error: ${it.message}", it) }
            .getOrNull()
            ?.let { WeatherInfo(it.tempC, it.condition, it.icon) }
    }

    override suspend fun getWeatherByLatLng(lat: Double, lng: Double): WeatherInfo? =
        withContext(Dispatchers.IO) {
            runCatching { WeatherService.currentByLatLng(lat, lng) }
                .onFailure { Log.e("WEATHER", "WeatherService(lat,lng) error: ${it.message}", it) }
                .getOrNull()
                ?.let { WeatherInfo(it.tempC, it.condition, it.icon) }
        }

    // ================= 기본 recommend (카카오만 사용) =================

    // ================= 기본 recommend (카카오만 사용) =================

    override suspend fun recommend(
        filter: FilterState,
        weather: WeatherInfo?
    ): RecommendationResult = withContext(Dispatchers.IO) {

        // 예: "부산 광안리, 송정해수욕장"
        val regionTextRaw = filter.region.ifBlank { "서울" }

        // "부산 광안리, 송정해수욕장" → ["부산 광안리", "부산 송정해수욕장"]
        val tokens = splitMultiRegions(regionTextRaw)
        // 중심 좌표를 잡기 위한 기준 문자열 (도시 또는 첫 번째 동네)
        val regionForCenter = tokens.firstOrNull() ?: regionTextRaw

        Log.d(
            "RECOMMEND",
            "recommend(region=$regionTextRaw, centerHint=$regionForCenter, cats=${filter.categories})"
        )

        // ✅ 중심 좌표는 첫 번째 토큰(도시/첫 동네) 기준으로만 잡는다.
        val center = KakaoLocalService.geocode(regionForCenter)
        if (center == null) {
            Log.w("RECOMMEND", "geocode failed for region=$regionForCenter")
            return@withContext RecommendationResult(emptyList(), weather)
        }
        val (centerLat, centerLng) = center

        val regionText = regionTextRaw
        val cats: Set<Category> =
            if (filter.categories.isEmpty()) setOf(Category.FOOD) else filter.categories

        val radius = min(8_000, BASE_RADIUS_METERS)
        val sizePerCat = BASE_SIZE_PER_CATEGORY

        // ✅ 여러 동네를 중심으로 검색
        val centers = buildCentersForMultiRegion(regionText, centerLat, centerLng)

        val merged = LinkedHashMap<String, Place>()
        for (cat in cats) {
            for (c in centers) {
                val chunk = KakaoLocalService.searchByCategories(
                    centerLat = c.lat,
                    centerLng = c.lng,
                    categories = setOf(cat),
                    radiusMeters = radius,
                    size = sizePerCat
                )
                Log.d(
                    "RECOMMEND",
                    "cat=$cat center=(${c.lat},${c.lng}) chunk=${chunk.size} : " +
                            chunk.joinToString(limit = 6) { it.name }
                )
                chunk.forEach { p -> merged.putIfAbsent(p.id, p) }
                if (merged.size >= MAX_TOTAL_CANDIDATES) break
            }
            if (merged.size >= MAX_TOTAL_CANDIDATES) break
        }

        Log.d(
            "RECOMMEND",
            "merged total=${merged.size} : " +
                    merged.values.joinToString(limit = 8) { it.name }
        )

        val cleanedCandidates = filterWeirdPlaces(merged.values.toList())

        // ✅ 도시 기준으로 한 번 더 필터링 (부산이면 부산 아닌 주소 제거)
        val cityFiltered = filterByCity(
            candidates = cleanedCandidates,
            regionText = regionTextRaw
        )

        // ✅ 그 다음 구/동 기준
        val districtFiltered = filterByDistrict(
            candidates = cityFiltered,
            regionText = regionTextRaw
        )

        val minPerCat = 3
        val perCatTop = 1

        // ----------------------------
        // 1) 카테고리 리밸런스
        // ----------------------------
        val (catTop, catBalancedRaw) = rebalanceByCategory(
            candidates = districtFiltered,
            selectedCats = cats,
            minPerCat = minPerCat,
            perCatTop = perCatTop,
            totalCap = MAX_LIST_RESULTS
        )

        // 🔹 멀티 동네 여부 체크 (콤마/구분자 들어가면 여러 동네로 판단)
        val hasMultiRegions = regionTextRaw.contains(",") ||
                regionTextRaw.contains("·") ||
                regionTextRaw.contains("/") ||
                regionTextRaw.contains(";")

        // ----------------------------
        // 2) 동네 리밸런스 (여러 동네인 경우에만)
        // ----------------------------
        val neighborhoodBalanced = if (hasMultiRegions) {
            rebalanceByNeighborhood(
                ordered = catBalancedRaw,
                regionText = regionTextRaw,
                minPerNeighborhood = 2,          // 동네당 최소 개수
                totalCap = MAX_LIST_RESULTS
            )
        } else {
            catBalancedRaw
        }

        // ----------------------------
        // 3) 세밀한 지역이면 중심 기준 거리 필터
        //    (여러 동네 입력이면 스킵)
        // ----------------------------
        val isFineRegion = !hasMultiRegions && isFineGrainedRegion(regionTextRaw)
        val maxDistanceMetersForFineRegion = 5_000   // 예: 5km 안

        val distanceFiltered = if (isFineRegion) {
            val filteredByDist = neighborhoodBalanced.filter { p ->
                distanceBetweenMeters(centerLat, centerLng, p.lat, p.lng) <= maxDistanceMetersForFineRegion
            }

            if (filteredByDist.size >= MIN_LIST_RESULTS || filteredByDist.isNotEmpty()) {
                filteredByDist
            } else {
                neighborhoodBalanced
            }
        } else {
            neighborhoodBalanced
        }

        // ----------------------------
        // 4) MIN_LIST_RESULTS 보장 (가능한 경우)
        // ----------------------------
        val ordered = if (
            distanceFiltered.size >= MIN_LIST_RESULTS ||
            neighborhoodBalanced.size <= MIN_LIST_RESULTS
        ) {
            distanceFiltered
        } else {
            val result = distanceFiltered.toMutableList()
            for (p in neighborhoodBalanced) {
                if (result.size >= MIN_LIST_RESULTS) break
                if (result.none { it.id == p.id }) {
                    result += p
                }
            }
            result
        }

// distance / 동네 리밸런스 적용 후 topPicks 계산 (카테고리별 첫 번째)
        val top = cats.mapNotNull { cat ->
            ordered.firstOrNull { it.category == cat }
        }

// 🔹 기본 recommend 는 GPT 점수가 없으므로 AI 추천도는 제외하고, 날씨/동행자만 요약으로 사용
        val reasonMap: Map<String, String> = ordered.associate { p ->
            val summary = buildSummaryLine(
                place = p,
                filter = filter,
                weather = weather,
                aiFit = null          // ← GPT 안 쓰는 버전이니까 항상 null
            )
            p.id to summary
        }

        return@withContext RecommendationResult(
            places = ordered,
            weather = weather,
            topPicks = top,
            gptReasons = reasonMap
        )
    }




    // ================= GPT 재랭크 recommend =================

    override suspend fun recommendWithGpt(
        filter: FilterState,
        centerLat: Double,
        centerLng: Double,
        radiusMeters: Int,
        candidateSize: Int   // 루트 길이 정보용 (리스트 개수는 따로)
    ): RecommendationResult = withContext(Dispatchers.IO) {

        val regionHint = filter.region.ifBlank { null }
        val cats = if (filter.categories.isEmpty()) setOf(Category.FOOD) else filter.categories

        Log.d(
            "FLOW",
            "recommendWithGpt(region='${filter.region}', cats=$cats, center=($centerLat,$centerLng), radiusReq=$radiusMeters, routeSize=$candidateSize)"
        )

        val weather = getWeatherByLatLng(centerLat, centerLng).also {
            Log.d("RERANK", "weather=${it?.condition} ${it?.tempC}C")
        }

        val radius = min(8_000, BASE_RADIUS_METERS)

        // ✅ 선택한 지역이 있으면 그 지역(멀티지역 포함) 기준으로,
        //    없으면 현재 center 한 곳만 사용해서 검색 범위를 쓸데없이 안 넓힘
        val centers: List<SearchCenter> = if (regionHint.isNullOrBlank()) {
            listOf(SearchCenter(centerLat, centerLng))
        } else {
            buildCentersForMultiRegion(regionHint, centerLat, centerLng)
        }

        val kakaoMerged = LinkedHashMap<String, Place>()

        // 1) 기존 카테고리 기반 Kakao 검색 ------------------------------
        for (cat in cats) {
            for (c in centers) {
                val chunk = KakaoLocalService.searchByCategories(
                    centerLat = c.lat,
                    centerLng = c.lng,
                    categories = setOf(cat),
                    radiusMeters = radius,
                    size = MAX_KAKAO_CANDIDATES_FOR_GPT,
                    maxPages = 2
                )
                Log.d(
                    "RERANK",
                    "cat=$cat center=(${c.lat},${c.lng}) chunk=${chunk.size} : " +
                            chunk.joinToString(limit = 10) { it.name }
                )
                chunk.forEach { p -> kakaoMerged.putIfAbsent(p.id, p) }
            }
        }

        val kakaoCandidatesRaw = kakaoMerged.values.toList()

        Log.d(
            "RERANK",
            "kakao merged candidates(${kakaoCandidatesRaw.size}): " +
                    kakaoCandidatesRaw.joinToString(limit = 30) { it.name }
        )

        // ✅ 먼저 도시 기준으로 필터 (부산이면 부산 포함 주소만)
        val cityFiltered = filterByCity(
            candidates = kakaoCandidatesRaw,
            regionText = filter.region
        )

        // ✅ 그 다음 구/동 기준으로 한 번 더 필터
        val districtFiltered = filterByDistrict(
            candidates = cityFiltered,
            regionText = filter.region
        )

        // 그 다음 체인점/관공서 필터
        val kakaoCandidates = filterWeirdPlaces(districtFiltered)

        if (kakaoCandidates.isEmpty()) {
            Log.w("RERANK", "no kakao candidates after filtering")
            return@withContext RecommendationResult(emptyList(), weather)
        }

        // 네이버 인기/블로그 정보 붙이기
        val enrichedCandidates = kakaoCandidates.map { place ->
            val popularity = NaverSearchService.getPopularityForPlace(
                placeName = place.name,
                regionHint = regionHint
            )

            if (popularity != null) {
                place.copy(
                    naverBlogCount = popularity.totalCount,
                    naverPopularityScore = popularity.score
                )
            } else {
                place
            }
        }

        val listCap = MAX_LIST_RESULTS
        val gptInputCount = listCap.coerceAtMost(NAVER_TOP_N_FOR_GPT)

        val candidatesForGpt = pickTopForGptByNaver(
            candidates = enrichedCandidates,
            targetCount = gptInputCount
        )

        Log.d(
            "RERANK",
            "naver top($gptInputCount, listCap=$listCap): " +
                    candidatesForGpt.joinToString(limit = 20) {
                        "${it.name}(blogs=${it.naverBlogCount}, pop=${"%.2f".format(it.naverPopularityScore ?: 0.0)})"
                    }
        )

        val out = runCatching {
            reranker.rerankWithReasons(
                filter = filter.copy(region = ""),   // GPT 프롬프트에는 region 비워서 넘김
                weather = weather,
                candidates = candidatesForGpt
            )
        }.onFailure {
            Log.e("RERANK", "rerank error: ${it.message}", it)
        }.getOrElse {
            GptRerankUseCase.RerankOutput(candidatesForGpt, emptyMap())
        }

        if (DEBUG_BYPASS_REBALANCE) {
            Log.w("RERANK", "DEBUG BYPASS → returning GPT order directly")
            return@withContext RecommendationResult(
                places = out.places,
                weather = weather,
                gptReasons = out.reasons,
                topPicks = out.places
                    .filter { it.category in cats }
                    .distinctBy { it.category }
                    .take(cats.size.coerceAtLeast(1)),
                aiTopIds = out.aiTopIds
            )
        }

        val minPerCat = 3
        val perCatTop = 1

        // ----------------------------
        // 1) 카테고리 리밸런스 (GPT 출력 리스트 기준)
        // ----------------------------
        val (catTop, catBalancedRaw) = rebalanceByCategory(
            candidates = out.places,
            selectedCats = cats,
            minPerCat = minPerCat,
            perCatTop = perCatTop,
            totalCap = listCap           // 최대 20개
        )

        // 🔹 멀티 동네 여부 체크
        val hasMultiRegions = filter.region.contains(",") ||
                filter.region.contains("·") ||
                filter.region.contains("/") ||
                filter.region.contains(";")

        // ----------------------------
        // 2) 동네 리밸런스 (여러 동네 입력인 경우에만)
        // ----------------------------
        val neighborhoodBalanced = if (hasMultiRegions) {
            rebalanceByNeighborhood(
                ordered = catBalancedRaw,
                regionText = filter.region,
                minPerNeighborhood = 2,
                totalCap = listCap
            )
        } else {
            catBalancedRaw
        }

        // ----------------------------
        // 3) 세밀한 지역이면 중심 기준 거리 필터
        // ----------------------------
        val isFineRegion = isFineGrainedRegion(filter.region) && !hasMultiRegions
        val maxDistanceMetersForFineRegion = 5_000   // 예: 5km

        val distanceFiltered = if (isFineRegion) {
            val filteredByDist = neighborhoodBalanced.filter { p ->
                distanceBetweenMeters(centerLat, centerLng, p.lat, p.lng) <= maxDistanceMetersForFineRegion
            }

            if (filteredByDist.size >= MIN_LIST_RESULTS || filteredByDist.isNotEmpty()) {
                filteredByDist
            } else {
                neighborhoodBalanced
            }
        } else {
            neighborhoodBalanced
        }

        // ----------------------------
        // 4) MIN_LIST_RESULTS 보장 (가능한 경우)
        // ----------------------------
        val ordered = if (
            distanceFiltered.size >= MIN_LIST_RESULTS ||
            neighborhoodBalanced.size <= MIN_LIST_RESULTS
        ) {
            distanceFiltered
        } else {
            val result = distanceFiltered.toMutableList()
            for (p in neighborhoodBalanced) {
                if (result.size >= MIN_LIST_RESULTS) break
                if (result.none { it.id == p.id }) {
                    result += p
                }
            }
            result
        }

        // distance / 동네 리밸런스 적용 후 top 재계산
        // distance / 동네 리밸런스 적용 후 top 재계산
        val top = cats.mapNotNull { cat ->
            ordered.firstOrNull { it.category == cat }
        }

// 🔹 AI 추천도 상/중/하: 최종 ordered 리스트 기준 상대 순위로 계산
        val aiFitMap: Map<String, String> = buildAiFitLabels(ordered)

// 🔹 요약 한 줄 + GPT 상세 이유를 합친다. (텍스트 기반으로 AI 추천도 보정)
        val mergedReasons: Map<String, String> = ordered.associate { p ->
            val rawDetail = out.reasons[p.id]?.trim().orEmpty()

            // 👉 상세 문장을 보고 "상"을 중/하로 내릴 수 있음
            val adjustedAi = adjustAiFitByText(aiFitMap[p.id], rawDetail)

            val summary = buildSummaryLine(
                place = p,
                filter = filter,
                weather = weather,
                aiFit = adjustedAi
            )

            val text = if (rawDetail.isEmpty()) {
                summary
            } else {
                summary + "\n" + rawDetail
            }

            p.id to text
        }


        Log.d("RERANK", ">>> repo final size = ${ordered.size}")

        return@withContext RecommendationResult(
            places = ordered,
            weather = weather,
            gptReasons = mergedReasons,
            topPicks = top,
            aiTopIds = out.aiTopIds
        )
    }





}
