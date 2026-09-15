package nomi.android.traffic

import nomi.product.nav.NavigationEventSource
import nomi.product.nav.NavigationEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NaverSubwayAccessibilityParserTest {

    @Test
    fun `quick exit with direction and cars speaks once`() {
        val events = NaverSubwayAccessibilityParser.parse(
            packageName = NaverMapNotification.PACKAGE,
            root = board(
                "3호선 정발산역 승차",
                "마두역 방면",
                "빠른 하차: 2-4",
            ),
        )
        assertEquals(1, events.size)
        val event = events[0]
        assertEquals(NavigationEventSource.NAVER, event.source)
        assertEquals(NavigationEventType.TRANSIT, event.type)
        assertEquals(NaverMapsTransit.QUICK_EXIT, event.action)
        assertEquals("마두역 방면", event.landmark)
        assertEquals("3호선 정발산역 승차", event.title)
        assertEquals("2-4", event.rawText)
        assertEquals(
            "3호선 정발산역 승차 마두역 방면입니다. 빠른 하차는 2다시4입니다.",
            NavigationEventSpeech.line(event),
        )
        val gate = NavigationEventSpeechGate()
        assertTrue(gate.accept(event))
        assertFalse(gate.accept(event))
    }

    @Test
    fun `device dump quick exit keeps every on-screen car`() {
        val events = NaverSubwayAccessibilityParser.parse(
            packageName = NaverMapNotification.PACKAGE,
            root = board(
                "3호선 정발산역 승차",
                "마두역 방면",
                "빠른 하차: 2-4, 4-1, 6-3",
                "실시간",
                "시간표",
                "17:08",
                "오금행",
            ),
        )
        assertEquals(1, events.size)
        assertEquals(
            "3호선 정발산역 승차 마두역 방면입니다. 빠른 하차는 2다시4, 4다시1, 6다시3입니다.",
            NavigationEventSpeech.line(events[0]),
        )
    }

    @Test
    fun `quick transfer with direction and cars speaks once`() {
        val events = NaverSubwayAccessibilityParser.parse(
            packageName = NaverMapNotification.PACKAGE,
            root = board(
                "대화역 방면",
                "빠른 환승: 2-3",
            ),
        )
        assertEquals(1, events.size)
        val event = events[0]
        assertEquals(NaverMapsTransit.QUICK_TRANSFER, event.action)
        assertEquals("대화역 방면", event.landmark)
        assertEquals("2-3", event.rawText)
        assertEquals("대화역 방면입니다. 빠른 환승은 2다시3입니다.", NavigationEventSpeech.line(event))
        val gate = NavigationEventSpeechGate()
        assertTrue(gate.accept(event))
        assertFalse(gate.accept(event))
    }

    @Test
    fun `new car cue after a different segment can speak again`() {
        val first = NaverSubwayAccessibilityParser.parse(
            packageName = NaverMapNotification.PACKAGE,
            root = board("마두역 방면", "빠른 하차: 2-4"),
        ).single()
        val second = NaverSubwayAccessibilityParser.parse(
            packageName = NaverMapNotification.PACKAGE,
            root = board("대화역 방면", "빠른 환승: 3-2"),
        ).single()
        val gate = NavigationEventSpeechGate()
        assertTrue(gate.accept(first))
        assertFalse(gate.accept(first))
        assertTrue(gate.accept(second))
        assertFalse(gate.accept(second))
    }

    @Test
    fun `car number alone is silent`() {
        val events = NaverSubwayAccessibilityParser.parse(
            packageName = NaverMapNotification.PACKAGE,
            root = board("2-4"),
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `direction alone is silent`() {
        val events = NaverSubwayAccessibilityParser.parse(
            packageName = NaverMapNotification.PACKAGE,
            root = board("마두역 방면"),
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `quick exit label alone is silent`() {
        val events = NaverSubwayAccessibilityParser.parse(
            packageName = NaverMapNotification.PACKAGE,
            root = board("빠른 하차"),
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `quick exit without car numbers is silent`() {
        val events = NaverSubwayAccessibilityParser.parse(
            packageName = NaverMapNotification.PACKAGE,
            root = board("마두역 방면", "빠른 하차:"),
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `quick transfer without direction is silent`() {
        val events = NaverSubwayAccessibilityParser.parse(
            packageName = NaverMapNotification.PACKAGE,
            root = board("빠른 환승: 2-3"),
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `wrong package is ignored`() {
        val events = NaverSubwayAccessibilityParser.parse(
            packageName = GoogleMapsTransit.PACKAGE,
            root = board("마두역 방면", "빠른 하차: 2-4"),
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `naver bus arrival stages use BusWaitCore`() {
        val core = nomi.android.traffic.buswait.BusWaitCore()
        core.seed("11")
        assertEquals(10, core.observe(listOf(nomi.product.nav.NavigationBusArrival("11", "10분")), 1_000L)!!.speakStage)
        assertEquals(
            5,
            core.observe(
                listOf(nomi.product.nav.NavigationBusArrival("11", "5분")),
                1_000L + nomi.android.traffic.buswait.BusWaitCore.STAGE_COOLDOWN_MS,
            )!!.speakStage,
        )
        assertEquals(
            2,
            core.observe(
                listOf(nomi.product.nav.NavigationBusArrival("11", "2분")),
                1_000L + nomi.android.traffic.buswait.BusWaitCore.STAGE_COOLDOWN_MS * 2,
            )!!.speakStage,
        )
        assertEquals(1, core.observe(listOf(nomi.product.nav.NavigationBusArrival("11", "1분")))!!.speakStage)
        val bus = NaverNotificationParser.parse(
            NaverNotificationParser.Snapshot(
                packageName = "com.nhn.android.nmap",
                notificationId = 301,
                channel = "302_PUBTRANS_POPUP",
                title = "일산동부경찰서(중) 도보 후 버스 승차",
                text = "11 (6분), 11 (13분)",
            ),
        )
        assertEquals(
            "11번, 11번 버스가 6분 후 도착해요. 다음은 11번, 13분 후 도착입니다.",
            NavigationEventSpeech.line(bus!!),
        )
    }

    @Test
    fun `future subway cars stay silent while a bus alight is still the current step`() {
        val events = NaverSubwayAccessibilityParser.parse(
            packageName = NaverMapNotification.PACKAGE,
            root = board(
                "안내 중",
                "롯데백화점 하차",
                "20214",
                "정발산역 2번 출구까지 도보 110m · 3분",
                "3호선 정발산역 승차",
                "마두역 방면",
                "빠른 하차: 2-4",
            ),
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `subway cars speak after the earlier alight step is gone`() {
        val events = NaverSubwayAccessibilityParser.parse(
            packageName = NaverMapNotification.PACKAGE,
            root = board(
                "3호선 정발산역 승차",
                "마두역 방면",
                "빠른 하차: 2-4",
            ),
        )
        assertEquals(
            "3호선 정발산역 승차 마두역 방면입니다. 빠른 하차는 2다시4입니다.",
            NavigationEventSpeech.line(events.single()),
        )
    }

    @Test
    fun `dedup skips the same cue`() {
        val dedup = NaverSubwayAccessibilityDedup()
        val event = NaverSubwayAccessibilityParser.parse(
            packageName = NaverMapNotification.PACKAGE,
            root = board("마두역 방면", "빠른 하차: 2-4"),
        ).single()
        assertTrue(dedup.accept(event))
        assertFalse(dedup.accept(event))
    }

    private fun board(vararg texts: String) = NaverSubwayAccessibilityParser.Node(
        children = texts.map { NaverSubwayAccessibilityParser.Node(text = it) },
    )
}
