package nomi.android.traffic

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The 안내 중 label disappears for a few frames inside one guidance, so the
 * accessibility live edge fires false→true again. Only a session that actually
 * arms may reset the subway cues.
 */
class NaverLiveGuidanceFlickerTest {

    private val window = 157L
    private lateinit var gate: NavigationEventSpeechGate

    @Before
    fun setUp() {
        NaverTripStartSession.reset()
        gate = NavigationEventSpeechGate()
    }

    @After
    fun tearDown() {
        NaverTripStartSession.reset()
    }

    @Test
    fun `1 flicker inside a spoken guidance keeps the subway cues`() {
        val t0 = 1_000_000L
        assertTrue(NaverTripStartSession.request(t0))
        assertFalse(NaverTripStartSession.noteLive(t0 + 100, window))
        NaverTripStartSession.markSpoken(t0 + 4_000)

        armSubwayCues()

        // 안내 중 gone for 390ms on the same window — shorter than the debounce.
        assertFalse(NaverTripStartSession.noteNotLive(t0 + 10_000, window))
        assertFalse(onLiveEdge(t0 + 10_390))

        assertTrue(gate.isTransferSubwayBriefPending())
        assertFalse(gate.accept(boardSoon()))
        assertFalse(gate.acceptNaverSubwayWalkBrief(boardSoon()))
    }

    @Test
    fun `2 a new guidance arm resets the subway cues`() {
        val t0 = 2_000_000L
        assertTrue(NaverTripStartSession.request(t0))
        assertFalse(NaverTripStartSession.noteLive(t0 + 100, window))
        NaverTripStartSession.markSpoken(t0 + 4_000)

        armSubwayCues()

        // Guidance really ended, so the next live edge opens a fresh session.
        assertFalse(NaverTripStartSession.noteNotLive(t0 + 10_000, window))
        assertTrue(
            NaverTripStartSession.noteNotLive(
                t0 + 10_000 + NaverTripStartSession.NOT_LIVE_END_MS,
                window,
            ),
        )
        assertTrue(onLiveEdge(t0 + 20_000))

        assertFalse(gate.isTransferSubwayBriefPending())
        assertTrue(gate.accept(boardSoon()))
        assertTrue(gate.acceptNaverSubwayWalkBrief(boardSoon()))
    }

    /**
     * Mirrors the live-edge branch in [NaverSubwayAccessibilityService], which
     * announces the transition once via NavigationEventVoice.onNaverGuidanceStarted.
     */
    private fun onLiveEdge(nowMs: Long): Boolean {
        val armed = NaverTripStartSession.noteLive(nowMs, window)
        if (armed) gate.resetNaverJourneyCues()
        return armed
    }

    private fun armSubwayCues() {
        assertTrue(gate.noteAlightThenTransfer("이번 정류장에서 하차 후 환승", null))
        assertTrue(gate.acceptNaverSubwayWalkBrief(boardSoon()))
        assertTrue(gate.isTransferSubwayBriefPending())
    }

    private fun boardSoon() = NaverNotificationParser.parse(
        NaverNotificationParser.Snapshot(
            packageName = "com.nhn.android.nmap",
            notificationId = 301,
            channel = "302_PUBTRANS_POPUP",
            title = "정발산역 3호선 열차 승차",
            text = "마두역 방면 빠른 하차: 2-4",
            bigText = "오금행 (1분)",
            timestampMillis = 0L,
        ),
    )!!
}
