package com.example.project_2.domain.model

import java.util.UUID

/**
 * 일정 관련 데이터 모델
 */

/** 전체 일정 */
data class Itinerary(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",                      // 일정 이름
    val days: List<DaySchedule>,
    val createdAt: Long = System.currentTimeMillis()
)

/** 하루 일정 */
data class DaySchedule(
    val day: Int,                           // 1, 2, 3
    val timeSlots: MutableList<TimeSlot>    // 시간대별 일정
)

/** 시간대 */
data class TimeSlot(
    val id: String = UUID.randomUUID().toString(),
    var startTime: String,                  // "09:00"
    var endTime: String,                    // "11:00"
    val place: Place? = null,               // 장소 (식사/이동은 null)
    val activity: String,                   // "VISIT", "MEAL", "TRANSPORT"
    var duration: Int,                      // 분 단위
    var travelInfo: TravelInfo? = null      // 이동 정보
)

/** 이동 정보 */
data class TravelInfo(
    val distance: Double,                   // km
    val duration: Int,                      // 분
    val type: String                        // "도보", "대중교통", "자동차"
)

/** GPT 일정 생성 응답 파싱용 */
data class GptDayAssignment(
    val day: Int,
    val places: List<GptPlaceAssignment>
)

data class GptPlaceAssignment(
    val placeId: String,
    val startTime: String,
    val durationMinutes: Int,
    val activity: String
)
