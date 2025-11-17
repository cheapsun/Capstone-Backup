package com.example.project_2.ui.itinerary

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.project_2.domain.ItineraryUseCase
import com.example.project_2.domain.model.*
import kotlinx.coroutines.launch
import org.burnoutcrew.reorderable.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItineraryScreen(
    selectedPlaces: List<Place>,
    filter: FilterState,
    autoAddMeals: Boolean = false,
    onBack: () -> Unit,
    onNavigateToMap: (Itinerary) -> Unit = {},
    onSaveItinerary: (Itinerary) -> Unit = {}
) {
    var itinerary by remember { mutableStateOf<Itinerary?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedDayTab by remember { mutableStateOf(0) }
    var isEditMode by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 일정 생성
    LaunchedEffect(selectedPlaces, autoAddMeals) {
        isLoading = true
        val useCase = ItineraryUseCase()
        itinerary = useCase.generateItinerary(selectedPlaces, filter, autoAddMeals)
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${filter.duration.toDays()}일 여행 일정") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "뒤로")
                    }
                },
                actions = {
                    if (isEditMode) {
                        IconButton(onClick = { isEditMode = false }) {
                            Icon(Icons.Default.Save, "완료")
                        }
                    } else {
                        IconButton(onClick = { isEditMode = true }) {
                            Icon(Icons.Default.Edit, "편집")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (itinerary != null) {
                BottomAppBar {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { itinerary?.let { onNavigateToMap(it) } },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Map, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("지도 보기")
                        }

                        Button(
                            onClick = { itinerary?.let { onSaveItinerary(it) } },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Save, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("일정 저장")
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading -> {
                    LoadingView()
                }
                itinerary != null -> {
                    Column(Modifier.fillMaxSize()) {
                        // Day 탭
                        TabRow(selectedTabIndex = selectedDayTab) {
                            itinerary!!.days.forEachIndexed { index, day ->
                                Tab(
                                    selected = selectedDayTab == index,
                                    onClick = { selectedDayTab = index },
                                    text = { Text("Day ${day.day}") }
                                )
                            }
                        }

                        // 선택된 Day의 일정
                        if (selectedDayTab < itinerary!!.days.size) {
                            DayScheduleView(
                                day = itinerary!!.days[selectedDayTab],
                                isEditMode = isEditMode,
                                onDeleteSlot = { slot ->
                                    // TimeSlot 삭제
                                    itinerary!!.days[selectedDayTab].timeSlots.remove(slot)
                                    // UI 업데이트를 위해 itinerary를 재할당
                                    itinerary = itinerary?.copy(days = itinerary!!.days)
                                },
                                onReorder = { from, to ->
                                    // TimeSlot 순서 변경
                                    val slots = itinerary!!.days[selectedDayTab].timeSlots
                                    val item = slots.removeAt(from)
                                    slots.add(to, item)
                                    // UI 업데이트를 위해 itinerary를 재할당
                                    itinerary = itinerary?.copy(days = itinerary!!.days)
                                }
                            )
                        }
                    }
                }
                else -> {
                    ErrorView()
                }
            }
        }
    }
}

@Composable
private fun LoadingView() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator()
            Text("AI가 최적의 일정을 생성하는 중...")
        }
    }
}

@Composable
private fun ErrorView() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text("일정을 생성할 수 없습니다", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun DayScheduleView(
    day: DaySchedule,
    isEditMode: Boolean,
    onDeleteSlot: (TimeSlot) -> Unit,
    onReorder: (Int, Int) -> Unit
) {
    val reorderableState = rememberReorderableLazyListState(
        onMove = { from, to ->
            // Subtract 1 because first item is header
            if (from.index > 0 && to.index > 0) {
                onReorder(from.index - 1, to.index - 1)
            }
        }
    )

    LazyColumn(
        state = reorderableState.listState,
        modifier = Modifier
            .fillMaxSize()
            .reorderable(reorderableState),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 헤더
        item(key = "header") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "Day ${day.day}",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "${day.timeSlots.size}개 일정",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        if (isEditMode) {
                            Text(
                                "드래그하여 순서 변경",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }

        // 시간대별 일정
        itemsIndexed(day.timeSlots, key = { _, slot -> slot.id }) { index, slot ->
            ReorderableItem(reorderableState, key = slot.id) { isDragging ->
                TimeSlotCard(
                    slot = slot,
                    isEditMode = isEditMode,
                    isDragging = isDragging,
                    reorderableState = reorderableState,
                    onDelete = { onDeleteSlot(slot) }
                )
            }
        }
    }
}

@Composable
private fun TimeSlotCard(
    slot: TimeSlot,
    isEditMode: Boolean = false,
    isDragging: Boolean = false,
    reorderableState: ReorderableLazyListState? = null,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isDragging) 8.dp else 2.dp
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (isDragging) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 드래그 핸들 (편집 모드일 때만)
            if (isEditMode && reorderableState != null) {
                Icon(
                    imageVector = Icons.Default.DragHandle,
                    contentDescription = "드래그",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(24.dp)
                        .detectReorderAfterLongPress(reorderableState)
                )
            }

            // 시간
            Column(
                modifier = Modifier.width(70.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    slot.startTime,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "${slot.duration}분",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Divider(
                modifier = Modifier
                    .width(2.dp)
                    .height(50.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            // 장소 정보
            Column(Modifier.weight(1f)) {
                if (slot.place != null) {
                    Text(
                        slot.place.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        getCategoryEmoji(slot.place.category) + " ${slot.place.category}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (slot.place.address != null) {
                        Text(
                            slot.place.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    // 식사/이동 등
                    Text(
                        getActivityName(slot.activity),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                // 이동 정보
                slot.travelInfo?.let { travel ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "🚶 다음까지: ${travel.distance}km, ${travel.duration}분",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // 삭제 버튼 (편집 모드일 때만)
            if (isEditMode) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "삭제",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

private fun getCategoryEmoji(category: Category): String = when (category) {
    Category.FOOD -> "🍜"
    Category.CAFE -> "☕"
    Category.PHOTO -> "📸"
    Category.CULTURE -> "🏛"
    Category.SHOPPING -> "🛍"
    Category.HEALING -> "🌳"
    Category.EXPERIENCE -> "🧪"
    Category.NIGHT -> "🌃"
    Category.STAY -> "🏨"
}

private fun getActivityName(activity: String): String = when (activity) {
    "MEAL" -> "🍽️ 식사 시간"
    "TRANSPORT" -> "🚶 이동"
    "REST" -> "☕ 휴식"
    else -> activity
}
