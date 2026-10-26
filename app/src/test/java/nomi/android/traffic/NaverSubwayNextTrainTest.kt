package nomi.android.traffic

import nomi.product.nav.NavigationBusArrival
import nomi.product.nav.NavigationBusInfo
import nomi.product.nav.NavigationEvent
import nomi.product.nav.NavigationEventSource
import nomi.product.nav.NavigationEventType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Subway stages reopen on a later clock head, or after `(도착)` on the first
 * clock head / a relative `2분` or more. `(도착)` itself is never a rung.
 */
class NaverSubwayNextTrainTest {

    @Test
    fun `countdown through arrived then the next train counts again`() {
        val gate = NavigationEventSpeechGate()
        assertTrue(
            gate.acceptNaverSubwayWalkBrief(
                subway("9분", clocks = listOf("10:01", "10:07", "10:14")),
            ),
        )
        assertTrue(gate.accept(subway("5분")))
        assertTrue(gate.accept(subway("2분")))
        assertTrue(gate.accept(subway("곧")))
        assertTrue(gate.isArrivedOnly(arrived()))
        gate.rememberNaverSubwayClocks(arrived())
        val next = subway("2분")
        gate.rememberNaverSubwayClocks(next)
        assertTrue(gate.accept(next))
    }

    @Test
    fun `soon right after arrived is the same train`() {
        val gate = NavigationEventSpeechGate()
        assertTrue(gate.accept(subway("곧")))
        gate.rememberNaverSubwayClocks(arrived())
        val flicker = soon()
        gate.rememberNaverSubwayClocks(flicker)
        assertFalse(gate.accept(flicker))
    }

    @Test
    fun `field 2026-10-02 relative first train then clocks for the next train`() {
        // 17:13 오금행 (1분) → (곧 도착) → (도착) → 17:15 오금행 (17:20) 5분 → (2분) → (1분).
        val gate = NavigationEventSpeechGate()
        val first = subway("곧")
        gate.rememberNaverSubwayClocks(first)
        assertTrue(gate.accept(first))
        gate.rememberNaverSubwayClocks(arrived())
        val five = subway("5분", clocks = listOf("17:20", "17:30", "17:37"))
        gate.rememberNaverSubwayClocks(five)
        assertTrue(gate.accept(five))
        val two = subway("2분")
        gate.rememberNaverSubwayClocks(two)
        assertTrue(gate.accept(two))
        val soon = subway("곧")
        gate.rememberNaverSubwayClocks(soon)
        assertTrue(gate.accept(soon))
    }

    @Test
    fun `field 2026-10-02 stale arrived does not eat the next train`() {
        // 17:21 오금행 (17:30) 9분 → 17:22 stale (도착) → 17:22 (17:30) again → 17:27 (2분).
        val gate = NavigationEventSpeechGate()
        val nine = subway("9분", clocks = listOf("17:30", "17:37", "17:46"))
        gate.rememberNaverSubwayClocks(nine)
        assertTrue(gate.accept(nine))
        gate.rememberNaverSubwayClocks(arrived())
        val again = subway("8분", clocks = listOf("17:30", "17:37", "17:46"))
        gate.rememberNaverSubwayClocks(again)
        assertFalse(gate.accept(again))
        val two = subway("2분")
        gate.rememberNaverSubwayClocks(two)
        assertTrue(gate.accept(two))
        val soon = subway("곧")
        gate.rememberNaverSubwayClocks(soon)
        assertTrue(gate.accept(soon))
    }

    @Test
    fun `arrived then later clock head reopens the ladder`() {
        val gate = NavigationEventSpeechGate()
        assertTrue(
            gate.acceptNaverSubwayWalkBrief(
                subway("9분", clocks = listOf("10:01", "10:07", "10:14")),
            ),
        )
        assertTrue(gate.accept(subway("2분")))
        assertTrue(gate.accept(subway("곧")))
        assertFalse(gate.accept(arrived()))
        assertTrue(
            gate.accept(subway("6분", clocks = listOf("10:07", "10:14", "10:27"))),
        )
    }

    @Test
    fun `a later clock head is the next train and reopens the ladder`() {
        val gate = NavigationEventSpeechGate()
        assertTrue(
            gate.acceptNaverSubwayWalkBrief(
                subway("9분", clocks = listOf("10:01", "10:07", "10:14")),
            ),
        )
        assertTrue(
            gate.accept(subway("6분", clocks = listOf("10:07", "10:14", "10:27"))),
        )
    }

    @Test
    fun `곧 도착 does not reset`() {
        val gate = NavigationEventSpeechGate()
        assertTrue(
            gate.acceptNaverSubwayWalkBrief(
                subway("9분", clocks = listOf("10:01", "10:07", "10:14")),
            ),
        )
        assertTrue(gate.accept(soon()))
        assertTrue(
            gate.accept(subway("6분", clocks = listOf("10:07", "10:14", "10:27"))),
        )
    }

    @Test
    fun `relative eta jump does not reset`() {
        val gate = NavigationEventSpeechGate()
        assertTrue(gate.accept(subway("곧")))
        assertFalse(gate.accept(subway("9분")))
    }

    private fun subway(
        eta: String,
        clocks: List<String> = emptyList(),
        arrived: Boolean = false,
    ) = NavigationEvent(
        source = NavigationEventSource.NAVER,
        type = NavigationEventType.TRANSIT,
        notificationId = 301,
        channel = "302_PUBTRANS_POPUP",
        title = "정발산역 3호선 도보 후 열차 승차",
        action = "정발산역 3호선 도보 후 열차 승차",
        distanceMeters = null,
        rawText = NaverMapsTransit.KIND_SUBWAY,
        busInfo = NavigationBusInfo(
            raw = "3호선 $eta",
            arrivals = listOf(NavigationBusArrival("3호선", eta)),
            clocks = clocks,
            arrived = arrived,
        ),
        timestampMillis = 0L,
    )

    private fun arrived() = subway("도착", arrived = true)

    private fun soon() = subway("곧", arrived = false)
}
