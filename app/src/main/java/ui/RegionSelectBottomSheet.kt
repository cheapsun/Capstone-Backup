package com.example.project_2.ui.region

import android.graphics.Color
import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
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
    onWholeRegionSearch: (
        regionName: String,
        polygon: List<com.example.project_2.data.LatLng>
    ) -> Unit,
    onRadiusSearch: (
        regionName: String,
        centerLat: Double,
        centerLng: Double
    ) -> Unit
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

            // 2. 역지오코딩: 좌표 → 행정구역 이름
            val regionInfo = KakaoLocalService.coord2regioncode(lat, lng)
            currentRegionName = regionInfo?.fullName ?: regionQuery

            Log.d("RegionSelect", "✅ 행정구역: $currentRegionName")

            // 3. VWorld API: 행정구역 경계 폴리곤
            val region1 = regionInfo?.region1 ?: regionQuery
            val region2 = regionInfo?.region2 ?: ""
            val vworldQuery = if (region2.isNotBlank()) "$region1 $region2" else region1

            adminPolygons = VWorldService.getAdminBoundary(vworldQuery)
            Log.d("RegionSelect", "✅ 폴리곤 ${adminPolygons.size}개 로드")

            // 4. VWorld API: 읍/면/동 라벨
            dongLabels = VWorldService.getDongLabels(vworldQuery)
            Log.d("RegionSelect", "✅ 동 라벨 ${dongLabels.size}개 로드")

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
        val map = kakaoMap ?: return@LaunchedEffect
        val routeLineManager = map.routeLineManager ?: return@LaunchedEffect
        val labelManager = map.labelManager ?: return@LaunchedEffect

        try {
            // 기존 경계선 및 라벨 제거
            routeLineManager.layer?.removeAll()
            labelManager.layer?.removeAll()

            Log.d("RegionSelect", "🎨 경계선/라벨 그리기 시작")

            // 폴리곤 그리기 (경계선만 표시 - RouteLine 사용)
            adminPolygons.forEach { polygon ->
                if (polygon.coordinates.isEmpty()) return@forEach

                val kakaoCoords = polygon.coordinates.map {
                    LatLng.from(it.lat, it.lng)
                }

                try {
                    // RouteLine을 사용하여 경계선 그리기
                    val segment = RouteLineSegment.from(kakaoCoords)
                        .setStyles(
                            RouteLineStyles.from(
                                RouteLineStyle.from(
                                    4f,  // 선 두께
                                    Color.argb(200, 66, 133, 244)  // 파란색
                                )
                            )
                        )

                    val options = RouteLineOptions.from(segment)
                    routeLineManager.layer?.addRouteLine(options)?.show()

                    Log.d("RegionSelect", "✅ 경계선 그림: ${polygon.name}, ${kakaoCoords.size}개 좌표")
                } catch (e: Exception) {
                    Log.e("RegionSelect", "❌ 경계선 그리기 실패: ${e.message}", e)
                }
            }

            // 동 라벨 그리기
            val textStyle = LabelStyles.from(
                LabelStyle.from(LabelTextStyle.from(24, Color.BLACK, 2, Color.WHITE))
            )

            dongLabels.forEach { label ->
                val options = LabelOptions.from(LatLng.from(label.centerLat, label.centerLng))
                    .setStyles(textStyle)
                    .setTexts(label.name)

                labelManager.layer?.addLabel(options)
            }

            Log.d("RegionSelect", "✅ 라벨 ${dongLabels.size}개 표시 완료")

        } catch (e: Exception) {
            Log.e("RegionSelect", "❌ 폴리곤/라벨 그리기 실패: ${e.message}", e)
        }
    }

    // 🔹 BottomSheet UI
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.fillMaxHeight(0.9f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // 헤더: 지역 이름
            Text(
                text = currentRegionName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

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

                                                // 🔹 지도 클릭 시 역지오코딩으로 지역명 업데이트
                                                map.setOnMapClickListener { _, latLng, _, _ ->
                                                    scope.launch {
                                                        try {
                                                            val regionInfo = KakaoLocalService.coord2regioncode(
                                                                latLng.latitude,
                                                                latLng.longitude
                                                            )
                                                            if (regionInfo != null) {
                                                                currentRegionName = regionInfo.fullName
                                                                centerLat = latLng.latitude
                                                                centerLng = latLng.longitude
                                                                Log.d("RegionSelect", "📍 지도 클릭: $currentRegionName")
                                                            }
                                                        } catch (e: Exception) {
                                                            Log.e("RegionSelect", "역지오코딩 실패: ${e.message}")
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
                    }
                }
            }

            // 하단 버튼들
            if (!isLoading && errorMessage == null) {
                Spacer(Modifier.height(16.dp))

                // 전체 지역 검색 버튼
                Button(
                    onClick = {
                        val polygon = adminPolygons.firstOrNull()?.coordinates
                        if (polygon != null && polygon.isNotEmpty()) {
                            onWholeRegionSearch(currentRegionName, polygon)
                            onDismiss()
                        } else {
                            Log.w("RegionSelect", "⚠️ 폴리곤 데이터 없음")
                        }
                    },
                    enabled = adminPolygons.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        "$currentRegionName 전체로 검색",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(12.dp))

                // 특정 위치 검색 버튼
                OutlinedButton(
                    onClick = {
                        centerLat?.let { lat ->
                            centerLng?.let { lng ->
                                onRadiusSearch(currentRegionName, lat, lng)
                                onDismiss()
                            }
                        }
                    },
                    enabled = centerLat != null && centerLng != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(
                        "$currentRegionName 주변 검색 (8km)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
