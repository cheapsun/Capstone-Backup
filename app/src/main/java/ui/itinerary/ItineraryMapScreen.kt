package com.example.project_2.ui.itinerary

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.project_2.data.route.TmapPedestrianService
import com.example.project_2.domain.model.Itinerary
import com.example.project_2.domain.model.Place
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapView
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItineraryMapScreen(
    itinerary: Itinerary,
    initialDay: Int = 0,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedDay by remember { mutableStateOf(initialDay) }
    var kakaoMap by remember { mutableStateOf<KakaoMap?>(null) }
    var selectedSegmentIndex by remember { mutableStateOf<Int?>(null) }
    var isLoadingRoute by remember { mutableStateOf(false) }

    // 접기/펼치기 상태
    var isRouteInfoExpanded by remember { mutableStateOf(true) }
    var isPlaceListExpanded by remember { mutableStateOf(true) }

    // 구간별 색상
    val segmentColors = remember {
        listOf(
            "#4285F4", "#34A853", "#FBBC04",
            "#EA4335", "#9C27B0", "#FF6D00"
        )
    }

    val labels = remember { mutableStateListOf<Label>() }
    val routeLines = remember { mutableStateMapOf<Int, RouteLine>() }

    // Get places for selected day
    val currentDayPlaces = remember(selectedDay, itinerary) {
        if (selectedDay < itinerary.days.size) {
            itinerary.days[selectedDay].timeSlots
                .mapNotNull { it.place }
                .filter { it.lat != null && it.lng != null }
        } else {
            emptyList()
        }
    }

    // 경로 계산 및 표시
    LaunchedEffect(kakaoMap, selectedDay, currentDayPlaces, selectedSegmentIndex, isPlaceListExpanded) {
        kakaoMap?.let { map ->
            if (currentDayPlaces.size >= 2) {
                isLoadingRoute = true
                try {
                    delay(300) // 지도 초기화 대기

                    val labelManager = map.labelManager
                    val routeLineManager = map.routeLineManager

                    // 기존 라벨 및 경로 제거
                    labelManager?.layer?.removeAll()
                    routeLineManager?.layer?.removeAll()
                    labels.clear()
                    routeLines.clear()

                    delay(100) // 약간의 지연으로 안정성 확보

                    // 마커 추가 (장소 리스트가 펼쳐져 있을 때만)
                    if (isPlaceListExpanded) {
                        currentDayPlaces.forEachIndexed { index, place ->
                            val currentSelectedIndex = selectedSegmentIndex
                            val isInSelectedSegment = when (currentSelectedIndex) {
                                null -> true // 전체 보기
                                else -> index == currentSelectedIndex || index == currentSelectedIndex + 1
                            }

                            val alpha = if (isInSelectedSegment) 1.0f else 0.3f
                            val scale = if (isInSelectedSegment) 1.2f else 0.8f

                            val bitmap = createNumberedPinBitmap(
                                number = index + 1,
                                color = segmentColors[index % segmentColors.size],
                                alpha = alpha,
                                scale = scale
                            )

                            val options = LabelOptions.from(LatLng.from(place.lat!!, place.lng!!))
                                .setStyles(LabelStyles.from(LabelStyle.from(bitmap).setApplyDpScale(false)))

                            labelManager?.layer?.addLabel(options)?.let { labels.add(it) }
                        }
                    }

                    // T-Map으로 경로 가져오기
                    val segments = TmapPedestrianService.getFullRoute(currentDayPlaces)

                    // 경로 라인 그리기
                    segments.forEachIndexed { index, segment ->
                        if (segment.pathCoordinates.isNotEmpty()) {
                            val currentSelectedIndex = selectedSegmentIndex
                            val isSelected = when (currentSelectedIndex) {
                                null -> false // 전체 보기 시 모두 기본 스타일
                                else -> index == currentSelectedIndex
                            }

                            val colorHex = segmentColors[index % segmentColors.size]
                            val baseColor = Color.parseColor(colorHex)

                            val alpha = when {
                                currentSelectedIndex == null -> 0.7f // 전체 보기
                                isSelected -> 1.0f // 선택된 구간
                                else -> 0.0f // 선택되지 않은 구간 완전히 숨김
                            }
                            val width = if (isSelected) 8f else 6f

                            // alpha 값을 포함한 color 생성
                            val red = Color.red(baseColor)
                            val green = Color.green(baseColor)
                            val blue = Color.blue(baseColor)
                            val colorWithAlpha = Color.argb((alpha * 255).toInt(), red, green, blue)

                            val options = RouteLineOptions.from(
                                RouteLineSegment.from(segment.pathCoordinates)
                                    .setStyles(
                                        RouteLineStyles.from(
                                            RouteLineStyle.from(width, colorWithAlpha)
                                        )
                                    )
                            )

                            routeLineManager?.layer?.addRouteLine(options)?.let { routeLine ->
                                routeLine.show()
                                routeLines[index] = routeLine
                            }
                        }
                    }

                    // 카메라 위치 조정
                    val currentSelectedIndex = selectedSegmentIndex
                    if (currentSelectedIndex != null && currentSelectedIndex < segments.size) {
                        // 선택된 구간에 포커스
                        val segment = segments[currentSelectedIndex]
                        if (segment.pathCoordinates.isNotEmpty()) {
                            val center = segment.pathCoordinates[segment.pathCoordinates.size / 2]
                            map.moveCamera(
                                CameraUpdateFactory.newCenterPosition(center, 15)
                            )
                        }
                    } else {
                        // 전체 경로 보기
                        currentDayPlaces.firstOrNull()?.let {
                            map.moveCamera(
                                CameraUpdateFactory.newCenterPosition(
                                    LatLng.from(it.lat!!, it.lng!!),
                                    13
                                )
                            )
                        }
                    }

                } catch (e: Exception) {
                    Log.e("ItineraryMapScreen", "경로 표시 실패: ${e.message}", e)
                    Toast.makeText(context, "경로를 표시할 수 없습니다", Toast.LENGTH_SHORT).show()
                } finally {
                    isLoadingRoute = false
                }
            } else {
                // 장소가 1개 이하면 마커만 표시
                map.labelManager?.layer?.removeAll()
                currentDayPlaces.firstOrNull()?.let { place ->
                    val bitmap = createNumberedPinBitmap(1, segmentColors[0], 1.0f, 1.0f)
                    val options = LabelOptions.from(LatLng.from(place.lat!!, place.lng!!))
                        .setStyles(LabelStyles.from(LabelStyle.from(bitmap).setApplyDpScale(false)))
                    map.labelManager?.layer?.addLabel(options)
                    map.moveCamera(CameraUpdateFactory.newCenterPosition(
                        LatLng.from(place.lat!!, place.lng!!), 15
                    ))
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "${itinerary.days.size}일 일정",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "뒤로")
                    }
                }
            )
        }
    ) { padding ->
        val mapNestedScrollConnection = remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    return available
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            // Day 탭
            item(key = "day_tabs") {
                TabRow(selectedTabIndex = selectedDay) {
                    itinerary.days.forEachIndexed { index, day ->
                        Tab(
                            selected = selectedDay == index,
                            onClick = {
                                selectedDay = index
                                selectedSegmentIndex = null
                            },
                            text = { Text("Day ${day.day}") }
                        )
                    }
                }
            }

            // 지도
            item(key = "map") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (isRouteInfoExpanded || isPlaceListExpanded) 300.dp else 500.dp)
                        .nestedScroll(mapNestedScrollConnection)
                ) {
                    if (currentDayPlaces.isNotEmpty()) {
                        AndroidView(
                            factory = { context ->
                                MapView(context).apply {
                                    start(object : MapLifeCycleCallback() {
                                        override fun onMapDestroy() {
                                            kakaoMap = null
                                        }
                                        override fun onMapError(error: Exception?) {
                                            Log.e("ItineraryMapScreen", "Map error: ${error?.message}")
                                        }
                                    }, object : KakaoMapReadyCallback() {
                                        override fun onMapReady(map: KakaoMap) {
                                            kakaoMap = map
                                        }
                                    })
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        if (isLoadingRoute) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "이 날짜에 표시할 장소가 없습니다",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 경로 안내 (RouteMapScreen 스타일)
            if (currentDayPlaces.size >= 2) {
                item(key = "route_info") {
                    RouteInfoCard(
                        places = currentDayPlaces,
                        isExpanded = isRouteInfoExpanded,
                        selectedSegmentIndex = selectedSegmentIndex,
                        segmentColors = segmentColors,
                        onToggleExpand = { isRouteInfoExpanded = !isRouteInfoExpanded },
                        onSegmentClick = { index ->
                            selectedSegmentIndex = if (selectedSegmentIndex == index) null else index
                        }
                    )
                }
            }

            // 장소 목록
            item(key = "place_list") {
                PlaceListCard(
                    places = currentDayPlaces,
                    isExpanded = isPlaceListExpanded,
                    segmentColors = segmentColors,
                    onToggleExpand = { isPlaceListExpanded = !isPlaceListExpanded }
                )
            }
        }
    }
}

/**
 * 📊 루트 정보 카드 (RouteMapScreen 스타일)
 */
@Composable
private fun RouteInfoCard(
    places: List<Place>,
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
                    "🚶 구간별 경로 (${places.size - 1}개 구간)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "접기" else "펼치기",
                    modifier = Modifier.rotate(rotationAngle)
                )
            }

            if (isExpanded && places.size >= 2) {
                Spacer(Modifier.height(16.dp))

                // 구간별 타임라인
                places.dropLast(1).forEachIndexed { index, place ->
                    val nextPlace = places[index + 1]
                    SegmentTimelineItem(
                        index = index,
                        fromPlace = place,
                        toPlace = nextPlace,
                        color = segmentColors[index % segmentColors.size],
                        isSelected = selectedSegmentIndex == index,
                        isLast = index == places.size - 2,
                        onClick = { onSegmentClick(index) }
                    )
                }
            }
        }
    }
}

/**
 * 🎨 구간 타임라인 아이템
 */
@Composable
private fun SegmentTimelineItem(
    index: Int,
    fromPlace: Place,
    toPlace: Place,
    color: String,
    isSelected: Boolean,
    isLast: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .then(
                if (isSelected) {
                    Modifier
                        .background(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                            MaterialTheme.shapes.small
                        )
                        .padding(vertical = 4.dp, horizontal = 8.dp)
                } else {
                    Modifier.padding(vertical = 4.dp)
                }
            ),
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
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(60.dp)
                        .background(androidx.compose.ui.graphics.Color(Color.parseColor(color)).copy(alpha = 0.5f))
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        // 구간 정보
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "${fromPlace.name} → ${toPlace.name}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
        }

        if (isSelected) {
            Icon(
                Icons.Default.KeyboardArrowUp,
                contentDescription = "선택됨",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

/**
 * 📍 장소 목록 카드
 */
@Composable
private fun PlaceListCard(
    places: List<Place>,
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

            if (!isLast) {
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

            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * 번호가 표시된 핀 비트맵 생성
 */
private fun createNumberedPinBitmap(
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
