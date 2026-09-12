package nomi.android.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class NaverTripStartParserTest {

    @Test
    fun `start phrase matches naver wording`() {
        assertTrue(NaverTripStartParser.isStartPhrase("길안내를 시작합니다"))
        assertTrue(NaverTripStartParser.isStartPhrase("길 안내를 시작합니다."))
    }

    @Test
    fun `end button matches only guidance end`() {
        assertTrue(NaverTripStartParser.isEndButton("안내종료"))
        assertTrue(NaverTripStartParser.isEndButton("안내 종료"))
        assertFalse(NaverTripStartParser.isEndButton("안내시작"))
        assertFalse(NaverTripStartParser.isEndButton("종료"))
    }

    @Test
    fun `preview caches walk meters-dot-minutes and depart wait`() {
        val decision = NaverTripStartParser.decision(
            packageName = NaverMapNotification.PACKAGE,
            root = NaverSubwayAccessibilityParser.Node(
                children = listOf(
                    NaverSubwayAccessibilityParser.Node(text = "안내시작"),
                    NaverSubwayAccessibilityParser.Node(text = "도보 756m · 11분"),
                    NaverSubwayAccessibilityParser.Node(text = "출발 대기 17분"),
                    NaverSubwayAccessibilityParser.Node(text = "3호선"),
                ),
            ),
            requireLive = false,
        )
        val event = (decision as NaverTripStartParser.Decision.Speak).event
        assertEquals(NaverMapsTransit.KIND_SUBWAY, event.rawText)
        assertEquals(11, event.distanceMeters)
        assertEquals("3호선", event.busInfo!!.arrivals[0].line)
        assertEquals("17분", event.busInfo!!.arrivals[0].eta)
        assertEquals(
            "3호선이 17분 후 출발합니다. 역까지는 걸어서 약 11분입니다.",
            NavigationEventSpeech.line(event),
        )
    }

    @Test
    fun `live start splits walk meters and minutes plus board line and clock`() {
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        cal.add(java.util.Calendar.MINUTE, 14)
        val clock = "%02d:%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
        val decision = NaverTripStartParser.decision(
            packageName = NaverMapNotification.PACKAGE,
            root = NaverSubwayAccessibilityParser.Node(
                children = listOf(
                    NaverSubwayAccessibilityParser.Node(text = "안내 중"),
                    NaverSubwayAccessibilityParser.Node(text = "도보 751m"),
                    NaverSubwayAccessibilityParser.Node(text = "·"),
                    NaverSubwayAccessibilityParser.Node(text = "11분"),
                    NaverSubwayAccessibilityParser.Node(text = "3호선 정발산역 승차"),
                    NaverSubwayAccessibilityParser.Node(text = clock),
                    NaverSubwayAccessibilityParser.Node(text = "오금행"),
                ),
            ),
            timestampMillis = now,
            requireLive = true,
        )
        val event = (decision as NaverTripStartParser.Decision.Speak).event
        assertEquals(NaverMapsTransit.KIND_SUBWAY, event.rawText)
        assertEquals(11, event.distanceMeters)
        assertEquals("3호선", event.busInfo!!.arrivals[0].line)
        assertEquals("14분", event.busInfo!!.arrivals[0].eta)
        assertEquals(
            "3호선이 14분 후 출발합니다. 역까지는 걸어서 약 11분입니다.",
            NavigationEventSpeech.line(event),
        )
    }

    @Test
    fun `live subway start names this train and the next clock`() {
        NaverNearBoardNotice.reset()
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 19)
            set(Calendar.MINUTE, 33)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val decision = NaverTripStartParser.decision(
            packageName = NaverMapNotification.PACKAGE,
            root = NaverSubwayAccessibilityParser.Node(
                children = listOf(
                    NaverSubwayAccessibilityParser.Node(text = "안내 중"),
                    NaverSubwayAccessibilityParser.Node(text = "도보 757m · 11분"),
                    NaverSubwayAccessibilityParser.Node(text = "3호선 정발산역 승차"),
                    NaverSubwayAccessibilityParser.Node(text = "19:46"),
                    NaverSubwayAccessibilityParser.Node(text = "13분"),
                    NaverSubwayAccessibilityParser.Node(text = "대화행"),
                    NaverSubwayAccessibilityParser.Node(text = "19:53"),
                    NaverSubwayAccessibilityParser.Node(text = "20분"),
                    NaverSubwayAccessibilityParser.Node(text = "대화행"),
                ),
            ),
            timestampMillis = now,
            requireLive = true,
        )
        val event = (decision as NaverTripStartParser.Decision.Speak).event
        assertEquals("3호선", event.busInfo!!.arrivals[0].line)
        assertEquals("13분", event.busInfo!!.arrivals[0].eta)
        assertEquals("20분", event.busInfo!!.arrivals[1].eta)
        assertEquals(
            "3호선이 13분 후 출발합니다. 역까지는 걸어서 약 11분입니다. 다음 열차는 20분 후 도착입니다.",
            NavigationEventSpeech.line(event),
        )
        NaverNearBoardNotice.reset()
    }

    @Test
    fun `live subway until-walk title still briefs when clocks appear`() {
        NaverNearBoardNotice.reset()
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 19)
            set(Calendar.MINUTE, 33)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val decision = NaverTripStartParser.decision(
            packageName = NaverMapNotification.PACKAGE,
            root = NaverSubwayAccessibilityParser.Node(
                children = listOf(
                    NaverSubwayAccessibilityParser.Node(text = "안내 중"),
                    NaverSubwayAccessibilityParser.Node(text = "도보 757m · 11분"),
                    NaverSubwayAccessibilityParser.Node(text = "대화역 3호선까지 걷기"),
                    NaverSubwayAccessibilityParser.Node(text = "19:46"),
                    NaverSubwayAccessibilityParser.Node(text = "19:53"),
                ),
            ),
            timestampMillis = now,
            requireLive = true,
        )
        val event = (decision as NaverTripStartParser.Decision.Speak).event
        assertEquals("3호선", event.busInfo!!.arrivals[0].line)
        assertEquals("13분", event.busInfo!!.arrivals[0].eta)
        assertEquals("20분", event.busInfo!!.arrivals[1].eta)
        assertEquals(
            "3호선이 13분 후 출발합니다. 역까지는 걸어서 약 11분입니다. 다음 열차는 20분 후 도착입니다.",
            NavigationEventSpeech.line(event),
        )
        NaverNearBoardNotice.reset()
    }

    @Test
    fun `trip start speaks bus eta plus walk like google`() {
        val decision = NaverTripStartParser.decision(
            packageName = NaverMapNotification.PACKAGE,
            root = NaverSubwayAccessibilityParser.Node(
                children = listOf(
                    NaverSubwayAccessibilityParser.Node(text = "안내 중"),
                    NaverSubwayAccessibilityParser.Node(text = "도보 약 6분"),
                    NaverSubwayAccessibilityParser.Node(text = "81"),
                    NaverSubwayAccessibilityParser.Node(text = "7분"),
                    NaverSubwayAccessibilityParser.Node(text = "5정류장"),
                    NaverSubwayAccessibilityParser.Node(text = "여유"),
                ),
            ),
        )
        val event = (decision as NaverTripStartParser.Decision.Speak).event
        assertEquals(NaverMapsTransit.TRIP_START_ACTION, event.action)
        assertEquals(NaverMapsTransit.KIND_BUS, event.rawText)
        assertEquals("81", event.busInfo!!.arrivals[0].line)
        assertEquals("7분", event.busInfo!!.arrivals[0].eta)
        assertEquals(6, event.distanceMeters)
        assertEquals(
            "81번 버스가 7분 후 도착합니다. 정류장까지는 걸어서 약 6분입니다.",
            NavigationEventSpeech.line(event),
        )
        val gate = NavigationEventSpeechGate()
        assertTrue(gate.accept(event))
    }

    @Test
    fun `trip start next bus is same stop cluster not the next 98`() {
        val chips = listOf("89", "67", "66", "98", "2000", "150", "97", "83")
        val decision = NaverTripStartParser.decision(
            packageName = NaverMapNotification.PACKAGE,
            root = NaverSubwayAccessibilityParser.Node(
                children = listOf(
                    NaverSubwayAccessibilityParser.Node(text = "안내 중"),
                    NaverSubwayAccessibilityParser.Node(text = "도보 487m · 8분"),
                    NaverSubwayAccessibilityParser.Node(text = "일산동부경찰서(중) 승차"),
                ) + chips.map { NaverSubwayAccessibilityParser.Node(text = it) } + listOf(
                    NaverSubwayAccessibilityParser.Node(text = "98"),
                    NaverSubwayAccessibilityParser.Node(text = "곧 도착"),
                    NaverSubwayAccessibilityParser.Node(text = "1정류장"),
                    NaverSubwayAccessibilityParser.Node(text = "여유"),
                    NaverSubwayAccessibilityParser.Node(text = "2000"),
                    NaverSubwayAccessibilityParser.Node(text = "3분"),
                    NaverSubwayAccessibilityParser.Node(text = "1정류장"),
                    NaverSubwayAccessibilityParser.Node(text = "여유"),
                    NaverSubwayAccessibilityParser.Node(text = "89"),
                    NaverSubwayAccessibilityParser.Node(text = "2분"),
                    NaverSubwayAccessibilityParser.Node(text = "2정류장"),
                    NaverSubwayAccessibilityParser.Node(text = "여유"),
                    NaverSubwayAccessibilityParser.Node(text = "98"),
                    NaverSubwayAccessibilityParser.Node(text = "5분"),
                    NaverSubwayAccessibilityParser.Node(text = "3정류장"),
                    NaverSubwayAccessibilityParser.Node(text = "여유"),
                ),
            ),
        )
        val event = (decision as NaverTripStartParser.Decision.Speak).event
        assertEquals("98", event.busInfo!!.arrivals[0].line)
        assertEquals("2000", event.busInfo!!.arrivals[1].line)
        assertEquals(
            "98번 버스, 곧 도착합니다. 정류장까지는 걸어서 약 8분입니다. 다음 버스는 2000번, 3분 후 도착입니다.",
            NavigationEventSpeech.line(event),
        )
    }

    @Test
    fun `trip start names the next bus once`() {
        val decision = NaverTripStartParser.decision(
            packageName = NaverMapNotification.PACKAGE,
            root = NaverSubwayAccessibilityParser.Node(
                children = listOf(
                    NaverSubwayAccessibilityParser.Node(text = "안내 중"),
                    NaverSubwayAccessibilityParser.Node(text = "도보 약 6분"),
                    NaverSubwayAccessibilityParser.Node(text = "150"),
                    NaverSubwayAccessibilityParser.Node(text = "3분"),
                    NaverSubwayAccessibilityParser.Node(text = "2정류장"),
                    NaverSubwayAccessibilityParser.Node(text = "여유"),
                    NaverSubwayAccessibilityParser.Node(text = "67"),
                    NaverSubwayAccessibilityParser.Node(text = "5분"),
                    NaverSubwayAccessibilityParser.Node(text = "4정류장"),
                    NaverSubwayAccessibilityParser.Node(text = "여유"),
                ),
            ),
        )
        val event = (decision as NaverTripStartParser.Decision.Speak).event
        assertEquals("150", event.busInfo!!.arrivals[0].line)
        assertEquals("67", event.busInfo!!.arrivals[1].line)
        assertEquals(
            "150번 버스가 3분 후 도착합니다. 정류장까지는 걸어서 약 6분입니다. 다음 버스는 67번, 5분 후 도착입니다.",
            NavigationEventSpeech.line(event),
        )
    }

    @Test
    fun `mixed bus then subway itinerary seeds bus as first boarding`() {
        val decision = NaverTripStartParser.decision(
            packageName = NaverMapNotification.PACKAGE,
            root = NaverSubwayAccessibilityParser.Node(
                children = listOf(
                    NaverSubwayAccessibilityParser.Node(text = "안내 중"),
                    NaverSubwayAccessibilityParser.Node(text = "도보 약 2분"),
                    NaverSubwayAccessibilityParser.Node(text = "81"),
                    NaverSubwayAccessibilityParser.Node(text = "4분"),
                    NaverSubwayAccessibilityParser.Node(text = "3정류장"),
                    NaverSubwayAccessibilityParser.Node(text = "여유"),
                    NaverSubwayAccessibilityParser.Node(text = "3호선 정발산역 승차"),
                ),
            ),
        )
        val event = (decision as NaverTripStartParser.Decision.Speak).event
        assertEquals(NaverMapsTransit.KIND_BUS, event.rawText)
        assertEquals("81", event.busInfo!!.arrivals[0].line)
        assertEquals("4분", event.busInfo!!.arrivals[0].eta)
    }

    @Test
    fun `mixed subway then bus itinerary seeds subway as first boarding`() {
        val decision = NaverTripStartParser.decision(
            packageName = NaverMapNotification.PACKAGE,
            root = NaverSubwayAccessibilityParser.Node(
                children = listOf(
                    NaverSubwayAccessibilityParser.Node(text = "안내 중"),
                    NaverSubwayAccessibilityParser.Node(text = "도보 약 3분"),
                    NaverSubwayAccessibilityParser.Node(text = "3호선 정발산역 승차"),
                    NaverSubwayAccessibilityParser.Node(text = "17:10"),
                    NaverSubwayAccessibilityParser.Node(text = "81"),
                    NaverSubwayAccessibilityParser.Node(text = "4분"),
                ),
            ),
            timestampMillis = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 17)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis,
        )
        val event = (decision as NaverTripStartParser.Decision.Speak).event
        assertEquals(NaverMapsTransit.KIND_SUBWAY, event.rawText)
        assertEquals("3호선", event.busInfo!!.arrivals[0].line)
    }

    @Test
    fun `live bus rows without walk still speak first vehicle`() {
        val decision = NaverTripStartParser.decision(
            packageName = NaverMapNotification.PACKAGE,
            root = NaverSubwayAccessibilityParser.Node(
                children = listOf(
                    NaverSubwayAccessibilityParser.Node(text = "안내 중"),
                    NaverSubwayAccessibilityParser.Node(text = "97"),
                    NaverSubwayAccessibilityParser.Node(text = "곧 도착"),
                    NaverSubwayAccessibilityParser.Node(text = "1정류장"),
                    NaverSubwayAccessibilityParser.Node(text = "여유"),
                    NaverSubwayAccessibilityParser.Node(text = "66"),
                    NaverSubwayAccessibilityParser.Node(text = "7분"),
                ),
            ),
        )
        val event = (decision as NaverTripStartParser.Decision.Speak).event
        assertEquals(NaverMapsTransit.KIND_BUS, event.rawText)
        assertEquals("97", event.busInfo!!.arrivals[0].line)
        assertEquals("곧 도착", event.busInfo!!.arrivals[0].eta)
        assertEquals(
            "97번 버스, 곧 도착합니다.",
            NavigationEventSpeech.line(event),
        )
    }

    @Test
    fun `destination subway label does not hide first bus briefing`() {
        val decision = NaverTripStartParser.decision(
            packageName = NaverMapNotification.PACKAGE,
            root = NaverSubwayAccessibilityParser.Node(
                children = listOf(
                    NaverSubwayAccessibilityParser.Node(text = "안내 중"),
                    NaverSubwayAccessibilityParser.Node(text = "대화역 3호선까지 이동"),
                    NaverSubwayAccessibilityParser.Node(text = "88B"),
                    NaverSubwayAccessibilityParser.Node(text = "2분"),
                    NaverSubwayAccessibilityParser.Node(text = "1정류장"),
                    NaverSubwayAccessibilityParser.Node(text = "88B"),
                    NaverSubwayAccessibilityParser.Node(text = "9분"),
                    NaverSubwayAccessibilityParser.Node(text = "4정류장"),
                ),
            ),
            requireLive = true,
        )
        val event = (decision as NaverTripStartParser.Decision.Speak).event
        assertEquals(NaverMapsTransit.KIND_BUS, event.rawText)
        assertEquals("88B", event.busInfo!!.arrivals[0].line)
        assertEquals("2분", event.busInfo!!.arrivals[0].eta)
        assertEquals("88B", event.busInfo!!.arrivals[1].line)
        assertEquals("9분", event.busInfo!!.arrivals[1].eta)
        assertEquals(
            "88B번 버스가 2분 후 도착합니다. 다음 버스는 88B번, 9분 후 도착입니다.",
            NavigationEventSpeech.line(event),
        )
    }

    @Test
    fun `trip start same line later bus still briefs the next one`() {
        val decision = NaverTripStartParser.decision(
            packageName = NaverMapNotification.PACKAGE,
            root = NaverSubwayAccessibilityParser.Node(
                children = listOf(
                    NaverSubwayAccessibilityParser.Node(text = "안내 중"),
                    NaverSubwayAccessibilityParser.Node(text = "도보 310m · 5분"),
                    NaverSubwayAccessibilityParser.Node(text = "대유·삼성오피스텔 승차"),
                    NaverSubwayAccessibilityParser.Node(text = "88B"),
                    NaverSubwayAccessibilityParser.Node(text = "5분"),
                    NaverSubwayAccessibilityParser.Node(text = "4정류장"),
                    NaverSubwayAccessibilityParser.Node(text = "여유"),
                    NaverSubwayAccessibilityParser.Node(text = "88B"),
                    NaverSubwayAccessibilityParser.Node(text = "11분"),
                    NaverSubwayAccessibilityParser.Node(text = "6정류장"),
                    NaverSubwayAccessibilityParser.Node(text = "여유"),
                ),
            ),
            requireLive = true,
        )
        val event = (decision as NaverTripStartParser.Decision.Speak).event
        assertEquals("88B", event.busInfo!!.arrivals[0].line)
        assertEquals("5분", event.busInfo!!.arrivals[0].eta)
        assertEquals("88B", event.busInfo!!.arrivals[1].line)
        assertEquals("11분", event.busInfo!!.arrivals[1].eta)
        assertEquals(
            "88B번 버스가 5분 후 도착합니다. 정류장까지는 걸어서 약 5분입니다. 다음 버스는 88B번, 11분 후 도착입니다.",
            NavigationEventSpeech.line(event),
        )
    }
}
