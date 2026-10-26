package nomi.android.traffic.api.nexttrain

import nomi.android.traffic.observe.TransitRow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NextTrainTest {
    @Before
    fun setUp() = NextTrain.reset()

    @After
    fun tearDown() = NextTrain.reset()

    @Test
    fun `later train before 곧 stays quiet`() {
        assertNull(NextTrain.onPoll(rows("4분")).text)
        assertNull(NextTrain.onPoll(rows("20분")).text)
        assertNull(NextTrain.onPoll(rows("20분")).text)
    }

    @Test
    fun `one later reading after 곧 does not speak`() {
        NextTrain.onPoll(rows("곧 도착"))
        assertNull(NextTrain.onPoll(rows("20분")).text)
    }

    @Test
    fun `two later readings after 곧 say that minute twice`() {
        NextTrain.onPoll(rows("곧 도착"))
        assertNull(NextTrain.onPoll(rows("20분")).text)
        val said = NextTrain.onPoll(rows("19분"))
        assertEquals("다음 열차는 19분, 19분 후 도착입니다.", said.text)
        assertTrue(said.departed)
    }

    @Test
    fun `the same departure is not said again`() {
        NextTrain.onPoll(rows("곧 도착"))
        NextTrain.onPoll(rows("20분"))
        NextTrain.onPoll(rows("20분"))
        assertNull(NextTrain.onPoll(rows("18분")).text)
    }

    @Test
    fun `a new 곧 train can announce the one after it`() {
        NextTrain.onPoll(rows("곧 도착"))
        NextTrain.onPoll(rows("20분"))
        NextTrain.onPoll(rows("20분"))
        NextTrain.onPoll(rows("곧 도착"))
        assertNull(NextTrain.onPoll(rows("8분")).text)
        val inside = NextTrain.onPoll(rows("8분"))
        assertNull(inside.text)
        assertTrue(inside.departed)
    }

    @Test
    fun `a 곧 between two later readings starts the count over`() {
        NextTrain.onPoll(rows("곧 도착"))
        assertNull(NextTrain.onPoll(rows("20분")).text)
        NextTrain.onPoll(rows("곧 도착"))
        assertNull(NextTrain.onPoll(rows("20분")).text)
        assertEquals(
            "다음 열차는 20분, 20분 후 도착입니다.",
            NextTrain.onPoll(rows("20분")).text,
        )
    }

    @Test
    fun `an empty poll between later readings does not confirm`() {
        NextTrain.onPoll(rows("곧 도착"))
        assertNull(NextTrain.onPoll(rows("20분")).text)
        assertNull(NextTrain.onPoll(emptyList()).text)
        assertNull(NextTrain.onPoll(rows("20분")).text)
        assertEquals(
            "다음 열차는 20분, 20분 후 도착입니다.",
            NextTrain.onPoll(rows("20분")).text,
        )
    }

    @Test
    fun `ten minutes or less does not say the far sentence`() {
        NextTrain.onPoll(rows("곧 도착"))
        NextTrain.onPoll(rows("10분"))
        val inside = NextTrain.onPoll(rows("10분"))
        assertNull(inside.text)
        assertTrue(inside.departed)
    }

    @Test
    fun `곧 still on the list keeps the later train quiet`() {
        NextTrain.onPoll(rows("곧 도착"))
        val both = listOf(TransitRow("3호선", "곧 도착"), TransitRow("3호선", "20분"))
        assertNull(NextTrain.onPoll(both).text)
        assertNull(NextTrain.onPoll(both).text)
    }

    private fun rows(eta: String) = listOf(TransitRow("3호선", eta))
}
