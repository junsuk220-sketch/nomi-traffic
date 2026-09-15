package nomi.android.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NaverBusAccessibilityParserTest {

    @Test
    fun `live sheet 81 at 5 minutes speaks once`() {
        val events = NaverBusAccessibilityParser.parse(
            packageName = NaverMapNotification.PACKAGE,
            root = board(
                "안내 중",
                "도보 162m · 2분",
                "라페스타.먹자골목 승차",
                "58117",
                "81",
                "5분",
                "5정류장",
                "여유",
                "99",
                "6분",
                "6정류장",
                "여유",
            ),
        )
        assertEquals(1, events.size)
        val event = events[0]
        assertEquals("81", event.busInfo!!.arrivals[0].line)
        assertEquals("5분", event.busInfo!!.arrivals[0].eta)
        assertEquals("여유", event.busInfo!!.arrivals[0].occupancy)
        assertEquals(5, event.busInfo!!.arrivals[0].stopsRemaining)
        assertEquals(
            "81번, 81번 버스가 5분 후 도착해요. 버스 좌석은 여유입니다. 다음은 99번, 6분 후 도착입니다.",
            NavigationEventSpeech.line(event),
        )
        val core = nomi.android.traffic.buswait.BusWaitCore()
        core.seed("81")
        assertEquals(5, core.observe(event.busInfo!!.arrivals)!!.speakStage)
        assertNull(core.observe(event.busInfo!!.arrivals)!!.speakStage)
    }

    @Test
    fun `walk minutes without 정류장 are not a bus arrival`() {
        val events = NaverBusAccessibilityParser.parse(
            packageName = NaverMapNotification.PACKAGE,
            root = board("안내 중", "162m", "2분", "라페스타.먹자골목 승차"),
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `preview without live guidance is silent`() {
        val events = NaverBusAccessibilityParser.parse(
            packageName = NaverMapNotification.PACKAGE,
            root = board("81", "5분", "5정류장", "여유"),
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `soon row and 1 minute both map to soon stage in core`() {
        val soon = NaverBusAccessibilityParser.parse(
            packageName = NaverMapNotification.PACKAGE,
            root = board("안내 중", "81", "곧", "1정류장", "여유"),
        ).single()
        assertEquals("81번, 81번 버스, 곧 도착합니다. 버스 좌석은 여유입니다.", NavigationEventSpeech.line(soon))
        val one = NaverBusAccessibilityParser.parse(
            packageName = NaverMapNotification.PACKAGE,
            root = board("안내 중", "81", "1분", "1정류장", "여유"),
        ).single()
        assertEquals("81번, 81번 버스, 곧 도착합니다. 버스 좌석은 여유입니다.", NavigationEventSpeech.line(one))
        assertFalse(NavigationEventSpeechGate().accept(one))
        val core = nomi.android.traffic.buswait.BusWaitCore()
        core.seed("81")
        assertEquals(1, core.observe(soon.busInfo!!.arrivals)!!.speakStage)
    }

    @Test
    fun `sheet 곧 도착 is the soon cue not the later 81`() {
        val event = NaverBusAccessibilityParser.parse(
            packageName = NaverMapNotification.PACKAGE,
            root = board(
                "안내 중",
                "라페스타.먹자골목 승차",
                "58202",
                "81",
                "곧 도착",
                "1정류장",
                "여유",
                "99",
                "곧 도착",
                "1정류장",
                "여유",
                "81",
                "18분",
                "10정류장",
                "여유",
                "99",
                "25분",
                "17정류장",
                "여유",
            ),
        ).single()
        assertEquals("81", event.busInfo!!.arrivals[0].line)
        assertEquals("곧 도착", event.busInfo!!.arrivals[0].eta)
        assertEquals("여유", event.busInfo!!.arrivals[0].occupancy)
        assertEquals(
            "81번, 81번 버스, 곧 도착합니다. 버스 좌석은 여유입니다. 다음은 99번, 곧 도착합니다.",
            NavigationEventSpeech.line(event),
        )
        val core = nomi.android.traffic.buswait.BusWaitCore()
        core.seed("81")
        assertEquals(1, core.observe(event.busInfo!!.arrivals)!!.speakStage)
        assertNull(core.observe(event.busInfo!!.arrivals)!!.speakStage)
    }

    @Test
    fun `wrong package is ignored`() {
        val events = NaverBusAccessibilityParser.parse(
            packageName = GoogleMapsTransit.PACKAGE,
            root = board("안내 중", "81", "5분", "5정류장"),
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `10 minute row speaks occupancy after the time`() {
        val event = NaverBusAccessibilityParser.parse(
            packageName = NaverMapNotification.PACKAGE,
            root = board(
                "안내 중",
                "라페스타.먹자골목 승차",
                "58117",
                "81",
                "10분",
                "9정류장",
                "여유",
                "99",
                "11분",
                "12정류장",
                "여유",
                "81",
                "13분",
                "10정류장",
                "여유",
            ),
        ).single()
        assertEquals("81", event.busInfo!!.arrivals[0].line)
        assertEquals("10분", event.busInfo!!.arrivals[0].eta)
        assertEquals("여유", event.busInfo!!.arrivals[0].occupancy)
        assertEquals("여유", event.busInfo!!.arrivals[1].occupancy)
        assertEquals(
            "81번, 81번 버스가 10분 후 도착해요. 버스 좌석은 여유입니다. 다음은 99번, 11분 후 도착입니다.",
            NavigationEventSpeech.line(event),
        )
    }

    @Test
    fun `crowded row speaks 혼잡 and missing occupancy stays on time only`() {
        val crowded = NaverBusAccessibilityParser.parse(
            packageName = NaverMapNotification.PACKAGE,
            root = board("안내 중", "81", "2분", "2정류장", "혼잡"),
        ).single()
        assertEquals("혼잡", crowded.busInfo!!.arrivals[0].occupancy)
        assertEquals(
            "81번, 81번 버스가 2분 후 도착해요. 버스 좌석은 혼잡입니다.",
            NavigationEventSpeech.line(crowded),
        )
        val bare = NaverBusAccessibilityParser.parse(
            packageName = NaverMapNotification.PACKAGE,
            root = board("안내 중", "81", "5분", "5정류장"),
        ).single()
        assertNull(bare.busInfo!!.arrivals[0].occupancy)
        assertEquals("81번, 81번 버스가 5분 후 도착해요.", NavigationEventSpeech.line(bare))
    }

    @Test
    fun `letter-suffix line 88B is a live bus row`() {
        val event = NaverBusAccessibilityParser.parse(
            packageName = NaverMapNotification.PACKAGE,
            root = board("안내 중", "88B", "2분", "1정류장", "여유", "88B", "9분", "4정류장"),
        ).single()
        assertEquals("88B", event.busInfo!!.arrivals[0].line)
        assertEquals("2분", event.busInfo!!.arrivals[0].eta)
        assertEquals("88B", event.busInfo!!.arrivals[1].line)
        assertEquals("9분", event.busInfo!!.arrivals[1].eta)
    }

    private fun board(vararg texts: String) = NaverSubwayAccessibilityParser.Node(
        children = texts.map { NaverSubwayAccessibilityParser.Node(text = it) },
    )
}
