package com.example.project_2.ui.route

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.project_2.data.RouteStorage
import com.example.project_2.domain.model.SavedRoute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteListScreen(
    onRouteClick: (String) -> Unit  // routeId를 전달
) {
    val context = LocalContext.current
    val routeStorage = remember { RouteStorage.getInstance(context) }
    var routes by remember { mutableStateOf(routeStorage.getAllRoutes()) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var routeToDelete by remember { mutableStateOf<SavedRoute?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("저장된 루트", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        if (routes.isEmpty()) {
            // 빈 상태
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "저장된 루트가 없습니다",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "지도 화면에서 루트를 생성하고 저장해보세요",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(routes, key = { it.id }) { route ->
                    RouteCard(
                        route = route,
                        onClick = { onRouteClick(route.id) },
                        onLongPress = {
                            routeToDelete = route
                            showDeleteDialog = true
                        }
                    )
                }
            }
        }
    }

    // 삭제 확인 다이얼로그
    if (showDeleteDialog && routeToDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("루트 삭제") },
            text = { Text("'${routeToDelete!!.name}' 루트를 삭제하시겠습니까?") },
            confirmButton = {
                Button(
                    onClick = {
                        routeStorage.deleteRoute(routeToDelete!!.id)
                        routes = routeStorage.getAllRoutes()
                        showDeleteDialog = false
                        routeToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("삭제")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("취소")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RouteCard(
    route: SavedRoute,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 루트 이름
            Text(
                route.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            // 장소 수
            Text(
                "${route.places.size}개 장소",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Divider()

            // 거리 및 시간 정보
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "총 거리",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        route.getTotalDistanceFormatted(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "예상 시간",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        route.getTotalDurationFormatted(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // 힌트 텍스트
            Text(
                "길게 눌러서 삭제",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}
