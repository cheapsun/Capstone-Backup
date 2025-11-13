package com.example.project_2.ui.route

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import sh.calvin.reorderable.reorderable
import sh.calvin.reorderable.draggableHandle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.project_2.data.RouteStorage
import com.example.project_2.domain.model.Place
import com.example.project_2.domain.model.SavedRoute
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
    var isEditMode by remember { mutableStateOf(false) }
    var editedPlaces by remember { mutableStateOf<List<Place>>(emptyList()) }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditMode) "루트 편집" else "루트 상세") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                actions = {
                    if (!isEditMode) {
                        IconButton(
                            onClick = {
                                isEditMode = true
                                editedPlaces = route!!.places.toList()
                            }
                        ) {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.Edit,
                                contentDescription = "편집"
                            )
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
                if (isEditMode) {
                    // 편집 모드: 저장/취소 버튼
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                isEditMode = false
                                editedPlaces = emptyList()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("취소")
                        }
                        Button(
                            onClick = {
                                // TODO: T-Map API로 경로 재계산 후 저장
                                // 지금은 임시로 순서만 저장
                                Toast.makeText(context, "순서 변경 후 경로 재계산 기능은 곧 추가됩니다", Toast.LENGTH_SHORT).show()
                                isEditMode = false
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("저장")
                        }
                    }
                } else {
                    // 일반 모드: 지도로 보기 버튼
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
        }
    ) { padding ->
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

            if (isEditMode) {
                // 편집 모드: 드래그 가능한 리스트
                item {
                    EditablePlacesList(
                        places = editedPlaces,
                        onReorder = { fromIndex, toIndex ->
                            val mutableList = editedPlaces.toMutableList()
                            val item = mutableList.removeAt(fromIndex)
                            mutableList.add(toIndex, item)
                            editedPlaces = mutableList
                        }
                    )
                }
            } else {
                // 일반 모드: 타임라인 뷰
                foundationItemsIndexed(route!!.places, key = { _, place -> place.id }) { index, place ->
                    PlaceItemCard(
                        place = place,
                        index = index,
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
            .padding(horizontal = 24.dp)
    ) {
        // 🔹 장소 (타임라인 노드)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 왼쪽: 순서 번호 원
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }

            // 오른쪽: 장소 정보
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    place.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                if (!place.address.isNullOrBlank()) {
                    Text(
                        place.address!!,
                        style = MaterialTheme.typography.bodyMedium,
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
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text(
                        "네이버에서 보기",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        // 🔹 이동 구간 (세로 라인 + 도보 정보)
        if (!isLast && nextSegment != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 왼쪽: 세로 라인 + 도보 아이콘
                Box(
                    modifier = Modifier.width(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // 세로 라인
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(80.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                    )

                    // 도보 아이콘 (중앙)
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.DirectionsWalk,
                            contentDescription = "도보",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // 오른쪽: 도보 정보
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "도보 이동",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "약 ${nextSegment.durationSeconds / 60}분",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "•",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                if (nextSegment.distanceMeters >= 1000) {
                                    "%.1f km".format(nextSegment.distanceMeters / 1000.0)
                                } else {
                                    "${nextSegment.distanceMeters} m"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 편집 모드 드래그 가능한 장소 리스트
 */
@Composable
private fun EditablePlacesList(
    places: List<Place>,
    onReorder: (Int, Int) -> Unit
) {
    val lazyListState = rememberLazyListState()
    val state = rememberReorderableLazyListState(
        lazyListState = lazyListState,
        onMove = { from, to ->
            onReorder(from.index, to.index)
        }
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // 헤더
            Text(
                "순서 변경",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(16.dp)
            )

            HorizontalDivider()

            // 드래그 가능한 리스트
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .reorderable(state)
            ) {
                itemsIndexed(places, key = { _, place -> place.id }) { index, place ->
                    ReorderableItem(state, key = place.id) { isDragging ->
                        EditablePlaceItem(
                            place = place,
                            index = index,
                            isDragging = isDragging,
                            dragModifier = Modifier.draggableHandle()
                        )
                    }
                }
            }

            // 힌트
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "💡 길게 눌러서 드래그하여 순서를 변경하세요",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

/**
 * 편집 가능한 장소 아이템
 */
@Composable
private fun EditablePlaceItem(
    place: Place,
    index: Int,
    isDragging: Boolean,
    dragModifier: Modifier = Modifier
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        color = if (isDragging) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        } else {
            Color.Transparent
        },
        shape = MaterialTheme.shapes.medium,
        tonalElevation = if (isDragging) 4.dp else 0.dp
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
                modifier = dragModifier.size(24.dp)
            )

            // 순서 번호
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
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
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!place.address.isNullOrBlank()) {
                    Text(
                        place.address!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
