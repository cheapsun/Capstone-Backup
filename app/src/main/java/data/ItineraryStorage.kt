package com.example.project_2.data

import android.content.Context
import android.content.SharedPreferences
import com.example.project_2.domain.model.Itinerary
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 일정 로컬 저장소 (SharedPreferences + JSON)
 */
class ItineraryStorage private constructor(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "saved_itineraries"
        private const val KEY_ITINERARIES = "itineraries"

        @Volatile
        private var instance: ItineraryStorage? = null

        fun getInstance(context: Context): ItineraryStorage {
            return instance ?: synchronized(this) {
                instance ?: ItineraryStorage(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * 모든 저장된 일정 가져오기
     */
    fun getAllItineraries(): List<Itinerary> {
        val json = prefs.getString(KEY_ITINERARIES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Itinerary>>() {}.type
            Gson().fromJson<List<Itinerary>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 일정 저장하기 (같은 ID가 있으면 업데이트, 없으면 추가)
     */
    fun saveItinerary(itinerary: Itinerary) {
        val itineraries = getAllItineraries().toMutableList()
        val existingIndex = itineraries.indexOfFirst { it.id == itinerary.id }

        if (existingIndex != -1) {
            // 기존 일정 업데이트
            itineraries[existingIndex] = itinerary
        } else {
            // 새 일정 추가
            itineraries.add(itinerary)
        }

        val json = Gson().toJson(itineraries)
        prefs.edit().putString(KEY_ITINERARIES, json).apply()
    }

    /**
     * 일정 삭제하기
     */
    fun deleteItinerary(itineraryId: String) {
        val itineraries = getAllItineraries().toMutableList()
        itineraries.removeAll { it.id == itineraryId }
        val json = Gson().toJson(itineraries)
        prefs.edit().putString(KEY_ITINERARIES, json).apply()
    }

    /**
     * 특정 일정 가져오기
     */
    fun getItinerary(itineraryId: String): Itinerary? {
        return getAllItineraries().find { it.id == itineraryId }
    }
}
