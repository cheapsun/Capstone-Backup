package com.example.project_2.ui.itinerary

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItineraryMapScreen(
    itinerary: Itinerary,
    initialDay: Int = 0,
    onBack: () -> Unit
) {
    var selectedDay by remember { mutableStateOf(initialDay) }
    var kakaoMap by remember { mutableStateOf<KakaoMap?>(null) }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("일정 지도", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "뒤로")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Day 탭
            TabRow(selectedTabIndex = selectedDay) {
                itinerary.days.forEachIndexed { index, day ->
                    Tab(
                        selected = selectedDay == index,
                        onClick = {
                            selectedDay = index
                            // Update map when day changes
                            kakaoMap?.let { map ->
                                updateMapMarkers(map, itinerary.days[index].timeSlots.mapNotNull { it.place })
                            }
                        },
                        text = { Text("Day ${day.day}") }
                    )
                }
            }

            // 지도
            if (currentDayPlaces.isNotEmpty()) {
                AndroidView(
                    factory = { context ->
                        MapView(context).apply {
                            start(object : MapLifeCycleCallback() {
                                override fun onMapDestroy() {}
                                override fun onMapError(error: Exception?) {}
                            }, object : KakaoMapReadyCallback() {
                                override fun onMapReady(map: KakaoMap) {
                                    kakaoMap = map
                                    updateMapMarkers(map, currentDayPlaces)
                                }
                            })
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(16.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    Text(
                        "이 날짜에 표시할 장소가 없습니다",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 장소 목록
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Day ${itinerary.days[selectedDay].day} 장소",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Divider()
                    currentDayPlaces.forEachIndexed { index, place ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    "${index + 1}",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    place.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                place.address?.let { addr ->
                                    Text(
                                        addr,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun updateMapMarkers(
    map: KakaoMap,
    places: List<com.example.project_2.domain.model.Place>
) {
    // Clear existing labels
    map.labelManager?.layer?.removeAll()

    if (places.isEmpty()) return

    // Add markers for each place
    val validPlaces = places.filter { it.lat != null && it.lng != null }

    // Default color for markers
    val defaultColor = "#FF5722"  // Red-orange color

    validPlaces.forEachIndexed { index, place ->
        val position = LatLng.from(place.lat!!, place.lng!!)

        // Create numbered bitmap
        val numberedBitmap = createNumberedMarkerBitmap(
            number = index + 1,
            color = defaultColor
        )

        val styles = LabelStyles.from(
            LabelStyle.from(numberedBitmap).setApplyDpScale(false)
        )

        val options = LabelOptions.from(position)
            .setStyles(styles)

        map.labelManager?.layer?.addLabel(options)
    }

    // Center camera on the places
    if (validPlaces.isNotEmpty()) {
        val centerLat = validPlaces.map { it.lat!! }.average()
        val centerLng = validPlaces.map { it.lng!! }.average()

        val cameraUpdate = CameraUpdateFactory.newCenterPosition(
            LatLng.from(centerLat, centerLng),
            if (validPlaces.size == 1) 15 else 13
        )
        map.moveCamera(cameraUpdate)
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
