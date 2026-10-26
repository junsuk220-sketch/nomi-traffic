package nomi.android.traffic.api.routelegs

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class RouteLegsTest {

    @Before
    fun setUp() {
        RouteLegs.resetForTest()
    }

    @After
    fun tearDown() {
        RouteLegs.resetForTest()
    }

    @Test
    fun `bucheon court route picks 661 card among three 81 routes`() {
        RouteLegs.observe(previewList(), live = false, nowMs = 1_000L)
        RouteLegs.observe(listOf("길안내를 시작합니다."), live = false, nowMs = 2_000L)
        RouteLegs.observe(liveStart("라페스타.먹자골목 승차", "1시간 27분"), live = true, nowMs = 3_000L)

        assertEquals(
            "서해 백마역 · 7호선 부천종합운동장역 · 661번 춘의역6번출구",
            RouteLegs.cardNote(),
        )
    }

    @Test
    fun `unsure list uses the live detail including an express line`() {
        RouteLegs.observe(
            listOf(
                "1시간 30분", "경로 고정",
                "도보 7분, 1500번 직행좌석 버스 37분, 도보 6분, 2호선 33분",
                "직행", "1500", "2호선", "당산역", "하차", "강남역", "바로 안내시작",
                "1시간 30분", "경로 고정",
                "도보 7분, 1500번 직행좌석 버스 40분, 도보 4분, 9호선 17분",
                "직행", "1500", "9호선", "당산역", "하차", "신논현역", "바로 안내시작",
            ),
            live = false,
            nowMs = 1_000L,
        )
        RouteLegs.observe(listOf("길안내를 시작합니다."), live = false, nowMs = 2_000L)
        RouteLegs.observe(
            listOf(
                "일산동부경찰서(중) 승차 중앙", "1500", "2분",
                "9호선(급) 당산역 승차", "여의도역 방면", "안내 중",
            ),
            live = true,
            nowMs = 3_000L,
        )
        RouteLegs.observe(
            listOf(
                "신논현역.(구)교보타워사거리 승차", "3100", "3분", "안내 중",
            ),
            live = true,
            nowMs = 4_000L,
        )

        assertEquals(
            "9호선 급행 당산역 · 3100번 신논현역.(구)교보타워사거리",
            RouteLegs.cardNote(),
        )
    }

    @Test
    fun `same first stop without matching total stays empty`() {
        RouteLegs.observe(previewList(), live = false, nowMs = 1_000L)
        RouteLegs.observe(listOf("길안내를 시작합니다."), live = false, nowMs = 2_000L)
        RouteLegs.observe(liveStart("라페스타.먹자골목 승차", "1시간 30분"), live = true, nowMs = 3_000L)

        assertNull(RouteLegs.cardNote())
    }

    @Test
    fun `running card in the live list wins`() {
        RouteLegs.observe(listOf("길안내를 시작합니다."), live = false, nowMs = 1_000L)
        RouteLegs.observe(
            listOf("바로 안내시작", "안내 중") + card661().dropLast(1) + listOf("안내 종료"),
            live = true,
            nowMs = 2_000L,
        )

        assertEquals(
            "서해 백마역 · 7호선 부천종합운동장역 · 661번 춘의역6번출구",
            RouteLegs.cardNote(),
        )
    }

    @Test
    fun `subway start route lists the transfers only`() {
        RouteLegs.observe(card673(), live = false, nowMs = 1_000L)
        RouteLegs.observe(
            liveStart("3호선 정발산역 승차", "1시간 18분") + listOf("길안내를 시작합니다."),
            live = true,
            nowMs = 2_000L,
        )

        assertEquals("서해 대곡역 · 1호선 소사역 · 673번 송내역A", RouteLegs.cardNote())
    }

    @Test
    fun `start phrase lingering while the screen scrolls keeps the route`() {
        RouteLegs.observe(card673(), live = false, nowMs = 1_000L)
        RouteLegs.observe(
            liveStart("3호선 정발산역 승차", "1시간 18분") + listOf("길안내를 시작합니다."),
            live = true,
            nowMs = 2_000L,
        )
        RouteLegs.observe(listOf("안내중인 경로 바로가기"), live = true, nowMs = 2_500L)
        RouteLegs.observe(
            liveStart("1호선 소사역 승차", "1시간 18분") + listOf("길안내를 시작합니다."),
            live = true,
            nowMs = 4_000L,
        )

        assertEquals("서해 대곡역 · 1호선 소사역 · 673번 송내역A", RouteLegs.cardNote())
    }

    @Test
    fun `gap between the total and the pin still reads the card`() {
        val card = card661().toMutableList()
        val pin = card.indexOf("경로 고정")
        card.add(pin, "★")
        card.add(card.indexOf("서해") + 1, "버튼")
        RouteLegs.observe(card, live = false, nowMs = 1_000L)
        RouteLegs.observe(listOf("길안내를 시작합니다."), live = false, nowMs = 2_000L)
        RouteLegs.observe(liveStart("라페스타.먹자골목 승차", "1시간 27분"), live = true, nowMs = 3_000L)

        assertEquals(
            "서해 백마역 · 7호선 부천종합운동장역 · 661번 춘의역6번출구",
            RouteLegs.cardNote(),
        )
    }

    @Test
    fun `running mark picks the route even when another has the same total`() {
        RouteLegs.observe(
            listOf("바로 안내시작", "안내 중", "1시간 18분", "경로 고정") +
                listOf("도보 11분, 3호선 7분, 도보 3분, 경의중앙선 2분, 도보 1분, 서해선 14분, 도보 2분, 8번 일반 버스 19분, 도보 7분") +
                listOf("3호선", "정발산역", "경의중앙", "대곡역", "서해", "능곡역", "일반", "부천종합운동장역5번출구.부천FC", "8", "하차", "솔안공원", "안내 종료") +
                listOf("1시간 18분", "경로 고정", "도보 11분, 3호선 7분, 도보 5분, 서해선 21분") +
                listOf("3호선", "정발산역", "서해", "대곡역", "1호선", "소사역", "하차", "송내역", "바로 안내시작"),
            live = true,
            nowMs = 1_000L,
        )

        assertEquals(
            "경의중앙 대곡역 · 서해 능곡역 · 8번 부천종합운동장역5번출구.부천FC",
            RouteLegs.cardNote(),
        )
    }

    @Test
    fun `bus stop named like a station is kept`() {
        RouteLegs.observe(
            listOf(
                "안내 중", "1시간 56분", "경로 고정",
                "도보 11분, 3호선 7분, 도보 5분, 서해선 21분, 도보 3분, 1호선 25분, 도보 5분, 4번 간선 버스 14분, 도보 6분",
                "3호선", "정발산역", "서해", "대곡역", "1호선", "소사역",
                "간선", "제물포역", "4", "하차", "학익시장(법원검찰청)", "안내 종료",
            ),
            live = true,
            nowMs = 1_000L,
        )

        assertEquals(
            "서해 대곡역 · 1호선 소사역 · 4번 제물포역",
            RouteLegs.cardNote(),
        )
    }

    @Test
    fun `bus board with a trailing word matches the line and the total`() {
        RouteLegs.observe(
            listOf(
                "1시간 41분", "경로 고정",
                "도보 7분, 5000번 직행좌석 버스 34분, 도보 4분, 인천1호선 19분, 도보 4분, 인천2호선 2분",
                "직행", "5000", "23분", "7정류장", "42석", "도착정보", "도착정보 버튼",
                "인천1", "계산역", "인천2", "인천시청역", "하차", "인천지방법원", "바로 안내시작",
            ),
            live = false,
            nowMs = 1_000L,
        )
        RouteLegs.observe(listOf("길안내를 시작합니다."), live = false, nowMs = 2_000L)
        RouteLegs.observe(
            listOf(
                "도보 433m", "7분", "일산동부경찰서(중) 승차\u00A0중앙", "5000", "23분",
                "1시간 41분", "오후 5:01 도착", "길안내를 시작합니다.",
            ),
            live = true,
            nowMs = 3_000L,
        )

        assertEquals("인천1 계산역 · 인천2 인천시청역", RouteLegs.cardNote())
    }

    @Test
    fun `new start later clears the previous route`() {
        RouteLegs.observe(previewList(), live = false, nowMs = 1_000L)
        RouteLegs.observe(listOf("길안내를 시작합니다."), live = false, nowMs = 2_000L)
        RouteLegs.observe(liveStart("라페스타.먹자골목 승차", "1시간 27분"), live = true, nowMs = 3_000L)

        RouteLegs.observe(listOf("길안내를 시작합니다."), live = false, nowMs = 60_000L)

        assertNull(RouteLegs.cardNote())
    }

    @Test
    fun `10-09 a total matching minutes after the start picks nothing`() {
        // 19:27:56: 8 min after 길안내를 시작, 58분 matched an option the rider never took.
        RouteLegs.observe(previewList(), live = false, nowMs = 1_000L)
        RouteLegs.observe(listOf("길안내를 시작합니다."), live = false, nowMs = 2_000L)
        RouteLegs.observe(
            liveStart("라페스타.먹자골목 승차", "1시간 27분"),
            live = true,
            nowMs = 2_000L + RouteLegs.START_MATCH_MS + 1,
        )

        assertNull(RouteLegs.cardNote())
    }

    @Test
    fun `live detail skips the stop number and a split 에서 승차`() {
        RouteLegs.observe(listOf("길안내를 시작합니다."), live = false, nowMs = 1_000L)
        RouteLegs.observe(
            listOf(
                "킨텍스역1번출구.현대모터스튜디오고양 승차", "20764", "941", "27분",
                "킨텍스역1번출구.현대모터스튜디오고양", "에서 승차", "15", "안내 중",
                "3호선 대화역 승차", "안내 중",
            ),
            live = true,
            nowMs = 2_000L,
        )

        assertEquals("3호선 대화역", RouteLegs.cardNote())
    }

    @Test
    fun `bus legs without a stop name keep the line`() {
        val legs = RouteOptionParser.legs(
            listOf(
                "7분", "일반", "67", "66", "65", "95", "포함 6대", "921", "곧 도착",
                "도착정보", "도착정보 버튼", "서해", "대곡역", "1호선", "소사역", "하차", "송내역",
            ),
        )

        assertEquals(listOf("67번", "서해 대곡역", "1호선 소사역"), legs.map { it.cardText })
    }

    @Test
    fun `cut off card is skipped`() {
        val options = RouteOptionParser.parse(card661().dropLast(4))

        assertEquals(0, options.size)
    }

    private fun card661(): List<String> = listOf(
        "1시간 27분", "경로 고정",
        "도보 2분, 81번 일반 버스 16분, 도보 4분, 서해선 24분, 도보 4분, 7호선 1분, 도보 4분, 661번 간선 버스 21분, 도보 1분",
        "2분", "16분", "24분", "21분", "1분", "일반", "라페스타.먹자골목", "81", "12분", "10정류장",
        "여유", "81", "25분", "22정류장", "여유", "도착정보", "도착정보 버튼", "서해", "백마역",
        "7호선", "부천종합운동장역", "간선", "춘의역6번출구", "661", "하차", "법원.검찰청",
        "바로 안내시작",
    )

    private fun card673(): List<String> = listOf(
        "최소시간", "1시간 12분", "경로 고정",
        "도보 11분, 3호선 7분, 도보 5분, 서해선 21분, 도보 3분, 1호선 6분, 도보 13분",
        "11분", "7분", "3호선", "정발산역", "오금행", "마두역 방면", "실시간", "시간표",
        "시간표 버튼", "서해", "대곡역", "1호선", "소사역", "하차", "송내역", "바로 안내시작",
        "1시간 18분", "경로 고정",
        "도보 11분, 3호선 7분, 도보 5분, 서해선 21분, 도보 3분, 1호선 6분, 도보 4분, 673번 간선 버스 7분, 도보 6분",
        "11분", "7분", "5분", "21분", "6분", "7분", "6분", "3호선", "정발산역", "오금행", "마두역 방면",
        "실시간", "시간표", "시간표 버튼", "서해", "대곡역", "1호선", "소사역", "간선", "송내역A",
        "673", "하차", "상도중학교", "바로 안내시작",
    )

    private fun previewList(): List<String> = listOf(
        "1시간 23분", "경로 고정",
        "도보 2분, 81번 일반 버스 16분, 도보 4분, 서해선 27분, 도보 3분, 1호선 6분, 도보 13분",
        "2분", "16분", "일반", "라페스타.먹자골목", "81", "1분", "1정류장", "보통", "도착정보",
        "도착정보 버튼", "서해", "백마역", "1호선", "소사역", "하차", "송내역", "바로 안내시작",
        "1시간 23분", "경로 고정",
        "도보 2분, 81번 일반 버스 16분, 도보 4분, 서해선 27분, 도보 6분, 53번 일반 버스 14분, 도보 7분",
        "2분", "16분", "일반", "라페스타.먹자골목", "81", "1분", "1정류장", "보통", "도착정보",
        "도착정보 버튼", "서해", "백마역", "일반", "소사역", "53", "하차", "솔안공원", "바로 안내시작",
    ) + card661()

    private fun liveStart(board: String, total: String): List<String> = listOf(
        "경기 고양시 일산동구 장항동 1480", "도보 188m", "2분", board, "58117", "81", "12분",
        "버스 도착정보 더보기", "9개 정류장 이동", "16분", "백마역 하차", "20383", "안내 중",
        "13", total, "오후 4:30 도착",
    )
}
