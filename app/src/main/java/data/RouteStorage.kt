package com.example.project_2.data

import android.content.Context
import android.content.SharedPreferences
import com.example.project_2.domain.model.SavedRoute

/**
 * 루트 로컬 저장소 (SharedPreferences + JSON)
 */
class RouteStorage private constructor(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "saved_routes"
        private const val KEY_ROUTES = "routes"

        @Volatile
        private var instance: RouteStorage? = null

        fun getInstance(context: Context): RouteStorage {
            return instance ?: synchronized(this) {
                instance ?: RouteStorage(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * 모든 저장된 루트 가져오기
     */
    fun getAllRoutes(): List<SavedRoute> {
        val json = prefs.getString(KEY_ROUTES, null) ?: return emptyList()
        return SavedRoute.fromJson(json)
    }

    /**
     * 루트 저장하기 (같은 ID가 있으면 업데이트, 없으면 추가)
     */
    fun saveRoute(route: SavedRoute) {
        val routes = getAllRoutes().toMutableList()
        val existingIndex = routes.indexOfFirst { it.id == route.id }

        if (existingIndex != -1) {
            // 기존 루트 업데이트
            routes[existingIndex] = route
        } else {
            // 새 루트 추가
            routes.add(route)
        }

        val json = SavedRoute.toJson(routes)
        prefs.edit().putString(KEY_ROUTES, json).apply()
    }

    /**
     * 루트 삭제하기
     */
    fun deleteRoute(routeId: String) {
        val routes = getAllRoutes().toMutableList()
        routes.removeAll { it.id == routeId }
        val json = SavedRoute.toJson(routes)
        prefs.edit().putString(KEY_ROUTES, json).apply()
    }

    /**
     * 특정 루트 가져오기
     */
    fun getRoute(routeId: String): SavedRoute? {
        return getAllRoutes().find { it.id == routeId }
    }
}
