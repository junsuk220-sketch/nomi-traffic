package nomi.android.traffic.api.seoulcountdown

import nomi.android.traffic.api.eta.EtaBand
import nomi.android.traffic.observe.TransitRow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SeoulSubwayCountdownTest {
    @Before
    fun setUp() = SeoulSubwayCountdown.reset()

    @After
    fun tearDown() = SeoulSubwayCountdown.reset()

    @Test
    fun `initial transfer briefing seeds its current band`() {
        SeoulSubwayCountdown.arm(
            "경의중앙선",
            rows("경의중앙선", "10분", "14분"),
        )
        assertEquals(setOf(EtaBand.TEN), SeoulSubwayCountdown.spokenBands())
        assertNull(
            SeoulSubwayCountdown.consider(rows("경의중앙선", "9분", "13분")).text,
        )
    }

    @Test
    fun `Seoul rows continue through five two and soon once each`() {
        SeoulSubwayCountdown.arm("경의중앙선", rows("경의중앙선", "10분", "14분"))

        assertEquals(
            "경의중앙선이 5분 후 출발합니다. 다음 열차는 9분 후 도착입니다.",
            SeoulSubwayCountdown.consider(rows("경의중앙선", "5분", "9분")).text,
        )
        assertNull(SeoulSubwayCountdown.consider(rows("경의중앙선", "4분", "8분")).text)
        assertEquals(
            "경의중앙선이 2분 후 출발합니다. 다음 열차는 6분 후 도착입니다.",
            SeoulSubwayCountdown.consider(rows("경의중앙선", "2분", "6분")).text,
        )
        assertEquals(
            "경의중앙선, 곧 도착합니다. 다음 열차는 4분 후 도착입니다.",
            SeoulSubwayCountdown.consider(rows("경의중앙선", "곧 도착", "4분")).text,
        )
        assertNull(
            SeoulSubwayCountdown.consider(rows("경의중앙선", "곧 도착", "3분")).text,
        )
    }

    @Test
    fun `different line never drives the armed countdown`() {
        SeoulSubwayCountdown.arm("경의중앙선", rows("경의중앙선", "10분"))
        assertNull(SeoulSubwayCountdown.consider(rows("3호선", "5분")).text)
    }

    @Test
    fun `starting inside a finer band does not replay coarser bands`() {
        SeoulSubwayCountdown.arm("3호선", rows("3호선", "2분"))
        assertEquals(
            setOf(EtaBand.TEN, EtaBand.FIVE, EtaBand.TWO),
            SeoulSubwayCountdown.spokenBands(),
        )
        assertNull(SeoulSubwayCountdown.consider(rows("3호선", "5분")).text)
        assertEquals(
            "3호선, 곧 도착합니다. 다음 열차 정보는 아직 없습니다.",
            SeoulSubwayCountdown.consider(rows("3호선", "곧 도착")).text,
        )
    }

    @Test
    fun `reopen after the soon train left speaks the next train inside ten minutes`() {
        SeoulSubwayCountdown.arm("3호선", rows("3호선", "곧 도착"))
        assertNull(SeoulSubwayCountdown.consider(rows("3호선", "8분")).text)
        SeoulSubwayCountdown.reopen()
        assertEquals(
            "3호선이 8분 후 출발합니다. 다음 열차 정보는 아직 없습니다.",
            SeoulSubwayCountdown.consider(rows("3호선", "8분")).text,
        )
        assertNull(SeoulSubwayCountdown.consider(rows("3호선", "6분")).text)
        assertEquals(
            "3호선이 4분 후 출발합니다. 다음 열차 정보는 아직 없습니다.",
            SeoulSubwayCountdown.consider(rows("3호선", "4분")).text,
        )
        assertEquals(
            "3호선이 2분 후 출발합니다. 다음 열차 정보는 아직 없습니다.",
            SeoulSubwayCountdown.consider(rows("3호선", "2분")).text,
        )
        assertEquals(
            "3호선, 곧 도착합니다. 다음 열차 정보는 아직 없습니다.",
            SeoulSubwayCountdown.consider(rows("3호선", "곧 도착")).text,
        )
    }

    @Test
    fun `reopen does not invent a rung above ten minutes`() {
        SeoulSubwayCountdown.arm("3호선", rows("3호선", "곧 도착"))
        SeoulSubwayCountdown.reopen()
        assertNull(SeoulSubwayCountdown.consider(rows("3호선", "20분")).text)
        assertEquals(
            "3호선이 9분 후 출발합니다. 다음 열차 정보는 아직 없습니다.",
            SeoulSubwayCountdown.consider(rows("3호선", "9분")).text,
        )
    }

    private fun rows(line: String, vararg eta: String): List<TransitRow> =
        eta.map { TransitRow(line, it) }
}
