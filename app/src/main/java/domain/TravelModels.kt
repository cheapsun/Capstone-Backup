package com.example.project_2.domain.model

// =====================
// 여행 관련 Enum 정의
// =====================

enum class Category {
    FOOD,       // 맛집
    CAFE,       // 카페
    PHOTO,      // 사진 명소
    CULTURE,    // 문화
    SHOPPING,   // 쇼핑
    HEALING,    // 힐링
    EXPERIENCE, // 체험
    NIGHT,      // 나이트 (Capstone-Backup 고유)
    STAY;       // 숙소

    /** 기본 카테고리 세트 (필요 시 수정 가능) */
    companion object {
        fun defaults(): Set<Category> = setOf(
            FOOD, CAFE, PHOTO, CULTURE, SHOPPING, HEALING, EXPERIENCE, NIGHT, STAY
        )
    }
}

enum class TripDuration { HALF_DAY, DAY, ONE_NIGHT, TWO_NIGHTS }

enum class Companion { SOLO, FRIENDS, COUPLE, FAMILY }

// =====================
// 필터 / 모델 정의
// =====================

/** 메인 검색 필터 상태 */
data class FilterState(
    val region: String = "",
    val categories: Set<Category> = emptySet(),
    val duration: TripDuration = TripDuration.DAY,
    val budgetPerPerson: Int = 30000, // 원(1인)
    val companion: Companion = Companion.SOLO
)

/** 날씨 정보 */
data class WeatherInfo(
    val tempC: Double,
    val condition: String, // Rain, Clear, Clouds ...
    val icon: String? = null
)

/** 장소 정보 */
data class Place(
    val id: String,
    val name: String,
    val category: Category,
    val lat: Double,
    val lng: Double,
    val distanceMeters: Int? = null,   // 중심 기준 거리 (m)
    val rating: Double? = null,        // 별점 (있을 경우)
    val address: String? = null,
    val score: Double? = null          // GPT 재랭크 점수 (추가됨)
)

/** 최종 추천 결과 */
data class RecommendationResult(
    val places: List<Place>,                  // 최종 정렬된 전체 장소 리스트
    val weather: WeatherInfo? = null,         // 날씨 정보 (옵션)
    val gptReasons: Map<String, String> = emptyMap(), // GPT가 이유를 제공할 경우
    val aiTopIds: Set<String> = emptySet(),   // AI 추천 상위 3개 ID (기존 유지)
    val topPicks: List<Place> = emptyList()   // ✅ 카테고리별 Top1 상단 고정용 (신규 추가)
)
