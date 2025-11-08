package com.example.project_2.data.openai

import android.util.Log
import com.example.project_2.domain.OpenAiPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object OpenAiService : OpenAiPort {

    private const val TAG = "OpenAiService"
    private const val CHAT_ENDPOINT = "https://api.openai.com/v1/chat/completions"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    @Volatile private var apiKey: String = ""
    @Volatile private var model: String = "gpt-4o-mini"

    // ✅ 타임아웃/안정성 강화
    private var client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 앱 시작 시 1회 호출 */
    fun init(apiKey: String, model: String = "gpt-4o-mini") {
        require(apiKey.isNotBlank()) { "OpenAiService.init(): apiKey 가 비어있습니다." }
        this.apiKey = apiKey.trim()
        this.model = model
        Log.d(TAG, "✅ OpenAI init: model=$model, keyPrefix=${this.apiKey.take(6)}")
    }

    private fun ready() = apiKey.isNotBlank()

    /** 프롬프트에 대해 'JSON만' 반환 (chat/completions 한 경로로만) */
    override suspend fun completeJson(prompt: String): String = withContext(Dispatchers.IO) {
        require(ready()) { "OpenAiService.init()을 먼저 호출하세요." }

        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You only reply with ONE valid JSON object. No prose, no code fences.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            // ✅ JSON 강제 (현재 chat/completions에서 가장 안정적)
            put("response_format", JSONObject().apply { put("type", "json_object") })
            put("temperature", 0.2)
            put("max_tokens", 1200)
        }.toString()

        val req = Request.Builder()
            .url(CHAT_ENDPOINT)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody(JSON))
            .build()

        // ✅ 간단한 재시도 2회
        repeat(2) { attempt ->
            try {
                Log.d(TAG, "➡️ POST /v1/chat/completions attempt=${attempt + 1} (model=$model)")
                client.newCall(req).execute().use { res ->
                    val text = res.body?.string().orEmpty()
                    if (!res.isSuccessful) {
                        Log.e(TAG, "❌ /chat/completions HTTP ${res.code}: $text")
                    } else {
                        val content = parseChatContent(text)
                        if (content.isNotBlank()) {
                            val cleaned = sanitize(content)
                            Log.d(TAG, "✅ chat ok: ${cleaned.take(160)}")
                            return@withContext if (isLikelyJson(cleaned)) cleaned else "{}"
                        } else {
                            Log.e(TAG, "parse(/chat) failed: empty content. raw=${text.take(200)}")
                        }
                    }
                }
            } catch (io: IOException) {
                Log.e(TAG, "IO(/chat): ${io.message}")
            }
        }
        "{}" // 모두 실패 시
    }

    // ---------- Helpers ----------

    /** Chat Completions 응답에서 content 추출 */
    private fun parseChatContent(raw: String): String {
        return try {
            val root = JSONObject(raw)
            val choices = root.getJSONArray("choices")
            val msg = choices.getJSONObject(0).getJSONObject("message")
            msg.getString("content")
        } catch (t: Throwable) {
            Log.e(TAG, "parseChatContent error: ${t.message}")
            ""
        }
    }

    /** 코드블록/양 끝 공백 제거 */
    private fun sanitize(s0: String): String {
        var s = s0.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```json").removePrefix("```").trim()
            if (s.endsWith("```")) s = s.removeSuffix("```").trim()
        }
        return s
    }

    private fun isLikelyJson(s: String): Boolean {
        if (s.isBlank()) return false
        val t = s.trim()
        if (!(t.startsWith("{") && t.endsWith("}"))) return false
        return try {
            JSONObject(t) // quick check
            true
        } catch (_: Throwable) {
            false
        }
    }
}
