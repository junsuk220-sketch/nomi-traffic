package nomi.android.traffic.api.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransferBoardParserTest {

    @Test
    fun `line then station board`() {
        val board = TransferBoardParser.parse(
            listOf("3호선 정발산역 승차", "마두역 방면", "빠른 하차: 9-1"),
        )!!
        assertEquals("정발산역", board.station)
        assertEquals("정발산", board.stationApiName)
        assertEquals("3호선", board.line)
        assertEquals("마두역", board.direction)
        assertEquals("9-1", board.cars)
    }

    @Test
    fun `station then line walk title`() {
        val board = TransferBoardParser.parse(
            listOf("정발산역 3호선까지 걷기"),
        )!!
        assertEquals("정발산역", board.station)
        assertEquals("3호선", board.line)
        assertNull(board.direction)
    }

    @Test
    fun `unrelated cues stay null`() {
        assertNull(TransferBoardParser.parse(listOf("이번 정류장에서 하차 후 환승", "롯데백화점")))
    }
}
