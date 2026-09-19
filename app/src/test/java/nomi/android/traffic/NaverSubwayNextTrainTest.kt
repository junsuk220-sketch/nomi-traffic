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
 * Constitution: subway stages reopen only after `(도착)` and a later clock head.
 */
class NaverSubwayNextTrainTest {

    @Test
    fun `countdown through arrived does not reset stages`() {
        val gate = NavigationEventSpeechGate()
        assertTrue(
            gate.acceptNaverSubwayWalkBrief(
                subway("9분", clocks = listOf("10:01", "10:07", "10:14")),
            ),
        )
        assertTrue(gate.accept(subway("5분")))
        assertTrue(gate.accept(subway("2분")))
        assertTrue(gate.accept(subway("곧")))
        assertFalse(gate.accept(arrived()))
        assertFalse(gate.accept(subway("2분")))
        assertFalse(gate.accept(subway("9분")))
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
    fun `clock head moving without arrived does not reset`() {
        val gate = NavigationEventSpeechGate()
        assertTrue(
            gate.acceptNaverSubwayWalkBrief(
                subway("9분", clocks = listOf("10:01", "10:07", "10:14")),
            ),
        )
        assertFalse(
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
        assertFalse(
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
