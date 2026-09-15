package nomi.android.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NaverTransitDestinationParserTest {

    @Test
    fun `place then until-move is the destination`() {
        assertEquals(
            "원흥역",
            NaverTransitDestinationParser.destination(
                listOf("원흥역 3호선", "까지 이동"),
            ),
        )
    }

    @Test
    fun `single blob until-move keeps the place`() {
        assertEquals(
            "원흥역",
            NaverTransitDestinationParser.destination(
                listOf("원흥역 3호선까지 이동"),
            ),
        )
    }

    @Test
    fun `alight stop is not the final destination`() {
        assertNull(
            NaverTransitDestinationParser.destination(
                listOf("원흥역 하차", "안내 중"),
            ),
        )
    }

    @Test
    fun `notification-style current step is not a destination`() {
        assertNull(
            NaverTransitDestinationParser.destination(
                listOf("정발산역 3호선 도보 후 열차 승차"),
            ),
        )
    }

    @Test
    fun `start button without until-move does not invent a destination`() {
        assertNull(
            NaverTransitDestinationParser.destination(
                listOf("미리보기", "안내시작", "대중교통"),
            ),
        )
        assertEquals(
            false,
            NaverTransitDestinationParser.isLiveGuidance(
                listOf("미리보기", "안내시작", "대중교통"),
            ),
        )
    }

    @Test
    fun `live hud until-move is live guidance`() {
        val blobs = listOf("원흥역 3호선", "까지 이동", "안내 중")
        assertEquals("원흥역", NaverTransitDestinationParser.destination(blobs))
        assertEquals(true, NaverTransitDestinationParser.isLiveGuidance(blobs))
    }

    @Test
    fun `until-move subway label is the final destination`() {
        assertEquals(
            "대화역",
            NaverTransitDestinationParser.destination(listOf("대화역 3호선까지 이동")),
        )
        assertEquals(
            "원흥역",
            NaverTransitDestinationParser.destination(listOf("원흥역 3호선까지 이동")),
        )
    }

    @Test
    fun `until-walk notification title is not the final destination`() {
        assertNull(
            NaverTransitDestinationParser.destination(listOf("일산동부경찰서(중)까지 걷기")),
        )
        assertNull(
            NaverTransitDestinationParser.destination(listOf("정발산역 3호선까지 걷기")),
        )
        assertNull(
            NaverTransitDestinationParser.destination(
                listOf("일산동부경찰서(중) 도보 후 버스 승차"),
            ),
        )
    }

    @Test
    fun `bus eta 302 text is not a destination`() {
        assertNull(
            NaverTransitDestinationParser.destination(
                listOf("길안내를 시작합니다", "98 (곧 도착), 67 (2분)"),
            ),
        )
    }
}
