package com.example.project_2.domain

/**
 * 주요 광역 도시별 세부 관광 지역 매핑 데이터
 *
 * WIDE 검색 전략에서 사용:
 * - 사용자가 "부산" 입력 시 → 부산의 주요 지역 5곳에서 각각 검색
 * - 다중 중심점 검색으로 넓은 지역을 골고루 커버
 */
object SubRegionsData {

    /**
     * 광역 도시 → 세부 지역 매핑
     *
     * 각 광역 도시의 주요 관광/여행 지역 5곳 선정
     * 기준: 관광 인기도, 지리적 분산, 대표성
     */
    private val wideRegionMap = mapOf(
        // 부산광역시
        "부산" to listOf("해운대", "광안리", "서면", "남포동", "태종대"),
        "부산광역시" to listOf("해운대", "광안리", "서면", "남포동", "태종대"),

        // 서울특별시
        "서울" to listOf("강남", "홍대", "명동", "이태원", "잠실"),
        "서울특별시" to listOf("강남", "홍대", "명동", "이태원", "잠실"),

        // 제주특별자치도
        "제주" to listOf("제주시", "서귀포", "성산", "애월", "중문"),
        "제주도" to listOf("제주시", "서귀포", "성산", "애월", "중문"),

        // 대구광역시
        "대구" to listOf("동성로", "수성못", "안지랑", "김광석길", "서문시장"),
        "대구광역시" to listOf("동성로", "수성못", "안지랑", "김광석길", "서문시장"),

        // 광주광역시
        "광주" to listOf("양림동", "충장로", "송정", "첨단", "무등산"),
        "광주광역시" to listOf("양림동", "충장로", "송정", "첨단", "무등산"),

        // 인천광역시
        "인천" to listOf("차이나타운", "월미도", "송도", "강화", "을왕리"),
        "인천광역시" to listOf("차이나타운", "월미도", "송도", "강화", "을왕리"),

        // 대전광역시
        "대전" to listOf("유성", "둔산", "대전역", "한밭수목원", "계룡산"),
        "대전광역시" to listOf("유성", "둔산", "대전역", "한밭수목원", "계룡산"),

        // 울산광역시
        "울산" to listOf("태화강", "간절곶", "대왕암공원", "울산대공원", "장생포"),
        "울산광역시" to listOf("태화강", "간절곶", "대왕암공원", "울산대공원", "장생포"),

        // 경기도 (주요 도시)
        "경기" to listOf("수원", "용인", "성남", "고양", "부천"),
        "경기도" to listOf("수원", "용인", "성남", "고양", "부천"),
        "수원" to listOf("수원역", "행궁동", "광교", "영통", "화성"),

        // 강원도 (주요 관광지)
        "강릉" to listOf("경포대", "안목해변", "주문진", "정동진", "강릉역"),
        "속초" to listOf("속초해수욕장", "청초호", "설악산", "속초항", "영랑호"),
        "춘천" to listOf("남이섬", "소양강", "춘천역", "춘천명동", "의암호"),

        // 전라도 (주요 관광지)
        "전주" to listOf("한옥마을", "전주역", "덕진공원", "남부시장", "동문거리"),
        "여수" to listOf("여수엑스포", "오동도", "돌산", "여수항", "향일암"),
        "순천" to listOf("순천만", "순천역", "낙안읍성", "순천만정원", "드라마세트장"),

        // 경상도 (주요 관광지)
        "경주" to listOf("불국사", "첨성대", "대릉원", "동궁", "경주역"),
        "통영" to listOf("동피랑", "케이블카", "통영항", "욕지도", "통영중앙시장"),
        "거제" to listOf("외도", "바람의언덕", "학동흑진주몽돌해변", "거제도포로수용소", "구조라"),

        // 충청도 (주요 관광지)
        "천안" to listOf("독립기념관", "천안역", "천안삼거리", "병천순대", "아라리오갤러리"),
        "청주" to listOf("청주역", "상당산성", "청주고인쇄박물관", "무심천", "수암골")
    )

    /**
     * 세부 지역 키워드 목록
     *
     * 이 키워드가 검색어에 포함되면 NARROW(세부 지역) 검색으로 판단
     * 예: "부산 해운대" → "해운대" 키워드 감지 → NARROW
     */
    private val detailedRegionKeywords = setOf(
        // 부산 세부 지역
        "해운대", "광안리", "서면", "남포동", "태종대", "송정", "기장", "영도",
        "감천", "자갈치", "범일동", "중앙동", "부산역", "부산대",

        // 서울 세부 지역
        "강남", "홍대", "명동", "이태원", "잠실", "신촌", "압구정", "삼청동",
        "인사동", "북촌", "종로", "동대문", "신림", "건대", "노원", "강북",
        "마포", "여의도", "용산", "성수", "연남동", "망원동", "서촌",

        // 제주 세부 지역
        "제주시", "서귀포", "성산", "애월", "중문", "협재", "한림", "표선",
        "우도", "마라도", "함덕", "김녕",

        // 광주 세부 지역
        "양림동", "충장로", "송정", "첨단", "무등산", "국립아시아문화전당",

        // 대구 세부 지역
        "동성로", "수성못", "안지랑", "김광석길", "서문시장", "팔공산",

        // 기타 주요 세부 지역
        "경포대", "안목", "주문진", "정동진", "남이섬", "소양강",
        "한옥마을", "오동도", "엑스포", "불국사", "첨성대", "동피랑"
    )

    /**
     * 주어진 지역이 광역 도시인지 확인
     *
     * @param region 사용자 입력 지역명
     * @return 광역 도시면 세부 지역 리스트, 아니면 null
     */
    fun getSubRegions(region: String): List<String>? {
        val normalizedRegion = region.trim()
        return wideRegionMap[normalizedRegion]
    }

    /**
     * 주어진 검색어에 세부 지역 키워드가 포함되어 있는지 확인
     *
     * @param searchQuery 사용자 검색어
     * @return 세부 지역 키워드 포함 여부
     */
    fun containsDetailedRegionKeyword(searchQuery: String): Boolean {
        return detailedRegionKeywords.any { keyword ->
            searchQuery.contains(keyword, ignoreCase = true)
        }
    }

    /**
     * 검색 타입 판단 (WIDE vs NARROW)
     *
     * @param searchQuery 사용자 검색어
     * @return SearchType (WIDE 또는 NARROW)
     */
    fun determineSearchType(searchQuery: String): SearchType {
        val normalized = searchQuery.trim()

        // 1차: 세부 지역 키워드 포함 여부 확인
        if (containsDetailedRegionKeyword(normalized)) {
            return SearchType.NARROW
        }

        // 2차: 공백/구분자로 분리된 경우 (예: "부산 해운대", "서울/강남")
        val parts = normalized.split(" ", "/", ",")
        if (parts.size >= 2) {
            return SearchType.NARROW
        }

        // 3차: 광역 도시 매핑 확인
        if (getSubRegions(normalized) != null) {
            return SearchType.WIDE
        }

        // 기본값: NARROW (안전하게 좁은 범위로)
        return SearchType.NARROW
    }

    /**
     * 모든 광역 도시 목록 반환 (자동완성용)
     */
    fun getAllWideRegions(): List<String> {
        return wideRegionMap.keys.toList()
    }
}

/**
 * 검색 타입 열거형
 */
enum class SearchType {
    WIDE,    // 광역 검색 (다중 중심점)
    NARROW   // 세부 검색 (단일 중심점)
}
