package nomi.android.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaverActiveGuidanceSliceTest {

    @Test
    fun `slice is inclusive from 안내 중 through 안내 종료`() {
        val slice = NaverActiveGuidanceSlice.from(
            listOf(
                "안내중인 경로 바로가기",
                "독바위역 6호선",
                "안내 중",
                "55분",
                "3호선",
                "정발산역",
                "오금행",
                "안내 종료",
                "최소시간",
                "53분",
                "도보 2분, 99번 일반 버스 2분",
                "바로 안내시작",
            ),
        )
        assertEquals(
            listOf("안내 중", "55분", "3호선", "정발산역", "오금행", "안내 종료"),
            slice,
        )
        assertTrue("99" !in slice.joinToString())
        assertTrue(slice.none { it.contains("99") })
        assertTrue("바로 안내시작" !in slice)
        assertTrue("안내중인 경로 바로가기" !in slice)
    }

    @Test
    fun `alternative cards before 안내 중 are dropped`() {
        val slice = NaverActiveGuidanceSlice.from(
            listOf(
                "최소시간",
                "53분",
                "99번 일반 버스 2분",
                "바로 안내시작",
                "안내 중",
                "55분",
                "3호선",
                "안내 종료",
            ),
        )
        assertEquals(listOf("안내 중", "55분", "3호선", "안내 종료"), slice)
        assertTrue(slice.none { it.contains("99") })
    }

    @Test
    fun `missing 안내 중 returns empty`() {
        assertTrue(
            NaverActiveGuidanceSlice.from(
                listOf("최소시간", "53분", "99", "2분", "바로 안내시작"),
            ).isEmpty(),
        )
    }

    @Test
    fun `missing 안내 종료 returns empty`() {
        assertTrue(
            NaverActiveGuidanceSlice.from(
                listOf("안내 중", "55분", "3호선", "99", "2분"),
            ).isEmpty(),
        )
    }
}
