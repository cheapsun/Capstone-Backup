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

enum class TripDuration {
    HALF_DAY, DAY, ONE_NIGHT, TWO_NIGHTS;

    /** 일정 생성용 일수 변환 */
    fun toDays(): Int = when (this) {
        HALF_DAY -> 1
        DAY -> 1
        ONE_NIGHT -> 2
        TWO_NIGHTS -> 3
    }
}

enum class Companion { SOLO, FRIENDS, COUPLE, FAMILY }

// =====================
// 필터 / 모델 정의
// =====================

/** 메인 검색 필터 상태 */
data class FilterState(
    // 🔹 도시/광역 단위 (예: "서울", "광주", "부산"...)
    val region: String = "",

    // 🔹 선택한 세부 동네들 (예: ["광주 동명동", "광주 상무지구"])
    //    - 아무것도 없으면 서버/레포지토리 쪽에서 `region` 하나만 기준으로 검색
    val subRegions: List<String> = emptyList(),

    val categories: Set<Category> = emptySet(),
    val duration: TripDuration = TripDuration.DAY,
    val budgetPerPerson: Int = 30000, // 원(1인)
    val companion: Companion = Companion.SOLO,
    val extraNote: String = "",

    // app-feature-logic에서 추가
    val numberOfPeople: Int = 1,           // 인원수
    val mandatoryPlace: String = ""        // 필수 방문 장소 (선택)
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
    val score: Double? = null,         // GPT 재랭크 점수 (추가됨)
    // ✅ 새로 추가 (기본값 꼭 넣기!)
    val naverBlogCount: Int? = null,
    val naverScore: Double? = null,
    val naverPopularityScore: Double? = null
)

/** 최종 추천 결과 */
data class RecommendationResult(
    val places: List<Place>,                  // 최종 정렬된 전체 장소 리스트
    val weather: WeatherInfo? = null,         // 날씨 정보 (옵션)
    val gptReasons: Map<String, String> = emptyMap(), // GPT가 이유를 제공할 경우
    val aiTopIds: Set<String> = emptySet(),   // AI 추천 상위 3개 ID (기존 유지)
    val topPicks: List<Place> = emptyList()   // ✅ 카테고리별 Top1 상단 고정용 (신규 추가)
)
