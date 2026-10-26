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

    private fun nodes(vararg texts: String) = NaverSubwayAccessibilityParser.Node(
        children = texts.map { NaverSubwayAccessibilityParser.Node(text = it) },
    )

    private fun at1754(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 17)
        set(Calendar.MINUTE, 54)
        set(Calendar.SECOND, 8)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** 2026-10-01 17:56:32: options list while a route is running. */
    @Test
    fun `live route options list is not a briefing`() {
        val decision = NaverTripStartParser.decision(
            packageName = NaverMapNotification.PACKAGE,
            root = nodes(
                "안내중인 경로 바로가기", "어구어구 건대본점", "오늘 17:53 출발", "안내 중",
                "1시간 19분", "도보 2분, 3호선 47분, 도보 3분, 2호선 16분, 도보 9분",
                "2분", "47분", "16분", "9분", "3호선", "정발산역", "오금행", "마두역 방면",
                "2호선", "을지로3가역", "하차", "건대입구역", "안내 종료",
                "1시간 38분", "도보 5분, M7412번 광역 버스 62분, 도보 7분, 7호선 10분, 도보 9분",
                "바로 안내시작",
            ),
            timestampMillis = at1754(),
            requireLive = true,
        )
        assertEquals(NaverTripStartParser.Decision.Pending, decision)
    }

    @Test
    fun `route options list is not a preview`() {
        val decision = NaverTripStartParser.decision(
            packageName = NaverMapNotification.PACKAGE,
            root = nodes(
                "도보 2분, 3호선 47분, 도보 3분, 2호선 16분, 도보 9분",
                "2분", "47분", "3호선", "정발산역", "오금행", "바로 안내시작",
            ),
            timestampMillis = at1754(),
            requireLive = false,
        )
        assertEquals(NaverTripStartParser.Decision.Pending, decision)
    }

    /** 2026-10-01 17:54:08: "오후 7:28 도착" is the route's arrival, not a train. */
    @Test
    fun `route arrival clock is not the next train`() {
        val decision = NaverTripStartParser.decision(
            packageName = NaverMapNotification.PACKAGE,
            root = nodes(
                "1시간 34분", "도보 1분, 81번 일반 버스 1분, 1500번 직행좌석 버스 38분, 도보 6분, 2호선 36분, 도보 9분",
                "2호선", "오후 5:53 기준 (출발 대기 1분)", "도보 83m", "1분",
                "1시간 34분", "오후 7:28 도착", "미리보기", "안내시작",
            ),
            timestampMillis = at1754(),
            requireLive = false,
        )
        val line = (decision as? NaverTripStartParser.Decision.Speak)
            ?.let { NavigationEventSpeech.line(it.event) }.orEmpty()
        assertFalse(line, line.contains("94분"))
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
                    NaverSubwayAccessibilityParser.Node(text = "안내 종료"),
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
                    NaverSubwayAccessibilityParser.Node(text = "안내 종료"),
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
                    NaverSubwayAccessibilityParser.Node(text = "안내 종료"),
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
                    NaverSubwayAccessibilityParser.Node(text = "안내 종료"),
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
            "81번, 81번 버스가 7분 후 도착합니다. 정류장까지는 걸어서 약 6분입니다.",
            NavigationEventSpeech.line(event),
        )
        val gate = NavigationEventSpeechGate()
        assertTrue(gate.accept(event))
    }

    @Test
    fun `trip start next bus is remaining soonest after first not 2000`() {
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
                    NaverSubwayAccessibilityParser.Node(text = "안내 종료"),
                ),
            ),
        )
        val event = (decision as NaverTripStartParser.Decision.Speak).event
        assertEquals("98", event.busInfo!!.arrivals[0].line)
        assertEquals("89", event.busInfo!!.arrivals[1].line)
        assertEquals("2분", event.busInfo!!.arrivals[1].eta)
        assertEquals(
            "98번, 98번 버스, 곧 도착합니다. 정류장까지는 걸어서 약 8분입니다. 다음은 89번, 2분 후 도착입니다.",
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
                    NaverSubwayAccessibilityParser.Node(text = "안내 종료"),
                ),
            ),
        )
        val event = (decision as NaverTripStartParser.Decision.Speak).event
        assertEquals("150", event.busInfo!!.arrivals[0].line)
        assertEquals("67", event.busInfo!!.arrivals[1].line)
        assertEquals(
            "150번, 150번 버스가 3분 후 도착합니다. 정류장까지는 걸어서 약 6분입니다. 다음은 67번, 5분 후 도착입니다.",
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
                    NaverSubwayAccessibilityParser.Node(text = "안내 종료"),
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
                    NaverSubwayAccessibilityParser.Node(text = "안내 종료"),
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
                    NaverSubwayAccessibilityParser.Node(text = "안내 종료"),
                ),
            ),
        )
        val event = (decision as NaverTripStartParser.Decision.Speak).event
        assertEquals(NaverMapsTransit.KIND_BUS, event.rawText)
        assertEquals("97", event.busInfo!!.arrivals[0].line)
        assertEquals("곧 도착", event.busInfo!!.arrivals[0].eta)
        assertEquals(
            "97번, 97번 버스, 곧 도착합니다.",
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
                    NaverSubwayAccessibilityParser.Node(text = "안내 종료"),
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
            "88B번, 88B번 버스가 2분 후 도착합니다. 다음은 88B번, 9분 후 도착입니다.",
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
                    NaverSubwayAccessibilityParser.Node(text = "안내 종료"),
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
            "88B번, 88B번 버스가 5분 후 도착합니다. 정류장까지는 걸어서 약 5분입니다. 다음은 88B번, 11분 후 도착입니다.",
            NavigationEventSpeech.line(event),
        )
    }

    @Test
    fun `bus without eta does not promote the next subway journey`() {
        val decision = noEtaBusThenSubway()
        val event = (decision as NaverTripStartParser.Decision.Speak).event
        assertEquals(NaverMapsTransit.KIND_BUS, event.rawText)
        assertEquals("1000", event.busInfo!!.arrivals[0].line)
        assertEquals("", event.busInfo!!.arrivals[0].eta)
        assertEquals(1, event.busInfo!!.arrivals.size)
        assertEquals(
            "1000번 버스 도착 예정 정보가 없습니다.",
            NavigationEventSpeech.line(event),
        )
        assertTrue(NavigationEventSpeechGate().accept(event))
    }

    @Test
    fun `bus without eta does not promote a later bus on the same sheet`() {
        val decision = NaverTripStartParser.decision(
            packageName = NaverMapNotification.PACKAGE,
            root = node(
                "안내 중",
                "도보 약 3분",
                "1000",
                "도착 예정 정보 없음",
                "81",
                "5분",
                "3정류장",
                "여유",
            ),
        )
        val event = (decision as NaverTripStartParser.Decision.Speak).event
        assertEquals(NaverMapsTransit.KIND_BUS, event.rawText)
        assertEquals("1000", event.busInfo!!.arrivals[0].line)
        assertEquals("", event.busInfo!!.arrivals[0].eta)
        assertEquals(
            "1000번 버스 도착 예정 정보가 없습니다.",
            NavigationEventSpeech.line(event),
        )
    }

    @Test
    fun `later bus eta still uses the wait ladder after a no-eta briefing`() {
        val briefing = (noEtaBusThenSubway() as NaverTripStartParser.Decision.Speak).event
        assertTrue(NavigationEventSpeechGate().accept(briefing))
        val core = nomi.android.traffic.buswait.BusWaitCore()
        core.seed("1000")
        val tick = core.observe(
            listOf(nomi.product.nav.NavigationBusArrival("1000", "9분")),
            nowMs = 1_000L,
        )!!
        assertEquals(10, tick.speakStage)
    }

    @Test
    fun `subway wait brief still works after a no-eta bus trip start`() {
        val gate = NavigationEventSpeechGate()
        val briefing = (noEtaBusThenSubway() as NaverTripStartParser.Decision.Speak).event
        assertTrue(gate.accept(briefing))
        val subway = nomi.product.nav.NavigationEvent(
            source = nomi.product.nav.NavigationEventSource.NAVER,
            type = nomi.product.nav.NavigationEventType.TRANSIT,
            notificationId = 301,
            channel = "302_PUBTRANS_POPUP",
            title = "정발산역 3호선 도보 후 열차 승차",
            action = "정발산역 3호선 도보 후 열차 승차",
            distanceMeters = null,
            rawText = NaverMapsTransit.KIND_SUBWAY,
            busInfo = nomi.product.nav.NavigationBusInfo(
                raw = "3호선 6분 | 오금행 (15:20)",
                arrivals = listOf(nomi.product.nav.NavigationBusArrival("3호선", "6분")),
            ),
            timestampMillis = 0L,
        )
        assertTrue(gate.acceptNaverSubwayWalkBrief(subway))
    }

    @Test
    fun `subway without eta does not promote the next bus journey`() {
        val event = (noEtaSubwayThenBus() as NaverTripStartParser.Decision.Speak).event
        assertEquals(NaverMapsTransit.KIND_SUBWAY, event.rawText)
        assertEquals("3호선", event.busInfo!!.arrivals[0].line)
        assertEquals("", event.busInfo!!.arrivals[0].eta)
        assertEquals(1, event.busInfo!!.arrivals.size)
        assertEquals(
            "3호선 오금행 열차 도착 예정 정보가 없습니다.",
            NavigationEventSpeech.line(event),
        )
        assertTrue(NavigationEventSpeechGate().accept(event))
    }

    @Test
    fun `later subway eta still uses the wait ladder after a no-eta briefing`() {
        val gate = NavigationEventSpeechGate()
        val briefing = (noEtaSubwayThenBus() as NaverTripStartParser.Decision.Speak).event
        assertTrue(gate.accept(briefing))
        val wait = nomi.product.nav.NavigationEvent(
            source = nomi.product.nav.NavigationEventSource.NAVER,
            type = nomi.product.nav.NavigationEventType.TRANSIT,
            notificationId = 301,
            channel = "302_PUBTRANS_POPUP",
            title = "정발산역 3호선 도보 후 열차 승차",
            action = "정발산역 3호선 도보 후 열차 승차",
            distanceMeters = null,
            rawText = NaverMapsTransit.KIND_SUBWAY,
            busInfo = nomi.product.nav.NavigationBusInfo(
                raw = "3호선 9분 | 오금행 (15:20)",
                arrivals = listOf(nomi.product.nav.NavigationBusArrival("3호선", "9분")),
            ),
            timestampMillis = 0L,
        )
        assertTrue(gate.accept(wait))
        assertTrue(gate.accept(wait.copy(
            busInfo = wait.busInfo!!.copy(
                arrivals = listOf(nomi.product.nav.NavigationBusArrival("3호선", "5분")),
            ),
        )))
        assertTrue(gate.accept(wait.copy(
            busInfo = wait.busInfo!!.copy(
                arrivals = listOf(nomi.product.nav.NavigationBusArrival("3호선", "2분")),
            ),
        )))
        assertTrue(gate.accept(wait.copy(
            busInfo = wait.busInfo!!.copy(
                arrivals = listOf(nomi.product.nav.NavigationBusArrival("3호선", "곧")),
            ),
        )))
    }

    @Test
    fun `bus wait still works after a no-eta subway trip start`() {
        val briefing = (noEtaSubwayThenBus() as NaverTripStartParser.Decision.Speak).event
        assertTrue(NavigationEventSpeechGate().accept(briefing))
        val core = nomi.android.traffic.buswait.BusWaitCore()
        core.seed("81")
        val tick = core.observe(
            listOf(nomi.product.nav.NavigationBusArrival("81", "4분")),
            nowMs = 1_000L,
        )!!
        assertEquals(5, tick.speakStage)
    }

    @Test
    fun `alternative bus before 안내 중 is not the trip-start pin`() {
        val decision = NaverTripStartParser.decision(
            packageName = NaverMapNotification.PACKAGE,
            root = node(
                "최소시간",
                "53분",
                "도보 2분, 99번 일반 버스 2분",
                "99",
                "2분",
                "7정류장",
                "여유",
                "바로 안내시작",
                "안내 중",
                "55분",
                "도보 약 11분",
                "3호선",
                "오금행",
                "안내 종료",
            ),
            timestampMillis = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 15)
                set(Calendar.MINUTE, 39)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis,
            requireLive = true,
        )
        // A route options list waits for the live sheet; it never pins the alternative 99.
        val event = (decision as? NaverTripStartParser.Decision.Speak)?.event
        assertTrue(event == null || event.busInfo!!.arrivals.none { it.line == "99" })
    }

    @Test
    fun `live snapshot without 안내 종료 still briefs from 안내 중 onward`() {
        val decision = NaverTripStartParser.decision(
            packageName = NaverMapNotification.PACKAGE,
            root = NaverSubwayAccessibilityParser.Node(
                children = listOf(
                    "안내 중",
                    "도보 약 11분",
                    "3호선",
                    "오금행",
                ).map { NaverSubwayAccessibilityParser.Node(text = it) },
            ),
            requireLive = true,
        )
        val event = (decision as NaverTripStartParser.Decision.Speak).event
        assertEquals(NaverMapsTransit.TRIP_START_ACTION, event.action)
        assertEquals(NaverMapsTransit.KIND_SUBWAY, event.rawText)
        assertEquals("3호선", event.busInfo!!.arrivals[0].line)
    }

    @Test
    fun `no-eta board above 안내 중 still briefs that bus`() {
        val decision = NaverTripStartParser.decision(
            packageName = NaverMapNotification.PACKAGE,
            root = texts(
                "경기 고양시 일산동구 장항동 1474",
                "도보 564m",
                "8분",
                "롯데백화점 승차",
                "20214",
                "50",
                "도착 예정 정보 없음",
                "과거 시간표",
                "버스 도착정보 더보기",
                "7개 정류장 이동",
                "16분",
                "세원고교입구.동국대병원사거리 하차",
                "20507",
                "도보 124m",
                "1분",
                "95번 환승1회 1시간 6분",
                "안내 중",
                "3800번 1시간 8분",
                "1시간 11분",
                "오후 5:51 도착",
                "길안내를 시작합니다.",
            ),
            requireLive = true,
        )
        val event = (decision as NaverTripStartParser.Decision.Speak).event
        assertEquals(NaverMapsTransit.KIND_BUS, event.rawText)
        assertEquals("50", event.busInfo!!.arrivals.single().line)
        assertEquals("", event.busInfo!!.arrivals.single().eta)
        assertEquals(
            "50번 버스 도착 예정 정보가 없습니다.",
            NavigationEventSpeech.line(event),
        )
    }

    @Test
    fun `board above 안내 중 does not replace a vehicle below it`() {
        val decision = NaverTripStartParser.decision(
            packageName = NaverMapNotification.PACKAGE,
            root = texts(
                "도보 564m",
                "8분",
                "롯데백화점 승차",
                "50",
                "도착 예정 정보 없음",
                "안내 중",
                "도보 약 11분",
                "3호선",
                "오금행",
            ),
            requireLive = true,
        )
        val event = (decision as NaverTripStartParser.Decision.Speak).event
        assertEquals(NaverMapsTransit.KIND_SUBWAY, event.rawText)
        assertEquals("3호선", event.busInfo!!.arrivals[0].line)
    }

    @Test
    fun `bare bus above 안내 중 without a boarding stop stays pending`() {
        val decision = NaverTripStartParser.decision(
            packageName = NaverMapNotification.PACKAGE,
            root = texts(
                "99",
                "2분",
                "7정류장",
                "여유",
                "안내 중",
                "3800번 1시간 8분",
            ),
            requireLive = true,
        )
        assertEquals(NaverTripStartParser.Decision.Pending, decision)
    }

    @Test
    fun `live without 안내 중 stays pending`() {
        val decision = NaverTripStartParser.decision(
            packageName = NaverMapNotification.PACKAGE,
            root = NaverSubwayAccessibilityParser.Node(
                children = listOf(
                    "도보 약 11분",
                    "3호선",
                    "오금행",
                ).map { NaverSubwayAccessibilityParser.Node(text = it) },
            ),
            requireLive = true,
        )
        assertEquals(NaverTripStartParser.Decision.Pending, decision)
    }

    private fun noEtaSubwayThenBus() = NaverTripStartParser.decision(
        packageName = NaverMapNotification.PACKAGE,
        root = node(
            "안내 중",
            "도보 약 3분",
            "3호선 정발산역 승차",
            "오금행",
            "도착 예정 정보 없음",
            "81",
            "5분",
            "3정류장",
            "여유",
        ),
    )

    private fun noEtaBusThenSubway() = NaverTripStartParser.decision(
        packageName = NaverMapNotification.PACKAGE,
        root = node(
            "안내 중",
            "도보 약 4분",
            "1000",
            "도착 예정 정보 없음",
            "3호선 정발산역 승차",
            "15:20",
            "오금행",
        ),
        timestampMillis = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 15)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis,
    )

    private fun texts(vararg blobs: String): NaverSubwayAccessibilityParser.Node =
        NaverSubwayAccessibilityParser.Node(
            children = blobs.map { NaverSubwayAccessibilityParser.Node(text = it) },
        )

    private fun node(vararg texts: String): NaverSubwayAccessibilityParser.Node {
        val blobs = texts.toList().let { list ->
            if (list.any { it == "안내 중" } && list.none { it == "안내 종료" || it == "안내종료" }) {
                list + "안내 종료"
            } else {
                list
            }
        }
        return NaverSubwayAccessibilityParser.Node(
            children = blobs.map { NaverSubwayAccessibilityParser.Node(text = it) },
        )
    }
}
