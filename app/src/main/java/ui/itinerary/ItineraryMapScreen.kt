package com.example.project_2.ui.itinerary

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapView
import com.kakao.vectormap.camera.CameraUpdateFactory
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

    // 구간별 색상
    val segmentColors = remember {
        listOf(
            "#4285F4", "#34A853", "#FBBC04",
            "#EA4335", "#9C27B0", "#FF6D00"
        )
    }

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
    LaunchedEffect(kakaoMap, selectedDay, currentDayPlaces) {
        kakaoMap?.let { map ->
            if (currentDayPlaces.size >= 2) {
                isLoadingRoute = true
                try {
                    delay(300) // 지도 초기화 대기

                    map.labelManager?.layer?.removeAll()
                    map.routeLineManager?.layer?.removeAll()

                    // 마커 추가
                    currentDayPlaces.forEachIndexed { index, place ->
                        val bitmap = createNumberedMarkerBitmap(
                            number = index + 1,
                            color = segmentColors[index % segmentColors.size]
                        )

                        val options = LabelOptions.from(LatLng.from(place.lat!!, place.lng!!))
                            .setStyles(LabelStyles.from(LabelStyle.from(bitmap).setApplyDpScale(false)))

                        map.labelManager?.layer?.addLabel(options)
                    }

                    // T-Map으로 경로 가져오기
                    val segments = TmapPedestrianService.getFullRoute(currentDayPlaces)

                    // 경로 라인 그리기
                    segments.forEachIndexed { index, segment ->
                        if (segment.pathCoordinates.isNotEmpty()) {
                            val colorHex = segmentColors[index % segmentColors.size]
                            val color = Color.parseColor(colorHex)

                            val options = RouteLineOptions.from(
                                RouteLineSegment.from(segment.pathCoordinates)
                                    .setStyles(
                                        RouteLineStyles.from(
                                            RouteLineStyle.from(6f, color)
                                        )
                                    )
                            )

                            map.routeLineManager?.layer?.addRouteLine(options)?.show()
                        }
                    }

                    // 카메라 중심 설정
                    updateMapCamera(map, currentDayPlaces, selectedSegmentIndex)

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
                    val bitmap = createNumberedMarkerBitmap(1, segmentColors[0])
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
                        "${itinerary.days.size}일 일정 - Day ${itinerary.days[selectedDay].day}",
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
                        .height(350.dp)
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

            // 경로 안내
            if (currentDayPlaces.size >= 2) {
                item(key = "route_header") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "구간별 경로",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "각 구간을 탭하여 상세 경로를 확인하세요",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                // 구간별 경로 리스트
                itemsIndexed(
                    items = currentDayPlaces.dropLast(1),
                    key = { index, _ -> "segment_$index" }
                ) { index, place ->
                    val nextPlace = currentDayPlaces[index + 1]
                    val isSelected = selectedSegmentIndex == index
                    val colorHex = segmentColors[index % segmentColors.size]

                    SegmentCard(
                        fromPlace = place,
                        toPlace = nextPlace,
                        index = index,
                        colorHex = colorHex,
                        isSelected = isSelected,
                        onClick = {
                            selectedSegmentIndex = if (isSelected) null else index
                            kakaoMap?.let { map ->
                                if (!isSelected) {
                                    // Focus on selected segment
                                    val midLat = (place.lat!! + nextPlace.lat!!) / 2
                                    val midLng = (place.lng!! + nextPlace.lng!!) / 2
                                    map.moveCamera(
                                        CameraUpdateFactory.newCenterPosition(
                                            LatLng.from(midLat, midLng), 14
                                        )
                                    )
                                } else {
                                    // Back to full view
                                    updateMapCamera(map, currentDayPlaces, null)
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SegmentCard(
    fromPlace: com.example.project_2.domain.model.Place,
    toPlace: com.example.project_2.domain.model.Place,
    index: Int,
    colorHex: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isSelected) 6.dp else 2.dp
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
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
            // 구간 번호
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        androidx.compose.ui.graphics.Color(Color.parseColor(colorHex)),
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

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    fromPlace.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "↓",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    toPlace.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

private fun updateMapCamera(
    map: KakaoMap,
    places: List<com.example.project_2.domain.model.Place>,
    selectedSegmentIndex: Int?
) {
    if (places.isEmpty()) return

    val validPlaces = places.filter { it.lat != null && it.lng != null }
    if (validPlaces.isEmpty()) return

    if (selectedSegmentIndex != null && selectedSegmentIndex < validPlaces.size - 1) {
        // Focus on selected segment
        val fromPlace = validPlaces[selectedSegmentIndex]
        val toPlace = validPlaces[selectedSegmentIndex + 1]
        val midLat = (fromPlace.lat!! + toPlace.lat!!) / 2
        val midLng = (fromPlace.lng!! + toPlace.lng!!) / 2

        map.moveCamera(
            CameraUpdateFactory.newCenterPosition(
                LatLng.from(midLat, midLng), 14
            )
        )
    } else {
        // Full view
        val centerLat = validPlaces.map { it.lat!! }.average()
        val centerLng = validPlaces.map { it.lng!! }.average()

        map.moveCamera(
            CameraUpdateFactory.newCenterPosition(
                LatLng.from(centerLat, centerLng),
                if (validPlaces.size == 1) 15 else 13
            )
        )
    }
}

/**
 * Create a numbered marker bitmap
 */
private fun createNumberedMarkerBitmap(
    number: Int,
    color: String
): Bitmap {
    val baseSize = 60
    val bitmap = Bitmap.createBitmap(baseSize, baseSize, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Draw circle background
    paint.color = Color.parseColor(color)
    canvas.drawCircle(
        baseSize / 2f,
        baseSize / 2f,
        (baseSize / 2 - 2).toFloat(),
        paint
    )

    // Draw white border
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 3f
    paint.color = Color.WHITE
    canvas.drawCircle(
        baseSize / 2f,
        baseSize / 2f,
        (baseSize / 2 - 2).toFloat(),
        paint
    )

    // Draw number text
    paint.style = Paint.Style.FILL
    paint.color = Color.WHITE
    paint.textSize = (baseSize * 0.5f)
    paint.textAlign = Paint.Align.CENTER
    val textY = baseSize / 2f - (paint.descent() + paint.ascent()) / 2f
    canvas.drawText(number.toString(), baseSize / 2f, textY, paint)

    return bitmap
}
