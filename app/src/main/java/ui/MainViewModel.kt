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
    val lastResult: RecommendationResult? = null,
    // 일정 생성용 선택된 장소
    val selectedPlacesForItinerary: List<Place> = emptyList(),
    // 식사 시간 자동 추가 여부
    val autoAddMeals: Boolean = false,
    // 지도에 표시할 일정
    val currentItineraryForMap: Itinerary? = null
)

class MainViewModel(
    private val repo: TravelRepository
) : ViewModel() {

    private val _ui = MutableStateFlow(MainUiState())
    val ui: StateFlow<MainUiState> = _ui.asStateFlow()

    private val TAG = "MainVM"
    private var searchInFlight = false

    /** 필터 전체 교체 */
    fun updateFilter(newFilter: FilterState) {
        Log.d(TAG, "updateFilter: $newFilter")
        _ui.update { it.copy(filter = newFilter) }
    }

    /** ✅ 세부사항(메모) 업데이트 */
    fun setExtraNote(note: String) {
        Log.d(TAG, "setExtraNote: $note")
        _ui.update { state ->
            state.copy(
                filter = state.filter.copy(
                    extraNote = note
                )
            )
        }
    }

    /** ✅ 동네(홍대/연남동, 강남역, 성수동 등) 토글 */
    fun toggleSubRegion(sub: String) {
        _ui.update { state ->
            val cur = state.filter.subRegions.toMutableList()
            if (cur.contains(sub)) {
                cur.remove(sub)
            } else {
                cur.add(sub)
            }
            Log.d(TAG, "toggleSubRegion: $sub → $cur")
            state.copy(filter = state.filter.copy(subRegions = cur))
        }
    }

    /** ✅ 도시 + subRegions 를 합쳐서 검색용 region 문자열 생성 */
    private fun buildRegionForSearch(filter: FilterState): String {
        val city = filter.region.ifBlank { "서울" }

        // 동네를 하나도 안 골랐으면 도시만 사용
        if (filter.subRegions.isEmpty()) return city

        // ["성수동", "홍대/연남동"] -> "서울 성수동, 서울 홍대/연남동"
        return filter.subRegions.joinToString(", ") { sub ->
            if (sub.startsWith(city)) sub else "$city $sub"
        }
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
            val regionText = buildRegionForSearch(f0)
            val cats = if (f0.categories.isEmpty()) setOf(Category.FOOD) else f0.categories
            val f = f0.copy(region = regionText, categories = cats)

            Log.d(TAG, "onSearchClicked: start, filter=$f")
            _ui.update { it.copy(loading = true, error = null) }

            runCatching {
                Log.d(TAG, "geocode start: region=$regionText")

                val center = KakaoLocalService.geocode(regionText)
                    ?: KakaoLocalService.geocode("서울")
                    ?: error("지역 좌표를 찾을 수 없습니다: $regionText")

                val (lat, lng) = center

                Log.d(
                    TAG,
                    "recommendWithGpt call → region=$regionText, lat=$lat, lng=$lng, cats=$cats, extraNote=${f.extraNote}"
                )

                val result = repo.recommendWithGpt(
                    filter = f,
                    centerLat = lat,
                    centerLng = lng,
                    radiusMeters = max(1500, 2500),
                    candidateSize = 15
                )

                Log.d(
                    TAG,
                    "recommendWithGpt returned: places=${result.places.size}, " +
                            "first=${result.places.firstOrNull()?.name ?: "none"}"
                )

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
        val f0 = _ui.value.filter
        val regionText = buildRegionForSearch(f0)
        val f = f0.copy(region = regionText)

        viewModelScope.launch {
            Log.d(TAG, "buildRecommendation start: filter=$f")
            _ui.update { it.copy(loading = true, error = null) }
            runCatching {
                Log.d(TAG, "getWeather + recommend start: region=$regionText")
                val weather = repo.getWeather(regionText)
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

    /** ✅ 도시 변경 시, 이전 동네 선택/결과 초기화 */
    fun setRegion(region: String) {
        Log.d(TAG, "setRegion: $region")
        _ui.update { state ->
            val oldRegion = state.filter.region
            if (oldRegion == region) {
                state
            } else {
                Log.d(TAG, "setRegion: city changed $oldRegion -> $region, clear subRegions & lastResult")
                state.copy(
                    filter = state.filter.copy(
                        region = region,
                        subRegions = emptyList()
                    ),
                    lastResult = null
                )
            }
        }
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

    // ========== 인원수 관련 ==========
    fun setNumberOfPeople(n: Int) {
        Log.d(TAG, "setNumberOfPeople: $n")
        _ui.update { it.copy(filter = it.filter.copy(numberOfPeople = n.coerceIn(1, 10))) }
    }

    fun increasePeople() {
        val current = _ui.value.filter.numberOfPeople
        setNumberOfPeople(current + 1)
    }

    fun decreasePeople() {
        val current = _ui.value.filter.numberOfPeople
        setNumberOfPeople(current - 1)
    }

    // ========== 필수 장소 관련 ==========
    fun setMandatoryPlace(text: String) {
        Log.d(TAG, "setMandatoryPlace: $text")
        _ui.update { it.copy(filter = it.filter.copy(mandatoryPlace = text)) }
    }

    // ========== 일정 생성 관련 ==========
    fun setSelectedPlacesForItinerary(places: List<Place>) {
        Log.d(TAG, "setSelectedPlacesForItinerary: ${places.size} places")
        _ui.update { it.copy(selectedPlacesForItinerary = places) }
    }

    fun setAutoAddMeals(autoAdd: Boolean) {
        Log.d(TAG, "setAutoAddMeals: $autoAdd")
        _ui.update { it.copy(autoAddMeals = autoAdd) }
    }

    fun setCurrentItineraryForMap(itinerary: Itinerary) {
        Log.d(TAG, "setCurrentItineraryForMap: ${itinerary.days.size} days")
        _ui.update { it.copy(currentItineraryForMap = itinerary) }
    }
}
