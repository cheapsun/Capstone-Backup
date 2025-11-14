package com.example.project_2.ui.route

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
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
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

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

    // 🔹 내 위치 표시 상태
    var showMyLocation by remember { mutableStateOf(false) }
    var myLocationLatLng by remember { mutableStateOf<LatLng?>(null) }
    var isLoadingLocation by remember { mutableStateOf(false) }
    var myLocationLabel by remember { mutableStateOf<Label?>(null) }

    // FusedLocationProviderClient
    val fusedLocationClient = remember {
        LocationServices.getFusedLocationProviderClient(context)
    }

    val scope = rememberCoroutineScope()

    // 🔹 내 위치 가져오기 및 마커 표시/제거
    LaunchedEffect(showMyLocation, kakaoMap) {
        val map = kakaoMap ?: return@LaunchedEffect
        val labelManager = map.labelManager ?: return@LaunchedEffect

        if (showMyLocation) {
            // 권한 확인
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPermission) {
                Toast.makeText(context, "위치 권한이 필요합니다", Toast.LENGTH_SHORT).show()
                showMyLocation = false
                return@LaunchedEffect
            }

            isLoadingLocation = true
            try {
                // 현재 위치 가져오기
                val location = fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    null
                ).await()

                if (location != null) {
                    val latLng = LatLng.from(location.latitude, location.longitude)
                    myLocationLatLng = latLng

                    // 기존 내 위치 마커 제거
                    myLocationLabel?.let { labelManager.layer?.remove(it) }

                    // 빨간색 마커 생성 (크기 조정)
                    val redBitmap = createNumberedPinBitmap(
                        context = context,
                        number = 0,  // "내 위치" 표시
                        color = "#EA4335",  // 빨간색
                        alpha = 1.0f,
                        scale = 1.2f
                    )

                    val redPinStyle = LabelStyles.from(
                        LabelStyle.from(redBitmap).setApplyDpScale(false)
                    )

                    val options = LabelOptions.from(latLng)
                        .setStyles(redPinStyle)

                    myLocationLabel = labelManager.layer?.addLabel(options)

                    // 카메라 이동 (내 위치 중심으로)
                    map.moveCamera(
                        CameraUpdateFactory.newCenterPosition(latLng, 15)
                    )
                } else {
                    Toast.makeText(context, "위치를 가져올 수 없습니다", Toast.LENGTH_SHORT).show()
                    showMyLocation = false
                }
            } catch (e: Exception) {
                Log.e("RouteMapScreen", "❌ 위치 가져오기 실패: ${e.message}", e)
                Toast.makeText(context, "위치 가져오기 실패", Toast.LENGTH_SHORT).show()
                showMyLocation = false
            } finally {
                isLoadingLocation = false
            }
        } else {
            // 내 위치 마커 제거
            myLocationLabel?.let { labelManager.layer?.remove(it) }
            myLocationLabel = null
            myLocationLatLng = null
        }
    }

    // 🔹 지도 및 경로 업데이트
    LaunchedEffect(kakaoMap, selectedSegmentIndex, isPlaceListExpanded) {
        kakaoMap?.let { map ->
            try {
                val labelManager = map.labelManager
                val routeLineManager = map.routeLineManager

                // 내 위치 마커 임시 저장
                val savedMyLocationLabel = myLocationLabel
                val savedMyLocationLatLng = myLocationLatLng

                // 기존 라벨 및 경로 제거
                labelManager?.layer?.removeAll()
                routeLineManager?.layer?.removeAll()
                labels.clear()
                routeLines.clear()

                delay(100) // 약간의 지연으로 안정성 확보

                // 🔹 마커 추가 (장소)
                // 장소 리스트가 펼쳐져 있을 때만 마커 표시
                if (isPlaceListExpanded) {
                    route.places.forEachIndexed { index, place ->
                        val currentSelectedIndex = selectedSegmentIndex
                        val isInSelectedSegment = when (currentSelectedIndex) {
                            null -> true // 전체 보기
                            else -> index == currentSelectedIndex || index == currentSelectedIndex + 1
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
                }

                // 🔹 경로 라인 추가 (구간별)
                route.routeSegments.forEachIndexed { index, segment ->
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
                            else -> 0.0f // 선택되지 않은 구간 완전히 숨김 (겹침 방지)
                        }
                        val width = if (isSelected) 8f else 6f

                        // alpha 값을 포함한 color 생성
                        val red = Color.red(baseColor)
                        val green = Color.green(baseColor)
                        val blue = Color.blue(baseColor)
                        val colorWithAlpha = Color.argb((alpha * 255).toInt(), red, green, blue)

                        val points = segment.pathCoordinates

                        val options = RouteLineOptions.from(
                            RouteLineSegment.from(points)
                                .setStyles(
                                    RouteLineStyles.from(
                                        RouteLineStyle.from(width, colorWithAlpha)
                                    )
                                )
                        )

                        routeLineManager?.layer?.addRouteLine(options)?.let { routeLine ->
                            routeLine.show()
                            routeLines[index] = routeLine
                            Log.d("RouteMapScreen", "✅ 경로 ${index + 1}: ${points.size}개 좌표, 투명도=$alpha")
                        } ?: run {
                            Log.e("RouteMapScreen", "❌ 경로 ${index + 1} 추가 실패")
                        }
                    }
                }

                // 🔹 카메라 위치 조정
                val currentSelectedIndex = selectedSegmentIndex
                if (currentSelectedIndex != null && currentSelectedIndex < route.routeSegments.size) {
                    // 선택된 구간에 포커스
                    val segment = route.routeSegments[currentSelectedIndex]
                    if (segment.pathCoordinates.isNotEmpty()) {
                        val center = segment.pathCoordinates[segment.pathCoordinates.size / 2]
                        map.moveCamera(
                            CameraUpdateFactory.newCenterPosition(center, 15)
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

                // 🔹 내 위치 마커 복원 (removeAll 후 다시 추가)
                if (savedMyLocationLatLng != null && showMyLocation) {
                    val redBitmap = createNumberedPinBitmap(
                        context = context,
                        number = 0,
                        color = "#EA4335",
                        alpha = 1.0f,
                        scale = 1.2f
                    )

                    val redPinStyle = LabelStyles.from(
                        LabelStyle.from(redBitmap).setApplyDpScale(false)
                    )

                    val options = LabelOptions.from(savedMyLocationLatLng)
                        .setStyles(redPinStyle)

                    myLocationLabel = labelManager?.layer?.addLabel(options)
                    Log.d("RouteMapScreen", "✅ 내 위치 마커 복원")
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
                // 🔹 지도 터치 시 LazyColumn 스크롤 차단
                val mapNestedScrollConnection = remember {
                    object : NestedScrollConnection {
                        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                            // 지도 영역 터치 시 부모의 스크롤을 모두 소비하여 차단
                            return available
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (isRouteInfoExpanded || isPlaceListExpanded) 300.dp else 500.dp)
                        .nestedScroll(mapNestedScrollConnection)
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
                                            // 🔹 첫 번째 장소로 카메라 이동 (초기 위치 설정)
                                            route.places.firstOrNull()?.let { firstPlace ->
                                                map.moveCamera(
                                                    CameraUpdateFactory.newCenterPosition(
                                                        LatLng.from(firstPlace.lat, firstPlace.lng),
                                                        13
                                                    )
                                                )
                                            }
                                            kakaoMap = map
                                        }
                                    }
                                )
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // 내 위치 버튼
                    FloatingActionButton(
                        onClick = {
                            if (!isLoadingLocation) {
                                showMyLocation = !showMyLocation
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                        containerColor = if (showMyLocation) {
                            MaterialTheme.colorScheme.error // 활성화 시 빨간색
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        }
                    ) {
                        if (isLoadingLocation) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.MyLocation,
                                contentDescription = "내 위치",
                                tint = if (showMyLocation) {
                                    MaterialTheme.colorScheme.onError
                                } else {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                }
                            )
                        }
                    }
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
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "접기" else "펼치기",
                    modifier = Modifier.rotate(rotationAngle)
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            route.getTotalDistanceFormatted(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Column {
                        Text(
                            "예상 시간",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            route.getTotalDurationFormatted(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // 구간별 상세 정보
                if (route.routeSegments.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))

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
 * 🎨 구간 타임라인 아이템 (클릭 가능)
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
                "${segment.from.name} → ${segment.to.name}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
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
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp)
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
