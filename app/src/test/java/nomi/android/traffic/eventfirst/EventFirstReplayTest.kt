package nomi.android.traffic.eventfirst

import nomi.android.traffic.NaverNearBoardNotice
import nomi.android.traffic.eventfirst.EventFirstDecision.Reason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

/**
 * Replays the 341 302 notifications captured on the 2026-09-15 afternoon ride
 * (bus 4 → bus 5 → 인천1호선 → 7호선 → 서해선) through the real pipeline.
 *
 * The fixture is the observation log verbatim: timestamp, title, text, nothing
 * added or corrected.
 */
class EventFirstReplayTest {

    private lateinit var originalZone: TimeZone

    @Before
    fun setUp() {
        // The capture happened in Asia/Seoul, and departure clocks resolve
        // against device-local time.
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
        NaverNearBoardNotice.reset()
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalZone)
    }

    @Test
    fun `the ride has the 341 records the observation log recorded`() {
        assertEquals(341, RIDE.size)
    }

    @Test
    fun `every 302 of the ride maps onto one of the nine semantic events`() {
        val unparsed = RIDE.filter { NaverEventParser.parse(it.title, it.text, it.ts) == null }
        assertTrue(
            "unclassified: " + unparsed.take(5).joinToString { "${it.title} | ${it.text}" },
            unparsed.isEmpty(),
        )
    }

    @Test
    fun `the ride is nine event kinds and mostly silence`() {
        val run = replay()
        assertEquals(
            mapOf(
                "GuidanceStart" to 6,
                "GuidanceEnd" to 1,
                "WaitBus" to 106,
                "WaitTrain" to 11,
                "BoardTrain" to 168,
                "Riding" to 30,
                "AlightSoon" to 4,
                "AlightNow" to 2,
                "AlightTransfer" to 2,
            ),
            run.kinds,
        )
        // Duplicates are absorbed before the judge, so the kinds above are the
        // 341 records minus the same-timestamp re-posts.
        assertEquals(341, run.kinds.values.sum() + run.duplicates)
        assertEquals(0, run.unparsed)
    }

    @Test
    fun `a two hour ride speaks twenty one times`() {
        val run = replay()
        assertEquals(21, run.spoken.size)
        assertEquals(341 - 21, run.silences.values.sum() + run.duplicates)
    }

    @Test
    fun `every silence has a named reason`() {
        val run = replay()
        assertEquals(
            mapOf(
                Reason.SILENCE_ALREADY_SPOKEN to 251,
                Reason.SILENCE_RIDING to 30,
                // Wait readings that are not a rung: 13분 / 15분 / 16분 / 17분 on the
                // bus boards, and the 인천1호선 board counting 8분 → 7분.
                Reason.SILENCE_NOT_A_STAGE to 17,
                Reason.SILENCE_GUIDANCE_START to 6,
                Reason.SILENCE_NO_STATION to 2,
                Reason.SILENCE_COOLDOWN to 1,
                Reason.SILENCE_GUIDANCE_END to 1,
                // The mixed 일산행 / 대곡행 board; its re-post was deduped.
                Reason.SILENCE_AMBIGUOUS_DIRECTION to 1,
            ),
            run.silences,
        )
    }

    @Test
    fun `same-timestamp re-posts are absorbed before the judge`() {
        assertEquals(11, replay().duplicates)
    }

    /** The whole ride, in the order Nomi would have said it. */
    @Test
    fun `the expected speech sequence`() {
        assertEquals(
            listOf(
                // 4번, 학익시장(법원검찰청)
                "16:31:06 4번, 4번 버스가 10분 후 도착합니다. 다음은 4번, 20분 후 도착입니다.",
                // 재탐색 → 학익시장앞. Naver itself said 곧 도착 here.
                "16:35:28 4번, 4번 버스, 곧 도착합니다. 다음은 4번, 14분 후 도착입니다.",
                "16:37:57 4번, 4번 버스가 10분 후 도착합니다. 다음은 4번, 23분 후 도착입니다.",
                // 재탐색 → 법원길삼거리
                "16:45:05 4번, 4번 버스가 3분 후 도착합니다. 다음은 4번, 17분 후 도착입니다.",
                "16:46:37 4번, 4번 버스, 곧 도착합니다. 다음은 4번, 16분 후 도착입니다.",
                // 안내 종료 → 재시작 → 5번, 문학정보고입구
                "16:50:33 5번, 5번 버스가 9분 후 도착합니다. 다음은 5번, 25분 후 도착입니다.",
                "16:54:07 5번, 5번 버스가 5분 후 도착해요. 다음은 5번, 23분 후 도착입니다.",
                "16:57:28 5번, 5번 버스가 2분 후 도착해요. 다음은 5번, 21분 후 도착입니다.",
                "16:58:29 5번, 5번 버스, 곧 도착합니다. 다음은 5번, 20분 후 도착입니다.",
                // 인천1호선 — 17:16 / 17:24 / 17:30 read at 17:08:44
                "17:08:44 인천1호선이 8분 후 출발합니다. 다음 열차는 16분 후 도착입니다.",
                "17:09:50 예술회관역 방면입니다. 빠른 환승은 8다시4입니다.",
                "17:50:37 다음 역은 부평구청역입니다. 내릴 준비하세요.",
                "17:52:39 이번 역은 부평구청역입니다. 이번 역에서 하차하세요.",
                "17:52:39 내리는 문은 오른쪽입니다. 7호선으로 환승입니다.",
                // 7호선
                "17:53:40 7호선이 5분 후 출발합니다.",
                "17:53:50 굴포천역 방면입니다. 빠른 환승은 7다시1입니다.",
                "18:09:35 다음 역은 부천종합운동장역입니다. 내릴 준비하세요.",
                "18:11:24 이번 역은 부천종합운동장역입니다. 이번 역에서 하차하세요.",
                "18:11:24 내리는 문은 왼쪽입니다. 서해선으로 환승입니다.",
                // 서해선 — 양방향이 정리된 뒤에야 판단
                "18:12:56 서해선이 6분 후 출발합니다. 다음 열차는 16분 후 도착입니다.",
                "18:13:57 원종역 방면입니다. 빠른 환승은 1다시1입니다.",
            ),
            replay().spoken,
        )
    }

    /**
     * The whole point of the exercise: on this ride the Journey path made no
     * decision at all for the 5번 leg because a stale pin survived the 안내 종료.
     */
    @Test
    fun `the 5번 leg walks the full ladder`() {
        val ladder = replay().spoken.filter { it.contains("5번") }
        assertEquals(
            listOf(
                "16:50:33 5번, 5번 버스가 9분 후 도착합니다. 다음은 5번, 25분 후 도착입니다.",
                "16:54:07 5번, 5번 버스가 5분 후 도착해요. 다음은 5번, 23분 후 도착입니다.",
                "16:57:28 5번, 5번 버스가 2분 후 도착해요. 다음은 5번, 21분 후 도착입니다.",
                "16:58:29 5번, 5번 버스, 곧 도착합니다. 다음은 5번, 20분 후 도착입니다.",
            ),
            ladder,
        )
    }

    @Test
    fun `boarding cues repeat 168 times and are spoken 3 times`() {
        val run = replay()
        assertEquals(168, run.kinds["BoardTrain"])
        assertEquals(3, run.spoken.count { it.contains("빠른 환승") })
    }

    private class Run(
        val kinds: Map<String, Int>,
        val silences: Map<Reason, Int>,
        val spoken: List<String>,
        val duplicates: Int,
        val unparsed: Int,
    )

    private fun replay(): Run {
        val pipeline = EventFirstPipeline()
        val kinds = linkedMapOf<String, Int>()
        val silences = mutableMapOf<Reason, Int>()
        val spoken = mutableListOf<String>()
        var duplicates = 0
        var unparsed = 0
        RIDE.forEach { record ->
            val step = pipeline.accept(record.title, record.text, record.ts)
            when {
                step.decision.reason == Reason.SILENCE_DUPLICATE -> duplicates++
                step.event == null -> unparsed++
                else -> {
                    val kind = step.event.javaClass.simpleName
                    kinds[kind] = (kinds[kind] ?: 0) + 1
                    val sentence = step.sentence
                    if (sentence != null) {
                        spoken += "${Fixture302.clock(record.ts)} $sentence"
                    } else {
                        silences[step.decision.reason] = (silences[step.decision.reason] ?: 0) + 1
                    }
                }
            }
        }
        return Run(
            kinds = kinds.toSortedMap(compareBy { KIND_ORDER.indexOf(it) }),
            silences = silences.toList().sortedByDescending { it.second }.toMap(),
            spoken = spoken,
            duplicates = duplicates,
            unparsed = unparsed,
        )
    }

    private companion object {
        val RIDE: List<Fixture302.Record> = Fixture302.load("eventfirst/naver-302-sep15-ride.jsonl")

        val KIND_ORDER = listOf(
            "GuidanceStart",
            "GuidanceEnd",
            "WaitBus",
            "WaitTrain",
            "BoardTrain",
            "Riding",
            "AlightSoon",
            "AlightNow",
            "AlightTransfer",
        )
    }
}
