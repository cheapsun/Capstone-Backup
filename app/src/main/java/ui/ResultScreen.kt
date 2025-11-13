package com.example.project_2.ui.result

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.example.project_2.data.RouteStorage
import com.example.project_2.data.route.TmapPedestrianService
import com.example.project_2.domain.model.Place
import com.example.project_2.domain.model.RecommendationResult
import com.example.project_2.domain.model.RouteSegment
import com.example.project_2.domain.model.SavedRoute
import com.example.project_2.domain.model.WeatherInfo
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.kakao.vectormap.*
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.Label
import com.kakao.vectormap.label.LabelOptions
import com.kakao.vectormap.label.LabelStyle
import com.kakao.vectormap.label.LabelStyles
import com.kakao.vectormap.label.LabelTextStyle
import com.kakao.vectormap.route.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.net.URLEncoder

@Composable
fun ResultScreen(
    rec: RecommendationResult,
    regionHint: String? = null,   // ✅ 사용자가 입력했던 지역 (예: "광주 상무동")
    savedRoute: SavedRoute? = null  // ✅ 저장된 루트 (있으면 자동으로 경로 표시)
) {
    Log.d("UI", "ResultScreen received ${rec.places.size} places (topPicks=${rec.topPicks.size})")
    rec.places.forEachIndexed { i, p ->
        Log.d("UI", "[$i] ${p.name} (${p.lat}, ${p.lng}) reason=${rec.gptReasons[p.id] ?: "없음"}")
    }

    var kakaoMap by remember { mutableStateOf<KakaoMap?>(null) }
    val labelPlaceMap = remember { mutableMapOf<Label, Place>() }
    var highlightedId by remember { mutableStateOf<String?>(null) }

    val selectedOrder = remember { mutableStateListOf<String>() }
    val selectedPlaces: List<Place> by remember(selectedOrder, rec.places) {
        derivedStateOf { selectedOrder.mapNotNull { id -> rec.places.find { it.id == id } } }
    }

    // 🔹 T-Map 라우팅 상태
    var routeSegments by remember { mutableStateOf<List<RouteSegment>>(emptyList()) }
    var isLoadingRoute by remember { mutableStateOf(false) }
    var showRealRoute by remember { mutableStateOf(false) }

    val topIds: Set<String> = remember(rec.topPicks) { rec.topPicks.map { it.id }.toSet() }

    // 🔹 저장된 루트가 있으면 자동으로 장소 선택 및 경로 표시
    LaunchedEffect(savedRoute) {
        if (savedRoute != null) {
            selectedOrder.clear()
            selectedOrder.addAll(savedRoute.places.map { it.id })
            routeSegments = savedRoute.routeSegments
            showRealRoute = true
            Log.d("UI", "✅ 저장된 루트 로드: ${savedRoute.name}, ${savedRoute.places.size}개 장소")
        }
    }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 🔹 커스텀 핀 비트맵 생성 (Capstone-Backup 방식)
    val bluePinBitmap = remember {
        createPinBitmap(context, "#4285F4") // 파란색 (일반 장소)
    }

    val starPinBitmap = remember {
        createPinBitmap(context, "#FFD700") // 골드색 (Top Picks)
    }

    val orangePinBitmap = remember {
        createPinBitmap(context, "#FF9800") // 주황색 (선택된 장소)
    }

    val redPinBitmap = remember {
        createPinBitmap(context, "#FF0000") // 빨간색 (내 위치)
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

    // 🔹 루트 저장 다이얼로그 상태
    var showSaveDialog by remember { mutableStateOf(false) }
    var routeNameInput by remember { mutableStateOf("") }

    // 🔹 구간 리스트 하이라이트 상태
    var expandedRouteList by remember { mutableStateOf(false) }
    var highlightedSegmentIndex by remember { mutableStateOf<Int?>(null) }

    // 🔹 추천 장소 리스트 상태
    // savedRoute가 있으면 기본 접힘 (루트만 보이도록), 없으면 펼침
    var expandedPlacesList by remember { mutableStateOf(savedRoute == null) }

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

                    // 빨간색 마커 추가
                    val redPinStyle = if (redPinBitmap != null) {
                        LabelStyles.from(LabelStyle.from(redPinBitmap).setAnchorPoint(0.5f, 1.0f))
                    } else {
                        LabelStyles.from(LabelStyle.from())
                    }

                    val options = LabelOptions.from(latLng)
                        .setStyles(redPinStyle)

                    myLocationLabel = labelManager.layer?.addLabel(options)

                    // 카메라 이동 (내 위치 중심으로)
                    map.moveCamera(CameraUpdateFactory.newCenterPosition(latLng, 15))

                    Log.d("UI", "✅ 내 위치 표시: ${location.latitude}, ${location.longitude}")
                } else {
                    Toast.makeText(context, "위치를 가져올 수 없습니다", Toast.LENGTH_SHORT).show()
                    showMyLocation = false
                }
            } catch (e: Exception) {
                Log.e("UI", "❌ 위치 가져오기 실패: ${e.message}", e)
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

    // 🔹 LaunchedEffect로 마커 + 경로 동적 업데이트 (Capstone-Backup 방식 - 단일 Effect)
    LaunchedEffect(kakaoMap, selectedOrder.toList(), rec.places, showRealRoute, routeSegments, highlightedSegmentIndex, expandedPlacesList) {
        val map = kakaoMap ?: return@LaunchedEffect
        val labelManager = map.labelManager ?: return@LaunchedEffect
        val routeLineManager = map.routeLineManager ?: return@LaunchedEffect

        // 내 위치 마커 임시 저장
        val savedMyLocationLabel = myLocationLabel
        val savedMyLocationLatLng = myLocationLatLng

        // 기존 마커 및 경로선 모두 제거
        labelManager.layer?.removeAll()
        routeLineManager.layer?.removeAll()
        labelPlaceMap.clear()

        Log.d("UI", "LaunchedEffect: Adding markers (expandedPlacesList=$expandedPlacesList)")

        // 텍스트 스타일
        val textStyle = LabelStyles.from(
            LabelStyle.from(LabelTextStyle.from(28, Color.BLACK, 2, Color.WHITE))
        )

        // 핀 스타일 생성
        val bluePinStyle = if (bluePinBitmap != null) {
            LabelStyles.from(LabelStyle.from(bluePinBitmap).setAnchorPoint(0.5f, 1.0f))
        } else {
            textStyle
        }

        val starPinStyle = if (starPinBitmap != null) {
            LabelStyles.from(LabelStyle.from(starPinBitmap).setAnchorPoint(0.5f, 1.0f))
        } else {
            textStyle
        }

        val orangePinStyle = if (orangePinBitmap != null) {
            LabelStyles.from(LabelStyle.from(orangePinBitmap).setAnchorPoint(0.5f, 1.0f))
        } else {
            textStyle
        }

        // 🔹 마커 표시: expandedPlacesList에 따라 필터링
        val placesToShow = if (expandedPlacesList) {
            // 추천 장소 리스트가 펼쳐져 있으면 모든 장소 표시
            rec.places
        } else {
            // 추천 장소 리스트가 접혀 있으면 선택된 장소만 표시
            selectedPlaces
        }

        placesToShow.forEach { place ->
            val selectedIndex = selectedOrder.indexOfFirst { it == place.id }
            val isSelected = selectedIndex != -1
            val isTopPick = topIds.contains(place.id)

            val options = LabelOptions.from(LatLng.from(place.lat, place.lng))
                .setClickable(true)

            when {
                isSelected -> {
                    // 선택된 장소: 주황색 핀 + 번호
                    options.setTexts("${selectedIndex + 1}")
                    options.setStyles(orangePinStyle)
                }
                isTopPick -> {
                    // Top Pick: 골드색 핀
                    options.setStyles(starPinStyle)
                }
                else -> {
                    // 일반 장소: 파란색 핀
                    options.setStyles(bluePinStyle)
                }
            }

            labelManager.layer?.addLabel(options)?.let { label ->
                labelPlaceMap[label] = place
            }
        }

        Log.d("UI", "✅ Markers added: ${labelPlaceMap.size} (showAll=$expandedPlacesList)")

        // 🔹 실제 경로 표시 (같은 LaunchedEffect 내에서 처리)
        if (showRealRoute && routeSegments.isNotEmpty()) {
            try {
                // 각 구간을 다른 색상으로 표시
                val colors = listOf(
                    Color.rgb(66, 133, 244),   // 파란색
                    Color.rgb(234, 67, 53),    // 빨간색
                    Color.rgb(251, 188, 5),    // 노란색
                    Color.rgb(52, 168, 83),    // 초록색
                    Color.rgb(156, 39, 176),   // 보라색
                    Color.rgb(255, 109, 0),    // 주황색
                )

                routeSegments.forEachIndexed { index, segment ->
                    val coords = segment.pathCoordinates
                    if (coords.size >= 2) {
                        val baseColor = colors[index % colors.size]

                        // 🔹 하이라이트 기능: 선택된 구간만 표시
                        val isHighlighted = highlightedSegmentIndex == index
                        val isAnyHighlighted = highlightedSegmentIndex != null

                        // 하이라이트가 있을 때, 선택되지 않은 구간은 완전히 투명 (숨김)
                        if (isAnyHighlighted && !isHighlighted) {
                            // 선택되지 않은 구간은 그리지 않음
                            return@forEachIndexed
                        }

                        // 모든 경로선 굵기 18f로 통일
                        val lineWidth = 18f

                        val options = RouteLineOptions.from(
                            RouteLineSegment.from(coords)
                                .setStyles(
                                    RouteLineStyles.from(
                                        RouteLineStyle.from(lineWidth, baseColor)
                                    )
                                )
                        )

                        val routeLine = routeLineManager.layer?.addRouteLine(options)
                        routeLine?.show()

                        Log.d("UI", "경로 ${index + 1}: ${coords.size}개 좌표, width=$lineWidth, highlighted=$isHighlighted")
                    }
                }

                Log.d("UI", "✅ 경로선 그리기 완료: ${routeSegments.size}개 구간")
            } catch (e: Exception) {
                Log.e("UI", "❌ 경로선 그리기 실패: ${e.message}", e)
            }
        }

        // 🔹 내 위치 마커 복원 (removeAll 후 다시 추가)
        if (savedMyLocationLatLng != null && showMyLocation) {
            val redPinStyle = if (redPinBitmap != null) {
                LabelStyles.from(LabelStyle.from(redPinBitmap).setAnchorPoint(0.5f, 1.0f))
            } else {
                LabelStyles.from(LabelStyle.from())
            }

            val options = LabelOptions.from(savedMyLocationLatLng)
                .setStyles(redPinStyle)

            myLocationLabel = labelManager.layer?.addLabel(options)
            Log.d("UI", "✅ 내 위치 마커 복원")
        }
    }

    val focusOn: (Place) -> Unit = { p ->
        kakaoMap?.let { map ->
            map.moveCamera(CameraUpdateFactory.newCenterPosition(LatLng.from(p.lat, p.lng)))
            highlightedId = p.id
        }
    }

    val toggleSelect: (Place) -> Unit = { p ->
        if (selectedOrder.contains(p.id)) {
            selectedOrder.remove(p.id)
        } else {
            selectedOrder.add(p.id)
        }
    }

    // 🔹 T-Map 실제 경로 생성
    val buildRealRoute: () -> Unit = route@{
        val map = kakaoMap ?: return@route
        if (selectedPlaces.size < 2) return@route

        isLoadingRoute = true
        showRealRoute = false

        scope.launch {
            try {
                Log.d("UI", "🚶 T-Map 경로 생성 시작: ${selectedPlaces.size}개 장소")
                val segments = TmapPedestrianService.getFullRoute(selectedPlaces)

                if (segments.isNotEmpty()) {
                    routeSegments = segments
                    showRealRoute = true
                    Log.d("UI", "✅ T-Map 경로 생성 완료: ${segments.size}개 구간")

                    // 경로 중심으로 카메라 이동
                    val (centerLat, centerLng) = computeCenter(selectedPlaces)
                    map.moveCamera(CameraUpdateFactory.newCenterPosition(LatLng.from(centerLat, centerLng)))
                } else {
                    Log.e("UI", "❌ T-Map 경로 생성 실패")
                }
            } catch (e: Exception) {
                Log.e("UI", "❌ 경로 생성 중 에러: ${e.message}", e)
            } finally {
                isLoadingRoute = false
            }
        }
    }

    // 전체 스크롤
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        // 날씨
        item(key = "weather") {
            WeatherBanner(rec.weather)
        }

        // 지도 + GPS 버튼
        item(key = "map") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
            ) {
                AndroidView(
                    factory = {
                        val mv = MapView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                (context.resources.displayMetrics.heightPixels * 0.35).toInt()
                            )
                        }
                        mv.start(
                            object : MapLifeCycleCallback() {
                                override fun onMapDestroy() {
                                    kakaoMap = null
                                }
                                override fun onMapError(p0: Exception?) {
                                    Log.e("UI", "Map error: ${p0?.message}", p0)
                                }
                            },
                            object : KakaoMapReadyCallback() {
                                var isMapInitialized = false
                                override fun onMapReady(map: KakaoMap) {
                                    if (!isMapInitialized) {
                                        rec.places.firstOrNull()?.let {
                                            map.moveCamera(
                                                CameraUpdateFactory.newCenterPosition(LatLng.from(it.lat, it.lng))
                                            )
                                        }
                                        map.setOnLabelClickListener { _, _, label ->
                                            labelPlaceMap[label]?.let { place ->
                                                focusOn(place)
                                            }
                                        }
                                        isMapInitialized = true
                                    }
                                    kakaoMap = map
                                }
                            }
                        )
                        mv
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // 🔹 GPS 버튼 (우측 하단)
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
                        MaterialTheme.colorScheme.primaryContainer // 비활성화 시 기본색
                    }
                ) {
                    if (isLoadingLocation) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
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

        // 🔹 경로 정보 (경로가 생성되면 표시)
        if (showRealRoute && routeSegments.isNotEmpty()) {
            item(key = "route_info") {
                RouteInfoSection(routeSegments)
            }
        }

        // 카테고리 Top
        if (rec.topPicks.isNotEmpty()) {
            item(key = "top_title") {
                Text(
                    "카테고리별 상위 추천",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            item(key = "top_row") {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    items(rec.topPicks, key = { it.id }) { p ->
                        TopPickCard(
                            p = p,
                            reason = rec.gptReasons[p.id],
                            isSelected = selectedOrder.contains(p.id),
                            onView = { focusOn(p) },
                            onToggle = {
                                toggleSelect(p)
                                focusOn(p)
                            }
                        )
                    }
                }
            }
        }

        // 🔹 하단 액션 (T-Map 경로 생성 버튼 추가)
        item(key = "actions") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            selectedOrder.clear()
                            routeSegments = emptyList()
                            showRealRoute = false
                            // LaunchedEffect가 자동으로 마커 및 경로 업데이트
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("선택 초기화") }

                    Button(
                        onClick = { buildRealRoute() },
                        enabled = selectedOrder.size >= 2 && !isLoadingRoute,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isLoadingRoute) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("경로 생성 중...")
                        } else {
                            Text("루트 생성하기 (${selectedOrder.size}개)")
                        }
                    }
                }

                // 루트 저장 버튼 (루트 생성 완료 후에만 표시)
                if (showRealRoute && routeSegments.isNotEmpty()) {
                    Button(
                        onClick = { showSaveDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Text("루트 저장하기")
                    }
                }
            }
        }

        // 🔹 선택된 장소 드래그 리스트
        if (selectedOrder.isNotEmpty()) {
            item(key = "selected_places") {
                SelectedPlacesList(
                    selectedPlaces = selectedPlaces,
                    selectedOrder = selectedOrder,
                    onReorder = { fromIndex, toIndex ->
                        // 순서 변경
                        val fromId = selectedOrder[fromIndex]
                        selectedOrder.removeAt(fromIndex)
                        selectedOrder.add(toIndex, fromId)

                        // 경로가 생성되어 있으면 초기화 (재생성 필요)
                        if (showRealRoute) {
                            routeSegments = emptyList()
                            showRealRoute = false
                        }
                    },
                    onRemove = { place ->
                        selectedOrder.remove(place.id)
                        if (showRealRoute && selectedOrder.size < 2) {
                            routeSegments = emptyList()
                            showRealRoute = false
                        }
                    }
                )
            }
        }

        // 🔹 경로 구간 리스트 (접이식) - 먼저 표시
        if (showRealRoute && routeSegments.isNotEmpty()) {
            item(key = "route_segments") {
                RouteSegmentsList(
                    segments = routeSegments,
                    selectedPlaces = selectedPlaces,
                    expanded = expandedRouteList,
                    onToggleExpand = { expandedRouteList = !expandedRouteList },
                    highlightedIndex = highlightedSegmentIndex,
                    onSegmentClick = { index ->
                        highlightedSegmentIndex = if (highlightedSegmentIndex == index) null else index
                        // 선택된 구간의 중간 지점으로 카메라 이동
                        if (highlightedSegmentIndex == index) {
                            val segment = routeSegments[index]
                            val midLat = (segment.from.lat + segment.to.lat) / 2.0
                            val midLng = (segment.from.lng + segment.to.lng) / 2.0
                            kakaoMap?.moveCamera(
                                CameraUpdateFactory.newCenterPosition(LatLng.from(midLat, midLng), 15)
                            )
                        }
                    }
                )
            }
        }

        // 🔹 추천 장소 접이식 리스트 - 나중에 표시
        item(key = "places_list") {
            PlacesExpandableList(
                places = rec.places,
                selectedOrder = selectedOrder,
                topIds = topIds,
                aiTopIds = rec.aiTopIds,
                gptReasons = rec.gptReasons,
                regionHint = regionHint,
                expanded = expandedPlacesList,
                onToggleExpand = { expandedPlacesList = !expandedPlacesList },
                onPlaceToggle = { place ->
                    toggleSelect(place)
                    focusOn(place)
                }
            )
        }
    }

    // 🔹 루트 저장 다이얼로그
    if (showSaveDialog) {
        SaveRouteDialog(
            onDismiss = { showSaveDialog = false },
            onSave = { routeName ->
                val savedRoute = SavedRoute(
                    id = System.currentTimeMillis().toString(),
                    name = routeName,
                    selectedPlaces = selectedPlaces,
                    routeSegments = routeSegments,
                    allRecommendedPlaces = rec.places,      // 🔹 모든 추천 장소 저장
                    gptReasons = rec.gptReasons,            // 🔹 GPT 이유 저장
                    topPicks = rec.topPicks,                // 🔹 Top Picks 저장
                    aiTopIds = rec.aiTopIds                 // 🔹 AI Top IDs 저장
                )
                RouteStorage.getInstance(context).saveRoute(savedRoute)
                Toast.makeText(context, "루트가 저장되었습니다", Toast.LENGTH_SHORT).show()
                showSaveDialog = false
                routeNameInput = ""
            }
        )
    }
}

@Composable
private fun WeatherBanner(w: WeatherInfo?) {
    if (w == null) return
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(Modifier.padding(16.dp)) {
            Text("🌤  현재 날씨  ${w.condition}  •  ${"%.1f".format(w.tempC)}℃")
        }
    }
}

/**
 * 🔹 경로 정보 섹션
 */
@Composable
private fun RouteInfoSection(segments: List<RouteSegment>) {
    val totalDistance = segments.sumOf { it.distanceMeters }
    val totalDuration = segments.sumOf { it.durationSeconds }

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "🚶 보행자 경로",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    "${segments.size}개 구간",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            Spacer(Modifier.height(12.dp))

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
                        if (totalDistance >= 1000) {
                            "%.1f km".format(totalDistance / 1000.0)
                        } else {
                            "$totalDistance m"
                        },
                        style = MaterialTheme.typography.titleLarge,
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
                        formatDuration(totalDuration),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            // 구간별 상세 정보
            if (segments.size > 1) {
                Spacer(Modifier.height(16.dp))
                Divider(color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f))
                Spacer(Modifier.height(12.dp))

                Text(
                    "구간 상세",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )

                Spacer(Modifier.height(8.dp))

                segments.forEachIndexed { index, segment ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "${index + 1}. ${segment.from.name} → ${segment.to.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${segment.distanceMeters}m • ${formatDuration(segment.durationSeconds)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 시간을 "분초" 형식으로 포맷
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
 * 리스트 행: 가게명 오른쪽에 작은 "바로가기" / 아래 쪽에 추천이유, 오른쪽엔 추가/제거 + 배지
 * 지역 힌트가 있으면 검색어에 같이 붙여서 더 정확하게 검색
 */
@Composable
private fun PlaceRow(
    p: Place,
    reason: String?,
    isSelected: Boolean,
    aiMarked: Boolean,
    catTop: Boolean,
    regionHint: String? = null,
    onToggle: () -> Unit
) {
    val context = LocalContext.current

    ListItem(
        headlineContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    p.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // 🔹 가게명 오른쪽 작은 "바로가기"
                TextButton(
                    onClick = {
                        val query = buildNaverQuery(p, regionHint)  // ✅ 지역 + 이름 + 주소
                        val encoded = URLEncoder.encode(query, "UTF-8")
                        val url = "https://m.search.naver.com/search.naver?query=$encoded"
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        context.startActivity(intent)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                    ),
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text(
                        "바로가기",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        },
        supportingContent = {
            Column {
                if (!p.address.isNullOrBlank()) {
                    Text(p.address!!)
                }
                if (!reason.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "추천 이유: $reason",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        trailingContent = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (catTop) SmallBadge("카테고리 Top")
                    if (aiMarked) SmallBadge("AI 추천")
                }
                if (isSelected) {
                    OutlinedButton(
                        onClick = onToggle,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "제거",
                            fontSize = MaterialTheme.typography.labelMedium.fontSize
                        )
                    }
                } else {
                    Button(
                        onClick = onToggle,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "추가",
                            fontSize = MaterialTheme.typography.labelMedium.fontSize
                        )
                    }
                }
            }
        }
    )
    Divider()
}

/** 상단 TopPick 카드 */
@Composable
private fun TopPickCard(
    p: Place,
    reason: String?,
    isSelected: Boolean,
    onView: () -> Unit,
    onToggle: () -> Unit
) {
    Surface(
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .widthIn(min = 240.dp)
            .padding(vertical = 2.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = p.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                SmallBadge("카테고리 Top")
            }

            Spacer(Modifier.height(4.dp))

            Text(
                text = p.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (!reason.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onView,
                    modifier = Modifier.weight(1f)
                ) { Text("보기") }
                if (isSelected) {
                    OutlinedButton(
                        onClick = onToggle,
                        modifier = Modifier.weight(1f)
                    ) { Text("제거") }
                } else {
                    Button(
                        onClick = onToggle,
                        modifier = Modifier.weight(1f)
                    ) { Text("추가") }
                }
            }
        }
    }
}

/** 작고 깔끔한 배지 */
@Composable
private fun SmallBadge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
        tonalElevation = 1.dp
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            maxLines = 1
        )
    }
}

/**
 * 🔹 시작점과 끝점에 커스텀 핀 마커 추가
 */
private fun addStartEndMarkers(map: KakaoMap, start: Place, end: Place) {
    try {
        val manager = map.labelManager ?: return
        val layer = manager.layer ?: return

        // 시작점 마커 (초록색)
        val startBitmap = createStartEndPinBitmap(Color.rgb(52, 168, 83), "출발")
        val startLabel = layer.addLabel(
            LabelOptions.from(LatLng.from(start.lat, start.lng))
                .setStyles(
                    LabelStyles.from(
                        LabelStyle.from(startBitmap).setApplyDpScale(false).setAnchorPoint(0.5f, 1.0f)
                    )
                )
        )
        startLabel?.show()

        // 끝점 마커 (빨간색)
        val endBitmap = createStartEndPinBitmap(Color.rgb(234, 67, 53), "도착")
        val endLabel = layer.addLabel(
            LabelOptions.from(LatLng.from(end.lat, end.lng))
                .setStyles(
                    LabelStyles.from(
                        LabelStyle.from(endBitmap).setApplyDpScale(false).setAnchorPoint(0.5f, 1.0f)
                    )
                )
        )
        endLabel?.show()

        Log.d("UI", "✅ 시작/끝 마커 추가 완료")
    } catch (e: Exception) {
        Log.e("UI", "❌ 시작/끝 마커 추가 실패: ${e.message}", e)
    }
}

/**
 * 🔹 색상이 지정된 핀 마커 비트맵 생성 (Capstone-Backup 방식)
 */
private fun createPinBitmap(context: android.content.Context, colorHex: String): Bitmap? {
    return try {
        val density = context.resources.displayMetrics.density
        val width = (24 * density).toInt()
        val height = (32 * density).toInt()

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            isAntiAlias = true
        }

        val centerX = width / 2f
        val topCircleRadius = width / 2.5f

        val path = Path().apply {
            moveTo(centerX, height.toFloat())
            lineTo(centerX - topCircleRadius * 0.6f, height - topCircleRadius * 1.5f)
            lineTo(centerX + topCircleRadius * 0.6f, height - topCircleRadius * 1.5f)
            close()
        }

        // 핀 색상
        paint.color = Color.parseColor(colorHex)
        paint.style = Paint.Style.FILL

        canvas.drawCircle(centerX, topCircleRadius * 1.2f, topCircleRadius, paint)
        canvas.drawPath(path, paint)

        // 흰색 테두리
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        canvas.drawCircle(centerX, topCircleRadius * 1.2f, topCircleRadius, paint)
        canvas.drawPath(path, paint)

        // 중앙 흰색 점
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        canvas.drawCircle(centerX, topCircleRadius * 1.2f, topCircleRadius * 0.3f, paint)

        bitmap
    } catch (e: Exception) {
        Log.e("UI", "Failed to create pin bitmap", e)
        null
    }
}

/**
 * 🔹 시작/끝 커스텀 핀 비트맵 생성 (색상과 텍스트 포함)
 */
private fun createStartEndPinBitmap(color: Int, text: String): Bitmap {
    val width = 120
    val height = 140
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // 핀 모양 그리기
    val paint = Paint().apply {
        this.color = color
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    // 원형 상단
    canvas.drawCircle(width / 2f, height / 3f, width / 3f, paint)

    // 하단 삼각형 (핀 모양)
    val path = Path().apply {
        moveTo(width / 2f - width / 6f, height / 2f)
        lineTo(width / 2f, height.toFloat())
        lineTo(width / 2f + width / 6f, height / 2f)
        close()
    }
    canvas.drawPath(path, paint)

    // 텍스트 그리기
    val textPaint = Paint().apply {
        this.color = Color.WHITE
        textSize = 32f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        isFakeBoldText = true
    }
    canvas.drawText(text, width / 2f, height / 3f + 12f, textPaint)

    return bitmap
}

private fun clearRoutePolyline(map: KakaoMap) {
    try {
        // RouteLineManager로 경로선 제거
        val routeManager = map.routeLineManager
        val routeLayer = routeManager?.layer
        routeLayer?.removeAll()

        Log.d("UI", "✅ 경로선 제거 완료")
    } catch (e: Exception) {
        Log.e("UI", "❌ 경로선 제거 실패: ${e.message}", e)
    }
}

/**
 * 🔹 선택된 장소 드래그 리스트
 */
@Composable
private fun SelectedPlacesList(
    selectedPlaces: List<Place>,
    selectedOrder: SnapshotStateList<String>,
    onReorder: (Int, Int) -> Unit,
    onRemove: (Place) -> Unit
) {
    val lazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val state = sh.calvin.reorderable.rememberReorderableLazyListState(
        lazyListState = lazyListState,
        onMove = { from, to ->
            onReorder(from.index, to.index)
        }
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 헤더
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Place,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        "선택된 장소 (${selectedPlaces.size}곳)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Divider()

            // 드래그 가능한 리스트
            androidx.compose.foundation.lazy.LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .sh.calvin.reorderable.reorderable(state)
            ) {
                androidx.compose.foundation.lazy.itemsIndexed(selectedPlaces, key = { _, place -> place.id }) { index, place ->
                    sh.calvin.reorderable.ReorderableItem(state, key = place.id) { isDragging ->
                        DraggablePlace(
                            place = place,
                            index = index,
                            isDragging = isDragging,
                            onRemove = { onRemove(place) },
                            dragModifier = Modifier.sh.calvin.reorderable.draggableHandle()
                        )
                    }
                }
            }

            // 힌트 텍스트
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
 * 드래그 가능한 장소 아이템
 */
@Composable
private fun DraggablePlace(
    place: Place,
    index: Int,
    isDragging: Boolean,
    onRemove: () -> Unit,
    dragModifier: Modifier = Modifier
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        color = if (isDragging) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        } else {
            ComposeColor.Transparent
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
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }

            // 장소명
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    place.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
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

            // 제거 버튼
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(36.dp)
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

private fun computeCenter(selected: List<Place>): Pair<Double, Double> {
    val minLat = selected.minOf { it.lat }
    val maxLat = selected.maxOf { it.lat }
    val minLng = selected.minOf { it.lng }
    val maxLng = selected.maxOf { it.lng }
    val centerLat = (minLat + maxLat) / 2.0
    val centerLng = (minLng + maxLng) / 2.0
    return centerLat to centerLng
}

/**
 * 네이버 검색어 생성
 * 우선순위: 지역 힌트 -> 가게 이름 -> 주소
 */
private fun buildNaverQuery(place: Place, regionHint: String? = null): String {
    val parts = mutableListOf<String>()
    if (!regionHint.isNullOrBlank()) {
        parts += regionHint
    }
    parts += place.name
    if (!place.address.isNullOrBlank()) {
        parts += place.address!!
    }
    return parts.joinToString(" ")
}

/**
 * 🔹 루트 저장 다이얼로그
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveRouteDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var routeName by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "루트 저장",
                    style = MaterialTheme.typography.titleLarge
                )

                OutlinedTextField(
                    value = routeName,
                    onValueChange = { routeName = it },
                    label = { Text("루트 이름") },
                    placeholder = { Text("예: 강남 맛집 투어") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("취소")
                    }

                    Button(
                        onClick = {
                            if (routeName.isNotBlank()) {
                                onSave(routeName)
                            }
                        },
                        enabled = routeName.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("저장")
                    }
                }
            }
        }
    }
}

/**
 * 🔹 경로 구간 리스트 (접이식)
 */
@Composable
private fun RouteSegmentsList(
    segments: List<RouteSegment>,
    selectedPlaces: List<Place>,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    highlightedIndex: Int?,
    onSegmentClick: (Int) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 헤더 (항상 표시)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.DirectionsWalk,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        "경로 구간 보기",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    if (expanded) "▲" else "▼",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            // 타임라인 리스트 (펼쳤을 때만 표시)
            if (expanded) {
                Divider()

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    // 시작점
                    TimelinePlace(
                        place = selectedPlaces.firstOrNull(),
                        index = 0,
                        isFirst = true,
                        isLast = false
                    )

                    // 각 구간과 도착지
                    segments.forEachIndexed { index, segment ->
                        val isHighlighted = highlightedIndex == index

                        // 구간 색상
                        val colors = listOf(
                            ComposeColor(0xFF4285F4),   // 파란색
                            ComposeColor(0xFFEA4335),   // 빨간색
                            ComposeColor(0xFFFBBC05),   // 노란색
                            ComposeColor(0xFF34A853),   // 초록색
                            ComposeColor(0xFF9C27B0),   // 보라색
                            ComposeColor(0xFFFF6D00),   // 주황색
                        )
                        val segmentColor = colors[index % colors.size]

                        // 이동 구간
                        TimelineSegment(
                            segment = segment,
                            segmentColor = segmentColor,
                            isHighlighted = isHighlighted,
                            onClick = { onSegmentClick(index) }
                        )

                        // 도착 장소
                        TimelinePlace(
                            place = selectedPlaces.getOrNull(index + 1),
                            index = index + 1,
                            isFirst = false,
                            isLast = index == segments.size - 1
                        )
                    }
                }

                // 힌트 텍스트
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "💡 구간을 탭하면 지도에서 해당 경로가 강조됩니다",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

/**
 * 타임라인 장소 노드
 */
@Composable
private fun TimelinePlace(
    place: Place?,
    index: Int,
    isFirst: Boolean,
    isLast: Boolean
) {
    if (place == null) return

    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 순서 번호
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(
                    if (isFirst) MaterialTheme.colorScheme.tertiary
                    else if (isLast) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "${index + 1}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = ComposeColor.White
            )
        }

        // 장소명
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                place.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
            if (isFirst) {
                Text(
                    "출발",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            } else if (isLast) {
                Text(
                    "도착",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * 타임라인 이동 구간
 */
@Composable
private fun TimelineSegment(
    segment: RouteSegment,
    segmentColor: ComposeColor,
    isHighlighted: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 왼쪽: 세로 라인 + 도보 아이콘
        Box(
            modifier = Modifier.width(32.dp),
            contentAlignment = Alignment.Center
        ) {
            // 세로 라인
            Box(
                modifier = Modifier
                    .width(if (isHighlighted) 4.dp else 2.dp)
                    .height(60.dp)
                    .background(
                        if (isHighlighted) segmentColor else segmentColor.copy(alpha = 0.3f)
                    )
            )

            // 도보 아이콘
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(
                        if (isHighlighted) segmentColor.copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.DirectionsWalk,
                    contentDescription = null,
                    tint = if (isHighlighted) segmentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // 오른쪽: 이동 정보
        Surface(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 12.dp),
            color = if (isHighlighted) {
                segmentColor.copy(alpha = 0.1f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            },
            shape = MaterialTheme.shapes.small
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        "도보 이동",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isHighlighted) segmentColor else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "약 ${segment.durationSeconds / 60}분",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Medium,
                            color = if (isHighlighted) segmentColor else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "•",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            if (segment.distanceMeters >= 1000) {
                                "%.1f km".format(segment.distanceMeters / 1000.0)
                            } else {
                                "${segment.distanceMeters} m"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Medium,
                            color = if (isHighlighted) segmentColor else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                if (isHighlighted) {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = null,
                        tint = segmentColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * 🔹 추천 장소 접이식 리스트
 */
@Composable
private fun PlacesExpandableList(
    places: List<Place>,
    selectedOrder: List<String>,
    topIds: Set<String>,
    aiTopIds: Set<String>,
    gptReasons: Map<String, String>,
    regionHint: String?,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onPlaceToggle: (Place) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 헤더 (항상 표시)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "추천 장소 (${places.size}개)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (expanded) "▲" else "▼",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            // 장소 리스트 (펼쳤을 때만 표시)
            if (expanded) {
                Divider()
                
                places.forEach { place ->
                    PlaceRow(
                        p = place,
                        reason = gptReasons[place.id],
                        isSelected = selectedOrder.contains(place.id),
                        aiMarked = aiTopIds.contains(place.id),
                        catTop = topIds.contains(place.id),
                        regionHint = regionHint,
                        onToggle = { onPlaceToggle(place) }
                    )
                }
            }
        }
    }
}
