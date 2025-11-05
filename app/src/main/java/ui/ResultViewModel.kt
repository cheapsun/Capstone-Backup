package com.example.project_2.ui

import androidx.lifecycle.ViewModel
import com.example.project_2.domain.model.Place
import com.example.project_2.domain.model.RecommendationResult
import com.example.project_2.domain.model.WeatherInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SortMode { DEFAULT, NAME, DISTANCE, RATING }

data class ResultUiState(
    val weather: WeatherInfo? = null,
    val allPlaces: List<Place> = emptyList(),
    val visiblePlaces: List<Place> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val sortMode: SortMode = SortMode.DEFAULT,
    val query: String = "",
    val maxSelection: Int = 5
) {
    val selectedPlaces: List<Place> =
        allPlaces.filter { selectedIds.contains(it.id) }

    val selectedCount: Int get() = selectedIds.size
}

class ResultViewModel : ViewModel() {

    private val _ui = MutableStateFlow(ResultUiState())
    val ui: StateFlow<ResultUiState> = _ui.asStateFlow()

    // ===== 입력 진입점 =====

    /** MainViewModel 결과 그대로 주입 */
    fun setResult(result: RecommendationResult) {
        // 기존 선택 유지(새 목록에 존재하는 것만)
        val keepIds = _ui.value.selectedIds.filter { id ->
            result.places.any { it.id == id }
        }.toSet()

        _ui.value = _ui.value.copy(
            weather = result.weather,
            allPlaces = result.places,
            selectedIds = keepIds
        ).recomputeVisible()
    }

    /** 이전 방식 호환: 장소 목록만 주입 */
    fun setAllPlaces(list: List<Place>) {
        val keepIds = _ui.value.selectedIds.filter { id ->
            list.any { it.id == id }
        }.toSet()

        _ui.value = _ui.value.copy(
            allPlaces = list,
            selectedIds = keepIds
        ).recomputeVisible()
    }

    // ===== 상호작용 =====

    fun setQuery(q: String) {
        if (q == _ui.value.query) return
        _ui.value = _ui.value.copy(query = q).recomputeVisible()
    }

    fun setSortMode(mode: SortMode) {
        if (mode == _ui.value.sortMode) return
        _ui.value = _ui.value.copy(sortMode = mode).recomputeVisible()
    }

    /** 리스트/지도에서 장소 선택 토글 (최대 선택 수 제한) */
    fun toggleSelect(p: Place) {
        val ui0 = _ui.value
        val cur = ui0.selectedIds.toMutableSet()
        if (cur.contains(p.id)) {
            cur.remove(p.id)
        } else {
            if (cur.size >= ui0.maxSelection) return // 초과 금지
            cur.add(p.id)
        }
        _ui.value = ui0.copy(selectedIds = cur).recomputeVisible()
    }

    fun clearSelected() {
        _ui.value = _ui.value.copy(selectedIds = emptySet()).recomputeVisible()
    }

    // ===== 내부 유틸 =====

    private fun ResultUiState.recomputeVisible(): ResultUiState {
        // 1) 검색
        val filtered = if (query.isBlank()) {
            allPlaces
        } else {
            val q = query.trim().lowercase()
            allPlaces.filter { p ->
                p.name.lowercase().contains(q) ||
                        (p.address ?: "").lowercase().contains(q) ||
                        p.category.name.lowercase().contains(q)
            }
        }

        // 2) 정렬
        val sorted = when (sortMode) {
            SortMode.DEFAULT -> filtered // 서버/GPT 순서 유지
            SortMode.NAME -> filtered.sortedBy { it.name.lowercase() }
            SortMode.DISTANCE -> filtered.sortedBy { it.distanceMeters ?: Int.MAX_VALUE }
            SortMode.RATING -> filtered.sortedByDescending { it.rating ?: Double.NEGATIVE_INFINITY }
        }

        return copy(visiblePlaces = sorted)
    }
}
