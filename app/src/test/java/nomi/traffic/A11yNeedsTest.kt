package nomi.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class A11yNeedsTest {

    @Test
    fun `google maps only asks one`() {
        assertEquals(listOf(A11yItem.GOOGLE), A11yNeeds.needed(googleInstalled = true, naverInstalled = false))
    }

    @Test
    fun `naver map only asks one`() {
        assertEquals(listOf(A11yItem.NAVER), A11yNeeds.needed(googleInstalled = false, naverInstalled = true))
    }

    @Test
    fun `both apps ask both`() {
        assertEquals(
            listOf(A11yItem.GOOGLE, A11yItem.NAVER),
            A11yNeeds.needed(googleInstalled = true, naverInstalled = true),
        )
    }

    @Test
    fun `no map app keeps asking both`() {
        assertEquals(
            listOf(A11yItem.GOOGLE, A11yItem.NAVER),
            A11yNeeds.needed(googleInstalled = false, naverInstalled = false),
        )
    }

    @Test
    fun `google installed later with naver on shows only google`() {
        val needed = A11yNeeds.needed(googleInstalled = true, naverInstalled = true)
        assertEquals(listOf(A11yItem.GOOGLE), A11yNeeds.off(needed) { it == A11yItem.NAVER })
    }

    @Test
    fun `both off shows both`() {
        val needed = A11yNeeds.needed(googleInstalled = true, naverInstalled = true)
        assertEquals(listOf(A11yItem.GOOGLE, A11yItem.NAVER), A11yNeeds.off(needed) { false })
    }

    @Test
    fun `all on shows none`() {
        val needed = A11yNeeds.needed(googleInstalled = true, naverInstalled = true)
        assertEquals(emptyList<A11yItem>(), A11yNeeds.off(needed) { true })
    }

    @Test
    fun `done notice only shortly after setup opened the screen`() {
        assertTrue(A11yDoneNotice.shouldShow(armedAtMs = 1_000L, nowMs = 5_000L))
        assertFalse(A11yDoneNotice.shouldShow(armedAtMs = 1_000L, nowMs = 1_000L + A11yDoneNotice.WINDOW_MS + 1))
        assertFalse(A11yDoneNotice.shouldShow(armedAtMs = 0L, nowMs = 5_000L))
    }
}
