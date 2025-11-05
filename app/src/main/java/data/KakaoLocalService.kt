package com.example.project_2.data

import com.example.project_2.domain.model.Category
import com.example.project_2.domain.model.Place
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/**
 * Kakao Local REST API (키워드/카테고리/주소검색)
 * - Base URL: https://dapi.kakao.com/
 * - 인증: Authorization: KakaoAK {REST_API_KEY}
 *
 * 사용 전에 KakaoLocalService.init(BuildConfig.KAKAO_REST_API_KEY) 호출하세요.
 */
object KakaoLocalService {

    private const val BASE_URL = "https://dapi.kakao.com/"
    private var api: KakaoLocalApi? = null

    /** 앱 시작 시 한 번만 호출 */
    fun init(kakaoRestApiKey: String) {
        val auth = Interceptor { chain ->
            val req = chain.request().newBuilder()
                .addHeader("Authorization", "KakaoAK $kakaoRestApiKey")
                .build()
            chain.proceed(req)
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(auth)
            // 🔒 네트워크 안정성 강화
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        api = retrofit.create(KakaoLocalApi::class.java)
    }

    // -------- Public Functions --------

    /** 지역 문자열을 좌표(위도,경도)로 변환. 실패 시 null */
    suspend fun geocode(regionOrAddress: String): Pair<Double, Double>? {
        val svc = api ?: return null
        val resp = svc.searchAddress(regionOrAddress)
        val doc = resp.documents.firstOrNull() ?: return null
        // Kakao는 x=경도, y=위도
        val lat = doc.y.toDoubleOrNull() ?: return null
        val lng = doc.x.toDoubleOrNull() ?: return null
        return lat to lng
    }

    /**
     * 카테고리 기반 장소 검색.
     * - centerLat/centerLng 기준 radius(m) 내 결과를 category_group_code로 필터
     * - 필요 시 여러 코드로 합쳐서 조회 (간단히 순차 호출 후 합치기)
     */
    suspend fun searchByCategories(
        centerLat: Double,
        centerLng: Double,
        categories: Set<Category>,
        radiusMeters: Int = 3000,
        size: Int = 15
    ): List<Place> {
        val svc = api ?: return emptyList()
        val codes = categoryCodesFor(categories)
        if (codes.isEmpty()) return emptyList()

        val out = mutableListOf<Place>()
        for (code in codes) {
            val resp = svc.searchByCategory(
                categoryGroupCode = code,
                x = centerLng,
                y = centerLat,
                radius = radiusMeters,
                size = size,
                sort = "distance"
            )
            out += resp.documents.mapNotNull { it.toPlace() }
        }

        // 🔁 id 중복 제거 + 📏 거리순 정렬(거리 없으면 뒤로)
        return out
            .distinctBy { it.id }
            .sortedBy { it.distanceMeters ?: Int.MAX_VALUE }
    }

    /** 키워드 기반 검색 (필요 시 사용) */
    suspend fun searchByKeyword(
        centerLat: Double,
        centerLng: Double,
        keyword: String,
        radiusMeters: Int = 3000,
        size: Int = 15
    ): List<Place> {
        val svc = api ?: return emptyList()
        val resp = svc.searchByKeyword(
            query = keyword,
            x = centerLng,
            y = centerLat,
            radius = radiusMeters,
            size = size,
            sort = "accuracy"
        )
        return resp.documents
            .mapNotNull { it.toPlace() }
            .distinctBy { it.id }
            .sortedBy { it.distanceMeters ?: Int.MAX_VALUE }
    }

    // -------- Private Helpers --------

    /** 우리 앱의 Category → Kakao category_group_code 매핑 */
    private fun categoryCodesFor(cats: Set<Category>): List<String> {
        if (cats.isEmpty()) return emptyList()
        val list = mutableListOf<String>()
        cats.forEach {
            when (it) {
                Category.FOOD -> list += "FD6"      // 음식점
                Category.CAFE -> list += "CE7"      // 카페
                Category.CULTURE -> list += "CT1"   // 문화시설
                Category.PHOTO -> list += "AT4"     // 관광명소(사진스팟 포괄)
                Category.SHOPPING -> {
                    list += "MT1"                   // 대형마트
                    list += "CS2"                   // 편의점 등
                }
                Category.HEALING -> {
                    list += "AT4"                   // 공원/명소 포괄
                }
                Category.EXPERIENCE -> {
                    list += "AT4"                   // 체험형 명소
                    list += "AC5"                   // 학원/체험(보조)
                }
                Category.NIGHT -> list += "AD5"     // 숙박/야간활동 근접
            }
        }
        return list
    }

    // -------- Retrofit DTO / API --------

    private interface KakaoLocalApi {
        @GET("v2/local/search/address.json")
        suspend fun searchAddress(
            @Query("query") query: String
        ): AddressResp

        @GET("v2/local/search/category.json")
        suspend fun searchByCategory(
            @Query("category_group_code") categoryGroupCode: String,
            @Query("x") x: Double,   // 경도
            @Query("y") y: Double,   // 위도
            @Query("radius") radius: Int = 3000,
            @Query("size") size: Int = 15,
            @Query("sort") sort: String = "distance"
        ): PlaceResp

        @GET("v2/local/search/keyword.json")
        suspend fun searchByKeyword(
            @Query("query") query: String,
            @Query("x") x: Double,
            @Query("y") y: Double,
            @Query("radius") radius: Int = 3000,
            @Query("size") size: Int = 15,
            @Query("sort") sort: String = "accuracy"
        ): PlaceResp
    }

    // --- Address
    private data class AddressResp(val documents: List<AddressDoc> = emptyList())
    private data class AddressDoc(
        val x: String, // 경도
        val y: String  // 위도
    )

    // --- Place
    private data class PlaceResp(val documents: List<PlaceDoc> = emptyList())
    private data class PlaceDoc(
        val id: String,
        val place_name: String,
        val category_group_code: String?,
        val x: String,              // 경도
        val y: String,              // 위도
        val address_name: String?,
        val distance: String? = null, // meter (문자열)
        val place_url: String? = null
    ) {
        fun toPlace(): Place? {
            val lat = y.toDoubleOrNull() ?: return null
            val lng = x.toDoubleOrNull() ?: return null
            val dist = distance?.toIntOrNull()
            val cat = when (category_group_code) {
                "FD6" -> Category.FOOD
                "CE7" -> Category.CAFE
                "CT1" -> Category.CULTURE
                "AT4" -> Category.PHOTO
                "MT1", "CS2" -> Category.SHOPPING
                "AD5" -> Category.NIGHT
                // 그 외 코드들은 대체로 문화/명소로 포괄
                else -> Category.CULTURE
            }
            return Place(
                id = id,
                name = place_name,
                category = cat,
                lat = lat,
                lng = lng,
                distanceMeters = dist,
                rating = null,
                address = address_name
            )
        }
    }
}
