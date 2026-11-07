package nomi.android.traffic.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechPronounceTest {

    @Test
    fun `2650 is spoken digit by digit with gong for zero`() {
        assertEquals("이육오공", SpeechPronounce.speakRoute("2650"))
        assertEquals(
            "이육오공번, 이육오공번 버스가 5분 후 도착합니다.",
            SpeechPronounce.forTts("2650번, 2650번 버스가 5분 후 도착합니다."),
        )
    }

    @Test
    fun `hyphenated 70-3 keeps both halves with dasi`() {
        assertEquals("칠공다시삼", SpeechPronounce.speakRoute("70-3"))
        assertEquals(
            "칠공다시삼번, 칠공다시삼번 버스, 곧 도착합니다. 다음은 칠공다시삼번, 9분 후 도착입니다.",
            SpeechPronounce.forTts(
                "70-3번, 70-3번 버스, 곧 도착합니다. 다음은 70-3번, 9분 후 도착입니다.",
            ),
        )
    }

    @Test
    fun `letter suffix bus keeps the letter`() {
        assertEquals("팔팔B", SpeechPronounce.speakRoute("88B"))
        assertEquals(
            "팔팔B번, 팔팔B번 버스가 2분 후 도착합니다.",
            SpeechPronounce.forTts("88B번, 88B번 버스가 2분 후 도착합니다."),
        )
    }

    @Test
    fun `subway line minutes stay cardinal for TTS engine`() {
        assertEquals(
            "3호선이 2분 후 출발합니다.",
            SpeechPronounce.forTts("3호선이 2분 후 출발합니다."),
        )
    }
}
