package nomi.android.traffic

import nomi.product.nav.NavigationBusArrival
import nomi.product.nav.NavigationBusInfo
import nomi.product.nav.NavigationEvent
import nomi.product.nav.NavigationEventSource
import nomi.product.nav.NavigationEventType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Constitution 8-2 · 부록 E: the subway pin is 정책 tier and the bus ladder is
 * 확정 tier, so pinning a subway line has no way to clear bus stages. The two
 * only move together through [NaverTripPin], which is where a subway-first trip
 * asks the bus wait to close itself.
 */
class NaverSubwayPinTest {

    private lateinit var tracker: NaverBusWaitTracker

    @Before
    fun setUp() {
        NaverSubwayPin.reset()
        tracker = NaverBusWaitTracker()
    }

    @After
    fun tearDown() {
        NaverSubwayPin.reset()
    }

    @Test
    fun `an unpinned journey allows any line`() {
        assertNull(NaverSubwayPin.pinned())
        assertTrue(NaverSubwayPin.allows("3호선"))
        assertTrue(NaverSubwayPin.allows("경의중앙선"))
    }

    @Test
    fun `a pinned journey allows only its own line`() {
        NaverSubwayPin.pin("3호선")
        assertEquals("3호선", NaverSubwayPin.pinned())
        assertTrue(NaverSubwayPin.allows(" 3호선 "))
        assertFalse(NaverSubwayPin.allows("경의중앙선"))
    }

    @Test
    fun `a blank line does not pin`() {
        NaverSubwayPin.pin("   ")
        assertNull(NaverSubwayPin.pinned())
    }

    @Test
    fun `pinning a subway line cannot clear the bus ladder`() {
        tracker.pinBusLine("81")
        assertEquals(10, tracker.onNotification(rows("9분"), nowMs = 1_000L)!!.speakStage)

        NaverSubwayPin.pin("3호선")

        assertEquals(setOf("81"), tracker.pinnedBusLines())
        assertFalse(tracker.isClosedForSubwayTrip())
        assertNull(tracker.onNotification(rows("8분"), nowMs = 2_000L)!!.speakStage)
    }

    @Test
    fun `a subway first trip pins the line and closes the bus wait`() {
        assertEquals("subway=3호선", NaverTripPin.apply(tripStart("3호선", subway = true), tracker))
        assertEquals("3호선", NaverSubwayPin.pinned())
        assertTrue(tracker.isClosedForSubwayTrip())
        assertTrue(tracker.pinnedBusLines().isEmpty())
    }

    @Test
    fun `a bus first trip seeds the bus wait and pins no subway`() {
        assertEquals("bus seed=81", NaverTripPin.apply(tripStart("81", subway = false), tracker))
        assertNull(NaverSubwayPin.pinned())
        assertFalse(tracker.isClosedForSubwayTrip())
        assertEquals(setOf("81"), tracker.pinnedBusLines())
    }

    @Test
    fun `a trip start without a line pins nothing`() {
        assertNull(NaverTripPin.apply(tripStart("  ", subway = true), tracker))
        assertNull(NaverSubwayPin.pinned())
        assertFalse(tracker.isClosedForSubwayTrip())
    }

    @Test
    fun `a transfer naming the bus reopens the bus wait`() {
        NaverTripPin.apply(tripStart("3호선", subway = true), tracker)
        assertTrue(tracker.isClosedForSubwayTrip())
        NaverTripPin.apply(tripStart("67", subway = false), tracker)
        assertFalse(tracker.isClosedForSubwayTrip())
        assertEquals(2, tracker.onNotification(rows("2분", "67"), nowMs = 1_000L)!!.speakStage)
    }

    private fun rows(eta: String, line: String = "81") =
        listOf(NavigationBusArrival(line, eta, "여유", null))

    private fun tripStart(line: String, subway: Boolean) = NavigationEvent(
        source = NavigationEventSource.NAVER,
        type = NavigationEventType.TRANSIT,
        notificationId = 301,
        channel = "302_PUBTRANS_POPUP",
        title = NaverMapsTransit.TRIP_START_ACTION,
        action = NaverMapsTransit.TRIP_START_ACTION,
        distanceMeters = null,
        rawText = if (subway) NaverMapsTransit.KIND_SUBWAY else NaverMapsTransit.KIND_BUS,
        busInfo = NavigationBusInfo(
            raw = "$line 5분",
            arrivals = listOf(NavigationBusArrival(line, "5분")),
        ),
        timestampMillis = 0L,
    )
}
