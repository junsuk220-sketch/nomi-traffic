package nomi.android.traffic.eventfirst

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * Every title/text pair here was captured from a 302 notification on device.
 */
class NaverEventParserTest {

    private lateinit var originalZone: TimeZone

    @Before
    fun setUp() {
        // Departure clocks resolve against device-local time.
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(SEOUL))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalZone)
    }

    private fun parse(title: String, text: String) = NaverEventParser.parse(title, text, T0)

    @Test
    fun `guidance start carries the destination`() {
        val event = parse("길안내를 시작합니다.", "우림보보카운티2까지 이동")
        assertEquals(
            NaverTransitEvent.GuidanceStart("우림보보카운티2", T0),
            event,
        )
    }

    @Test
    fun `guidance end carries the reason`() {
        val event = parse("길안내를 종료합니다.", "현위치가 예상 경로를 벗어났습니다. 경로를 다시 찾아주세요.")
        assertTrue(event is NaverTransitEvent.GuidanceEnd)
        assertEquals(
            "현위치가 예상 경로를 벗어났습니다. 경로를 다시 찾아주세요.",
            (event as NaverTransitEvent.GuidanceEnd).reason,
        )
    }

    @Test
    fun `walking to a stop is a bus wait`() {
        val event = parse("학익시장(법원검찰청)까지 걷기", "4 (10분), 4 (20분)")
        assertTrue(event is NaverTransitEvent.WaitBus)
        event as NaverTransitEvent.WaitBus
        assertEquals("학익시장(법원검찰청)", event.stop)
        assertEquals("4", event.route)
        assertEquals("10분", event.eta)
        assertEquals("20분", event.nextEta)
    }

    @Test
    fun `boarding title and walking title share one bus scope`() {
        val walk = parse("문학정보고입구까지 걷기", "5 (9분), 5 (25분)")
        val board = parse("문학정보고입구 버스 승차", "5 (9분), 5 (25분)")
        assertEquals(walk?.scopeKey, board?.scopeKey)
        assertEquals("bus|문학정보고입구|5", walk?.scopeKey)
    }

    @Test
    fun `walk then board title keeps the stop name`() {
        val event = parse("일산동부경찰서(중) 도보 후 버스 승차", "2000 (13분), 2000 (30분)")
        assertTrue(event is NaverTransitEvent.WaitBus)
        assertEquals("일산동부경찰서(중)", (event as NaverTransitEvent.WaitBus).stop)
    }

    @Test
    fun `bus board keeps every row for the next-vehicle rule`() {
        val event = parse("일산동부경찰서(중)까지 걷기", "66 (곧 도착), 98 (2분), 83 (3분), 89 (7분), 67 (10분)")
        event as NaverTransitEvent.WaitBus
        assertEquals(5, event.arrivals.size)
        assertEquals("66", event.route)
        assertEquals("곧 도착", event.eta)
        assertEquals("2분", event.nextEta)
    }

    @Test
    fun `train wait reads station line and departures`() {
        val event = NaverEventParser.parse(
            "인천터미널역 인천1호선까지 걷기",
            "검단호수공원행 (17:16), 검단호수공원행 (17:24), 검단호수공원행 (17:30)",
            CLOCK_T0,
        )
        assertTrue(event is NaverTransitEvent.WaitTrain)
        event as NaverTransitEvent.WaitTrain
        assertEquals("인천터미널역", event.station)
        assertEquals("인천1호선", event.line)
        assertEquals("검단호수공원행", event.direction)
        // Clocks stay verbatim on the row; WaitTrainClockTest covers the minutes.
        assertEquals(listOf("17:16", "17:24", "17:30"), event.departures.map { it.departureTime })
        assertEquals("train|인천터미널역|인천1호선", event.scopeKey)
    }

    @Test
    fun `numbered subway line is not read as a bus`() {
        val event = parse("부평구청역 7호선까지 걷기", "장암행 (5분)")
        assertTrue(event is NaverTransitEvent.WaitTrain)
        event as NaverTransitEvent.WaitTrain
        assertEquals("부평구청역", event.station)
        assertEquals("7호선", event.line)
        assertEquals("5분", event.departures.single().eta)
    }

    @Test
    fun `mixed directions are both kept so the judge can refuse to guess`() {
        val event = parse(
            "부천종합운동장역 서해선까지 걷기",
            "일산행 (도착), 대곡행 (5분), 대곡행 (16분)",
        )
        event as NaverTransitEvent.WaitTrain
        assertEquals(listOf("일산행", "대곡행"), event.directions)
        assertNull(event.direction)
    }

    @Test
    fun `board train reads direction and fast transfer car`() {
        val event = parse("인천터미널역 인천1호선 열차 승차", "예술회관역 방면 빠른 환승: 8-4")
        assertEquals(
            NaverTransitEvent.BoardTrain("인천터미널역", "인천1호선", "예술회관역", "8-4", T0),
            event,
        )
    }

    @Test
    fun `riding while moving reads the remaining stops`() {
        val event = parse("문학경기장(야구장)으로 이동 중", "하차까지 35개 정류장")
        assertEquals(
            NaverTransitEvent.Riding("문학경기장(야구장)", 35, RemainingUnit.STOP, T0),
            event,
        )
    }

    @Test
    fun `riding at a stop reads the remaining stations`() {
        val event = parse("예술회관역 정차", "하차까지 7개 역, 15분 소요")
        assertEquals(
            NaverTransitEvent.Riding("예술회관역", 7, RemainingUnit.STATION, T0),
            event,
        )
    }

    @Test
    fun `riding handles the parenthesised particle`() {
        val event = parse("도화IC(으)로 이동 중", "하차까지 12개 정류장")
        assertEquals("도화IC", (event as NaverTransitEvent.Riding).stop)
    }

    @Test
    fun `alight soon names the station when Naver does`() {
        val event = parse("하차까지 1개 역", "부평구청역에서 하차")
        assertEquals(
            NaverTransitEvent.AlightSoon("부평구청역", RemainingUnit.STATION, T0),
            event,
        )
    }

    @Test
    fun `alight soon has no station on the unnamed variant`() {
        val event = parse("하차까지 1개 역", "다음 역에서 하차")
        assertNull((event as NaverTransitEvent.AlightSoon).station)
    }

    @Test
    fun `remaining stations above one is not an alight cue`() {
        assertNull(NaverEventParser.parse("하차까지 3개 역", "부평구청역에서 하차", T0))
    }

    @Test
    fun `alight now takes the station from the text`() {
        val event = parse("이번 역에서 하차", "부평구청역")
        assertEquals(
            NaverTransitEvent.AlightNow("부평구청역", RemainingUnit.STATION, T0),
            event,
        )
    }

    @Test
    fun `alight now also appears with the station in the title`() {
        val event = parse("이번 역(도화역)에서 하차", "도화역")
        assertEquals("도화역", (event as NaverTransitEvent.AlightNow).station)
    }

    @Test
    fun `alight transfer reads station door side and next line`() {
        val event = parse(
            "이번 역(부평구청역)에서 하차 후 환승",
            "내리는 문 오른쪽, 부평구청역 7호선으로 환승",
        )
        assertEquals(
            NaverTransitEvent.AlightTransfer("부평구청역", "7호선", "오른쪽", T0),
            event,
        )
    }

    @Test
    fun `alight transfer reads a named line too`() {
        val event = parse(
            "이번 역(부천종합운동장역)에서 하차 후 환승",
            "내리는 문 왼쪽, 부천종합운동장역 서해선으로 환승",
        )
        event as NaverTransitEvent.AlightTransfer
        assertEquals("서해선", event.transferLine)
        assertEquals("왼쪽", event.doorSide)
    }

    @Test
    fun `unknown title is not forced into an event`() {
        assertNull(NaverEventParser.parse("알 수 없는 안내", "무엇인가", T0))
        assertNull(NaverEventParser.parse("", "4 (10분)", T0))
    }

    private companion object {
        const val T0 = 1_000_000L
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

        /** 2026-09-15 17:08:44 KST — the moment the 인천1호선 board was captured. */
        val CLOCK_T0: Long =
            ZonedDateTime.of(2026, 9, 15, 17, 8, 44, 0, SEOUL).toInstant().toEpochMilli()
    }
}
