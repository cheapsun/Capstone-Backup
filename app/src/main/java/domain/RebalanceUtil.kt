package com.example.project_2.domain.model

/**
 * 최종 노출 점수:
 * - GPT score(있으면 최우선)
 * - rating(보조 가중치)
 * - distance(가까울수록 약간 가산)
 */
private fun Place.finalScore(): Double {
    val s = this.score ?: 0.0
    val ratingPart = (this.rating ?: 0.0) * 0.1
    val distancePart = if (this.distanceMeters != null) {
        1_000_000.0 / (this.distanceMeters + 50)
    } else 0.0
    return s + ratingPart + distancePart
}

/**
 * 카테고리별 최소 개수 보장 + 카테고리별 Top1 상단 고정 + 라운드로빈 분배
 *
 * @param candidates 카카오/GPT 통합 후보(중복 id 가능하면 알아서 distinct)
 * @param selectedCats 사용자가 선택한 카테고리들
 * @param minPerCat 카테고리 당 최소 보장 수 (기본 4)
 * @param perCatTop 상단 고정 개수(카테고리 당, 기본 1)
 * @param totalCap 최종 최대 개수(없으면 제한 없음)
 * @return Pair(상단 고정 리스트, 최종 정렬된 전체 리스트)
 */
fun rebalanceByCategory(
    candidates: List<Place>,
    selectedCats: Set<Category>,
    minPerCat: Int = 4,
    perCatTop: Int = 1,
    totalCap: Int? = null
): Pair<List<Place>, List<Place>> {

    // 0) 선택 카테고리만, id 중복 제거
    val filtered = candidates
        .filter { it.category in selectedCats }
        .distinctBy { it.id }

    // 1) 카테고리별 점수 내림차순 정렬 큐
    val grouped = filtered
        .groupBy { it.category }
        .mapValues { (_, list) -> list.sortedByDescending { it.finalScore() } }
        .toMutableMap()

    val used = LinkedHashSet<String>()
    val topPicks = mutableListOf<Place>()

    // 2) 카테고리별 Top n 상단 고정
    for (cat in selectedCats) {
        val list = grouped[cat].orEmpty()
        val takeN = minOf(perCatTop, list.size)
        repeat(takeN) { idx ->
            val p = list[idx]
            if (used.add(p.id)) topPicks += p
        }
        if (takeN > 0) grouped[cat] = list.drop(takeN)
    }

    // 3) 1차 라운드로빈: 각 카테고리 최소 minPerCat 충족
    val body = mutableListOf<Place>()
    val perCatCount = mutableMapOf<Category, Int>().withDefault { 0 }

    fun canTake(cat: Category) =
        perCatCount.getValue(cat) < minPerCat && grouped[cat].orEmpty().isNotEmpty()

    while (selectedCats.any { canTake(it) }) {
        for (cat in selectedCats) {
            if (!canTake(cat)) continue
            val q = grouped[cat]!!
            val p = q.first()
            grouped[cat] = q.drop(1)
            if (used.add(p.id)) {
                body += p
                perCatCount[cat] = perCatCount.getValue(cat) + 1
            }
        }
    }

    // 4) 2차 채우기: 남은 항목을 점수순 + 가벼운 RR로 채움
    val remaining = buildList {
        for ((cat, list) in grouped) addAll(list.map { cat to it })
    }.sortedByDescending { it.second.finalScore() }

    var lastCat: Category? = body.lastOrNull()?.category ?: topPicks.lastOrNull()?.category
    for ((cat, p) in remaining) {
        if (totalCap != null && (topPicks.size + body.size) >= totalCap) break
        if (used.contains(p.id)) continue
        if (lastCat == cat) continue   // 같은 카테고리 연속 배치 완화
        body += p
        used += p.id
        lastCat = cat
    }
    // 아직 자리가 남았으면 나머지도 채움
    for ((_, p) in remaining) {
        if (totalCap != null && (topPicks.size + body.size) >= totalCap) break
        if (used.add(p.id)) body += p
    }

    val finalList = topPicks + body
    return topPicks to finalList
}
