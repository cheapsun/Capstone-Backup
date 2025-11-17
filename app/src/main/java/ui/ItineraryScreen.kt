package com.example.project_2.ui.itinerary

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.project_2.domain.ItineraryUseCase
import com.example.project_2.domain.model.*
import kotlinx.coroutines.launch

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
                                onMoveUp = { slot ->
                                    val slots = itinerary!!.days[selectedDayTab].timeSlots
                                    val index = slots.indexOf(slot)
                                    if (index > 0) {
                                        slots.remove(slot)
                                        slots.add(index - 1, slot)
                                        itinerary = itinerary?.copy(days = itinerary!!.days)
                                    }
                                },
                                onMoveDown = { slot ->
                                    val slots = itinerary!!.days[selectedDayTab].timeSlots
                                    val index = slots.indexOf(slot)
                                    if (index < slots.size - 1) {
                                        slots.remove(slot)
                                        slots.add(index + 1, slot)
                                        itinerary = itinerary?.copy(days = itinerary!!.days)
                                    }
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
    onMoveUp: (TimeSlot) -> Unit,
    onMoveDown: (TimeSlot) -> Unit
) {
    LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier.fillMaxSize(),
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
                                "화살표로 순서 변경",
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
            TimeSlotCard(
                slot = slot,
                isEditMode = isEditMode,
                isFirst = index == 0,
                isLast = index == day.timeSlots.size - 1,
                onDelete = if (isEditMode) { { onDeleteSlot(slot) } } else null,
                onMoveUp = if (isEditMode && index > 0) { { onMoveUp(slot) } } else null,
                onMoveDown = if (isEditMode && index < day.timeSlots.size - 1) { { onMoveDown(slot) } } else null
            )
        }
    }
}

@Composable
private fun TimeSlotCard(
    slot: TimeSlot,
    isEditMode: Boolean = false,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    onDelete: (() -> Unit)?,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 순서 변경 버튼 (편집 모드일 때만)
            if (isEditMode) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = { onMoveUp?.invoke() },
                        enabled = onMoveUp != null,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowUp,
                            contentDescription = "위로",
                            tint = if (onMoveUp != null)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                    }
                    IconButton(
                        onClick = { onMoveDown?.invoke() },
                        enabled = onMoveDown != null,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = "아래로",
                            tint = if (onMoveDown != null)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                    }
                }
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
            if (onDelete != null) {
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
