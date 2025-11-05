// package는 네 프로젝트에 맞게
package com.example.project_2.data.weather

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

// ====== OpenWeather 응답 DTO ======
data class WeatherMain(
    val temp: Double,          // 섭씨 (units=metric)
    val feels_like: Double,
    val humidity: Int
)
data class WeatherDesc(
    val main: String,          // "Rain", "Clear", "Clouds" ...
    val description: String    // "약한 비" 등
)
data class WeatherResp(
    val weather: List<WeatherDesc>,
    val main: WeatherMain
)

// 앱에서 쓰기 편한 경량 DTO (Repository에서 바로 사용 가능)
data class SimpleWeather(
    val tempC: Double,
    val condition: String,
    val icon: String? = null   // 필요 시 OpenWeather 아이콘 코드 붙여도 됨
)

// ====== OpenWeather REST API ======
interface OpenWeatherApi {
    // 도시명 기반
    @GET("data/2.5/weather")
    suspend fun current(
        @Query("q") city: String
    ): WeatherResp

    // 좌표 기반
    @GET("data/2.5/weather")
    suspend fun currentByCoord(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double
    ): WeatherResp
}

// ====== Service ======
object WeatherService {
    private var api: OpenWeatherApi? = null

    /** 앱 시작 시 한 번 호출해서 API 키를 주입해 주세요. */
    fun init(openWeatherApiKey: String) {
        val auth = Interceptor { chain ->
            val url = chain.request().url.newBuilder()
                .addQueryParameter("appid", openWeatherApiKey)
                .addQueryParameter("lang", "kr")
                .addQueryParameter("units", "metric") // 섭씨로 받음
                .build()
            val req = chain.request().newBuilder().url(url).build()
            chain.proceed(req)
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(auth)
            .build()

        api = Retrofit.Builder()
            .baseUrl("https://api.openweathermap.org/")
            .addConverterFactory(GsonConverterFactory.create())
            .client(client)
            .build()
            .create(OpenWeatherApi::class.java)
    }

    /** 도시명으로 현재 날씨(경량) */
    suspend fun currentByCity(city: String): SimpleWeather? {
        val svc = api ?: return null
        val res = svc.current(city)
        return SimpleWeather(
            tempC = res.main.temp,
            condition = res.weather.firstOrNull()?.main ?: "Unknown",
            icon = null
        )
    }

    /** 위도/경도로 현재 날씨(경량) — RealTravelRepository에서 사용 */
    suspend fun currentByLatLng(lat: Double, lon: Double): SimpleWeather? {
        val svc = api ?: return null
        val res = svc.currentByCoord(lat, lon)
        return SimpleWeather(
            tempC = res.main.temp,
            condition = res.weather.firstOrNull()?.main ?: "Unknown",
            icon = null
        )
    }

    /** 간단 테스트용 (UI에서 빠르게 확인) */
    suspend fun testWeather(city: String = "Seoul"): String {
        val svc = requireNotNull(api) { "WeatherService.init() 먼저 호출하세요." }
        val res = svc.current(city)
        return "🌤️ $city: ${res.weather.firstOrNull()?.description}, ${res.main.temp}°C (체감 ${res.main.feels_like}°C)"
    }
}
