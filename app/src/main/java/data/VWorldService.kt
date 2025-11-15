package com.example.project_2.data

import android.util.Log
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

    private const val TAG = "VWorldService"
    private const val BASE_URL = "https://api.vworld.kr/"  // HTTPS 사용
    private var api: VWorldApi? = null
    private var apiKey: String = ""

    /** 앱 시작 시 한 번만 호출 */
    fun init(vworldApiKey: String) {
        apiKey = vworldApiKey
        Log.d(TAG, "init: API key length = ${vworldApiKey.length}")

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
        Log.d(TAG, "init: VWorld API initialized")
    }

    /**
     * 행정구역 검색 (자동완성용)
     * - type=DISTRICT로 행정구역만 검색
     */
    suspend fun searchDistrict(
        query: String,
        size: Int = 10
    ): List<DistrictResult> {
        if (query.isBlank()) {
            Log.d(TAG, "searchDistrict: query is blank")
            return emptyList()
        }

        val svc = api
        if (svc == null) {
            Log.e(TAG, "searchDistrict: API not initialized")
            return emptyList()
        }

        if (apiKey.isBlank()) {
            Log.e(TAG, "searchDistrict: API key is blank")
            return emptyList()
        }

        return try {
            Log.d(TAG, "searchDistrict: Searching for query='$query', size=$size")

            val resp = svc.search(
                key = apiKey,
                service = "search",
                request = "search",
                version = "2.0",
                type = "district",  // 소문자
                query = query,
                size = size,
                page = 1,
                category = "L1,L2",  // 시도 + 시군구 레벨만
                crs = "EPSG:4326",
                format = "json",
                errorFormat = "json"
            )

            Log.d(TAG, "searchDistrict: Response received - response=${resp.response != null}")
            Log.d(TAG, "searchDistrict: Status = ${resp.response?.status}")
            Log.d(TAG, "searchDistrict: Error = ${resp.response?.error}")
            Log.d(TAG, "searchDistrict: Result = ${resp.response?.result}")

            // 응답에서 결과 추출
            val items = resp.response?.result?.items ?: emptyList()
            Log.d(TAG, "searchDistrict: Found ${items.size} items")

            val results = items.map { item ->
                DistrictResult(
                    title = item.title ?: "",
                    category = item.category ?: "",
                    address = item.address ?: ""
                )
            }.filter { it.title.isNotBlank() }

            Log.d(TAG, "searchDistrict: Returning ${results.size} results")
            results
        } catch (e: Exception) {
            Log.e(TAG, "searchDistrict: Error - ${e.message}", e)
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
            @Query("page") page: Int,
            @Query("category") category: String,
            @Query("crs") crs: String,
            @Query("format") format: String,
            @Query("errorformat") errorFormat: String
        ): VWorldResponse
    }

    // -------- Response DTOs --------

    private data class VWorldResponse(
        val response: VWorldResponseBody? = null
    )

    private data class VWorldResponseBody(
        val status: String? = null,
        val result: VWorldResult? = null,
        val error: VWorldError? = null
    )

    private data class VWorldError(
        val text: String? = null,
        val code: String? = null
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
