package nomi.android.traffic.buswait

import nomi.product.nav.NavigationBusArrival
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * D-6: lock silence reasons only. speakStage / switched / target stay as today.
 */
class BusWaitSilenceTest {

    @Test
    fun `spoken tick has no silence`() {
        val core = BusWaitCore()
        core.seed("81")
        val tick = core.observe(listOf(bus("81", "5분")), 1_000L)!!
        assertEquals(5, tick.speakStage)
        assertNull(tick.silence)
    }

    @Test
    fun `no pin still returns null without a silence code`() {
        val core = BusWaitCore()
        assertNull(core.observe(listOf(bus("81", "5분"))))
    }

    @Test
    fun `snapshot with no pinned row is candidate-outside`() {
        val core = BusWaitCore()
        core.seed("81")
        core.observe(listOf(bus("81", "4분"), bus("99", "5분")), 1_000L)
        val only95 = core.observe(listOf(bus("95", "1분")), 2_000L)!!
        assertEquals("81", only95.target!!.line)
        assertNull(only95.speakStage)
        assertEquals(BusWaitSilence.NO_PINNED_ROWS, only95.silence)
    }

    @Test
    fun `partial board hold is absent hold`() {
        val core = BusWaitCore()
        core.seed("67")
        assertEquals(5, core.observe(listOf(bus("67", "4분"), bus("98", "9분")), 1_000L)!!.speakStage)
        val partial = core.observe(listOf(bus("98", "곧 도착")), 3_000L)!!
        assertEquals("67", partial.target!!.line)
        assertNull(partial.speakStage)
        assertEquals(BusWaitSilence.ABSENT_HOLD, partial.silence)
    }

    @Test
    fun `lagging feed wait is unsettled`() {
        val core = BusWaitCore()
        core.seed("88B")
        assertEquals(1, core.observe(listOf(bus("88B", "곧 도착")), 1_000L)!!.speakStage)
        val lag = core.observe(listOf(bus("88B", "2분"), bus("88B", "8분")), 6_000L)!!
        assertNull(lag.speakStage)
        assertEquals(BusWaitSilence.UNSETTLED_FEED, lag.silence)
    }

    @Test
    fun `eta drop confirmation wait is unsettled`() {
        val core = BusWaitCore()
        core.seed("88B")
        assertEquals(5, core.observe(listOf(bus("88B", "5분")), 1_000L)!!.speakStage)
        val flip = core.observe(listOf(bus("88B", "곧 도착")), 1_700L)!!
        assertNull(flip.speakStage)
        assertEquals(BusWaitSilence.UNSETTLED_FEED, flip.silence)
    }

    @Test
    fun `minutes above 10 are not a stage`() {
        val core = BusWaitCore()
        core.seed("81")
        val tick = core.observe(listOf(bus("81", "14분")), 1_000L)!!
        assertNull(tick.speakStage)
        assertEquals(BusWaitSilence.NOT_A_STAGE, tick.silence)
    }

    @Test
    fun `same stage again is already spoken`() {
        val core = BusWaitCore()
        core.seed("81")
        assertEquals(10, core.observe(listOf(bus("81", "10분")), 1_000L)!!.speakStage)
        val again = core.observe(listOf(bus("81", "9분")), 1_001L)!!
        assertNull(again.speakStage)
        assertEquals(BusWaitSilence.STAGE_ALREADY, again.silence)
    }

    @Test
    fun `briefing cooldown is cooldown not already spoken`() {
        val core = BusWaitCore()
        core.seed("81")
        core.noteSpoken(1_000L)
        val mid = core.observe(listOf(bus("81", "6분")), 13_000L)!!
        assertNull(mid.speakStage)
        assertEquals(BusWaitSilence.STAGE_COOLDOWN, mid.silence)
    }

    @Test
    fun `bounce switch back to six after soon is already spoken`() {
        val core = BusWaitCore()
        core.seed("88B")
        core.observe(listOf(bus("88B", "곧 도착"), bus("88B", "7분")), 1_000L)
        core.observe(listOf(bus("88B", "6분"), bus("88B", "18분")), 4_000L)
        core.observe(listOf(bus("88B", "곧 도착"), bus("88B", "7분")), 15_000L)
        val bounce = core.observe(listOf(bus("88B", "6분"), bus("88B", "18분")), 15_050L)!!
        assertNull(bounce.speakStage)
        assertEquals(BusWaitSilence.STAGE_ALREADY, bounce.silence)
    }

    @Test
    fun `after cooldown consumed the stage the next same stage is already spoken`() {
        val core = BusWaitCore()
        core.seed("81")
        core.noteSpoken(1_000L)
        assertNull(core.observe(listOf(bus("81", "6분")), 13_000L)!!.speakStage)
        val again = core.observe(listOf(bus("81", "5분")), 60_000L)!!
        assertNull(again.speakStage)
        assertEquals(BusWaitSilence.STAGE_COOLDOWN, again.silence)
    }

    private fun bus(
        line: String,
        eta: String,
        occupancy: String? = "여유",
        stops: Int? = null,
    ) = NavigationBusArrival(line, eta, occupancy, stops)
}
