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
당신은 여행 일정 플래너입니다.
선택된 ${places.size}개 장소를 ${days}일 일정으로 배치해주세요.

[선택된 장소]
$placesText

[조건]
- 총 ${days}일 일정
- 인원: ${filter.numberOfPeople}명$mandatoryText

[중요 규칙]
1. 시간대별 활동 배치:
   - 09:00-12:00 (오전): 관광지, 사진 명소, 문화 시설, 체험 활동
   - 12:00-13:30 (점심): FOOD 카테고리 장소 또는 "MEAL" 활동 (반드시 포함)
   - 13:30-17:00 (오후): 관광지, 카페, 쇼핑
   - 18:00-19:30 (저녁): FOOD 카테고리 장소 또는 "MEAL" 활동 (반드시 포함)
   - 19:30-22:00 (야간): 나이트 명소, 야경

2. 카테고리별 배치 규칙:
   - FOOD: 점심(12:00) 또는 저녁(18:00) 시간대에만 배치
   - CAFE: 오후(14:00-17:00) 시간대에 배치
   - PHOTO, CULTURE, HEALING, EXPERIENCE: 오전/오후 시간대 배치
   - NIGHT: 저녁(19:30 이후) 시간대에 배치

3. 필수 식사 시간:
   - 점심: 12:00 (90분)
   - 저녁: 18:00 (90분)
   - FOOD 카테고리 장소가 있으면 해당 시간대에 배치, 없으면 "MEAL" 활동으로 삽입

4. 체류 시간:
   - FOOD: 90분
   - CAFE: 60분
   - PHOTO, HEALING: 90분
   - CULTURE, EXPERIENCE: 120분
   - NIGHT: 90분
   - SHOPPING: 90분

5. Day별로 장소를 균등하게 배치하되, 위 시간대 규칙을 준수

출력 형식 (JSON):
{
  "days": [
    {
      "day": 1,
      "slots": [
        {"place_id": 0, "start_time": "09:00", "duration_min": 120, "activity": "VISIT"},
        {"place_id": 2, "start_time": "12:00", "duration_min": 90, "activity": "VISIT"},
        {"activity": "MEAL", "start_time": "18:00", "duration_min": 90}
      ]
    }
  ]
}

주의: place_id는 FOOD/CAFE 등 해당 카테고리 장소가 있을 때만 사용하고,
      장소가 없으면 "activity": "MEAL"로 식사 시간만 표시하세요.
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
            generateFallbackItinerary(places, days).days
        }
    }

    private fun generateFallbackItinerary(
        places: List<Place>,
        days: Int
    ): Itinerary {
        Log.d(TAG, "Using fallback itinerary generation for $days days with ${places.size} places")

        // Separate places by category
        val foodPlaces = places.filter { it.category == Category.FOOD }
        val cafePlaces = places.filter { it.category == Category.CAFE }
        val nightPlaces = places.filter { it.category == Category.NIGHT }
        val stayPlaces = places.filter { it.category == Category.STAY }
        val otherPlaces = places.filter {
            it.category !in setOf(Category.FOOD, Category.CAFE, Category.NIGHT, Category.STAY)
        }

        Log.d(TAG, "Category distribution: FOOD=${foodPlaces.size}, CAFE=${cafePlaces.size}, " +
                "NIGHT=${nightPlaces.size}, STAY=${stayPlaces.size}, OTHER=${otherPlaces.size}")

        val schedules = mutableListOf<DaySchedule>()

        // Distribute places across days
        val otherPerDay = (otherPlaces.size + days - 1) / days
        val cafePerDay = (cafePlaces.size + days - 1) / days
        val nightPerDay = (nightPlaces.size + days - 1) / days

        for (dayIndex in 0 until days) {
            val slots = mutableListOf<TimeSlot>()
            var currentTime = LocalTime.of(9, 0)

            // Morning slot: Other activities (관광, 문화, 체험 등)
            val otherStart = dayIndex * otherPerDay
            val otherEnd = minOf(otherStart + (otherPerDay / 2).coerceAtLeast(1), otherPlaces.size)
            val morningPlaces = if (otherStart < otherPlaces.size) {
                otherPlaces.subList(otherStart, otherEnd)
            } else emptyList()

            morningPlaces.forEach { place ->
                val duration = getDurationForCategory(place.category)
                slots.add(createTimeSlot(place, currentTime, duration))
                currentTime = currentTime.plusMinutes(duration.toLong()).plusMinutes(20) // 20분 이동
            }

            // Lunch at 12:00
            currentTime = LocalTime.of(12, 0)
            val lunchPlace = foodPlaces.getOrNull(dayIndex * 2)
            if (lunchPlace != null) {
                slots.add(createTimeSlot(lunchPlace, currentTime, 90))
            } else {
                slots.add(createMealSlot(currentTime, 90))
            }
            currentTime = LocalTime.of(13, 30)

            // Afternoon: Remaining other activities + cafes
            val afternoonOtherStart = otherEnd
            val afternoonOtherEnd = minOf(otherStart + otherPerDay, otherPlaces.size)
            val afternoonPlaces = if (afternoonOtherStart < otherPlaces.size) {
                otherPlaces.subList(afternoonOtherStart, afternoonOtherEnd)
            } else emptyList()

            afternoonPlaces.forEach { place ->
                val duration = getDurationForCategory(place.category)
                slots.add(createTimeSlot(place, currentTime, duration))
                currentTime = currentTime.plusMinutes(duration.toLong()).plusMinutes(20)
            }

            // Cafe time (14:00-17:00)
            val cafeStart = dayIndex * cafePerDay
            val cafeEnd = minOf(cafeStart + cafePerDay, cafePlaces.size)
            val dayCafes = if (cafeStart < cafePlaces.size) {
                cafePlaces.subList(cafeStart, cafeEnd)
            } else emptyList()

            dayCafes.forEach { cafe ->
                if (currentTime.hour < 17) {
                    currentTime = maxOf(currentTime, LocalTime.of(14, 0))
                    slots.add(createTimeSlot(cafe, currentTime, 60))
                    currentTime = currentTime.plusMinutes(60).plusMinutes(15)
                }
            }

            // Dinner at 18:00
            currentTime = LocalTime.of(18, 0)
            val dinnerPlace = foodPlaces.getOrNull(dayIndex * 2 + 1)
            if (dinnerPlace != null) {
                slots.add(createTimeSlot(dinnerPlace, currentTime, 90))
            } else {
                slots.add(createMealSlot(currentTime, 90))
            }
            currentTime = LocalTime.of(19, 30)

            // Evening: Night spots
            val nightStart = dayIndex * nightPerDay
            val nightEnd = minOf(nightStart + nightPerDay, nightPlaces.size)
            val nightSpots = if (nightStart < nightPlaces.size) {
                nightPlaces.subList(nightStart, nightEnd)
            } else emptyList()

            nightSpots.forEach { night ->
                slots.add(createTimeSlot(night, currentTime, 90))
                currentTime = currentTime.plusMinutes(90).plusMinutes(15)
            }

            schedules.add(DaySchedule(day = dayIndex + 1, timeSlots = slots))
        }

        return Itinerary(days = schedules)
    }

    private fun getDurationForCategory(category: Category): Int = when (category) {
        Category.FOOD -> 90
        Category.CAFE -> 60
        Category.CULTURE, Category.EXPERIENCE -> 120
        Category.PHOTO, Category.HEALING, Category.SHOPPING -> 90
        Category.NIGHT -> 90
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
