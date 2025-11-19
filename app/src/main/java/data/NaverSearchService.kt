package com.example.project_2.data

import android.util.Log
import com.example.project_2.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 네이버 블로그 검색 기반 인기 점수 서비스
 * - query(가게명 + 주소)를 넣으면,
 *   블로그 결과 total 개수를 기반으로 popularityScore를 계산해서 돌려줌.
 */
object NaverSearchService {

    private const val TAG = "NaverSearch"
    private const val BASE_URL = "https://openapi.naver.com/v1/search/blog.json"

    private val client = OkHttpClient()

    data class Popularity(
        val totalCount: Int,
        val score: Double
    )

    /**
     * 네이버 블로그 검색 결과를 이용해 인기 점수 계산
     * - 반드시 IO 스레드(Dispatchers.IO)에서 호출할 것.
     */
    fun getPopularitySync(query: String): Popularity? {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "$BASE_URL?query=$encoded&display=10"

            val request = Request.Builder()
                .url(url)
                .addHeader("X-Naver-Client-Id", BuildConfig.NAVER_CLIENT_ID)
                .addHeader("X-Naver-Client-Secret", BuildConfig.NAVER_CLIENT_SECRET)
                .build()

            client.newCall(request).execute().use { resp ->
                // 응답 body를 먼저 문자열로 받아둔다 (성공/실패 둘 다에서 쓰려고)
                val bodyStr = resp.body?.string() ?: ""

                if (!resp.isSuccessful) {
                    // 🔥 코드 + 네이버가 준 에러 메시지까지 같이 출력
                    Log.w(
                        TAG,
                        "Naver blog search failed: code=${resp.code}, body=$bodyStr, url=$url"
                    )
                    return null
                }

                val json = JSONObject(bodyStr)

                val total = json.optInt("total", 0)
                // 너무 큰 수는 로그 스케일로 눌러서 점수화
                val score = kotlin.math.log10(total + 1.0)

                Popularity(totalCount = total, score = score)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getPopularitySync error: ${e.message}", e)
            null
        }
    }

    /**
     * 장소 이름 + 지역 힌트를 기반으로 인기 점수 조회
     * - regionHint 예: "부산 해운대", "서울 홍대입구" 등
     * - 내부적으로 getPopularitySync(query)를 호출한다.
     */
    fun getPopularityForPlace(
        placeName: String,
        regionHint: String? = null
    ): Popularity? {
        val query = if (regionHint.isNullOrBlank()) {
            placeName
        } else {
            "$regionHint $placeName"
        }
        return getPopularitySync(query)
    }
}
