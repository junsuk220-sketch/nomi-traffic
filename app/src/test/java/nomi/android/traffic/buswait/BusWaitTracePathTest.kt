package nomi.android.traffic.buswait

import nomi.product.nav.NavigationBusArrival
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Path names only. speakStage / target / switched stay as today's core. */
class BusWaitTracePathTest {

    @Test
    fun `no pin still has no tick`() {
        val core = BusWaitCore()
        assertNull(core.observe(listOf(bus("15", "2분"))))
    }

    @Test
    fun `first track is initial`() {
        val core = BusWaitCore()
        core.seed("15")
        val tick = core.observe(listOf(bus("15", "2분"), bus("15", "16분"), bus("66", "20분")), 1_000L)!!
        assertEquals("15", tick.target!!.line)
        assertEquals("2분", tick.target!!.eta)
        assertFalse(tick.switched)
        assertEquals(2, tick.speakStage)
        assertEquals(BusWaitTracePath.INITIAL, tick.path)
    }

    @Test
    fun `same vehicle keep is keep same`() {
        val core = BusWaitCore()
        core.seed("15")
        assertEquals(5, core.observe(listOf(bus("15", "5분")), 1_000L)!!.speakStage)
        val keep = core.observe(listOf(bus("15", "4분")), 1_001L)!!
        assertEquals("15", keep.target!!.line)
        assertNull(keep.speakStage)
        assertEquals(BusWaitTracePath.KEEP_SAME, keep.path)
    }

    @Test
    fun `partial board path is hold absent`() {
        val core = BusWaitCore()
        core.seed("67")
        core.observe(listOf(bus("67", "4분"), bus("98", "9분")), 1_000L)
        val hold = core.observe(listOf(bus("98", "곧 도착")), 3_000L)!!
        assertEquals("67", hold.target!!.line)
        assertNull(hold.speakStage)
        assertEquals(BusWaitTracePath.HOLD_ABSENT, hold.path)
    }

    @Test
    fun `unsettled path is hold unsettled`() {
        val core = BusWaitCore()
        core.seed("88B")
        core.observe(listOf(bus("88B", "곧 도착")), 1_000L)
        val hold = core.observe(listOf(bus("88B", "2분"), bus("88B", "8분")), 6_000L)!!
        assertNull(hold.speakStage)
        assertEquals(BusWaitTracePath.HOLD_UNSETTLED, hold.path)
    }

    @Test
    fun `pin-outside snapshot path is hold no pinned rows`() {
        val core = BusWaitCore()
        core.seed("81")
        core.observe(listOf(bus("81", "4분"), bus("99", "5분")), 1_000L)
        val hold = core.observe(listOf(bus("95", "1분")), 2_000L)!!
        assertEquals("81", hold.target!!.line)
        assertEquals(BusWaitTracePath.HOLD_NO_PINNED_ROWS, hold.path)
    }

    @Test
    fun `faster alternate path does not change the selected bus`() {
        val core = BusWaitCore()
        core.seed("81")
        val first = core.observe(listOf(bus("81", "5분"), bus("99", "8분")), 1_000L)!!
        assertEquals("81", first.target!!.line)
        assertEquals(5, first.speakStage)
        val next = core.observe(listOf(bus("81", "6분"), bus("99", "3분")), 2_000L)!!
        assertEquals("99", next.target!!.line)
        assertEquals(5, next.speakStage)
        assertEquals(true, next.switched)
        assertEquals(BusWaitTracePath.FASTER_ALTERNATE, next.path)
    }

    @Test
    fun `same-line miss path is no same vehicle`() {
        val core = BusWaitCore()
        core.seed("15")
        val first = core.observe(listOf(bus("15", "2분"), bus("15", "16분"), bus("66", "20분")), 1_000L)!!
        assertEquals("15", first.target!!.line)
        assertEquals("2분", first.target!!.eta)
        val next = core.observe(listOf(bus("15", "16분"), bus("66", "20분")), 2_000L)!!
        assertEquals(true, next.switched)
        assertEquals(BusWaitTracePath.NO_SAME_VEHICLE, next.path)
        assertEquals("15", next.target!!.line)
        assertEquals("16분", next.target!!.eta)
    }

    private fun bus(line: String, eta: String) =
        NavigationBusArrival(line, eta, "여유", null)
}
