package nomi.android.traffic

import nomi.android.traffic.buswait.BusWaitSilence
import nomi.product.nav.NavigationBusArrival
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Constitution 6-2 · 8-2: the bus stage set is cleared only when the tracked
 * vehicle changes or guidance ends. Every other per-journey reset — subway,
 * transfer, prepare-alight, trip-start, sheet ownership — must leave it alone,
 * in any order and any number of times. This is the v83/v84 regression class:
 * a cue's reset wiping stages the bus had already spoken.
 */
class NaverResetIsolationTest {

    private lateinit var gate: NavigationEventSpeechGate
    private lateinit var tracker: NaverBusWaitTracker

    @Before
    fun setUp() {
        NaverNearBoardNotice.reset()
        gate = NavigationEventSpeechGate()
        tracker = NaverBusWaitTracker()
        tracker.pinBusLine("81")
    }

    /** Every reset that is not "guidance ended", in a deliberately odd order. */
    private fun allOtherResets() {
        gate.resetNaverSubwayStages()
        gate.resetNaverPrepareAlight()
        tracker.releaseSheetOwnership()
        gate.resetNaverTransferCues()
        gate.resetNaverTripStart()
        gate.resetTransferSubwayBrief()
        gate.resetNaverSubwayBoardBriefs()
        gate.resetNaverJourneyCues()
        gate.resetGoogleTripStart()
    }

    @Test
    fun `a spoken stage stays consumed through every other reset`() {
        assertEquals(10, tracker.onNotification(rows("9분"), nowMs = 1_000L)!!.speakStage)
        allOtherResets()
        val again = tracker.onNotification(rows("8분"), nowMs = 2_000L)!!
        assertNull(again.speakStage)
        assertEquals(BusWaitSilence.STAGE_ALREADY, again.silence)
    }

    @Test
    fun `the ladder keeps descending after every other reset`() {
        assertEquals(10, tracker.onNotification(rows("9분"), nowMs = 1_000L)!!.speakStage)
        allOtherResets()
        assertEquals(5, tracker.onNotification(rows("4분"), nowMs = 130_000L)!!.speakStage)
        allOtherResets()
        assertEquals(2, tracker.onNotification(rows("2분"), nowMs = 260_000L)!!.speakStage)
    }

    @Test
    fun `resets repeated in reverse order still change nothing`() {
        assertEquals(10, tracker.onNotification(rows("9분"), nowMs = 1_000L)!!.speakStage)
        repeat(3) {
            gate.resetGoogleTripStart()
            gate.resetNaverJourneyCues()
            gate.resetTransferSubwayBrief()
            gate.resetNaverTripStart()
            gate.resetNaverTransferCues()
            tracker.releaseSheetOwnership()
            gate.resetNaverPrepareAlight()
            gate.resetNaverSubwayStages()
        }
        assertNull(tracker.onNotification(rows("8분"), nowMs = 2_000L)!!.speakStage)
        assertEquals(setOf("81"), tracker.pinnedBusLines())
    }

    @Test
    fun `the pin survives sheet ownership being released`() {
        assertEquals(10, tracker.onNotification(rows("9분"), nowMs = 1_000L)!!.speakStage)
        tracker.releaseSheetOwnership()
        assertEquals(setOf("81"), tracker.pinnedBusLines())
        assertEquals(2, tracker.onNotification(rows("2분"), nowMs = 130_000L)!!.speakStage)
    }

    @Test
    fun `only guidance ended clears the stages`() {
        assertEquals(10, tracker.onNotification(rows("9분"), nowMs = 1_000L)!!.speakStage)
        tracker.leave()
        assertEquals(emptySet<String>(), tracker.pinnedBusLines())
        tracker.pinBusLine("81")
        assertEquals(10, tracker.onNotification(rows("9분"), nowMs = 2_000L)!!.speakStage)
    }

    @Test
    fun `a subway stage reset does not reopen a bus stage`() {
        assertEquals(10, tracker.onNotification(rows("9분"), nowMs = 1_000L)!!.speakStage)
        assertEquals(true, gate.acceptNaverSubwayMinutes(9))
        gate.resetNaverSubwayStages()
        // The subway ladder reopened; the bus ladder must not have.
        assertEquals(true, gate.acceptNaverSubwayMinutes(9))
        assertNull(tracker.onNotification(rows("8분"), nowMs = 2_000L)!!.speakStage)
    }

    private fun rows(eta: String) = listOf(NavigationBusArrival("81", eta, "여유", null))
}
