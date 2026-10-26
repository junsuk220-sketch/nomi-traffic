package nomi.android.traffic.setupnudge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupNudgeTest {

    private val minute = 60_000L

    @Test
    fun `off speaks once then stays quiet`() {
        val nudge = SetupNudge()
        assertTrue(nudge.shouldSpeak(SetupGap.GOOGLE_A11Y, off = true, nowMillis = 0))
        assertFalse(nudge.shouldSpeak(SetupGap.GOOGLE_A11Y, off = true, nowMillis = 5 * minute))
        assertFalse(nudge.shouldSpeak(SetupGap.GOOGLE_A11Y, off = true, nowMillis = 120 * minute))
    }

    @Test
    fun `on never speaks`() {
        val nudge = SetupNudge()
        assertFalse(nudge.shouldSpeak(SetupGap.GOOGLE_A11Y, off = false, nowMillis = 0))
    }

    @Test
    fun `speaks again after quiet time`() {
        val nudge = SetupNudge()
        assertTrue(nudge.shouldSpeak(SetupGap.NAVER_A11Y, off = true, nowMillis = 0))
        assertTrue(nudge.shouldSpeak(SetupGap.NAVER_A11Y, off = true, nowMillis = SetupNudge.QUIET_MILLIS))
    }

    @Test
    fun `turned on then off again speaks again`() {
        val nudge = SetupNudge()
        assertTrue(nudge.shouldSpeak(SetupGap.LISTENER, off = true, nowMillis = 0))
        assertFalse(nudge.shouldSpeak(SetupGap.LISTENER, off = false, nowMillis = minute))
        assertTrue(nudge.shouldSpeak(SetupGap.LISTENER, off = true, nowMillis = 2 * minute))
    }

    @Test
    fun `gaps are independent`() {
        val nudge = SetupNudge()
        assertTrue(nudge.shouldSpeak(SetupGap.GOOGLE_A11Y, off = true, nowMillis = 0))
        assertTrue(nudge.shouldSpeak(SetupGap.NAVER_A11Y, off = true, nowMillis = 0))
    }

    @Test
    fun `speech names the switch and how to fix`() {
        val tap = SetupNudge.speech(SetupGap.GOOGLE_A11Y, canTapNotice = true)
        assertTrue(tap.contains("구글 교통을 켜 주세요"))
        assertTrue(tap.contains("알림을 누르면"))
        val noTap = SetupNudge.speech(SetupGap.GOOGLE_A11Y, canTapNotice = false)
        assertTrue(noTap.contains("앱을 열어 주세요"))
    }
}
