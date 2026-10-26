package nomi.android.traffic.api.google.prepare

import nomi.android.traffic.api.google.read.GoogleStepReader
import nomi.android.traffic.api.google.ride.GoogleRide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** 2026-09-26 20:30–20:36. Count 2 is this cell. The ride cell is not touched. */
class GooglePrepareTest {

    private val arrive = "오후 9:53 도착"

    private fun alight(text: String) =
        GoogleStepReader.read("저동초교삼거리에서 하차", text, arrive)!!

    @Before
    fun setUp() {
        GooglePrepare.resetForTest()
        GoogleRide.resetForTest()
    }

    @Test
    fun `two stops speaks once`() {
        assertNull(GooglePrepare.onStep(alight("정류장 4개 · 3분 · 999(으)로 환승 · 오후 8:56")))
        assertNull(GooglePrepare.onStep(alight("정류장 3개 · 3분 · 999(으)로 환승 · 오후 8:56")))
        assertEquals(
            "다음 정류장 저동초교삼거리에서 하차 준비하세요.",
            GooglePrepare.onStep(alight("정류장 2개 · 2분 · 999(으)로 환승 · 오후 8:56")),
        )
        assertNull(GooglePrepare.onStep(alight("정류장 2개 · 1분 · 999(으)로 환승 · 오후 8:56")))
    }

    @Test
    fun `next stop wording is not this cell`() {
        assertNull(GooglePrepare.onStep(alight("다음 경유지 · 1분 · 999(으)로 환승 · 오후 8:56")))
    }

    @Test
    fun `ride count is not this cell`() {
        val ride = GoogleStepReader.read("신원상가앞까지 이동", "정류장 37개 · 49분", "오후 9:27 도착")!!
        assertNull(GooglePrepare.onStep(ride))
    }

    @Test
    fun `ride cell still speaks its own count`() {
        val two = alight("정류장 2개 · 2분 · 999(으)로 환승 · 오후 8:56")
        GooglePrepare.onStep(two)
        assertNull(GoogleRide.onStep(two))
        val ride = GoogleStepReader.read("신원상가앞까지 이동", "정류장 37개 · 49분", "오후 9:27 도착")!!
        assertEquals("신원상가앞까지 정류장 37개, 49분 남았습니다.", GoogleRide.onStep(ride))
    }

    @Test
    fun `trip end lets the same stop speak again`() {
        val two = alight("정류장 2개 · 2분 · 999(으)로 환승 · 오후 8:56")
        GooglePrepare.onStep(two)
        GooglePrepare.reset()
        assertEquals("다음 정류장 저동초교삼거리에서 하차 준비하세요.", GooglePrepare.onStep(two))
    }

    @Test
    fun `subway unit is spoken as google wrote it`() {
        val step = GoogleStepReader.read("합정역에서 하차", "역 2개 · 3분", arrive)!!
        assertEquals("다음 역 합정역에서 하차 준비하세요.", GooglePrepare.onStep(step))
    }
}
