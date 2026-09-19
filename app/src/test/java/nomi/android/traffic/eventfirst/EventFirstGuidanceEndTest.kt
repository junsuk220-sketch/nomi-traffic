package nomi.android.traffic.eventfirst

import nomi.android.traffic.NaverNearBoardNotice
import nomi.android.traffic.eventfirst.EventFirstDecision.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * STEP 3-2: a11y GuidanceEnd must close wait cues until the next start, without
 * resetting on GuidanceStart itself, and without speaking a leftover 302.
 */
class EventFirstGuidanceEndTest {

    private lateinit var ride: Ride

    @Before
    fun setUp() {
        NaverNearBoardNotice.reset()
        ride = Ride()
    }

    @Test
    fun `A leftover wait after a11y end is not spoken`() {
        assertEquals(Reason.SILENCE_GUIDANCE_START, ride.at(0, START, DEST).decision.reason)
        assertEquals(Reason.SPEAK_BRIEF, ride.at(1, WALK_81, SOON_81).decision.reason)

        val ended = ride.end(2)
        assertEquals(Reason.SILENCE_GUIDANCE_END, ended.decision.reason)
        assertTrue(ride.state.awaitingStart)
        assertNull(ride.state.reading)
        assertTrue(ride.state.spokenKeys.isEmpty())

        val leftover = ride.at(3, BOARD_81, SOON_81)
        assertEquals(Reason.SILENCE_WAIT_AFTER_END, leftover.decision.reason)
        assertFalse(leftover.decision.speak)
        assertNull(leftover.sentence)
        assertTrue(ride.state.awaitingStart)
        assertNull(ride.state.reading)
    }

    @Test
    fun `B the same board after a new start is a new wait`() {
        ride.at(0, START, DEST)
        ride.at(1, WALK_81, SOON_81)
        ride.end(2)
        assertEquals(Reason.SILENCE_GUIDANCE_START, ride.at(20, START, DEST_2).decision.reason)
        assertFalse(ride.state.awaitingStart)

        val second = ride.at(21, WALK_81, SOON_81)
        assertEquals(Reason.SPEAK_BRIEF, second.decision.reason)
        assertEquals("bus|라페스타.먹자골목|81", second.event?.scopeKey)
        assertEquals("81:곧 도착", "${ride.state.reading?.line}:${ride.state.reading?.eta}")
    }

    @Test
    fun `C a second end does not corrupt the gate`() {
        ride.at(0, START, DEST)
        ride.at(1, WALK_81, SOON_81)
        ride.end(2)
        val again = ride.end(3)
        assertEquals(Reason.SILENCE_GUIDANCE_END, again.decision.reason)
        assertTrue(ride.state.awaitingStart)
        assertNull(ride.state.reading)
        assertTrue(ride.state.spokenKeys.isEmpty())
        assertNull(ride.state.pending)
        assertNull(ride.state.lastSpokenAtMs)

        assertEquals(Reason.SILENCE_WAIT_AFTER_END, ride.at(4, BOARD_81, SOON_81).decision.reason)
        assertEquals(Reason.SILENCE_GUIDANCE_START, ride.at(20, START, DEST_2).decision.reason)
        assertEquals(Reason.SPEAK_BRIEF, ride.at(21, WALK_81, "81 (5분), 99 (10분)").decision.reason)
    }

    @Test
    fun `a leftover wait train is gated the same way`() {
        ride.at(0, START, DEST)
        assertEquals(
            Reason.SPEAK_BRIEF,
            ride.at(1, "부평구청역 7호선까지 걷기", "장암행 (5분)").decision.reason,
        )
        ride.end(2)
        val leftover = ride.at(3, "부평구청역 7호선까지 걷기", "장암행 (5분)")
        assertEquals(Reason.SILENCE_WAIT_AFTER_END, leftover.decision.reason)
        assertNull(ride.state.reading)
    }

    @Test
    fun `302 guidance end sets the same gate as a11y`() {
        ride.at(0, START, DEST)
        ride.at(1, WALK_81, SOON_81)
        val ended = ride.at(2, "길안내를 종료합니다.", "현위치가 예상 경로를 벗어났습니다.")
        assertEquals(Reason.SILENCE_GUIDANCE_END, ended.decision.reason)
        assertTrue(ride.state.awaitingStart)
        assertEquals(Reason.SILENCE_WAIT_AFTER_END, ride.at(3, BOARD_81, SOON_81).decision.reason)
    }

    @Test
    fun `guidance start during a live wait does not wipe marks`() {
        ride.at(0, START, DEST)
        ride.at(1, WALK_81, SOON_81)
        assertEquals(Reason.SILENCE_GUIDANCE_START, ride.at(2, START, DEST).decision.reason)
        assertFalse(ride.state.awaitingStart)
        assertEquals("81:곧 도착", "${ride.state.reading?.line}:${ride.state.reading?.eta}")
        assertEquals(
            Reason.SILENCE_ALREADY_SPOKEN,
            ride.at(3, BOARD_81, SOON_81).decision.reason,
        )
    }

    @Test
    fun `a11y end bypasses 302 dedup`() {
        ride.end(0)
        val second = ride.end(0)
        assertEquals(Reason.SILENCE_GUIDANCE_END, second.decision.reason)
        assertTrue(ride.state.awaitingStart)
    }

    private class Ride {
        private val pipeline = EventFirstPipeline()
        val state: EventFirstState get() = pipeline.state

        fun at(second: Long, title: String, text: String): EventFirstPipeline.Step =
            pipeline.accept(title, text, BASE_MS + second * 1000L)

        fun end(second: Long): EventFirstPipeline.Step {
            val atMs = BASE_MS + second * 1000L
            return pipeline.acceptEvent(NaverTransitEvent.GuidanceEnd(reason = null, atMs = atMs), atMs)
        }
    }

    private companion object {
        const val BASE_MS = 1_789_000_000_000L
        const val START = "길안내를 시작합니다."
        const val DEST = "광닭발까지 이동"
        const val DEST_2 = "대화역 3호선까지 이동"
        const val WALK_81 = "라페스타.먹자골목까지 걷기"
        const val BOARD_81 = "라페스타.먹자골목 도보 후 버스 승차"
        const val SOON_81 = "81 (곧 도착), 99 (1분), 81 (7분)"
    }
}
