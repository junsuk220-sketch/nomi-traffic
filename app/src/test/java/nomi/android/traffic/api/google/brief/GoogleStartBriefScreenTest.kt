package nomi.android.traffic.api.google.brief

import nomi.android.traffic.GoogleTransitAccessibilityParser.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class GoogleStartBriefScreenTest {

    private val card = Node(
        children = listOf(
            Node(contentDesc = "도보 6분후 버스, 89후 도보 2분"),
            Node(text = "89", contentDesc = "버스, 89"),
            Node(text = "13분(실시간) 후 일산동부경찰서(중)에서 출발"),
            Node(contentDesc = "이 이동의 경로 한눈에 보기 시작"),
        ),
    )
    private val quiet = Node(children = listOf(Node(text = "대중교통")))
    private val spoken = "89번, 89번 버스가 13분 후 도착합니다. 정류장까지는 걸어서 약 6분입니다."

    /** 22:19 field screen: walk clock 10:28 sits above the bus clock 10:33. */
    private val field = Node(
        children = listOf(
            Node(contentDesc = "버스, \u200b81후 지하철, \u200b3호선후 버스, \u200b790후 도보 4분"),
            Node(text = "\u200b\u200b81\u200b", contentDesc = "버스, \u200b81"),
            Node(text = "오후 10:28"),
            Node(text = "도보 약 2분"),
            Node(text = "오후\n10:33", contentDesc = "오후 10:33에 출발"),
            Node(text = "기타: 오후 10:45(예정)"),
            Node(text = "정류장 5개(5분) 이동"),
            Node(text = "오후\n10:51", contentDesc = "오후 10:51에 출발"),
            Node(text = "기타: 오후 10:41(예정)"),
        ),
    )

    /** 2026-09-27 08:08. The opened route, before 시작. */
    private val morning = Node(
        children = listOf(
            Node(contentDesc = "버스, \u200b81후 버스, \u200b999"),
            Node(text = "\u200b\u200b81\u200b", contentDesc = "버스, \u200b81"),
            Node(text = "도보 약 2분"),
            Node(text = "오전 8:15에 출발"),
            Node(text = "기타: 오전 8:27(예정)"),
        ),
    )

    /** The same route after guidance turns on: "도보 약 2분" is gone. */
    private val morningLive = Node(
        children = listOf(
            Node(contentDesc = "버스, \u200b81후 버스, \u200b999"),
            Node(text = "\u200b\u200b81\u200b", contentDesc = "버스, \u200b81"),
            Node(text = "도보"),
            Node(contentDesc = "이 이동의 경로 한눈에 보기 종료"),
        ),
    )

    private fun at(hour: Int, minute: Int, second: Int = 0) = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, second)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Before
    fun setUp() = GoogleStartBrief.resetForTest()

    @Test
    fun `opened route before start stays silent`() {
        assertNull(GoogleStartBrief.onScreen(card, live = false, nowMillis = 1L))
    }

    @Test
    fun `guidance on speaks the screen remembered before it`() {
        assertNull(GoogleStartBrief.onScreen(card, live = false, nowMillis = 1L))
        assertEquals(spoken, GoogleStartBrief.onScreen(quiet, live = true, nowMillis = 2L))
        assertNull(GoogleStartBrief.onScreen(card, live = true, nowMillis = 3L))
    }

    @Test
    fun `live screen without a remembered route stays silent`() {
        assertNull(GoogleStartBrief.onScreen(card, live = true, nowMillis = 1L))
    }

    @Test
    fun `morning route speaks from the screen before start`() {
        val seen = at(8, 8, 53)
        val started = at(8, 8, 57)
        assertNull(GoogleStartBrief.onScreen(morning, live = false, nowMillis = seen))
        assertEquals(
            "81번, 81번 버스가 7분 후 도착합니다. 정류장까지는 걸어서 약 2분입니다. 다음 버스는 19분 후입니다.",
            GoogleStartBrief.onScreen(morningLive, live = true, nowMillis = started),
        )
    }

    @Test
    fun `bus clock is used, not the walk clock, and the next bus follows`() {
        val now = at(22, 19, 28)
        assertNull(GoogleStartBrief.onScreen(field, live = false, nowMillis = now))
        assertEquals(
            "81번, 81번 버스가 14분 후 도착합니다. 정류장까지는 걸어서 약 2분입니다. 다음 버스는 26분 후입니다.",
            GoogleStartBrief.onScreen(quiet, live = true, nowMillis = now),
        )
    }

    @Test
    fun `ending guidance does not speak and a flicker does not repeat`() {
        assertNull(GoogleStartBrief.onScreen(card, live = false, nowMillis = 1L))
        assertEquals(spoken, GoogleStartBrief.onScreen(quiet, live = true, nowMillis = 2L))
        assertNull(GoogleStartBrief.onScreen(card, live = false, nowMillis = 3L))
        assertNull(GoogleStartBrief.onScreen(quiet, live = true, nowMillis = 4L))
    }

    @Test
    fun `trip end allows the next trip`() {
        assertNull(GoogleStartBrief.onScreen(card, live = false, nowMillis = 1L))
        assertEquals(spoken, GoogleStartBrief.onScreen(quiet, live = true, nowMillis = 2L))
        GoogleStartBrief.reset()
        assertNull(GoogleStartBrief.onScreen(card, live = false, nowMillis = 3L))
        assertEquals(spoken, GoogleStartBrief.onScreen(quiet, live = true, nowMillis = 4L))
    }

    /** 2026-09-27 11:43. Summary lists the transfer bus 999 before the boarded 99. */
    @Test
    fun `transfer bus in the summary is not spoken`() {
        val route = Node(
            children = listOf(
                Node(contentDesc = "버스, \u200b81 또는 99후 버스, \u200b999"),
                Node(text = "81 또는 99", contentDesc = "버스, \u200b81 또는 99"),
                Node(text = "999", contentDesc = "버스, \u200b999"),
                Node(text = "도보 약 2분"),
                Node(text = "라페스타.먹자골목"),
                Node(text = "99"),
                Node(text = "약산마을"),
                Node(text = "예정됨"),
                Node(contentDesc = "오전 11:47에 출발"),
                Node(text = "기타: 오전 11:51(예정)"),
            ),
        )
        assertNull(GoogleStartBrief.onScreen(route, live = false, nowMillis = at(11, 43)))
        assertEquals(
            "99번, 99번 버스가 4분 후 도착합니다. 정류장까지는 걸어서 약 2분입니다. 다음 버스는 8분 후입니다.",
            GoogleStartBrief.onScreen(quiet, live = true, nowMillis = at(11, 43)),
        )
    }

    /** 2026-09-27 17:14. The boarded bus is 7727, not the first alternative 66. */
    @Test
    fun `boarded bus is spoken, not the first alternative`() {
        val route = Node(
            children = listOf(
                Node(contentDesc = "도보 5분후 버스, \u200b66, 89, 707 또는 7727후 지하철, \u200bGTX-A"),
                Node(text = "도보 약 5분"),
                Node(text = "일산동부경찰서(중)"),
                Node(text = "7727"),
                Node(text = "신촌"),
                Node(text = "실시간"),
                Node(contentDesc = "오후 5:24에 출발"),
            ),
        )
        assertNull(GoogleStartBrief.onScreen(route, live = false, nowMillis = at(17, 14)))
        assertEquals(
            "7727번, 7727번 버스가 10분 후 도착합니다. 정류장까지는 걸어서 약 5분입니다.",
            GoogleStartBrief.onScreen(quiet, live = true, nowMillis = at(17, 14)),
        )
    }

    /** 2026-09-30 17:26:10. Route options list; its first row is 65, not the chosen route. */
    private val routeList = Node(
        children = listOf(
            Node(text = "대중교통 모드: 35분"),
            Node(contentDesc = "도보 4분후 버스, \u200b65, 66, 7727 또는 9711후 도보 9분"),
            Node(text = "도보 4분"),
            Node(text = "\u200b\u200b65\u200b, \u200b66\u200b, \u200b7727\u200b 또는 \u200b9711\u200b"),
            Node(text = "소요 시간: 35분"),
            Node(text = "3분(실시간) 후 일산동부경찰서(중)에서 출발\n기타: 9분(실시간) 후"),
            Node(contentDesc = "버스, \u200b81 또는 99후 버스, \u200b1000 또는 7727후 도보 9분"),
            Node(text = "소요 시간: 45분"),
            Node(text = "예정 시간: 오후 5:33 라페스타.먹자골목에서 출발\n기타: 예정 시간: 오후 5:44, 오후 5:45"),
        ),
    )

    /** 2026-09-30 17:26:12. The chosen route starts at the stop: no walk before 81. */
    private val boardHere = Node(
        children = listOf(
            Node(contentDesc = "버스, \u200b81 또는 99후 버스, \u200b1000 또는 7727후 도보 9분"),
            Node(text = "\u200b\u200b81\u200b 또는 \u200b99\u200b", contentDesc = "버스, \u200b81 또는 99"),
            Node(text = "9"),
            Node(text = "도보 9분"),
            Node(text = "오후 5:26"),
            Node(text = "라페스타.먹자골목"),
            Node(text = "\u200b\u200b81\u200b", contentDesc = "\u200b81"),
            Node(text = "모모월드"),
            Node(text = "예정됨"),
            Node(text = "오후\n5:33", contentDesc = "오후 5:33에 출발"),
            Node(text = "출발편 더보기"),
            Node(text = "기타: 오후 5:44(예정)"),
            Node(text = "\u200b\u200b7727\u200b", contentDesc = "\u200b7727"),
            Node(text = "오후\n5:41", contentDesc = "오후 5:41에 출발"),
            Node(text = "기타: 오후 5:38(실시간)"),
            Node(contentDesc = "이 이동의 경로 한눈에 보기 시작"),
        ),
    )

    @Test
    fun `route options list is not remembered`() {
        val now = at(17, 26, 10)
        assertNull(GoogleStartBrief.onScreen(routeList, live = false, nowMillis = now))
        assertNull(GoogleStartBrief.onScreen(quiet, live = true, nowMillis = now + 13_000L))
    }

    @Test
    fun `route that boards at the stop speaks the chosen bus, not the list`() {
        assertNull(GoogleStartBrief.onScreen(routeList, live = false, nowMillis = at(17, 26, 10)))
        assertNull(GoogleStartBrief.onScreen(boardHere, live = false, nowMillis = at(17, 26, 12)))
        assertEquals(
            "81번, 81번 버스가 7분 후 도착합니다. 다음 버스는 18분 후입니다.",
            GoogleStartBrief.onScreen(quiet, live = true, nowMillis = at(17, 26, 23)),
        )
    }

    @Test
    fun `next clock far away is dropped`() {
        val late = Node(
            children = listOf(
                Node(contentDesc = "버스, \u200b81후 버스, \u200b567"),
                Node(text = "\u200b81", contentDesc = "버스, \u200b81"),
                Node(text = "도보 약 3분"),
                Node(text = "오후\n10:51", contentDesc = "오후 10:51에 출발"),
                Node(text = "기타: 오전 6:03(예정)"),
            ),
        )
        val now = at(22, 20, 38)
        assertNull(GoogleStartBrief.onScreen(late, live = false, nowMillis = now))
        assertEquals(
            "81번, 81번 버스가 31분 후 도착합니다. 정류장까지는 걸어서 약 3분입니다.",
            GoogleStartBrief.onScreen(quiet, live = true, nowMillis = now),
        )
    }
}
