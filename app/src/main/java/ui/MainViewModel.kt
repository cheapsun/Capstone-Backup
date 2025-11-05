package com.example.project_2.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.project_2.domain.model.*
import com.example.project_2.domain.repo.TravelRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MainUiState(
    val filter: FilterState = FilterState(),
    val loading: Boolean = false,
    val error: String? = null
)

class MainViewModel(
    private val repo: TravelRepository
) : ViewModel() {

    private val _ui = MutableStateFlow(MainUiState())
    val ui: StateFlow<MainUiState> = _ui.asStateFlow()

    // 추천 결과 상태
    private val _recommendationResult = MutableStateFlow<RecommendationResult?>(null)
    val recommendationResult: StateFlow<RecommendationResult?> = _recommendationResult.asStateFlow()

    // 선택된 장소 목록 상태
    private val _selectedPlaces = MutableStateFlow<List<Place>>(emptyList())
    val selectedPlaces: StateFlow<List<Place>> = _selectedPlaces.asStateFlow()

    fun setRegion(text: String) {
        _ui.value = _ui.value.copy(filter = _ui.value.filter.copy(region = text))
    }

    fun toggleCategory(cat: Category) {
        val now = _ui.value.filter
        val next = if (cat in now.categories) now.categories - cat else now.categories + cat
        _ui.value = _ui.value.copy(filter = now.copy(categories = next))
    }

    fun setDuration(d: TripDuration) {
        _ui.value = _ui.value.copy(filter = _ui.value.filter.copy(duration = d))
    }

    fun setBudget(value: Int) {
        _ui.value = _ui.value.copy(filter = _ui.value.filter.copy(budgetPerPerson = value))
    }

    fun setCompanion(c: Companion) {
        _ui.value = _ui.value.copy(filter = _ui.value.filter.copy(companion = c))
    }

    /** 장소 선택/해제 토글 */
    fun togglePlaceSelection(place: Place) {
        val currentSelected = _selectedPlaces.value.toMutableList()
        val existingPlace = currentSelected.find { it.id == place.id }

        if (existingPlace != null) {
            currentSelected.remove(existingPlace)
        } else {
            currentSelected.add(place)
        }
        _selectedPlaces.value = currentSelected.toList()
    }

    /** 추천 데이터 생성 후 결과 화면으로 이동 준비 */
    fun buildRecommendation(onReady: () -> Unit) {
        // 추천 받기 전, 이전 선택 기록 초기화
        _selectedPlaces.value = emptyList()

        val f = _ui.value.filter
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            try {
                val weather = repo.getWeather(f.region.ifBlank { "Seoul" })
                val rec = repo.recommend(f, weather)
                _recommendationResult.value = rec // 결과를 StateFlow에 저장
                onReady()
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(error = e.message)
            } finally {
                _ui.value = _ui.value.copy(loading = false)
            }
        }
    }
}
