package com.example.project_2.ui.main

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.project_2.data.KakaoLocalService
import com.example.project_2.domain.model.*
import com.example.project_2.domain.repo.TravelRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max

data class MainUiState(
    val filter: FilterState = FilterState(),
    val loading: Boolean = false,
    val error: String? = null,
    val lastResult: RecommendationResult? = null
)

class MainViewModel(
    private val repo: TravelRepository
) : ViewModel() {

    private val _ui = MutableStateFlow(MainUiState())
    val ui: StateFlow<MainUiState> = _ui.asStateFlow()

    private val TAG = "MainVM"
    private var searchInFlight = false

    /** 필터 업데이트 로그 */
    fun updateFilter(newFilter: FilterState) {
        Log.d(TAG, "updateFilter: $newFilter")
        _ui.update { it.copy(filter = newFilter) }
    }

    /** "맞춤 루트 생성하기" → GPT 재랭크 */
    fun onSearchClicked() {
        if (searchInFlight) {
            Log.w(TAG, "onSearchClicked: already searching, ignored")
            return
        }
        searchInFlight = true

        val f0 = _ui.value.filter
        viewModelScope.launch {
            Log.d(TAG, "onSearchClicked: start, filter=$f0")
            _ui.update { it.copy(loading = true, error = null) }

            runCatching {
                val region = f0.region.ifBlank { "서울" }
                Log.d(TAG, "geocode start: region=$region")

                val center = KakaoLocalService.geocode(region)
                    ?: KakaoLocalService.geocode("서울")
                    ?: error("지역 좌표를 찾을 수 없습니다: $region")

                val (lat, lng) = center
                val cats = if (f0.categories.isEmpty()) setOf(Category.FOOD) else f0.categories
                val f = f0.copy(categories = cats)

                Log.d(TAG, "recommendWithGpt call → region=$region, lat=$lat, lng=$lng, cats=$cats")

                // ✅ 실제 GPT 재랭크 호출
                val result = repo.recommendWithGpt(
                    filter = f,
                    centerLat = lat,
                    centerLng = lng,
                    radiusMeters = max(1500, 2500),
                    candidateSize = 15
                )

                Log.d(TAG, "recommendWithGpt returned: places=${result.places.size}, " +
                        "first=${result.places.firstOrNull()?.name ?: "none"}")

                result
            }.onSuccess { res ->
                Log.d(TAG, "onSearchClicked: success, updating UI with ${res.places.size} places")
                _ui.update { it.copy(loading = false, lastResult = res) }
                searchInFlight = false
            }.onFailure { e ->
                Log.e(TAG, "onSearchClicked: failed → ${e.message}", e)
                _ui.update { it.copy(loading = false, error = e.message ?: "추천 실패") }
                searchInFlight = false
            }
        }
    }

    /** 기본 추천 (GPT 없이) */
    fun buildRecommendation() {
        val f = _ui.value.filter
        viewModelScope.launch {
            Log.d(TAG, "buildRecommendation start: filter=$f")
            _ui.update { it.copy(loading = true, error = null) }
            runCatching {
                val region = f.region.ifBlank { "서울" }
                Log.d(TAG, "getWeather + recommend start: region=$region")
                val weather = repo.getWeather(region)
                repo.recommend(filter = f, weather = weather)
            }.onSuccess { res ->
                Log.d(TAG, "buildRecommendation success: ${res.places.size} places")
                _ui.update { it.copy(loading = false, lastResult = res) }
            }.onFailure { e ->
                Log.e(TAG, "buildRecommendation failed: ${e.message}", e)
                _ui.update { it.copy(loading = false, error = e.message ?: "추천 실패") }
            }
        }
    }

    fun toggleCategory(category: Category) {
        _ui.update { state ->
            val current = state.filter.categories
            val newCats =
                if (current.contains(category)) current - category else current + category
            Log.d(TAG, "toggleCategory: $category → new=$newCats")
            state.copy(filter = state.filter.copy(categories = newCats))
        }
    }

    fun setRegion(region: String) {
        Log.d(TAG, "setRegion: $region")
        _ui.update { it.copy(filter = it.filter.copy(region = region)) }
    }

    fun setDuration(duration: TripDuration) {
        Log.d(TAG, "setDuration: $duration")
        _ui.update { it.copy(filter = it.filter.copy(duration = duration)) }
    }

    fun setBudget(budgetPerPerson: Int) {
        Log.d(TAG, "setBudget: $budgetPerPerson")
        _ui.update { it.copy(filter = it.filter.copy(budgetPerPerson = budgetPerPerson)) }
    }

    fun setCompanion(companion: Companion) {
        Log.d(TAG, "setCompanion: $companion")
        _ui.update { it.copy(filter = it.filter.copy(companion = companion)) }
    }

    fun consumeResult() {
        Log.d(TAG, "consumeResult (결과 초기화)")
        _ui.update { it.copy(lastResult = null) }
    }
}
