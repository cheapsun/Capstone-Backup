package com.example.project_2.data

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/**
 * VWorld 검색 API 2.0
 * - 행정구역 검색 전용
 * - Base URL: http://api.vworld.kr/
 * - 인증: API 키 파라미터
 */
object VWorldService {

    private const val BASE_URL = "http://api.vworld.kr/"
    private var api: VWorldApi? = null
    private var apiKey: String = ""

    /** 앱 시작 시 한 번만 호출 */
    fun init(vworldApiKey: String) {
        apiKey = vworldApiKey

        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        api = retrofit.create(VWorldApi::class.java)
    }

    /**
     * 행정구역 검색 (자동완성용)
     * - type=DISTRICT로 행정구역만 검색
     */
    suspend fun searchDistrict(
        query: String,
        size: Int = 10
    ): List<DistrictResult> {
        if (query.isBlank()) return emptyList()
        val svc = api ?: return emptyList()
        if (apiKey.isBlank()) return emptyList()

        return try {
            val resp = svc.search(
                key = apiKey,
                service = "search",
                request = "search",
                version = "2.0",
                type = "DISTRICT",
                query = query,
                size = size,
                crs = "EPSG:4326"
            )

            // 응답에서 결과 추출
            val items = resp.response?.result?.items ?: emptyList()
            items.map { item ->
                DistrictResult(
                    title = item.title ?: "",
                    category = item.category ?: "",
                    address = item.address ?: ""
                )
            }.filter { it.title.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 행정구역 검색 결과 */
    data class DistrictResult(
        val title: String,
        val category: String,
        val address: String
    )

    // -------- Retrofit API --------

    private interface VWorldApi {
        @GET("req/search")
        suspend fun search(
            @Query("key") key: String,
            @Query("service") service: String,
            @Query("request") request: String,
            @Query("version") version: String,
            @Query("type") type: String,
            @Query("query") query: String,
            @Query("size") size: Int,
            @Query("crs") crs: String
        ): VWorldResponse
    }

    // -------- Response DTOs --------

    private data class VWorldResponse(
        val response: VWorldResponseBody? = null
    )

    private data class VWorldResponseBody(
        val result: VWorldResult? = null
    )

    private data class VWorldResult(
        val items: List<VWorldItem> = emptyList()
    )

    private data class VWorldItem(
        val title: String? = null,
        val category: String? = null,
        val address: String? = null
    )
}
