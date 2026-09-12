package nomi.android.traffic.buswait

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** D-6: fold repeated skip lines; heartbeat only repeats the same line with elapsed. */
class BusWaitSilenceLogTest {

    @Test
    fun `first skip emits immediately`() {
        val next = BusWaitSilenceLog.next(BusWaitSilenceLog.State(), "skip reason=NOT_A_STAGE", 1_000L, HEART)
        assertEquals("skip reason=NOT_A_STAGE", next.line)
        assertEquals("skip reason=NOT_A_STAGE", next.state.message)
    }

    @Test
    fun `same skip before heartbeat is folded`() {
        val first = BusWaitSilenceLog.next(BusWaitSilenceLog.State(), "skip reason=STAGE_COOLDOWN", 1_000L, HEART)
        val again = BusWaitSilenceLog.next(first.state, "skip reason=STAGE_COOLDOWN", 1_000L + HEART - 1, HEART)
        assertNull(again.line)
        assertEquals(first.state, again.state)
    }

    @Test
    fun `same skip at heartbeat keeps the reason and adds elapsed`() {
        val first = BusWaitSilenceLog.next(BusWaitSilenceLog.State(), "skip reason=STAGE_COOLDOWN", 1_000L, HEART)
        val later = BusWaitSilenceLog.next(first.state, "skip reason=STAGE_COOLDOWN", 1_000L + HEART, HEART)
        assertEquals("skip reason=STAGE_COOLDOWN silentForMs=$HEART", later.line)
        assertEquals("skip reason=STAGE_COOLDOWN", later.state.message)
        assertEquals(1_000L, later.state.startedAtMs)
        assertEquals(1_000L + HEART, later.state.lastEmitAtMs)
    }

    @Test
    fun `reason change emits immediately without elapsed`() {
        val first = BusWaitSilenceLog.next(BusWaitSilenceLog.State(), "skip reason=UNSETTLED_FEED", 1_000L, HEART)
        val next = BusWaitSilenceLog.next(first.state, "skip reason=STAGE_ALREADY", 1_500L, HEART)
        assertEquals("skip reason=STAGE_ALREADY", next.line)
        assertEquals(1_500L, next.state.startedAtMs)
    }

    companion object {
        private const val HEART = 10_000L
    }
}
