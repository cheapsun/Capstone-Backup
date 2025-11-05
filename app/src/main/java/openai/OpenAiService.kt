package com.example.project_2.data.openai

import com.google.gson.annotations.SerializedName
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

// ==== DTOs ====
data class ChatMessage(
    val role: String,
    val content: String
)

data class ResponseFormat(val type: String)

data class ChatRequest(
    val model: String = "gpt-5o-mini",
    val temperature: Double = 0.2,
    @SerializedName("response_format") val responseFormat: ResponseFormat = ResponseFormat("json_object"),
    val messages: List<ChatMessage>
)

data class ChatChoice(val index: Int, val message: ChatMessage)
data class ChatResponse(val choices: List<ChatChoice>)

// ==== Retrofit API ====
interface OpenAiApi {
    @Headers("Content-Type: application/json")
    @POST("v1/chat/completions")
    suspend fun chat(@Body req: ChatRequest): ChatResponse
}

object OpenAiService {
    private const val BASE_URL = "https://api.openai.com/"
    private var api: OpenAiApi? = null

    fun init(openAiApiKey: String) {
        val auth = Interceptor { chain ->
            val req = chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $openAiApiKey")
                .build()
            chain.proceed(req)
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(auth)
            .build()

        api = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpenAiApi::class.java)
    }

    suspend fun complete(messages: List<ChatMessage>): String {
        val res = api!!.chat(
            ChatRequest(messages = messages)
        )
        return res.choices.firstOrNull()?.message?.content ?: "{}"
    }
}
