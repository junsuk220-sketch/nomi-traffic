package nomi.android.traffic.eventfirst

import nomi.android.traffic.NaverNearBoardNotice
import nomi.android.traffic.eventfirst.EventFirstDecision.Reason
import nomi.android.traffic.eventfirst.EventFirstDecision.SpeechType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drives the real pipeline with 302 title/text pairs on a synthetic clock.
 */
class EventFirstJudgeTest {

    private lateinit var ride: Ride

    @Before
    fun setUp() {
        // Legacy near-board flag is a shared object; the wait sentences read it.
        NaverNearBoardNotice.reset()
        ride = Ride()
    }

    @Test
    fun `ladder walks 10 then 5 then 2 then soon`() {
        assertEquals(Reason.SPEAK_BRIEF, ride.at(0, BOARD_5, "5 (9분), 5 (25분)").decision.reason)
        assertEquals(Reason.SPEAK_STAGE_5, ride.at(210, BOARD_5, "5 (5분), 5 (23분)").decision.reason)
        assertEquals(Reason.SPEAK_STAGE_2, ride.at(420, BOARD_5, "5 (2분), 5 (21분)").decision.reason)
        assertEquals(Reason.SPEAK_SOON, ride.at(430, BOARD_5, "5 (곧 도착), 5 (18분)").decision.reason)
    }

    @Test
    fun `wobbling eta on the same bus stays quiet`() {
        assertEquals(Reason.SPEAK_BRIEF, ride.at(0, BOARD_4, "4 (8분), 4 (24분)").decision.reason)
        listOf(16L to "4 (10분), 4 (23분)", 31L to "4 (9분), 4 (23분)", 46L to "4 (8분), 4 (23분)")
            .forEach { (second, text) ->
                assertEquals(
                    "t=$second",
                    Reason.SILENCE_ALREADY_SPOKEN,
                    ride.at(second, BOARD_4, text).decision.reason,
                )
            }
    }

    @Test
    fun `eta rising after soon is the next vehicle and reopens the ladder`() {
        assertEquals(Reason.SPEAK_BRIEF, ride.at(0, BOARD_5, "5 (곧 도착), 5 (18분)").decision.reason)
        // 곧 → 17분 cannot be one vehicle, so the rungs we walked are dropped…
        assertEquals(Reason.SILENCE_NOT_A_STAGE, ride.at(49, BOARD_5, "5 (17분)").decision.reason)
        assertTrue(ride.state.marks("bus|문학정보고입구|5").isEmpty())
        // …and the bus behind it briefs on its own.
        assertEquals(Reason.SPEAK_BRIEF, ride.at(120, BOARD_5, "5 (9분), 5 (20분)").decision.reason)
    }

    @Test
    fun `sudden drop is held until it repeats`() {
        assertEquals(Reason.SPEAK_BRIEF, ride.at(0, BOARD_4, "4 (10분), 4 (20분)").decision.reason)
        assertEquals(Reason.SILENCE_ALREADY_SPOKEN, ride.at(200, BOARD_4, "4 (9분), 4 (20분)").decision.reason)
        // 9분 → 3분 three seconds later: the clock cannot explain it.
        assertEquals(Reason.SILENCE_PENDING, ride.at(203, BOARD_4, "4 (3분), 4 (20분)").decision.reason)
        assertEquals("3분", ride.state.pending?.eta)
        // The same value again after the confirm window is believed.
        assertEquals(Reason.SPEAK_STAGE_5, ride.at(214, BOARD_4, "4 (3분), 4 (20분)").decision.reason)
        assertNull(ride.state.pending)
    }

    @Test
    fun `a different route at a different stop briefs on its own`() {
        assertEquals(Reason.SPEAK_BRIEF, ride.at(0, "학익시장앞 버스 승차", "4 (10분), 4 (23분)").decision.reason)
        val step = ride.at(60, BOARD_5, "5 (9분), 5 (25분)")
        assertEquals(Reason.SPEAK_BRIEF, step.decision.reason)
        assertEquals("bus|문학정보고입구|5", step.event?.scopeKey)
    }

    @Test
    fun `riding drops the bus wait state`() {
        ride.at(0, BOARD_5, "5 (곧 도착), 5 (18분)")
        val step = ride.at(60, "문학경기장(야구장)으로 이동 중", "하차까지 35개 정류장")
        assertEquals(Reason.SILENCE_RIDING, step.decision.reason)
        assertNull(ride.state.reading)
        assertTrue(ride.state.marks("bus|문학정보고입구|5").isEmpty())
    }

    @Test
    fun `a train wait after a bus wait is simply the next scope`() {
        ride.at(0, BOARD_5, "5 (곧 도착), 5 (18분)")
        val step = ride.at(60, "부평구청역 7호선까지 걷기", "장암행 (5분)")
        assertEquals(Reason.SPEAK_BRIEF, step.decision.reason)
        assertEquals(SpeechType.TRAIN_BRIEF, step.decision.speechType)
        assertEquals("7호선이 5분 후 출발합니다.", step.sentence)
    }

    @Test
    fun `two directions on one board stay silent until Naver settles`() {
        val mixed = ride.at(0, SEOHAE_WALK, "일산행 (도착), 대곡행 (5분), 대곡행 (16분)")
        assertEquals(Reason.SILENCE_AMBIGUOUS_DIRECTION, mixed.decision.reason)
        assertNull(ride.state.reading)
        val settled = ride.at(22, SEOHAE_WALK, "대곡행 (6분), 대곡행 (15분), 대곡행 (26분)")
        assertEquals(Reason.SPEAK_BRIEF, settled.decision.reason)
    }

    @Test
    fun `two variants of one notification are one event`() {
        assertEquals(Reason.SPEAK_BRIEF, ride.at(0, "문학정보고입구까지 걷기", ETA_5_9).decision.reason)
        assertEquals(Reason.SILENCE_DUPLICATE, ride.at(0, "문학정보고입구까지 걷기", ETA_5_9).decision.reason)
        // Different title, same stop and route: not a duplicate, but the same scope.
        assertEquals(Reason.SILENCE_ALREADY_SPOKEN, ride.at(1, BOARD_5, ETA_5_9).decision.reason)
    }

    @Test
    fun `guidance end clears everything and the next trip briefs at once`() {
        ride.at(0, "학익시장앞 버스 승차", "4 (10분), 4 (23분)")
        val ended = ride.at(60, "길안내를 종료합니다.", "현위치가 예상 경로를 벗어났습니다.")
        assertEquals(Reason.SILENCE_GUIDANCE_END, ended.decision.reason)
        assertEquals(EventFirstState(awaitingStart = true), ride.state)
        assertEquals(
            Reason.SILENCE_GUIDANCE_START,
            ride.at(93, "길안내를 시작합니다.", "우림보보카운티2까지 이동").decision.reason,
        )
        // No stale pin from the previous trip can block the new route.
        assertEquals(Reason.SPEAK_BRIEF, ride.at(120, BOARD_5, "5 (9분), 5 (25분)").decision.reason)
    }

    @Test
    fun `next vehicle is the soonest other row whatever route it is`() {
        val step = ride.at(0, "학익시장앞 버스 승차", "4 (10분), 7 (12분), 4 (23분)")
        val event = step.event as NaverTransitEvent.WaitBus
        assertEquals("4", event.route)
        assertEquals("12분", event.nextEta)
        assertEquals(
            "4번, 4번 버스가 10분 후 도착합니다. 다음은 7번, 12분 후 도착입니다.",
            step.sentence,
        )
    }

    @Test
    fun `middle rungs stay quiet inside the 120s window`() {
        assertEquals(Reason.SPEAK_BRIEF, ride.at(0, BOARD_4, "4 (10분), 4 (20분)").decision.reason)
        assertEquals(Reason.SILENCE_COOLDOWN, ride.at(60, BOARD_4, "4 (5분), 4 (20분)").decision.reason)
        // The rung was consumed while quiet, so it cannot arrive late.
        assertEquals(Reason.SILENCE_ALREADY_SPOKEN, ride.at(200, BOARD_4, "4 (4분), 4 (20분)").decision.reason)
    }

    @Test
    fun `soon ignores the cooldown`() {
        assertEquals(Reason.SPEAK_BRIEF, ride.at(0, BOARD_4, "4 (10분), 4 (20분)").decision.reason)
        val soon = ride.at(30, BOARD_4, "4 (곧 도착), 4 (20분)")
        assertEquals(Reason.SPEAK_SOON, soon.decision.reason)
        assertEquals("4번, 4번 버스, 곧 도착합니다. 다음은 4번, 20분 후 도착입니다.", soon.sentence)
    }

    @Test
    fun `boarding a train is announced once and ends the wait`() {
        ride.at(0, "부평구청역 7호선까지 걷기", "장암행 (5분)")
        val board = ride.at(10, BOARD_7, "굴포천역 방면 빠른 환승: 7-1")
        assertEquals(Reason.SPEAK_BOARD, board.decision.reason)
        assertEquals("굴포천역 방면입니다. 빠른 환승은 7다시1입니다.", board.sentence)
        assertNull(ride.state.reading)
        assertTrue(ride.state.marks("train|부평구청역|7호선").isEmpty())
        // The same notification repeats every 15s for the whole ride.
        assertEquals(Reason.SILENCE_ALREADY_SPOKEN, ride.at(25, BOARD_7, "굴포천역 방면 빠른 환승: 7-1").decision.reason)
        assertEquals(Reason.SILENCE_ALREADY_SPOKEN, ride.at(900, BOARD_7, "굴포천역 방면 빠른 환승: 7-1").decision.reason)
    }

    @Test
    fun `alight cues fire once per station and merge with the transfer`() {
        assertEquals(
            Reason.SILENCE_NO_STATION,
            ride.at(0, "하차까지 1개 역", "다음 역에서 하차").decision.reason,
        )
        val soon = ride.at(1, "하차까지 1개 역", "부평구청역에서 하차")
        assertEquals(Reason.SPEAK_ALIGHT_SOON, soon.decision.reason)
        assertEquals("다음 역은 부평구청역입니다. 내릴 준비하세요.", soon.sentence)

        val now = ride.at(122, "이번 역에서 하차", "부평구청역")
        assertEquals(Reason.SPEAK_ALIGHT_NOW, now.decision.reason)
        assertEquals("이번 역은 부평구청역입니다. 이번 역에서 하차하세요.", now.sentence)

        // Same timestamp as 하차: the station was just named, so only the transfer.
        val transfer = ride.at(122, TRANSFER_TITLE, "내리는 문 오른쪽, 부평구청역 7호선으로 환승")
        assertEquals(SpeechType.TRANSFER_ONLY, transfer.decision.speechType)
        assertEquals("내리는 문은 오른쪽입니다. 7호선으로 환승입니다.", transfer.sentence)

        assertEquals(Reason.SILENCE_ALREADY_SPOKEN, ride.at(130, "이번 역에서 하차", "부평구청역").decision.reason)
    }

    @Test
    fun `transfer arriving first carries the station itself`() {
        val transfer = ride.at(0, TRANSFER_TITLE, "내리는 문 오른쪽, 부평구청역 7호선으로 환승")
        assertEquals(SpeechType.TRANSFER_WITH_ALIGHT, transfer.decision.speechType)
        assertEquals(
            "이번 역은 부평구청역입니다. 내리는 문은 오른쪽입니다. 7호선으로 환승입니다.",
            transfer.sentence,
        )
        // The 하차 notification that follows must not repeat the station.
        assertEquals(Reason.SILENCE_ALREADY_SPOKEN, ride.at(1, "이번 역에서 하차", "부평구청역").decision.reason)
    }

    /**
     * The 5번 leg the Journey path spoke nothing for: guidance had ended, a
     * stale pin survived, and BusWaitCore made zero decisions. Real timestamps
     * and real 302 text from that leg.
     */
    @Test
    fun `the leg that used to be silent walks the whole ladder`() {
        val spoken = mutableListOf<String>()
        listOf(
            "16:48:34" to ("길안내를 종료합니다." to "현위치가 예상 경로를 벗어났습니다. 경로를 다시 찾아주세요."),
            "16:50:07" to ("길안내를 시작합니다." to "우림보보카운티2까지 이동"),
            "16:50:33" to ("문학정보고입구까지 걷기" to "5 (9분), 5 (25분)"),
            "16:50:34" to (BOARD_5 to "5 (9분), 5 (25분)"),
            "16:51:35" to (BOARD_5 to "5 (8분), 5 (24분)"),
            "16:53:52" to (BOARD_5 to "5 (6분), 5 (22분)"),
            "16:54:07" to (BOARD_5 to "5 (5분), 5 (23분)"),
            "16:55:40" to (BOARD_5 to "5 (3분), 5 (22분)"),
            "16:57:28" to (BOARD_5 to "5 (2분), 5 (21분)"),
            "16:58:29" to (BOARD_5 to "5 (1분), 5 (20분)"),
            "16:59:27" to (BOARD_5 to "5 (곧 도착), 5 (18분)"),
            "17:00:16" to (BOARD_5 to "5 (17분)"),
            "17:00:50" to ("문학경기장(야구장)으로 이동 중" to "하차까지 35개 정류장"),
        ).forEach { (clock, notification) ->
            val step = ride.atMs(clockMs(clock), notification.first, notification.second)
            step.sentence?.let { spoken += it }
        }
        assertEquals(
            listOf(
                "5번, 5번 버스가 9분 후 도착합니다. 다음은 5번, 25분 후 도착입니다.",
                "5번, 5번 버스가 5분 후 도착해요. 다음은 5번, 23분 후 도착입니다.",
                "5번, 5번 버스가 2분 후 도착해요. 다음은 5번, 21분 후 도착입니다.",
                "5번, 5번 버스, 곧 도착합니다. 다음은 5번, 20분 후 도착입니다.",
            ),
            spoken,
        )
    }

    private class Ride {
        private val pipeline = EventFirstPipeline()
        val state: EventFirstState get() = pipeline.state

        fun at(second: Long, title: String, text: String): EventFirstPipeline.Step =
            pipeline.accept(title, text, BASE_MS + second * 1000L)

        fun atMs(atMs: Long, title: String, text: String): EventFirstPipeline.Step =
            pipeline.accept(title, text, atMs)
    }

    private companion object {
        const val BASE_MS = 1_789_000_000_000L
        const val BOARD_4 = "학익시장앞 버스 승차"
        const val BOARD_5 = "문학정보고입구 버스 승차"
        const val BOARD_7 = "부평구청역 7호선 열차 승차"
        const val SEOHAE_WALK = "부천종합운동장역 서해선까지 걷기"
        const val TRANSFER_TITLE = "이번 역(부평구청역)에서 하차 후 환승"
        const val ETA_5_9 = "5 (9분), 5 (25분)"

        /** `hh:mm:ss` on the captured ride day, as epoch millis. */
        fun clockMs(clock: String): Long {
            val (h, m, s) = clock.split(':').map { it.toLong() }
            return DAY_START_MS + ((h * 3600) + (m * 60) + s) * 1000L
        }

        const val DAY_START_MS = 1_789_000_000_000L
    }
}
