package nomi.android.traffic.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechMouthTest {

    @Test
    fun `walk briefing then walk countdown is one fact`() {
        val (line, spoken) = SpeechMouth.admit(
            "81번 버스가 9분 후 도착합니다. 정류장까지는 걸어서 약 3분입니다.",
            0L,
            null,
        )!!
        assertEquals("81번 버스가 9분 후 도착합니다. 정류장까지는 걸어서 약 3분입니다.", line)
        assertNull(SpeechMouth.admit("도보 1분 남았습니다.", 6_000L, spoken))
    }

    @Test
    fun `same walk fact speaks again after the window`() {
        val (_, spoken) = SpeechMouth.admit("정류장까지는 걸어서 약 3분입니다.", 0L, null)!!
        val again = SpeechMouth.admit("도보 1분 남았습니다.", SpeechMouth.WINDOW_MILLIS + 1, spoken)
        assertEquals("도보 1분 남았습니다.", again!!.first)
    }

    @Test
    fun `same bus line close together is swallowed`() {
        val (_, spoken) = SpeechMouth.admit("95번, 95번 버스가 4분 후 도착합니다.", 0L, null)!!
        assertNull(SpeechMouth.admit("95번 버스 2분 후 도착합니다.", 10_000L, spoken))
    }

    @Test
    fun `a different line is not the same fact`() {
        val (_, spoken) = SpeechMouth.admit("95번 버스가 4분 후 도착합니다.", 0L, null)!!
        val next = SpeechMouth.admit("707번 버스 5분 후 도착합니다.", 5_000L, spoken)
        assertEquals("707번 버스 5분 후 도착합니다.", next!!.first)
    }

    @Test
    fun `train and the bus after it both speak`() {
        val (_, spoken) = SpeechMouth.admit("3호선이 2분 후 출발합니다.", 0L, null)!!
        val bus = SpeechMouth.admit("95번 버스가 5분 후 도착합니다.", 4_000L, spoken)
        assertEquals("95번 버스가 5분 후 도착합니다.", bus!!.first)
    }

    @Test
    fun `a later subway rung is not the same fact`() {
        val (_, spoken) = SpeechMouth.admit(
            "3호선 오금행 열차가 5분 후 도착합니다.",
            0L,
            null,
        )!!
        val two = SpeechMouth.admit("3호선 오금행 열차가 2분 후 도착합니다.", 15_000L, spoken)
        assertEquals("3호선 오금행 열차가 2분 후 도착합니다.", two!!.first)
    }

    @Test
    fun `the same subway rung inside the window is swallowed`() {
        val (_, spoken) = SpeechMouth.admit(
            "3호선이 3분 후 출발합니다.",
            0L,
            null,
        )!!
        assertNull(
            SpeechMouth.admit("3호선 오금행 열차가 5분 후 도착합니다.", 12_000L, spoken),
        )
    }

    @Test
    fun `blank line never speaks`() {
        assertNull(SpeechMouth.admit("   ", 0L, null))
    }
}
