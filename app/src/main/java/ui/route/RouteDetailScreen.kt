package com.example.project_2.ui.route

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.example.project_2.data.RouteStorage
import com.example.project_2.data.route.TmapPedestrianService
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
    val route = remember { routeStorage.getRoute(routeId) }

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

    // 🔹 편집 모드 상태
    var isEditMode by remember { mutableStateOf(false) }
    val editablePlaces = remember { mutableStateListOf<Place>().apply { addAll(route.places) } }
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
                                            val updatedRoute = route.copy(
                                                places = editablePlaces.toList(),
                                                routeSegments = newSegments
                                            )
                                            routeStorage.saveRoute(updatedRoute)
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
                        IconButton(onClick = { isEditMode = true }) {
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
            // 🔹 편집 모드: 드래그 가능
            val reorderableState = rememberReorderableLazyListState(
                onMove = { from, to ->
                    // ✅ ReorderableItem만 추적하므로 -1 불필요
                    val item = editablePlaces.removeAt(from.index)
                    editablePlaces.add(to.index, item)
                }
            )

            LazyColumn(
                state = reorderableState.listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .reorderable(reorderableState),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                // 헤더
                item(key = "header") {
                    RouteHeader(route)
                }

                // 장소 리스트 (편집 모드)
                itemsIndexed(editablePlaces, key = { _, place -> place.id }) { index, place ->
                    ReorderableItem(reorderableState, key = place.id) { isDragging ->
                        EditablePlaceItemCard(
                            place = place,
                            index = index,
                            isDragging = isDragging,
                            reorderableState = reorderableState,
                            onRemove = {
                                if (editablePlaces.size > 2) {
                                    editablePlaces.remove(place)
                                } else {
                                    Toast.makeText(context, "최소 2개 장소가 필요합니다", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
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
                    RouteHeader(route)
                }

                // 장소 리스트
                itemsIndexed(route.places, key = { _, place -> place.id }) { index, place ->
                    PlaceItemCard(
                        place = place,
                        index = index,
                        isLast = index == route.places.size - 1,
                        nextSegment = if (index < route.routeSegments.size) {
                            route.routeSegments[index]
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

@Composable
private fun PlaceItemCard(
    place: Place,
    index: Int,
    isLast: Boolean,
    nextSegment: com.example.project_2.domain.model.RouteSegment?
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 순서 번호 (파란색 배경)
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.shapes.medium
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }

                // 장소 정보
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        place.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

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
                }
            }
        }

        // 다음 장소로의 이동 정보 (마지막 아이템 제외)
        if (!isLast && nextSegment != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 56.dp, top = 4.dp, bottom = 4.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        MaterialTheme.shapes.small
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )

                Text(
                    "도보 ${nextSegment.durationSeconds / 60}분",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    "•",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    if (nextSegment.distanceMeters >= 1000) {
                        "%.1f km".format(nextSegment.distanceMeters / 1000.0)
                    } else {
                        "${nextSegment.distanceMeters} m"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
    isDragging: Boolean,
    reorderableState: ReorderableLazyListState,
    onRemove: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
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
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
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

                // 순서 번호
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.shapes.medium
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }

                // 장소 정보
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        place.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

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
                }

                // 제거 버튼
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "제거",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
