package com.example.project_2.ui.main

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.project_2.data.KakaoLocalService
import com.example.project_2.domain.model.*
import com.example.project_2.domain.repo.TravelRepository
import com.example.project_2.domain.repo.RealTravelRepository
import com.example.project_2.domain.SubRegionsData
import com.example.project_2.domain.SearchType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val autoCompleteSuggestions: List<String> = emptyList(),  // 자동완성 제안
    val showAutoComplete: Boolean = false  // 자동완성 표시 여부
)

class MainViewModel(
    private val repo: TravelRepository
) : ViewModel() {

    private val _ui = MutableStateFlow(MainUiState())
    val ui: StateFlow<MainUiState> = _ui.asStateFlow()

    private val TAG = "MainVM"
    private var searchInFlight = false
    private var autoCompleteJob: Job? = null  // 자동완성 디바운싱용

    /** 필터 업데이트 로그 */
    fun updateFilter(newFilter: FilterState) {
        Log.d(TAG, "updateFilter: $newFilter")
        _ui.update { it.copy(filter = newFilter) }
    }

    /** "맞춤 루트 생성하기" → WIDE/NARROW 판단 후 GPT 재랭크 */
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
                Log.d(TAG, "Region: $region")

                // ===== STEP 1: WIDE/NARROW 판단 =====
                val searchType = SubRegionsData.determineSearchType(region)
                Log.d(TAG, "Search type determined: $searchType")

                val cats = if (f0.categories.isEmpty()) setOf(Category.FOOD) else f0.categories
                val f = f0.copy(categories = cats, region = region)

                // ===== STEP 2: 검색 전략 실행 =====
                when (searchType) {
                    SearchType.WIDE -> {
                        // 다중 중심점 검색
                        Log.d(TAG, "Executing WIDE search strategy")

                        val subRegions = SubRegionsData.getSubRegions(region)
                        if (subRegions == null) {
                            Log.w(TAG, "No sub-regions found for $region, fallback to NARROW")
                            executeNarrowSearch(f)
                        } else {
                            executeWideSearch(f, subRegions)
                        }
                    }
                    SearchType.NARROW -> {
                        // 단일 중심점 검색
                        Log.d(TAG, "Executing NARROW search strategy")
                        executeNarrowSearch(f)
                    }
                }
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

    /**
     * WIDE 검색 전략 실행 (다중 중심점)
     */
    private suspend fun executeWideSearch(
        filter: FilterState,
        subRegions: List<String>
    ): RecommendationResult {
        Log.d(TAG, "executeWideSearch: subRegions=$subRegions")

        // RealTravelRepository로 캐스팅 (recommendWideWithGpt 메서드 사용)
        val realRepo = repo as? RealTravelRepository
            ?: error("Repository does not support WIDE search")

        return realRepo.recommendWideWithGpt(
            filter = filter,
            subRegions = subRegions,
            radiusMeters = 3000,
            candidateSizePerRegion = 5
        )
    }

    /**
     * NARROW 검색 전략 실행 (단일 중심점)
     */
    private suspend fun executeNarrowSearch(filter: FilterState): RecommendationResult {
        val region = filter.region
        Log.d(TAG, "executeNarrowSearch: region=$region")

        val center = KakaoLocalService.geocode(region)
            ?: KakaoLocalService.geocode("서울")
            ?: error("지역 좌표를 찾을 수 없습니다: $region")

        val (lat, lng) = center
        Log.d(TAG, "Geocode result: ($lat, $lng)")

        return repo.recommendWithGpt(
            filter = filter,
            centerLat = lat,
            centerLng = lng,
            radiusMeters = 3000,
            candidateSize = 15
        )
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

        // 자동완성 트리거 (2글자 이상일 때)
        if (region.length >= 2) {
            triggerAutoComplete(region)
        } else {
            // 2글자 미만이면 자동완성 숨김
            _ui.update { it.copy(showAutoComplete = false, autoCompleteSuggestions = emptyList()) }
        }
    }

    /**
     * 자동완성 트리거 (디바운싱 적용)
     * 300ms 대기 후 API 호출
     */
    private fun triggerAutoComplete(query: String) {
        // 기존 Job 취소
        autoCompleteJob?.cancel()

        autoCompleteJob = viewModelScope.launch {
            delay(300)  // 300ms 디바운싱
            getAutoCompleteSuggestions(query)
        }
    }

    /**
     * Kakao Keyword API로 자동완성 제안 가져오기
     */
    private suspend fun getAutoCompleteSuggestions(query: String) {
        Log.d(TAG, "getAutoCompleteSuggestions: $query")

        val suggestions = try {
            // Kakao Keyword API 호출
            val places = KakaoLocalService.searchByKeyword(
                centerLat = 37.5665,  // 서울 중심 (기본값)
                centerLng = 126.9780,
                keyword = query,
                radiusMeters = 20000,
                size = 10
            )

            // place_name과 address_name에서 지역명 추출
            val regionNames = places.mapNotNull { place ->
                // "부산광역시 해운대구" → "부산 해운대"
                extractRegionName(place.address ?: "")
            }.distinct().take(5)

            // 광역 도시 목록도 함께 제공
            val wideRegions = SubRegionsData.getAllWideRegions()
                .filter { it.contains(query, ignoreCase = true) }
                .take(3)

            (wideRegions + regionNames).distinct().take(5)
        } catch (e: Exception) {
            Log.e(TAG, "AutoComplete error: ${e.message}", e)
            emptyList()
        }

        Log.d(TAG, "Suggestions: $suggestions")
        _ui.update {
            it.copy(
                autoCompleteSuggestions = suggestions,
                showAutoComplete = suggestions.isNotEmpty()
            )
        }
    }

    /**
     * 주소에서 지역명 추출
     * "부산광역시 해운대구 우동" → "부산 해운대"
     */
    private fun extractRegionName(address: String): String? {
        if (address.isBlank()) return null

        val parts = address.split(" ")
        if (parts.isEmpty()) return null

        // 1depth (시도)
        val depth1 = parts[0].replace("광역시", "").replace("특별시", "")
            .replace("특별자치시", "").replace("도", "")

        // 2depth (구군)
        val depth2 = if (parts.size > 1) {
            parts[1].replace("구", "").replace("군", "")
        } else null

        return if (depth2 != null) {
            "$depth1 $depth2"
        } else {
            depth1
        }
    }

    /**
     * 자동완성 선택
     */
    fun selectAutoComplete(suggestion: String) {
        Log.d(TAG, "selectAutoComplete: $suggestion")
        _ui.update {
            it.copy(
                filter = it.filter.copy(region = suggestion),
                showAutoComplete = false
            )
        }
    }

    /**
     * 자동완성 닫기
     */
    fun hideAutoComplete() {
        _ui.update { it.copy(showAutoComplete = false) }
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

    /**
     * ResultScreen에서 사용할 검색 확장 콜백 생성
     *
     * @param centerLat 검색 중심 위도
     * @param centerLng 검색 중심 경도
     * @param categories 검색 카테고리
     * @return 검색 확장 suspend 람다
     */
    fun createExpandSearchCallback(
        centerLat: Double,
        centerLng: Double,
        categories: Set<Category>
    ): suspend (excludeIds: Set<String>) -> List<Place> {
        return { excludeIds: Set<String> ->
            val realRepo = repo as? RealTravelRepository
                ?: error("Repository does not support expand search")

            Log.d(TAG, "Expand search callback called: excludeIds=${excludeIds.size}")

            realRepo.expandSearch(
                centerLat = centerLat,
                centerLng = centerLng,
                categories = categories.ifEmpty { setOf(Category.FOOD) },
                newRadius = 5000,  // 5km로 확장
                excludeIds = excludeIds
            )
        }
    }
}
