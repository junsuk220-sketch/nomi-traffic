package nomi.android.traffic

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NaverNearBoardNoticeTest {

    @Before
    fun reset() {
        NaverNearBoardNotice.reset()
    }

    @After
    fun tearDown() {
        NaverNearBoardNotice.reset()
    }

    @Test
    fun `naver near-stop wording arms`() {
        assertFalse(NaverNearBoardNotice.isArmed())
        assertTrue(NaverNearBoardNotice.note("승차정류장 부근입니다."))
        assertTrue(NaverNearBoardNotice.isArmed())
        assertFalse(NaverNearBoardNotice.note("승차정류장 부근입니다."))
    }

    @Test
    fun `spoken 승차역 부근에 도착합니다 arms`() {
        assertTrue(NaverNearBoardNotice.note("승차역 부근에 도착했습니다."))
        assertTrue(NaverNearBoardNotice.isArmed())
    }

    @Test
    fun `spoken 승착역 부근 also arms`() {
        assertTrue(NaverNearBoardNotice.note("승착역 부근입니다."))
        assertTrue(NaverNearBoardNotice.isArmed())
    }

    @Test
    fun `walk-to-station title does not arm`() {
        assertFalse(NaverNearBoardNotice.note("정발산역 3호선까지 걷기"))
        assertFalse(NaverNearBoardNotice.isArmed())
    }

    @Test
    fun `board-title flip during the walk does not arm`() {
        assertFalse(NaverNearBoardNotice.note("정발산역 3호선 도보 후 열차 승차"))
        assertFalse(NaverNearBoardNotice.isArmed())
    }

    @Test
    fun `bare 역 부근 during the walk does not arm`() {
        assertFalse(NaverNearBoardNotice.note("정발산역 부근"))
        assertFalse(NaverNearBoardNotice.isArmed())
    }
}
