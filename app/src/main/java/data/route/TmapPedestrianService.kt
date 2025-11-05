package com.example.project_2.data.route

import com.example.project_2.domain.model.Place
import com.example.project_2.domain.model.RouteSegment
import com.example.project_2.domain.model.TransportMode
import com.kakao.vectormap.LatLng
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

/**
 * TMAP 보행자 경로 안내 API
 * - Base URL: https://apis.openapi.sk.com/
 * - 인증: appKey 헤더
 *
 * 보행자 전용 경로를 제공합니다 (횡단보도, 인도, 계단 포함)
 *
 * 사용 전에 TmapPedestrianService.init(BuildConfig.TMAP_API_KEY) 호출하세요.
 */
object TmapPedestrianService {

    private const val BASE_URL = "https://apis.openapi.sk.com/"
    private var api: TmapApi? = null
    private var tmapApiKey: String? = null

    /** 앱 시작 시 한 번만 호출 */
    fun init(apiKey: String) {
        tmapApiKey = apiKey

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        api = retrofit.create(TmapApi::class.java)
    }

    /**
     * 두 장소 사이의 보행자 경로를 조회합니다.
     *
     * @param from 출발 장소
     * @param to 도착 장소
     * @return RouteSegment 또는 실패 시 null
     */
    suspend fun getRoute(
        from: Place,
        to: Place
    ): RouteSegment? {
        val svc = api ?: return null
        val key = tmapApiKey ?: return null

        try {
            android.util.Log.d("TmapPedestrian", "=== 경로 조회 시작 ===")
            android.util.Log.d("TmapPedestrian", "출발: ${from.name} (${from.lat}, ${from.lng})")
            android.util.Log.d("TmapPedestrian", "도착: ${to.name} (${to.lat}, ${to.lng})")

            // 요청 바디 생성
            val requestBody = JSONObject().apply {
                put("startX", from.lng)
                put("startY", from.lat)
                put("endX", to.lng)
                put("endY", to.lat)
                put("startName", from.name)
                put("endName", to.name)
                put("reqCoordType", "WGS84GEO")
                put("resCoordType", "WGS84GEO")
                put("searchOption", "0")  // 0: 추천, 1: 최단거리
            }.toString()

            android.util.Log.d("TmapPedestrian", "요청 바디: $requestBody")

            val resp = svc.getPedestrianRoute(
                appKey = key,
                version = "1",
                body = requestBody.toRequestBody()
            )

            android.util.Log.d("TmapPedestrian", "응답 받음: features 개수 = ${resp.features.size}")

            // 응답 파싱
            val result = parseResponse(from, to, resp)

            if (result != null) {
                android.util.Log.d("TmapPedestrian", "✅ 성공: 좌표 ${result.pathCoordinates.size}개, 거리 ${result.distanceMeters}m")
            } else {
                android.util.Log.e("TmapPedestrian", "❌ 파싱 실패")
            }

            return result

        } catch (e: Exception) {
            android.util.Log.e("TmapPedestrian", "❌ 에러 발생: ${e.message}", e)
            e.printStackTrace()
            return null
        }
    }

    /**
     * 여러 장소를 순서대로 연결하는 전체 경로를 조회합니다.
     *
     * @param places 순서대로 방문할 장소 리스트
     * @return RouteSegment 리스트 (각 구간별 경로)
     */
    suspend fun getFullRoute(
        places: List<Place>
    ): List<RouteSegment> {
        if (places.size < 2) return emptyList()

        val segments = mutableListOf<RouteSegment>()

        // 순차적으로 각 구간 조회: A→B, B→C, C→D, ...
        for (i in 0 until places.size - 1) {
            val from = places[i]
            val to = places[i + 1]

            val segment = getRoute(from, to)
            if (segment != null) {
                segments.add(segment)
            } else {
                // 실패 시 직선으로 대체
                segments.add(createFallbackSegment(from, to))
            }

            // API 호출 제한 방지 (선택적)
            kotlinx.coroutines.delay(100)
        }

        return segments
    }

    /**
     * TMAP API 응답을 RouteSegment로 변환
     */
    private fun parseResponse(
        from: Place,
        to: Place,
        response: TmapPedestrianResponse
    ): RouteSegment? {
        try {
            val features = response.features
            if (features.isEmpty()) {
                android.util.Log.e("TmapPedestrian", "features가 비어있음")
                return null
            }

            android.util.Log.d("TmapPedestrian", "총 features 개수: ${features.size}")

            // 총 거리와 시간은 첫 번째 feature에 있음
            val summary = features.firstOrNull {
                it.properties?.totalDistance != null && it.properties.totalDistance > 0
            }
            val totalDistance = summary?.properties?.totalDistance ?: 0
            val totalTime = summary?.properties?.totalTime ?: 0

            android.util.Log.d("TmapPedestrian", "총 거리: ${totalDistance}m, 총 시간: ${totalTime}초")

            // 경로 좌표 수집
            val pathCoordinates = mutableListOf<LatLng>()

            features.forEachIndexed { index, feature ->
                try {
                    val coords = feature.geometry.coordinates

                    when (feature.geometry.type) {
                        "Point" -> {
                            // Point: [lng, lat]
                            if (coords.size >= 2) {
                                val lng = (coords[0] as? Number)?.toDouble() ?: return@forEachIndexed
                                val lat = (coords[1] as? Number)?.toDouble() ?: return@forEachIndexed
                                pathCoordinates.add(LatLng.from(lat, lng))
                                android.util.Log.d("TmapPedestrian", "[$index] Point: ($lat, $lng)")
                            }
                        }
                        "LineString" -> {
                            // LineString: [[lng,lat], [lng,lat], ...]
                            android.util.Log.d("TmapPedestrian", "[$index] LineString 처리 시작")

                            coords.forEach { coord ->
                                if (coord is List<*> && coord.size >= 2) {
                                    val lng = (coord[0] as? Number)?.toDouble()
                                    val lat = (coord[1] as? Number)?.toDouble()
                                    if (lng != null && lat != null) {
                                        pathCoordinates.add(LatLng.from(lat, lng))
                                    }
                                }
                            }

                            android.util.Log.d("TmapPedestrian", "[$index] LineString: ${pathCoordinates.size}개 좌표 추가")
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("TmapPedestrian", "feature[$index] 파싱 실패: ${e.message}")
                }
            }

            android.util.Log.d("TmapPedestrian", "수집된 좌표 개수: ${pathCoordinates.size}")

            if (pathCoordinates.size < 2) {
                android.util.Log.e("TmapPedestrian", "좌표가 부족함 (${pathCoordinates.size}개) - fallback 사용")
                return createFallbackSegment(from, to)
            }

            // 중복 좌표 제거
            val uniqueCoordinates = pathCoordinates.distinctBy {
                "${String.format("%.6f", it.latitude)},${String.format("%.6f", it.longitude)}"
            }

            android.util.Log.d("TmapPedestrian", "중복 제거 후: ${uniqueCoordinates.size}개")

            return RouteSegment(
                from = from,
                to = to,
                pathCoordinates = uniqueCoordinates,
                distanceMeters = if (totalDistance > 0) totalDistance else calculateDistance(from.lat, from.lng, to.lat, to.lng).toInt(),
                durationSeconds = if (totalTime > 0) totalTime else (totalDistance / 1.4).toInt(),
                mode = TransportMode.WALK
            )

        } catch (e: Exception) {
            android.util.Log.e("TmapPedestrian", "parseResponse 전체 실패: ${e.message}", e)
            e.printStackTrace()
            return createFallbackSegment(from, to)
        }
    }

    /**
     * API 실패 시 직선 경로로 대체
     */
    private fun createFallbackSegment(
        from: Place,
        to: Place
    ): RouteSegment {
        // 단순 직선 거리 계산 (Haversine formula)
        val distance = calculateDistance(from.lat, from.lng, to.lat, to.lng)

        return RouteSegment(
            from = from,
            to = to,
            pathCoordinates = listOf(
                LatLng.from(from.lat, from.lng),
                LatLng.from(to.lat, to.lng)
            ),
            distanceMeters = distance.toInt(),
            durationSeconds = (distance / 1.4).toInt(),  // 도보 시속 5km/h 가정
            mode = TransportMode.WALK
        )
    }

    /**
     * 두 지점 간 직선 거리 계산 (Haversine formula)
     */
    private fun calculateDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earthRadius = 6371000.0 // 미터
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)

        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)

        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return earthRadius * c
    }

    // -------- Retrofit API --------

    private interface TmapApi {
        @POST("tmap/routes/pedestrian")
        suspend fun getPedestrianRoute(
            @Header("appKey") appKey: String,
            @retrofit2.http.Query("version") version: String,
            @Body body: okhttp3.RequestBody
        ): TmapPedestrianResponse
    }

    // -------- Response DTO --------

    private data class TmapPedestrianResponse(
        val type: String,
        val features: List<Feature>
    )

    private data class Feature(
        val type: String,
        val geometry: Geometry,
        val properties: Properties?
    )

    private data class Geometry(
        val type: String,                          // "Point" 또는 "LineString"
        val coordinates: List<Any>                 // 유연하게 받기
    )

    private data class Properties(
        val totalDistance: Int?,    // 총 거리 (미터)
        val totalTime: Int?,        // 총 시간 (초)
        val index: Int?,
        val pointIndex: Int?,
        val name: String?,
        val description: String?,
        val distance: Int?,
        val time: Int?
    )
}