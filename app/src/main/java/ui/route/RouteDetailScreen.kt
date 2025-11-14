package com.example.project_2.ui.route

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.project_2.data.RouteStorage
import com.example.project_2.data.route.TmapPedestrianService
import com.example.project_2.domain.model.Category
import com.example.project_2.domain.model.Place
import com.example.project_2.domain.model.SavedRoute
import kotlinx.coroutines.launch
import org.burnoutcrew.reorderable.*
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteDetailScreen(
    routeId: String,
    onBackClick: () -> Unit,
    onShowOnMap: () -> Unit  // 지도로 보기
) {
    val context = LocalContext.current
    val routeStorage = remember { RouteStorage.getInstance(context) }
    var route by remember { mutableStateOf(routeStorage.getRoute(routeId)) }

    if (route == null) {
        // 루트를 찾을 수 없음
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("루트를 찾을 수 없습니다")
        }
        return
    }

    // 🔹 구간별 색상 정의
    val segmentColors = remember {
        listOf(
            "#4285F4", // 파란색
            "#34A853", // 초록색
            "#FBBC04", // 노란색
            "#EA4335", // 빨간색
            "#9C27B0", // 보라색
            "#FF6D00"  // 주황색
        )
    }

    // 🔹 편집 모드 상태
    var isEditMode by remember { mutableStateOf(false) }
    val editablePlaces = remember(route) { mutableStateListOf<Place>().apply { addAll(route!!.places) } }
    var isSaving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("루트 상세") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                actions = {
                    if (isEditMode) {
                        // 저장 버튼
                        IconButton(
                            onClick = {
                                if (!isSaving && editablePlaces.size >= 2) {
                                    isSaving = true
                                    scope.launch {
                                        try {
                                            // T-Map으로 경로 재생성
                                            val newSegments = TmapPedestrianService.getFullRoute(editablePlaces)
                                            val updatedRoute = route!!.copy(
                                                places = editablePlaces.toList(),
                                                routeSegments = newSegments
                                            )
                                            routeStorage.saveRoute(updatedRoute)
                                            // 🔹 route를 업데이트하여 UI에 반영
                                            route = updatedRoute
                                            Toast.makeText(context, "저장되었습니다", Toast.LENGTH_SHORT).show()
                                            isEditMode = false
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "저장 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                                        } finally {
                                            isSaving = false
                                        }
                                    }
                                }
                            },
                            enabled = !isSaving && editablePlaces.size >= 2
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.Save, contentDescription = "저장")
                            }
                        }
                    } else {
                        // 편집 버튼
                        IconButton(onClick = {
                            // 🔹 편집 모드 진입 시 현재 route의 places로 리셋
                            editablePlaces.clear()
                            editablePlaces.addAll(route!!.places)
                            isEditMode = true
                        }) {
                            Icon(Icons.Default.Edit, contentDescription = "편집")
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = onShowOnMap,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("지도로 보기", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    ) { padding ->
        if (isEditMode) {
            // 🔹 편집 모드: 드래그 가능 (별도 섹션으로 분리)
            val reorderableState = rememberReorderableLazyListState(
                onMove = { from, to ->
                    val item = editablePlaces.removeAt(from.index)
                    editablePlaces.add(to.index, item)
                }
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                // 헤더
                item(key = "header") {
                    RouteHeader(route!!)
                }

                // 🔹 드래그 가능한 장소 섹션 (별도 Composable)
                item(key = "editable_places_section") {
                    EditablePlacesSection(
                        editablePlaces = editablePlaces,
                        reorderableState = reorderableState,
                        segmentColors = segmentColors,
                        onRemove = { place ->
                            if (editablePlaces.size > 2) {
                                editablePlaces.remove(place)
                            } else {
                                Toast.makeText(context, "최소 2개 장소가 필요합니다", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
        } else {
            // 🔹 일반 모드: 읽기 전용
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                // 헤더
                item {
                    RouteHeader(route!!)
                }

                // 장소 리스트
                itemsIndexed(route!!.places, key = { _, place -> place.id }) { index, place ->
                    PlaceItemCard(
                        place = place,
                        index = index,
                        color = segmentColors[index % segmentColors.size],
                        isLast = index == route!!.places.size - 1,
                        nextSegment = if (index < route!!.routeSegments.size) {
                            route!!.routeSegments[index]
                        } else null
                    )
                }
            }
        }
    }
}

@Composable
private fun RouteHeader(route: SavedRoute) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 루트 이름 + 총 시간
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    route.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    route.getTotalDurationFormatted(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // 총 거리
            Text(
                "총 이동거리: ${route.getTotalDistanceFormatted()}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * 🎨 장소 타임라인 아이템
 */
@Composable
private fun PlaceItemCard(
    place: Place,
    index: Int,
    color: String,
    isLast: Boolean,
    nextSegment: com.example.project_2.domain.model.RouteSegment?
) {
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        // 타임라인 (원 + 세로선)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(40.dp)
        ) {
            // 원형 번호
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        androidx.compose.ui.graphics.Color(Color.parseColor(color)),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = androidx.compose.ui.graphics.Color.White
                )
            }

            // 세로 연결선
            if (!isLast && nextSegment != null) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(60.dp)
                        .background(androidx.compose.ui.graphics.Color(Color.parseColor(color)).copy(alpha = 0.5f))
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        // 장소 정보
        Column(modifier = Modifier.weight(1f)) {
            Text(
                place.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )

            // 주소
            if (!place.address.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    place.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 네이버 링크
            TextButton(
                onClick = {
                    val query = URLEncoder.encode(place.name, "UTF-8")
                    val url = "https://m.search.naver.com/search.naver?query=$query"
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    context.startActivity(intent)
                },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text(
                    "네이버에서 보기",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // 다음 구간 정보
            if (!isLast && nextSegment != null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            MaterialTheme.shapes.small
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "↓",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (nextSegment.distanceMeters >= 1000) {
                            "%.1f km".format(nextSegment.distanceMeters / 1000.0)
                        } else {
                            "${nextSegment.distanceMeters}m"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("•", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "${nextSegment.durationSeconds / 60}분",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 🔹 편집 가능한 장소 카드 (드래그 가능)
 */
@Composable
private fun EditablePlaceItemCard(
    place: Place,
    index: Int,
    color: String,
    isDragging: Boolean,
    reorderableState: ReorderableLazyListState,
    onRemove: () -> Unit
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isDragging) 8.dp else 2.dp
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (isDragging) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 드래그 핸들
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = "드래그",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(24.dp)
                    .detectReorderAfterLongPress(reorderableState)
            )

            // 순서 번호 (원형)
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        androidx.compose.ui.graphics.Color(Color.parseColor(color)),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = androidx.compose.ui.graphics.Color.White
                )
            }

            // 장소 정보
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    place.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )

                // 주소
                if (!place.address.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        place.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }

            // 제거 버튼
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "제거",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * 🔹 편집 가능한 장소 섹션 (드래그 가능)
 */
@Composable
private fun EditablePlacesSection(
    editablePlaces: List<Place>,
    reorderableState: ReorderableLazyListState,
    segmentColors: List<String>,
    onRemove: (Place) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // 헤더
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "장소 목록 (${editablePlaces.size}개)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "≡ 드래그하여 순서 변경",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 드래그 가능한 장소 리스트 (LazyColumn 사용)
        LazyColumn(
            state = reorderableState.listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 600.dp)  // 최대 높이 제한
                .reorderable(reorderableState),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(editablePlaces, key = { _, place -> place.id }) { index, place ->
                ReorderableItem(reorderableState, key = place.id) { isDragging ->
                    EditablePlaceItemCard(
                        place = place,
                        index = index,
                        color = segmentColors[index % segmentColors.size],
                        isDragging = isDragging,
                        reorderableState = reorderableState,
                        onRemove = { onRemove(place) }
                    )
                }
            }
        }
    }
}
