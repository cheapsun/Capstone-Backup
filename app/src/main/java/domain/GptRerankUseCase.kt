package com.example.project_2.domain

import android.util.Log
import com.example.project_2.domain.model.FilterState
import com.example.project_2.domain.model.Place
import com.example.project_2.domain.model.WeatherInfo
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * Kakao 후보 + 사용자 선호 + 날씨 기반 GPT 재랭크 유즈케이스
 * - 실제 Place.id로 프롬프트/매칭 (pN은 백업 매핑)
 * - 입력과 동일하면 1회 재시도
 * - ✅ GPT score가 없거나 일부만 있을 때도, GPT가 만든 "순서" 자체가 이후 파이프라인에서 유지되도록
 *    순서를 기반으로 fallback score를 부여하여 rebalance에서 우선 반영되게 함.
 */
class GptRerankUseCase(
    private val openAi: OpenAiPort
) {
    private val TAG = "GptRerank"
    private val MAX_CANDIDATES_IN_PROMPT = 30

    data class RankedPlace(val id: String, val score: Int, val reason: String?)

    /**
     * 결과:
     * - places: 최종 재배열된 장소 목록(Place.score 채워짐: GPT score or 순위기반 fallback score)
     * - reasons: place.id -> GPT가 준 한 줄 이유
     * - aiTopIds: 재랭크가 '실제로' 적용되었다고 판단될 때 상위 3개 id (UI 뱃지용)
     */
    data class RerankOutput(
        val places: List<Place>,
        val reasons: Map<String, String>,
        val aiTopIds: Set<String> = emptySet()
    )

    private fun safeToText(v: Any?): String = when (v) {
        null -> "미지정"
        is Enum<*> -> v.name
        else -> v.toString()
    }

    /** 프롬프트: 실제 Place.id 사용 + origIndex 포함 + 입력순서 금지 + score 명세(있으면) */
    private fun buildPrompt(
        filter: FilterState,
        weather: WeatherInfo?,
        items: List<Place>
    ): String {
        val weatherText = weather?.let {
            "- 현재 기온: ${"%.1f".format(it.tempC)}℃ / 상태: ${it.condition}"
        } ?: "- 현재 날씨 정보 없음"

        val cats = if (filter.categories.isEmpty()) "미지정"
        else filter.categories.joinToString(", ") { safeToText(it) }

        val budget = "예산(1인): ${filter.budgetPerPerson}원"
        val companion = safeToText(filter.companion)
        val duration  = safeToText(filter.duration)

        val candidatesText = buildString {
            items.forEachIndexed { idx, p ->
                val cat = runCatching { safeToText(p.category) }.getOrElse { "-" }
                val rating = runCatching { p.rating?.toString() ?: "-" }.getOrElse { "-" }
                val dist = runCatching { p.distanceMeters?.toString() ?: "-" }.getOrElse { "-" }

                // ✅ 네이버 정보 문자열로 추가
                val naverScore = runCatching {
                    p.naverPopularityScore?.let { "%.3f".format(it) } ?: "-"
                }.getOrElse { "-" }

                val blogCnt = runCatching {
                    p.naverBlogCount?.toString() ?: "-"
                }.getOrElse { "-" }

                appendLine(
                    "- origIndex=$idx, id=${p.id}, name=${p.name}, " +
                            "cat=$cat, rating=$rating, distM=$dist, " +
                            "naverScore=$naverScore, naverBlogs=$blogCnt"
                )
            }
        }

        // ✅ 세부 메모(꼭 가보고 싶은 가게/장소 등)
        val extraNoteBlock = if (filter.extraNote.isNotBlank()) {
            """
- 사용자가 직접 남긴 메모:
  "${filter.extraNote.trim()}"
- 위 메모에 언급된 '꼭 가보고 싶은 장소/가게'가 후보 목록에 존재한다면
  - 가능한 한 최종 추천 목록에 반드시 포함하고,
  - 상위 순위에 배치하세요.
- 후보 목록에 존재하지 않는 이름은 참고 정보로만 사용하고,
  분위기·위치·가격대가 비슷한 다른 장소를 상위에 올리려고 노력하세요.
            """.trimIndent()
        } else {
            "- 추가 메모 없음"
        }

        // ✅ 체인점 패널티 규칙
        val chainPenaltyRule = """
- 프랜차이즈(체인점) 패널티: 아래 키워드가 이름에 포함되면 지역 특색이 부족하므로 **순위를 낮추세요.**
  (스타벅스, 메가커피, 이디야, 투썸플레이스, 빽다방, 할리스, 커피빈, 공차, 폴바셋, 엔젤리너스, 탐앤탐스, 더벤티 등)
- 단, 해당 지점만의 독특한 가치(예: 한강뷰, 루프탑, 전시·공연 결합, 한정 메뉴 등)가 있다면 예외로 둘 수 있습니다.
  이 경우 반드시 그 구체적인 이유를 reason에 한 줄로 명시하세요.
        """.trimIndent()

        // ✅ 네이버 점수 활용 규칙 추가
        val naverRule = """
- 각 후보에는 naverScore(네이버 블로그 검색 결과 기반 인기 점수)와
  naverBlogs(관련 블로그 글 수)가 포함될 수 있습니다.
- 이 값이 높을수록 실제로 많이 언급되고 인기 있는 장소이므로,
  다른 조건(카테고리 적합성, 동행/예산/시간/날씨)이 비슷하다면
  naverScore가 높은 장소를 더 상위에 배치하세요.
        """.trimIndent()

        return """
당신은 감성적인 여행 큐레이터입니다.
아래 사용자 조건과 후보 장소를 바탕으로, 모든 후보를 빠짐없이 한 번씩만 포함하여 **재배열**해 주세요.
각 장소마다 "reason"을 한 문장(자연스럽고 사람처럼, 20~80자)으로 작성합니다.

[사용자 조건]
- 지역: ${filter.region.ifBlank { "미지정(입력값 기준 중심좌표 사용)" }}
- 선호 카테고리: $cats
- 동행: $companion
- 소요시간: $duration
- $budget

[날씨]
$weatherText

[사용자 추가 메모]
$extraNoteBlock

[평가 기준]
- 현지인의 시선에서 '여행 중 방문할 만한 특별한 장소'를 우선합니다.
- 지역 고유성, 희소성, 리뷰 감성, 날씨 적합성(실내/실외), 동행/체류시간/예산 등을 종합 고려합니다.
$chainPenaltyRule
$naverRule
- 같은 유형이 몰리면 다양성을 확보하세요.
- reason은 한국어 한 문장(20~80자), 공백/기호만 금지.
- 모든 후보를 빠짐없이 한 번씩만 포함하고, id는 반드시 그대로 사용하세요.
- 입력 순서와 동일한 결과는 0점입니다. 절반 이상 위치가 바뀌어야 합니다.
- 가능한 경우 score(0~100 정수)를 함께 제공합니다. (높을수록 추천 우선)

[후보 목록]
$candidatesText

출력 형식:
{"ordered":[{"id":"<place_id>","score":95,"reason":"..."}]}
""".trimIndent()
    }

    /** 기존 호환용: 장소만 */
    suspend fun rerank(
        filter: FilterState,
        weather: WeatherInfo?,
        candidates: List<Place>
    ): List<Place> = rerankWithReasons(filter, weather, candidates).places

    /** 최종: GPT 순서 + GPT 이유(+ Top3 id) + ✅ 순서기반 fallback score 반영 */
    suspend fun rerankWithReasons(
        filter: FilterState,
        weather: WeatherInfo?,
        candidates: List<Place>
    ): RerankOutput {
        if (candidates.isEmpty()) return RerankOutput(emptyList(), emptyMap(), emptySet())

        val capped = if (candidates.size > MAX_CANDIDATES_IN_PROMPT)
            candidates.subList(0, MAX_CANDIDATES_IN_PROMPT) else candidates

        Log.d(TAG, "Before(ids): " + capped.joinToString { it.id })

        // 1차 호출
        val prompt = buildPrompt(filter, weather, capped)
        Log.d(TAG, "🔹 GPT 프롬프트:\n$prompt")

        val first = callAndAssemble(prompt, capped)
        Log.d(TAG, "After(ids, first): " + first.places.joinToString { it.id })
        Log.d(TAG, "final names(first): " + first.places.joinToString { it.name })

        // 동일하면 1회 재시도(더 강한 지시)
        return if (sameOrder(first.places, capped)) {
            Log.w(TAG, "⚠️ order unchanged → retry once with stronger instruction")
            val retryPrompt = prompt + "\n\n[추가 지시] 방금과 **다른 순서**로 출력하세요. **입력과 동일한 순서 금지**입니다."
            val second = callAndAssemble(retryPrompt, capped)

            Log.d(TAG, "After(ids, retry): " + second.places.joinToString { it.id })
            Log.d(TAG, "final names(retry): " + second.places.joinToString { it.name })

            if (sameOrder(second.places, capped)) first else second
        } else {
            first
        }
    }

    /** 동일 순서인지 (id 기준) */
    private fun sameOrder(a: List<Place>, b: List<Place>): Boolean =
        a.size == b.size && a.indices.all { a[it].id == b[it].id }

    /**
     * 한 번 호출 + 파싱 + 매칭 + 누락 보강 + AI 사용 판정 + TOP3 계산
     * ✅ Place.score 반영: GPT score가 있으면 사용, 없으면 "GPT가 만든 순서"를 점수로 환산해 부여
     *    → 이후 rebalance 단계에서 GPT 순서가 유지되는 효과.
     */
    private suspend fun callAndAssemble(prompt: String, capped: List<Place>): RerankOutput {
        val raw = runCatching { openAi.completeJson(prompt) }
            .onFailure { Log.e(TAG, "OpenAI call failed: ${it.message}") }
            .getOrElse { "{}" }

        Log.d(TAG, "🔹 GPT 응답 원문:\n$raw")
        val jsonText = sanitizeToJson(raw)
        Log.d(TAG, "🔹 파싱 대상 JSON:\n$jsonText")

        val ranked: List<RankedPlace> = runCatching {
            val root = JSONObject(jsonText)
            val arr: JSONArray = root.optJSONArray("ordered") ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id", "").trim()
                    if (id.isBlank()) continue
                    val score = o.optInt("score", Int.MIN_VALUE) // 없으면 Int.MIN_VALUE로 표시
                    val reason = o.optString("reason", "").ifBlank { null }
                    add(RankedPlace(id, score, reason))
                }
            }
        }.onFailure { Log.e(TAG, "JSON parse failed: ${it.message}") }
            .getOrElse { emptyList() }

        Log.d(TAG, "parsed ids: " + ranked.joinToString { it.id })

        val byRealId = capped.associateBy { it.id }
        val used = HashSet<String>()

        fun idToIndex(id: String): Int? =
            if (id.startsWith("p")) id.drop(1).toIntOrNull() else null

        fun resolveRealId(gptId: String): String? {
            if (byRealId.containsKey(gptId)) return gptId
            val idx = idToIndex(gptId) ?: return null
            return capped.getOrNull(idx)?.id
        }

        // 1) GPT 순서를 "그대로" 적용 (가능한 항목만), 아직 점수는 확정하지 않음
        val inOrder: List<Place> = ranked.mapNotNull { rp ->
            val realId = resolveRealId(rp.id) ?: return@mapNotNull null
            val origin = byRealId[realId] ?: return@mapNotNull null
            if (!used.add(realId)) return@mapNotNull null
            origin
        }

        // 2) 이유 맵(실제 place.id로)
        val reasonsMap = LinkedHashMap<String, String>().apply {
            ranked.forEach { rp ->
                val pid = resolveRealId(rp.id)
                val reason = rp.reason?.let { sanitizeReason(it) }
                if (pid != null && !reason.isNullOrBlank()) put(pid, reason)
            }
        }

        // 3) 누락 보강: GPT가 못 맞춘 항목을 "뒤에" 보존 (우선순위는 낮음)
        val finalOrdered = ArrayList<Place>(capped.size).apply {
            addAll(inOrder)
            capped.forEach { if (used.add(it.id)) add(it) }
        }

        // 4) 점수 부여
        val scoreFromGpt: Map<String, Double> = buildMap {
            ranked.forEachIndexed { _, rp ->
                val realId = resolveRealId(rp.id) ?: return@forEachIndexed
                if (rp.score != Int.MIN_VALUE) put(realId, rp.score.toDouble())
            }
        }

        val fallbackBase = 100.0  // 앞에 있을수록 100, 99, 98 ...
        val ensuredScored = finalOrdered.mapIndexed { index, p ->
            val s = scoreFromGpt[p.id] ?: (fallbackBase - index)
            p.copy(score = s)
        }

        // 5) "AI 사용" 판정
        val orderChanged = !sameOrder(ensuredScored, capped)
        val anyReason = reasonsMap.isNotEmpty()
        val aiUsed = ranked.isNotEmpty() && (orderChanged || anyReason)

        // 6) TOP3 id 세트 산출
        val top3 = if (aiUsed) ensuredScored.take(3).map { it.id }.toSet() else emptySet()

        Log.d(
            TAG,
            "✅ ensured scores sample: " + ensuredScored.take(5).joinToString {
                "${it.name}:${"%.1f".format(it.score ?: 0.0)}"
            }
        )

        return RerankOutput(ensuredScored, reasonsMap, top3)
    }

    private fun clampScore(s: Int): Int = max(0, min(100, s))

    /** 코드펜스/텍스트 섞였을 때 첫 JSON 오브젝트만 추출 */
    private fun sanitizeToJson(raw: String): String {
        val cleaned = raw.replace("```json", "```").replace("```", "").trim()
        val s = cleaned.indexOf('{')
        val e = cleaned.lastIndexOf('}')
        return if (s >= 0 && e > s) cleaned.substring(s, e + 1) else "{}"
    }

    /** 한 줄 정리(너무 길면 …) */
    private fun sanitizeReason(src: String, maxLen: Int = 80): String {
        val oneLine = src.replace(Regex("\\s+"), " ")
            .replace(Regex("[\\p{Cntrl}]"), "")
            .trim()
        return if (oneLine.isEmpty()) "(AI가 이유를 비웠습니다)"
        else if (oneLine.length <= maxLen) oneLine
        else oneLine.take(maxLen - 1) + "…"
    }
}

/** OpenAI 호출 추상화 */
interface OpenAiPort {
    suspend fun completeJson(prompt: String): String
}
