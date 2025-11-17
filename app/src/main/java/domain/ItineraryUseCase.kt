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
     * @param autoAddMeals 식사 시간 자동 추가 여부
     * @return 생성된 일정
     */
    suspend fun generateItinerary(
        selectedPlaces: List<Place>,
        filter: FilterState,
        autoAddMeals: Boolean = false
    ): Itinerary {
        Log.d(TAG, "generateItinerary: ${selectedPlaces.size} places, ${filter.duration.toDays()} days, autoAddMeals=$autoAddMeals")

        val days = filter.duration.toDays()

        // GPT에게 일정 생성 요청
        val prompt = buildItineraryPrompt(selectedPlaces, filter, days)
        Log.d(TAG, "GPT Prompt:\n$prompt")

        val gptResponse = try {
            openAi.completeJson(prompt)
        } catch (e: Exception) {
            Log.e(TAG, "GPT call failed, using fallback", e)
            // GPT 실패 시 간단한 fallback 로직
            return generateFallbackItinerary(selectedPlaces, days, autoAddMeals)
        }

        Log.d(TAG, "GPT Response:\n$gptResponse")

        // GPT 응답 파싱
        val daySchedules = parseGptResponse(gptResponse, selectedPlaces, days, autoAddMeals)

        return Itinerary(days = daySchedules)
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
당신은 여행 일정 최적화 전문 AI입니다.
선택된 ${places.size}개 장소를 ${days}일 일정에 **최대한 많이** 포함하되, 여행자의 피로도와 동선을 고려하여 스마트하게 배치해주세요.

[선택된 장소]
$placesText

[조건]
- 총 ${days}일 일정
- 인원: ${filter.numberOfPeople}명$mandatoryText

[핵심 목표]
1. **가능한 많은 장소를 포함** (${places.size}개 중 최소 80% 이상 포함 목표)
2. 각 장소의 거리와 중요도를 고려하여 **체류 시간과 이동 시간을 동적으로 조정**
3. 시간대별 특성에 맞는 장소 배치

[시간대별 활동 가이드]
- 08:30-12:00 (오전): 관광지, 사진 명소, 문화 시설, 체험 활동
- 12:00-13:30 (점심): FOOD 카테고리 장소 또는 "MEAL" 활동
- 13:30-18:00 (오후): 관광지, 카페, 쇼핑, 힐링 장소
- 18:00-19:30 (저녁): FOOD 카테고리 장소 또는 "MEAL" 활동
- 19:30-22:30 (야간): 나이트 명소, 야경, 카페

[스마트 시간 배분 규칙]
1. **체류 시간을 유연하게 조정** (카테고리별 권장 시간은 참고만 하고 실제로는 동적으로 조정):
   - FOOD: 60-90분 (간단한 식사는 60분, 여유있는 식사는 90분)
   - CAFE: 30-60분 (휴식 겸 방문은 30분, 여유있게는 60분)
   - PHOTO: 45-75분 (사진만 찍는 곳은 45분, 둘러볼 곳 많으면 75분)
   - CULTURE, EXPERIENCE: 60-120분 (규모에 따라 조정)
   - HEALING, SHOPPING: 45-90분
   - NIGHT: 45-90분

2. **이동 시간 최적화**:
   - 가까운 장소(같은 지역): 5-10분
   - 중간 거리: 15-20분
   - 먼 거리: 25-30분
   - 위도/경도 차이로 거리 추정하여 배정

3. **더 많은 장소 포함을 위한 전략**:
   - 가까운 장소들은 체류 시간을 짧게 조정
   - 이동 동선을 최적화하여 이동 시간 최소화
   - 하루에 8-12개 장소 포함 목표 (식사 포함)
   - 필요시 오전을 08:30부터, 야간을 22:30까지 활용

4. **필수 식사**:
   - 점심: 12:00-13:30 (60-90분)
   - 저녁: 18:00-19:30 (60-90분)
   - FOOD 카테고리 장소가 있으면 우선 배치, 없으면 "MEAL" 활동

5. **Day별 균등 배치**:
   - ${days}일이면 각 날마다 약 ${(places.size.toDouble() / days).toInt()}-${(places.size.toDouble() / days + 2).toInt()}개 장소 배치
   - 거리와 동선을 고려하여 같은 지역 장소들을 같은 날에 배치

출력 형식 (JSON):
{
  "days": [
    {
      "day": 1,
      "slots": [
        {"place_id": 0, "start_time": "08:30", "duration_min": 60, "activity": "VISIT"},
        {"place_id": 1, "start_time": "09:40", "duration_min": 75, "activity": "VISIT"},
        {"place_id": 2, "start_time": "11:10", "duration_min": 45, "activity": "VISIT"},
        {"place_id": 3, "start_time": "12:00", "duration_min": 60, "activity": "VISIT"},
        {"place_id": 4, "start_time": "13:10", "duration_min": 60, "activity": "VISIT"},
        {"activity": "MEAL", "start_time": "18:00", "duration_min": 75}
      ]
    }
  ]
}

**중요**: 가능한 많은 장소를 포함하되, 시간 배분은 위 가이드를 참고하여 각 장소에 맞게 동적으로 조정하세요.
""".trimIndent()
    }

    private fun parseGptResponse(
        gptResponse: String,
        places: List<Place>,
        days: Int,
        autoAddMeals: Boolean
    ): List<DaySchedule> {
        return try {
            val json = sanitizeJson(gptResponse)
            val root = JSONObject(json)
            val daysArray = root.getJSONArray("days")

            val schedules = mutableListOf<DaySchedule>()

            // Only parse up to the requested number of days
            val maxDays = minOf(daysArray.length(), days)
            for (i in 0 until maxDays) {
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
            generateFallbackItinerary(places, days, autoAddMeals).days
        }
    }

    private fun generateFallbackItinerary(
        places: List<Place>,
        days: Int,
        autoAddMeals: Boolean
    ): Itinerary {
        Log.d(TAG, "Using fallback itinerary generation for $days days with ${places.size} places, autoAddMeals=$autoAddMeals")

        // Separate places by category
        val foodPlaces = places.filter { it.category == Category.FOOD }.toMutableList()
        val cafePlaces = places.filter { it.category == Category.CAFE }.toMutableList()
        val nightPlaces = places.filter { it.category == Category.NIGHT }.toMutableList()
        val stayPlaces = places.filter { it.category == Category.STAY }
        val otherPlaces = places.filter {
            it.category !in setOf(Category.FOOD, Category.CAFE, Category.NIGHT, Category.STAY)
        }.toMutableList()

        Log.d(TAG, "Category distribution: FOOD=${foodPlaces.size}, CAFE=${cafePlaces.size}, " +
                "NIGHT=${nightPlaces.size}, STAY=${stayPlaces.size}, OTHER=${otherPlaces.size}")

        val schedules = mutableListOf<DaySchedule>()
        var foodIndex = 0
        var cafeIndex = 0
        var otherIndex = 0
        var nightIndex = 0

        for (dayIndex in 0 until days) {
            val slots = mutableListOf<TimeSlot>()
            var currentTime = LocalTime.of(8, 30)  // 09:00 -> 08:30 (조금 더 일찍 시작)

            // ===== 오전 (08:30-12:00) =====
            // OTHER, FOOD(브런치), CAFE를 시간이 허락하는 한 배치
            while (currentTime.hour < 12 || (currentTime.hour == 12 && currentTime.minute == 0)) {
                val nextPlace = when {
                    otherIndex < otherPlaces.size -> otherPlaces[otherIndex++]
                    foodIndex < foodPlaces.size && currentTime.hour < 11 -> foodPlaces[foodIndex++]
                    cafeIndex < cafePlaces.size -> cafePlaces[cafeIndex++]
                    else -> break
                }

                val duration = getDurationForCategory(nextPlace.category)
                val endTime = currentTime.plusMinutes(duration.toLong())

                // 점심 시간(12:00) 전에 끝나야 함
                if (endTime.hour < 12 || (endTime.hour == 12 && endTime.minute == 0)) {
                    slots.add(createTimeSlot(nextPlace, currentTime, duration))
                    currentTime = endTime.plusMinutes(10) // 이동 시간 (20 -> 10분)
                } else {
                    // 다음 장소는 점심 후로
                    when (nextPlace.category) {
                        Category.FOOD -> foodIndex--
                        Category.CAFE -> cafeIndex--
                        else -> otherIndex--
                    }
                    break
                }
            }

            // ===== 점심 (12:00-13:00) =====
            currentTime = LocalTime.of(12, 0)
            if (foodIndex < foodPlaces.size) {
                val place = foodPlaces[foodIndex++]
                slots.add(createTimeSlot(place, currentTime, 60))  // 90 -> 60분
            } else if (autoAddMeals) {
                slots.add(createMealSlot(currentTime, 60))  // 90 -> 60분
            }
            currentTime = LocalTime.of(13, 0)  // 13:30 -> 13:00

            // ===== 오후 (13:00-18:00) =====
            // OTHER, CAFE, 추가 FOOD를 번갈아가며 배치
            while (currentTime.hour < 18) {
                val nextPlace = when {
                    otherIndex < otherPlaces.size -> otherPlaces[otherIndex++]
                    cafeIndex < cafePlaces.size -> cafePlaces[cafeIndex++]
                    foodIndex < foodPlaces.size -> foodPlaces[foodIndex++]
                    else -> break
                }

                val duration = getDurationForCategory(nextPlace.category)
                val endTime = currentTime.plusMinutes(duration.toLong())

                // 저녁 시간(18:00) 전에 끝나야 함
                if (endTime.hour < 18) {
                    slots.add(createTimeSlot(nextPlace, currentTime, duration))
                    currentTime = endTime.plusMinutes(10)  // 20 -> 10분
                } else {
                    // 다음 장소는 저녁 후로
                    when (nextPlace.category) {
                        Category.FOOD -> foodIndex--
                        Category.CAFE -> cafeIndex--
                        else -> otherIndex--
                    }
                    break
                }
            }

            // ===== 저녁 (18:00-19:00) =====
            currentTime = LocalTime.of(18, 0)
            if (foodIndex < foodPlaces.size) {
                val place = foodPlaces[foodIndex++]
                slots.add(createTimeSlot(place, currentTime, 60))  // 90 -> 60분
            } else if (autoAddMeals) {
                slots.add(createMealSlot(currentTime, 60))  // 90 -> 60분
            }
            currentTime = LocalTime.of(19, 0)  // 19:30 -> 19:00

            // ===== 야간 (19:00-22:30) =====
            // NIGHT > CAFE > FOOD 순서로 배치
            while (currentTime.hour < 22 || (currentTime.hour == 22 && currentTime.minute < 30)) {
                val nextPlace = when {
                    nightIndex < nightPlaces.size -> nightPlaces[nightIndex++]
                    cafeIndex < cafePlaces.size -> cafePlaces[cafeIndex++]
                    foodIndex < foodPlaces.size -> foodPlaces[foodIndex++]
                    else -> break
                }

                val duration = getDurationForCategory(nextPlace.category)
                val endTime = currentTime.plusMinutes(duration.toLong())

                // 22:30 전에 끝나야 함
                if (endTime.hour < 22 || (endTime.hour == 22 && endTime.minute <= 30)) {
                    slots.add(createTimeSlot(nextPlace, currentTime, duration))
                    currentTime = endTime.plusMinutes(10)  // 15 -> 10분
                } else {
                    // 시간 초과
                    when (nextPlace.category) {
                        Category.NIGHT -> nightIndex--
                        Category.CAFE -> cafeIndex--
                        Category.FOOD -> foodIndex--
                        else -> {}
                    }
                    break
                }
            }

            schedules.add(DaySchedule(day = dayIndex + 1, timeSlots = slots))
        }

        return Itinerary(days = schedules)
    }

    private fun getDurationForCategory(category: Category): Int = when (category) {
        Category.FOOD -> 60           // 90 -> 60분 (더 빠른 식사)
        Category.CAFE -> 45           // 60 -> 45분 (짧은 휴식)
        Category.CULTURE, Category.EXPERIENCE -> 75  // 120 -> 75분 (효율적 관람)
        Category.PHOTO -> 45          // 90 -> 45분 (사진 촬영)
        Category.HEALING, Category.SHOPPING -> 60    // 90 -> 60분
        Category.NIGHT -> 60          // 90 -> 60분
        Category.STAY -> 0
    }

    private fun createTimeSlot(place: Place, startTime: LocalTime, durationMin: Int): TimeSlot {
        return TimeSlot(
            startTime = startTime.toString(),
            endTime = startTime.plusMinutes(durationMin.toLong()).toString(),
            place = place,
            activity = "VISIT",
            duration = durationMin
        )
    }

    private fun createMealSlot(startTime: LocalTime, durationMin: Int): TimeSlot {
        return TimeSlot(
            startTime = startTime.toString(),
            endTime = startTime.plusMinutes(durationMin.toLong()).toString(),
            place = null,
            activity = "MEAL",
            duration = durationMin
        )
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
