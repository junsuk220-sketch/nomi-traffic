package nomi.android.traffic.api.google.countdown

import nomi.android.traffic.api.google.read.GoogleStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class GoogleCountdownTest {

    private fun at(hour: Int, minute: Int, second: Int = 0) = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, second)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun wait(depart: String, line: String = "81") =
        GoogleStep.Wait(line, "모모월드", depart, "오후 11:21")

    /** Ticks every 15 s from [from] to [to] and keeps what was spoken. */
    private fun run(from: Long, to: Long): List<String> {
        val out = ArrayList<String>()
        var t = from
        while (t <= to) {
            GoogleCountdown.onTick(t)?.let(out::add)
            t += 15_000L
        }
        return out
    }

    @Before
    fun setUp() = GoogleCountdown.resetForTest()

    @Test
    fun `speaks 10 5 2 soon once each`() {
        assertNull(GoogleCountdown.onStep(wait("오후 10:33"), at(22, 18)))
        assertEquals(
            listOf(
                "81번, 81번 버스가 10분 후 도착해요.",
                "81번, 81번 버스가 5분 후 도착해요.",
                "81번, 81번 버스가 2분 후 도착해요.",
                "81번, 81번 버스, 곧 도착합니다.",
            ),
            run(at(22, 18), at(22, 34)),
        )
    }

    @Test
    fun `rungs already passed at first sight stay silent`() {
        assertNull(GoogleCountdown.onStep(wait("오후 10:33"), at(22, 25)))
        assertEquals(
            listOf(
                "81번, 81번 버스가 5분 후 도착해요.",
                "81번, 81번 버스가 2분 후 도착해요.",
                "81번, 81번 버스, 곧 도착합니다.",
            ),
            run(at(22, 25), at(22, 33)),
        )
    }

    @Test
    fun `boarding stops the count`() {
        GoogleCountdown.onStep(wait("오후 10:33"), at(22, 25))
        GoogleCountdown.onStep(GoogleStep.Board("81", "모모월드", "오후 10:33", "오후 11:21"), at(22, 26))
        assertFalse(GoogleCountdown.active)
        assertEquals(emptyList<String>(), run(at(22, 26), at(22, 34)))
    }

    @Test
    fun `later clock is followed without repeating a rung`() {
        GoogleCountdown.onStep(wait("오후 10:33"), at(22, 20))
        assertEquals(listOf("81번, 81번 버스가 10분 후 도착해요."), run(at(22, 23), at(22, 23)))
        assertNull(GoogleCountdown.onStep(wait("오후 10:36"), at(22, 24)))
        assertEquals(listOf("81번, 81번 버스가 5분 후 도착해요."), run(at(22, 31), at(22, 31)))
    }

    @Test
    fun `transfer wait starts a new count`() {
        GoogleCountdown.onStep(wait("오후 10:33"), at(22, 30))
        GoogleCountdown.onStep(GoogleStep.Ride("마두역(중)", 5, "정류장", 5, "오후 11:21"), at(22, 34))
        GoogleCountdown.onStep(wait("오후 10:52", line = "790"), at(22, 40))
        assertEquals(
            listOf("790번, 790번 버스가 10분 후 도착해요.", "790번, 790번 버스가 5분 후 도착해요."),
            run(at(22, 42), at(22, 47)),
        )
    }

    @Test
    fun `a subway line uses the train sentence`() {
        assertNull(GoogleCountdown.onStep(wait("오후 10:33", line = "3호선"), at(22, 18)))
        assertEquals("3호선이 5분 후 도착합니다.", GoogleCountdown.onTick(at(22, 28)))
        assertEquals("3호선, 곧 도착합니다.", GoogleCountdown.onTick(at(22, 32)))
    }

    @Test
    fun `trip end is silent`() {
        GoogleCountdown.onStep(wait("오후 10:33"), at(22, 18))
        GoogleCountdown.reset()
        assertEquals(emptyList<String>(), run(at(22, 18), at(22, 34)))
    }
}
