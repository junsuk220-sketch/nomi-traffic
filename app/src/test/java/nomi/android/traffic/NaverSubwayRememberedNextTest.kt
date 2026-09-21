package nomi.android.traffic

import nomi.product.nav.NavigationBusArrival
import nomi.product.nav.NavigationBusInfo
import nomi.product.nav.NavigationEvent
import nomi.product.nav.NavigationEventSource
import nomi.product.nav.NavigationEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Preserve a seen next HH:mm so 2분/곧 can still name the next train.
 * Speech reads the event only — Gate attaches the second arrival first.
 */
class NaverSubwayRememberedNextTest {

    @Test
    fun `CASE 1 two minutes uses remembered 14 37 as 15 minutes`() {
        val gate = NavigationEventSpeechGate()
        gate.rememberNaverSubwayClocks(clocks("14:25", "14:37", "14:49", at = wall(14, 13, 29)))
        val two = gate.withRememberedSubwayNext(relative("2분", at = wall(14, 22, 50)))
        assertEquals(listOf("2분", "15분"), etas(two))
        assertEquals(
            "3호선 오금행 열차가 2분 후 도착합니다. 다음 열차는 15분 후 도착입니다.",
            NavigationEventSpeech.line(two),
        )
    }

    @Test
    fun `CASE 2 same 14 37 recomputes to 14 minutes at a later timestamp`() {
        val gate = NavigationEventSpeechGate()
        gate.rememberNaverSubwayClocks(clocks("14:25", "14:37", "14:49", at = wall(14, 13, 29)))
        val soon = gate.withRememberedSubwayNext(relative("곧", at = wall(14, 23, 36)))
        assertEquals(listOf("곧", "14분"), etas(soon))
        assertEquals(
            "3호선, 곧 도착합니다. 다음 열차는 14분 후 도착입니다.",
            NavigationEventSpeech.line(soon),
        )
    }

    @Test
    fun `CASE 3 two minutes without a remembered clock stays current only`() {
        val gate = NavigationEventSpeechGate()
        val two = gate.withRememberedSubwayNext(relative("2분", at = wall(14, 22, 50)))
        assertEquals(listOf("2분"), etas(two))
        assertEquals(
            "3호선 오금행 열차가 2분 후 도착합니다.",
            NavigationEventSpeech.line(two),
        )
    }

    @Test
    fun `CASE 4 soon without a remembered clock stays current only`() {
        val gate = NavigationEventSpeechGate()
        val soon = gate.withRememberedSubwayNext(relative("곧", at = wall(14, 24, 37)))
        assertEquals(listOf("곧"), etas(soon))
        assertEquals("3호선, 곧 도착합니다.", NavigationEventSpeech.line(soon))
    }

    @Test
    fun `CASE 5 arrived makes the remembered next stale`() {
        val gate = NavigationEventSpeechGate()
        gate.rememberNaverSubwayClocks(clocks("14:25", "14:37", "14:49", at = wall(14, 13, 29)))
        gate.rememberNaverSubwayClocks(arrived(at = wall(14, 25, 8)))
        val two = gate.withRememberedSubwayNext(relative("2분", at = wall(14, 25, 20)))
        assertEquals(listOf("2분"), etas(two))
        assertFalse(NavigationEventSpeech.line(two)!!.contains("다음 열차"))
    }

    @Test
    fun `CASE 6 new clock list rebuilds current and next`() {
        val gate = NavigationEventSpeechGate()
        gate.rememberNaverSubwayClocks(clocks("14:25", "14:37", "14:49", at = wall(14, 13, 29)))
        gate.rememberNaverSubwayClocks(arrived(at = wall(14, 25, 8)))
        val nextBoard = clocks("14:37", "14:49", "15:01", at = wall(14, 25, 39))
        gate.rememberNaverSubwayClocks(nextBoard)
        assertEquals(listOf("12분", "24분"), etas(nextBoard))
        val two = gate.withRememberedSubwayNext(relative("2분", at = wall(14, 34, 7)))
        assertEquals(listOf("2분", "15분"), etas(two))
        assertEquals(
            "3호선 오금행 열차가 2분 후 도착합니다. 다음 열차는 15분 후 도착입니다.",
            NavigationEventSpeech.line(two),
        )
    }

    @Test
    fun `CASE 7 journey reset drops the previous next clock`() {
        val gate = NavigationEventSpeechGate()
        gate.rememberNaverSubwayClocks(clocks("14:25", "14:37", "14:49", at = wall(14, 13, 29)))
        gate.resetNaverJourneyCues()
        val two = gate.withRememberedSubwayNext(relative("2분", at = wall(14, 22, 50)))
        assertEquals(listOf("2분"), etas(two))
        assertFalse(NavigationEventSpeech.line(two)!!.contains("다음 열차"))
    }

    @Test
    fun `CASE 8 remembered next does not change the 10 5 2 soon ladder`() {
        val gate = NavigationEventSpeechGate()
        assertTrue(gate.acceptNaverSubwayWalkBrief(clocks("14:25", "14:37", "14:49", at = wall(14, 15, 6))))
        assertTrue(gate.accept(relative("5분", at = wall(14, 20, 2))))
        val two = gate.withRememberedSubwayNext(relative("2분", at = wall(14, 22, 50)))
        assertEquals("2분", two.busInfo!!.arrivals[0].eta)
        assertEquals("15분", two.busInfo!!.arrivals[1].eta)
        assertTrue(gate.accept(two))
        val soon = gate.withRememberedSubwayNext(relative("곧", at = wall(14, 24, 37)))
        assertEquals("곧", soon.busInfo!!.arrivals[0].eta)
        assertTrue(gate.accept(soon))
        assertFalse(gate.accept(soon))
    }

    @Test
    fun `CASE 9 two arrivals from Naver are not given a third`() {
        val gate = NavigationEventSpeechGate()
        val board = clocks("14:25", "14:37", "14:49", at = wall(14, 15, 6))
        gate.rememberNaverSubwayClocks(board)
        val again = gate.withRememberedSubwayNext(board)
        assertEquals(2, again.busInfo!!.arrivals.size)
        assertEquals(listOf("10분", "22분"), etas(again))
    }

    @Test
    fun `CASE 10 same stage with remembered next still dedups`() {
        val gate = NavigationEventSpeechGate()
        gate.rememberNaverSubwayClocks(clocks("14:25", "14:37", "14:49", at = wall(14, 13, 29)))
        val first = gate.withRememberedSubwayNext(relative("2분", at = wall(14, 22, 50)))
        val again = gate.withRememberedSubwayNext(relative("2분", at = wall(14, 23, 6)))
        assertTrue(gate.accept(first))
        assertFalse(gate.accept(again))
    }

    @Test
    fun `a different line does not inherit the remembered next clock`() {
        val gate = NavigationEventSpeechGate()
        gate.rememberNaverSubwayClocks(clocks("14:25", "14:37", "14:49", at = wall(14, 13, 29)))
        val other = relative("2분", at = wall(14, 22, 50), line = "4호선")
        val prepared = gate.withRememberedSubwayNext(other)
        assertEquals(listOf("2분"), etas(prepared))
    }

    private fun etas(event: NavigationEvent): List<String> =
        event.busInfo!!.arrivals.map { it.eta }

    private fun clocks(vararg times: String, at: Long): NavigationEvent {
        val first = times.first()
        val minutes = NaverSubwayNotificationEta.clockMinutesUntil(first, at)
        val nextMinutes = times.getOrNull(1)?.let { NaverSubwayNotificationEta.clockMinutesUntil(it, at) }
        val firstEta = minutes?.let { NaverSubwayNotificationEta.etaText(it) } ?: "12분"
        val arrivals = mutableListOf(NavigationBusArrival("3호선", firstEta))
        if (nextMinutes != null) {
            arrivals.add(NavigationBusArrival("3호선", NaverSubwayNotificationEta.etaText(nextMinutes)))
        }
        val body = times.joinToString(", ") { "오금행 ($it)" }
        return event(
            eta = firstEta,
            arrivals = arrivals,
            clocks = times.toList(),
            raw = "3호선 $firstEta | $body",
            at = at,
        )
    }

    private fun relative(eta: String, at: Long, line: String = "3호선") = event(
        eta = eta,
        arrivals = listOf(NavigationBusArrival(line, eta)),
        clocks = emptyList(),
        raw = "$line $eta | 오금행 ($eta)",
        at = at,
        line = line,
    )

    private fun arrived(at: Long) = event(
        eta = "도착",
        arrivals = listOf(NavigationBusArrival("3호선", "도착")),
        clocks = emptyList(),
        raw = "3호선 도착 | 오금행 (도착)",
        at = at,
        arrived = true,
    )

    private fun event(
        eta: String,
        arrivals: List<NavigationBusArrival>,
        clocks: List<String>,
        raw: String,
        at: Long,
        line: String = "3호선",
        arrived: Boolean = false,
    ) = NavigationEvent(
        source = NavigationEventSource.NAVER,
        type = NavigationEventType.TRANSIT,
        notificationId = 301,
        channel = "302_PUBTRANS_POPUP",
        title = "정발산역 ${line} 도보 후 열차 승차",
        action = "정발산역 ${line} 도보 후 열차 승차",
        distanceMeters = null,
        rawText = NaverMapsTransit.KIND_SUBWAY,
        busInfo = NavigationBusInfo(
            raw = raw,
            arrivals = arrivals,
            clocks = clocks,
            arrived = arrived,
        ),
        timestampMillis = at,
    )

    private fun wall(hour: Int, minute: Int, second: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, second)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
