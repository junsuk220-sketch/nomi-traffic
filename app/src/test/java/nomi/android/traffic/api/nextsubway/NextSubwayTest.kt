package nomi.android.traffic.api.nextsubway

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class NextSubwayTest {

    @Before
    fun setUp() {
        NextSubway.resetForTest()
    }

    @After
    fun tearDown() {
        NextSubway.resetForTest()
    }

    @Test
    fun `route list before guidance does not remember a subway`() {
        val fact = NextSubway.consider(
            listOf(
                "3호선",
                "정발산역",
                "오금행",
                "마두역 방면",
                "바로 안내시작",
            ),
        )
        assertNull(fact)
        assertNull(NextSubway.snapshot())
    }

    /** 2026-10-01 22:45: 240 bus stop list. `학동역` + `학동역6번출구` are bus stops. */
    @Test
    fun `bus stops named after a station are not a subway`() {
        val fact = NextSubway.consider(
            listOf(
                "압구정역4번출구",
                "국민은행압구정종합금융센터",
                "강남을지병원",
                "학동역",
                "학동역6번출구",
                "영동시장",
                "신사역3번출구 하차",
                "23110",
                "안내 중",
            ),
        )
        assertNull(fact?.station)
    }

    @Test
    fun `guidance start remembers the subway on the live card`() {
        val fact = NextSubway.consider(
            listOf(
                "라페스타.먹자골목 승차",
                "롯데백화점 하차",
                "정발산역",
                "번 출구까지",
                "\uFFFD3호선\uFFFD환승1회\uFFFD50분",
                "안내 중",
                "최소시간\uFFFD49분",
                "\uFFFD81번\uFFFD환승2회\uFFFD51분",
                "길안내를 시작합니다.",
            ),
        )
        assertEquals("3호선", fact!!.line)
        assertEquals("정발산역", fact.station)
        assertNull(fact.direction)
        assertNull(fact.cars)
    }

    @Test
    fun `later start screen without the station keeps the remembered station`() {
        NextSubway.consider(
            listOf(
                "정발산역",
                "번 출구까지",
                "\uFFFD3호선\uFFFD환승1회\uFFFD50분",
                "안내 중",
                "길안내를 시작합니다.",
            ),
        )
        val fact = NextSubway.consider(
            listOf(
                "라페스타.먹자골목 승차",
                "\uFFFD3호선\uFFFD환승1회\uFFFD50분",
                "안내 중",
                "길안내를 시작합니다.",
            ),
        )
        assertEquals("3호선", fact!!.line)
        assertEquals("정발산역", fact.station)
    }

    @Test
    fun `board text fills direction and cars without replacing the station`() {
        NextSubway.consider(
            listOf(
                "정발산역",
                "번 출구까지",
                "\uFFFD3호선\uFFFD환승1회\uFFFD50분",
                "안내 중",
            ),
        )
        val fact = NextSubway.consider(
            listOf(
                "3호선 정발산역 승차",
                "마두역 방면",
                "빠른 하차: 9-1",
                "오금행",
                "삼송역 하차",
                "안내 중",
            ),
        )
        assertEquals("3호선", fact!!.line)
        assertEquals("정발산역", fact.station)
        assertEquals("마두역", fact.direction)
        assertEquals("9-1", fact.cars)
    }

    @Test
    fun `started station keeps the line seen beside it before guidance`() {
        NextSubway.consider(
            listOf(
                "3호선",
                "정발산역",
                "오금행",
                "마두역 방면",
                "바로 안내시작",
            ),
        )
        val fact = NextSubway.consider(
            listOf(
                "라페스타.먹자골목 승차",
                "롯데백화점 하차",
                "정발산역",
                "번 출구까지",
                "길안내를 시작합니다.",
            ),
        )
        assertEquals("3호선", fact!!.line)
        assertEquals("정발산역", fact.station)
        assertEquals("마두역", fact.direction)
    }

    @Test
    fun `start board stop keeps the subway from that route card`() {
        NextSubway.consider(
            listOf(
                "3호선",
                "정발산역",
                "오금행",
                "마두역 방면",
                "삼송역4번출구",
                "바로 안내시작",
                "일반",
                "라페스타.먹자골목",
                "99",
                "81",
                "3호선",
                "정발산역",
                "원흥역7번출구",
                "바로 안내시작",
            ),
        )
        val fact = NextSubway.consider(
            listOf(
                "라페스타.먹자골목 승차",
                "\uFFFD3호선\uFFFD환승1회\uFFFD47분",
                "안내 중",
                "길안내를 시작합니다.",
            ),
        )
        assertEquals("3호선", fact!!.line)
        assertEquals("정발산역", fact.station)
    }

    @Test
    fun `live start without station still keeps subway from bus card`() {
        NextSubway.consider(
            listOf(
                "11분", "19분", "5분", "5분", "3분",
                "3호선", "정발산역", "오금행", "마두역 방면", "마을",
                "바로 안내시작",
                "최소시간", "43분", "경로 고정",
                "도보 2분, 81번 일반 버스 2분, 도보 3분, 3호선 19분",
                "2분", "2분", "3분", "19분", "5분", "5분", "3분",
                "일반", "라페스타.먹자골목", "81", "99", "99",
                "7분", "7정류장", "여유", "81", "8분", "8정류장", "여유",
                "도착정보", "도착정보 버튼",
                "3호선", "정발산역", "마을", "삼송역4번출구",
                "041B", "047", "포함 3대", "하차", "신원상가앞",
                "바로 안내시작",
            ),
        )
        val fact = NextSubway.consider(
            listOf(
                "라페스타.먹자골목 승차",
                "58117",
                "99",
                "7분",
                "최적\uFFFD45분",
                "안내 중",
                "\uFFFD3호선\uFFFD환승2회\uFFFD44분",
                "길안내를 시작합니다.",
            ),
        )
        assertEquals("3호선", fact!!.line)
        assertEquals("정발산역", fact.station)
    }

    @Test
    fun `10-13 live destination 선릉역 2호선 is not the next subway`() {
        val fact = NextSubway.consider(
            listOf("안내 중", "최소환승\uFFFD1시간 23분", "11", "선릉역 2호선", "까지 이동"),
        )
        assertNull(fact)
        assertNull(NextSubway.cardNote())
    }

    @Test
    fun `search history without route card does not become the subway`() {
        assertNull(
            NextSubway.consider(
                listOf("대화역 3호선", "대화역", "3호선"),
            ),
        )
        NextSubway.consider(
            listOf(
                "일반",
                "라페스타.먹자골목",
                "81",
                "3호선",
                "정발산역",
                "마두역 방면",
                "바로 안내시작",
            ),
        )
        val fact = NextSubway.consider(
            listOf(
                "라페스타.먹자골목 승차",
                "길안내를 시작합니다.",
            ),
        )
        assertEquals("3호선", fact!!.line)
        assertEquals("정발산역", fact.station)
    }

    @Test
    fun `search history after an earlier guidance does not replace the pressed route`() {
        // Earlier guidance left the memory open without an end phrase.
        NextSubway.consider(
            listOf("정발산역", "번 출구까지", "\uFFFD3호선\uFFFD환승1회\uFFFD50분", "안내 중"),
        )
        // 2026-09-24 07:59:10: search home with recent history, not live.
        NextSubway.consider(
            listOf(
                "장소, 버스, 지하철, 주소 검색", "광닭발", "09.23.",
                "대화역 3호선", "09.22.", "대화역", "09.22.", "최근검색",
            ),
            learn = false,
        )
        // Route list with the 55-minute card, not live.
        NextSubway.consider(
            listOf(
                "일반", "라페스타.먹자골목", "81", "99",
                "3호선", "정발산역", "마을", "삼송역4번출구", "041B", "040",
                "하차", "신원상가앞",
                "바로 안내시작",
            ),
            learn = false,
        )
        val fact = NextSubway.consider(
            listOf(
                "라페스타.먹자골목 승차",
                "3개 정류장 이동",
                "롯데백화점 하차",
                "\uFFFD3호선\uFFFD환승1회\uFFFD51분",
                "안내 중",
                "길안내를 시작합니다.",
            ),
        )
        assertEquals("3호선", fact!!.line)
        assertEquals("정발산역", fact.station)
    }

    @Test
    fun `detail 안내시작 keeps subway from earlier list card`() {
        // 2026-09-24 08:35~08:42: route list with 바로 안내시작.
        NextSubway.consider(
            listOf(
                "일반", "라페스타.먹자골목", "81", "99",
                "3호선", "정발산역", "오금행", "마두역 방면",
                "마을", "삼송역4번출구", "041B", "040",
                "하차", "신원상가앞",
                "바로 안내시작",
            ),
            learn = false,
        )
        // Detail view: button is "안내시작", station name not on screen.
        NextSubway.consider(
            listOf(
                "55분",
                "도보 2분, 81번 일반 버스 2분, 도보 3분, 3호선 19분",
                "81", "3호선", "041B",
                "라페스타.먹자골목 승차",
                "58117",
                "미리보기",
                "안내시작",
            ),
            learn = false,
        )
        val fact = NextSubway.consider(
            listOf(
                "라페스타.먹자골목 승차",
                "58117",
                "3개 정류장 이동",
                "안내 중",
                "길안내를 시작합니다.",
            ),
        )
        assertEquals("3호선", fact!!.line)
        assertEquals("정발산역", fact.station)
        assertEquals("마두역", fact.direction)
    }

    @Test
    fun `search history while guidance is open is ignored`() {
        NextSubway.consider(
            listOf(
                "일반", "라페스타.먹자골목", "81",
                "3호선", "정발산역", "바로 안내시작",
            ),
            learn = false,
        )
        NextSubway.consider(listOf("라페스타.먹자골목 승차", "안내 중", "길안내를 시작합니다."))
        NextSubway.consider(listOf("대화역 3호선", "대화역", "최근검색"), learn = false)
        assertEquals("정발산역", NextSubway.snapshot()!!.station)
    }

    @Test
    fun `three subway route keeps every board in order`() {
        // 2026-09-24 09:04 live ticks, 광닭발 → 인천지방법원.
        NextSubway.consider(
            listOf(
                "정발산역", "2", "번 출구까지", "도보 757m", "11분", "지하철 노선도 미리보기",
                "3호선 정발산역 승차", "마두역 방면", "빠른 환승: 8-1", "실시간", "시간표",
                "3개 역 이동", "7분", "대곡역 하차",
                "\uFFFD3호선\uFFFD환승3회\uFFFD1시간 53분", "안내 중",
                "\uFFFD5000번\uFFFD환승3회\uFFFD1시간 40분", "15", "1시간 54분",
                "오전 10:58 도착", "길안내를 시작합니다.",
            ),
        )
        NextSubway.consider(
            listOf(
                "대곡역 하차", "도보 386m", "5분", "서해선 대곡역 승차", "능곡역 방면",
                "빠른 환승: 1-1", "실시간", "시간표", "원시행", "(2번)", "5개 역 이동", "21분",
                "\uFFFD3호선\uFFFD환승3회\uFFFD1시간 53분", "안내 중",
            ),
        )
        NextSubway.consider(
            listOf(
                "빠른 환승: 1-1", "실시간", "시간표", "원시행", "(2번)", "5개 역 이동", "21분",
                "소사역 하차", "도보 222m", "3분", "1호선 소사역 승차", "부천역 방면",
                "실시간", "시간표", "\uFFFD3호선\uFFFD환승3회\uFFFD1시간 53분", "안내 중",
                "11", "인천지방법원", "까지 이동",
            ),
        )
        // Screen scrolls back to the start leg later.
        NextSubway.consider(
            listOf("3호선 정발산역 승차", "마두역 방면", "대곡역 하차", "안내 중"),
        )
        val chain = NextSubway.chain()
        assertEquals(listOf("정발산역", "대곡역", "소사역"), chain.map { it.station })
        assertEquals(listOf("3호선", "서해선", "1호선"), chain.map { it.line })
        assertEquals("능곡역", chain[1].direction)
        assertEquals("1-1", chain[1].cars)
        assertEquals("정발산역", NextSubway.startLeg()!!.station)
        assertEquals("서해선 대곡역 · 1호선 소사역", NextSubway.cardNote())

        val first = NextSubway.takeTransferTarget(
            listOf("이번 역(대곡)에서 하차 후 환승", "내리는 문 오른쪽, 서해선으로 환승"),
        )
        assertEquals("대곡역", first!!.station)
        val second = NextSubway.takeTransferTarget(listOf("이번 역(소사)에서 하차 후 환승"))
        assertEquals("소사역", second!!.station)
        assertNull(NextSubway.takeTransferTarget(listOf("이번 역(주안)에서 하차 후 환승")))
    }

    @Test
    fun `bus first route has the subway as a transfer`() {
        NextSubway.consider(
            listOf(
                "라페스타.먹자골목 승차", "58117", "3개 정류장 이동", "롯데백화점 하차",
                "안내 중", "길안내를 시작합니다.",
            ),
        )
        NextSubway.consider(
            listOf("롯데백화점 하차", "3호선 정발산역 승차", "마두역 방면", "빠른 환승: 6-3", "안내 중"),
        )
        assertNull(NextSubway.startLeg())
        assertEquals("3호선 정발산역", NextSubway.cardNote())
        val target = NextSubway.takeTransferTarget(listOf("이번 정류장에서 하차 후 환승"))
        assertEquals("정발산역", target!!.station)
        assertNull(NextSubway.takeTransferTarget(listOf("이번 역(삼송)에서 하차 후 환승")))
    }

    @Test
    fun `bus category tokens on a card are not subway lines`() {
        NextSubway.consider(
            listOf("간선", "학익시장", "4", "3호선", "정발산역", "바로 안내시작"),
            learn = false,
        )
        val fact = NextSubway.consider(listOf("학익시장 승차", "안내 중", "길안내를 시작합니다."))
        assertEquals("3호선", fact!!.line)
        assertEquals("정발산역", fact.station)
    }

    @Test
    fun `guidance end clears the remembered subway`() {
        NextSubway.consider(
            listOf(
                "정발산역",
                "번 출구까지",
                "3호선 환승1회",
                "안내 중",
            ),
        )
        assertNull(NextSubway.consider(listOf("길안내를 종료합니다.")))
        assertNull(NextSubway.snapshot())
    }

    @Test
    fun `express marker stays on the boarded line and a browsed line does not replace it`() {
        // 2026-10-06 강남: the list also showed `2호선 당산역`, the detail said `9호선(급)`.
        NextSubway.consider(
            listOf("3호선", "정발산역", "마두역 방면", "바로 안내시작", "2호선", "당산역", "바로 안내시작"),
            learn = false,
        )
        NextSubway.consider(
            listOf(
                "일산동부경찰서(중) 승차",
                "당산역.삼성래미안아파트 하차",
                "9호선(급) 당산역 승차",
                "여의도역 방면",
                "안내 중",
                "길안내를 시작합니다.",
            ),
        )
        val board = NextSubway.chain().single()
        assertEquals("9호선", board.line)
        assertEquals("당산역", board.station)
        assertEquals("급행", board.service)
        assertEquals("여의도역", board.direction)
        assertEquals("9호선 급행 당산역", NextSubway.cardNote())
        assertEquals(
            "당산역",
            NextSubway.takeTransferTarget(listOf("이번 정류장에서 하차 후 환승"))!!.station,
        )
        assertNull(NextSubway.takeTransferTarget(listOf("이번 역(신논현역)에서 하차 후 환승")))
    }
}
