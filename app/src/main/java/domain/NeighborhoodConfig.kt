package com.example.project_2.domain.model

/**
 * 도시 안의 "추천 동네" 정보를 담는 프리셋
 *
 * - city: 도시 이름 (서울, 부산, 광주광역시 등)
 * - name: Chip에 보여줄 동네 이름 (홍대/연남동, 동명동 등)
 * - queryKeyword: Kakao Local 검색에 사용할 키워드 (예: "서울 홍대", "광주 동명동")
 */
data class NeighborhoodPreset(
    val id: String,
    val city: String,
    val name: String,
    val queryKeyword: String
)

object NeighborhoodConfig {

    // 편하게 프리셋 만드는 헬퍼
    private fun preset(
        city: String,
        name: String,
        queryKeyword: String = "$city $name"
    ): NeighborhoodPreset = NeighborhoodPreset(
        id = "${city}_$name",   // 단순 문자열 ID (중복만 안 나면 됨)
        city = city,
        name = name,
        queryKeyword = queryKeyword
    )

    // ───────────────── 서울 ─────────────────
    val seoul = listOf(
        preset("서울", "홍대/연남동", "서울 홍대"),
        preset("서울", "강남역", "서울 강남역"),
        preset("서울", "성수동", "서울 성수동"),
        preset("서울", "익선동/종로", "서울 익선동"),
        preset("서울", "이태원", "서울 이태원")
    )

    // ───────────────── 부산 ─────────────────
    val busan = listOf(
        preset("부산", "해운대", "부산 해운대"),
        preset("부산", "서면", "부산 서면"),
        preset("부산", "남포동/BIFF", "부산 남포동"),
        preset("부산", "광안리", "부산 광안리"),
        preset("부산", "송정해수욕장", "부산 송정해수욕장")
    )

    // ───────────────── 제주 ─────────────────
    val jeju = listOf(
        preset("제주", "애월", "제주 애월"),
        preset("제주", "협재/한림", "제주 협재"),
        preset("제주", "월정리", "제주 월정리"),
        preset("제주", "성산/섭지코지", "제주 성산일출봉"),
        preset("제주", "중문", "제주 중문 관광단지")
    )

    // ───────────────── 강릉 ─────────────────
    val gangneung = listOf(
        preset("강릉", "경포대", "강릉 경포대"),
        preset("강릉", "안목해변", "강릉 안목해변"),
        preset("강릉", "중앙시장/원도심", "강릉 중앙시장"),
        preset("강릉", "주문진", "강릉 주문진"),
        preset("강릉", "교동택지", "강릉 교동")
    )

    // ───────────────── 광주 ─────────────────
    private val gwangjuList = listOf(
        preset("광주", "동명동", "광주 동명동"),
        preset("광주", "양림동", "광주 양림동"),
        preset("광주", "상무동", "광주 상무동"),
        preset("광주", "충장로", "광주 충장로"),
        preset("광주", "수완동", "광주 수완동")
    )

    // ───────────────── 대전 ─────────────────
    val daejeon = listOf(
        preset("대전", "은행동/중앙로", "대전 은행동"),
        preset("대전", "둔산동", "대전 둔산동"),
        preset("대전", "유성/궁동", "대전 궁동"),
        preset("대전", "탄방동", "대전 탄방동"),
        preset("대전", "관저동", "대전 관저동")
    )

    // ───────────────── 대구 ─────────────────
    val daegu = listOf(
        preset("대구", "동성로", "대구 동성로"),
        preset("대구", "수성못", "대구 수성못"),
        preset("대구", "앞산/안지랑", "대구 안지랑"),
        preset("대구", "동대구역", "대구 동대구역"),
        preset("대구", "칠성시장", "대구 칠성시장")
    )

    // ───────────────── 인천 ─────────────────
    val incheon = listOf(
        preset("인천", "송도", "인천 송도"),
        preset("인천", "부평역", "인천 부평역"),
        preset("인천", "청라", "인천 청라"),
        preset("인천", "월미도/연안부두", "인천 월미도"),
        preset("인천", "구월동", "인천 구월동")
    )

    // ───────────────── 울산 ─────────────────
    val ulsan = listOf(
        preset("울산", "삼산동", "울산 삼산동"),
        preset("울산", "성남동/중구", "울산 성남동"),
        preset("울산", "태화강/태화동", "울산 태화강국가정원"),
        preset("울산", "무거동/울산대", "울산 무거동"),
        preset("울산", "진하해수욕장", "울산 진하해수욕장")
    )

    // ───────────────── 수원 ─────────────────
    val suwon = listOf(
        preset("수원", "인계동", "수원 인계동"),
        preset("수원", "행궁동", "수원 행궁동"),
        preset("수원", "광교", "수원 광교"),
        preset("수원", "영통", "수원 영통"),
        preset("수원", "매탄동", "수원 매탄동")
    )

    // ───────────────── 창원 ─────────────────
    val changwon = listOf(
        preset("창원", "상남동", "창원 상남동"),
        preset("창원", "용호동/시티세븐", "창원 용호동"),
        preset("창원", "창원중앙역/중앙동", "창원 중앙동"),
        preset("창원", "마산 창동/오동동", "마산 창동"),
        preset("창원", "진해 경화동", "진해 경화동")
    )

    // ───────────────── 전주 ─────────────────
    val jeonju = listOf(
        preset("전주", "전주한옥마을", "전주 한옥마을"),
        preset("전주", "객사/고사동", "전주 객사"),
        preset("전주", "서신동", "전주 서신동"),
        preset("전주", "송천동/혁신도시", "전주 송천동"),
        preset("전주", "효자동", "전주 효자동")
    )

    // ───────────────── 포항 ─────────────────
    val pohang = listOf(
        preset("포항", "영일대해수욕장", "포항 영일대해수욕장"),
        preset("포항", "죽도시장", "포항 죽도시장"),
        preset("포항", "양덕동", "포항 양덕동"),
        preset("포항", "구룡포", "포항 구룡포"),
        preset("포항", "오천읍", "포항 오천읍")
    )

    // ───────────────── 여수 ─────────────────
    val yeosu = listOf(
        preset("여수", "여수EXPO/중앙동", "여수 엑스포역"),
        preset("여수", "학동/여서동", "여수 학동"),
        preset("여수", "웅천지구", "여수 웅천동"),
        preset("여수", "돌산대교/돌산읍", "여수 돌산대교"),
        preset("여수", "소호동 요트장", "여수 소호동 요트장")
    )

    // ───────────────── 통영 ─────────────────
    val tongyeong = listOf(
        preset("통영", "중앙동 항구", "통영 중앙동"),
        preset("통영", "동피랑마을", "통영 동피랑"),
        preset("통영", "죽림 신도시", "통영 죽림"),
        preset("통영", "미수동 해변", "통영 미수동"),
        preset("통영", "도남동/케이블카", "통영 케이블카")
    )

    // ───────────────── 속초 ─────────────────
    val sokcho = listOf(
        preset("속초", "속초해수욕장", "속초 해수욕장"),
        preset("속초", "중앙시장/교동", "속초 중앙시장"),
        preset("속초", "청초호", "속초 청초호"),
        preset("속초", "영랑호", "속초 영랑호"),
        preset("속초", "설악동", "속초 설악동")
    )

    // ───────────────── 춘천 ─────────────────
    val chuncheon = listOf(
        preset("춘천", "명동/중앙시장", "춘천 명동"),
        preset("춘천", "소양강스카이워크", "춘천 소양강스카이워크"),
        preset("춘천", "강촌", "춘천 강촌"),
        preset("춘천", "남이섬", "춘천 남이섬"),
        preset("춘천", "구봉산/애니메이션박물관", "춘천 애니메이션박물관")
    )

    // ───────────────── 경주 ─────────────────
    val gyeongju = listOf(
        preset("경주", "황리단길", "경주 황리단길"),
        preset("경주", "보문단지", "경주 보문관광단지"),
        preset("경주", "불국사", "경주 불국사"),
        preset("경주", "양남 주상절리", "경주 양남 주상절리"),
        preset("경주", "감포/문무대왕암", "경주 문무대왕암")
    )

    // ───────────────── city → presets 매핑 ─────────────────
    val byCity: Map<String, List<NeighborhoodPreset>> = mapOf(
        "서울" to seoul,
        "부산" to busan,
        "제주" to jeju,
        "강릉" to gangneung,

        // 광주는 "광주" / "광주광역시" 둘 다 지원
        "광주" to gwangjuList,
        "광주광역시" to gwangjuList,

        "대전" to daejeon,
        "대구" to daegu,
        "인천" to incheon,
        "울산" to ulsan,
        "수원" to suwon,
        "창원" to changwon,
        "전주" to jeonju,
        "포항" to pohang,
        "여수" to yeosu,
        "통영" to tongyeong,
        "속초" to sokcho,
        "춘천" to chuncheon,
        "경주" to gyeongju
    )
}
