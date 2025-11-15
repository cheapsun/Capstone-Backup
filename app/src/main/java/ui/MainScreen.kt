package com.example.project_2.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.project_2.domain.model.*
import com.example.project_2.ui.region.RegionSelectBottomSheet

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    vm: MainViewModel,
    onGoResult: (RecommendationResult) -> Unit
) {
    val ui by vm.ui.collectAsState()
    val focusManager = LocalFocusManager.current
    val onGoResultState by rememberUpdatedState(onGoResult)

    // 이미 네비게이션한 결과 ID를 추적 (중복 네비게이션 방지)
    var navigatedResultId by remember { mutableStateOf<String?>(null) }

    // ✅ ViewModel에서 lastResult가 갱신되면 지도 화면으로 네비게이션
    LaunchedEffect(ui.lastResult) {
        ui.lastResult?.let { result ->
            // 같은 결과로 중복 네비게이션 방지
            val resultId = result.places.firstOrNull()?.id ?: result.hashCode().toString()
            if (navigatedResultId != resultId) {
                navigatedResultId = resultId
                onGoResultState(result)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("여행 가이드", fontWeight = FontWeight.Bold) })
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Button(
                        // 🔧 핵심 수정: GPT 경로로 변경
                        onClick = { vm.onSearchClicked() },
                        // VM에서 region 비어도 "서울"로 기본 처리하므로 굳이 막지 않아도 됨
                        enabled = !ui.loading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (ui.loading) "생성 중…" else "맞춤 루트 생성하기 (AI)")
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 검색 박스
            item {
                SearchCard(
                    value = ui.filter.region,
                    onValueChange = vm::setRegion,
                    onDone = { focusManager.clearFocus() },
                    onMapIconClick = vm::showRegionSelectSheet
                )
            }

            // 카테고리
            item {
                SectionCard(title = "어떤 여행을 원하나요?") {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CategoryChip("🍜 맛집", Category.FOOD, ui.filter.categories, vm::toggleCategory)
                        CategoryChip("☕ 카페", Category.CAFE, ui.filter.categories, vm::toggleCategory)
                        CategoryChip("📸 사진", Category.PHOTO, ui.filter.categories, vm::toggleCategory)
                        CategoryChip("🏛 문화", Category.CULTURE, ui.filter.categories, vm::toggleCategory)
                        CategoryChip("🛍 쇼핑", Category.SHOPPING, ui.filter.categories, vm::toggleCategory)
                        CategoryChip("🌳 힐링", Category.HEALING, ui.filter.categories, vm::toggleCategory)
                        CategoryChip("🧪 체험", Category.EXPERIENCE, ui.filter.categories, vm::toggleCategory)
                        CategoryChip("🌃 숙소", Category.STAY, ui.filter.categories, vm::toggleCategory)
                    }
                    if (ui.filter.categories.isEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        AssistiveHint(text = "선택하지 않으면 기본 카테고리(예: 맛집)로 보정해 드려요.")
                    }
                }
            }

            // 기간
            item {
                SectionCard(title = "여행 기간") {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                DurationChip("반나절", TripDuration.HALF_DAY, ui.filter.duration, vm::setDuration)
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                DurationChip("하루", TripDuration.DAY, ui.filter.duration, vm::setDuration)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                DurationChip("1박2일", TripDuration.ONE_NIGHT, ui.filter.duration, vm::setDuration)
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                DurationChip("2박3일", TripDuration.TWO_NIGHTS, ui.filter.duration, vm::setDuration)
                            }
                        }
                    }
                }
            }

            // 예산
            item {
                SectionCard(title = "1인당 예산") {
                    Text("₩${ui.filter.budgetPerPerson}", style = MaterialTheme.typography.titleMedium)
                    Slider(
                        value = ui.filter.budgetPerPerson.toFloat(),
                        onValueChange = { vm.setBudget(it.toInt()) },
                        valueRange = 10000f..100000f
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("₩10,000", style = MaterialTheme.typography.labelSmall)
                        Text("₩100,000+", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // 동행
            item {
                SectionCard(title = "누구와 함께?") {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                CompanionChip("👤 혼자", Companion.SOLO, ui.filter.companion, vm::setCompanion)
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                CompanionChip("👥 친구", Companion.FRIENDS, ui.filter.companion, vm::setCompanion)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                CompanionChip("💑 연인", Companion.COUPLE, ui.filter.companion, vm::setCompanion)
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                CompanionChip("👪 가족", Companion.FAMILY, ui.filter.companion, vm::setCompanion)
                            }
                        }
                    }
                }
            }

            // 오류 메시지
            if (ui.error != null) {
                item { Text("오류: ${ui.error}", color = MaterialTheme.colorScheme.error) }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }

        // 🔹 지역 선택 BottomSheet
        if (ui.showRegionSelectSheet) {
            RegionSelectBottomSheet(
                regionQuery = ui.filter.region.ifBlank { "서울" },
                onDismiss = vm::hideRegionSelectSheet,
                onRegionSelected = { regionName, polygons ->
                    // ✅ 검색 칸에 지역명 + 폴리곤 정보 입력
                    vm.setRegionWithPolygon(regionName, polygons)
                }
            )
        }
    }
}

/* ---------------------- UI 조각들 ---------------------- */

@Composable
private fun SearchCard(
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    onMapIconClick: () -> Unit = {}
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 검색창
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text("도시 또는 지역 검색…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = onMapIconClick) {
                        Icon(Icons.Default.Place, contentDescription = "지도에서 선택", tint = MaterialTheme.colorScheme.primary)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone() })
            )

            // 지도 사용법 안내
            AssistiveHint(text = "📍 오른쪽 지도 아이콘을 눌러 지도에서 원하는 지역을 선택하세요")
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun AssistiveHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CategoryChip(
    label: String,
    cat: Category,
    selectedSet: Set<Category>,
    toggle: (Category) -> Unit
) {
    FilterChip(
        selected = selectedSet.contains(cat),
        onClick = { toggle(cat) },
        label = { Text(label) }
    )
}

@Composable
private fun DurationChip(
    label: String,
    value: TripDuration,
    selected: TripDuration,
    onSelect: (TripDuration) -> Unit
) {
    FilterChip(
        selected = selected == value,
        onClick = { onSelect(value) },
        label = { Text(label) }
    )
}

@Composable
private fun CompanionChip(
    label: String,
    value: Companion,
    selected: Companion,
    onSelect: (Companion) -> Unit
) {
    FilterChip(
        selected = selected == value,
        onClick = { onSelect(value) },
        label = { Text(label) }
    )

}
