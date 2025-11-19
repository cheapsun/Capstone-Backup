package com.example.project_2.domain.repo

import com.example.project_2.domain.model.FilterState
import com.example.project_2.domain.model.RecommendationResult
import com.example.project_2.domain.model.WeatherInfo

interface TravelRepository {

    /** 지역 이름(예: "서울")으로 날씨 가져오기 */
    suspend fun getWeather(region: String): WeatherInfo?

    /**
     * 필터 + 날씨 기반 기본 추천
     *
     * - FilterState.region  : 도시/광역 단위 (예: "광주")
     * - FilterState.subRegions : 선택한 세부 동네 리스트
     *      예) ["광주 동명동", "광주 상무지구"]
     * - subRegions 가 비어 있으면 구현체(RealTravelRepository)에서 region 하나만 기준으로 검색
     */
    suspend fun recommend(filter: FilterState, weather: WeatherInfo?): RecommendationResult

    /** 위도/경도로 날씨 가져오기 */
    suspend fun getWeatherByLatLng(lat: Double, lng: Double): WeatherInfo?

    /**
     * GPT 재랭크 추천 (단일 중심 좌표 기반):
     *  - centerLat/centerLng 주변에서 카카오 API로 후보 수집
     *  - GPT로 랭킹/필터링
     *  - 최종 RecommendationResult 반환 (weather + places)
     *
     * ※ 여러 동네를 섞어서 검색하는 로직은 기본 recommend(...) 쪽에서 처리하고,
     *   이 함수는 지도 중심 한 지점에서 추천이 필요할 때 사용.
     */
    suspend fun recommendWithGpt(
        filter: FilterState,
        centerLat: Double,
        centerLng: Double,
        radiusMeters: Int = 2500,
        candidateSize: Int = 15
    ): RecommendationResult
}
