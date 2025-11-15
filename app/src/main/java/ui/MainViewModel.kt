package com.example.project_2.ui.main

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.project_2.data.KakaoLocalService
import com.example.project_2.domain.model.*
import com.example.project_2.domain.repo.TravelRepository
import com.example.project_2.domain.repo.RealTravelRepository
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
    val showAutoComplete: Boolean = false,  // 자동완성 표시 여부
    // 🔹 검색 확장을 위한 마지막 검색 정보
    val lastSearchCenter: Pair<Double, Double>? = null,  // (lat, lng)
    val lastSearchCategories: Set<Category> = emptySet(),
    // 🔹 지역 선택 BottomSheet
    val showRegionSelectSheet: Boolean = false,
    // 🔹 선택된 지역의 폴리곤 정보
    val regionPolygons: List<com.example.project_2.data.AdminPolygon> = emptyList()
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

    /** "맞춤 루트 생성하기" → 폴리곤 기반 또는 중심점 검색 */
    fun onSearchClicked() {
        if (searchInFlight) {
            Log.w(TAG, "onSearchClicked: already searching, ignored")
            return
        }
        searchInFlight = true

        val f0 = _ui.value.filter
        var polygons = _ui.value.regionPolygons

        viewModelScope.launch {
            Log.d(TAG, "onSearchClicked: start, filter=$f0, polygons=${polygons.size}")
            _ui.update { it.copy(loading = true, error = null) }

            runCatching {
                val region = f0.region.ifBlank { "서울" }
                Log.d(TAG, "Region: $region")

                val cats = if (f0.categories.isEmpty()) setOf(Category.FOOD) else f0.categories
                val f = f0.copy(categories = cats, region = region)

                // ✅ 폴리곤이 없으면 VWorld API로 조회 시도
                if (polygons.isEmpty()) {
                    Log.d(TAG, "No polygon saved, fetching from VWorld API for: $region")
                    try {
                        val fetchedPolygons = com.example.project_2.data.VWorldService.getAdminBoundary(region)
                        if (fetchedPolygons.isNotEmpty()) {
                            Log.d(TAG, "Fetched ${fetchedPolygons.size} polygons from VWorld")
                            polygons = fetchedPolygons
                        } else {
                            Log.d(TAG, "No polygons found for: $region")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to fetch polygons: ${e.message}", e)
                        // 실패하면 polygons는 비어있는 상태로 유지
                    }
                }

                // ✅ 폴리곤이 있으면 폴리곤 기반 검색, 없으면 중심점 검색
                if (polygons.isNotEmpty()) {
                    Log.d(TAG, "Using polygon-based search with ${polygons.size} polygons")

                    // 모든 폴리곤의 좌표를 하나의 리스트로 합침
                    val allCoords = polygons.flatMap { it.coordinates }

                    val realRepo = repo as? RealTravelRepository
                        ?: error("Repository does not support polygon search")

                    realRepo.recommendPolygonWithGpt(filter = f, polygonCoords = allCoords)
                } else {
                    Log.d(TAG, "Using center-based search (no polygon found)")

                    // 지역 좌표 조회
                    val center = KakaoLocalService.geocode(region)
                        ?: KakaoLocalService.geocode("서울")
                        ?: error("지역 좌표를 찾을 수 없습니다: $region")

                    val (lat, lng) = center
                    Log.d(TAG, "Geocode result: ($lat, $lng)")

                    // 단일 중심점 검색 (3km 반경)
                    repo.recommendWithGpt(
                        filter = f,
                        centerLat = lat,
                        centerLng = lng,
                        radiusMeters = 3000,
                        candidateSize = 15
                    )
                }
            }.onSuccess { res ->
                Log.d(TAG, "onSearchClicked: success, updating UI with ${res.places.size} places")
                // 결과와 함께 검색 정보도 저장 (확장 검색에 사용)
                _ui.update {
                    it.copy(
                        loading = false,
                        lastResult = res,
                        lastSearchCenter = res.places.firstOrNull()?.let { p -> p.lat to p.lng },
                        lastSearchCategories = f0.categories.ifEmpty { setOf(Category.FOOD) }
                    )
                }
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

        // 자동완성 트리거 (2글자 이상일 때)
        if (region.length >= 2) {
            triggerAutoComplete(region)
        } else {
            // 2글자 미만이면 자동완성 숨김
            _ui.update { it.copy(showAutoComplete = false, autoCompleteSuggestions = emptyList()) }
        }
    }

    /**
     * 지역명과 폴리곤 정보를 함께 설정 (지도에서 선택 시)
     */
    fun setRegionWithPolygon(region: String, polygons: List<com.example.project_2.data.AdminPolygon>) {
        Log.d(TAG, "setRegionWithPolygon: $region, ${polygons.size} polygons")
        _ui.update {
            it.copy(
                filter = it.filter.copy(region = region),
                regionPolygons = polygons,
                showAutoComplete = false
            )
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

            regionNames
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

    // ===== 지역 선택 BottomSheet 관련 =====

    /**
     * 지역 선택 BottomSheet 표시
     */
    fun showRegionSelectSheet() {
        Log.d(TAG, "showRegionSelectSheet")
        _ui.update { it.copy(showRegionSelectSheet = true) }
    }

    /**
     * 지역 선택 BottomSheet 숨기기
     */
    fun hideRegionSelectSheet() {
        Log.d(TAG, "hideRegionSelectSheet")
        _ui.update { it.copy(showRegionSelectSheet = false) }
    }

    /**
     * 전체 지역 검색 (폴리곤 기반)
     *
     * @param regionName 지역명 (예: "광주광역시 동구")
     * @param polygon 폴리곤 좌표 리스트
     */
    fun onWholeRegionSearch(
        regionName: String,
        polygon: List<com.example.project_2.data.LatLng>
    ) {
        if (searchInFlight) {
            Log.w(TAG, "onWholeRegionSearch: already searching, ignored")
            return
        }
        searchInFlight = true

        val f0 = _ui.value.filter
        viewModelScope.launch {
            Log.d(TAG, "onWholeRegionSearch: $regionName, ${polygon.size} coords")
            _ui.update { it.copy(loading = true, error = null, showRegionSelectSheet = false) }

            runCatching {
                val cats = if (f0.categories.isEmpty()) setOf(Category.FOOD) else f0.categories
                val f = f0.copy(categories = cats, region = regionName)

                val realRepo = repo as? RealTravelRepository
                    ?: error("Repository does not support polygon search")

                realRepo.recommendPolygonWithGpt(filter = f, polygonCoords = polygon)
            }.onSuccess { res ->
                Log.d(TAG, "onWholeRegionSearch: success, ${res.places.size} places")
                _ui.update {
                    it.copy(
                        loading = false,
                        lastResult = res,
                        lastSearchCenter = res.places.firstOrNull()?.let { p -> p.lat to p.lng },
                        lastSearchCategories = f0.categories.ifEmpty { setOf(Category.FOOD) }
                    )
                }
                searchInFlight = false
            }.onFailure { e ->
                Log.e(TAG, "onWholeRegionSearch: failed → ${e.message}", e)
                _ui.update { it.copy(loading = false, error = e.message ?: "검색 실패") }
                searchInFlight = false
            }
        }
    }

    /**
     * 특정 위치 주변 검색 (8km 반경)
     *
     * @param regionName 지역명 (예: "광주광역시 동구 충장동")
     * @param centerLat 중심 위도
     * @param centerLng 중심 경도
     */
    fun onRadiusSearch(
        regionName: String,
        centerLat: Double,
        centerLng: Double
    ) {
        if (searchInFlight) {
            Log.w(TAG, "onRadiusSearch: already searching, ignored")
            return
        }
        searchInFlight = true

        val f0 = _ui.value.filter
        viewModelScope.launch {
            Log.d(TAG, "onRadiusSearch: $regionName at ($centerLat, $centerLng)")
            _ui.update { it.copy(loading = true, error = null, showRegionSelectSheet = false) }

            runCatching {
                val cats = if (f0.categories.isEmpty()) setOf(Category.FOOD) else f0.categories
                val f = f0.copy(categories = cats, region = regionName)

                repo.recommendWithGpt(
                    filter = f,
                    centerLat = centerLat,
                    centerLng = centerLng,
                    radiusMeters = 8000,  // 8km 반경
                    candidateSize = 15
                )
            }.onSuccess { res ->
                Log.d(TAG, "onRadiusSearch: success, ${res.places.size} places")
                _ui.update {
                    it.copy(
                        loading = false,
                        lastResult = res,
                        lastSearchCenter = centerLat to centerLng,
                        lastSearchCategories = f0.categories.ifEmpty { setOf(Category.FOOD) }
                    )
                }
                searchInFlight = false
            }.onFailure { e ->
                Log.e(TAG, "onRadiusSearch: failed → ${e.message}", e)
                _ui.update { it.copy(loading = false, error = e.message ?: "검색 실패") }
                searchInFlight = false
            }
        }
    }
}
