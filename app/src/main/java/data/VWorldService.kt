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
     * 특정 읍/면/동 이름으로 경계 폴리곤 받기
     * @param dongName 읍/면/동 이름 (예: "영등동")
     * @param region 상위 지역명 (예: "전라북도 익산시", 검색 범위 좁히기용)
     * @return 해당 읍/면/동 폴리곤 (null이면 찾지 못함)
     */
    suspend fun getEmdongBoundaryByName(dongName: String, region: String): AdminPolygon? {
        Log.d(TAG, "🔍 getEmdongBoundaryByName 시작: dongName='$dongName', region='$region'")

        val svc = api ?: run {
            Log.e(TAG, "❌ getEmdongBoundaryByName: api is null")
            return null
        }
        val key = apiKey ?: run {
            Log.e(TAG, "❌ getEmdongBoundaryByName: apiKey is null")
            return null
        }

        return try {
            // 읍/면/동 이름으로 검색 (상위 지역명 포함하여 검색 범위 좁히기)
            val fullQuery = "$region $dongName"
            Log.d(TAG, "🔍 VWorld API 호출 (full_nm): '$fullQuery'")

            val response = svc.getFeature(
                service = "WFS",
                request = "GetFeature",
                typename = "lt_c_emdong_info", // 읍면동 경계
                key = key,
                domain = DOMAIN,
                output = "application/json",
                attrFilter = "full_nm:like:$fullQuery"
            )

            Log.d(TAG, "🔍 VWorld API 응답 (full_nm): ${response.features.size}개 features")

            if (response.features.isEmpty()) {
                // 전체 이름 검색 실패 시 읍/면/동 이름만으로 재시도
                Log.d(TAG, "🔍 전체 이름 검색 실패, 읍/면/동 이름만으로 재시도: '$dongName'")

                val response2 = svc.getFeature(
                    service = "WFS",
                    request = "GetFeature",
                    typename = "lt_c_emdong_info",
                    key = key,
                    domain = DOMAIN,
                    output = "application/json",
                    attrFilter = "emd_kor_nm:like:$dongName"
                )

                Log.d(TAG, "🔍 VWorld API 응답 (emd_kor_nm): ${response2.features.size}개 features")

                if (response2.features.isEmpty()) {
                    Log.w(TAG, "⚠️ 읍/면/동 경계를 찾을 수 없음: '$dongName'")
                    return null
                }

                val feature = response2.features.first()
                Log.d(TAG, "🔍 feature.properties: ${feature.properties}")

                val coords = extractCoordinates(feature.geometry)
                Log.d(TAG, "✅ 읍/면/동 경계 추출 성공 (fallback): ${coords.size}개 좌표")

                return AdminPolygon(
                    name = feature.properties.emd_kor_nm ?: dongName,
                    coordinates = coords
                )
            }

            val feature = response.features.first()
            Log.d(TAG, "🔍 feature.properties: ${feature.properties}")

            val coords = extractCoordinates(feature.geometry)
            Log.d(TAG, "✅ 읍/면/동 경계 추출 성공: ${coords.size}개 좌표")

            AdminPolygon(
                name = feature.properties.emd_kor_nm ?: dongName,
                coordinates = coords
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌❌❌ getEmdongBoundaryByName 실패: ${e.javaClass.simpleName} - ${e.message}", e)
            e.printStackTrace()
            null
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
