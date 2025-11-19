package com.example.project_2.domain.model

/**
 * ============================
 *  공통 스코어 계산
 * ============================
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
 * 문자열 "광주 동명동, 광주 상무지구" → ["동명동", "상무지구"]
 */
fun extractNeighborhoodKeywords(regionText: String): List<String> {
    return regionText
        .split(',', '·', '/', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { token ->
            // token: "광주 동명동"
            val parts = token.split(" ")
            if (parts.size >= 2) parts[1].trim() else null
        }
        .distinct()
}

/**
 * ============================
 *  1) 카테고리 리밸런스
 * ============================
 */
fun rebalanceByCategory(
    candidates: List<Place>,
    selectedCats: Set<Category>,
    minPerCat: Int = 4,
    perCatTop: Int = 1,
    totalCap: Int? = null
): Pair<List<Place>, List<Place>> {

    val filtered = candidates
        .filter { it.category in selectedCats }
        .distinctBy { it.id }

    val grouped = filtered
        .groupBy { it.category }
        .mapValues { (_, list) ->
            list.sortedByDescending { it.finalScore() }
        }
        .toMutableMap()

    val used = LinkedHashSet<String>()
    val topPicks = mutableListOf<Place>()

    // ------------------------------
    // Top pick 확보
    // ------------------------------
    for (cat in selectedCats) {
        val list = grouped[cat].orEmpty()
        val takeN = minOf(perCatTop, list.size)
        repeat(takeN) { i ->
            val p = list[i]
            if (used.add(p.id)) topPicks += p
        }
        if (takeN > 0) grouped[cat] = list.drop(takeN)
    }

    // ------------------------------
    // 최소 minPerCat 개수 확보 라운드로빈
    // ------------------------------
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

    // ------------------------------
    // 잔여 채우기
    // ------------------------------
    val remaining = buildList {
        for ((cat, list) in grouped) {
            addAll(list.map { cat to it })
        }
    }.sortedByDescending { it.second.finalScore() }

    var lastCat: Category? = body.lastOrNull()?.category ?: topPicks.lastOrNull()?.category

    for ((cat, p) in remaining) {
        if (totalCap != null && (topPicks.size + body.size) >= totalCap) break
        if (used.contains(p.id)) continue
        if (lastCat == cat) continue

        used.add(p.id)
        body += p
        lastCat = cat
    }

    for ((_, p) in remaining) {
        if (totalCap != null && (topPicks.size + body.size) >= totalCap) break
        if (used.add(p.id)) body += p
    }

    return topPicks to (topPicks + body)
}

/**
 * ============================
 *  2) 동네 리밸런스
 * ============================
 */
fun rebalanceByNeighborhood(
    ordered: List<Place>,
    regionText: String,
    minPerNeighborhood: Int,
    totalCap: Int
): List<Place> {
    if (ordered.isEmpty()) return emptyList()

    val neighborhoods = extractNeighborhoodKeywords(regionText)
    if (neighborhoods.isEmpty()) return ordered

    val byArea = mutableMapOf<String, MutableList<Place>>()
    val others = mutableListOf<Place>()

    for (p in ordered) {
        val addr = p.address ?: ""
        val matched = neighborhoods.firstOrNull { kw ->
            addr.contains(kw)
        }
        if (matched != null) {
            byArea.getOrPut(matched) { mutableListOf() }.add(p)
        } else {
            others.add(p)
        }
    }

    val picked = LinkedHashSet<Place>()

    // ------------------------------
    // 동네별 최소 개수 확보
    // ------------------------------
    for (kw in neighborhoods) {
        val bucket = byArea[kw].orEmpty()
        bucket.take(minPerNeighborhood).forEach {
            if (picked.size < totalCap) picked.add(it)
        }
    }

    // ------------------------------
    // 남은 자리 기존 순으로 채우기
    // ------------------------------
    for (p in ordered) {
        if (picked.size >= totalCap) break
        picked.add(p)
    }

    return picked.toList()
}
