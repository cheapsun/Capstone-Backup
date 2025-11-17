package com.example.project_2.domain

import android.util.Log
import com.example.project_2.domain.model.*
import com.example.project_2.data.openai.OpenAiService
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime

/**
 * 일정 생성 UseCase
 * - GPT를 사용해서 장소들을 Day별로 배치하고 시간 배정
 */
class ItineraryUseCase(
    private val openAi: OpenAiPort = OpenAiService
) {
    private val TAG = "ItineraryUseCase"

    /**
     * 일정 생성 (간단 버전)
     * @param selectedPlaces 사용자가 선택한 장소 리스트
     * @param filter 사용자 필터 (기간, 인원, 필수 장소 등)
     * @return 생성된 일정
     */
    suspend fun generateItinerary(
        selectedPlaces: List<Place>,
        filter: FilterState
    ): Itinerary {
        Log.d(TAG, "generateItinerary: ${selectedPlaces.size} places, ${filter.duration.toDays()} days")

        val days = filter.duration.toDays()

        // GPT에게 일정 생성 요청
        val prompt = buildItineraryPrompt(selectedPlaces, filter, days)
        Log.d(TAG, "GPT Prompt:\n$prompt")

        val gptResponse = try {
            openAi.completeJson(prompt)
        } catch (e: Exception) {
            Log.e(TAG, "GPT call failed, using fallback", e)
            // GPT 실패 시 간단한 fallback 로직
            return generateFallbackItinerary(selectedPlaces, days)
        }

        Log.d(TAG, "GPT Response:\n$gptResponse")

        // GPT 응답 파싱
        val daySchedules = parseGptResponse(gptResponse, selectedPlaces, days)

        return Itinerary(
            days = daySchedules,
            totalCost = daySchedules.sumOf { it.estimatedCost }
        )
    }

    private fun buildItineraryPrompt(
        places: List<Place>,
        filter: FilterState,
        days: Int
    ): String {
        val placesText = places.mapIndexed { idx, p ->
            "- id=$idx, name=${p.name}, category=${p.category}, lat=${p.lat}, lng=${p.lng}"
        }.joinToString("\n")

        val mandatoryText = if (filter.mandatoryPlace.isNotBlank()) {
            "\n- 필수 방문: ${filter.mandatoryPlace} (반드시 포함)"
        } else ""

        return """
당신은 여행 일정 플래너입니다.
선택된 ${places.size}개 장소를 ${days}일 일정으로 배치해주세요.

[선택된 장소]
$placesText

[조건]
- 총 ${days}일 일정
- 인원: ${filter.numberOfPeople}명
- 예산: 1인당 ${filter.budgetPerPerson}원$mandatoryText

[요구사항]
1. Day별로 균등하게 장소 배치 (Day당 ${places.size / days}~${(places.size / days) + 2}개)
2. 각 장소마다 시간 배정 (09:00부터 시작)
3. 점심(12:00), 저녁(19:00) 식사 시간 자동 삽입
4. 같은 지역 장소끼리 묶어서 배치
5. 각 장소 체류 시간:
   - 관광/사진: 2시간
   - 맛집: 1.5시간
   - 카페: 1시간
   - 문화: 2.5시간

출력 형식 (JSON):
{
  "days": [
    {
      "day": 1,
      "slots": [
        {"place_id": 0, "start_time": "09:00", "duration_min": 120, "activity": "VISIT"},
        {"activity": "MEAL", "start_time": "12:00", "duration_min": 90},
        {"place_id": 1, "start_time": "14:00", "duration_min": 60, "activity": "VISIT"}
      ]
    }
  ]
}
""".trimIndent()
    }

    private fun parseGptResponse(
        gptResponse: String,
        places: List<Place>,
        days: Int
    ): List<DaySchedule> {
        return try {
            val json = sanitizeJson(gptResponse)
            val root = JSONObject(json)
            val daysArray = root.getJSONArray("days")

            val schedules = mutableListOf<DaySchedule>()

            for (i in 0 until daysArray.length()) {
                val dayObj = daysArray.getJSONObject(i)
                val dayNum = dayObj.getInt("day")
                val slotsArray = dayObj.getJSONArray("slots")

                val timeSlots = mutableListOf<TimeSlot>()

                for (j in 0 until slotsArray.length()) {
                    val slotObj = slotsArray.getJSONObject(j)
                    val startTime = slotObj.getString("start_time")
                    val durationMin = slotObj.getInt("duration_min")
                    val activity = slotObj.optString("activity", "VISIT")
                    val placeId = slotObj.optInt("place_id", -1)

                    val place = if (placeId >= 0 && placeId < places.size) {
                        places[placeId]
                    } else null

                    val endTime = calculateEndTime(startTime, durationMin)

                    timeSlots.add(
                        TimeSlot(
                            startTime = startTime,
                            endTime = endTime,
                            place = place,
                            activity = activity,
                            duration = durationMin
                        )
                    )
                }

                schedules.add(
                    DaySchedule(
                        day = dayNum,
                        timeSlots = timeSlots
                    )
                )
            }

            schedules
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse GPT response", e)
            generateFallbackItinerary(places, days).days
        }
    }

    private fun generateFallbackItinerary(
        places: List<Place>,
        days: Int
    ): Itinerary {
        Log.d(TAG, "Using fallback itinerary generation")

        val placesPerDay = places.chunked((places.size + days - 1) / days)

        val schedules = placesPerDay.mapIndexed { dayIndex, dayPlaces ->
            var currentTime = LocalTime.of(9, 0)
            val slots = mutableListOf<TimeSlot>()

            dayPlaces.forEachIndexed { idx, place ->
                // 점심 시간 추가
                if (currentTime.hour >= 12 && currentTime.hour < 13 && idx > 0) {
                    slots.add(
                        TimeSlot(
                            startTime = "12:00",
                            endTime = "13:30",
                            place = null,
                            activity = "MEAL",
                            duration = 90
                        )
                    )
                    currentTime = LocalTime.of(13, 30)
                }

                val duration = when (place.category) {
                    Category.FOOD -> 90
                    Category.CAFE -> 60
                    Category.CULTURE -> 150
                    else -> 120
                }

                val startTime = currentTime.toString()
                currentTime = currentTime.plusMinutes(duration.toLong())
                val endTime = currentTime.toString()

                slots.add(
                    TimeSlot(
                        startTime = startTime,
                        endTime = endTime,
                        place = place,
                        activity = "VISIT",
                        duration = duration
                    )
                )

                // 30분 이동 시간
                currentTime = currentTime.plusMinutes(30)
            }

            DaySchedule(
                day = dayIndex + 1,
                timeSlots = slots
            )
        }

        return Itinerary(days = schedules)
    }

    private fun sanitizeJson(raw: String): String {
        val cleaned = raw.replace("```json", "").replace("```", "").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        return if (start >= 0 && end > start) {
            cleaned.substring(start, end + 1)
        } else {
            "{}"
        }
    }

    private fun calculateEndTime(startTime: String, durationMin: Int): String {
        return try {
            val start = LocalTime.parse(startTime)
            start.plusMinutes(durationMin.toLong()).toString()
        } catch (e: Exception) {
            startTime
        }
    }
}
