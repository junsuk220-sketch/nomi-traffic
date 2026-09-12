package nomi.android.traffic.buswait

import nomi.product.nav.NavigationBusArrival
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks docs/BUS_WAIT_CONSTITUTION.md — pin → track → stage.
 */
class BusWaitCoreTest {

    @Test
    fun `no pin means no observe tick`() {
        val core = BusWaitCore()
        assertNull(core.observe(listOf(bus("81", "5분"))))
    }

    @Test
    fun `seed expands to same-stop alternate and tracks soonest`() {
        val core = BusWaitCore()
        core.seed("81")
        val tick = core.observe(listOf(bus("99", "2분"), bus("81", "4분")))!!
        assertEquals(setOf("81", "99"), core.pinnedLines())
        assertEquals("99", tick.target!!.line)
        assertEquals(2, tick.speakStage) // ≤2
    }

    @Test
    fun `later same-stop line joins pin and speaks after miss`() {
        val core = BusWaitCore()
        core.seed("67")
        assertEquals(1, core.observe(listOf(bus("67", "곧 도착"), bus("98", "곧 도착")))!!.speakStage)
        assertEquals(setOf("67", "98"), core.pinnedLines())
        val next = core.observe(
            listOf(
                bus("67", "8분"),
                bus("83", "5분"),
                bus("98", "14분"),
            ),
        )!!
        assertTrue(core.pinnedLines().contains("83"))
        assertTrue(next.switched)
        assertEquals("83", next.target!!.line)
        assertEquals(5, next.speakStage)
    }

    @Test
    fun `foreign line never co-appeared stays out`() {
        val core = BusWaitCore()
        core.seed("81")
        core.observe(listOf(bus("81", "4분"), bus("99", "5분")))
        assertEquals(setOf("81", "99"), core.pinnedLines())
        val only95 = core.observe(listOf(bus("95", "1분")))!!
        assertEquals("81", only95.target!!.line)
        assertNull(only95.speakStage)
        assertFalse(core.pinnedLines().contains("95"))
    }

    @Test
    fun `stages 10 then 5 then 2 then soon once each`() {
        val core = BusWaitCore()
        core.seed("81")
        assertEquals(10, core.observe(listOf(bus("81", "10분")), 1_000L)!!.speakStage)
        assertNull(core.observe(listOf(bus("81", "9분")), 1_001L)!!.speakStage)
        assertEquals(5, core.observe(listOf(bus("81", "5분")), 1_000L + GAP)!!.speakStage)
        assertNull(core.observe(listOf(bus("81", "4분")), 1_000L + GAP + 1)!!.speakStage)
        assertEquals(2, core.observe(listOf(bus("81", "2분")), 1_000L + GAP * 2)!!.speakStage)
        assertEquals(
            1,
            core.observe(listOf(bus("81", "곧 도착")), 1_000L + GAP * 2 + CONFIRM)!!.speakStage,
        )
        assertNull(
            core.observe(listOf(bus("81", "곧 도착")), 1_000L + GAP * 2 + CONFIRM + 1)!!.speakStage,
        )
    }

    @Test
    fun `miss next same line resets stages so 10 can speak again`() {
        val core = BusWaitCore()
        core.seed("81")
        assertEquals(1, core.observe(listOf(bus("81", "곧 도착")))!!.speakStage)
        val next = core.observe(listOf(bus("81", "10분")))!!
        assertTrue(next.switched)
        assertEquals("81", next.target!!.line)
        assertEquals(10, next.speakStage)
    }

    @Test
    fun `soon then 3분 same line is new vehicle`() {
        assertFalse(
            BusWaitCore.isSameVehicle(bus("81", "곧 도착", stops = 1), bus("81", "3분", stops = 3)),
        )
    }

    @Test
    fun `1분 then 2분 stays same vehicle`() {
        assertTrue(
            BusWaitCore.isSameVehicle(
                bus("81", "1분", stops = 1),
                bus("81", "2분", stops = 2),
            ),
        )
        assertFalse(
            BusWaitCore.isSameVehicle(
                bus("81", "1분", stops = 1),
                bus("81", "10분", stops = 8),
            ),
        )
    }

    @Test
    fun `near eta alternate does not steal and does not repeat stage`() {
        val core = BusWaitCore()
        core.seed("98")
        assertEquals(10, core.observe(listOf(bus("98", "9분"), bus("2000", "10분")))!!.speakStage)
        val jitter = core.observe(listOf(bus("2000", "9분"), bus("98", "9분")))!!
        assertEquals("98", jitter.target!!.line)
        assertFalse(jitter.switched)
        assertNull(jitter.speakStage)
        val still = core.observe(listOf(bus("98", "8분"), bus("2000", "8분")))!!
        assertEquals("98", still.target!!.line)
        assertFalse(still.switched)
        assertNull(still.speakStage)
    }

    @Test
    fun `partial board without tracked line holds and says nothing`() {
        val core = BusWaitCore()
        core.seed("67")
        assertEquals(5, core.observe(listOf(bus("67", "4분"), bus("98", "9분")), 1_000L)!!.speakStage)
        val partial = core.observe(listOf(bus("98", "곧 도착")), 3_000L)!!
        assertEquals("67", partial.target!!.line)
        assertFalse(partial.switched)
        assertNull(partial.speakStage)
    }

    @Test
    fun `tracked line gone for long switches to soonest`() {
        val core = BusWaitCore()
        core.seed("67")
        core.observe(listOf(bus("67", "4분"), bus("98", "9분")), 1_000L)
        val gone = core.observe(
            listOf(bus("98", "곧 도착")),
            1_000L + BusWaitCore.ABSENT_HOLD_MS,
        )!!
        assertTrue(gone.switched)
        assertEquals("98", gone.target!!.line)
        assertEquals(1, gone.speakStage)
    }

    @Test
    fun `faster alternate switches and resets stages`() {
        val core = BusWaitCore()
        core.seed("81")
        val first = core.observe(listOf(bus("81", "5분"), bus("99", "8분")))!!
        assertEquals("81", first.target!!.line)
        assertEquals(5, first.speakStage)
        val next = core.observe(listOf(bus("81", "6분"), bus("99", "3분")))!!
        assertTrue(next.switched)
        assertEquals("99", next.target!!.line)
        assertEquals(5, next.speakStage)
    }

    @Test
    fun `leave clears pin`() {
        val core = BusWaitCore()
        core.seed("81")
        core.observe(listOf(bus("81", "5분")))
        core.leave()
        assertTrue(core.pinnedLines().isEmpty())
        assertNull(core.observe(listOf(bus("81", "2분"))))
    }

    @Test
    fun `jump 9 to 3 skips 5 inside the cooldown then 2 after`() {
        val core = BusWaitCore()
        core.seed("81")
        assertEquals(10, core.observe(listOf(bus("81", "9분")), 1_000L)!!.speakStage)
        assertNull(core.observe(listOf(bus("81", "3분")), 1_001L)!!.speakStage)
        assertEquals(2, core.observe(listOf(bus("81", "2분")), 1_000L + GAP)!!.speakStage)
    }

    @Test
    fun `briefing at 6 skips 10 and 5 then speaks 2`() {
        val core = BusWaitCore()
        core.seed("81")
        core.noteSpoken(1_000L)
        assertNull(core.observe(listOf(bus("81", "6분")), 13_000L)!!.speakStage)
        assertNull(core.observe(listOf(bus("81", "5분")), 60_000L)!!.speakStage)
        assertEquals(2, core.observe(listOf(bus("81", "2분")), 1_000L + GAP)!!.speakStage)
        assertEquals(
            1,
            core.observe(listOf(bus("81", "곧 도착")), 1_000L + GAP + CONFIRM)!!.speakStage,
        )
    }

    @Test
    fun `soon speaks inside the cooldown`() {
        val core = BusWaitCore()
        core.seed("81")
        assertEquals(2, core.observe(listOf(bus("81", "2분")), 1_000L)!!.speakStage)
        assertEquals(
            1,
            core.observe(listOf(bus("81", "곧 도착")), 1_000L + CONFIRM)!!.speakStage,
        )
    }

    @Test
    fun `soon then same line 2분 is a lagging feed not the next bus`() {
        val core = BusWaitCore()
        core.seed("88B")
        assertEquals(1, core.observe(listOf(bus("88B", "곧 도착")), 1_000L)!!.speakStage)
        // 302 board still shows the coarse row while the sheet already says 곧.
        val lag = core.observe(listOf(bus("88B", "2분"), bus("88B", "8분")), 6_000L)!!
        assertFalse(lag.switched)
        assertEquals("곧 도착", lag.target!!.eta)
        assertNull(lag.speakStage)
        // The sheet keeps saying 곧, so the 2분 row never settles.
        assertNull(core.observe(listOf(bus("88B", "곧 도착")), 7_000L)!!.speakStage)
        assertNull(
            core.observe(listOf(bus("88B", "2분"), bus("88B", "8분")), 23_000L)!!.speakStage,
        )
    }

    @Test
    fun `next bus speaks once the risen board holds`() {
        val core = BusWaitCore()
        core.seed("88B")
        assertEquals(1, core.observe(listOf(bus("88B", "곧 도착")), 1_000L)!!.speakStage)
        assertNull(core.observe(listOf(bus("88B", "3분")), 2_000L)!!.speakStage)
        val settled = core.observe(listOf(bus("88B", "3분")), 2_000L + CONFIRM)!!
        assertTrue(settled.switched)
        assertEquals("3분", settled.target!!.eta)
        assertEquals(5, settled.speakStage)
    }

    @Test
    fun `sheet dropping five minutes in one second waits for confirmation`() {
        val core = BusWaitCore()
        core.seed("88B")
        assertEquals(5, core.observe(listOf(bus("88B", "5분")), 1_000L)!!.speakStage)
        val flip = core.observe(listOf(bus("88B", "곧 도착")), 1_700L)!!
        assertEquals("5분", flip.target!!.eta)
        assertNull(flip.speakStage)
        assertNull(core.observe(listOf(bus("88B", "5분")), 2_200L)!!.speakStage)
        assertNull(core.observe(listOf(bus("88B", "곧 도착")), 2_700L)!!.speakStage)
    }

    @Test
    fun `switch ignores cooldown so the new bus can speak`() {
        val core = BusWaitCore()
        core.seed("81")
        assertEquals(5, core.observe(listOf(bus("81", "5분"), bus("99", "8분")), 1_000L)!!.speakStage)
        val next = core.observe(listOf(bus("81", "6분"), bus("99", "3분")), 2_000L)!!
        assertTrue(next.switched)
        assertEquals("99", next.target!!.line)
        assertEquals(5, next.speakStage)
    }

    private fun bus(
        line: String,
        eta: String,
        occupancy: String? = "여유",
        stops: Int? = null,
    ) = NavigationBusArrival(line, eta, occupancy, stops)

    companion object {
        private const val GAP = BusWaitCore.STAGE_COOLDOWN_MS
        private const val CONFIRM = BusWaitCore.CONFIRM_MS
    }
}
