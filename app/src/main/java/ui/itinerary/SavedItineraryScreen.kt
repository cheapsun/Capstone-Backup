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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.project_2.data.ItineraryStorage
import com.example.project_2.domain.model.*
import android.widget.Toast
import org.burnoutcrew.reorderable.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedItineraryScreen(
    itineraryId: String,
    onBack: () -> Unit,
    onNavigateToMap: (Itinerary) -> Unit = {}
) {
    val context = LocalContext.current
    val storage = remember { ItineraryStorage.getInstance(context) }
    var itinerary by remember { mutableStateOf(storage.getItinerary(itineraryId)) }
    var selectedDayTab by remember { mutableStateOf(0) }
    var isEditMode by remember { mutableStateOf(false) }
    var showNameEditDialog by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf("") }
    var showMoveDayDialog by remember { mutableStateOf(false) }
    var slotToMove by remember { mutableStateOf<Pair<Int, TimeSlot>?>(null) } // (fromDay, slot)
    var showAddPlaceDialog by remember { mutableStateOf(false) }
    var targetDayForAdd by remember { mutableStateOf(0) }

    if (itinerary == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("일정을 찾을 수 없습니다", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        if (itinerary!!.name.isNotBlank()) {
                            Text(
                                itinerary!!.name,
                                style = MaterialTheme.typography.titleLarge
                            )
                        } else {
                            Text("${itinerary!!.days.size}일 여행 일정")
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "뒤로")
                    }
                },
                actions = {
                    // 이름 편집 버튼
                    IconButton(onClick = {
                        editingName = itinerary!!.name.ifBlank { "${itinerary!!.days.size}일 여행 일정" }
                        showNameEditDialog = true
                    }) {
                        Icon(Icons.Default.DriveFileRenameOutline, "이름 변경")
                    }

                    if (isEditMode) {
                        IconButton(onClick = {
                            storage.saveItinerary(itinerary!!)
                            Toast.makeText(context, "저장되었습니다", Toast.LENGTH_SHORT).show()
                            isEditMode = false
                        }) {
                            Icon(Icons.Default.Save, "저장")
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
            BottomAppBar {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Button(
                        onClick = { itinerary?.let { onNavigateToMap(it) } },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Map, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("지도 보기")
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
                        dayIndex = selectedDayTab,
                        isEditMode = isEditMode,
                        totalDays = itinerary!!.days.size,
                        onDeleteSlot = { slot ->
                            // TimeSlot 삭제 - 새로운 리스트 생성하여 참조 변경
                            itinerary = itinerary?.copy(
                                days = itinerary!!.days.mapIndexed { index, day ->
                                    if (index == selectedDayTab) {
                                        day.copy(
                                            timeSlots = day.timeSlots.toMutableList().apply {
                                                remove(slot)
                                            }
                                        )
                                    } else {
                                        day
                                    }
                                }
                            )
                        },
                        onReorder = { from, to ->
                            // TimeSlot 순서 변경 - 새로운 리스트 생성하여 참조 변경
                            itinerary = itinerary?.copy(
                                days = itinerary!!.days.mapIndexed { index, day ->
                                    if (index == selectedDayTab) {
                                        day.copy(
                                            timeSlots = day.timeSlots.toMutableList().apply {
                                                add(to, removeAt(from))
                                            }
                                        )
                                    } else {
                                        day
                                    }
                                }
                            )
                        },
                        onMoveToDay = { slot ->
                            slotToMove = selectedDayTab to slot
                            showMoveDayDialog = true
                        },
                        onAddPlace = {
                            targetDayForAdd = selectedDayTab
                            showAddPlaceDialog = true
                        }
                    )
                }
            }
        }
    }

    // Day 간 이동 다이얼로그
    if (showMoveDayDialog && slotToMove != null) {
        val (fromDay, slot) = slotToMove!!
        AlertDialog(
            onDismissRequest = { showMoveDayDialog = false },
            title = { Text("다른 날로 이동") },
            text = {
                Column {
                    Text("이동할 날짜를 선택하세요")
                    Spacer(Modifier.height(16.dp))
                    itinerary!!.days.forEachIndexed { index, day ->
                        if (index != fromDay) {
                            OutlinedButton(
                                onClick = {
                                    // fromDay에서 제거하고 targetDay에 추가
                                    itinerary = itinerary?.copy(
                                        days = itinerary!!.days.mapIndexed { dayIndex, d ->
                                            when (dayIndex) {
                                                fromDay -> d.copy(
                                                    timeSlots = d.timeSlots.toMutableList().apply {
                                                        remove(slot)
                                                    }
                                                )
                                                index -> d.copy(
                                                    timeSlots = d.timeSlots.toMutableList().apply {
                                                        add(slot)
                                                    }
                                                )
                                                else -> d
                                            }
                                        }
                                    )
                                    storage.saveItinerary(itinerary!!)
                                    Toast.makeText(context, "Day ${index + 1}로 이동했습니다", Toast.LENGTH_SHORT).show()
                                    showMoveDayDialog = false
                                    slotToMove = null
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Text("Day ${day.day} (${day.timeSlots.size}개 일정)")
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    showMoveDayDialog = false
                    slotToMove = null
                }) {
                    Text("취소")
                }
            }
        )
    }

    // 장소 추가 안내 다이얼로그 (간단 버전)
    if (showAddPlaceDialog) {
        AlertDialog(
            onDismissRequest = { showAddPlaceDialog = false },
            title = { Text("장소 추가") },
            text = {
                Column {
                    Text("현재는 저장된 일정에 장소를 추가하려면:")
                    Spacer(Modifier.height(8.dp))
                    Text("1. 편집 모드에서 기존 장소를 삭제", style = MaterialTheme.typography.bodyMedium)
                    Text("2. 뒤로 가기하여 메인 화면으로 이동", style = MaterialTheme.typography.bodyMedium)
                    Text("3. 새로운 장소를 포함하여 일정 재생성", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("더 편리한 장소 추가 기능은 추후 업데이트될 예정입니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddPlaceDialog = false }) {
                    Text("확인")
                }
            }
        )
    }

    // 이름 편집 다이얼로그
    if (showNameEditDialog) {
        AlertDialog(
            onDismissRequest = { showNameEditDialog = false },
            title = { Text("일정 이름 변경") },
            text = {
                Column {
                    Text("새로운 일정 이름을 입력하세요")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editingName,
                        onValueChange = { editingName = it },
                        label = { Text("일정 이름") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editingName.isNotBlank()) {
                            itinerary?.let {
                                it.name = editingName  // 직접 수정 (copy() 대신)
                                storage.saveItinerary(it)
                                Toast.makeText(context, "이름이 변경되었습니다", Toast.LENGTH_SHORT).show()
                            }
                            showNameEditDialog = false
                        }
                    },
                    enabled = editingName.isNotBlank()
                ) {
                    Text("변경")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNameEditDialog = false }) {
                    Text("취소")
                }
            }
        )
    }
}

@Composable
internal fun DayScheduleView(
    day: DaySchedule,
    dayIndex: Int,
    isEditMode: Boolean,
    totalDays: Int,
    onDeleteSlot: (TimeSlot) -> Unit,
    onReorder: (Int, Int) -> Unit,
    onMoveToDay: (TimeSlot) -> Unit,
    onAddPlace: () -> Unit
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
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 장소 추가 버튼
                                IconButton(
                                    onClick = onAddPlace,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = "장소 추가",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
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
        }

        // 시간대별 일정
        itemsIndexed(day.timeSlots, key = { _, slot -> slot.id }) { index, slot ->
            ReorderableItem(reorderableState, key = slot.id) { isDragging ->
                TimeSlotCard(
                    slot = slot,
                    isEditMode = isEditMode,
                    isDragging = isDragging,
                    reorderableState = reorderableState,
                    canMoveToOtherDay = totalDays > 1,
                    onDelete = if (isEditMode) { { onDeleteSlot(slot) } } else null,
                    onMoveToDay = if (isEditMode && totalDays > 1) { { onMoveToDay(slot) } } else null
                )
            }
        }
    }
}

@Composable
internal fun TimeSlotCard(
    slot: TimeSlot,
    isEditMode: Boolean = false,
    isDragging: Boolean = false,
    reorderableState: ReorderableLazyListState? = null,
    canMoveToOtherDay: Boolean = false,
    onDelete: (() -> Unit)?,
    onMoveToDay: (() -> Unit)? = null
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
                        .detectReorder(reorderableState)
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

            // 액션 버튼들 (편집 모드일 때만)
            if (isEditMode) {
                Row {
                    // 다른 날로 이동 버튼
                    if (canMoveToOtherDay && onMoveToDay != null) {
                        IconButton(onClick = onMoveToDay) {
                            Icon(
                                Icons.Default.SwapVert,
                                contentDescription = "다른 날로 이동",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // 삭제 버튼
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
