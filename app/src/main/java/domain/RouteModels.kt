package com.example.project_2.domain.model

import com.kakao.vectormap.LatLng

/**
 * 이동 수단
 */
enum class TransportMode {
    WALK,      // 도보
    CAR,       // 자동차
    PUBLIC     // 대중교통
}

/**
 * 두 장소 사이의 실제 경로 정보
 * 
 * @param from 출발 장소
 * @param to 도착 장소
 * @param pathCoordinates 실제 도로를 따라가는 좌표 리스트 (Kakao Mobility API의 vertexes)
 * @param distanceMeters 실제 이동 거리 (미터)
 * @param durationSeconds 예상 소요 시간 (초)
 * @param mode 이동 수단
 */
data class RouteSegment(
    val from: Place,
    val to: Place,
    val pathCoordinates: List<LatLng>,  // 실제 도로 좌표들
    val distanceMeters: Int,
    val durationSeconds: Int,
    val mode: TransportMode = TransportMode.WALK
)

/**
 * 전체 루트 정보 (여러 구간의 조합)
 * 
 * @param segments 각 구간별 경로 정보 리스트 (A→B, B→C, C→D, ...)
 * @param totalDistanceMeters 총 이동 거리
 * @param totalDurationSeconds 총 이동 시간
 */
data class FullRoute(
    val segments: List<RouteSegment>,
    val totalDistanceMeters: Int,
    val totalDurationSeconds: Int
) {
    /**
     * 전체 경로의 모든 좌표를 순서대로 반환
     */
    fun getAllCoordinates(): List<LatLng> {
        return segments.flatMap { it.pathCoordinates }
    }
}
