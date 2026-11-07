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
    fun `hyphenated bus keeps full subject not the trailing digit`() {
        val (_, spoken) = SpeechMouth.admit(
            "70-3번, 70-3번 버스, 곧 도착합니다.",
            0L,
            null,
        )!!
        assertEquals(SpeechMouth.Kind.BUS, spoken.fact.kind)
        assertEquals("70-3", spoken.fact.subject)
        assertNull(
            SpeechMouth.admit("70-3번, 70-3번 버스가 6분 후 도착해요.", 5_000L, spoken),
        )
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

    /** 2026-11-02 15:25: the 3호선 brief swallowed the 707 brief, both end with a walk time. */
    @Test
    fun `a new bus after a train is spoken though both mention a walk`() {
        val (_, spoken) = SpeechMouth.admit(
            "3호선이 12분 후 출발합니다. 역까지는 걸어서 약 10분입니다.", 0L, null,
        )!!
        val bus = SpeechMouth.admit(
            "707번, 707번 버스가 5분 후 도착합니다. 정류장까지는 걸어서 약 6분입니다.", 13_000L, spoken,
        )
        assertEquals(
            "707번, 707번 버스가 5분 후 도착합니다. 정류장까지는 걸어서 약 6분입니다.", bus!!.first,
        )
    }

    @Test
    fun `walk after a brief that already said the walk is swallowed`() {
        val (_, spoken) = SpeechMouth.admit(
            "707번, 707번 버스가 5분 후 도착합니다. 정류장까지는 걸어서 약 6분입니다.", 0L, null,
        )!!
        assertNull(SpeechMouth.admit("걸어서 약 1분입니다.", 8_000L, spoken))
    }

    @Test
    fun `the same count sentence twice inside two minutes is swallowed`() {
        val line = "연희동까지 정류장 24개, 46분 남았습니다."
        val (_, spoken) = SpeechMouth.admit(line, 0L, null)!!
        assertNull(SpeechMouth.admit(line, 60_000L, spoken))
    }

    @Test
    fun `blank line never speaks`() {
        assertNull(SpeechMouth.admit("   ", 0L, null))
    }
}
