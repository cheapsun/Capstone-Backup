package com.example.project_2.ui.region

import android.graphics.Color
import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.project_2.data.AdminPolygon
import com.example.project_2.data.DongLabel
import com.example.project_2.data.KakaoLocalService
import com.example.project_2.data.VWorldService
import com.kakao.vectormap.*
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.LabelOptions
import com.kakao.vectormap.label.LabelStyle
import com.kakao.vectormap.label.LabelStyles
import com.kakao.vectormap.label.LabelTextStyle
import com.kakao.vectormap.shape.Polygon
import com.kakao.vectormap.shape.PolygonOptions
import com.kakao.vectormap.shape.PolygonStyle
import com.kakao.vectormap.shape.MapPoints
import com.kakao.vectormap.shape.DotPoints
import com.kakao.vectormap.route.RouteLineOptions
import com.kakao.vectormap.route.RouteLineSegment
import com.kakao.vectormap.route.RouteLineStyle
import com.kakao.vectormap.route.RouteLineStyles
import kotlinx.coroutines.launch

/**
 * 🗺️ 지역 선택 BottomSheet
 *
 * 사용자가 지역을 입력하면:
 * 1. Kakao 지도에 해당 지역을 표시
 * 2. VWorld API로 행정구역 경계(폴리곤) 표시
 * 3. 읍/면/동 이름 라벨 표시
 * 4. 두 가지 검색 옵션 제공:
 *    - 전체 지역 검색 (폴리곤 기반)
 *    - 특정 위치 검색 (8km 반경)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegionSelectBottomSheet(
    regionQuery: String,
    onDismiss: () -> Unit,
    onRegionSelected: (String) -> Unit  // ✅ 단순화: 지역명만 반환
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 🔹 상태 관리
    var kakaoMap by remember { mutableStateOf<KakaoMap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var centerLat by remember { mutableStateOf<Double?>(null) }
    var centerLng by remember { mutableStateOf<Double?>(null) }
    var currentRegionName by remember { mutableStateOf(regionQuery) }

    var adminPolygons by remember { mutableStateOf<List<AdminPolygon>>(emptyList()) }
    var dongLabels by remember { mutableStateOf<List<DongLabel>>(emptyList()) }

    // 🔹 이전 상태 저장 (실수로 클릭한 경우 되돌리기용)
    var previousRegionName by remember { mutableStateOf<String?>(null) }
    var previousAdminPolygons by remember { mutableStateOf<List<AdminPolygon>>(emptyList()) }
    var previousDongLabels by remember { mutableStateOf<List<DongLabel>>(emptyList()) }

    // 🔹 지역 데이터 로드
    LaunchedEffect(regionQuery) {
        isLoading = true
        errorMessage = null

        try {
            Log.d("RegionSelect", "🔍 지역 검색 시작: $regionQuery")

            // 1. Geocoding: 지역명 → 좌표
            val coords = KakaoLocalService.geocode(regionQuery)
            if (coords == null) {
                errorMessage = "지역을 찾을 수 없습니다"
                isLoading = false
                return@LaunchedEffect
            }

            val (lat, lng) = coords
            centerLat = lat
            centerLng = lng

            Log.d("RegionSelect", "✅ 좌표 찾음: ($lat, $lng)")

            // 2. 사용자가 입력한 검색어를 그대로 유지 (역지오코딩하지 않음)
            currentRegionName = regionQuery

            Log.d("RegionSelect", "✅ 지역명: $currentRegionName")

            // 3. VWorld API: 행정구역 경계 폴리곤
            val vworldQuery = regionQuery

            adminPolygons = VWorldService.getAdminBoundary(vworldQuery)
            Log.d("RegionSelect", "✅ 폴리곤 ${adminPolygons.size}개 로드")

            // 4. 구 레벨 라벨 생성 (시/군/구 이름만, 읍/면/동 X)
            dongLabels = adminPolygons.map { polygon ->
                // 폴리곤 중심점 계산
                val centerLat = polygon.coordinates.map { it.lat }.average()
                val centerLng = polygon.coordinates.map { it.lng }.average()

                DongLabel(
                    name = polygon.name,
                    centerLat = centerLat,
                    centerLng = centerLng
                )
            }
            Log.d("RegionSelect", "✅ 구 라벨 ${dongLabels.size}개 생성 (시/군/구 레벨)")

            // 5. 지도 카메라 이동
            kakaoMap?.let { map ->
                map.moveCamera(CameraUpdateFactory.newCenterPosition(LatLng.from(lat, lng), 13))
            }

        } catch (e: Exception) {
            Log.e("RegionSelect", "❌ 지역 로드 실패: ${e.message}", e)
            errorMessage = "지역 정보를 불러올 수 없습니다"
        } finally {
            isLoading = false
        }
    }

    // 🔹 지도에 경계선 및 라벨 표시
    LaunchedEffect(kakaoMap, adminPolygons, dongLabels) {
        val map = kakaoMap ?: run {
            Log.e("RegionSelect", "❌ LaunchedEffect: kakaoMap is null")
            return@LaunchedEffect
        }
        val routeLineManager = map.routeLineManager ?: run {
            Log.e("RegionSelect", "❌ LaunchedEffect: routeLineManager is null")
            return@LaunchedEffect
        }
        val labelManager = map.labelManager ?: run {
            Log.e("RegionSelect", "❌ LaunchedEffect: labelManager is null")
            return@LaunchedEffect
        }
        val shapeManager = map.shapeManager ?: run {
            Log.e("RegionSelect", "❌ LaunchedEffect: shapeManager is null")
            return@LaunchedEffect
        }

        try {
            Log.d("RegionSelect", "🎨 ===== 경계선/라벨 그리기 시작 =====")
            Log.d("RegionSelect", "📊 adminPolygons.size = ${adminPolygons.size}")
            Log.d("RegionSelect", "📊 dongLabels.size = ${dongLabels.size}")

            // 기존 경계선, 폴리곤 및 라벨 제거
            routeLineManager.layer?.removeAll()
            labelManager.layer?.removeAll()
            shapeManager.layer?.removeAll()  // ✅ 폴리곤 레이어도 제거
            Log.d("RegionSelect", "✅ 기존 레이어 제거 완료")

            // 폴리곤 그리기 (경계선만 표시 - RouteLine 사용)
            adminPolygons.forEachIndexed { idx, polygon ->
                Log.d("RegionSelect", "🔹 폴리곤 $idx 처리 중: name=${polygon.name}, coords=${polygon.coordinates.size}")

                if (polygon.coordinates.isEmpty()) {
                    Log.w("RegionSelect", "⚠️ 폴리곤 $idx 좌표 없음: ${polygon.name}")
                    return@forEachIndexed
                }

                // ✅ 닫힌 경로를 위해 첫 번째 좌표를 마지막에 추가
                val kakaoCoords = polygon.coordinates.map {
                    LatLng.from(it.lat, it.lng)
                }.toMutableList().apply {
                    if (isNotEmpty()) add(first())  // 시작점 = 끝점으로 닫힌 경로
                }

                Log.d("RegionSelect", "🔹 폴리곤 $idx KakaoCoords 생성: ${kakaoCoords.size}개 (첫=${kakaoCoords.firstOrNull()}, 끝=${kakaoCoords.lastOrNull()})")

                try {
                    // ✅ 1단계: 반투명 채우기 (Polygon) - GeoJSON 방식
                    val coordinates = kakaoCoords.joinToString(",") { "[${it.longitude},${it.latitude}]" }
                    val geoJson = """{"type":"Polygon","coordinates":[[$coordinates]]}"""

                    val fillStyle = PolygonStyle.from(
                        Color.argb(40, 66, 133, 244)  // 반투명 파란색 채우기 (Material Blue)
                    )

                    val polygonOptions = PolygonOptions.from(geoJson, fillStyle)

                    val filledPolygon = shapeManager.layer?.addPolygon(polygonOptions)
                    if (filledPolygon != null) {
                        filledPolygon.show()
                        Log.d("RegionSelect", "✅ 폴리곤 $idx 채우기 성공: ${polygon.name}")
                    } else {
                        Log.w("RegionSelect", "⚠️ 폴리곤 $idx 채우기 실패: ${polygon.name}")
                    }

                    // ✅ 2단계: 경계선 그리기 (RouteLine) - 부드러운 파란색
                    val segment = RouteLineSegment.from(kakaoCoords)
                        .setStyles(
                            RouteLineStyles.from(
                                RouteLineStyle.from(
                                    4f,  // 선 두께 (적당한 굵기)
                                    Color.argb(180, 66, 133, 244)  // 부드러운 파란색 (눈에 편안함)
                                )
                            )
                        )

                    Log.d("RegionSelect", "🔹 폴리곤 $idx RouteLineSegment 생성 완료")

                    val options = RouteLineOptions.from(segment)
                    val routeLine = routeLineManager.layer?.addRouteLine(options)

                    Log.d("RegionSelect", "🔹 폴리곤 $idx RouteLine 추가: routeLine=${routeLine != null}, layer=${routeLineManager.layer != null}")

                    if (routeLine != null) {
                        routeLine.show()
                        Log.d("RegionSelect", "✅ 폴리곤 $idx 경계선 표시 성공: ${polygon.name}")
                    } else {
                        Log.e("RegionSelect", "❌ 폴리곤 $idx RouteLine이 null: ${polygon.name}")
                    }
                } catch (e: Exception) {
                    Log.e("RegionSelect", "❌ 폴리곤 $idx 경계선 그리기 실패: ${e.javaClass.simpleName} - ${e.message}", e)
                    e.printStackTrace()
                }
            }

            Log.d("RegionSelect", "🎨 경계선 그리기 완료 (${adminPolygons.size}개 처리)")

            // 동 라벨 그리기
            Log.d("RegionSelect", "🏷️ 동 라벨 그리기 시작: ${dongLabels.size}개")
            val textStyle = LabelStyles.from(
                LabelStyle.from(LabelTextStyle.from(24, Color.BLACK, 2, Color.WHITE))
            )

            dongLabels.forEachIndexed { idx, label ->
                try {
                    val options = LabelOptions.from(LatLng.from(label.centerLat, label.centerLng))
                        .setStyles(textStyle)
                        .setTexts(label.name)

                    val addedLabel = labelManager.layer?.addLabel(options)
                    Log.d("RegionSelect", "🏷️ 라벨 $idx 추가: ${label.name} at (${label.centerLat}, ${label.centerLng}), success=${addedLabel != null}")
                } catch (e: Exception) {
                    Log.e("RegionSelect", "❌ 라벨 $idx 추가 실패: ${e.message}", e)
                }
            }

            Log.d("RegionSelect", "✅ 라벨 ${dongLabels.size}개 표시 완료")
            Log.d("RegionSelect", "🎨 ===== 경계선/라벨 그리기 종료 =====")

        } catch (e: Exception) {
            Log.e("RegionSelect", "❌❌❌ 폴리곤/라벨 그리기 전체 실패: ${e.javaClass.simpleName} - ${e.message}", e)
            e.printStackTrace()
        }
    }

    // 🔹 BottomSheet UI
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { false }  // ✅ 모든 상태 변경 차단 = 드래그 불가
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,  // 드래그 핸들 UI 제거
        modifier = Modifier.fillMaxHeight(0.9f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // 헤더: 지역 이름 + 이전으로 버튼
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = currentRegionName.also {
                        Log.d("RegionSelect", "📍 UI에 표시되는 지역명: '$it'")
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                // ✅ 이전으로 돌아가기 버튼
                if (previousRegionName != null) {
                    IconButton(
                        onClick = {
                            Log.d("RegionSelect", "⬅️ 이전으로: $previousRegionName")
                            currentRegionName = previousRegionName ?: regionQuery
                            adminPolygons = previousAdminPolygons
                            dongLabels = previousDongLabels
                            previousRegionName = null
                            previousAdminPolygons = emptyList()
                            previousDongLabels = emptyList()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "이전으로"
                        )
                    }
                }
            }

            // 로딩 또는 에러 표시
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text("지역 정보를 불러오는 중...")
                        }
                    }
                }
                errorMessage != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = errorMessage!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                else -> {
                    // 지도 표시
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                MapView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    start(
                                        object : MapLifeCycleCallback() {
                                            override fun onMapDestroy() {
                                                kakaoMap = null
                                            }
                                            override fun onMapError(e: Exception?) {
                                                Log.e("RegionSelect", "Map error: ${e?.message}", e)
                                            }
                                        },
                                        object : KakaoMapReadyCallback() {
                                            override fun onMapReady(map: KakaoMap) {
                                                kakaoMap = map

                                                centerLat?.let { lat ->
                                                    centerLng?.let { lng ->
                                                        map.moveCamera(
                                                            CameraUpdateFactory.newCenterPosition(
                                                                LatLng.from(lat, lng),
                                                                13
                                                            )
                                                        )
                                                    }
                                                }

                                                // 🔹 지도 클릭 시 역지오코딩으로 지역명 업데이트 및 경계선 다시 로드
                                                map.setOnMapClickListener { _, latLng, _, _ ->
                                                    Log.d("RegionSelect", "🖱️ ===== 지도 클릭 이벤트 시작 =====")
                                                    Log.d("RegionSelect", "🖱️ 클릭 좌표: (${latLng.latitude}, ${latLng.longitude})")

                                                    scope.launch {
                                                        try {
                                                            Log.d("RegionSelect", "🖱️ 역지오코딩 시작...")
                                                            val regionInfo = KakaoLocalService.coord2regioncode(
                                                                latLng.latitude,
                                                                latLng.longitude
                                                            )

                                                            Log.d("RegionSelect", "🖱️ 역지오코딩 결과: $regionInfo")

                                                            if (regionInfo != null) {
                                                                Log.d("RegionSelect", "🖱️ region1=${regionInfo.region1}, region2=${regionInfo.region2}")
                                                                Log.d("RegionSelect", "🖱️ displayName=${regionInfo.displayName}")

                                                                // ✅ 이전 상태 저장 (되돌리기용)
                                                                previousRegionName = currentRegionName
                                                                previousAdminPolygons = adminPolygons
                                                                previousDongLabels = dongLabels
                                                                Log.d("RegionSelect", "💾 이전 상태 저장: $previousRegionName")

                                                                centerLat = latLng.latitude
                                                                centerLng = latLng.longitude

                                                                // ✅ 시/군/구 레벨만 표시 (동까지 들어가지 않음)
                                                                currentRegionName = regionInfo.displayName
                                                                Log.d("RegionSelect", "🖱️ currentRegionName 업데이트: '$currentRegionName'")

                                                                val region1 = regionInfo.region1
                                                                val region2 = regionInfo.region2
                                                                val vworldQuery = if (region2.isNotBlank()) {
                                                                    "$region1 $region2"
                                                                } else {
                                                                    region1
                                                                }

                                                                Log.d("RegionSelect", "🖱️ VWorld API 호출: getAdminBoundary('$vworldQuery')")
                                                                adminPolygons = VWorldService.getAdminBoundary(vworldQuery)
                                                                Log.d("RegionSelect", "🖱️ 시/군/구 폴리곤: ${adminPolygons.size}개")

                                                                // ✅ 구 레벨 라벨 생성 (시/군/구 이름만)
                                                                dongLabels = adminPolygons.map { polygon ->
                                                                    val centerLat = polygon.coordinates.map { it.lat }.average()
                                                                    val centerLng = polygon.coordinates.map { it.lng }.average()

                                                                    DongLabel(
                                                                        name = polygon.name,
                                                                        centerLat = centerLat,
                                                                        centerLng = centerLng
                                                                    )
                                                                }
                                                                Log.d("RegionSelect", "🖱️ 구 라벨 ${dongLabels.size}개 생성")

                                                                Log.d("RegionSelect", "✅ 시/군/구 선택 완료: $currentRegionName")

                                                                Log.d("RegionSelect", "📍 지도 클릭 처리 완료: $currentRegionName (${adminPolygons.size}개 폴리곤)")

                                                                // ✅ 지도 카메라 이동 (더 확대)
                                                                Log.d("RegionSelect", "🖱️ 카메라 이동: zoom=15")
                                                                map.moveCamera(
                                                                    CameraUpdateFactory.newCenterPosition(latLng, 15)
                                                                )
                                                                Log.d("RegionSelect", "🖱️ ===== 지도 클릭 이벤트 완료 =====")
                                                            } else {
                                                                Log.e("RegionSelect", "❌ 역지오코딩 결과가 null")
                                                            }
                                                        } catch (e: Exception) {
                                                            Log.e("RegionSelect", "❌❌❌ 지도 클릭 처리 실패: ${e.javaClass.simpleName} - ${e.message}", e)
                                                            e.printStackTrace()
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        // ✅ 지도 컨트롤 버튼들 (오른쪽 하단)
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 확대 버튼
                            FloatingActionButton(
                                onClick = {
                                    kakaoMap?.let { map ->
                                        val currentZoom = map.cameraPosition?.zoomLevel ?: 13
                                        map.moveCamera(
                                            CameraUpdateFactory.zoomTo(currentZoom + 1)
                                        )
                                    }
                                },
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "확대"
                                )
                            }

                            // 축소 버튼
                            FloatingActionButton(
                                onClick = {
                                    kakaoMap?.let { map ->
                                        val currentZoom = map.cameraPosition?.zoomLevel ?: 13
                                        map.moveCamera(
                                            CameraUpdateFactory.zoomTo(currentZoom - 1)
                                        )
                                    }
                                },
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Remove,
                                    contentDescription = "축소"
                                )
                            }

                            // 현재 위치로 돌아가기 버튼
                            FloatingActionButton(
                                onClick = {
                                    kakaoMap?.let { map ->
                                        centerLat?.let { lat ->
                                            centerLng?.let { lng ->
                                                map.moveCamera(
                                                    CameraUpdateFactory.newCenterPosition(
                                                        LatLng.from(lat, lng),
                                                        13
                                                    )
                                                )
                                            }
                                        }
                                    }
                                },
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MyLocation,
                                    contentDescription = "원래 위치로"
                                )
                            }
                        }
                    }
                }
            }

            // 하단 버튼 - 선택 완료
            if (!isLoading && errorMessage == null) {
                Spacer(Modifier.height(16.dp))

                // ✅ 지역 선택 완료 버튼 (검색 칸에 입력)
                Button(
                    onClick = {
                        onRegionSelected(currentRegionName)
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        "'$currentRegionName' 선택",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
