package com.example.project_2.ui.itinerary

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Save
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
                title = { Text("${itinerary!!.days.size}일 여행 일정") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "뒤로")
                    }
                },
                actions = {
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
                        onDeleteSlot = if (isEditMode) {
                            { slot ->
                                // TimeSlot 삭제
                                itinerary!!.days[selectedDayTab].timeSlots.remove(slot)
                                // UI 업데이트를 위해 itinerary를 재할당
                                itinerary = itinerary?.copy(days = itinerary!!.days)
                            }
                        } else null
                    )
                }
            }
        }
    }
}

@Composable
private fun DayScheduleView(
    day: DaySchedule,
    onDeleteSlot: ((TimeSlot) -> Unit)?
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 헤더
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(Modifier.padding(16.dp)) {
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
            }
        }

        // 시간대별 일정
        items(day.timeSlots) { slot ->
            TimeSlotCard(
                slot = slot,
                onDelete = onDeleteSlot?.let { { it(slot) } }
            )
        }
    }
}

@Composable
private fun TimeSlotCard(
    slot: TimeSlot,
    onDelete: (() -> Unit)?
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
