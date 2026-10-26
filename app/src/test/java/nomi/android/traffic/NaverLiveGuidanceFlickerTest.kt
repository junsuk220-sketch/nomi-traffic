package nomi.android.traffic

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The 안내 중 label leaves the screen inside one guidance (a few frames, or minutes
 * while the sheet shows the next 승차 card), so the accessibility live edge fires
 * false→true again. Only a session that actually arms may reset the subway cues.
 */
class NaverLiveGuidanceFlickerTest {

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
    fun `1 안내 중 returning inside a spoken guidance keeps the subway cues`() {
        val t0 = 1_000_000L
        assertTrue(NaverTripStartSession.request(t0))
        assertFalse(NaverTripStartSession.noteLive(t0 + 100))
        NaverTripStartSession.markSpoken(t0 + 4_000)

        armSubwayCues()

        assertFalse(onLiveEdge(t0 + 10_390))

        assertFalse(gate.accept(boardSoon()))
        assertFalse(gate.acceptNaverSubwayWalkBrief(boardSoon()))
    }

    @Test
    fun `2 a new guidance arm resets the subway cues`() {
        val t0 = 2_000_000L
        assertTrue(NaverTripStartSession.request(t0))
        assertFalse(NaverTripStartSession.noteLive(t0 + 100))
        NaverTripStartSession.markSpoken(t0 + 4_000)

        armSubwayCues()

        // Guidance really ended (안내 종료 / removed card), so the next live edge opens a fresh session.
        NaverTripStartSession.reset()
        assertTrue(onLiveEdge(t0 + 20_000))

        assertTrue(gate.accept(boardSoon()))
        assertTrue(gate.acceptNaverSubwayWalkBrief(boardSoon()))
    }

    /**
     * Mirrors the live-edge branch in [NaverSubwayAccessibilityService], which
     * announces the transition once via NavigationEventVoice.onNaverGuidanceStarted.
     */
    private fun onLiveEdge(nowMs: Long): Boolean {
        val armed = NaverTripStartSession.noteLive(nowMs)
        if (armed) gate.resetNaverJourneyCues()
        return armed
    }

    private fun armSubwayCues() {
        assertTrue(gate.acceptNaverSubwayWalkBrief(boardSoon()))
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
