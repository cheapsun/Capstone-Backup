package com.example.project_2.domain.model

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 저장된 루트 데이터 모델
 */
data class SavedRoute(
    val id: String,                          // 고유 ID (타임스탬프 기반)
    val name: String,                        // 사용자가 지정한 루트 이름
    val selectedPlaces: List<Place>,         // 선택된 장소들 (순서대로)
    val routeSegments: List<RouteSegment>,   // T-Map 경로 구간들
    val allRecommendedPlaces: List<Place>,   // 🔹 원본 추천된 모든 장소
    val gptReasons: Map<String, String> = emptyMap(),   // 🔹 GPT 추천 이유
    val topPicks: List<Place> = emptyList(),            // 🔹 카테고리 Top
    val aiTopIds: Set<String> = emptySet(),             // 🔹 AI Top ID
    val createdAt: Long = System.currentTimeMillis()    // 생성 시간
) {
    // 하위 호환성을 위한 속성 (기존 코드와 호환)
    @Deprecated("Use selectedPlaces instead", ReplaceWith("selectedPlaces"))
    val places: List<Place>
        get() = selectedPlaces

    // 총 거리 (미터)
    val totalDistanceMeters: Int
        get() = routeSegments.sumOf { it.distanceMeters }

    // 총 소요 시간 (초)
    val totalDurationSeconds: Int
        get() = routeSegments.sumOf { it.durationSeconds }

    // 총 소요 시간 (시간 단위, 포맷팅)
    fun getTotalDurationFormatted(): String {
        val hours = totalDurationSeconds / 3600
        val minutes = (totalDurationSeconds % 3600) / 60
        return when {
            hours > 0 && minutes > 0 -> "총 ${hours}시간 ${minutes}분"
            hours > 0 -> "총 ${hours}시간"
            else -> "총 ${minutes}분"
        }
    }

    // 총 거리 (포맷팅)
    fun getTotalDistanceFormatted(): String {
        return if (totalDistanceMeters >= 1000) {
            "%.1f km".format(totalDistanceMeters / 1000.0)
        } else {
            "$totalDistanceMeters m"
        }
    }

    companion object {
        // JSON 직렬화/역직렬화
        fun toJson(routes: List<SavedRoute>): String {
            return Gson().toJson(routes)
        }

        fun fromJson(json: String): List<SavedRoute> {
            val type = object : TypeToken<List<SavedRoute>>() {}.type
            return Gson().fromJson(json, type) ?: emptyList()
        }
    }
}
