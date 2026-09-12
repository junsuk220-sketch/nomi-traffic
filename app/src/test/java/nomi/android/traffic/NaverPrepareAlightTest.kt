package nomi.android.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NaverPrepareAlightTest {

    @Before
    fun reset() {
        NaverPrepareAlight.reset()
    }

    @Test
    fun `itinerary 하차 row is the station not the destination`() {
        assertEquals("삼송역", NaverPrepareAlight.alightStop("삼송역 하차"))
        assertEquals("삼송역", NaverPrepareAlight.alightStop("삼송역하차"))
        assertNull(NaverPrepareAlight.alightStop("빠른 하차: 9-1"))
        assertNull(NaverPrepareAlight.alightStop("삼송역3번출구약국까지 이동"))
        assertEquals("라페스타.먹자골목", NaverPrepareAlight.alightStop("라페스타.먹자골목 하차"))
    }

    @Test
    fun `split bus 하차 row still names the stop`() {
        NaverPrepareAlight.noteStops(listOf("라페스타.먹자골목", "하차"))
        NaverPrepareAlight.noteCue("전역입니다")
        val event = NaverPrepareAlight.readyEvent()!!
        assertEquals("라페스타.먹자골목", event.landmark)
        assertEquals(NaverMapsTransit.KIND_BUS, event.rawText)
        assertEquals(
            "다음 정류장은 라페스타.먹자골목입니다. 내릴 준비하세요.",
            NavigationEventSpeech.line(event),
        )
    }

    @Test
    fun `전역 cue plus stop speaks once`() {
        assertFalse(NaverPrepareAlight.isPrepareCue("정발산역 3호선까지 걷기"))
        assertTrue(NaverPrepareAlight.isPrepareCue("전역입니다"))
        NaverPrepareAlight.noteStop("삼송역 하차")
        assertNull(NaverPrepareAlight.readyEvent())
        NaverPrepareAlight.noteCue("전역입니다")
        val event = NaverPrepareAlight.readyEvent()!!
        assertEquals(NaverMapsTransit.PREPARE_ALIGHT_ACTION, event.action)
        assertEquals("삼송역", event.landmark)
        assertEquals(
            "다음 역은 삼송역입니다. 내릴 준비하세요.",
            NavigationEventSpeech.line(event),
        )
        assertNull(NaverPrepareAlight.readyEvent())
    }

    @Test
    fun `bus 전역 can speak after subway 전역 on the same trip`() {
        NaverPrepareAlight.noteStop("삼송역 하차")
        NaverPrepareAlight.noteCue("전역입니다")
        assertEquals("삼송역", NaverPrepareAlight.readyEvent()!!.landmark)
        NaverPrepareAlight.noteStop("라페스타.먹자골목 하차")
        NaverPrepareAlight.noteCue("전역입니다")
        val bus = NaverPrepareAlight.readyEvent()!!
        assertEquals("라페스타.먹자골목", bus.landmark)
        assertEquals(
            "다음 정류장은 라페스타.먹자골목입니다. 내릴 준비하세요.",
            NavigationEventSpeech.line(bus),
        )
    }
}
