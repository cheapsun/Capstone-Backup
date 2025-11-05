package com.example.project_2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.project_2.data.KakaoLocalService
import com.example.project_2.data.openai.OpenAiService
import com.example.project_2.data.weather.WeatherService
import com.example.project_2.data.route.TmapPedestrianService
import com.example.project_2.domain.repo.RealTravelRepository
import com.example.project_2.ui.main.MainScreen
import com.example.project_2.ui.main.MainViewModel
import com.example.project_2.ui.result.ResultScreen
import com.example.project_2.ui.theme.Project2Theme
import com.kakao.vectormap.KakaoMapSdk

sealed class Screen(val route: String, val name: String, val icon: @Composable () -> Unit) {
    object Search : Screen("search", "검색", { Icon(Icons.Default.Home, contentDescription = null) })
    object Map : Screen("map", "지도", { Icon(Icons.Default.Place, contentDescription = null) })
    object Route : Screen("route", "루트", { Icon(Icons.Default.List, contentDescription = null) })
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // SDK & API Keys
        KakaoMapSdk.init(this, BuildConfig.KAKAO_NATIVE_APP_KEY)
        KakaoLocalService.init(BuildConfig.KAKAO_REST_API_KEY)
        TmapPedestrianService.init(BuildConfig.TMAP_API_KEY)  // 보행자 경로 API
        WeatherService.init(BuildConfig.OPENWEATHER_API_KEY)
        OpenAiService.init(BuildConfig.OPENAI_API_KEY)

        // ViewModel & Repository
        val repo = RealTravelRepository()
        val mainVm = MainViewModel(repo)

        setContent {
            Project2Theme {
                val navController = rememberNavController()
                Scaffold(
                    bottomBar = { BottomNavBar(navController) }
                ) { innerPadding ->
                    NavHost(
                        navController,
                        startDestination = Screen.Search.route,
                        Modifier.padding(innerPadding)
                    ) {
                        composable(Screen.Search.route) {
                            MainScreen(mainVm) { // onReady
                                navController.navigate(Screen.Map.route) { launchSingleTop = true }
                            }
                        }
                        composable(Screen.Map.route) {
                            val recResult by mainVm.recommendationResult.collectAsState()
                            val selectedPlaces by mainVm.selectedPlaces.collectAsState()

                            recResult?.let {
                                ResultScreen(
                                    rec = it,
                                    selectedPlaces = selectedPlaces,
                                    onPlaceSelected = { place -> mainVm.togglePlaceSelection(place) }
                                )
                            } ?: run {
                                // TODO: 추천 결과가 없을 때의 UI (예: 검색 화면으로 유도)
                            }
                        }
                        composable(Screen.Route.route) {
                            // TODO: 루트 화면 구현
                            val selectedPlaces by mainVm.selectedPlaces.collectAsState()
                            // FinalRouteScreen(selectedPlaces)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomNavBar(navController: androidx.navigation.NavController) {
    val items = listOf(Screen.Search, Screen.Map, Screen.Route)
    NavigationBar {
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = navBackStackEntry?.destination
        items.forEach { screen ->
            NavigationBarItem(
                icon = { screen.icon() },
                label = { Text(screen.name) },
                selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                onClick = {
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
    }
}