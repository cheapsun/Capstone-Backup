package com.example.project_2.ui.route

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import android.view.ViewGroup
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.project_2.data.RouteStorage
import com.example.project_2.domain.model.Place
import com.example.project_2.domain.model.RouteSegment
import com.example.project_2.domain.model.SavedRoute
import com.kakao.vectormap.*
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.Label
import com.kakao.vectormap.label.LabelOptions
import com.kakao.vectormap.label.LabelStyle
import com.kakao.vectormap.label.LabelStyles
import com.kakao.vectormap.route.RouteLine
import com.kakao.vectormap.route.RouteLineOptions
import com.kakao.vectormap.route.RouteLineSegment
import com.kakao.vectormap.route.RouteLineStyle
import com.kakao.vectormap.route.RouteLineStyles
import com.kakao.vectormap.route.RouteLineStylesSet
import kotlinx.coroutines.delay

/**
 * 🗺️ 저장된 루트를 지도에 표시하는 화면
 * - 구간별 포커스 기능 (클릭 시 해당 구간만 강조)
 * - 접기/펼치기 기능
 * - T-Map 스타일 타임라인 UI
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteMapScreen(
    routeId: String,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val routeStorage = remember { RouteStorage.getInstance(context) }
    val route = remember { routeStorage.getRoute(routeId) }

    if (route == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("루트를 찾을 수 없습니다")
        }
        return
    }

    var kakaoMap by remember { mutableStateOf<KakaoMap?>(null) }
    val routeLines = remember { mutableStateMapOf<Int, RouteLine>() } // 구간 인덱스 -> RouteLine
    val labels = remember { mutableStateListOf<Label>() }

    // 🔹 접기/펼치기 상태
    var isRouteInfoExpanded by remember { mutableStateOf(true) }
    var isPlaceListExpanded by remember { mutableStateOf(true) }

    // 🔹 구간별 포커스 상태 (선택된 구간 인덱스, null이면 전체 보기)
    var selectedSegmentIndex by remember { mutableStateOf<Int?>(null) }

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

    // 🔹 지도 및 경로 업데이트
    LaunchedEffect(kakaoMap, selectedSegmentIndex) {
        kakaoMap?.let { map ->
            try {
                val labelManager = map.labelManager
                val routeLineManager = map.routeLineManager

                // 기존 라벨 및 경로 제거
                labelManager?.layer?.removeAll()
                labels.clear()
                routeLines.values.forEach { routeLineManager?.remove(it) }
                routeLines.clear()

                delay(100) // 약간의 지연으로 안정성 확보

                // 🔹 마커 추가 (장소)
                route.places.forEachIndexed { index, place ->
                    val isInSelectedSegment = when (selectedSegmentIndex) {
                        null -> true // 전체 보기
                        else -> index == selectedSegmentIndex || index == selectedSegmentIndex + 1
                    }

                    val alpha = if (isInSelectedSegment) 1.0f else 0.3f
                    val scale = if (isInSelectedSegment) 1.2f else 0.8f

                    val bitmap = createNumberedPinBitmap(
                        context = context,
                        number = index + 1,
                        color = segmentColors[index % segmentColors.size],
                        alpha = alpha,
                        scale = scale
                    )

                    val options = LabelOptions.from(LatLng.from(place.lat, place.lng))
                        .setStyles(LabelStyles.from(LabelStyle.from(bitmap).setApplyDpScale(false)))

                    labelManager?.layer?.addLabel(options)?.let { labels.add(it) }
                }

                // 🔹 경로 라인 추가 (구간별)
                route.routeSegments.forEachIndexed { index, segment ->
                    if (segment.path.isNotEmpty()) {
                        val isSelected = when (selectedSegmentIndex) {
                            null -> false // 전체 보기 시 모두 기본 스타일
                            else -> index == selectedSegmentIndex
                        }

                        val color = segmentColors[index % segmentColors.size]
                        val alpha = when {
                            selectedSegmentIndex == null -> 0.7f // 전체 보기
                            isSelected -> 1.0f // 선택된 구간
                            else -> 0.3f // 선택되지 않은 구간
                        }
                        val width = if (isSelected) 8 else 6

                        val points = segment.path.map { LatLng.from(it.lat, it.lng) }
                        val routeSegment = RouteLineSegment.from(points)

                        val style = RouteLineStyle.from(width, Color.parseColor(color))
                            .setStrokeAlpha(alpha)

                        val stylesSet = RouteLineStylesSet.from(style)
                        val options = RouteLineOptions.from(listOf(routeSegment))
                            .setStylesSet(stylesSet)

                        routeLineManager?.addRouteLine(options)?.let { routeLine ->
                            routeLines[index] = routeLine
                        }
                    }
                }

                // 🔹 카메라 위치 조정
                if (selectedSegmentIndex != null && selectedSegmentIndex!! < route.routeSegments.size) {
                    // 선택된 구간에 포커스
                    val segment = route.routeSegments[selectedSegmentIndex!!]
                    if (segment.path.isNotEmpty()) {
                        val center = segment.path[segment.path.size / 2]
                        map.moveCamera(
                            CameraUpdateFactory.newCenterPosition(
                                LatLng.from(center.lat, center.lng),
                                15
                            )
                        )
                    }
                } else {
                    // 전체 경로 보기
                    route.places.firstOrNull()?.let {
                        map.moveCamera(
                            CameraUpdateFactory.newCenterPosition(
                                LatLng.from(it.lat, it.lng),
                                13
                            )
                        )
                    }
                }

            } catch (e: Exception) {
                Log.e("RouteMapScreen", "지도 업데이트 실패: ${e.message}", e)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(route.name) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            // 🗺️ 지도
            item(key = "map") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (isRouteInfoExpanded || isPlaceListExpanded) 300.dp else 500.dp)
                ) {
                    AndroidView(
                        factory = {
                            MapView(context).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            }.also { mv ->
                                mv.start(
                                    object : MapLifeCycleCallback() {
                                        override fun onMapDestroy() {
                                            kakaoMap = null
                                        }

                                        override fun onMapError(p0: Exception?) {
                                            Log.e("RouteMapScreen", "Map error: ${p0?.message}", p0)
                                        }
                                    },
                                    object : KakaoMapReadyCallback() {
                                        override fun onMapReady(map: KakaoMap) {
                                            kakaoMap = map
                                        }
                                    }
                                )
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // 📊 루트 정보 (접기/펼치기)
            item(key = "route_info") {
                RouteInfoCard(
                    route = route,
                    isExpanded = isRouteInfoExpanded,
                    selectedSegmentIndex = selectedSegmentIndex,
                    segmentColors = segmentColors,
                    onToggleExpand = { isRouteInfoExpanded = !isRouteInfoExpanded },
                    onSegmentClick = { index ->
                        selectedSegmentIndex = if (selectedSegmentIndex == index) null else index
                    }
                )
            }

            // 📍 장소 목록 (접기/펼치기)
            item(key = "place_list") {
                PlaceListCard(
                    places = route.places,
                    segments = route.routeSegments,
                    isExpanded = isPlaceListExpanded,
                    segmentColors = segmentColors,
                    onToggleExpand = { isPlaceListExpanded = !isPlaceListExpanded }
                )
            }
        }
    }
}

/**
 * 📊 루트 정보 카드 (접기/펼치기 + 구간별 클릭)
 */
@Composable
private fun RouteInfoCard(
    route: SavedRoute,
    isExpanded: Boolean,
    selectedSegmentIndex: Int?,
    segmentColors: List<String>,
    onToggleExpand: () -> Unit,
    onSegmentClick: (Int) -> Unit
) {
    val rotationAngle by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(300), label = "rotation"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .animateContentSize(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            // 헤더 (클릭 시 접기/펼치기)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "🚶 루트 정보 (${route.routeSegments.size}개 구간)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "접기" else "펼치기",
                    modifier = Modifier.rotate(rotationAngle),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            if (isExpanded) {
                Spacer(Modifier.height(16.dp))

                // 총 거리 및 시간
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Column {
                        Text(
                            "총 거리",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        )
                        Text(
                            route.getTotalDistanceFormatted(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }

                    Column {
                        Text(
                            "예상 시간",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        )
                        Text(
                            route.getTotalDurationFormatted(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                // 구간별 상세 정보 (T-Map 스타일 타임라인)
                if (route.routeSegments.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Divider(color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f))
                    Spacer(Modifier.height(12.dp))

                    Text(
                        "구간 상세 (클릭하여 지도에서 확인)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )

                    Spacer(Modifier.height(12.dp))

                    route.routeSegments.forEachIndexed { index, segment ->
                        SegmentTimelineItem(
                            index = index,
                            segment = segment,
                            color = segmentColors[index % segmentColors.size],
                            isSelected = selectedSegmentIndex == index,
                            isLast = index == route.routeSegments.size - 1,
                            onClick = { onSegmentClick(index) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 🎨 T-Map 스타일 타임라인 구간 아이템
 */
@Composable
private fun SegmentTimelineItem(
    index: Int,
    segment: RouteSegment,
    color: String,
    isSelected: Boolean,
    isLast: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }

    val elevation by animateFloatAsState(
        targetValue = if (isSelected) 4f else 1f,
        animationSpec = tween(300), label = "elevation"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(backgroundColor, MaterialTheme.shapes.small)
            .padding(vertical = 8.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 타임라인 (원 + 세로선)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(40.dp)
        ) {
            // 원형 번호
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(
                        androidx.compose.ui.graphics.Color(Color.parseColor(color)),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = androidx.compose.ui.graphics.Color.White
                )
            }

            // 세로 연결선
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(40.dp)
                        .background(androidx.compose.ui.graphics.Color(Color.parseColor(color)).copy(alpha = 0.5f))
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        // 구간 정보
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "${segment.from.name} → ${segment.to.name}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (segment.distanceMeters >= 1000) {
                        "%.1f km".format(segment.distanceMeters / 1000.0)
                    } else {
                        "${segment.distanceMeters}m"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("•", style = MaterialTheme.typography.bodySmall)
                Text(
                    formatDuration(segment.durationSeconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (isSelected) {
            Icon(
                Icons.Default.KeyboardArrowUp,
                contentDescription = "선택됨",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * 📍 장소 목록 카드 (접기/펼치기)
 */
@Composable
private fun PlaceListCard(
    places: List<Place>,
    segments: List<RouteSegment>,
    isExpanded: Boolean,
    segmentColors: List<String>,
    onToggleExpand: () -> Unit
) {
    val rotationAngle by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(300), label = "rotation"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .animateContentSize(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            // 헤더
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "📍 장소 목록 (${places.size}개)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "접기" else "펼치기",
                    modifier = Modifier.rotate(rotationAngle)
                )
            }

            if (isExpanded) {
                Spacer(Modifier.height(12.dp))

                places.forEachIndexed { index, place ->
                    PlaceTimelineItem(
                        index = index,
                        place = place,
                        color = segmentColors[index % segmentColors.size],
                        nextSegment = if (index < segments.size) segments[index] else null,
                        isLast = index == places.size - 1
                    )
                }
            }
        }
    }
}

/**
 * 🎨 장소 타임라인 아이템
 */
@Composable
private fun PlaceTimelineItem(
    index: Int,
    place: Place,
    color: String,
    nextSegment: RouteSegment?,
    isLast: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        // 타임라인 (원 + 세로선)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(40.dp)
        ) {
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
            if (!place.address.isNullOrBlank()) {
                Text(
                    place.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("•", style = MaterialTheme.typography.labelSmall)
                    Text(
                        formatDuration(nextSegment.durationSeconds),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * 시간 포맷 헬퍼 함수
 */
private fun formatDuration(seconds: Int): String {
    val minutes = seconds / 60
    val secs = seconds % 60
    return if (minutes > 0) {
        if (secs > 0) "${minutes}분 ${secs}초" else "${minutes}분"
    } else {
        "${secs}초"
    }
}

/**
 * 번호가 표시된 핀 비트맵 생성 (투명도 및 크기 조절)
 */
private fun createNumberedPinBitmap(
    context: android.content.Context,
    number: Int,
    color: String,
    alpha: Float = 1.0f,
    scale: Float = 1.0f
): Bitmap {
    val baseSize = (60 * scale).toInt()
    val bitmap = Bitmap.createBitmap(baseSize, baseSize, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.alpha = (alpha * 255).toInt()

    // 핀 배경 (원형)
    paint.color = Color.parseColor(color)
    canvas.drawCircle(
        baseSize / 2f,
        baseSize / 2f,
        (baseSize / 2 - 2).toFloat(),
        paint
    )

    // 테두리
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 3f
    paint.color = Color.WHITE
    canvas.drawCircle(
        baseSize / 2f,
        baseSize / 2f,
        (baseSize / 2 - 2).toFloat(),
        paint
    )

    // 숫자 텍스트
    paint.style = Paint.Style.FILL
    paint.color = Color.WHITE
    paint.textSize = (baseSize * 0.5f)
    paint.textAlign = Paint.Align.CENTER
    val textY = baseSize / 2f - (paint.descent() + paint.ascent()) / 2f
    canvas.drawText(number.toString(), baseSize / 2f, textY, paint)

    return bitmap
}
