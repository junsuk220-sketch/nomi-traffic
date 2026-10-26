package nomi.android.traffic.api.nextbus

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NextBusNoInfoTest {

    @Before
    fun setUp() {
        NextBusNoInfo.resetForTest()
    }

    @After
    fun tearDown() {
        NextBusNoInfo.resetForTest()
    }

    @Test
    fun `seoul national univ alight speaks no info for 관악07 once`() {
        NextBusNoInfo.observeScreen(screen("도착 예정 정보 없음"), 1_000L)

        val out = NextBusNoInfo.onCues(alightCues(), 400_000L)

        assertEquals("관악07 도착 정보가 아직 없습니다.", out?.text)
        assertEquals("21540", out?.stopNo)
        assertTrue(NextBusNoInfo.stillNoInfo("21540"))
        assertNull(NextBusNoInfo.onCues(alightCues(), 401_000L))
    }

    @Test
    fun `minutes on the last screen stay silent`() {
        NextBusNoInfo.observeScreen(screen("3분", "2정류장"), 1_000L)

        val out = NextBusNoInfo.onCues(alightCues(), 2_000L)

        assertNull(out?.text)
        assertTrue(out?.note.orEmpty().startsWith("has_info"))
    }

    @Test
    fun `latest screen wins after minutes turn back to no info`() {
        NextBusNoInfo.observeScreen(screen("3분", "2정류장"), 1_000L)
        NextBusNoInfo.observeScreen(screen("도착 예정 정보 없음"), 2_000L)

        assertEquals("관악07 도착 정보가 아직 없습니다.", NextBusNoInfo.onCues(alightCues(), 3_000L)?.text)
    }

    @Test
    fun `scrolled screen keeps the alight learned earlier`() {
        NextBusNoInfo.observeScreen(screen("3분", "2정류장"), 1_000L)
        NextBusNoInfo.observeScreen(
            listOf(
                "서울대입구역2호선 승차", "21540", "관악07", "마을",
                "도착 예정 정보 없음", "과거 시간표", "버스 도착정보 더보기",
            ),
            2_000L,
        )

        assertEquals("관악07 도착 정보가 아직 없습니다.", NextBusNoInfo.onCues(alightCues(), 3_000L)?.text)
    }

    @Test
    fun `minutes arriving before playback drop the line`() {
        NextBusNoInfo.observeScreen(screen("도착 예정 정보 없음"), 1_000L)
        NextBusNoInfo.onCues(alightCues(), 2_000L)

        NextBusNoInfo.observeScreen(screen("5분", "3정류장"), 3_000L)

        assertFalse(NextBusNoInfo.stillNoInfo("21540"))
    }

    @Test
    fun `loading block without closing mark is ignored`() {
        NextBusNoInfo.observeScreen(
            listOf(
                "서울대입구역 하차", "서울대입구역", "7", "번 출구에서", "도보 193m", "5분",
                "서울대입구역2호선 승차", "21540", "안내 중", "최소환승\u00b738분",
            ),
            1_000L,
        )

        val out = NextBusNoInfo.onCues(alightCues(), 2_000L)

        assertNull(out?.text)
        assertEquals("no_stop alight=서울대입구", out?.note)
    }

    @Test
    fun `other alight station does not use this stop`() {
        NextBusNoInfo.observeScreen(screen("도착 예정 정보 없음"), 1_000L)

        val out = NextBusNoInfo.onCues(
            listOf("이번 역(신림역)에서 하차 후 환승", "내리는 문 오른쪽"),
            2_000L,
        )

        assertNull(out?.text)
    }

    @Test
    fun `plain alight without transfer is not a trigger`() {
        NextBusNoInfo.observeScreen(screen("도착 예정 정보 없음"), 1_000L)

        assertNull(NextBusNoInfo.onCues(listOf("이번 역에서 하차", "서울대입구역"), 2_000L))
    }

    @Test
    fun `guidance start clears memory`() {
        NextBusNoInfo.observeScreen(screen("도착 예정 정보 없음"), 1_000L)
        NextBusNoInfo.onCues(listOf("길안내를 시작합니다."), 2_000L)

        val out = NextBusNoInfo.onCues(alightCues(), 3_000L)

        assertNull(out?.text)
        assertEquals("no_stop alight=서울대입구", out?.note)
    }

    @Test
    fun `several lines all without info are named together`() {
        NextBusNoInfo.observeScreen(
            listOf(
                "봉천역 하차", "봉천역", "2", "번 출구에서", "도보 100m", "2분",
                "봉천역 승차", "21111", "5413", "도착 예정 정보 없음",
                "6512", "도착 예정 정보 없음", "버스 도착정보 더보기",
            ),
            1_000L,
        )

        val out = NextBusNoInfo.onCues(listOf("이번 역(봉천역)에서 하차 후 환승"), 2_000L)

        assertEquals("5413, 6512 도착 정보가 아직 없습니다.", out?.text)
    }

    @Test
    fun `parser splits lines with and without minutes`() {
        val stops = NextBusStopParser.parse(
            listOf(
                "라페스타.먹자골목 승차", "58117", "81", "7분", "8정류장", "여유",
                "99", "도착 예정 정보 없음", "버스 도착정보 더보기",
            ),
            1_000L,
        )

        assertEquals(listOf("81"), stops.single().infoLines)
        assertEquals(listOf("99"), stops.single().noInfoLines)
    }

    private fun screen(vararg status: String): List<String> =
        listOf(
            "9개 역 이동", "19분", "서울대입구역 하차", "서울대입구역", "7", "번 출구에서",
            "도보 193m", "5분", "서울대입구역2호선 승차", "21540", "관악07", "마을",
        ) + status.toList() + listOf(
            "과거 시간표", "버스 도착정보 더보기", "4개 정류장 이동", "7분",
            "우성아파트103동 하차", "21580",
        )

    private fun alightCues(): List<String> =
        listOf("이번 역(서울대입구역)에서 하차 후 환승", "내리는 문 왼쪽, 7번 출구로 나가기")
}
