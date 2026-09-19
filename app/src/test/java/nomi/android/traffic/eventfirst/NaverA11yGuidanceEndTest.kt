package nomi.android.traffic.eventfirst

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The a11y end phrase is an exact blob, not a substring and not the on-screen
 * 안내 종료 button that stays up while guidance is still live.
 */
class NaverA11yGuidanceEndTest {

    @Test
    fun `the captured toast is an end`() {
        assertTrue(NaverA11yGuidanceEnd.read(listOf("길안내를 종료합니다.")))
    }

    @Test
    fun `the phrase without a period is still an end`() {
        assertTrue(NaverA11yGuidanceEnd.read(listOf("길안내를 종료합니다")))
    }

    @Test
    fun `surrounding blobs do not hide the toast`() {
        assertTrue(NaverA11yGuidanceEnd.read(listOf("광닭발", "길안내를 종료합니다.")))
    }

    @Test
    fun `leading space is trimmed`() {
        assertTrue(NaverA11yGuidanceEnd.read(listOf("  길안내를 종료합니다.  ")))
    }

    @Test
    fun `the end button on a live sheet is not an end`() {
        assertFalse(
            NaverA11yGuidanceEnd.read(
                listOf("안내 종료", "안내중인 경로 바로가기", "광닭발"),
            ),
        )
    }

    @Test
    fun `a longer sentence that only contains the phrase is not an end`() {
        assertFalse(NaverA11yGuidanceEnd.read(listOf("길안내를 종료합니다. 더보기")))
        assertFalse(NaverA11yGuidanceEnd.matches("지금 길안내를 종료합니다."))
    }

    @Test
    fun `start and wait blobs are not an end`() {
        assertFalse(NaverA11yGuidanceEnd.read(listOf("길안내를 시작합니다.")))
        assertFalse(NaverA11yGuidanceEnd.read(listOf("바로 안내시작")))
        assertFalse(NaverA11yGuidanceEnd.read(listOf("안내 중", "81", "곧 도착")))
        assertFalse(NaverA11yGuidanceEnd.read(emptyList()))
    }
}
