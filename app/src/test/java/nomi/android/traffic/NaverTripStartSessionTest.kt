package nomi.android.traffic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NaverTripStartSessionTest {

    @Before
    fun reset() {
        NaverTripStartSession.reset()
    }

    @Test
    fun `click live and notification arm only once`() {
        val t0 = 1_000L
        assertTrue(NaverTripStartSession.request(t0))
        assertFalse(NaverTripStartSession.request(t0 + 100))
        assertFalse(NaverTripStartSession.noteLive(t0 + 200))
        assertFalse(NaverTripStartSession.due(t0 + 1_000))
        assertTrue(NaverTripStartSession.due(t0 + NaverTripStartSession.ARM_DELAY_MS))
        assertTrue(NaverTripStartSession.holdBusWait(t0 + 1_000))
    }

    @Test
    fun `spoken click is ignored until guidance ends`() {
        val t0 = 10_000L
        assertTrue(NaverTripStartSession.request(t0))
        NaverTripStartSession.markSpoken(t0 + 4_000)
        assertFalse(NaverTripStartSession.request(t0 + 5_000))
        assertFalse(NaverTripStartSession.noteLive(t0 + 6_000))
        assertFalse(NaverTripStartSession.noteNotLive(t0 + 7_000))
        assertTrue(
            NaverTripStartSession.noteNotLive(
                t0 + 7_000 + NaverTripStartSession.NOT_LIVE_END_MS,
            ),
        )
        assertTrue(NaverTripStartSession.request(t0 + 20_000))
    }

    @Test
    fun `new 안내시작 click reopens briefing after a spoken trip`() {
        val t0 = 30_000L
        assertTrue(NaverTripStartSession.request(t0))
        NaverTripStartSession.markSpoken(t0 + 4_000)
        assertFalse(NaverTripStartSession.request(t0 + 5_000))
        assertTrue(NaverTripStartSession.restartFromClick(t0 + 6_000))
        assertTrue(NaverTripStartSession.due(t0 + 6_000 + NaverTripStartSession.ARM_DELAY_MS))
    }

    @Test
    fun `guidance end frees a session that never found a briefing`() {
        val t0 = 100_000L
        assertTrue(NaverTripStartSession.request(t0))
        val due = t0 + NaverTripStartSession.ARM_DELAY_MS
        assertTrue(NaverTripStartSession.due(due))
        assertFalse(NaverTripStartSession.giveUpIfStale(due))
        assertTrue(
            NaverTripStartSession.giveUpIfStale(due + NaverTripStartSession.GIVE_UP_AFTER_DUE_MS),
        )
        assertFalse(NaverTripStartSession.holdBusWait(due))
        assertTrue(NaverTripStartSession.due(due + 60_000))
        assertFalse(NaverTripStartSession.hasSpokenBriefing())
        assertFalse(NaverTripStartSession.request(due + 60_000))
        assertFalse(NaverTripStartSession.noteNotLive(due + 60_000))
        assertTrue(
            NaverTripStartSession.noteNotLive(
                due + 60_000 + NaverTripStartSession.NOT_LIVE_END_MS,
            ),
        )
        assertTrue(NaverTripStartSession.noteLive(due + 70_000))
    }

    @Test
    fun `briefing spoken is false until markSpoken`() {
        assertFalse(NaverTripStartSession.hasSpokenBriefing())
        NaverTripStartSession.request(1_000L)
        assertFalse(NaverTripStartSession.hasSpokenBriefing())
        NaverTripStartSession.markSpoken(5_000L)
        assertTrue(NaverTripStartSession.hasSpokenBriefing())
    }

    @Test
    fun `other window not-live does not end a spoken session`() {
        val t0 = 200_000L
        val windowA = 3397L
        val windowB = 3407L
        assertTrue(NaverTripStartSession.request(t0))
        assertFalse(NaverTripStartSession.noteLive(t0 + 100, windowA))
        NaverTripStartSession.markSpoken(t0 + 4_000)
        assertTrue(NaverTripStartSession.hasSpokenBriefing())
        assertFalse(NaverTripStartSession.noteNotLive(t0 + 5_000, windowB))
        assertFalse(
            NaverTripStartSession.noteNotLive(
                t0 + 5_000 + NaverTripStartSession.NOT_LIVE_END_MS + 500,
                windowB,
            ),
        )
        assertTrue(NaverTripStartSession.hasSpokenBriefing())
        assertFalse(NaverTripStartSession.noteLive(t0 + 8_000, windowA))
        assertTrue(NaverTripStartSession.hasSpokenBriefing())
        assertFalse(NaverTripStartSession.due(t0 + 8_000 + NaverTripStartSession.ARM_DELAY_MS))
        assertFalse(NaverTripStartSession.request(t0 + 9_000))
    }

    @Test
    fun `same window not-live still ends after the existing debounce`() {
        val t0 = 300_000L
        val windowA = 3397L
        assertTrue(NaverTripStartSession.request(t0))
        assertFalse(NaverTripStartSession.noteLive(t0 + 100, windowA))
        NaverTripStartSession.markSpoken(t0 + 4_000)
        assertFalse(NaverTripStartSession.noteNotLive(t0 + 5_000, windowA))
        assertTrue(NaverTripStartSession.hasSpokenBriefing())
        assertTrue(
            NaverTripStartSession.noteNotLive(
                t0 + 5_000 + NaverTripStartSession.NOT_LIVE_END_MS,
                windowA,
            ),
        )
        assertFalse(NaverTripStartSession.hasSpokenBriefing())
        assertTrue(NaverTripStartSession.noteLive(t0 + 20_000, windowA))
    }

    @Test
    fun `안내종료 reset ends immediately without waiting for not-live`() {
        val t0 = 400_000L
        val windowA = 3397L
        assertTrue(NaverTripStartSession.request(t0))
        assertFalse(NaverTripStartSession.noteLive(t0 + 100, windowA))
        NaverTripStartSession.markSpoken(t0 + 4_000)
        assertTrue(NaverTripStartSession.hasSpokenBriefing())
        NaverTripStartSession.reset()
        assertFalse(NaverTripStartSession.hasSpokenBriefing())
        assertTrue(NaverTripStartSession.request(t0 + 4_100))
    }

    @Test
    fun `bus hold lifts after briefing window without consuming stages`() {
        val t0 = 50_000L
        NaverTripStartSession.request(t0)
        NaverTripStartSession.markSpoken(t0 + 4_000)
        assertTrue(NaverTripStartSession.holdBusWait(t0 + 4_000 + 100))
        assertFalse(
            NaverTripStartSession.holdBusWait(
                t0 + 4_000 + NaverTripStartSession.BUS_HOLD_AFTER_SPEAK_MS,
            ),
        )
    }
}
