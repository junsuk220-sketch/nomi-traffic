package nomi.android.traffic

import nomi.android.traffic.buswait.BusWaitCore
import nomi.android.traffic.buswait.BusWaitFieldTrace
import nomi.android.traffic.buswait.BusWaitSilence
import nomi.product.nav.NavigationBusArrival
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Sheet-ownership adapter around [nomi.android.traffic.buswait.BusWaitCore].
 * Pin/track/stage constitution tests live in BusWaitCoreTest.
 */
class NaverBusWaitTrackerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun attachTrace() {
        BusWaitFieldTrace.resetForTest(tmp.root)
    }

    @Test
    fun `without seed notification yields no target`() {
        val tracker = NaverBusWaitTracker()
        val snap = tracker.onNotification(listOf(bus("81", "5분")))
        assertNull(snap!!.target)
        assertNull(snap.speakStage)
    }

    @Test
    fun `notification speaks stage even while sheet owns`() {
        val tracker = NaverBusWaitTracker()
        tracker.pinBusLine("99")
        val t0 = 1_000_000L
        tracker.onSheet(listOf(bus("99", "5분", stops = 5)), t0)
        assertTrue(tracker.hasSheetTarget())
        val fromNotif = tracker.onNotification(
            listOf(bus("99", "2분"), bus("81", "10분")),
            nowMs = t0 + BusWaitCore.STAGE_COOLDOWN_MS,
        )
        assertEquals("99", fromNotif!!.target!!.line)
        assertEquals("2분", fromNotif.target!!.eta)
        assertEquals(2, fromNotif.speakStage)
        assertFalse(tracker.hasSheetTarget())
    }

    @Test
    fun `empty sheet does not freshen ownership`() {
        val tracker = NaverBusWaitTracker()
        tracker.pinBusLine("81")
        tracker.onSheet(listOf(bus("81", "5분", stops = 5)), 1_000L)
        assertTrue(tracker.hasSheetTarget())
        tracker.releaseSheetOwnership()
        assertFalse(tracker.hasSheetTarget())
        tracker.onSheet(emptyList(), 1_001L)
        assertFalse(tracker.hasSheetTarget())
        val fromNotif = tracker.onNotification(
            listOf(bus("81", "2분")),
            nowMs = 1_000L + BusWaitCore.STAGE_COOLDOWN_MS,
        )
        assertEquals(2, fromNotif!!.speakStage)
    }

    @Test
    fun `release sheet keeps pin so next bus can speak`() {
        val tracker = NaverBusWaitTracker()
        tracker.pinBusLine("67")
        assertEquals(1, tracker.onSheet(listOf(bus("67", "곧 도착")))!!.speakStage)
        tracker.releaseSheetOwnership()
        assertFalse(tracker.hasSheetTarget())
        assertEquals(setOf("67"), tracker.pinnedBusLines())
        val next = tracker.onNotification(listOf(bus("67", "8분"), bus("83", "2분")))!!
        assertTrue(next.switched)
        assertEquals("83", next.target!!.line)
        assertEquals(2, next.speakStage)
        assertTrue(tracker.pinnedBusLines().contains("83"))
    }

    @Test
    fun `core silence passes through snapshot`() {
        val tracker = NaverBusWaitTracker()
        tracker.pinBusLine("81")
        val snap = tracker.onNotification(listOf(bus("81", "14분")), nowMs = 1_000L)!!
        assertNull(snap.speakStage)
        assertEquals(BusWaitSilence.NOT_A_STAGE, snap.silence)
    }

    @Test
    fun `other-stop skip is not a core silence`() {
        val tracker = NaverBusWaitTracker()
        tracker.pinBusLine("81")
        tracker.onNotification(listOf(bus("81", "5분")), stop = "라페스타.먹자골목")
        val stale = tracker.onNotification(
            listOf(bus("67", "곧 도착")),
            stop = "일산동부경찰서(중)",
        )
        assertNull(stale)
    }

    @Test
    fun `leftover board from another stop never drives the wait`() {
        val tracker = NaverBusWaitTracker()
        tracker.pinBusLine("81")
        val stale = tracker.onNotification(
            listOf(bus("67", "곧 도착"), bus("83", "2분")),
            stop = "일산동부경찰서(중)",
        )
        assertNull(stale)
        assertEquals(setOf("81"), tracker.pinnedBusLines())
        val mine = tracker.onNotification(
            listOf(bus("81", "5분"), bus("99", "8분")),
            stop = "라페스타.먹자골목",
        )!!
        assertEquals("81", mine.target!!.line)
        assertEquals(5, mine.speakStage)
        assertNull(
            tracker.onNotification(listOf(bus("67", "곧 도착")), stop = "일산동부경찰서(중)"),
        )
        assertFalse(tracker.pinnedBusLines().contains("67"))
    }

    @Test
    fun `subway first trip keeps bus silent until transfer names the bus`() {
        val tracker = NaverBusWaitTracker()
        tracker.closeForSubwayTrip()
        assertTrue(tracker.isClosedForSubwayTrip())
        assertTrue(tracker.pinnedBusLines().isEmpty())
        tracker.pinBusLine("67")
        assertFalse(tracker.isClosedForSubwayTrip())
        assertEquals(2, tracker.onNotification(listOf(bus("67", "2분")))!!.speakStage)
    }

    @Test
    fun `leave clears seed`() {
        val tracker = NaverBusWaitTracker()
        tracker.pinBusLine("81")
        tracker.onSheet(listOf(bus("81", "5분")))
        tracker.leave()
        assertTrue(tracker.pinnedBusLines().isEmpty())
        assertNull(tracker.onNotification(listOf(bus("81", "2분")))!!.target)
    }

    @Test
    fun `sheet trace keeps arrival order and source`() {
        val tracker = NaverBusWaitTracker()
        tracker.pinBusLine("15")
        tracker.onSheet(
            listOf(bus("15", "2분"), bus("15", "16분"), bus("66", "20분")),
            nowMs = 1_000L,
        )
        val line = BusWaitFieldTrace.file().readText()
        assertTrue(line.contains("\"src\":\"sheet\""))
        val seen = line.indexOf("\"seen\":")
        assertTrue(line.indexOf("\"15\",\"eta\":\"2분\"", seen) < line.indexOf("\"15\",\"eta\":\"16분\"", seen))
        assertTrue(line.indexOf("\"15\",\"eta\":\"16분\"", seen) < line.indexOf("\"66\",\"eta\":\"20분\"", seen))
    }

    @Test
    fun `notification trace keeps stop`() {
        val tracker = NaverBusWaitTracker()
        tracker.pinBusLine("15")
        tracker.onNotification(
            listOf(bus("15", "5분")),
            stop = "일산동부경찰서(중)",
            nowMs = 1_000L,
        )
        val line = BusWaitFieldTrace.file().readText()
        assertTrue(line.contains("\"src\":\"notification\""))
        assertTrue(line.contains("일산동부경찰서(중)"))
    }

    @Test
    fun `other-stop skip does not write a core path`() {
        val tracker = NaverBusWaitTracker()
        tracker.pinBusLine("81")
        tracker.onNotification(listOf(bus("81", "5분")), stop = "라페스타.먹자골목", nowMs = 1_000L)
        BusWaitFieldTrace.resetForTest(tmp.root)
        val stale = tracker.onNotification(
            listOf(bus("67", "곧 도착")),
            stop = "일산동부경찰서(중)",
            nowMs = 2_000L,
        )
        assertNull(stale)
        assertFalse(BusWaitFieldTrace.file().exists() && BusWaitFieldTrace.file().length() > 0)
    }

    @Test
    fun `miss next bus returns speakStage for new ladder`() {
        val tracker = NaverBusWaitTracker()
        tracker.pinBusLine("81")
        assertEquals(1, tracker.onSheet(listOf(bus("81", "곧 도착")))!!.speakStage)
        val next = tracker.onSheet(listOf(bus("81", "10분"), bus("99", "12분")))!!
        assertTrue(next.switched)
        assertEquals("81", next.target!!.line)
        assertEquals(10, next.speakStage)
    }

    private fun bus(
        line: String,
        eta: String,
        occupancy: String? = "여유",
        stops: Int? = null,
    ) = NavigationBusArrival(line, eta, occupancy, stops)
}
