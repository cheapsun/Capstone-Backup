package com.example.project_2

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.project_2.data.KakaoLocalService
import com.example.project_2.data.VWorldService
import com.example.project_2.data.openai.OpenAiService
import com.example.project_2.data.weather.WeatherService
import com.example.project_2.data.route.TmapPedestrianService
import com.example.project_2.domain.GptRerankUseCase
import com.example.project_2.domain.repo.RealTravelRepository
import com.example.project_2.ui.main.MainScreen
import com.example.project_2.ui.main.MainViewModel
import com.example.project_2.ui.result.ResultScreen
import com.example.project_2.ui.route.RouteDetailScreen
import com.example.project_2.ui.route.RouteListScreen
import com.example.project_2.ui.route.RouteMapScreen
import com.example.project_2.ui.theme.Project2Theme
import com.kakao.vectormap.KakaoMapSdk

// 🔹 하단 네비게이션 화면 정의
sealed class Screen(val route: String, val name: String, val icon: @Composable () -> Unit) {
    object Search : Screen("search", "검색", { Icon(Icons.Default.Home, contentDescription = null) })
    object Map : Screen("map", "지도", { Icon(Icons.Default.Place, contentDescription = null) })
    object Route : Screen("route", "루트", { Icon(Icons.Default.List, contentDescription = null) })
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ===== SDK / API 키 초기화 =====
        KakaoMapSdk.init(this, BuildConfig.KAKAO_NATIVE_APP_KEY)
        KakaoLocalService.init(BuildConfig.KAKAO_REST_API_KEY)
        VWorldService.init(BuildConfig.VWORLD_API_KEY)  // ✅ VWorld 행정구역 검색 API
        WeatherService.init(BuildConfig.OPENWEATHER_API_KEY)
        OpenAiService.init(BuildConfig.OPENAI_API_KEY)
        TmapPedestrianService.init(BuildConfig.TMAP_API_KEY)  // ✅ T-Map 보행자 경로 API

        // ===== GPT 재랭커 + Repository + ViewModel =====
        val reranker = GptRerankUseCase(openAi = OpenAiService)
        val repo = RealTravelRepository(reranker)
        val mainVm = MainViewModel(repo)

        setContent {
            Project2Theme {
                // ✅ 앱 시작 시 위치 권한 요청 (한 번만)
                RequestLocationPermissions()

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
                            MainScreen(mainVm) { rec ->
                                // MainScreen에서 이미 lastResult 업데이트됨
                                navController.navigate(Screen.Map.route) {
                                    launchSingleTop = true
                                }
                            }
                        }

                        composable(Screen.Map.route) {
                            val uiState by mainVm.ui.collectAsState()
                            val recResult = uiState.lastResult

                            recResult?.let { rec ->
                                val regionHint = uiState.filter.region.ifBlank { null }
                                ResultScreen(
                                    rec = rec,
                                    regionHint = regionHint,
                                    onNavigateToItinerary = { selectedPlaces ->
                                        mainVm.setSelectedPlacesForItinerary(selectedPlaces)
                                        navController.navigate("itinerary")
                                    }
                                )
                            } ?: run {
                                // 추천 결과가 없을 때는 검색 화면으로 유도
                                LaunchedEffect(Unit) {
                                    navController.navigate(Screen.Search.route) {
                                        popUpTo(Screen.Search.route) { inclusive = true }
                                    }
                                }
                            }
                        }

                        composable("itinerary") {
                            val uiState by mainVm.ui.collectAsState()
                            val selectedPlaces = uiState.selectedPlacesForItinerary

                            if (selectedPlaces.isNotEmpty()) {
                                com.example.project_2.ui.itinerary.ItineraryScreen(
                                    selectedPlaces = selectedPlaces,
                                    filter = uiState.filter,
                                    onBack = {
                                        navController.popBackStack()
                                    },
                                    onNavigateToMap = { itinerary ->
                                        // TODO: Day별 지도 화면 구현
                                    },
                                    onSaveItinerary = { itinerary ->
                                        // TODO: 일정 저장 구현
                                        navController.navigate(Screen.Route.route)
                                    }
                                )
                            } else {
                                // 선택된 장소가 없으면 지도 화면으로
                                LaunchedEffect(Unit) {
                                    navController.popBackStack()
                                }
                            }
                        }

                        composable(Screen.Route.route) {
                            RouteListScreen(
                                onRouteClick = { routeId ->
                                    navController.navigate("route_detail/$routeId")
                                }
                            )
                        }

                        composable("route_detail/{routeId}") { backStackEntry ->
                            val routeId = backStackEntry.arguments?.getString("routeId") ?: return@composable
                            RouteDetailScreen(
                                routeId = routeId,
                                onBackClick = {
                                    navController.popBackStack()
                                },
                                onShowOnMap = {
                                    // 🔹 RouteMapScreen으로 이동 (저장된 루트 지도 화면)
                                    navController.navigate("route_map/$routeId")
                                }
                            )
                        }

                        composable("route_map/{routeId}") { backStackEntry ->
                            val routeId = backStackEntry.arguments?.getString("routeId") ?: return@composable
                            RouteMapScreen(
                                routeId = routeId,
                                onBackClick = {
                                    navController.popBackStack()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 🔹 하단 네비게이션 바
 */
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
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
    }
}

/**
 * 위치 권한 요청 컴포저블
 * - FINE / COARSE 둘 다 요청
 * - 이미 허용되어 있으면 아무 것도 하지 않음
 */
@Composable
private fun RequestLocationPermissions() {
    val context = LocalContext.current

    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* result map 무시해도 됨. 지도에서 권한 여부만 체크해서 동작함 */ }

    val fineGranted = remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val coarseGranted = remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    // 권한 상태는 시스템 설정에서 바뀔 수 있으므로 recomposition 시마다 갱신
    LaunchedEffect(Unit) {
        fineGranted.value = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        coarseGranted.value = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    var askedOnce by remember { mutableStateOf(false) }

    LaunchedEffect(fineGranted.value, coarseGranted.value) {
        val hasAny = fineGranted.value || coarseGranted.value
        if (!hasAny && !askedOnce) {
            askedOnce = true
            launcher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }
}
