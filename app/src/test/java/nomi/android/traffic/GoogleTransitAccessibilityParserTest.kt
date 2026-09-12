package nomi.android.traffic

import nomi.product.nav.NavigationEvent
import nomi.product.nav.NavigationEventSource
import nomi.product.nav.NavigationEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleTransitAccessibilityParserTest {

    @Test
    fun `route options before start stay silent even with second-leg bus`() {
        val now = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 9)
            set(java.util.Calendar.MINUTE, 38)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val events = GoogleTransitAccessibilityParser.parse(
            packageName = GoogleMapsTransit.PACKAGE,
            root = GoogleTransitAccessibilityParser.Node(
                children = listOf(
                    GoogleTransitAccessibilityParser.Node(text = "대중교통"),
                    GoogleTransitAccessibilityParser.Node(text = "44분"),
                    GoogleTransitAccessibilityParser.Node(text = "오전 9:38 – 오전 10:22"),
                    GoogleTransitAccessibilityParser.Node(contentDesc = "버스, 790"),
                    GoogleTransitAccessibilityParser.Node(text = "790"),
                    GoogleTransitAccessibilityParser.Node(
                        text = "예정 시간: 오전 9:48 정발산(고양아람누리)에서 출발",
                    ),
                    GoogleTransitAccessibilityParser.Node(
                        text = "기타: 예정 시간: 오전 9:41, 오전 10:02",
                    ),
                    GoogleTransitAccessibilityParser.Node(contentDesc = "한눈에 보기 시작"),
                ),
            ),
            timestampMillis = now,
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `same card bus number and realtime minutes become one arrival`() {
        val events = GoogleTransitAccessibilityParser.parse(
            packageName = GoogleMapsTransit.PACKAGE,
            root = busListCard(
                numberDesc = "버스, \u200b140",
                numberText = "\u200b\u200b140\u200b",
                etaText = "예정 시간: 오후 3:09 지하철2호선강남역에서 출발\n기타: 4분(실시간) 후",
            ),
        )
        assertEquals(1, events.size)
        val event = events[0]
        assertEquals(NavigationEventSource.GOOGLE, event.source)
        assertEquals(NavigationEventType.TRANSIT, event.type)
        assertEquals("140", event.busInfo!!.arrivals[0].line)
        assertEquals("4분", event.busInfo!!.arrivals[0].eta)
        assertEquals("140번 버스가 4분 후 도착해요.", NavigationEventSpeech.line(event))
    }

    @Test
    fun `google realtime now without minutes speaks soon`() {
        val events = GoogleTransitAccessibilityParser.parse(
            packageName = GoogleMapsTransit.PACKAGE,
            root = busListCard(
                numberDesc = "버스, 140",
                numberText = "140",
                etaText = "기타: 지금(실시간) 후",
            ),
        )
        assertEquals("140번 버스, 곧 도착합니다.", NavigationEventSpeech.line(events.first()))
    }

    @Test
    fun `live glance bare line with 지금 출발 speaks soon`() {
        val events = GoogleTransitAccessibilityParser.parse(
            packageName = GoogleMapsTransit.PACKAGE,
            root = GoogleTransitAccessibilityParser.Node(
                children = listOf(
                    GoogleTransitAccessibilityParser.Node(
                        contentDesc = "버스, 83, 95, 150, 760 또는 7727",
                    ),
                    GoogleTransitAccessibilityParser.Node(
                        children = listOf(
                            GoogleTransitAccessibilityParser.Node(text = "7727", contentDesc = "7727"),
                            GoogleTransitAccessibilityParser.Node(text = "신촌"),
                            GoogleTransitAccessibilityParser.Node(text = "실시간"),
                            GoogleTransitAccessibilityParser.Node(
                                text = "지금",
                                contentDesc = "지금 출발",
                            ),
                            GoogleTransitAccessibilityParser.Node(text = "기타: 오후 8:49(실시간)"),
                            GoogleTransitAccessibilityParser.Node(
                                text = "정류장 2개(5분) 이동",
                                contentDesc = "정류장 2개(5분) 이동. 단계를 접었습니다.",
                            ),
                            GoogleTransitAccessibilityParser.Node(
                                contentDesc = "7727, 마두역(중)까지 이동, 경로 세부정보를 접으려면 두 번 탭하세요.",
                            ),
                            GoogleTransitAccessibilityParser.Node(
                                contentDesc = "이 이동의 경로 한눈에 보기 종료",
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(1, events.size)
        assertEquals("7727", events[0].busInfo!!.arrivals[0].line)
        assertEquals("곧", events[0].busInfo!!.arrivals[0].eta)
        assertEquals("7727번 버스, 곧 도착합니다.", NavigationEventSpeech.line(events[0]))
        assertTrue(events.none { it.action == GoogleMapsTransit.PREPARE_ALIGHT_ACTION })
    }

    @Test
    fun `live glance primary clock becomes remaining minutes`() {
        val now = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 20)
            set(java.util.Calendar.MINUTE, 44)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val events = GoogleTransitAccessibilityParser.parse(
            packageName = GoogleMapsTransit.PACKAGE,
            root = GoogleTransitAccessibilityParser.Node(
                children = listOf(
                    GoogleTransitAccessibilityParser.Node(text = "7727", contentDesc = "7727"),
                    GoogleTransitAccessibilityParser.Node(text = "실시간"),
                    GoogleTransitAccessibilityParser.Node(
                        text = "오후 8:46",
                        contentDesc = "오후 8:46에 출발",
                    ),
                    GoogleTransitAccessibilityParser.Node(text = "기타: 오후 8:49(실시간)"),
                    GoogleTransitAccessibilityParser.Node(
                        contentDesc = "이 이동의 경로 한눈에 보기 종료",
                    ),
                ),
            ),
            timestampMillis = now,
        )
        assertEquals("7727", events[0].busInfo!!.arrivals[0].line)
        assertEquals("2분", events[0].busInfo!!.arrivals[0].eta)
        assertEquals("7727번 버스가 2분 후 도착해요.", NavigationEventSpeech.line(events[0]))
        assertTrue(NavigationEventSpeechGate().accept(events[0]))
    }

    @Test
    fun `trip start speaks subway first leg with walk`() {
        val now = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 9)
            set(java.util.Calendar.MINUTE, 38)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val decision = GoogleTransitAccessibilityParser.tripStartDecision(
            packageName = GoogleMapsTransit.PACKAGE,
            root = GoogleTransitAccessibilityParser.Node(
                children = listOf(
                    GoogleTransitAccessibilityParser.Node(
                        contentDesc = "도보 10분후 지하철, 3호선후 버스, 313",
                    ),
                    GoogleTransitAccessibilityParser.Node(text = "도보 약 9분"),
                    GoogleTransitAccessibilityParser.Node(
                        text = "3호선",
                        contentDesc = "지하철, 3호선",
                    ),
                    GoogleTransitAccessibilityParser.Node(
                        text = "오전 9:48",
                        contentDesc = "오전 9:48에 출발",
                    ),
                    GoogleTransitAccessibilityParser.Node(text = "2 통해 들어가기"),
                    GoogleTransitAccessibilityParser.Node(
                        contentDesc = "이 이동의 경로 한눈에 보기 종료",
                    ),
                ),
            ),
            timestampMillis = now,
        )
        val event = (decision as GoogleTransitAccessibilityParser.TripStartDecision.Speak).event
        assertEquals(GoogleMapsTransit.KIND_SUBWAY, event.rawText)
        assertEquals("3호선", event.busInfo!!.arrivals[0].line)
        assertEquals("10분", event.busInfo!!.arrivals[0].eta)
        assertEquals(10, event.distanceMeters)
        assertEquals(
            "3호선이 10분 후 출발합니다. 역까지는 걸어서 약 10분입니다.",
            NavigationEventSpeech.line(event),
        )
    }

    @Test
    fun `trip start speaks any eta plus walk and ignores stage gate`() {
        val briefing = GoogleTransitAccessibilityParser.tripStartBriefing(
            packageName = GoogleMapsTransit.PACKAGE,
            root = GoogleTransitAccessibilityParser.Node(
                children = listOf(
                    GoogleTransitAccessibilityParser.Node(
                        contentDesc = "도보 6분후 버스, 1100(평일운행)후 도보 4분후 지하철, 2호선",
                    ),
                    GoogleTransitAccessibilityParser.Node(
                        text = "1100(평일운행)",
                        contentDesc = "버스, 1100(평일운행)",
                    ),
                    GoogleTransitAccessibilityParser.Node(text = "도보 약 6분"),
                    GoogleTransitAccessibilityParser.Node(
                        text = "지금",
                        contentDesc = "지금 출발",
                    ),
                    GoogleTransitAccessibilityParser.Node(
                        contentDesc = "이 이동의 경로 한눈에 보기 시작",
                    ),
                ),
            ),
        )
        assertEquals(GoogleMapsTransit.TRIP_START_ACTION, briefing!!.action)
        assertEquals(GoogleMapsTransit.KIND_BUS, briefing.rawText)
        assertEquals("1100", briefing.busInfo!!.arrivals[0].line)
        assertEquals("곧", briefing.busInfo!!.arrivals[0].eta)
        assertEquals(6, briefing.distanceMeters)
        assertEquals(
            "1100번 버스, 곧 도착합니다. 정류장까지는 걸어서 약 6분입니다.",
            NavigationEventSpeech.line(briefing),
        )
        val gate = NavigationEventSpeechGate()
        assertTrue(gate.accept(briefing))
        assertFalse(gate.accept(briefing))
    }

    @Test
    fun `trip start with seven minutes is not blocked by stage rules`() {
        val withMinutes = GoogleTransitAccessibilityParser.tripStartBriefing(
            packageName = GoogleMapsTransit.PACKAGE,
            root = GoogleTransitAccessibilityParser.Node(
                children = listOf(
                    GoogleTransitAccessibilityParser.Node(text = "도보 약 6분"),
                    GoogleTransitAccessibilityParser.Node(
                        text = "1100",
                        contentDesc = "버스, 1100",
                    ),
                    GoogleTransitAccessibilityParser.Node(text = "7분(실시간) 후"),
                ),
            ),
        )
        assertEquals("7분", withMinutes!!.busInfo!!.arrivals[0].eta)
        assertEquals(
            "1100번 버스가 7분 후 도착합니다. 정류장까지는 걸어서 약 6분입니다.",
            NavigationEventSpeech.line(withMinutes),
        )
        assertTrue(NavigationEventSpeechGate().accept(withMinutes))
    }

    @Test
    fun `walk minutes on the same card are not used as arrival`() {
        val events = GoogleTransitAccessibilityParser.parse(
            packageName = GoogleMapsTransit.PACKAGE,
            root = busListCard(
                headerDesc = "도보 6분후 버스, \u200b140",
                numberDesc = "버스, \u200b140",
                numberText = "140",
                walkText = "6",
                walkDesc = "도보 6분",
                durationText = "43분",
                durationDesc = "소요 시간: 43분",
                etaText = "예정 시간: 오후 3:09 지하철2호선강남역에서 출발\n기타: 지금, 6분(실시간) 후",
            ),
        )
        assertEquals("140", events[0].busInfo!!.arrivals[0].line)
        assertEquals("6분", events[0].busInfo!!.arrivals[0].eta)
    }

    @Test
    fun `bus number without realtime minutes is ignored`() {
        val events = GoogleTransitAccessibilityParser.parse(
            packageName = GoogleMapsTransit.PACKAGE,
            root = GoogleTransitAccessibilityParser.Node(
                children = listOf(
                    GoogleTransitAccessibilityParser.Node(
                        text = "140",
                        contentDesc = "버스, 140",
                    ),
                    GoogleTransitAccessibilityParser.Node(text = "기타: 오후 3:14(실시간)"),
                ),
            ),
        )
        assertTrue(events.none { it.busInfo != null })
    }

    @Test
    fun `subway card without bus is not an arrival`() {
        val events = GoogleTransitAccessibilityParser.parse(
            packageName = GoogleMapsTransit.PACKAGE,
            root = GoogleTransitAccessibilityParser.Node(
                contentDesc = "지하철, 2호선후 지하철, 4호선",
                children = listOf(
                    GoogleTransitAccessibilityParser.Node(text = "2호선", contentDesc = "지하철, 2호선"),
                    GoogleTransitAccessibilityParser.Node(text = "39분", contentDesc = "소요 시간: 39분"),
                ),
            ),
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `enter preferred when walking without ride remaining preview`() {
        val events = GoogleTransitAccessibilityParser.parse(
            packageName = GoogleMapsTransit.PACKAGE,
            root = GoogleTransitAccessibilityParser.Node(
                children = listOf(
                    GoogleTransitAccessibilityParser.Node(text = "도보 약 9분"),
                    GoogleTransitAccessibilityParser.Node(text = "5 통해 나가기"),
                    GoogleTransitAccessibilityParser.Node(text = "5 통해 들어가기"),
                    GoogleTransitAccessibilityParser.Node(text = "2 통해 들어가기"),
                    GoogleTransitAccessibilityParser.Node(
                        contentDesc = "이 이동의 경로 한눈에 보기 종료",
                    ),
                ),
            ),
        )
        assertEquals(listOf("5 통해 들어가기"), events.map { it.action })
        assertEquals("5번 출구를 통해 들어가세요.", NavigationEventSpeech.line(events[0]))
    }

    @Test
    fun `expanded sheet with many stops left stays silent on distant exit`() {
        val events = GoogleTransitAccessibilityParser.parse(
            packageName = GoogleMapsTransit.PACKAGE,
            root = GoogleTransitAccessibilityParser.Node(
                children = listOf(
                    GoogleTransitAccessibilityParser.Node(
                        contentDesc = "도보 10분후 지하철, 3호선후 버스, 790",
                    ),
                    GoogleTransitAccessibilityParser.Node(text = "도보 약 9분"),
                    GoogleTransitAccessibilityParser.Node(text = "정류장 7개(20분) 이동"),
                    GoogleTransitAccessibilityParser.Node(text = "삼송 (중부대학교)"),
                    GoogleTransitAccessibilityParser.Node(text = "2 통해 나가기"),
                    GoogleTransitAccessibilityParser.Node(
                        contentDesc = "이 이동의 경로 한눈에 보기 종료",
                    ),
                ),
            ),
        )
        assertTrue(events.none { it.action.contains("통해") })
    }

    @Test
    fun `subway wait speaks 10 5 2 soon stages like bus`() {
        val now = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 10)
            set(java.util.Calendar.MINUTE, 16)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        fun at(minute: Int) = GoogleTransitAccessibilityParser.parse(
            packageName = GoogleMapsTransit.PACKAGE,
            root = GoogleTransitAccessibilityParser.Node(
                children = listOf(
                    GoogleTransitAccessibilityParser.Node(text = "도보 약 1분"),
                    GoogleTransitAccessibilityParser.Node(
                        text = "3호선",
                        contentDesc = "지하철, 3호선",
                    ),
                    GoogleTransitAccessibilityParser.Node(text = "오금역 방면"),
                    GoogleTransitAccessibilityParser.Node(text = "예정됨"),
                    GoogleTransitAccessibilityParser.Node(
                        text = "오전 10:$minute",
                        contentDesc = "오전 10:${minute}에 출발",
                    ),
                    GoogleTransitAccessibilityParser.Node(
                        contentDesc = "이 이동의 경로 한눈에 보기 종료",
                    ),
                ),
            ),
            timestampMillis = now,
        ).first { it.busInfo != null }

        val gate = NavigationEventSpeechGate()
        val at10 = at(26) // 10 min
        assertEquals("10분", at10.busInfo!!.arrivals[0].eta)
        assertEquals("3호선이 10분 후 출발해요.", NavigationEventSpeech.line(at10))
        assertTrue(gate.accept(at10))

        val at5 = GoogleTransitAccessibilityParser.parse(
            packageName = GoogleMapsTransit.PACKAGE,
            root = GoogleTransitAccessibilityParser.Node(
                children = listOf(
                    GoogleTransitAccessibilityParser.Node(text = "도보 약 1분"),
                    GoogleTransitAccessibilityParser.Node(
                        text = "3호선",
                        contentDesc = "지하철, 3호선",
                    ),
                    GoogleTransitAccessibilityParser.Node(text = "오금역 방면"),
                    GoogleTransitAccessibilityParser.Node(
                        text = "오전 10:21",
                        contentDesc = "오전 10:21에 출발",
                    ),
                    GoogleTransitAccessibilityParser.Node(
                        contentDesc = "이 이동의 경로 한눈에 보기 종료",
                    ),
                ),
            ),
            timestampMillis = now,
        ).first { it.busInfo != null }
        assertEquals("5분", at5.busInfo!!.arrivals[0].eta)
        assertEquals("3호선이 5분 후 출발해요.", NavigationEventSpeech.line(at5))
        assertTrue(gate.accept(at5))
    }

    @Test
    fun `subway wait silent while still a long walk away`() {
        val now = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 10)
            set(java.util.Calendar.MINUTE, 16)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val events = GoogleTransitAccessibilityParser.parse(
            packageName = GoogleMapsTransit.PACKAGE,
            root = GoogleTransitAccessibilityParser.Node(
                children = listOf(
                    GoogleTransitAccessibilityParser.Node(text = "도보 약 9분"),
                    GoogleTransitAccessibilityParser.Node(
                        text = "3호선",
                        contentDesc = "지하철, 3호선",
                    ),
                    GoogleTransitAccessibilityParser.Node(text = "오금역 방면"),
                    GoogleTransitAccessibilityParser.Node(
                        text = "오전 10:26",
                        contentDesc = "오전 10:26에 출발",
                    ),
                    GoogleTransitAccessibilityParser.Node(
                        contentDesc = "이 이동의 경로 한눈에 보기 종료",
                    ),
                ),
            ),
            timestampMillis = now,
        )
        assertTrue(events.none { it.busInfo != null })
    }

    @Test
    fun `board direction speaks at end of walk to station`() {
        val events = GoogleTransitAccessibilityParser.parse(
            packageName = GoogleMapsTransit.PACKAGE,
            root = GoogleTransitAccessibilityParser.Node(
                children = listOf(
                    GoogleTransitAccessibilityParser.Node(text = "도보 약 2분"),
                    GoogleTransitAccessibilityParser.Node(text = "정발산 (고양아람누리)"),
                    GoogleTransitAccessibilityParser.Node(text = "2 통해 들어가기"),
                    GoogleTransitAccessibilityParser.Node(
                        text = "3호선",
                        contentDesc = "지하철, 3호선",
                    ),
                    GoogleTransitAccessibilityParser.Node(text = "오금역 방면"),
                    GoogleTransitAccessibilityParser.Node(
                        contentDesc = "이 이동의 경로 한눈에 보기 종료",
                    ),
                ),
            ),
        )
        val board = events.single { it.action == GoogleMapsTransit.BOARD_DIRECTION_ACTION }
        assertEquals("3호선", board.title)
        assertEquals("오금역 방면", board.landmark)
        assertEquals("3호선입니다. 오금역 방면입니다.", NavigationEventSpeech.line(board))
        assertTrue(events.any { it.action == "2 통해 들어가기" })
    }

    @Test
    fun `board direction stays silent while still a long walk away`() {
        val events = GoogleTransitAccessibilityParser.parse(
            packageName = GoogleMapsTransit.PACKAGE,
            root = GoogleTransitAccessibilityParser.Node(
                children = listOf(
                    GoogleTransitAccessibilityParser.Node(text = "도보 약 9분"),
                    GoogleTransitAccessibilityParser.Node(
                        text = "3호선",
                        contentDesc = "지하철, 3호선",
                    ),
                    GoogleTransitAccessibilityParser.Node(text = "오금역 방면"),
                    GoogleTransitAccessibilityParser.Node(text = "2 통해 들어가기"),
                    GoogleTransitAccessibilityParser.Node(
                        contentDesc = "이 이동의 경로 한눈에 보기 종료",
                    ),
                ),
            ),
        )
        assertTrue(events.none { it.action == GoogleMapsTransit.BOARD_DIRECTION_ACTION })
    }

    @Test
    fun `exit speaks only when one stop remains`() {
        val events = GoogleTransitAccessibilityParser.parse(
            packageName = GoogleMapsTransit.PACKAGE,
            root = GoogleTransitAccessibilityParser.Node(
                children = listOf(
                    GoogleTransitAccessibilityParser.Node(text = "정류장 1개(3분) 이동"),
                    GoogleTransitAccessibilityParser.Node(text = "삼송 (중부대학교)"),
                    GoogleTransitAccessibilityParser.Node(text = "2 통해 나가기"),
                    GoogleTransitAccessibilityParser.Node(
                        contentDesc = "3호선, 삼송 (중부대학교)까지 이동, 경로 세부정보를 접으려면 두 번 탭하세요.",
                    ),
                    GoogleTransitAccessibilityParser.Node(
                        contentDesc = "이 이동의 경로 한눈에 보기 종료",
                    ),
                ),
            ),
        )
        val passage = events.single { it.action.contains("통해") }
        assertEquals("2 통해 나가기", passage.action)
        assertEquals("2번 출구를 통해 나가세요.", NavigationEventSpeech.line(passage))
    }

    @Test
    fun `subway-first live walk does not speak later bus leg`() {
        val events = GoogleTransitAccessibilityParser.parse(
            packageName = GoogleMapsTransit.PACKAGE,
            root = GoogleTransitAccessibilityParser.Node(
                children = listOf(
                    GoogleTransitAccessibilityParser.Node(
                        contentDesc = "도보 10분후 지하철, 3호선후 버스, 790",
                    ),
                    GoogleTransitAccessibilityParser.Node(text = "도보 약 9분"),
                    GoogleTransitAccessibilityParser.Node(text = "2 통해 들어가기"),
                    GoogleTransitAccessibilityParser.Node(text = "790", contentDesc = "버스, 790"),
                    GoogleTransitAccessibilityParser.Node(text = "11분(실시간) 후"),
                    GoogleTransitAccessibilityParser.Node(
                        contentDesc = "이 이동의 경로 한눈에 보기 종료",
                    ),
                ),
            ),
        )
        assertTrue(events.none { it.busInfo?.arrivals?.any { a -> a.line == "790" } == true })
        assertEquals("2 통해 들어가기", events.single().action)
        assertEquals("2번 출구를 통해 들어가세요.", NavigationEventSpeech.line(events.single()))
    }

    @Test
    fun `other packages are ignored`() {
        val events = GoogleTransitAccessibilityParser.parse(
            packageName = "com.nhn.android.nmap",
            root = busListCard(
                numberDesc = "버스, 140",
                numberText = "140",
                etaText = "기타: 4분(실시간) 후",
            ),
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `identical bus pair is skipped until eta changes`() {
        val dedup = GoogleTransitAccessibilityDedup()
        val at6 = GoogleTransitAccessibilityParser.parse(
            packageName = GoogleMapsTransit.PACKAGE,
            root = busListCard(
                numberDesc = "버스, 140",
                numberText = "140",
                etaText = "기타: 6분(실시간) 후",
            ),
        ).first()
        val still6 = GoogleTransitAccessibilityParser.parse(
            packageName = GoogleMapsTransit.PACKAGE,
            root = busListCard(
                numberDesc = "버스, 140",
                numberText = "140",
                etaText = "기타: 6분(실시간) 후",
            ),
        ).first()
        val at5 = GoogleTransitAccessibilityParser.parse(
            packageName = GoogleMapsTransit.PACKAGE,
            root = busListCard(
                numberDesc = "버스, 140",
                numberText = "140",
                etaText = "기타: 5분(실시간) 후",
            ),
        ).first()
        assertTrue(dedup.accept(at6))
        assertFalse(dedup.accept(still6))
        assertTrue(dedup.accept(at5))
    }

    @Test
    fun `gate speaks google bus at 10 then 5 then 2 then soon`() {
        val gate = NavigationEventSpeechGate()
        val at10 = busEvent(10)
        val at9 = busEvent(9)
        val at5 = busEvent(5)
        val at4 = busEvent(4)
        val at3 = busEvent(3)
        val at2 = busEvent(2)
        val at1 = busEvent(1)
        val soon = soonEvent()
        assertTrue(gate.accept(at10))
        assertFalse(gate.accept(at9))
        assertFalse(gate.accept(at10))
        assertTrue(gate.accept(at5))
        assertFalse(gate.accept(at4))
        assertFalse(gate.accept(at3))
        assertTrue(gate.accept(at2))
        assertFalse(gate.accept(at1))
        assertNull(NavigationEventSpeech.line(at1))
        assertTrue(gate.accept(soon))
        assertFalse(gate.accept(at2))
        assertEquals("140번 버스, 곧 도착합니다.", NavigationEventSpeech.line(soon))
    }

    @Test
    fun `google bus gate does not repeat 10 after jitter back up`() {
        val gate = NavigationEventSpeechGate()
        assertTrue(gate.accept(busEvent(10)))
        assertFalse(gate.accept(busEvent(9)))
        assertFalse(gate.accept(busEvent(10)))
    }

    @Test
    fun `google bus gate does not repeat 5 after jitter back up`() {
        val gate = NavigationEventSpeechGate()
        assertTrue(gate.accept(busEvent(10)))
        assertTrue(gate.accept(busEvent(5)))
        assertFalse(gate.accept(busEvent(4)))
        assertFalse(gate.accept(busEvent(5)))
    }

    @Test
    fun `google bus gate does not repeat 2 after jitter to 1`() {
        val gate = NavigationEventSpeechGate()
        assertTrue(gate.accept(busEvent(10)))
        assertTrue(gate.accept(busEvent(5)))
        assertTrue(gate.accept(busEvent(2)))
        assertFalse(gate.accept(busEvent(1)))
        assertFalse(gate.accept(busEvent(2)))
    }

    @Test
    fun `gate speaks each passage cue once`() {
        val gate = NavigationEventSpeechGate()
        val enter = passageEvent("5 통해 들어가기")
        val leave = passageEvent("5 통해 나가기")
        assertTrue(gate.accept(leave))
        assertFalse(gate.accept(leave))
        assertTrue(gate.accept(enter))
        assertFalse(gate.accept(enter))
    }

    @Test
    fun `preview remaining one stop does not alight without live trip`() {
        val events = GoogleTransitAccessibilityParser.parse(
            packageName = GoogleMapsTransit.PACKAGE,
            root = GoogleTransitAccessibilityParser.Node(
                children = listOf(
                    GoogleTransitAccessibilityParser.Node(
                        text = "정류장 1개(3분) 이동",
                        contentDesc = "정류장 1개(3분) 이동. 단계를 접었습니다.",
                    ),
                    GoogleTransitAccessibilityParser.Node(text = "교대"),
                ),
            ),
        )
        assertTrue(events.none { isAlightFamily(it) })
    }

    @Test
    fun `fourteen remaining stops is not alight`() {
        val events = GoogleTransitAccessibilityParser.parse(
            packageName = GoogleMapsTransit.PACKAGE,
            root = liveTrip(
                remaining = "정류장 14개(37분) 이동",
                hud = "140, 혜화역.마로니에공원까지 이동, 경로 세부정보를 접으려면 두 번 탭하세요.",
            ),
        )
        assertTrue(events.none { isAlightFamily(it) })
    }

    @Test
    fun `live glance and one remaining stop speak named bus alight once`() {
        val events = GoogleTransitAccessibilityParser.parse(
            packageName = GoogleMapsTransit.PACKAGE,
            root = liveTrip(
                remaining = "정류장 1개(3분) 이동",
                hud = "9707, 일산동구청(중)까지 이동, 경로 세부정보를 접으려면 두 번 탭하세요.",
                end = "이 이동의 경로 한눈에 보기 종료",
            ),
        )
        val alight = events.single { it.action == GoogleMapsTransit.ALIGHT_ACTION }
        assertEquals(NavigationEventSource.GOOGLE, alight.source)
        assertEquals("일산동구청(중)", alight.landmark)
        assertEquals(
            "이번 정류장은 일산동구청(중)입니다. 이번 정류장에서 하차하세요.",
            NavigationEventSpeech.line(alight),
        )
        val gate = NavigationEventSpeechGate()
        val dedup = GoogleTransitAccessibilityDedup()
        assertTrue(dedup.accept(alight))
        assertFalse(dedup.accept(alight))
        assertTrue(gate.accept(alight))
        assertFalse(gate.accept(alight))
    }

    @Test
    fun `bus remaining one without live end is not alight`() {
        val events = GoogleTransitAccessibilityParser.parse(
            packageName = GoogleMapsTransit.PACKAGE,
            root = liveTrip(
                remaining = "정류장 1개(3분) 이동",
                hud = "9707, 일산동구청(중)까지 이동, 경로 세부정보를 접으려면 두 번 탭하세요.",
            ),
        )
        assertTrue(events.none { isAlightFamily(it) })
    }

    @Test
    fun `live remaining one without bus stop name is not named alight`() {
        val events = GoogleTransitAccessibilityParser.parse(
            packageName = GoogleMapsTransit.PACKAGE,
            root = liveTrip(
                remaining = "정류장 1개(3분) 이동",
                hud = "경로 세부정보를 접으려면 두 번 탭하세요.",
                end = "이 이동의 경로 한눈에 보기 종료",
            ),
        )
        assertTrue(events.none { isAlightFamily(it) })
    }

    @Test
    fun `subway remaining one stop is not a bus alight`() {
        val events = GoogleTransitAccessibilityParser.parse(
            packageName = GoogleMapsTransit.PACKAGE,
            root = liveTrip(
                remaining = "정류장 1개(3분) 이동",
                hud = "2호선, 교대까지 이동, 경로 세부정보를 펼치려면 두 번 탭하세요.",
            ),
        )
        assertTrue(events.none { isAlightFamily(it) })
    }

    @Test
    fun `live bus two remaining speaks prepare then one remaining speaks alight`() {
        val prepareEvents = GoogleTransitAccessibilityParser.parse(
            packageName = GoogleMapsTransit.PACKAGE,
            root = liveTrip(
                remaining = "정류장 2개(8분) 이동",
                hud = "9707, 일산동구청(중)까지 이동, 경로 세부정보를 접으려면 두 번 탭하세요.",
                end = "이 이동의 경로 한눈에 보기 종료",
            ),
        )
        val prepare = prepareEvents.single { it.action == GoogleMapsTransit.PREPARE_ALIGHT_ACTION }
        assertEquals("일산동구청(중)", prepare.landmark)
        assertEquals(
            "다음은 일산동구청(중)입니다. 하차 준비하세요.",
            NavigationEventSpeech.line(prepare),
        )
        val alightEvents = GoogleTransitAccessibilityParser.parse(
            packageName = GoogleMapsTransit.PACKAGE,
            root = liveTrip(
                remaining = "정류장 1개(3분) 이동",
                hud = "9707, 일산동구청(중)까지 이동, 경로 세부정보를 접으려면 두 번 탭하세요.",
                end = "이 이동의 경로 한눈에 보기 종료",
            ),
        )
        val alight = alightEvents.single { it.action == GoogleMapsTransit.ALIGHT_ACTION }
        assertEquals(
            "이번 정류장은 일산동구청(중)입니다. 이번 정류장에서 하차하세요.",
            NavigationEventSpeech.line(alight),
        )
        val gate = NavigationEventSpeechGate()
        val dedup = GoogleTransitAccessibilityDedup()
        assertTrue(dedup.accept(prepare))
        assertFalse(dedup.accept(prepare))
        assertTrue(dedup.accept(alight))
        assertFalse(dedup.accept(alight))
        assertTrue(gate.accept(prepare))
        assertFalse(gate.accept(prepare))
        assertTrue(gate.accept(alight))
        assertFalse(gate.accept(alight))
    }

    @Test
    fun `live subway two remaining speaks named prepare then alight`() {
        val prepareEvents = GoogleTransitAccessibilityParser.parse(
            packageName = GoogleMapsTransit.PACKAGE,
            root = liveTrip(
                remaining = "역 2개(4분) 이동",
                hud = "2호선, 교대까지 이동, 경로 세부정보를 접으려면 두 번 탭하세요.",
                end = "이 이동의 경로 한눈에 보기 종료",
            ),
        )
        val prepare = prepareEvents.single { it.action == GoogleMapsTransit.PREPARE_ALIGHT_ACTION }
        assertEquals("교대", prepare.landmark)
        assertEquals(GoogleMapsTransit.KIND_SUBWAY, prepare.rawText)
        assertEquals("다음은 교대입니다. 하차 준비하세요.", NavigationEventSpeech.line(prepare))
        val alightEvents = GoogleTransitAccessibilityParser.parse(
            packageName = GoogleMapsTransit.PACKAGE,
            root = liveTrip(
                remaining = "역 1개(2분) 이동",
                hud = "2호선, 교대까지 이동, 경로 세부정보를 접으려면 두 번 탭하세요.",
                end = "이 이동의 경로 한눈에 보기 종료",
            ),
        )
        val alight = alightEvents.single { it.action == GoogleMapsTransit.ALIGHT_ACTION }
        assertEquals("교대", alight.landmark)
        assertEquals(
            "이번 역은 교대입니다. 이번 역에서 하차하세요.",
            NavigationEventSpeech.line(alight),
        )
        val gate = NavigationEventSpeechGate()
        assertTrue(gate.accept(prepare))
        assertTrue(gate.accept(alight))
        assertFalse(gate.accept(alight))
    }

    @Test
    fun `live subway remaining one without station name stays unnamed`() {
        val events = GoogleTransitAccessibilityParser.parse(
            packageName = GoogleMapsTransit.PACKAGE,
            root = liveTrip(
                remaining = "역 1개(2분) 이동",
                hud = "경로 세부정보를 접으려면 두 번 탭하세요.",
                end = "이 이동의 경로 한눈에 보기 종료",
            ),
        )
        val alight = events.single { it.action == GoogleMapsTransit.ALIGHT_ACTION }
        assertEquals(null, alight.landmark)
        assertEquals("이번 역에서 하차하세요.", NavigationEventSpeech.line(alight))
    }

    private fun isAlightFamily(event: NavigationEvent): Boolean =
        event.action == GoogleMapsTransit.ALIGHT_ACTION ||
            event.action == GoogleMapsTransit.PREPARE_ALIGHT_ACTION

    private fun liveTrip(
        remaining: String,
        hud: String,
        end: String? = null,
    ) = GoogleTransitAccessibilityParser.Node(
        children = listOfNotNull(
            end?.let { GoogleTransitAccessibilityParser.Node(contentDesc = it) },
            GoogleTransitAccessibilityParser.Node(text = remaining, contentDesc = remaining),
            GoogleTransitAccessibilityParser.Node(contentDesc = hud),
        ),
    )

    private fun busEvent(minutes: Int) = GoogleTransitAccessibilityParser.parse(
        packageName = GoogleMapsTransit.PACKAGE,
        root = busListCard(
            numberDesc = "버스, 140",
            numberText = "140",
            etaText = "기타: ${minutes}분(실시간) 후",
        ),
    ).first()

    private fun soonEvent() = GoogleTransitAccessibilityParser.parse(
        packageName = GoogleMapsTransit.PACKAGE,
        root = busListCard(
            numberDesc = "버스, 140",
            numberText = "140",
            etaText = "기타: 지금(실시간) 후",
        ),
    ).first()

    private fun passageEvent(cue: String) = GoogleTransitAccessibilityParser.parse(
        packageName = GoogleMapsTransit.PACKAGE,
        root = GoogleTransitAccessibilityParser.Node(
            children = listOfNotNull(
                if (cue.endsWith("들어가기")) {
                    GoogleTransitAccessibilityParser.Node(text = "도보 약 2분")
                } else {
                    GoogleTransitAccessibilityParser.Node(text = "정류장 1개(2분) 이동")
                },
                GoogleTransitAccessibilityParser.Node(text = cue),
                GoogleTransitAccessibilityParser.Node(
                    contentDesc = "이 이동의 경로 한눈에 보기 종료",
                ),
            ),
        ),
    ).first { it.action.contains("통해") }

    private fun busListCard(
        numberDesc: String,
        numberText: String,
        etaText: String,
        headerDesc: String? = null,
        walkText: String? = null,
        walkDesc: String? = null,
        durationText: String? = null,
        durationDesc: String? = null,
    ) = GoogleTransitAccessibilityParser.Node(
        children = listOf(
            GoogleTransitAccessibilityParser.Node(
                children = listOfNotNull(
                    headerDesc?.let { GoogleTransitAccessibilityParser.Node(contentDesc = it) },
                    GoogleTransitAccessibilityParser.Node(
                        text = numberText,
                        contentDesc = numberDesc,
                    ),
                    walkText?.let {
                        GoogleTransitAccessibilityParser.Node(text = it, contentDesc = walkDesc)
                    },
                    durationText?.let {
                        GoogleTransitAccessibilityParser.Node(text = it, contentDesc = durationDesc)
                    },
                    GoogleTransitAccessibilityParser.Node(text = etaText),
                    GoogleTransitAccessibilityParser.Node(
                        contentDesc = "이 이동의 경로 한눈에 보기 종료",
                    ),
                ),
            ),
        ),
    )
}
