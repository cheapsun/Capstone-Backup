package com.example.project_2.data

import android.util.Log
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/**
 * VWorld WFS API (행정구역 경계 폴리곤)
 * - Base URL: https://api.vworld.kr/req/wfs
 * - 인증: key 파라미터로 API 키 전달
 *
 * 사용 전에 VWorldService.init(VWORLD_API_KEY) 호출하세요.
 */
object VWorldService {

    private const val BASE_URL = "https://api.vworld.kr/req/"
    private const val DOMAIN = "http://localhost:4141"
    private const val TAG = "VWorldService"

    private var api: VWorldApi? = null
    private var apiKey: String? = null

    /** 앱 시작 시 한 번만 호출 */
    fun init(vworldApiKey: String) {
        apiKey = vworldApiKey

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

        api = retrofit.create(VWorldApi::class.java)
    }

    // -------- Public Functions --------

    /**
     * 시/군/구 행정구역 경계 폴리곤 받기
     * @param regionName 지역명 (예: "광주", "부산 해운대구")
     * @return 폴리곤 좌표 리스트
     */
    suspend fun getAdminBoundary(regionName: String): List<AdminPolygon> {
        val svc = api ?: return emptyList()
        val key = apiKey ?: return emptyList()

        return try {
            val response = svc.getFeature(
                service = "WFS",
                request = "GetFeature",
                typename = "lt_c_adsigg_info", // 시군구 경계
                key = key,
                domain = DOMAIN,
                output = "application/json",
                attrFilter = "sig_kor_nm:like:$regionName"
            )

            Log.d(TAG, "getAdminBoundary($regionName): ${response.features.size} features")

            response.features.map { feature ->
                val coords = extractCoordinates(feature.geometry)
                AdminPolygon(
                    name = feature.properties.sig_kor_nm ?: regionName,
                    coordinates = coords
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "getAdminBoundary failed: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 읍/면/동 목록과 중심 좌표 받기
     * @param regionName 지역명 (예: "광주")
     * @return 동 이름과 중심 좌표 리스트
     */
    suspend fun getDongLabels(regionName: String): List<DongLabel> {
        val svc = api ?: return emptyList()
        val key = apiKey ?: return emptyList()

        return try {
            val response = svc.getFeature(
                service = "WFS",
                request = "GetFeature",
                typename = "lt_c_emdong_info", // 읍면동 경계
                key = key,
                domain = DOMAIN,
                output = "application/json",
                attrFilter = "full_nm:like:$regionName"
            )

            Log.d(TAG, "getDongLabels($regionName): ${response.features.size} features")

            response.features.map { feature ->
                val coords = extractCoordinates(feature.geometry)
                val center = calculateCenter(coords)

                DongLabel(
                    name = feature.properties.emd_kor_nm ?: "",
                    centerLat = center.first,
                    centerLng = center.second
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "getDongLabels failed: ${e.message}", e)
            emptyList()
        }
    }

    // -------- Private Helpers --------

    /**
     * GeoJSON Geometry에서 좌표 추출
     * Polygon 또는 MultiPolygon 타입 처리
     */
    private fun extractCoordinates(geometry: Geometry): List<LatLng> {
        return try {
            when (geometry.type) {
                "Polygon" -> {
                    // Polygon: coordinates는 List<List<List<Double>>> 형태
                    // coordinates[0]이 외곽선 (첫 번째 링)
                    @Suppress("UNCHECKED_CAST")
                    val rings = geometry.coordinates as? List<List<List<Double>>> ?: return emptyList()
                    val ring = rings.firstOrNull() ?: return emptyList()

                    ring.mapNotNull { coord ->
                        if (coord.size >= 2) {
                            // GeoJSON: [경도, 위도]
                            LatLng(lat = coord[1], lng = coord[0])
                        } else null
                    }
                }
                "MultiPolygon" -> {
                    // MultiPolygon: coordinates는 List<List<List<List<Double>>>> 형태
                    // 첫 번째 폴리곤의 첫 번째 링만 사용
                    @Suppress("UNCHECKED_CAST")
                    val polygons = geometry.coordinates as? List<List<List<List<Double>>>> ?: return emptyList()
                    val firstPolygon = polygons.firstOrNull() ?: return emptyList()
                    val ring = firstPolygon.firstOrNull() ?: return emptyList()

                    ring.mapNotNull { coord ->
                        if (coord.size >= 2) {
                            LatLng(lat = coord[1], lng = coord[0])
                        } else null
                    }
                }
                else -> emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "extractCoordinates failed: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 폴리곤의 중심점 계산 (간단한 평균)
     */
    private fun calculateCenter(coords: List<LatLng>): Pair<Double, Double> {
        if (coords.isEmpty()) return 0.0 to 0.0

        val avgLat = coords.map { it.lat }.average()
        val avgLng = coords.map { it.lng }.average()

        return avgLat to avgLng
    }
}

// -------- Retrofit Interface --------

private interface VWorldApi {
    @GET("wfs")
    suspend fun getFeature(
        @Query("service") service: String,
        @Query("request") request: String,
        @Query("typename") typename: String,
        @Query("key") key: String,
        @Query("domain") domain: String,
        @Query("output") output: String,
        @Query("attrFilter") attrFilter: String
    ): VWorldResponse
}

// -------- Data Models --------

/**
 * VWorld WFS API 응답
 */
private data class VWorldResponse(
    @SerializedName("type") val type: String,
    @SerializedName("features") val features: List<Feature>
)

private data class Feature(
    @SerializedName("type") val type: String,
    @SerializedName("geometry") val geometry: Geometry,
    @SerializedName("properties") val properties: Properties
)

private data class Geometry(
    @SerializedName("type") val type: String, // "Polygon" or "MultiPolygon"
    @SerializedName("coordinates") val coordinates: List<Any> // Dynamic type for Polygon/MultiPolygon
)

private data class Properties(
    @SerializedName("sig_kor_nm") val sig_kor_nm: String?, // 시군구 한글명
    @SerializedName("emd_kor_nm") val emd_kor_nm: String?, // 읍면동 한글명
    @SerializedName("full_nm") val full_nm: String? // 전체 이름
)

// -------- Public Data Classes --------

/**
 * 행정구역 폴리곤
 */
data class AdminPolygon(
    val name: String,
    val coordinates: List<LatLng>
)

/**
 * 동/읍/면 레이블
 */
data class DongLabel(
    val name: String,
    val centerLat: Double,
    val centerLng: Double
)

/**
 * 위경도 좌표
 */
data class LatLng(
    val lat: Double,
    val lng: Double
)
