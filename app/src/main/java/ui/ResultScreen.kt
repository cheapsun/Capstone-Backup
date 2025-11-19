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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.project_2.data.route.TmapPedestrianService
import com.example.project_2.domain.model.Place
import com.example.project_2.domain.model.RecommendationResult
import com.example.project_2.domain.model.RouteSegment
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
    onBack: () -> Unit            // ✅ 뒤로가기 콜백
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
    LaunchedEffect(kakaoMap, selectedOrder.toList(), rec.places, showRealRoute, routeSegments) {
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

        Log.d("UI", "✅ Markers added: ${labelPlaceMap.size}")

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
                        val color = colors[index % colors.size]

                        val options = RouteLineOptions.from(
                            RouteLineSegment.from(coords)
                                .setStyles(
                                    RouteLineStyles.from(
                                        RouteLineStyle.from(18f, color)
                                    )
                                )
                        )

                        val routeLine = routeLineManager.layer?.addRouteLine(options)
                        routeLine?.show()

                        Log.d("UI", "경로 ${index + 1}: ${coords.size}개 좌표, 색상=${String.format("#%06X", color and 0xFFFFFF)}")
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

        // 추천 장소 타이틀
        item(key = "list_title") {
            Text(
                "추천 장소",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        // 추천 장소 리스트
        items(rec.places, key = { it.id }) { p ->
            PlaceRow(
                p = p,
                reason = rec.gptReasons[p.id],
                isSelected = selectedOrder.contains(p.id),
                aiMarked = rec.aiTopIds.contains(p.id),
                catTop = topIds.contains(p.id),
                regionHint = regionHint,   // ✅ 지역 힌트 넘김
                onToggle = {
                    toggleSelect(p)
                    focusOn(p)
                }
            )
        }

        // 🔹 하단 액션 (T-Map 경로 생성 버튼 추가)
        item(key = "actions") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
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
                    modifier = Modifier.weight(2f)
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
        }
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

    // reason = "요약 한 줄\nGPT 상세 설명..." 형태라서
    // 첫 줄 / 두 번째 줄로 나눈다.
    val (summaryLine, detailLine) = remember(reason) {
        if (reason.isNullOrBlank()) {
            "" to ""
        } else {
            val parts = reason.split('\n', limit = 2)
            val summary = parts.getOrNull(0).orEmpty()
            val detail = parts.getOrNull(1).orEmpty()
            summary to detail
        }
    }

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
                        val query = buildNaverQuery(p, regionHint)
                        val encoded = URLEncoder.encode(query, "UTF-8")
                        val url = "https://m.search.naver.com/search.naver?query=$encoded"
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                    ),
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("바로가기", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        supportingContent = {
            Column {
                if (!p.address.isNullOrBlank()) {
                    Text(p.address!!)
                }

                // 👉 1줄 요약
                if (summaryLine.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "추천 이유: $summaryLine",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 👉 GPT가 써 준 자세한 설명 (항상 바로 아래에 표시)
                if (detailLine.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = detailLine,
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
                        Text("제거", fontSize = MaterialTheme.typography.labelMedium.fontSize)
                    }
                } else {
                    Button(
                        onClick = onToggle,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("추가", fontSize = MaterialTheme.typography.labelMedium.fontSize)
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

    val rawReason = reason.orEmpty()
    val reasonLines = rawReason.lines()
    val summary = reasonLines.firstOrNull().orEmpty()
    val detail = reasonLines.drop(1).joinToString("\n").trim()

    var expanded by remember(p.id) { mutableStateOf(false) }

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

            if (summary.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "추천 이유: $summary",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (detail.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        text = if (expanded) "접기" else "자세히",
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                if (expanded) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
