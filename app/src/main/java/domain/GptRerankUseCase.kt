package com.example.project_2.domain

import com.example.project_2.data.openai.ChatMessage
import com.example.project_2.data.openai.OpenAiService
import com.example.project_2.domain.model.FilterState
import com.example.project_2.domain.model.Place
import com.example.project_2.domain.model.RecommendationResult
import com.example.project_2.domain.model.WeatherInfo
import com.google.gson.Gson

// === GPT 결과 스키마 ===
data class RankedPlace(
    val id: String,
    val score: Double,
    val reason: String,
    val indoor: Boolean?
)

data class RerankResult(
    val weather_policy: String,
    val picked: List<RankedPlace>
)

object GptRerankUseCase {
    private val gson = Gson()

    // ====== 리플렉션 헬퍼: 필드 이름이 달라도 안전하게 값을 꺼내기 ======
    private fun <T> getField(o: Any, vararg names: String): T? {
        for (n in names) {
            try {
                val f = o.javaClass.getDeclaredField(n)
                f.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val v = f.get(o) as? T
                if (v != null) return v
            } catch (_: NoSuchFieldException) {
                // try next
            } catch (_: Throwable) {
                // ignore
            }
        }
        return null
    }

    private fun getString(o: Any, vararg names: String): String? =
        getField<String>(o, *names)

    private fun getBool(o: Any, vararg names: String): Boolean? =
        getField<Boolean>(o, *names)

    private fun getDouble(o: Any, vararg names: String): Double? {
        for (n in names) {
            try {
                val f = o.javaClass.getDeclaredField(n)
                f.isAccessible = true
                val v = f.get(o)
                when (v) {
                    is Double -> return v
                    is Float -> return v.toDouble()
                    is Int -> return v.toDouble()
                    is Long -> return v.toDouble()
                    is String -> v.toDoubleOrNull()?.let { return it }
                }
            } catch (_: NoSuchFieldException) {
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun MutableMap<String, Any>.putIfNotNull(key: String, value: Any?) {
        if (value != null) this[key] = value
    }

    // =====================================================================
    // ① 실제 GPT 재랭크: category + weather + candidates → 상위 정렬된 Place 목록
    // =====================================================================
    suspend fun rerank(
        category: String,
        weather: WeatherInfo,
        candidates: List<Place>
    ): List<Place> {

        // 후보를 경량 JSON으로 변환 (필드명 다양성 흡수)
        val compact = candidates.mapNotNull { p ->
            val obj: Any = p

            val id = getString(obj, "id", "placeId")
            val name = getString(obj, "name", "place_name")
            val addr = getString(
                obj,
                "address", "roadAddressName", "addressName",
                "road_address_name", "address_name"
            ) ?: ""

            // 위경도: 카카오는 x=lng, y=lat 이기도 함
            val lat = getDouble(obj, "latitude", "lat", "y")
            val lng = getDouble(obj, "longitude", "lng", "x")

            val tags = getString(obj, "category", "categoryName", "category_name") ?: category
            val indoor = getBool(obj, "isIndoorHint", "indoor")

            if (id == null || name == null || lat == null || lng == null) {
                null // 필수 값 없으면 스킵
            } else {
                mapOf(
                    "id" to id,
                    "name" to name,
                    "addr" to addr,
                    "lat" to lat,
                    "lng" to lng,
                    "tags" to tags,
                    "isIndoorHint" to indoor
                )
            }
        }

        // 후보가 하나도 없으면 그대로 반환
        if (compact.isEmpty()) return candidates

        // === weatherBrief 만들기: 필드명이 달라도 안전하게 ===
        val weatherBrief = run {
            val m = mutableMapOf<String, Any>()

            // 온도(섭씨 추천)
            val temp = getDouble(weather, "tempC", "temperatureC", "temp", "temp_c", "temperature")
            val feels = getDouble(weather, "feelsLikeC", "feels_like", "feelsLike", "feels_like_c")

            val hum = getDouble(weather, "humidity", "hum")?.toInt()

            // 상태(비/맑음/눈 등)
            val cond = getString(weather, "condition", "main", "weatherMain", "description", "status") ?: "Unknown"

            // 바람(kph/mps)
            val wind = getDouble(
                weather,
                "windKph", "wind_kph", "windSpeedKph",
                "wind_speed", "wind", "windMps", "wind_mps"
            )

            m.putIfNotNull("tempC", temp)
            m.putIfNotNull("feelsLikeC", feels)
            m.putIfNotNull("humidity", hum)
            m.putIfNotNull("condition", cond)
            m.putIfNotNull("wind", wind)
            m
        }

        val system = ChatMessage(
            role = "system",
            content = """
                역할: 지역 추천 큐레이터.
                목표: 제공된 후보 안에서 날씨 안전/쾌적성과 카테고리 적합성을 반영해 상위 5개 선정/정렬.
                규칙:
                - 반드시 JSON만 출력.
                - 비/눈/강풍 또는 체감 ≤ 0℃ / ≥ 32℃ 이면 실내 위주로 가산점.
                - 입력 후보만 사용(새 상호명 창작 금지).
                - 각 항목에 선택 이유를 1문장으로 작성.
                - 스키마:
                  {
                    "weather_policy": "한 줄 요약",
                    "picked": [
                       {"id":"원본 id","score":0.0,"reason":"...", "indoor": true|false|null}
                    ]
                  }
            """.trimIndent()
        )

        val user = ChatMessage(
            role = "user",
            content = """
                카테고리: $category
                현재날씨: ${gson.toJson(weatherBrief)}
                후보목록: ${gson.toJson(compact)}
                출력은 스키마 그대로.
            """.trimIndent()
        )

        // ✅ OpenAiService.complete가 "JSON 문자열"을 반환한다고 가정
        val json = OpenAiService.complete(listOf(system, user))

        // 만약 객체를 반환한다면 아래처럼 한 줄만 교체:
        // val resp = OpenAiService.complete(listOf(system, user))
        // val json = resp.choices.firstOrNull()?.message?.content ?: "{}"

        val parsed = try {
            gson.fromJson(json, RerankResult::class.java)
        } catch (_: Exception) {
            RerankResult("fallback", emptyList())
        }

        if (parsed.picked.isEmpty()) return candidates

        // id -> 원본 객체 매핑
        val byId: Map<String, Place> = candidates.associateBy { cand ->
            getString(cand, "id", "placeId") ?: ""
        }.filterKeys { it.isNotBlank() }

        // 스코어 순으로 원본 반환 (상위 5개)
        return parsed.picked
            .sortedByDescending { it.score }
            .mapNotNull { byId[it.id] }
            .take(5)
    }

    // =====================================================================
    // ② 어댑터 오버로드: Repository에서 바로 쓰기 좋게 포장 (nullable weather 포함)
    // =====================================================================
    suspend fun rerank(
        filter: FilterState,
        candidates: List<Place>,
        weather: WeatherInfo?
    ): RecommendationResult {

        val category = "카페"

        val ranked = try {
            if (weather != null) {
                rerank(category = category, weather = weather, candidates = candidates)
            } else {
                candidates
            }
        } catch (_: Exception) {
            candidates
        }

        val reason = if (ranked === candidates) "폴백/원본 유지" else "GPT 재랭크 결과"
        return RecommendationResult(weather, ranked)
    }
}
