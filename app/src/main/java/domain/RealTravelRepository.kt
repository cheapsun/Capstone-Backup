package com.example.project_2.domain.repo

import android.util.Log
import com.example.project_2.data.KakaoLocalService
import com.example.project_2.data.weather.WeatherService
import com.example.project_2.domain.GptRerankUseCase
import com.example.project_2.domain.model.*
import kotlinx.coroutines.Dispatchers
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
}
