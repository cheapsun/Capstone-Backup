# 프로젝트 종합 분석 리포트

## 1. 프로젝트 개요

**앱 이름**: 여행 가이드 (project_2)
**플랫폼**: Android (Kotlin + Jetpack Compose)
**빌드 시스템**: Gradle (Kotlin DSL)
**최소 SDK**: 24 / 타겟 SDK: 36

## 2. 핵심 기능

### 2.1 AI 기반 여행 추천 시스템
- **GPT-4o-mini** 활용하여 사용자 선호도 기반 장소 재랭킹
- 카테고리, 예산, 동행자, 기간 등 필터링
- 프랜차이즈 패널티 적용 (스타벅스, 이디야 등 체인점 순위 하향)

### 2.2 외부 API 통합
| 서비스 | 용도 | 파일 |
|--------|------|------|
| **OpenAI** | GPT 재랭킹 | `OpenAiService.kt`, `GptRerankUseCase.kt` |
| **Kakao Local** | 장소 검색, 지오코딩 | `KakaoLocalService.kt` |
| **Kakao Map SDK** | 지도 표시 | `ResultScreen.kt`, `RouteMapScreen.kt` |
| **OpenWeather** | 날씨 정보 | `WeatherService.kt` |
| **TMAP** | 보행자 경로 안내 | `TmapPedestrianService.kt` |

### 2.3 주요 화면 구성
```
검색 (MainScreen) → 지도/결과 (ResultScreen) → 루트 저장
       ↓
저장된 루트 (RouteListScreen) → 루트 상세 (RouteDetailScreen) → 지도 보기 (RouteMapScreen)
```

## 3. 아키텍처 분석

### 3.1 패키지 구조
```
├── com.example.project_2
│   ├── MainActivity.kt          # 앱 진입점, 네비게이션 설정
│   └── ui/theme/                 # 테마 설정
├── data/
│   ├── KakaoLocalService.kt      # Kakao API 연동
│   ├── WeatherService.kt         # 날씨 API
│   ├── RouteStorage.kt           # SharedPreferences 저장소
│   ├── openai/OpenAiService.kt   # OpenAI API
│   └── route/TmapPedestrianService.kt  # TMAP 경로
├── domain/
│   ├── model/                    # 데이터 모델
│   ├── repo/                     # Repository 패턴
│   └── GptRerankUseCase.kt       # 비즈니스 로직
└── ui/
    ├── main/                     # 메인 화면
    ├── result/                   # 결과 화면
    └── route/                    # 루트 관련 화면
```

### 3.2 데이터 흐름
```
사용자 입력 → FilterState → Repository → API 호출
    → GPT 재랭킹 → rebalanceByCategory() → RecommendationResult → UI
```

## 4. 주요 컴포넌트 분석

### 4.1 GptRerankUseCase (domain/GptRerankUseCase.kt)
- **역할**: Kakao 검색 결과를 GPT로 재정렬
- **특징**:
  - 최대 30개 후보 처리
  - 순서 변경 실패시 1회 재시도
  - JSON 응답 파싱 및 sanitize 처리
  - fallback score 부여 (GPT가 점수 안 줄 경우)

### 4.2 rebalanceByCategory (domain/RebalanceUtil.kt)
- **역할**: 카테고리별 최소 개수 보장 + 라운드로빈 분배
- **특징**:
  - 카테고리당 최소 4개 보장
  - Top 1개 상단 고정
  - 동일 카테고리 연속 배치 방지

### 4.3 ResultScreen (ui/result/ResultScreen.kt)
- **1756줄**의 대형 Composable
- 주요 기능:
  - Kakao Map 표시 및 마커 관리
  - 장소 선택/드래그 정렬
  - T-Map 경로 생성
  - 구간별 색상 코딩
  - 루트 저장

## 5. 잠재적 문제점 및 개선 사항

### 5.1 코드 품질

**문제 1: 대형 Composable (ResultScreen.kt - 1756줄)**
- UI 로직, 비즈니스 로직, 상태 관리가 혼재
- 테스트 및 유지보수 어려움

**개선 방안**:
```kotlin
// ViewModel 분리
class ResultViewModel : ViewModel() {
    val selectedPlaces = mutableStateListOf<Place>()
    val routeSegments = mutableStateOf<List<RouteSegment>>(emptyList())
    // ...
}
```

**문제 2: 하드코딩된 값들**
```kotlin
// GptRerankUseCase.kt:71
val chainPenaltyRule = """
- 프랜차이즈(체인점) 패널티: ...
  (스타벅스, 메가커피, 이디야, 투썸플레이스, ...)
```

**개선 방안**: 설정 파일 또는 서버에서 관리

**문제 3: 에러 처리 불완전**
```kotlin
// OpenAiService.kt:92
} catch (io: IOException) {
    Log.e(TAG, "IO(/chat): ${io.message}")
}
// 사용자에게 에러 피드백 없음
```

### 5.2 보안 관련

**문제 1: API 키 노출 위험**
- BuildConfig를 통해 키 관리 중 (좋은 방식)
- 단, local.properties가 git에 포함되지 않도록 확인 필요

**문제 2: 네트워크 보안**
```kotlin
// 현재 HTTPS 사용 중 (양호)
private const val BASE_URL = "https://dapi.kakao.com/"
```

### 5.3 성능 관련

**문제 1: 중첩 LazyColumn**
```kotlin
// ResultScreen.kt:832
LazyColumn(
    // 부모 LazyColumn 내부에서
    state = reorderableState.listState,
    // 또 다른 LazyColumn
)
```
- 스크롤 충돌 및 성능 저하 가능

**문제 2: 잦은 recomposition**
```kotlin
// LaunchedEffect 의존성이 너무 많음
LaunchedEffect(kakaoMap, selectedPlaces.toList(), rec.places,
               showRealRoute, routeSegments, selectedSegmentIndex,
               isPlaceListExpanded) {
```

### 5.4 UX 관련

**문제 1: 로딩 상태 관리**
- 일부 API 호출에서 로딩 표시 없음

**문제 2: 오프라인 지원 없음**
- 네트워크 연결 없을 때 앱 사용 불가

## 6. 강점

1. **현대적인 기술 스택**: Jetpack Compose, Kotlin Coroutines, Flow
2. **잘 구조화된 API 서비스**: Retrofit + OkHttp 조합
3. **AI 통합**: GPT를 활용한 개인화 추천
4. **재사용 가능한 컴포넌트**: SmallBadge, PlaceRow 등
5. **세심한 UX**: 드래그 앤 드롭, 구간별 색상 코딩, 타임라인 UI
6. **robust한 에러 처리**: fallback segment, retry 로직

## 7. 카테고리 매핑

```kotlin
// KakaoLocalService.kt
Category.FOOD -> "FD6"      // 음식점
Category.CAFE -> "CE7"      // 카페
Category.CULTURE -> "CT1"   // 문화시설
Category.PHOTO -> "AT4"     // 관광명소
Category.SHOPPING -> "MT1", "CS2"  // 마트, 편의점
Category.NIGHT -> "AD5"     // 숙박/야간
Category.STAY -> "AD5"      // 숙박
```

## 8. 데이터 모델

```kotlin
// 핵심 모델들
data class Place(id, name, category, lat, lng, distanceMeters, rating, address, score)
data class FilterState(region, categories, duration, budgetPerPerson, companion)
data class RecommendationResult(places, weather, gptReasons, aiTopIds, topPicks)
data class RouteSegment(from, to, pathCoordinates, distanceMeters, durationSeconds, mode)
data class SavedRoute(id, name, places, routeSegments, createdAt)
```

## 9. 결론

이 프로젝트는 **AI 기반 여행 추천 앱**으로서 다양한 외부 API를 효과적으로 통합하고 있습니다. Jetpack Compose를 활용한 현대적인 UI와 GPT를 활용한 개인화 추천이 특징입니다.

**우선 개선 권장 사항**:
1. ResultScreen 분리 (ViewModel + 작은 Composable들)
2. 에러 처리 및 사용자 피드백 강화
3. 테스트 코드 추가
4. 성능 최적화 (LazyColumn 중첩 해결)

---

*리뷰 일자: 2025-11-19*
