package nomi.android.traffic

import nomi.product.nav.NavigationBusArrival
import nomi.product.nav.NavigationBusInfo
import nomi.product.nav.NavigationEvent
import nomi.product.nav.NavigationEventSource
import nomi.product.nav.NavigationEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationEventSpeechTest {

    /**
     * [NavigationEventSpeech] reads no tracker state, so nothing here needs a
     * clean slate. Only the tests that arm 부근 on purpose leave a mark to clear.
     */
    @After
    fun tearDown() {
        NaverNearBoardNotice.reset()
    }

    @Test
    fun `walk landmark is silent in product v1`() {
        assertNull(
            NavigationEventSpeech.line(
                walk(
                    action = "횡단보도 건너기",
                    meters = 33,
                    landmark = "고두리김치생삼겹살",
                ),
            ),
        )
        assertNull(
            NavigationEventSpeech.line(
                walk(action = "직진", meters = 20),
            ),
        )
    }

    @Test
    fun `parser walk snapshot does not speak`() {
        val event = NaverNotificationParser.parse(
            NaverNotificationParser.Snapshot(
                packageName = "com.nhn.android.nmap",
                notificationId = 501,
                channel = "350_WALK_NAVIGATION",
                title = "고두리김치생삼겹살 방면으로 횡단보도 건너기",
                text = "20m 남음",
                action = "횡단보도 건너기",
            ),
        )
        assertNull(NavigationEventSpeech.line(event!!))
    }

    @Test
    fun `walk events are rejected by the speech gate`() {
        val gate = NavigationEventSpeechGate()
        assertFalse(
            gate.accept(
                walk(
                    action = "횡단보도 건너기",
                    meters = 33,
                    landmark = "고두리김치생삼겹살",
                ),
            ),
        )
    }

    @Test
    fun `transit first bus and minutes become a spoken line`() {
        val line = NavigationEventSpeech.line(
            transit(
                NavigationBusInfo(
                    raw = "11 (6분), 11 (13분)",
                    arrivals = listOf(
                        NavigationBusArrival("11", "6분"),
                        NavigationBusArrival("11", "13분"),
                    ),
                ),
            ),
        )
        assertEquals("11번, 11번 버스가 6분 후 도착해요. 다음은 11번, 13분 후 도착입니다.", line)
    }

    @Test
    fun `transit without parsed arrivals is silent`() {
        assertNull(
            NavigationEventSpeech.line(
                transit(NavigationBusInfo(raw = "대기", arrivals = emptyList())),
            ),
        )
        assertNull(NavigationEventSpeech.line(transit(null)))
    }

    @Test
    fun `parser transit snapshot speaks the first bus`() {
        val event = NaverNotificationParser.parse(
            NaverNotificationParser.Snapshot(
                packageName = "com.nhn.android.nmap",
                notificationId = 301,
                channel = "302_PUBTRANS_POPUP",
                title = "일산동부경찰서(중) 도보 후 버스 승차",
                text = "11 (6분), 11 (13분)",
            ),
        )
        assertEquals("11번, 11번 버스가 6분 후 도착해요. 다음은 11번, 13분 후 도착입니다.", NavigationEventSpeech.line(event!!))
    }

    @Test
    fun `bus wait stages live in BusWaitCore not SpeechGate`() {
        val core = nomi.android.traffic.buswait.BusWaitCore()
        core.seed("11")
        assertNull(core.observe(listOf(NavigationBusArrival("11", "14분")), 1_000L)!!.speakStage)
        assertEquals(
            10,
            core.observe(listOf(NavigationBusArrival("11", "9분")), 1_000L + CONFIRM)!!.speakStage,
        )
        assertNull(core.observe(listOf(NavigationBusArrival("11", "8분")), 1_001L + CONFIRM)!!.speakStage)
        assertNull(core.observe(listOf(NavigationBusArrival("11", "6분")), 1_002L + CONFIRM)!!.speakStage)
        assertEquals(
            5,
            core.observe(listOf(NavigationBusArrival("11", "4분")), 1_000L + CONFIRM + GAP)!!.speakStage,
        )
        assertNull(
            core.observe(listOf(NavigationBusArrival("11", "3분")), 1_001L + CONFIRM + GAP)!!.speakStage,
        )
        assertEquals(
            2,
            core.observe(listOf(NavigationBusArrival("11", "2분")), 1_000L + CONFIRM + GAP * 2)!!.speakStage,
        )
        assertEquals(
            1,
            core.observe(listOf(NavigationBusArrival("11", "1분")), 1_001L + CONFIRM + GAP * 2)!!.speakStage,
        )
        assertNull(
            core.observe(listOf(NavigationBusArrival("11", "2분")), 1_002L + CONFIRM + GAP * 2)!!.speakStage,
        )
    }

    @Test
    fun `jump from 12 to 3 speaks 5 then soon skips 2 inside cooldown`() {
        val core = nomi.android.traffic.buswait.BusWaitCore()
        core.seed("11")
        assertEquals(5, core.observe(listOf(NavigationBusArrival("11", "3분")), 1_000L)!!.speakStage)
        assertNull(core.observe(listOf(NavigationBusArrival("11", "2분")), 2_000L)!!.speakStage)
        assertEquals(1, core.observe(listOf(NavigationBusArrival("11", "1분")), 3_000L)!!.speakStage)
    }

    @Test
    fun `minutes above 10 are not the 10 minute stage`() {
        val core = nomi.android.traffic.buswait.BusWaitCore()
        core.seed("11")
        assertNull(core.observe(listOf(NavigationBusArrival("11", "14분")), 1_000L)!!.speakStage)
        assertEquals(
            10,
            core.observe(listOf(NavigationBusArrival("11", "10분")), 1_000L + CONFIRM)!!.speakStage,
        )
    }

    @Test
    fun `naver bus core stage does not consume google bus stage`() {
        val core = nomi.android.traffic.buswait.BusWaitCore()
        core.seed("11")
        assertEquals(2, core.observe(listOf(NavigationBusArrival("11", "2분")))!!.speakStage)
        val gate = NavigationEventSpeechGate()
        assertTrue(
            gate.accept(
                NavigationEvent(
                    source = NavigationEventSource.GOOGLE,
                    type = NavigationEventType.TRANSIT,
                    notificationId = 0,
                    channel = GoogleMapsTransit.CHANNEL,
                    title = "140 2분",
                    action = "140 2분",
                    distanceMeters = null,
                    rawText = "140 2분",
                    busInfo = NavigationBusInfo(
                        raw = "140 (2분)",
                        arrivals = listOf(NavigationBusArrival("140", "2분")),
                    ),
                    timestampMillis = 0L,
                ),
            ),
        )
    }

    @Test
    fun `core does not repeat a stage after jitter back up`() {
        val core = nomi.android.traffic.buswait.BusWaitCore()
        core.seed("11")
        assertEquals(10, core.observe(listOf(NavigationBusArrival("11", "10분")), 1_000L)!!.speakStage)
        assertNull(core.observe(listOf(NavigationBusArrival("11", "9분")), 1_001L)!!.speakStage)
        assertNull(core.observe(listOf(NavigationBusArrival("11", "10분")), 1_002L)!!.speakStage)
        assertEquals(
            5,
            core.observe(listOf(NavigationBusArrival("11", "5분")), 1_000L + GAP)!!.speakStage,
        )
    }

    @Test
    fun `core speaks soon after 2 and does not repeat 2`() {
        val core = nomi.android.traffic.buswait.BusWaitCore()
        core.seed("11")
        assertEquals(10, core.observe(listOf(NavigationBusArrival("11", "10분")), 1_000L)!!.speakStage)
        assertEquals(
            5,
            core.observe(listOf(NavigationBusArrival("11", "5분")), 1_000L + GAP)!!.speakStage,
        )
        assertEquals(
            2,
            core.observe(listOf(NavigationBusArrival("11", "2분")), 1_000L + GAP * 2)!!.speakStage,
        )
        assertEquals(1, core.observe(listOf(NavigationBusArrival("11", "1분")), 1_000L + GAP * 2 + 1)!!.speakStage)
        assertNull(core.observe(listOf(NavigationBusArrival("11", "2분")), 1_000L + GAP * 2 + 2)!!.speakStage)
    }

    @Test
    fun `one minute speaks as soon and soon wording speaks once`() {
        assertEquals(
            "11번, 11번 버스, 곧 도착합니다.",
            NavigationEventSpeech.line(transitMinutes(1)),
        )
        assertEquals(
            "11번, 11번 버스, 곧 도착합니다.",
            NavigationEventSpeech.line(
                transit(
                    NavigationBusInfo(
                        raw = "11 (곧)",
                        arrivals = listOf(NavigationBusArrival("11", "곧")),
                    ),
                ),
            ),
        )
        val core = nomi.android.traffic.buswait.BusWaitCore()
        core.seed("11")
        assertEquals(1, core.observe(listOf(NavigationBusArrival("11", "1분")))!!.speakStage)
        assertNull(core.observe(listOf(NavigationBusArrival("11", "1분")))!!.speakStage)
        assertNull(core.observe(listOf(NavigationBusArrival("11", "곧")))!!.speakStage)
    }

    @Test
    fun `naver trip start does not burn wait stages`() {
        val gate = NavigationEventSpeechGate()
        val start = NavigationEvent(
            source = NavigationEventSource.NAVER,
            type = NavigationEventType.TRANSIT,
            notificationId = 0,
            channel = NaverMapsTransit.CHANNEL,
            title = "10",
            action = NaverMapsTransit.TRIP_START_ACTION,
            distanceMeters = 6,
            rawText = NaverMapsTransit.KIND_BUS,
            busInfo = NavigationBusInfo(
                raw = "81 10분 walk=6",
                arrivals = listOf(NavigationBusArrival("81", "10분")),
            ),
            timestampMillis = 0L,
        )
        assertTrue(gate.accept(start))
        val again = start.copy(
            busInfo = NavigationBusInfo(
                raw = "81 7분 walk=6",
                arrivals = listOf(NavigationBusArrival("81", "7분")),
            ),
        )
        assertFalse(gate.accept(again))
        gate.resetNaverTripStart()
        assertTrue(gate.accept(again))
        val core = nomi.android.traffic.buswait.BusWaitCore()
        core.seed("81")
        assertEquals(10, core.observe(listOf(NavigationBusArrival("81", "10분")), 1_000L)!!.speakStage)
        assertEquals(
            5,
            core.observe(listOf(NavigationBusArrival("81", "5분")), 1_000L + GAP)!!.speakStage,
        )
    }

    @Test
    fun `naver 곧 도착 wording speaks once`() {
        val event = transit(
            NavigationBusInfo(
                raw = "81 (곧 도착), 99 (곧 도착)",
                arrivals = listOf(
                    NavigationBusArrival("81", "곧 도착"),
                    NavigationBusArrival("99", "곧 도착"),
                ),
            ),
        )
        assertEquals(
            "81번, 81번 버스, 곧 도착합니다. 다음은 99번, 곧 도착합니다.",
            NavigationEventSpeech.line(event),
        )
        assertFalse(NavigationEventSpeechGate().accept(event))
        val core = nomi.android.traffic.buswait.BusWaitCore()
        core.seed("81")
        assertEquals(1, core.observe(listOf(NavigationBusArrival("81", "곧 도착")))!!.speakStage)
        assertNull(core.observe(listOf(NavigationBusArrival("81", "곧 도착")))!!.speakStage)
    }

    @Test
    fun `every wait cue names the remaining soonest next bus`() {
        val event = transit(
            NavigationBusInfo(
                raw = "98 3분, 89 5분, 66 9분, 83 11분",
                arrivals = listOf(
                    NavigationBusArrival("98", "3분", "여유", 2),
                    NavigationBusArrival("89", "5분", "여유", 2),
                    NavigationBusArrival("66", "9분", "여유", 4),
                    NavigationBusArrival("83", "11분", "여유", 6),
                ),
            ),
        )
        val expected =
            "98번, 98번 버스가 3분 후 도착해요. 버스 좌석은 여유입니다. 다음은 89번, 5분 후 도착입니다."
        assertEquals(expected, NavigationEventSpeech.line(event))
        // 관측 등급 신호는 확정 등급 발화의 게이트가 될 수 없다 (V2 부록 E).
        NaverNearBoardNotice.note("승차정류장 부근입니다.")
        assertEquals(expected, NavigationEventSpeech.line(event))
    }

    @Test
    fun `every subway wait cue names this train and the next`() {
        val event = transit(
            NavigationBusInfo(
                raw = "3호선 3분, 3호선 10분",
                arrivals = listOf(
                    NavigationBusArrival("3호선", "3분"),
                    NavigationBusArrival("3호선", "10분"),
                ),
            ),
        ).copy(rawText = NaverMapsTransit.KIND_SUBWAY)
        val expected = "3호선이 3분, 3분 후 도착합니다. 다음 열차는 10분 후 도착입니다."
        assertEquals(expected, NavigationEventSpeech.line(event))
        NaverNearBoardNotice.note("승차역 부근입니다.")
        assertEquals(expected, NavigationEventSpeech.line(event))
    }

    @Test
    fun `a near-board scrap cannot reword a Google subway wait`() {
        val event = transit(
            NavigationBusInfo(
                raw = "3호선 8분",
                arrivals = listOf(NavigationBusArrival("3호선", "8분")),
            ),
        ).copy(source = NavigationEventSource.GOOGLE, rawText = GoogleMapsTransit.KIND_SUBWAY)
        val expected = "3호선이 8분 후 출발해요."
        assertEquals(expected, NavigationEventSpeech.line(event))
        NaverNearBoardNotice.note("승차역 부근입니다.")
        assertEquals(expected, NavigationEventSpeech.line(event))
    }

    @Test
    fun `transfer subway brief names headsign next train and fast alight`() {
        val event = subwayWait(
            raw = "3호선 8분 | 마두역 방면 빠른 하차: 2-4\n오금행 (17:08), 오금행 (17:15)",
            arrivals = listOf(
                NavigationBusArrival("3호선", "8분"),
                NavigationBusArrival("3호선", "15분"),
            ),
        )
        assertEquals(
            "3호선 오금행 열차가 8분, 8분 후 도착합니다. 다음 열차는 15분 후 도착입니다. 빠른 하차는 2-4번입니다.",
            NavigationEventSpeech.line(event, includeFastAlight = true),
        )
    }

    @Test
    fun `stage subway wait keeps headsign and omits fast alight`() {
        val event = subwayWait(
            raw = "3호선 8분 | 마두역 방면 빠른 하차: 2-4\n오금행 (17:08), 오금행 (17:15)",
            arrivals = listOf(
                NavigationBusArrival("3호선", "8분"),
                NavigationBusArrival("3호선", "15분"),
            ),
        )
        assertEquals(
            "3호선 오금행 열차가 8분, 8분 후 도착합니다. 다음 열차는 15분 후 도착입니다.",
            NavigationEventSpeech.line(event, includeFastAlight = false),
        )
        assertEquals(
            "3호선 오금행 열차가 8분, 8분 후 도착합니다. 다음 열차는 15분 후 도착입니다.",
            NavigationEventSpeech.line(event),
        )
    }

    @Test
    fun `two-minute subway stage names this train only`() {
        val event = subwayWait(
            raw = "3호선 2분 | 마두역 방면 빠른 하차: 2-4\n오금행 (2분)",
            arrivals = listOf(NavigationBusArrival("3호선", "2분")),
        )
        assertEquals(
            "3호선 오금행 열차가 2분, 2분 후 도착합니다.",
            NavigationEventSpeech.line(event),
        )
        assertFalse(NavigationEventSpeech.line(event)!!.contains("다음 열차"))
        assertFalse(NavigationEventSpeech.line(event)!!.contains("빠른 하차"))
    }

    @Test
    fun `subway wait without a bound keeps the line subject`() {
        val event = subwayWait(
            raw = "3호선 8분 | 마두역 방면 빠른 하차: 2-4",
            arrivals = listOf(NavigationBusArrival("3호선", "8분")),
        )
        assertEquals(
            "3호선이 8분, 8분 후 도착합니다. 빠른 하차는 2-4번입니다.",
            NavigationEventSpeech.line(event, includeFastAlight = true),
        )
        assertEquals(
            "3호선이 8분, 8분 후 도착합니다.",
            NavigationEventSpeech.line(event),
        )
    }

    @Test
    fun `transfer subway brief omits fast alight when the board has none`() {
        val event = subwayWait(
            raw = "3호선 8분 | 오금행 (17:08), 오금행 (17:15)",
            arrivals = listOf(
                NavigationBusArrival("3호선", "8분"),
                NavigationBusArrival("3호선", "15분"),
            ),
        )
        val line = NavigationEventSpeech.line(event, includeFastAlight = true)!!
        assertEquals(
            "3호선 오금행 열차가 8분, 8분 후 도착합니다. 다음 열차는 15분 후 도착입니다.",
            line,
        )
        assertFalse(line.contains("빠른 하차"))
    }

    private fun subwayWait(
        raw: String,
        arrivals: List<NavigationBusArrival>,
    ) = NavigationEvent(
        source = NavigationEventSource.NAVER,
        type = NavigationEventType.TRANSIT,
        notificationId = 301,
        channel = "302_PUBTRANS_POPUP",
        title = "정발산역 3호선 열차 승차",
        action = "정발산역 3호선 열차 승차",
        distanceMeters = null,
        rawText = NaverMapsTransit.KIND_SUBWAY,
        busInfo = NavigationBusInfo(raw = raw, arrivals = arrivals),
        timestampMillis = 0L,
    )

    @Test
    fun `near-board subway exit repeats three minutes with cars 3-2`() {
        assertEquals(
            "3호선 오금행 열차가 3분, 3분 후 도착합니다. 빠른 하차는 3-2번입니다.",
            NavigationEventSpeech.line(nearBoardExit(minutes = 3, cars = "3-2")),
        )
    }

    @Test
    fun `near-board subway exit repeats five minutes with cars 2-4`() {
        assertEquals(
            "3호선 오금행 열차가 5분, 5분 후 도착합니다. 빠른 하차는 2-4번입니다.",
            NavigationEventSpeech.line(nearBoardExit(minutes = 5, cars = "2-4")),
        )
    }

    @Test
    fun `near-board subway soon wait wording is unchanged`() {
        val event = transit(
            NavigationBusInfo(
                raw = "3호선 곧",
                arrivals = listOf(NavigationBusArrival("3호선", "곧")),
            ),
        ).copy(rawText = NaverMapsTransit.KIND_SUBWAY)
        NaverNearBoardNotice.note("승차역 부근입니다.")
        assertEquals("3호선, 곧 출발합니다.", NavigationEventSpeech.line(event))
    }

    @Test
    fun `focused wait event still carries the next bus`() {
        val tracker = NaverBusWaitTracker()
        tracker.pinBusLine("98")
        val event = NavigationEvent(
            source = NavigationEventSource.NAVER,
            type = NavigationEventType.TRANSIT,
            notificationId = 0,
            channel = NaverMapsTransit.BUS_CHANNEL,
            title = "",
            action = "",
            distanceMeters = null,
            rawText = "",
            busInfo = NavigationBusInfo(
                raw = "98 3분, 89 5분",
                arrivals = listOf(
                    NavigationBusArrival("98", "3분", "여유", 2),
                    NavigationBusArrival("89", "5분", "여유", 2),
                ),
            ),
            timestampMillis = 0L,
        )
        val focused = NaverBusWaitSpeech.focusedEvent(tracker, event) {}!!
        assertEquals("98", focused.busInfo!!.arrivals[0].line)
        assertEquals("89", focused.busInfo!!.arrivals[1].line)
        assertEquals(
            "98번, 98번 버스가 3분 후 도착해요. 버스 좌석은 여유입니다. 다음은 89번, 5분 후 도착입니다.",
            NavigationEventSpeech.line(focused),
        )
    }

    @Test
    fun `occupancy is spoken after the arrival cue`() {
        assertEquals(
            "81번, 81번 버스가 10분 후 도착해요. 버스 좌석은 여유입니다.",
            NavigationEventSpeech.line(
                transit(
                    NavigationBusInfo(
                        raw = "81 (10분)",
                        arrivals = listOf(
                            NavigationBusArrival("81", "10분", "여유"),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `naver 하차 action is not a google alight cue`() {
        val event = NavigationEvent(
            source = NavigationEventSource.NAVER,
            type = NavigationEventType.TRANSIT,
            notificationId = 301,
            channel = "302_PUBTRANS_POPUP",
            title = "롯데백화점 하차",
            action = "하차",
            distanceMeters = null,
            rawText = "",
            busInfo = null,
            timestampMillis = 0L,
            landmark = "롯데백화점",
        )
        assertNull(NavigationEventSpeech.line(event))
        assertFalse(NavigationEventSpeechGate().accept(event))
    }

    private fun walk(
        action: String,
        meters: Int?,
        landmark: String? = null,
        channel: String = "350_WALK_NAVIGATION",
        notificationId: Int = 501,
    ) = NavigationEvent(
        source = NavigationEventSource.NAVER,
        type = NavigationEventType.WALK,
        notificationId = notificationId,
        channel = channel,
        title = action,
        action = action,
        distanceMeters = meters,
        rawText = meters?.let { "${it}m 남음" }.orEmpty(),
        busInfo = null,
        timestampMillis = 0L,
        landmark = landmark,
    )

    private fun transitMinutes(minutes: Int) = transit(
        NavigationBusInfo(
            raw = "11 (${minutes}분)",
            arrivals = listOf(NavigationBusArrival("11", "${minutes}분")),
        ),
    )

    private fun transit(busInfo: NavigationBusInfo?) = NavigationEvent(
        source = NavigationEventSource.NAVER,
        type = NavigationEventType.TRANSIT,
        notificationId = 301,
        channel = "302_PUBTRANS_POPUP",
        title = "버스 승차",
        action = "버스 승차",
        distanceMeters = null,
        rawText = busInfo?.raw.orEmpty(),
        busInfo = busInfo,
        timestampMillis = 0L,
    )

    private fun nearBoardExit(minutes: Int, cars: String) = NavigationEvent(
        source = NavigationEventSource.NAVER,
        type = NavigationEventType.TRANSIT,
        notificationId = 0,
        channel = NaverMapsTransit.CHANNEL,
        title = "3호선 정발산역 승차",
        action = NaverMapsTransit.QUICK_EXIT,
        distanceMeters = null,
        rawText = cars,
        busInfo = NavigationBusInfo(
            raw = "3호선 ${minutes}분 | 오금행 (11:16)",
            arrivals = listOf(NavigationBusArrival("3호선", "${minutes}분")),
        ),
        timestampMillis = 0L,
        landmark = "마두역 방면",
    )

    companion object {
        private const val GAP = nomi.android.traffic.buswait.BusWaitCore.STAGE_COOLDOWN_MS
        private const val CONFIRM = nomi.android.traffic.buswait.BusWaitCore.CONFIRM_MS
    }
}
