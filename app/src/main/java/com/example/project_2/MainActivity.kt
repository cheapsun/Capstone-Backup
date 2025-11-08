package com.example.project_2

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.project_2.data.KakaoLocalService
import com.example.project_2.data.openai.OpenAiService
import com.example.project_2.data.weather.WeatherService
import com.example.project_2.data.route.TmapPedestrianService
import com.example.project_2.domain.GptRerankUseCase
import com.example.project_2.domain.model.RecommendationResult
import com.example.project_2.domain.repo.RealTravelRepository
import com.example.project_2.ui.main.MainScreen
import com.example.project_2.ui.main.MainViewModel
import com.example.project_2.ui.result.ResultScreen
import com.example.project_2.ui.theme.Project2Theme
import com.kakao.vectormap.KakaoMapSdk

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ===== SDK / API 키 초기화 =====
        KakaoMapSdk.init(this, BuildConfig.KAKAO_NATIVE_APP_KEY)
        KakaoLocalService.init(BuildConfig.KAKAO_REST_API_KEY)
        WeatherService.init(BuildConfig.OPENWEATHER_API_KEY)
        OpenAiService.init(BuildConfig.OPENAI_API_KEY)
        TmapPedestrianService.init(BuildConfig.TMAP_API_KEY)  // ✅ T-Map 보행자 경로 API

        // ===== GPT 재랭커 + Repository =====
        val reranker = GptRerankUseCase(openAi = OpenAiService)
        val repo = RealTravelRepository(reranker)
        val mainVm = MainViewModel(repo)

        setContent {
            Project2Theme {
                // ✅ 앱 시작 시 위치 권한 요청 (한 번만)
                RequestLocationPermissions()

                var mode by remember { mutableStateOf("main") }
                var lastResult by remember { mutableStateOf<RecommendationResult?>(null) }
                var lastRegionHint by remember { mutableStateOf<String?>(null) }

                BackHandler(enabled = mode == "result") {
                    mode = "main"; lastResult = null; lastRegionHint = null
                }

                when (mode) {
                    "main" -> {
                        val uiState by mainVm.ui.collectAsState()
                        MainScreen(mainVm) { rec ->
                            lastResult = rec
                            lastRegionHint = uiState.filter.region.ifBlank { null }
                            mode = "result"
                        }
                    }
                    "result" -> lastResult?.let { ResultScreen(it, lastRegionHint) }
                }
            }
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
