package nomi.android.traffic.api.google.alight

import nomi.android.traffic.api.google.prepare.GooglePrepare
import nomi.android.traffic.api.google.read.GoogleStepReader
import nomi.android.traffic.api.google.transfer.GoogleTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** 2026-09-26 20:36 "다음 경유지". One phrase. Transfer stays in its own cell. */
class GoogleAlightTest {

    private val arrive = "오후 9:53 도착"

    private fun alight(text: String) =
        GoogleStepReader.read("저동초교삼거리에서 하차", text, arrive)!!

    private fun at(hour: Int, minute: Int) = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, hour)
        set(java.util.Calendar.MINUTE, minute)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Before
    fun setUp() {
        GoogleAlight.resetForTest()
        GooglePrepare.resetForTest()
        GoogleTransfer.resetForTest()
    }

    @Test
    fun `next place speaks once`() {
        assertNull(GoogleAlight.onStep(alight("정류장 2개 · 1분 · 999(으)로 환승 · 오후 8:56")))
        assertEquals(
            "이번 정류장 저동초교삼거리에서 하차하세요.",
            GoogleAlight.onStep(alight("다음 경유지 · 1분 · 999(으)로 환승 · 오후 8:56")),
        )
        assertNull(GoogleAlight.onStep(alight("다음 경유지 · 1분 · 999(으)로 환승 · 오후 8:56")))
    }

    @Test
    fun `transfer line does not change this phrase`() {
        val step = alight("다음 경유지 · 1분 · 999(으)로 환승 · 오후 8:56")
        assertEquals("이번 정류장 저동초교삼거리에서 하차하세요.", GoogleAlight.onStep(step))
        assertEquals(
            "다음 버스는 999번입니다. 12분 후 도착합니다.",
            GoogleTransfer.onStep(step, at(20, 44)),
        )
    }

    @Test
    fun `prepare cell still owns two stops`() {
        val two = alight("정류장 2개 · 2분 · 999(으)로 환승 · 오후 8:56")
        assertNull(GoogleAlight.onStep(two))
        assertEquals("다음 정류장 저동초교삼거리에서 하차 준비하세요.", GooglePrepare.onStep(two))
    }

    @Test
    fun `trip end lets the same stop speak again`() {
        val here = alight("다음 경유지 · 1분 · 999(으)로 환승 · 오후 8:56")
        GoogleAlight.onStep(here)
        GoogleAlight.reset()
        assertEquals("이번 정류장 저동초교삼거리에서 하차하세요.", GoogleAlight.onStep(here))
    }
}
