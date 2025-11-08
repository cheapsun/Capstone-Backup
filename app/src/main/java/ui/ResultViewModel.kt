package com.example.project_2.ui.result

import androidx.lifecycle.ViewModel
import com.example.project_2.domain.model.Place
import com.example.project_2.domain.model.WeatherInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
    val routeRequestedAt: Long = 0L
) {
    val selectedPlaces: List<Place> =
        selectedOrder.mapNotNull { id -> allPlaces.find { it.id == id } }
    val selectedCount: Int get() = selectedOrder.size
}

class ResultViewModel : ViewModel() {

    private val _ui = MutableStateFlow(ResultUiState())
    val ui: StateFlow<ResultUiState> = _ui.asStateFlow()

    fun setData(
        places: List<Place>,
        weather: WeatherInfo?
    ) {
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
}
