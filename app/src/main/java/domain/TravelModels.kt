package com.example.project_2.domain.model

enum class Category {
    FOOD, CAFE, PHOTO, CULTURE, SHOPPING, HEALING, EXPERIENCE, NIGHT
}

enum class TripDuration { HALF_DAY, DAY, ONE_NIGHT, TWO_NIGHTS }

enum class Companion { SOLO, FRIENDS, COUPLE, FAMILY }

data class FilterState(
    val region: String = "",
    val categories: Set<Category> = emptySet(),
    val duration: TripDuration = TripDuration.DAY,
    val budgetPerPerson: Int = 30000, // 원
    val companion: Companion = Companion.SOLO
)

data class WeatherInfo(
    val tempC: Double,
    val condition: String,     // Rain, Clear, Clouds...
    val icon: String? = null
)

data class Place(
    val id: String,
    val name: String,
    val category: Category,
    val lat: Double,
    val lng: Double,
    val distanceMeters: Int? = null,
    val rating: Double? = null,
    val address: String? = null
)

data class RecommendationResult(
    val weather: WeatherInfo?,
    val places: List<Place>
)
