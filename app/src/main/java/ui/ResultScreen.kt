package com.example.project_2.ui.result

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.project_2.data.route.TmapPedestrianService
import com.example.project_2.domain.model.Place
import com.example.project_2.domain.model.RecommendationResult
import com.example.project_2.domain.model.RouteSegment
import com.example.project_2.domain.model.WeatherInfo
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
import com.kakao.vectormap.label.LabelTextStyle
import com.kakao.vectormap.route.RouteLineOptions
import com.kakao.vectormap.route.RouteLineSegment
import com.kakao.vectormap.route.RouteLineStyle
import com.kakao.vectormap.route.RouteLineStyles
import kotlinx.coroutines.launch

@Composable
fun ResultScreen(
    rec: RecommendationResult,
    selectedPlaces: List<Place>,
    onPlaceSelected: (Place) -> Unit,
) {
    var kakaoMap by remember { mutableStateOf<KakaoMap?>(null) }
    val labelPlaceMap = remember { mutableMapOf<Label, Place>() }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 실제 경로 데이터를 저장할 상태
    var routeSegments by remember { mutableStateOf<List<RouteSegment>?>(null) }
    var isLoadingRoute by remember { mutableStateOf(false) }
    var showRealRoute by remember { mutableStateOf(false) }  // 루트 생성 버튼 클릭 여부

    // 코드로 직접 파란색 핀 마커를 생성 (미선택 상태)
    val bluePinBitmap = remember {
        createPinBitmap(context, "#4285F4") // 파란색
    }

    // 주황색 핀 마커 생성 (선택 상태)
    val orangePinBitmap = remember {
        createPinBitmap(context, "#FF9800") // 주황색
    }

    LaunchedEffect(kakaoMap, selectedPlaces, routeSegments, showRealRoute) {
        val map = kakaoMap ?: return@LaunchedEffect
        val labelManager = map.labelManager ?: return@LaunchedEffect
        val routeLineManager = map.routeLineManager ?: return@LaunchedEffect

        labelManager.layer?.removeAll()
        routeLineManager.layer?.removeAll()
        labelPlaceMap.clear()

        val textStyle = LabelStyles.from(
            LabelStyle.from(LabelTextStyle.from(30, Color.BLACK, 2, Color.WHITE))
        )

        val bluePinStyle = if (bluePinBitmap != null) {
            LabelStyles.from(LabelStyle.from(bluePinBitmap).setAnchorPoint(0.5f, 1.0f))
        } else {
            LabelStyles.from(LabelStyle.from())
        }

        val orangePinStyle = if (orangePinBitmap != null) {
            LabelStyles.from(LabelStyle.from(orangePinBitmap).setAnchorPoint(0.5f, 1.0f))
        } else {
            LabelStyles.from(LabelStyle.from())
        }

        // 모든 추천 장소에 마커 표시
        rec.places.forEach { place ->
            val selectedIndex = selectedPlaces.indexOfFirst { it.id == place.id }
            val isSelected = selectedIndex != -1

            val options = LabelOptions.from(LatLng.from(place.lat, place.lng))
                .setClickable(true)

            if (isSelected) {
                // 선택된 장소: 주황색 핀 + 번호
                options.setTexts("${selectedIndex + 1}")
                options.setStyles(orangePinStyle)
            } else {
                // 선택 안된 장소: 파란색 핀
                options.setStyles(bluePinStyle)
            }

            labelManager.layer?.addLabel(options)?.let {
                labelPlaceMap[it] = place
            }
        }

        // 실제 경로 표시
        if (showRealRoute && routeSegments != null && routeSegments!!.isNotEmpty()) {
            // 실제 도로 경로 표시 (구간별 다른 색상)
            val colors = listOf(
                Color.parseColor("#FF4081"),  // 핑크
                Color.parseColor("#3F51B5"),  // 인디고
                Color.parseColor("#4CAF50"),  // 그린
                Color.parseColor("#FF9800"),  // 오렌지
                Color.parseColor("#9C27B0")   // 퍼플
            )

            routeSegments!!.forEachIndexed { index, segment ->
                val color = colors[index % colors.size]
                val styles = RouteLineStyles.from(RouteLineStyle.from(18f, color))
                val segmentLine = RouteLineSegment.from(segment.pathCoordinates)
                    .setStyles(styles)
                val routeOptions = RouteLineOptions.from(segmentLine)
                routeLineManager.layer?.addRouteLine(routeOptions)
            }
        }
        // 루트 생성 전에는 경로선 표시 안 함 (마커만 표시)
    }

    Column(Modifier.fillMaxSize()) {
        WeatherBanner(rec.weather)

        // 루트 생성하기 버튼
        if (selectedPlaces.size >= 2) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Button(
                    onClick = {
                        if (!isLoadingRoute && !showRealRoute) {
                            isLoadingRoute = true
                            coroutineScope.launch {
                                try {
                                    // TMAP 보행자 경로 조회
                                    val segments = TmapPedestrianService.getFullRoute(
                                        selectedPlaces
                                    )
                                    routeSegments = segments
                                    showRealRoute = true
                                } catch (e: Exception) {
                                    Log.e("ResultScreen", "Failed to get route", e)
                                } finally {
                                    isLoadingRoute = false
                                }
                            }
                        }
                    },
                    enabled = !isLoadingRoute && !showRealRoute,
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) {
                    if (isLoadingRoute) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(24.dp).padding(end = 8.dp)
                        )
                        Text("경로 생성 중...")
                    } else if (showRealRoute) {
                        Text("✓ 루트 생성 완료")
                    } else {
                        Text("🗺️ 루트 생성하기 (${selectedPlaces.size}개 장소)")
                    }
                }
            }
        }

        AndroidView(
            factory = { ctx ->
                MapView(ctx).apply {
                    start(
                        object : MapLifeCycleCallback() {
                            override fun onMapDestroy() {
                                kakaoMap = null
                            }
                            override fun onMapError(error: Exception) {
                                Log.e("ResultScreen", "KakaoMap Error: ", error)
                            }
                        },
                        object : KakaoMapReadyCallback() {
                            var isMapInitialized = false
                            override fun onMapReady(map: KakaoMap) {
                                if (!isMapInitialized) {
                                    rec.places.firstOrNull()?.let {
                                        val center = LatLng.from(it.lat, it.lng)
                                        map.moveCamera(CameraUpdateFactory.newCenterPosition(center))
                                    }
                                    map.setOnLabelClickListener { _, _, label ->
                                        labelPlaceMap[label]?.let(onPlaceSelected)
                                    }
                                    isMapInitialized = true
                                }
                                kakaoMap = map
                            }
                        }
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
        )

        Spacer(Modifier.height(8.dp))

        // 경로 정보 표시
        if (showRealRoute && routeSegments != null && routeSegments!!.isNotEmpty()) {
            RouteInfoSection(routeSegments!!)
            Spacer(Modifier.height(8.dp))
        }

        Text(
            "추천 장소",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(rec.places) { _, place ->
                val index = selectedPlaces.indexOfFirst { it.id == place.id }
                PlaceRow(
                    p = place,
                    selectedIndex = if (index != -1) index + 1 else null,
                    onToggle = { onPlaceSelected(place) }
                )
            }
        }
    }
}

@Composable
private fun WeatherBanner(w: WeatherInfo?) {
    if (w == null) return
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(Modifier.padding(16.dp)) {
            Text("🌤  현재 날씨  ${w.condition}  •  ${"%.2f".format(w.tempC)}℃")
        }
    }
}

@Composable
private fun PlaceRow(
    p: Place,
    selectedIndex: Int?,
    onToggle: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(p.name) },
        supportingContent = {
            Text(
                listOfNotNull(
                    p.address,
                    p.distanceMeters?.let { "~${it}m" }
                ).joinToString("  ·  ")
            )
        },
        leadingContent = selectedIndex?.let {
            { Text("$it", style = MaterialTheme.typography.titleLarge) }
        },
        trailingContent = {
            Button(onClick = onToggle) {
                Text(if (selectedIndex != null) "선택 해제" else "선택")
            }
        }
    )
    Divider()
}

@Composable
private fun RouteInfoSection(segments: List<RouteSegment>) {
    val totalDistance = segments.sumOf { it.distanceMeters }
    val totalDuration = segments.sumOf { it.durationSeconds }

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "📍 전체 이동 경로",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            segments.forEachIndexed { index, segment ->
                Row(Modifier.padding(vertical = 4.dp)) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Column {
                        Text(
                            "${segment.from.name} → ${segment.to.name}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "🚶 도보 ${segment.durationSeconds / 60}분 · ${String.format("%.1f", segment.distanceMeters / 1000.0)}km",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                "총 이동: ${totalDuration / 60}분 · ${String.format("%.1f", totalDistance / 1000.0)}km",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * 색상이 지정된 핀 마커 비트맵 생성
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
        Log.e("ResultScreen", "Failed to create pin bitmap", e)
        null
    }
}