package com.example.project_2.domain.repo

import com.example.project_2.domain.model.FilterState
import com.example.project_2.domain.model.RecommendationResult
import com.example.project_2.domain.model.WeatherInfo

interface TravelRepository {

    /** 지역 이름(예: "서울")으로 날씨 가져오기 */
    suspend fun getWeather(region: String): WeatherInfo?

    /** 필터 + 날씨 기반 기본 추천 */
    suspend fun recommend(filter: FilterState, weather: WeatherInfo?): RecommendationResult

    /** 위도/경도로 날씨 가져오기 */
    suspend fun getWeatherByLatLng(lat: Double, lng: Double): WeatherInfo?

    /**
     * GPT 재랭크 추천:
     *  - 카카오 API로 후보 수집
     *  - GPT로 랭킹/필터링
     *  - 최종 RecommendationResult 반환 (weather + places)
     */
    suspend fun recommendWithGpt(
        filter: FilterState,
        centerLat: Double,
        centerLng: Double,
        radiusMeters: Int = 2500,
        candidateSize: Int = 15
    ): RecommendationResult
}
