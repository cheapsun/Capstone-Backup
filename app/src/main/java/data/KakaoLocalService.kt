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

    // ===================================================================
    //  지역 → 좌표 변환
    // ===================================================================

    suspend fun geocode(regionOrAddress: String): Pair<Double, Double>? {
        val svc = api ?: return null
        val resp = svc.searchAddress(regionOrAddress)
        val doc = resp.documents.firstOrNull() ?: return null
        val lat = doc.y.toDoubleOrNull() ?: return null
        val lng = doc.x.toDoubleOrNull() ?: return null
        return lat to lng
    }

    // ===================================================================
    //  카테고리 검색 (📌 다중 페이지 지원)
    // ===================================================================

    suspend fun searchByCategories(
        centerLat: Double,
        centerLng: Double,
        categories: Set<Category>,
        radiusMeters: Int = 5000,
        size: Int = 15,
        maxPages: Int = 3                 // ← 추가됨 (총 45개까지 가능)
    ): List<Place> {

        val svc = api ?: return emptyList()
        val codes = categoryCodesFor(categories)
        if (codes.isEmpty()) return emptyList()

        val out = mutableListOf<Place>()

        for (code in codes) {
            for (page in 1..maxPages) {
                val resp = svc.searchByCategory(
                    categoryGroupCode = code,
                    x = centerLng,
                    y = centerLat,
                    radius = radiusMeters,
                    size = size,          // size ≤ 15 (카카오 제한)
                    page = page,
                    sort = "distance"
                )

                // 결과 문서가 없으면 더 이상 페이지 없음 → 중단
                if (resp.documents.isEmpty()) break

                out += resp.documents.mapNotNull { it.toPlace() }
            }
        }

        return out
            .distinctBy { it.id }
            .filterNot { place -> isLowPriorityPlaceName(place.name) }
            .sortedBy { it.distanceMeters ?: Int.MAX_VALUE }
    }

    // ===================================================================
    //  키워드 검색 (기본 1페이지)
    // ===================================================================

    suspend fun searchByKeyword(
        centerLat: Double,
        centerLng: Double,
        keyword: String,
        radiusMeters: Int = 5000,
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
            .filterNot { place -> isLowPriorityPlaceName(place.name) }
            .sortedBy { it.distanceMeters ?: Int.MAX_VALUE }
    }

    // ===================================================================
    //  래핑 함수 (RealTravelRepository에서 사용)
    // ===================================================================

    suspend fun searchKeyword(
        region: String,
        keyword: String,
        centerLat: Double,
        centerLng: Double,
        radiusMeters: Int = 5000,
        size: Int = 15
    ): List<Place> {
        return searchByKeyword(
            centerLat = centerLat,
            centerLng = centerLng,
            keyword = keyword,
            radiusMeters = radiusMeters,
            size = size
        )
    }

    // ===================================================================
    //  카테고리 → Kakao 코드 매핑
    // ===================================================================

    private fun categoryCodesFor(cats: Set<Category>): List<String> {
        if (cats.isEmpty()) return emptyList()
        val list = mutableListOf<String>()
        cats.forEach {
            when (it) {
                Category.FOOD -> list += "FD6"
                Category.CAFE -> list += "CE7"
                Category.CULTURE -> list += "CT1"
                Category.PHOTO -> list += "AT4"
                Category.SHOPPING -> {
                    list += "MT1"
                    list += "CS2"
                }
                Category.HEALING -> list += "AT4"
                Category.EXPERIENCE -> {
                    list += "AT4"
                    list += "AC5"
                }
                Category.NIGHT -> list += "AD5"
                Category.STAY -> list += "AD5"
            }
        }
        return list
    }

    // 저품질 장소 필터링
    private fun isLowPriorityPlaceName(name: String): Boolean {
        val badKeywords = listOf(
            "구내식당", "사내식당", "학생식당", "교내식당", "급식실", "기숙사식당"
        )
        return badKeywords.any { keyword -> name.contains(keyword) }
    }

    // ===================================================================
    //  Retrofit DTO / API
    // ===================================================================

    private interface KakaoLocalApi {

        @GET("v2/local/search/address.json")
        suspend fun searchAddress(
            @Query("query") query: String
        ): AddressResp

        @GET("v2/local/search/category.json")
        suspend fun searchByCategory(
            @Query("category_group_code") categoryGroupCode: String,
            @Query("x") x: Double,
            @Query("y") y: Double,
            @Query("radius") radius: Int = 5000,
            @Query("size") size: Int = 15,
            @Query("page") page: Int = 1,               // ← 추가됨
            @Query("sort") sort: String = "distance"
        ): PlaceResp

        @GET("v2/local/search/keyword.json")
        suspend fun searchByKeyword(
            @Query("query") query: String,
            @Query("x") x: Double,
            @Query("y") y: Double,
            @Query("radius") radius: Int = 5000,
            @Query("size") size: Int = 15,
            @Query("sort") sort: String = "accuracy"
        ): PlaceResp
    }

    // Address
    private data class AddressResp(val documents: List<AddressDoc> = emptyList())
    private data class AddressDoc(val x: String, val y: String)

    // Place
    private data class PlaceResp(val documents: List<PlaceDoc> = emptyList())
    private data class PlaceDoc(
        val id: String,
        val place_name: String,
        val category_group_code: String?,
        val x: String,
        val y: String,
        val address_name: String?,
        val distance: String? = null
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
                "AD5" -> Category.STAY
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
