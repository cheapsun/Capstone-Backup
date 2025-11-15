package com.example.project_2.ui.result

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.project_2.domain.model.Category
import com.example.project_2.domain.model.FilterState
import com.example.project_2.domain.model.Place
import com.example.project_2.domain.model.WeatherInfo
import com.example.project_2.domain.repo.RealTravelRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SortMode { DEFAULT, NAME, DISTANCE, RATING }

data class ResultUiState(
    val weather: WeatherInfo? = null,
    val allPlaces: List<Place> = emptyList(),
    val visiblePlaces: List<Place> = emptyList(),
    /** 사용자가 '추가'를 누른 순서 유지 (루트 생성 시 그대로 연결) */
    val selectedOrder: List<String> = emptyList(),
    val sortMode: SortMode = SortMode.DEFAULT,
    val query: String = "",
    val maxSelection: Int = 8,
    /** 루트가 그려지도록 트리거 (지도 쪽에서 수신) */
    val routeRequestedAt: Long = 0L,
    /** 🔹 검색 확장 관련 */
    val isExpanding: Boolean = false,
    val hasExpanded: Boolean = false
) {
    val selectedPlaces: List<Place> =
        selectedOrder.mapNotNull { id -> allPlaces.find { it.id == id } }
    val selectedCount: Int get() = selectedOrder.size
}

class ResultViewModel(
    private val repo: RealTravelRepository? = null
) : ViewModel() {

    private val TAG = "ResultVM"

    private val _ui = MutableStateFlow(ResultUiState())
    val ui: StateFlow<ResultUiState> = _ui.asStateFlow()

    // 검색 확장에 필요한 정보 저장
    private var centerLat: Double = 0.0
    private var centerLng: Double = 0.0
    private var categories: Set<Category> = emptySet()
    private var currentRadius = 3000

    fun setData(
        places: List<Place>,
        weather: WeatherInfo?,
        centerLat: Double = 0.0,
        centerLng: Double = 0.0,
        categories: Set<Category> = emptySet()
    ) {
        this.centerLat = centerLat
        this.centerLng = centerLng
        this.categories = categories.ifEmpty { setOf(Category.FOOD) }

        _ui.update {
            it.copy(
                allPlaces = places,
                visiblePlaces = places, // 필터링/검색 있으면 여기 반영
                weather = weather
            )
        }
    }

    /** '추가' 버튼 */
    fun addPlace(place: Place) {
        _ui.update { state ->
            if (state.selectedOrder.contains(place.id) || state.selectedOrder.size >= state.maxSelection) state
            else state.copy(selectedOrder = state.selectedOrder + place.id)
        }
    }

    /** 선택 취소(옵션) */
    fun removePlace(placeId: String) {
        _ui.update { it.copy(selectedOrder = it.selectedOrder.filterNot { id -> id == placeId }) }
    }

    /** 전체 초기화(옵션) */
    fun clearSelection() {
        _ui.update { it.copy(selectedOrder = emptyList()) }
    }

    /** 루트 생성 버튼 */
    fun requestRoute() {
        _ui.update { it.copy(routeRequestedAt = System.currentTimeMillis()) }
    }

    /**
     * 🔍 검색 범위 확장 ("더 많은 장소 보기")
     *
     * - 현재 반경을 1.5배로 확장 (최대 10km)
     * - 기존 장소는 제외하고 새로운 장소만 추가
     * - 한 번만 실행 가능
     */
    fun expandSearch() {
        val repository = repo ?: run {
            Log.w(TAG, "Repository not provided, cannot expand search")
            return
        }

        if (_ui.value.hasExpanded) {
            Log.w(TAG, "Already expanded, cannot expand again")
            return
        }

        if (_ui.value.isExpanding) {
            Log.w(TAG, "Expansion already in progress")
            return
        }

        viewModelScope.launch {
            _ui.update { it.copy(isExpanding = true) }
            Log.d(TAG, "🔍 검색 범위 확장 시작 (현재 반경: ${currentRadius}m)")

            try {
                // 새 반경 계산 (1.5배, 최대 10km)
                val newRadius = kotlin.math.min(10_000, (currentRadius * 1.5).toInt())
                Log.d(TAG, "새 반경: ${newRadius}m")

                // 기존 장소 ID 수집
                val currentIds = _ui.value.allPlaces.map { it.id }.toSet()
                Log.d(TAG, "제외할 기존 장소: ${currentIds.size}개")

                // Repository의 expandSearch 호출
                val newPlaces = repository.expandSearch(
                    centerLat = centerLat,
                    centerLng = centerLng,
                    categories = categories,
                    newRadius = newRadius,
                    excludeIds = currentIds
                )

                Log.d(TAG, "✅ 새로운 장소 발견: ${newPlaces.size}개")

                if (newPlaces.isNotEmpty()) {
                    // 기존 결과에 새 장소 추가
                    val mergedPlaces = _ui.value.allPlaces + newPlaces
                    _ui.update {
                        it.copy(
                            allPlaces = mergedPlaces,
                            visiblePlaces = mergedPlaces,
                            hasExpanded = true,
                            isExpanding = false
                        )
                    }

                    Log.d(TAG, "총 장소 수: ${mergedPlaces.size}개 (기존 ${currentIds.size} + 신규 ${newPlaces.size})")
                    currentRadius = newRadius
                } else {
                    Log.w(TAG, "확장된 범위에서 새로운 장소를 찾지 못했습니다")
                    _ui.update { it.copy(isExpanding = false, hasExpanded = true) }
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ 검색 확장 실패: ${e.message}", e)
                _ui.update { it.copy(isExpanding = false) }
            }
        }
    }

    /**
     * 확장 가능 여부 확인
     */
    fun canExpand(): Boolean = !_ui.value.hasExpanded && !_ui.value.isExpanding && repo != null
}

