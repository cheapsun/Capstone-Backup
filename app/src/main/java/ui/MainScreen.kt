package com.example.project_2.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
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
import com.example.project_2.domain.model.*

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
                        onClick = { vm.onSearchClicked() },
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
            // 🔹 지역 검색 박스
            item {
                SearchCard(
                    value = ui.filter.region,
                    onValueChange = vm::setRegion,
                    onDone = { focusManager.clearFocus() }
                )
            }

            // 🔹 추천 동네 섹션 (도시 선택 시 자동 노출)
            item {
                NeighborhoodSection(
                    regionText = ui.filter.region,
                    selectedNeighborhoods = ui.filter.subRegions,
                    onToggleNeighborhood = vm::toggleSubRegion
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

            // 여행 기간
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

            /*
            // 예산
            item {
                SectionCard(title = "1인당 예산") {
                    Text(
                        "₩${ui.filter.budgetPerPerson}",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Slider(
                        value = ui.filter.budgetPerPerson.toFloat(),
                        onValueChange = { vm.setBudget(it.toInt()) },
                        valueRange = 10000f..100000f
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("₩10,000", style = MaterialTheme.typography.labelSmall)
                        Text("₩100,000+", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

             */

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

            // 인원수
            item {
                SectionCard(title = "인원수") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { vm.decreasePeople() },
                            enabled = ui.filter.numberOfPeople > 1
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "감소")
                        }
                        Text(
                            text = "${ui.filter.numberOfPeople}명",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                        IconButton(
                            onClick = { vm.increasePeople() },
                            enabled = ui.filter.numberOfPeople < 10
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "증가")
                        }
                    }
                }
            }

            // 필수 방문 장소
            item {
                SectionCard(title = "필수 방문 장소") {
                    OutlinedTextField(
                        value = ui.filter.mandatoryPlace,
                        onValueChange = vm::setMandatoryPlace,
                        placeholder = {
                            Text("예: 해운대, 광안리")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
                    )
                    Spacer(Modifier.height(4.dp))
                    AssistiveHint("꼭 가고 싶은 장소가 있다면 입력해주세요. 추천 결과에 자동으로 포함됩니다.")
                }
            }

            // ✅ 세부 계획 / 메모

/*
item {
    SectionCard(title = "세부 계획 / 메모") {
        OutlinedTextField(
            value = ui.filter.extraNote,
            onValueChange = vm::setExtraNote,
            placeholder = {
                Text("원하시는 부분을 세부적으로 말씀해주세요.")
            },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4
        )
        Spacer(Modifier.height(4.dp))
        AssistiveHint("꼭 가보고 싶은 가게/장소나 일정이 있다면 적어주세요. GPT가 추천에 반영해요.")
    }
}

 */

// 오류 메시지
if (ui.error != null) {
    item {
        Text(
            "오류: ${ui.error}",
            color = MaterialTheme.colorScheme.error
        )
    }
}

item { Spacer(Modifier.height(8.dp)) }
}
}
}

/* ---------------------- 추천 동네 섹션 ---------------------- */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NeighborhoodSection(
regionText: String,
selectedNeighborhoods: List<String>,
onToggleNeighborhood: (String) -> Unit
) {
// 검색창 텍스트에서 도시 추론
val cityKey: String? = remember(regionText) {
when {
regionText.contains("서울") -> "서울"
regionText.contains("부산") -> "부산"
regionText.contains("제주") -> "제주"
regionText.contains("강릉") -> "강릉"
regionText.contains("광주") -> "광주"
regionText.contains("대전") -> "대전"
regionText.contains("대구") -> "대구"
regionText.contains("인천") -> "인천"
regionText.contains("울산") -> "울산"
regionText.contains("수원") -> "수원"
regionText.contains("창원") -> "창원"
regionText.contains("전주") -> "전주"
regionText.contains("포항") -> "포항"
regionText.contains("여수") -> "여수"
regionText.contains("통영") -> "통영"
regionText.contains("속초") -> "속초"
regionText.contains("춘천") -> "춘천"
regionText.contains("경주") -> "경주"
else -> null
}
}

if (cityKey == null) return

val presets = NeighborhoodConfig.byCity[cityKey].orEmpty()
if (presets.isEmpty()) return

val focusManager = LocalFocusManager.current
var customSub by remember { mutableStateOf("") }

Column(
modifier = Modifier
.fillMaxWidth()
.padding(top = 4.dp),
verticalArrangement = Arrangement.spacedBy(8.dp)
) {
Text(
text = "어느 동네를 중심으로 여행할까요?",
style = MaterialTheme.typography.titleMedium,
fontWeight = FontWeight.SemiBold
)

FlowRow(
horizontalArrangement = Arrangement.spacedBy(8.dp),
verticalArrangement = Arrangement.spacedBy(8.dp)
) {
presets.forEach { n ->
    val selected = selectedNeighborhoods.contains(n.name)
    FilterChip(
        selected = selected,
        onClick = { onToggleNeighborhood(n.name) },
        label = { Text(n.name) }
    )
}
}

// 🔽 직접 입력 영역
Spacer(modifier = Modifier.height(4.dp))

Row(
modifier = Modifier.fillMaxWidth(),
verticalAlignment = Alignment.CenterVertically
) {
OutlinedTextField(
    value = customSub,
    onValueChange = { customSub = it },
    modifier = Modifier.weight(1f),
    placeholder = { Text("직접 입력 (예: 노형동, 연산동)") },
    singleLine = true,
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    keyboardActions = KeyboardActions(
        onDone = {
            val trimmed = customSub.trim()
            if (trimmed.isNotEmpty()) {
                onToggleNeighborhood(trimmed)
                customSub = ""
                focusManager.clearFocus()
            }
        }
    )
)

Spacer(modifier = Modifier.width(8.dp))

TextButton(
    onClick = {
        val trimmed = customSub.trim()
        if (trimmed.isNotEmpty()) {
            onToggleNeighborhood(trimmed)
            customSub = ""
            focusManager.clearFocus()
        }
    }
) {
    Text("추가")
}
}

if (selectedNeighborhoods.isNotEmpty()) {
Text(
    text = "선택한 동네: ${selectedNeighborhoods.joinToString()}",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant
)
} else {
Text(
    text = "칩을 선택하거나, 직접 입력해서 동네를 추가해 주세요.",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant
)
}
}
}

/* ---------------------- UI 조각들 ---------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchCard(
value: String,
onValueChange: (String) -> Unit,
onDone: () -> Unit
) {
var showMoreRegions by remember { mutableStateOf(false) }

val mainRegions = listOf("서울", "부산", "제주", "광주")

val moreRegions = listOf(
"강릉", "대전", "대구", "인천", "울산",
"수원", "창원", "전주", "포항", "여수",
"통영", "속초", "춘천", "경주"
)

Surface(
modifier = Modifier.fillMaxWidth(),
shape = RoundedCornerShape(16.dp),
tonalElevation = 1.dp
) {
Column(
Modifier.padding(16.dp),
verticalArrangement = Arrangement.spacedBy(8.dp)
) {
OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    placeholder = { Text("도시 또는 지역 검색…") },
    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
    modifier = Modifier.fillMaxWidth(),
    singleLine = true,
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    keyboardActions = KeyboardActions(onDone = { onDone() })
)

Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp)
) {
    mainRegions.forEach { city ->
        QuickRegionChip(city) {
            onValueChange(city)
        }
    }

    QuickRegionChip("...") {
        showMoreRegions = true
    }
}
}
}

if (showMoreRegions) {
val allRegions = moreRegions.sorted()

ModalBottomSheet(
onDismissRequest = { showMoreRegions = false }
) {
Text(
    text = "다른 지역 선택",
    style = MaterialTheme.typography.titleMedium,
    modifier = Modifier
        .padding(horizontal = 16.dp, vertical = 8.dp)
)

Column(
    modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = 24.dp)
        .verticalScroll(rememberScrollState())
) {
    allRegions.forEach { city ->
        ListItem(
            headlineContent = { Text(city) },
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onValueChange(city)
                    showMoreRegions = false
                }
        )
        Divider()
    }
}
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
Column(
Modifier.padding(16.dp),
verticalArrangement = Arrangement.spacedBy(12.dp)
) {
Text(
    title,
    style = MaterialTheme.typography.titleMedium,
    fontWeight = FontWeight.SemiBold
)
content()
}
}
}

@Composable
private fun AssistiveHint(text: String) {
Text(
text,
style = MaterialTheme.typography.bodySmall,
color = MaterialTheme.colorScheme.onSurfaceVariant
)
}

@Composable
private fun QuickRegionChip(label: String, onClick: () -> Unit) {
AssistChip(
onClick = onClick,
label = {
Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
)
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
