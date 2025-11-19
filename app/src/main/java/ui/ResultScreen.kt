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
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import org.burnoutcrew.reorderable.*
import com.example.project_2.data.RouteStorage
import com.example.project_2.data.route.TmapPedestrianService
import com.example.project_2.domain.model.Category
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
    mandatoryPlaceName: String? = null,  // ✅ 필수 장소 이름 (자동 선택용)
    onNavigateToItinerary: (List<Place>, Boolean) -> Unit = { _, _ -> }  // ✅ 일정 생성 화면으로 이동 (places, autoAddMeals)
) {
    Log.d("UI", "ResultScreen received ${rec.places.size} places (topPicks=${rec.topPicks.size}, mandatoryPlace=$mandatoryPlaceName)")
    rec.places.forEachIndexed { i, p ->
        Log.d("UI", "[$i] ${p.name} (${p.lat}, ${p.lng}) reason=${rec.gptReasons[p.id] ?: "없음"}")
    }

    var kakaoMap by remember { mutableStateOf<KakaoMap?>(null) }
    val labelPlaceMap = remember { mutableMapOf<Label, Place>() }
    var highlightedId by remember { mutableStateOf<String?>(null) }
    var showMealDialog by remember { mutableStateOf(false) }

    // 🔹 선택된 장소 리스트 (드래그 순서 유지를 위해 mutableStateListOf 사용)
    val selectedPlaces = remember { mutableStateListOf<Place>() }

    // ✅ 필수 장소 자동 선택
    LaunchedEffect(mandatoryPlaceName) {
        if (!mandatoryPlaceName.isNullOrBlank() && rec.places.isNotEmpty()) {
            // 첫 번째 장소가 필수 장소라고 가정 (MainViewModel에서 맨 앞에 추가함)
            val mandatoryPlace = rec.places.firstOrNull()
            if (mandatoryPlace != null && !selectedPlaces.contains(mandatoryPlace)) {
                selectedPlaces.add(mandatoryPlace)
                Log.d("UI", "Auto-selected mandatory place: ${mandatoryPlace.name}")
            }
        }
    }

    // 🔹 T-Map 라우팅 상태
    var routeSegments by remember { mutableStateOf<List<RouteSegment>>(emptyList()) }
    var isLoadingRoute by remember { mutableStateOf(false) }
    var showRealRoute by remember { mutableStateOf(false) }

    val topIds: Set<String> = remember(rec.topPicks) { rec.topPicks.map { it.id }.toSet() }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 🔹 Reorderable state for drag-and-drop
    val reorderableState = rememberReorderableLazyListState(
        onMove = { from, to ->
            if (from.index >= 0 && from.index < selectedPlaces.size &&
                to.index >= 0 && to.index <= selectedPlaces.size) {
                val item = selectedPlaces.removeAt(from.index)
                selectedPlaces.add(to.index, item)
            }
        }
    )

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

    // 🔹 접기/펼치기 및 구간별 포커스 상태
    var isRouteInfoExpanded by remember { mutableStateOf(true) }
    var isPlaceListExpanded by remember { mutableStateOf(true) }
    var selectedSegmentIndex by remember { mutableStateOf<Int?>(null) }
    val routeLines = remember { mutableStateMapOf<Int, RouteLine>() }

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
    LaunchedEffect(kakaoMap, selectedPlaces.toList(), rec.places, showRealRoute, routeSegments, selectedSegmentIndex, isPlaceListExpanded) {
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

        Log.d("UI", "LaunchedEffect: Adding ${rec.places.size} markers")

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

        // 모든 추천 장소에 마커 표시
        rec.places.forEach { place ->
            val selectedIndex = selectedPlaces.indexOfFirst { it.id == place.id }
            val isSelected = selectedIndex != -1
            val isTopPick = topIds.contains(place.id)

            // 🔹 장소 리스트가 접혀있으면 선택된 장소만 표시
            if (!isPlaceListExpanded && !isSelected) {
                return@forEach  // 선택되지 않은 장소는 마커 추가 안 함
            }

            val options = LabelOptions.from(LatLng.from(place.lat, place.lng))
                .setClickable(true)

            when {
                isSelected -> {
                    // 선택된 장소: 구간별 색상 핀 + 번호 (RouteMapScreen과 동일한 스타일)
                    val numberedBitmap = createNumberedPinBitmap(
                        context = context,
                        number = selectedIndex + 1,
                        color = segmentColors[selectedIndex % segmentColors.size],
                        alpha = 1.0f,
                        scale = 1.0f
                    )
                    options.setStyles(
                        LabelStyles.from(LabelStyle.from(numberedBitmap).setApplyDpScale(false))
                    )
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

        Log.d("UI", "✅ Markers added: ${labelPlaceMap.size}")

        // 🔹 실제 경로 표시 (색상 코딩 + 구간별 포커스)
        // removeAll()로 이미 제거되었으므로 map만 클리어
        routeLines.clear()

        Log.d("UI", "🔍 경로 표시 조건: showRealRoute=$showRealRoute, routeSegments.size=${routeSegments.size}")

        if (showRealRoute && routeSegments.isNotEmpty()) {
            try {
                Log.d("UI", "🚀 경로선 그리기 시작: ${routeSegments.size}개 구간")
                routeSegments.forEachIndexed { index, segment ->
                    val coords = segment.pathCoordinates
                    Log.d("UI", "  구간 ${index + 1}: ${segment.from.name} → ${segment.to.name}, 좌표 ${coords.size}개")
                    if (coords.size >= 2) {
                        val colorHex = segmentColors[index % segmentColors.size]
                        val baseColor = Color.parseColor(colorHex)

                        // 선택된 구간 여부에 따라 스타일 조정
                        val currentSelectedIndex = selectedSegmentIndex
                        val isSelected = when (currentSelectedIndex) {
                            null -> false // 전체 보기
                            else -> index == currentSelectedIndex
                        }

                        val alpha = when {
                            currentSelectedIndex == null -> 0.7f // 전체 보기
                            isSelected -> 1.0f // 선택된 구간
                            else -> 0.0f // 선택되지 않은 구간 완전히 숨김 (겹침 방지)
                        }
                        val width = if (isSelected) 10f else 8f

                        // alpha 값을 포함한 color 생성
                        val red = Color.red(baseColor)
                        val green = Color.green(baseColor)
                        val blue = Color.blue(baseColor)
                        val colorWithAlpha = Color.argb((alpha * 255).toInt(), red, green, blue)

                        val options = RouteLineOptions.from(
                            RouteLineSegment.from(coords)
                                .setStyles(
                                    RouteLineStyles.from(
                                        RouteLineStyle.from(width, colorWithAlpha)
                                    )
                                )
                        )

                        val routeLine = routeLineManager.layer?.addRouteLine(options)
                        if (routeLine != null) {
                            routeLine.show()
                            routeLines[index] = routeLine
                            Log.d("UI", "✅ 경로 ${index + 1}: ${coords.size}개 좌표, 색상=${String.format("#%06X", baseColor and 0xFFFFFF)}, 투명도=$alpha")
                        } else {
                            Log.e("UI", "❌ 경로 ${index + 1} 추가 실패: addRouteLine returned null")
                        }
                    } else {
                        Log.w("UI", "⚠️ 구간 ${index + 1}: 좌표가 부족함 (${coords.size}개)")
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
        if (selectedPlaces.any { it.id == p.id }) {
            selectedPlaces.removeAll { it.id == p.id }
        } else {
            selectedPlaces.add(p)
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
                    segments.forEachIndexed { idx, seg ->
                        Log.d("UI", "  구간 ${idx + 1}: ${seg.from.name} → ${seg.to.name}, 좌표 ${seg.pathCoordinates.size}개")
                    }

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
                    .height(260.dp)
                    .nestedScroll(mapNestedScrollConnection)
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

        // 🔹 선택된 장소 섹션 (드래그 가능한 별도 영역)
        if (selectedPlaces.isNotEmpty()) {
            item(key = "selected_places_section") {
                SelectedPlacesSection(
                    selectedPlaces = selectedPlaces,
                    reorderableState = reorderableState,
                    onRemove = { place ->
                        selectedPlaces.remove(place)
                        routeSegments = emptyList()
                        showRealRoute = false
                    }
                )
            }
        }

        // 🔹 경로 정보 (경로가 생성되면 표시)
        if (showRealRoute && routeSegments.isNotEmpty()) {
            item(key = "route_info") {
                RouteInfoSection(
                    segments = routeSegments,
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
                    items(rec.topPicks, key = { "top_${it.id}" }) { p ->
                        TopPickCard(
                            p = p,
                            reason = rec.gptReasons[p.id],
                            isSelected = selectedPlaces.any { it.id == p.id },
                            isMandatory = !mandatoryPlaceName.isNullOrBlank() && p == rec.places.firstOrNull(),
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

        // 🔹 추천 장소 리스트 (접기/펼치기 가능)
        item(key = "recommended_places") {
            RecommendedPlacesCard(
                places = rec.places,
                gptReasons = rec.gptReasons,
                aiTopIds = rec.aiTopIds,
                topIds = topIds,
                selectedPlaces = selectedPlaces,
                regionHint = regionHint,
                mandatoryPlaceName = mandatoryPlaceName,
                isExpanded = isPlaceListExpanded,
                onToggleExpand = { isPlaceListExpanded = !isPlaceListExpanded },
                onToggle = { place ->
                    toggleSelect(place)
                    focusOn(place)
                }
            )
        }

        // 🔹 하단 액션 (T-Map 경로 생성 버튼 추가)
        item(key = "actions") {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 2.dp,
                shadowElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 선택 초기화 버튼
                    OutlinedButton(
                        onClick = {
                            selectedPlaces.clear()
                            routeSegments = emptyList()
                            showRealRoute = false
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "선택 초기화",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // 일정 생성하기 버튼
                    Button(
                        onClick = {
                            // FOOD 장소 개수 확인
                            val foodCount = selectedPlaces.count { it.category == Category.FOOD }
                            if (foodCount < 2) {
                                // 맛집이 부족하면 다이얼로그 표시
                                showMealDialog = true
                            } else {
                                // 충분하면 바로 일정 생성 (식사 자동 추가 불필요)
                                onNavigateToItinerary(selectedPlaces.toList(), false)
                            }
                        },
                        enabled = selectedPlaces.size >= 3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = MaterialTheme.shapes.medium,
                        elevation = ButtonDefaults.buttonElevation(
                            defaultElevation = 4.dp,
                            pressedElevation = 8.dp,
                            disabledElevation = 0.dp
                        )
                    ) {
                        Icon(
                            Icons.Default.CalendarMonth,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "일정 생성하기 (${selectedPlaces.size}개)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // 안내 텍스트
                    if (selectedPlaces.size < 3) {
                        Text(
                            "최소 3개 장소를 선택해주세요",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    // 루트 저장 버튼 (루트 생성 완료 후에만 표시)
                    if (showRealRoute && routeSegments.isNotEmpty()) {
                        Button(
                            onClick = { showSaveDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary
                            ),
                            shape = MaterialTheme.shapes.medium,
                            elevation = ButtonDefaults.buttonElevation(
                                defaultElevation = 4.dp,
                                pressedElevation = 8.dp
                            )
                        ) {
                            Text(
                                "✓ 루트 저장하기",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
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
                    places = selectedPlaces,
                    routeSegments = routeSegments
                )
                RouteStorage.getInstance(context).saveRoute(savedRoute)
                Toast.makeText(context, "루트가 저장되었습니다", Toast.LENGTH_SHORT).show()
                showSaveDialog = false
                routeNameInput = ""
            }
        )
    }

    // 🔹 식사 시간 자동 추가 확인 다이얼로그
    if (showMealDialog) {
        MealAutoInsertDialog(
            foodCount = selectedPlaces.count { it.category == Category.FOOD },
            onDismiss = { showMealDialog = false },
            onConfirm = { autoAdd ->
                showMealDialog = false
                onNavigateToItinerary(selectedPlaces.toList(), autoAdd)
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
 * 🔹 선택된 장소 섹션 (드래그 가능)
 */
@Composable
private fun SelectedPlacesSection(
    selectedPlaces: List<Place>,
    reorderableState: ReorderableLazyListState,
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
                    "선택된 장소 (${selectedPlaces.size}개)",
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
                .heightIn(max = 400.dp)  // 최대 높이 제한
                .reorderable(reorderableState),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(selectedPlaces, key = { _, place -> place.id }) { index, place ->
                ReorderableItem(reorderableState, key = place.id) { isDragging ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
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

                            // 순서 번호
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.shapes.small
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "${index + 1}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }

                            // 장소 이름
                            Text(
                                place.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            // 제거 버튼
                            IconButton(
                                onClick = { onRemove(place) },
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
            }
        }
    }
}

/**
 * 🔹 경로 정보 섹션 (접기/펼치기 + 구간별 클릭)
 */
@Composable
private fun RouteInfoSection(
    segments: List<RouteSegment>,
    isExpanded: Boolean,
    selectedSegmentIndex: Int?,
    segmentColors: List<String>,
    onToggleExpand: () -> Unit,
    onSegmentClick: (Int) -> Unit
) {
    val totalDistance = segments.sumOf { it.distanceMeters }
    val totalDuration = segments.sumOf { it.durationSeconds }

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
                    "🚶 루트 정보 (${segments.size}개 구간)",
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
                            if (totalDistance >= 1000) {
                                "%.1f km".format(totalDistance / 1000.0)
                            } else {
                                "$totalDistance m"
                            },
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
                            formatDuration(totalDuration),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // 구간별 상세 정보
                if (segments.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))

                    segments.forEachIndexed { index, segment ->
                        SegmentTimelineItem(
                            index = index,
                            segment = segment,
                            color = segmentColors[index % segmentColors.size],
                            isSelected = selectedSegmentIndex == index,
                            isLast = index == segments.size - 1,
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
    isMandatory: Boolean = false,
    regionHint: String? = null,
    onToggle: () -> Unit
) {
    val context = LocalContext.current

    ListItem(
        modifier = Modifier.padding(vertical = 4.dp),
        headlineContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isMandatory) {
                        Text(
                            text = "⭐ ",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        p.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
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
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(
                        "🔍",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "바로가기",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!p.address.isNullOrBlank()) {
                    Text(
                        p.address!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!reason.isNullOrBlank()) {
                    Text(
                        text = "💡 $reason",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        trailingContent = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (catTop) SmallBadge("카테고리 Top")
                    if (aiMarked) SmallBadge("AI 추천")
                }
                if (isSelected) {
                    OutlinedButton(
                        onClick = onToggle,
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                    ) {
                        Text(
                            "제거",
                            fontSize = MaterialTheme.typography.labelLarge.fontSize,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                } else {
                    Button(
                        onClick = onToggle,
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        elevation = ButtonDefaults.buttonElevation(
                            defaultElevation = 2.dp
                        )
                    ) {
                        Text(
                            "추가",
                            fontSize = MaterialTheme.typography.labelLarge.fontSize,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    )
}

/** 상단 TopPick 카드 */
@Composable
private fun TopPickCard(
    p: Place,
    reason: String?,
    isSelected: Boolean,
    isMandatory: Boolean = false,
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
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isMandatory) {
                        Text(
                            text = "⭐ ",
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    Text(
                        text = p.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                SmallBadge(if (isMandatory) "필수 장소" else "카테고리 Top")
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
 * 🔹 번호가 있는 핀 마커 비트맵 생성 (RouteMapScreen과 동일한 스타일)
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
 * 🔹 식사 시간 자동 추가 확인 다이얼로그
 */
@Composable
private fun MealAutoInsertDialog(
    foodCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit
) {
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
                    "식사 시간 추가",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    buildString {
                        append("선택한 장소에 맛집이 ")
                        append(if (foodCount == 0) "없습니다." else "${foodCount}개만 있습니다.")
                        append("\n\n")
                        append("점심(12:00)과 저녁(18:00) 식사 시간을 ")
                        append("자동으로 추가할까요?")
                        append("\n\n")
                        append("※ 일정 화면에서 언제든지 삭제할 수 있습니다.")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onConfirm(false) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("추가 안 함")
                    }

                    Button(
                        onClick = { onConfirm(true) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("추가하기")
                    }
                }
            }
        }
    }
}

/**
 * 🔹 추천 장소 카드 (접기/펼치기 가능)
 */
@Composable
private fun RecommendedPlacesCard(
    places: List<Place>,
    gptReasons: Map<String, String>,
    aiTopIds: Set<String>,
    topIds: Set<String>,
    selectedPlaces: List<Place>,
    regionHint: String?,
    mandatoryPlaceName: String? = null,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggle: (Place) -> Unit
) {
    val rotationAngle by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(300),
        label = "rotation"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .animateContentSize(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column {
            // 헤더 (클릭 시 접기/펼치기)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() }
                    .padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "📍",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Column {
                        Text(
                            "추천 장소",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "${places.size}개",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "접기" else "펼치기",
                    modifier = Modifier.rotate(rotationAngle),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            if (isExpanded) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                Spacer(Modifier.height(8.dp))

                places.forEachIndexed { index, p ->
                    PlaceRow(
                        p = p,
                        reason = gptReasons[p.id],
                        isSelected = selectedPlaces.any { it.id == p.id },
                        aiMarked = aiTopIds.contains(p.id),
                        catTop = topIds.contains(p.id),
                        isMandatory = !mandatoryPlaceName.isNullOrBlank() && p == places.firstOrNull(),
                        regionHint = regionHint,
                        onToggle = { onToggle(p) }
                    )

                    if (index < places.size - 1) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
