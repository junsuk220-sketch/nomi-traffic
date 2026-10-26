package nomi.android.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NaverFirstLegTest {

    private val subwayTop = listOf(
        "경기 가나시 다라로 1",
        "도보 600m",
        "8분",
        "3호선 가나역 승차",
        "대화방면",
        "곧 도착",
        "12개 역 이동",
        "다라역 하차",
        "안내 중",
    )

    private val busTop = listOf(
        "경기 가나시 다라로 1",
        "도보 200m",
        "3분",
        "가나정류장 승차",
        "81",
        "2분",
        "3정류장",
        "99",
        "9분",
        "6정류장",
        "버스 도착정보 더보기",
        "5개 정류장 이동",
        "안내 중",
    )

    @Test
    fun subwayBoardAtTopLocksItsLine() {
        val leg = NaverFirstLeg.read(subwayTop)
        assertNotNull(leg)
        assertTrue(leg!!.subway)
        assertEquals(setOf("3호선"), leg.lines)
        assertEquals("곧 도착", leg.block.last())
    }

    @Test
    fun busBoardAtTopLocksEveryListedLine() {
        val leg = NaverFirstLeg.read(busTop)
        assertNotNull(leg)
        assertFalse(leg!!.subway)
        assertEquals(setOf("81", "99"), leg.lines)
    }

    @Test
    fun scrolledSheetDoesNotLock() {
        val scrolled = listOf(
            "12개 역 이동",
            "다라역 하차",
            "도보 300m",
            "다라정류장 승차",
            "33",
            "5분",
            "4정류장",
            "안내 중",
        )
        assertNull(NaverFirstLeg.read(scrolled))
    }

    @Test
    fun boardWithoutSheetTopDoesNotLock() {
        val noTop = listOf("다라정류장 승차", "33", "5분", "4정류장", "안내 중")
        assertNull(NaverFirstLeg.read(noTop))
    }

    @Test
    fun hudSummaryIsFallback() {
        val hud = listOf("안내 중", "도보 11분, 3호선 19분, 도보 4분, 093번 마을 버스 3분")
        assertEquals(setOf("3호선"), NaverFirstLeg.read(hud)?.lines)
        val bus = listOf("안내 중", "도보 4분, 99번 일반 버스 4분, 도보 3분")
        val leg = NaverFirstLeg.read(bus)
        assertEquals(setOf("99"), leg?.lines)
        assertFalse(leg!!.subway)
    }

    @Test
    fun noLiveMarkerNoLeg() {
        assertNull(NaverFirstLeg.read(subwayTop.dropLast(1)))
    }

    @Test
    fun walkTitleLocksSubwayOnly() {
        assertEquals(setOf("3호선"), NaverFirstLeg.fromWalkTitle("가나역 3호선까지 걷기")?.lines)
        assertNull(NaverFirstLeg.fromWalkTitle("가나정류장까지 걷기"))
        assertNull(NaverFirstLeg.fromWalkTitle(null))
    }

    @Test
    fun allowsOnlyLockedVehicle() {
        val subway = NaverFirstLeg.read(subwayTop)
        assertTrue(NaverFirstLeg.allows("3호선이 곧 도착합니다.", subway))
        assertTrue(NaverFirstLeg.allows("3호선", subway))
        assertFalse(NaverFirstLeg.allows("033번, 033번 버스가 5분 뒤 도착합니다.", subway))

        val bus = NaverFirstLeg.read(busTop)
        assertTrue(NaverFirstLeg.allows("99번, 99번 버스가 3분 뒤 도착합니다.", bus))
        assertTrue(NaverFirstLeg.allows("81", bus))
        assertFalse(NaverFirstLeg.allows("3호선", bus))

        assertTrue(NaverFirstLeg.allows("아무거나", null))
        assertTrue(NaverFirstLeg.allows(null, bus))
    }

    @Test
    fun sessionLocksOnceWithinWindow() {
        NaverTripStartSession.reset()
        val sub = NaverFirstLeg.read(subwayTop)!!
        val bus = NaverFirstLeg.read(busTop)!!
        assertFalse(NaverTripStartSession.noteFirstLeg(sub, 1_000L))
        assertTrue(NaverTripStartSession.request(1_000L))
        assertFalse(NaverTripStartSession.noteFirstLeg(sub, 1_000L + NaverTripStartSession.FIRST_LEG_WINDOW_MS + 1))
        NaverTripStartSession.reset()
        assertTrue(NaverTripStartSession.request(1_000L))
        assertTrue(NaverTripStartSession.noteFirstLeg(sub, 2_000L))
        assertFalse(NaverTripStartSession.noteFirstLeg(bus, 3_000L))
        assertEquals(sub.lines, NaverTripStartSession.firstLeg()?.lines)
        NaverTripStartSession.reset()
        assertNull(NaverTripStartSession.firstLeg())
    }
}
