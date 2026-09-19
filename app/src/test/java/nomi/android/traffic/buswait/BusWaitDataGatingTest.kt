package nomi.android.traffic.buswait

import nomi.product.nav.NavigationBusArrival
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Constitution 6-3 / 6-5: a stage speaks only if the raw ETA landed in it, and a
 * stage the ETA never reached must not be logged as one we already spoke.
 * Observed rides that reach this path are in V2 appendix G-2.
 */
class BusWaitDataGatingTest {

    @Test
    fun `a walk down the ladder skips nothing`() {
        val core = BusWaitCore()
        core.seed("81")
        val ten = core.observe(listOf(bus("81", "9분")), 1_000L)!!
        assertEquals(10, ten.speakStage)
        assertEquals(emptyList<Int>(), ten.skippedStages)
    }

    @Test
    fun `first reading at two closes ten and five without speaking them`() {
        val core = BusWaitCore()
        core.seed("81")
        val tick = core.observe(listOf(bus("81", "2분")), 1_000L)!!
        assertEquals(2, tick.speakStage)
        assertEquals(listOf(10, 5), tick.skippedStages)
    }

    @Test
    fun `first reading at soon closes the whole ladder above it`() {
        val core = BusWaitCore()
        core.seed("88B")
        val tick = core.observe(listOf(bus("88B", "곧 도착")), 1_000L)!!
        assertEquals(1, tick.speakStage)
        assertEquals(listOf(10, 5, 2), tick.skippedStages)
    }

    @Test
    fun `a stage already spoken is not counted as skipped`() {
        val core = BusWaitCore()
        core.seed("81")
        assertEquals(10, core.observe(listOf(bus("81", "6분")), 1_000L)!!.speakStage)
        val two = core.observe(listOf(bus("81", "2분")), 130_000L)!!
        assertEquals(2, two.speakStage)
        assertEquals(listOf(5), two.skippedStages)
    }

    @Test
    fun `a stage the eta never reached is source never reached`() {
        val core = BusWaitCore()
        core.seed("81")
        assertEquals(2, core.observe(listOf(bus("81", "2분")), 1_000L)!!.speakStage)
        val rise = core.observe(listOf(bus("81", "4분")), 2_000L)!!
        assertNull(rise.speakStage)
        assertEquals(BusWaitSilence.SOURCE_NEVER_REACHED, rise.silence)
    }

    @Test
    fun `a stage the eta walked through stays already spoken`() {
        val core = BusWaitCore()
        core.seed("81")
        assertEquals(10, core.observe(listOf(bus("81", "10분")), 1_000L)!!.speakStage)
        val again = core.observe(listOf(bus("81", "9분")), 1_001L)!!
        assertNull(again.speakStage)
        assertEquals(BusWaitSilence.STAGE_ALREADY, again.silence)
    }

    @Test
    fun `a stage the gap silenced is not blamed on the source`() {
        val core = BusWaitCore()
        core.seed("81")
        core.noteSpoken(1_000L)
        val held = core.observe(listOf(bus("81", "6분")), 13_000L)!!
        assertEquals(BusWaitSilence.STAGE_COOLDOWN, held.silence)
        val rise = core.observe(listOf(bus("81", "7분")), 13_500L)!!
        assertNull(rise.speakStage)
        assertEquals(BusWaitSilence.STAGE_ALREADY, rise.silence)
    }

    @Test
    fun `the next vehicle reopens stages the previous one never reached`() {
        val core = BusWaitCore()
        core.seed("88B")
        val soon = core.observe(listOf(bus("88B", "곧 도착")), 1_000L)!!
        assertEquals(listOf(10, 5, 2), soon.skippedStages)
        val next = core.observe(listOf(bus("88B", "8분")), 70_000L)!!
        assertEquals(10, next.speakStage)
        assertEquals(emptyList<Int>(), next.skippedStages)
        val rise = core.observe(listOf(bus("88B", "9분")), 71_000L)!!
        assertEquals(BusWaitSilence.STAGE_ALREADY, rise.silence)
    }

    private fun bus(
        line: String,
        eta: String,
        occupancy: String? = "여유",
        stops: Int? = null,
    ) = NavigationBusArrival(line, eta, occupancy, stops)
}
