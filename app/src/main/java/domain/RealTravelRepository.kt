package com.example.project_2.domain.repo

import android.util.Log
import com.example.project_2.data.KakaoLocalService
import com.example.project_2.data.weather.WeatherService
import com.example.project_2.domain.GptRerankUseCase
import com.example.project_2.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.min

// ⬇️ 카테고리 최소/Top 보장 유틸
import com.example.project_2.domain.model.rebalanceByCategory

// ⬇️ 디버그 플래그: true면 리밸런스 우회하고 GPT 순서 그대로 반환
private const val DEBUG_BYPASS_REBALANCE: Boolean = false

class RealTravelRepository(
    private val reranker: GptRerankUseCase
) : TravelRepository {

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

    // ===== 기본 recommend (카카오만) =====
    override suspend fun recommend(
        filter: FilterState,
        weather: WeatherInfo?
    ): RecommendationResult = withContext(Dispatchers.IO) {
        val regionText = filter.region.ifBlank { "서울" }
        Log.d("RECOMMEND", "recommend(region=$regionText, cats=${filter.categories})")

        val center = KakaoLocalService.geocode(regionText)
        if (center == null) {
            Log.w("RECOMMEND", "geocode failed for region=$regionText")
            return@withContext RecommendationResult(emptyList(), weather)
        }
        val (centerLat, centerLng) = center

        val cats: Set<Category> =
            if (filter.categories.isEmpty()) setOf(Category.FOOD) else filter.categories

        val radius = min(20_000, kotlin.math.max(1, 3_000))
        val sizePerCat = 15 // 카카오 size 최대 15

        // 카테고리별 개별 호출 → 합치기(원순서 유지)
        val merged = LinkedHashMap<String, Place>()
        for (cat in cats) {
            val chunk = KakaoLocalService.searchByCategories(
                centerLat = centerLat,
                centerLng = centerLng,
                categories = setOf(cat),
                radiusMeters = radius,
                size = sizePerCat
            )
            Log.d("RECOMMEND", "cat=$cat chunk=${chunk.size} : " +
                    chunk.joinToString(limit = 6) { it.name })
            chunk.forEach { p -> merged.putIfAbsent(p.id, p) }
            if (merged.size >= 60) break
        }

        Log.d("RECOMMEND", "merged total=${merged.size} : " +
                merged.values.joinToString(limit = 8) { it.name })

        val (top, ordered) = rebalanceByCategory(
            candidates = merged.values.toList(),
            selectedCats = cats,
            minPerCat = 4,
            perCatTop = 1,
            totalCap = null
        )

        RecommendationResult(
            places = ordered,
            weather = weather,
            topPicks = top
        )
    }

    // ===== GPT 재랭크 recommend =====
    override suspend fun recommendWithGpt(
        filter: FilterState,
        centerLat: Double,
        centerLng: Double,
        radiusMeters: Int,
        candidateSize: Int
    ): RecommendationResult = withContext(Dispatchers.IO) {
        Log.d(
            "FLOW",
            "USING recommendWithGpt(cats=${filter.categories}, center=($centerLat,$centerLng), radius=$radiusMeters, size=$candidateSize)"
        )

        val weather = getWeatherByLatLng(centerLat, centerLng).also {
            Log.d("RERANK", "weather=${it?.condition} ${it?.tempC}C")
        }

        val cats = if (filter.categories.isEmpty()) setOf(Category.FOOD) else filter.categories
        val radius = min(20_000, kotlin.math.max(1, radiusMeters))
        val size = min(15, kotlin.math.max(1, candidateSize))

        // 1) 카카오 후보
        val candidates = KakaoLocalService.searchByCategories(
            centerLat = centerLat,
            centerLng = centerLng,
            categories = cats,
            radiusMeters = radius,
            size = size
        )
        Log.d("RERANK", "kakao candidates(${candidates.size}): " +
                candidates.joinToString { it.name })

        // 2) GPT 재랭크 (실패 시 원본 유지)
        val out = runCatching {
            reranker.rerankWithReasons(filter.copy(region = ""), weather, candidates)
        }.onFailure {
            Log.e("RERANK", "rerank error: ${it.message}", it)
        }.getOrElse {
            GptRerankUseCase.RerankOutput(candidates, emptyMap())
        }

        // 3) 순서 비교 로그
        val kakaoIds = candidates.joinToString { it.id }
        val gptIds   = out.places.joinToString { it.id }
        Log.d("RERANK", "kakao order: $kakaoIds")
        Log.d("RERANK", "gpt   order: $gptIds")

        // 4) 디버그: GPT 순서 그대로 반환 (리밸런스 우회)
        if (DEBUG_BYPASS_REBALANCE) {
            Log.w("RERANK", "DEBUG BYPASS → returning GPT order directly")
            return@withContext RecommendationResult(
                places     = out.places,        // ★ GPT가 만든 순서 그대로
                weather    = weather,
                gptReasons = out.reasons,
                topPicks   = out.places
                    .filter { it.category in cats }
                    .distinctBy { it.category }
                    .take(cats.size.coerceAtLeast(1)),
                aiTopIds   = out.aiTopIds
            )
        }

        // 5) (정상 흐름) 카테고리 최소/Top 보장
        val (top, ordered) = rebalanceByCategory(
            candidates = out.places,
            selectedCats = cats,
            minPerCat = 4,
            perCatTop = 1,
            totalCap = null
        )

        RecommendationResult(
            places     = ordered,
            weather    = weather,
            gptReasons = out.reasons,
            topPicks   = top,
            aiTopIds   = out.aiTopIds
        )
    }

    // ===== 다중 중심점 검색 (WIDE 전략) =====
    /**
     * 여러 세부 지역 중심으로 병렬 검색 후 결과 병합
     *
     * @param mainRegion 주 지역명 (예: "부산")
     * @param subRegions 세부 지역 리스트 (예: ["해운대", "광안리", "서면"])
     * @param categories 검색할 카테고리
     * @param radiusPerPoint 각 중심점당 검색 반경 (미터)
     * @param sizePerPoint 각 중심점당 최대 결과 수
     * @return 병합된 장소 리스트
     */
    suspend fun searchMultiplePoints(
        mainRegion: String,
        subRegions: List<String>,
        categories: Set<Category>,
        radiusPerPoint: Int = 3000,
        sizePerPoint: Int = 5
    ): List<Place> = withContext(Dispatchers.IO) {
        Log.d("MULTI_SEARCH", "searchMultiplePoints: main=$mainRegion, subs=$subRegions, radius=$radiusPerPoint")

        val cats = if (categories.isEmpty()) setOf(Category.FOOD) else categories
        val allPlaces = mutableListOf<Place>()

        // 병렬로 각 세부 지역 검색
        coroutineScope {
            val searchJobs = subRegions.map { subRegion ->
                async {
                    try {
                        // 좌표 얻기
                        val fullRegionName = "$mainRegion $subRegion"
                        Log.d("MULTI_SEARCH", "Geocoding: $fullRegionName")

                        val coords = KakaoLocalService.geocode(fullRegionName)
                        if (coords == null) {
                            Log.w("MULTI_SEARCH", "Geocode failed for: $fullRegionName")
                            return@async emptyList<Place>()
                        }

                        val (lat, lng) = coords
                        Log.d("MULTI_SEARCH", "$fullRegionName -> ($lat, $lng)")

                        // 장소 검색
                        val places = KakaoLocalService.searchByCategories(
                            centerLat = lat,
                            centerLng = lng,
                            categories = cats,
                            radiusMeters = radiusPerPoint,
                            size = sizePerPoint
                        )

                        Log.d("MULTI_SEARCH", "$subRegion: ${places.size} places found")
                        places
                    } catch (e: Exception) {
                        Log.e("MULTI_SEARCH", "Error searching $subRegion: ${e.message}", e)
                        emptyList()
                    }
                }
            }

            // 모든 검색 완료 대기 후 병합
            searchJobs.awaitAll().forEach { places ->
                allPlaces.addAll(places)
            }
        }

        // 중복 제거 (같은 place_id)
        val uniquePlaces = allPlaces.distinctBy { it.id }

        Log.d("MULTI_SEARCH", "Total: ${allPlaces.size} places, Unique: ${uniquePlaces.size}")
        Log.d("MULTI_SEARCH", "Sample: ${uniquePlaces.take(5).joinToString { it.name }}")

        uniquePlaces
    }

    /**
     * WIDE 전략용 GPT 재랭크 추천
     *
     * @param filter 사용자 필터 (지역, 카테고리 등)
     * @param subRegions 세부 지역 리스트
     * @param radiusMeters 각 세부 지역당 검색 반경
     * @param candidateSizePerRegion 각 지역당 후보 수
     * @return 재랭크된 추천 결과
     */
    suspend fun recommendWideWithGpt(
        filter: FilterState,
        subRegions: List<String>,
        radiusMeters: Int = 3000,
        candidateSizePerRegion: Int = 5
    ): RecommendationResult = withContext(Dispatchers.IO) {
        val mainRegion = filter.region.ifBlank { "서울" }
        Log.d("WIDE_SEARCH", "recommendWideWithGpt: region=$mainRegion, subs=$subRegions")

        // 날씨 조회 (주 지역 중심으로)
        val weather = getWeather(mainRegion).also {
            Log.d("WIDE_SEARCH", "weather=${it?.condition} ${it?.tempC}C")
        }

        val cats = if (filter.categories.isEmpty()) setOf(Category.FOOD) else filter.categories

        // 다중 중심점 검색
        val candidates = searchMultiplePoints(
            mainRegion = mainRegion,
            subRegions = subRegions,
            categories = cats,
            radiusPerPoint = radiusMeters,
            sizePerPoint = candidateSizePerRegion
        )

        Log.d("WIDE_SEARCH", "Multi-point search returned ${candidates.size} candidates")

        if (candidates.isEmpty()) {
            Log.w("WIDE_SEARCH", "No candidates found!")
            return@withContext RecommendationResult(emptyList(), weather)
        }

        // GPT 재랭크
        val out = runCatching {
            reranker.rerankWithReasons(filter, weather, candidates)
        }.onFailure {
            Log.e("WIDE_SEARCH", "GPT rerank error: ${it.message}", it)
        }.getOrElse {
            GptRerankUseCase.RerankOutput(candidates, emptyMap())
        }

        // 리밸런싱
        val (top, ordered) = rebalanceByCategory(
            candidates = out.places,
            selectedCats = cats,
            minPerCat = 4,
            perCatTop = 1,
            totalCap = null
        )

        RecommendationResult(
            places     = ordered,
            weather    = weather,
            gptReasons = out.reasons,
            topPicks   = top,
            aiTopIds   = out.aiTopIds
        )
    }

    /**
     * 검색 범위 확대 (더 많은 장소 보기)
     *
     * @param centerLat 중심 위도
     * @param centerLng 중심 경도
     * @param categories 카테고리
     * @param newRadius 확대된 반경
     * @param excludeIds 이미 표시된 장소 ID (중복 제외용)
     * @return 추가 장소 리스트
     */
    suspend fun expandSearch(
        centerLat: Double,
        centerLng: Double,
        categories: Set<Category>,
        newRadius: Int = 5000,
        excludeIds: Set<String> = emptySet()
    ): List<Place> = withContext(Dispatchers.IO) {
        Log.d("EXPAND_SEARCH", "expandSearch: radius=$newRadius, exclude=${excludeIds.size}")

        val cats = if (categories.isEmpty()) setOf(Category.FOOD) else categories

        val newPlaces = KakaoLocalService.searchByCategories(
            centerLat = centerLat,
            centerLng = centerLng,
            categories = cats,
            radiusMeters = newRadius,
            size = 15  // 더 많이 검색
        )

        // 이미 표시된 장소 제외
        val filtered = newPlaces.filter { it.id !in excludeIds }

        Log.d("EXPAND_SEARCH", "Found ${newPlaces.size} places, ${filtered.size} new ones")

        filtered
    }

    // ===== 폴리곤 기반 검색 (WIDE 전략 대체) =====

    /**
     * 폴리곤 내부에 grid 포인트를 생성하고 각 포인트에서 검색
     *
     * @param polygonCoords 폴리곤 좌표 리스트
     * @param categories 검색 카테고리
     * @param gridSpacing grid 간격 (도 단위, 약 0.05 = 5km)
     * @param radiusPerPoint 각 grid 포인트당 검색 반경 (미터)
     * @param sizePerPoint 각 포인트당 최대 결과 수
     * @param maxGridPoints 최대 grid 포인트 개수 (성능 제한)
     * @return 폴리곤 내부의 장소 리스트
     */
    suspend fun searchWithinPolygon(
        polygonCoords: List<com.example.project_2.data.LatLng>,
        categories: Set<Category>,
        gridSpacing: Double = 0.05,  // ✅ 약 5km (기존 1.5km에서 증가)
        radiusPerPoint: Int = 3000,   // ✅ 3km 반경 (기존 2km에서 증가)
        sizePerPoint: Int = 15,       // ✅ 각 포인트당 15개 (기존 10개에서 증가)
        maxGridPoints: Int = 50       // ✅ 최대 50개 포인트로 제한
    ): List<Place> = withContext(Dispatchers.IO) {
        Log.d("POLYGON_SEARCH", "searchWithinPolygon: ${polygonCoords.size} coords, spacing=$gridSpacing, max=$maxGridPoints")

        if (polygonCoords.isEmpty()) {
            Log.w("POLYGON_SEARCH", "Empty polygon!")
            return@withContext emptyList()
        }

        val cats = if (categories.isEmpty()) setOf(Category.FOOD) else categories

        // 1. 폴리곤의 경계 계산 (bounding box)
        val minLat = polygonCoords.minOf { it.lat }
        val maxLat = polygonCoords.maxOf { it.lat }
        val minLng = polygonCoords.minOf { it.lng }
        val maxLng = polygonCoords.maxOf { it.lng }

        Log.d("POLYGON_SEARCH", "Bounds: lat=[$minLat, $maxLat], lng=[$minLng, $maxLng]")

        // 2. Grid 포인트 생성
        val gridPoints = mutableListOf<Pair<Double, Double>>()
        var currentLat = minLat
        while (currentLat <= maxLat && gridPoints.size < maxGridPoints) {  // ✅ 최대 개수 체크
            var currentLng = minLng
            while (currentLng <= maxLng && gridPoints.size < maxGridPoints) {  // ✅ 최대 개수 체크
                // 포인트가 폴리곤 내부에 있는지 확인
                if (isPointInPolygon(currentLat, currentLng, polygonCoords)) {
                    gridPoints.add(currentLat to currentLng)
                }
                currentLng += gridSpacing
            }
            currentLat += gridSpacing
        }

        Log.d("POLYGON_SEARCH", "Generated ${gridPoints.size} grid points (max: $maxGridPoints)")

        if (gridPoints.isEmpty()) {
            Log.w("POLYGON_SEARCH", "No grid points found inside polygon!")
            return@withContext emptyList()
        }

        // 3. 각 grid 포인트에서 병렬 검색
        val allPlaces = mutableListOf<Place>()

        coroutineScope {
            val searchJobs = gridPoints.map { (lat, lng) ->
                async {
                    try {
                        val places = KakaoLocalService.searchByCategories(
                            centerLat = lat,
                            centerLng = lng,
                            categories = cats,
                            radiusMeters = radiusPerPoint,
                            size = sizePerPoint
                        )
                        Log.d("POLYGON_SEARCH", "Grid ($lat, $lng): ${places.size} places")
                        places
                    } catch (e: Exception) {
                        Log.e("POLYGON_SEARCH", "Error at grid ($lat, $lng): ${e.message}")
                        emptyList()
                    }
                }
            }

            searchJobs.awaitAll().forEach { places ->
                allPlaces.addAll(places)
            }
        }

        // 4. 중복 제거
        val uniquePlaces = allPlaces.distinctBy { it.id }

        // 5. 폴리곤 외부 장소 필터링
        val insidePolygon = uniquePlaces.filter { place ->
            isPointInPolygon(place.lat, place.lng, polygonCoords)
        }

        Log.d("POLYGON_SEARCH", "Total: ${allPlaces.size}, Unique: ${uniquePlaces.size}, Inside: ${insidePolygon.size}")
        Log.d("POLYGON_SEARCH", "Sample: ${insidePolygon.take(5).joinToString { it.name }}")

        insidePolygon
    }

    /**
     * 폴리곤 검색 + GPT 재랭크
     *
     * @param filter 사용자 필터
     * @param polygonCoords 폴리곤 좌표
     * @return 재랭크된 추천 결과
     */
    suspend fun recommendPolygonWithGpt(
        filter: FilterState,
        polygonCoords: List<com.example.project_2.data.LatLng>
    ): RecommendationResult = withContext(Dispatchers.IO) {
        val regionName = filter.region.ifBlank { "지역" }
        Log.d("POLYGON_SEARCH", "recommendPolygonWithGpt: region=$regionName, ${polygonCoords.size} coords")

        // 날씨 조회 (폴리곤 중심으로)
        val centerLat = polygonCoords.map { it.lat }.average()
        val centerLng = polygonCoords.map { it.lng }.average()
        val weather = getWeatherByLatLng(centerLat, centerLng).also {
            Log.d("POLYGON_SEARCH", "weather=${it?.condition} ${it?.tempC}C")
        }

        val cats = if (filter.categories.isEmpty()) setOf(Category.FOOD) else filter.categories

        // 폴리곤 내 검색
        val candidates = searchWithinPolygon(
            polygonCoords = polygonCoords,
            categories = cats
        )

        Log.d("POLYGON_SEARCH", "Polygon search returned ${candidates.size} candidates")

        if (candidates.isEmpty()) {
            Log.w("POLYGON_SEARCH", "No candidates found in polygon!")
            return@withContext RecommendationResult(emptyList(), weather)
        }

        // GPT 재랭크
        val out = runCatching {
            reranker.rerankWithReasons(filter, weather, candidates)
        }.onFailure {
            Log.e("POLYGON_SEARCH", "GPT rerank error: ${it.message}", it)
        }.getOrElse {
            GptRerankUseCase.RerankOutput(candidates, emptyMap())
        }

        // 리밸런싱
        val (top, ordered) = rebalanceByCategory(
            candidates = out.places,
            selectedCats = cats,
            minPerCat = 4,
            perCatTop = 1,
            totalCap = null
        )

        RecommendationResult(
            places     = ordered,
            weather    = weather,
            gptReasons = out.reasons,
            topPicks   = top,
            aiTopIds   = out.aiTopIds
        )
    }

    /**
     * Point-in-Polygon 알고리즘 (Ray Casting)
     *
     * @param lat 확인할 점의 위도
     * @param lng 확인할 점의 경도
     * @param polygon 폴리곤 좌표 리스트
     * @return 점이 폴리곤 내부에 있으면 true
     */
    private fun isPointInPolygon(
        lat: Double,
        lng: Double,
        polygon: List<com.example.project_2.data.LatLng>
    ): Boolean {
        if (polygon.size < 3) return false

        var inside = false
        var j = polygon.size - 1

        for (i in polygon.indices) {
            val xi = polygon[i].lng
            val yi = polygon[i].lat
            val xj = polygon[j].lng
            val yj = polygon[j].lat

            val intersect = ((yi > lat) != (yj > lat)) &&
                    (lng < (xj - xi) * (lat - yi) / (yj - yi) + xi)

            if (intersect) inside = !inside
            j = i
        }

        return inside
    }
}
