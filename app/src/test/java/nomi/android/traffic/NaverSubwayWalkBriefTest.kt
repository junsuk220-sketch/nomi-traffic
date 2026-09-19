package nomi.android.traffic

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NaverSubwayWalkBriefTest {

    private lateinit var gate: NavigationEventSpeechGate

    @Before
    fun setUp() {
        NaverNearBoardNotice.reset()
        gate = NavigationEventSpeechGate()
    }

    @After
    fun tearDown() {
        NaverNearBoardNotice.reset()
    }

    @Test
    fun `A soon-only first board is a soon wait`() {
        val event = parseBoard(
            text = "마두역 방면 빠른 하차: 2-4",
            bigText = "마두역 방면 빠른 하차: 2-4\n오금행 (곧 도착)",
            hour = 11,
            minute = 9,
            second = 27,
        )
        assertEquals(NaverMapsTransit.KIND_SUBWAY, event.rawText)
        assertEquals("곧", event.busInfo!!.arrivals[0].eta)
        assertEquals("3호선, 곧 출발합니다.", NavigationEventSpeech.line(event))
        assertTrue(gate.acceptNaverSubwayWalkBrief(event))
        assertFalse(gate.accept(event))
    }

    @Test
    fun `B first clock board briefs once with this train and the next`() {
        val event = clockBoard(11, 16, 11, 23)
        assertEquals(NaverMapsTransit.KIND_SUBWAY, event.rawText)
        assertEquals("6분", event.busInfo!!.arrivals[0].eta)
        assertEquals("13분", event.busInfo!!.arrivals[1].eta)
        assertEquals(
            "3호선 오금행 열차가 6분, 6분 후 도착합니다. 다음 열차는 13분 후 도착입니다.",
            NavigationEventSpeech.line(event),
        )
        assertTrue(gate.acceptNaverSubwayWalkBrief(event))
    }

    @Test
    fun `C the same board does not brief again`() {
        val first = clockBoard(11, 16, 11, 23)
        val again = clockBoard(11, 16, 11, 23)
        assertTrue(gate.acceptNaverSubwayWalkBrief(first))
        assertFalse(gate.acceptNaverSubwayWalkBrief(again))
        assertFalse(gate.accept(again))
    }

    @Test
    fun `D a first eta past 10 minutes still briefs`() {
        val event = clockBoard(11, 23, 11, 25)
        assertEquals("13분", event.busInfo!!.arrivals[0].eta)
        assertEquals("15분", event.busInfo!!.arrivals[1].eta)
        assertEquals(
            "3호선 오금행 열차가 13분, 13분 후 도착합니다. 다음 열차는 15분 후 도착입니다.",
            NavigationEventSpeech.line(event),
        )
        assertTrue(gate.acceptNaverSubwayWalkBrief(event))
        assertFalse(gate.accept(event))
    }

    @Test
    fun `walk brief repeats five minutes and leaves the next alone`() {
        val event = transitMinutesPair(5, 15)
        assertEquals(
            "3호선이 5분, 5분 후 도착합니다. 다음 열차는 15분 후 도착입니다.",
            NavigationEventSpeech.line(event),
        )
        assertTrue(gate.acceptNaverSubwayWalkBrief(event))
    }

    @Test
    fun `walk brief repeats eleven minutes and leaves the next alone`() {
        val event = transitMinutesPair(11, 20)
        assertEquals(
            "3호선이 11분, 11분 후 도착합니다. 다음 열차는 20분 후 도착입니다.",
            NavigationEventSpeech.line(event),
        )
        assertTrue(gate.acceptNaverSubwayWalkBrief(event))
    }

    @Test
    fun `E stages after the brief still follow 10 5 2 soon`() {
        val late = clockBoard(11, 23, 11, 25)
        assertTrue(gate.acceptNaverSubwayWalkBrief(late))
        assertFalse(gate.accept(late))

        val ten = minutesBoard(8)
        assertFalse(gate.acceptNaverSubwayWalkBrief(ten))
        assertTrue(gate.accept(ten))

        val five = minutesBoard(4)
        assertFalse(gate.acceptNaverSubwayWalkBrief(five))
        assertTrue(gate.accept(five))

        val two = minutesBoard(2)
        assertFalse(gate.acceptNaverSubwayWalkBrief(two))
        assertTrue(gate.accept(two))

        val soon = minutesBoard(1)
        assertFalse(gate.acceptNaverSubwayWalkBrief(soon))
        assertTrue(gate.accept(soon))
        assertFalse(gate.accept(soon))
    }

    @Test
    fun `relative two one and soon still follow remaining stages after a clock brief`() {
        val first = clockBoard(11, 16, 11, 23)
        assertTrue(gate.acceptNaverSubwayWalkBrief(first))

        val two = parseBoard(
            text = "마두역 방면 빠른 하차: 2-4",
            bigText = "마두역 방면 빠른 하차: 2-4\n오금행 (2분)",
            hour = 11,
            minute = 16,
            second = 0,
        )
        assertEquals(NaverMapsTransit.KIND_SUBWAY, two.rawText)
        assertEquals("2분", two.busInfo!!.arrivals[0].eta)
        assertFalse(gate.acceptNaverSubwayWalkBrief(two))
        assertTrue(gate.accept(two))

        val one = parseBoard(
            text = "마두역 방면 빠른 하차: 2-4",
            bigText = "마두역 방면 빠른 하차: 2-4\n오금행 (1분)",
            hour = 11,
            minute = 17,
            second = 0,
        )
        assertEquals("곧", one.busInfo!!.arrivals[0].eta)
        assertFalse(gate.acceptNaverSubwayWalkBrief(one))
        assertTrue(gate.accept(one))

        val soon = parseBoard(
            text = "마두역 방면 빠른 하차: 2-4",
            bigText = "마두역 방면 빠른 하차: 2-4\n오금행 (곧 도착)",
            hour = 11,
            minute = 17,
            second = 30,
        )
        assertEquals("곧", soon.busInfo!!.arrivals[0].eta)
        assertFalse(gate.acceptNaverSubwayWalkBrief(soon))
        assertFalse(gate.accept(soon))
    }

    @Test
    fun `walk title and board title share the one brief`() {
        val walking = parse(
            title = "정발산역 3호선까지 걷기",
            text = "오금행 (11:16), 오금행 (11:23)",
            hour = 11,
            minute = 10,
            second = 18,
        )
        val boarding = clockBoard(11, 16, 11, 23)
        assertTrue(gate.acceptNaverSubwayWalkBrief(walking))
        assertFalse(gate.acceptNaverSubwayWalkBrief(boarding))
    }

    @Test
    fun `walk title and 도보 후 board title share the one brief`() {
        val walking = parse(
            title = "정발산역 3호선까지 걷기",
            text = "수서행 (18:18), 오금행 (18:27)",
            hour = 18,
            minute = 13,
            second = 18,
        )
        val afterWalk = parse(
            title = "정발산역 3호선 도보 후 열차 승차",
            text = "수서행 (18:18), 오금행 (18:27)",
            hour = 18,
            minute = 13,
            second = 54,
        )
        assertTrue(gate.acceptNaverSubwayWalkBrief(walking))
        assertFalse(gate.acceptNaverSubwayWalkBrief(afterWalk))
        assertFalse(gate.accept(afterWalk))
    }

    private fun clockBoard(firstHour: Int, firstMinute: Int, nextHour: Int, nextMinute: Int) =
        parseBoard(
            text = "마두역 방면 빠른 하차: 2-4",
            bigText = "마두역 방면 빠른 하차: 2-4\n" +
                "오금행 (${clock(firstHour, firstMinute)}), 오금행 (${clock(nextHour, nextMinute)})",
            hour = 11,
            minute = 10,
            second = 18,
        )

    private fun minutesBoard(minutes: Int) = parse(
        title = "정발산역 3호선 열차 승차",
        text = "오금행 (${clockAfter(minutes)})",
        hour = null,
        minute = null,
        second = null,
        nowMillis = System.currentTimeMillis(),
    )

    private fun transitMinutesPair(current: Int, next: Int) =
        nomi.product.nav.NavigationEvent(
            source = nomi.product.nav.NavigationEventSource.NAVER,
            type = nomi.product.nav.NavigationEventType.TRANSIT,
            notificationId = 301,
            channel = "302_PUBTRANS_POPUP",
            title = "정발산역 3호선 열차 승차",
            action = "정발산역 3호선 열차 승차",
            distanceMeters = null,
            rawText = NaverMapsTransit.KIND_SUBWAY,
            busInfo = nomi.product.nav.NavigationBusInfo(
                raw = "3호선 ${current}분 | 오금행",
                arrivals = listOf(
                    nomi.product.nav.NavigationBusArrival("3호선", "${current}분"),
                    nomi.product.nav.NavigationBusArrival("3호선", "${next}분"),
                ),
            ),
            timestampMillis = 0L,
        )

    private fun parseBoard(
        text: String,
        bigText: String,
        hour: Int,
        minute: Int,
        second: Int,
    ) = parse(
        title = "정발산역 3호선 열차 승차",
        text = text,
        bigText = bigText,
        hour = hour,
        minute = minute,
        second = second,
    )

    private fun parse(
        title: String,
        text: String,
        bigText: String? = null,
        hour: Int?,
        minute: Int?,
        second: Int?,
        nowMillis: Long? = null,
    ) = NaverNotificationParser.parse(
        NaverNotificationParser.Snapshot(
            packageName = "com.nhn.android.nmap",
            notificationId = 301,
            channel = "302_PUBTRANS_POPUP",
            title = title,
            text = text,
            bigText = bigText,
            timestampMillis = nowMillis ?: wallClock(hour!!, minute!!, second!!),
        ),
    )!!

    private fun clock(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

    private fun clockAfter(minutes: Int): String {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.MINUTE, minutes)
        return "%02d:%02d".format(
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
        )
    }

    private fun wallClock(hour: Int, minute: Int, second: Int): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
        cal.set(java.util.Calendar.MINUTE, minute)
        cal.set(java.util.Calendar.SECOND, second)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
