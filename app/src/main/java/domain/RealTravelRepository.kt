package com.example.project_2.domain.repo

import com.example.project_2.data.KakaoLocalService
import com.example.project_2.data.weather.WeatherService
import com.example.project_2.domain.GptRerankUseCase
import com.example.project_2.domain.model.*

class RealTravelRepository : TravelRepository {

    // 지역 문자열(예: "서울") → 좌표 → 날씨
    override suspend fun getWeather(region: String): WeatherInfo? {
        val center = KakaoLocalService.geocode(region) ?: return null
        return getWeatherByLatLng(center.first, center.second)
    }

    // 위경도 → 날씨
    override suspend fun getWeatherByLatLng(lat: Double, lng: Double): WeatherInfo? {
        val w = WeatherService.currentByLatLng(lat, lng) ?: return null
        return WeatherInfo(
            tempC = w.tempC,
            condition = w.condition,
            icon = w.icon
        )
    }

    // 간단 추천
    override suspend fun recommend(
        filter: FilterState,
        weather: WeatherInfo?
    ): RecommendationResult {
        val regionText = filter.region.ifBlank { "서울" }

        val center = KakaoLocalService.geocode(regionText)
            ?: KakaoLocalService.geocode("서울")
            ?: return RecommendationResult(weather, emptyList())

        val candidates: List<Place> =
            if (filter.categories.isNotEmpty()) {
                // 카테고리 기반
                KakaoLocalService.searchByCategories(
                    centerLat = center.first,
                    centerLng = center.second,
                    categories = filter.categories,
                    radiusMeters = 2000,
                    size = 10
                )
            } else {
                // 키워드 기반(기본값: "카페")
                KakaoLocalService.searchByKeyword(
                    centerLat = center.first,
                    centerLng = center.second,
                    keyword = "카페",
                    radiusMeters = 2000,
                    size = 10
                )
            }

        return RecommendationResult(weather, candidates)
    }

    // GPT 재랭크 기반 추천
    override suspend fun recommendWithGpt(
        filter: FilterState,
        centerLat: Double,
        centerLng: Double,
        radiusMeters: Int,
        candidateSize: Int
    ): RecommendationResult {

        val weather = getWeatherByLatLng(centerLat, centerLng)

        val candidates: List<Place> =
            if (filter.categories.isNotEmpty()) {
                KakaoLocalService.searchByCategories(
                    centerLat = centerLat,
                    centerLng = centerLng,
                    categories = filter.categories,
                    radiusMeters = radiusMeters,
                    size = candidateSize
                )
            } else {
                KakaoLocalService.searchByKeyword(
                    centerLat = centerLat,
                    centerLng = centerLng,
                    keyword = "카페",
                    radiusMeters = radiusMeters,
                    size = candidateSize
                )
            }

        if (candidates.isEmpty()) {
            return RecommendationResult(weather, emptyList())
        }

        // GPT 재랭크 (실패 시 내부 폴백)
        return GptRerankUseCase.rerank(
            filter = filter,
            candidates = candidates,
            weather = weather
        )
    }
}
