package nomi.android.traffic.eventfirst

import nomi.android.traffic.NaverNearBoardNotice
import nomi.android.traffic.eventfirst.EventFirstDecision.Reason
import nomi.android.traffic.eventfirst.EventFirstDecision.SpeechType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * Absolute departure clocks (`검단호수공원행 (17:16)`) resolved into the minutes the
 * 10 / 5 / 2 / 곧 ladder grades.
 *
 * The conversion runs on device-local time, so the JVM zone is pinned to the one
 * the fixture was captured in.
 */
class WaitTrainClockTest {

    private lateinit var originalZone: TimeZone
    private lateinit var ride: Ride

    @Before
    fun setUp() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(SEOUL))
        NaverNearBoardNotice.reset()
        ride = Ride()
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalZone)
    }

    // A. 17:08:44 + 17:16 → 8분
    @Test
    fun `a departure clock becomes minutes from now`() {
        val event = waitTrain("17:08:44", "검단호수공원행 (17:16)")
        val departure = event.departures.single()
        assertEquals("17:16", departure.departureTime)
        assertEquals(8, departure.etaMinutes)
        assertEquals("8분", departure.eta)
    }

    // B. 17:08:44 + 17:16 / 17:24 → 8 / 16
    @Test
    fun `the next train converts too`() {
        val event = waitTrain("17:08:44", "검단호수공원행 (17:16), 검단호수공원행 (17:24)")
        assertEquals(listOf(8, 16), event.departures.map { it.etaMinutes })
        assertEquals(listOf("8분", "16분"), event.arrivals.map { it.eta })
    }

    // C. the real 17:08:44 board → 8 / 16 / 22
    @Test
    fun `the captured board converts to 8 16 and 22`() {
        val event = waitTrain("17:08:44", INCHEON_BOARD)
        assertEquals(listOf(8, 16, 22), event.departures.map { it.etaMinutes })
        assertEquals("인천터미널역", event.station)
        assertEquals("인천1호선", event.line)
        assertEquals("검단호수공원행", event.direction)
    }

    // D. a train that already left is not a candidate
    @Test
    fun `a departed train is dropped instead of announced`() {
        val event = waitTrain("17:20:00", "검단호수공원행 (17:16), 검단호수공원행 (17:24)")
        assertEquals(listOf(4), event.departures.map { it.etaMinutes })
        assertEquals(listOf("4분"), event.arrivals.map { it.eta })
    }

    @Test
    fun `a board of only departed trains is still a train board`() {
        val event = waitTrain("17:20:00", "검단호수공원행 (17:16)")
        assertTrue(event.departures.isEmpty())
        assertEquals(
            Reason.SILENCE_NO_ARRIVALS,
            EventFirstJudge.judge(event, EventFirstState(), event.atMs).decision.reason,
        )
    }

    // E. 23:58 + 00:05 → 7분
    @Test
    fun `a clock past midnight is tomorrow`() {
        val event = waitTrain("23:58:00", "검단호수공원행 (00:05)")
        assertEquals(7, event.departures.single().etaMinutes)
    }

    @Test
    fun `a far out timetable reading is not a wait cue`() {
        val event = waitTrain("17:08:44", "검단호수공원행 (20:30)")
        assertTrue(event.departures.isEmpty())
    }

    // F. two 방면 on one board → still no guessing
    @Test
    fun `mixed directions stay ambiguous with clocks too`() {
        val step = ride.at("17:08:44", SEOHAE_WALK, "일산행 (17:16), 대곡행 (17:20)")
        assertEquals(Reason.SILENCE_AMBIGUOUS_DIRECTION, step.decision.reason)
        assertNull(ride.state.reading)
    }

    // G. one 방면 → an ordinary wait
    @Test
    fun `one direction with clocks briefs normally`() {
        val step = ride.at("17:08:44", INCHEON_WALK, INCHEON_BOARD)
        assertEquals(Reason.SPEAK_BRIEF, step.decision.reason)
        assertEquals(SpeechType.TRAIN_BRIEF, step.decision.speechType)
        assertEquals(
            "인천1호선이 8분 후 출발합니다. 다음 열차는 16분 후 도착입니다.",
            step.sentence,
        )
    }

    // H / I / J. the ladder over one train's clock
    @Test
    fun `the clock board walks 10 then 5 then 2 then soon`() {
        assertEquals(Reason.SPEAK_BRIEF, ride.at("17:08:44", INCHEON_WALK, "검단호수공원행 (17:16)").decision.reason)
        assertEquals(Reason.SPEAK_STAGE_5, ride.at("17:11:00", INCHEON_WALK, "검단호수공원행 (17:16)").decision.reason)
        assertEquals(Reason.SPEAK_STAGE_2, ride.at("17:14:00", INCHEON_WALK, "검단호수공원행 (17:16)").decision.reason)
        val soon = ride.at("17:15:00", INCHEON_WALK, "검단호수공원행 (17:16)")
        assertEquals(Reason.SPEAK_SOON, soon.decision.reason)
        assertEquals("인천1호선, 곧 도착합니다.", soon.sentence)
    }

    // K. a departure time drifting back and forth is one train
    @Test
    fun `a drifting departure clock stays quiet`() {
        assertEquals(Reason.SPEAK_BRIEF, ride.at("17:08:44", INCHEON_WALK, "검단호수공원행 (17:16)").decision.reason)
        listOf(
            "17:09:00" to "검단호수공원행 (17:19)",
            "17:09:20" to "검단호수공원행 (17:18)",
            "17:09:40" to "검단호수공원행 (17:17)",
        ).forEach { (clock, text) ->
            assertEquals(clock, Reason.SILENCE_ALREADY_SPOKEN, ride.at(clock, INCHEON_WALK, text).decision.reason)
        }
    }

    // L. the same notification posted twice on one timestamp
    @Test
    fun `two variants of one clock board are one event`() {
        assertEquals(Reason.SPEAK_BRIEF, ride.at("17:08:44", INCHEON_WALK, INCHEON_BOARD).decision.reason)
        assertEquals(Reason.SILENCE_DUPLICATE, ride.at("17:08:45", INCHEON_WALK, INCHEON_BOARD).decision.reason)
    }

    @Test
    fun `boarding ends the clock wait without repeating it`() {
        assertEquals(Reason.SPEAK_BRIEF, ride.at("17:08:44", INCHEON_WALK, INCHEON_BOARD).decision.reason)
        val board = ride.at("17:09:50", "인천터미널역 인천1호선 열차 승차", "예술회관역 방면 빠른 환승: 8-4")
        assertEquals(Reason.SPEAK_BOARD, board.decision.reason)
        assertEquals("예술회관역 방면입니다. 빠른 환승은 8다시4입니다.", board.sentence)
        assertNull(ride.state.reading)
        assertTrue(ride.state.marks("train|인천터미널역|인천1호선").isEmpty())
    }

    private fun waitTrain(clock: String, text: String): NaverTransitEvent.WaitTrain =
        NaverEventParser.parse(INCHEON_WALK, text, atMs(clock)) as NaverTransitEvent.WaitTrain

    private class Ride {
        private val pipeline = EventFirstPipeline()
        val state: EventFirstState get() = pipeline.state

        fun at(clock: String, title: String, text: String): EventFirstPipeline.Step =
            pipeline.accept(title, text, atMs(clock))
    }

    private companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        const val INCHEON_WALK = "인천터미널역 인천1호선까지 걷기"
        const val SEOHAE_WALK = "부천종합운동장역 서해선까지 걷기"
        const val INCHEON_BOARD =
            "검단호수공원행 (17:16), 검단호수공원행 (17:24), 검단호수공원행 (17:30)"

        /** `hh:mm:ss` on the day the ride was captured. */
        fun atMs(clock: String): Long {
            val (h, m, s) = clock.split(':').map { it.toInt() }
            return ZonedDateTime.of(2026, 9, 15, h, m, s, 0, SEOUL).toInstant().toEpochMilli()
        }
    }
}
