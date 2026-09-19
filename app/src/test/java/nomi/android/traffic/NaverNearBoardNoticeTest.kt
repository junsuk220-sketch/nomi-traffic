package nomi.android.traffic

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Observation tier (constitution 8-1 · 부록 E): this notice only records that the
 * scrap appeared. `note` returning true means "log it once", not "now speak".
 * There is no reader for the state on purpose — see the 2026-09-18 revision.
 */
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
    fun `the boarding-stop wording is recorded once`() {
        assertTrue(NaverNearBoardNotice.note("승차정류장 부근입니다."))
        assertFalse(NaverNearBoardNotice.note("승차정류장 부근입니다."))
    }

    @Test
    fun `spoken 승차역 부근에 도착합니다 is recorded`() {
        assertTrue(NaverNearBoardNotice.note("승차역 부근에 도착했습니다."))
    }

    @Test
    fun `spoken 승착역 부근 is recorded`() {
        assertTrue(NaverNearBoardNotice.note("승착역 부근입니다."))
    }

    @Test
    fun `walk-to-station title is not the boarding stop`() {
        assertFalse(NaverNearBoardNotice.note("정발산역 3호선까지 걷기"))
    }

    @Test
    fun `board-title flip during the walk is not the boarding stop`() {
        assertFalse(NaverNearBoardNotice.note("정발산역 3호선 도보 후 열차 승차"))
    }

    @Test
    fun `bare 역 부근 during the walk is not the boarding stop`() {
        assertFalse(NaverNearBoardNotice.note("정발산역 부근"))
    }

    @Test
    fun `a walk scrap does not consume the latch`() {
        assertFalse(NaverNearBoardNotice.note("정발산역 부근"))
        assertTrue(NaverNearBoardNotice.note("승차역 부근입니다."))
    }

    @Test
    fun `a new journey can record it again`() {
        assertTrue(NaverNearBoardNotice.note("승차역 부근입니다."))
        NaverNearBoardNotice.reset()
        assertTrue(NaverNearBoardNotice.note("승차역 부근입니다."))
    }
}
