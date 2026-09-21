package nomi.android.traffic

import nomi.product.nav.NavigationEventSource
import nomi.product.nav.NavigationEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NaverNotificationParserTest {

    @Test
    fun `302 subway board keeps this train and the next one`() {
        NaverNearBoardNotice.reset()
        val now = System.currentTimeMillis()
        val event = NaverNotificationParser.parse(
            NaverNotificationParser.Snapshot(
                packageName = "com.nhn.android.nmap",
                notificationId = 301,
                channel = "302_PUBTRANS_POPUP",
                title = "정발산역 3호선까지 걷기",
                text = "오금행 (${clockAfter(3)}), 오금행 (${clockAfter(21)}), 오금행 (${clockAfter(33)})",
                timestampMillis = now,
            ),
        )!!
        val arrivals = event.busInfo!!.arrivals
        assertEquals(2, arrivals.size)
        assertEquals("3분", arrivals[0].eta)
        assertEquals("21분", arrivals[1].eta)
        assertEquals(
            "3호선 오금행 열차가 3분 후 도착합니다. 다음 열차는 21분 후 도착입니다.",
            NavigationEventSpeech.line(event),
        )
        assertEquals(
            "다음 열차는 21분 후 도착입니다.",
            NavigationEventSpeech.naverNextTrainLine(event),
        )
    }

    @Test
    fun `302 letter-suffix bus line is parsed and can brief`() {
        val event = NaverNotificationParser.parse(
            NaverNotificationParser.Snapshot(
                packageName = "com.nhn.android.nmap",
                notificationId = 301,
                channel = "302_PUBTRANS_POPUP",
                title = "대우.삼성오피스텔까지 걷기",
                text = "88B (2분), 88B (9분)",
                timestampMillis = System.currentTimeMillis(),
            ),
        )!!
        assertEquals("88B", event.busInfo!!.arrivals[0].line)
        assertEquals("2분", event.busInfo!!.arrivals[0].eta)
        assertEquals("9분", event.busInfo!!.arrivals[1].eta)
        val briefing = NaverTripStartParser.asTripStart(event)!!
        assertEquals(
            "88B번, 88B번 버스가 2분 후 도착합니다. 다음은 88B번, 9분 후 도착입니다.",
            NavigationEventSpeech.line(briefing),
        )
    }

    @Test
    fun `302 bus board next is remaining soonest after first`() {
        val event = NaverNotificationParser.parse(
            NaverNotificationParser.Snapshot(
                packageName = "com.nhn.android.nmap",
                notificationId = 301,
                channel = "302_PUBTRANS_POPUP",
                title = "일산동부경찰서(중)까지 걷기",
                text = "150 (3분), 67 (5분), 150 (18분)",
                timestampMillis = System.currentTimeMillis(),
            ),
        )!!
        assertEquals(
            "다음은 67번, 5분 후 도착입니다.",
            NavigationEventSpeech.naverNextTrainLine(event),
        )
    }

    @Test
    fun `single departure has no next train line`() {
        val event = NaverNotificationParser.parse(
            NaverNotificationParser.Snapshot(
                packageName = "com.nhn.android.nmap",
                notificationId = 301,
                channel = "302_PUBTRANS_POPUP",
                title = "정발산역 3호선까지 걷기",
                text = "오금행 (${clockAfter(6)})",
                timestampMillis = System.currentTimeMillis(),
            ),
        )!!
        assertEquals(1, event.busInfo!!.arrivals.size)
        assertNull(NavigationEventSpeech.naverNextTrainLine(event))
    }

    private fun clockAfter(minutes: Int): String {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.MINUTE, minutes)
        return "%02d:%02d".format(
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
        )
    }

    private fun wallClock(hour: Int, minute: Int, second: Int): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
        cal.set(java.util.Calendar.MINUTE, minute)
        cal.set(java.util.Calendar.SECOND, second)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun liveBoard(departure: String) = NaverNotificationParser.parse(
        NaverNotificationParser.Snapshot(
            packageName = "com.nhn.android.nmap",
            notificationId = 301,
            channel = "302_PUBTRANS_POPUP",
            title = "정발산역 3호선 열차 승차",
            text = "마두역 방면 빠른 하차: 2-4",
            bigText = "마두역 방면 빠른 하차: 2-4\n$departure",
            timestampMillis = wallClock(17, 5, 38),
        ),
    )!!

    private fun subwayEtaMinutes(event: nomi.product.nav.NavigationEvent): Int? {
        val eta = event.busInfo?.arrivals?.firstOrNull()?.eta ?: return null
        if (TransitSoonEta.matches(eta)) return 1
        return Regex("""(\d+)\s*분""").find(eta)?.groupValues?.get(1)?.toIntOrNull()
    }

    @Test
    fun `boarding stop comes from the 302 primary line`() {
        assertEquals(
            "일산동부경찰서(중)",
            NaverNotificationParser.boardingStop("일산동부경찰서(중) 도보 후 버스 승차"),
        )
        assertEquals(
            "일산동부경찰서(중)",
            NaverNotificationParser.boardingStop("일산동부경찰서(중)까지 걷기"),
        )
        assertNull(NaverNotificationParser.boardingStop("길안내를 시작합니다"))
        assertNull(NaverNotificationParser.boardingStop(null))
    }

    @Test
    fun `walk 350 maps title text to action and meters`() {
        val event = NaverNotificationParser.parse(
            NaverNotificationParser.Snapshot(
                packageName = "com.nhn.android.nmap",
                notificationId = 501,
                channel = "350_WALK_NAVIGATION",
                title = "고두리김치생삼겹살 방면으로 횡단보도 건너기",
                text = "26m 남음",
                timestampMillis = 1L,
            ),
        )
        assertNotNull(event)
        assertEquals(NavigationEventSource.NAVER, event!!.source)
        assertEquals(NavigationEventType.WALK, event.type)
        assertEquals(501, event.notificationId)
        assertEquals("350_WALK_NAVIGATION", event.channel)
        assertEquals("고두리김치생삼겹살 방면으로 횡단보도 건너기", event.title)
        assertTrue(event.action.contains("횡단보도 건너기"))
        assertEquals("고두리김치생삼겹살", event.landmark)
        assertEquals(26, event.distanceMeters)
        assertEquals("26m 남음", event.rawText)
        assertNull(event.busInfo)
    }

    @Test
    fun `walk title with 방면으로 splits landmark and action`() {
        val event = NaverNotificationParser.parse(
            NaverNotificationParser.Snapshot(
                packageName = "com.nhn.android.nmap",
                notificationId = 501,
                channel = "350_WALK_NAVIGATION",
                title = "탑 메디컬 트레이닝&pt 방면으로 횡단보도 건너기",
                text = "47m 남음",
                action = "횡단보도 건너기",
                chip = "47m",
            ),
        )
        assertEquals("탑 메디컬 트레이닝&pt", event!!.landmark)
        assertEquals("횡단보도 건너기", event.action)
        assertEquals(47, event.distanceMeters)
    }

    @Test
    fun `walk title without 방면으로 does not invent a landmark`() {
        val event = NaverNotificationParser.parse(
            NaverNotificationParser.Snapshot(
                packageName = "com.nhn.android.nmap",
                notificationId = 501,
                channel = "350_WALK_NAVIGATION",
                title = "백억커피 라페스타점에서 오른쪽 방향",
                text = "45m 남음",
                action = "오른쪽 방향",
            ),
        )
        assertNull(event!!.landmark)
        assertEquals("오른쪽 방향", event.action)
        assertEquals(45, event.distanceMeters)
    }

    @Test
    fun `walk 350 updates distance meters`() {
        val event = NaverNotificationParser.parse(
            NaverNotificationParser.Snapshot(
                packageName = "com.nhn.android.nmap",
                notificationId = 501,
                channel = "350_WALK_NAVIGATION",
                title = "고두리김치생삼겹살 방면으로 횡단보도 건너기",
                text = "23m 남음",
            ),
        )
        assertEquals(23, event!!.distanceMeters)
        assertEquals(NavigationEventType.WALK, event.type)
    }

    @Test
    fun `transit 302 keeps bus number and arrival times`() {
        val event = NaverNotificationParser.parse(
            NaverNotificationParser.Snapshot(
                packageName = "com.nhn.android.nmap",
                notificationId = 301,
                channel = "302_PUBTRANS_POPUP",
                title = "일산동부경찰서(중) 도보 후 버스 승차",
                text = "11 (7분), 11 (13분)",
            ),
        )
        assertNotNull(event)
        assertEquals(NavigationEventType.TRANSIT, event!!.type)
        assertEquals("일산동부경찰서(중) 도보 후 버스 승차", event.title)
        val bus = event.busInfo
        assertNotNull(bus)
        assertEquals("11 (7분), 11 (13분)", bus!!.raw)
        assertEquals(2, bus.arrivals.size)
        assertEquals("11", bus.arrivals[0].line)
        assertEquals("7분", bus.arrivals[0].eta)
        assertEquals("11", bus.arrivals[1].line)
        assertEquals("13분", bus.arrivals[1].eta)
    }

    @Test
    fun `transit 302 곧 도착 speaks soon for the first bus`() {
        val event = NaverNotificationParser.parse(
            NaverNotificationParser.Snapshot(
                packageName = "com.nhn.android.nmap",
                notificationId = 301,
                channel = "302_PUBTRANS_POPUP",
                title = "라페스타.먹자골목 승차",
                text = "81 (곧 도착), 99 (곧 도착), 81 (18분), 99 (25분)",
            ),
        )
        assertEquals("곧 도착", event!!.busInfo!!.arrivals[0].eta)
        assertEquals(
            "81번, 81번 버스, 곧 도착합니다. 다음은 99번, 곧 도착합니다.",
            NavigationEventSpeech.line(event),
        )
        assertFalse(NavigationEventSpeechGate().accept(event))
        val core = nomi.android.traffic.buswait.BusWaitCore()
        core.seed("81")
        assertEquals(1, core.observe(event.busInfo!!.arrivals)!!.speakStage)
    }

    @Test
    fun `transit 302 subway clocks become wait stages`() {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.MINUTE, 5)
        val clock = "%02d:%02d".format(
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
        )
        val now = System.currentTimeMillis()
        val event = NaverNotificationParser.parse(
            NaverNotificationParser.Snapshot(
                packageName = "com.nhn.android.nmap",
                notificationId = 301,
                channel = "302_PUBTRANS_POPUP",
                title = "정발산역 3호선까지 걷기",
                text = "오금행 ($clock), 오금행 (23:59)",
                timestampMillis = now,
            ),
        )
        assertNotNull(event)
        assertEquals(NaverMapsTransit.KIND_SUBWAY, event!!.rawText)
        assertEquals("3호선", event.busInfo!!.arrivals[0].line)
        assertEquals("5분", event.busInfo!!.arrivals[0].eta)
        assertEquals(
            "3호선 오금행 열차가 5분 후 도착합니다.",
            NavigationEventSpeech.line(event),
        )
        val gate = NavigationEventSpeechGate()
        assertTrue(gate.accept(event))
        assertFalse(gate.accept(event))
        // Bus wait stages are independent (BusWaitCore), not this gate.
        val core = nomi.android.traffic.buswait.BusWaitCore()
        core.seed("81")
        assertEquals(5, core.observe(listOf(nomi.product.nav.NavigationBusArrival("81", "5분")))!!.speakStage)
    }

    @Test
    fun `302 정발산역 3호선 오금행 clocks still parse after a11y slice`() {
        val event = NaverNotificationParser.parse(
            NaverNotificationParser.Snapshot(
                packageName = "com.nhn.android.nmap",
                notificationId = 301,
                channel = "302_PUBTRANS_POPUP",
                title = "정발산역 3호선까지 걷기",
                text = "오금행 (15:49), 오금행 (16:01), 수서행 (16:13)",
                timestampMillis = wallClock(15, 40, 50),
            ),
        )!!
        assertEquals(NaverMapsTransit.KIND_SUBWAY, event.rawText)
        assertEquals("3호선", event.busInfo!!.arrivals[0].line)
        assertEquals("9분", event.busInfo!!.arrivals[0].eta)
        assertEquals("21분", event.busInfo!!.arrivals[1].eta)
    }

    @Test
    fun `transit 302 subway one-minute clock speaks soon`() {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.MINUTE, 1)
        val clock = "%02d:%02d".format(
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
        )
        val event = NaverNotificationParser.parse(
            NaverNotificationParser.Snapshot(
                packageName = "com.nhn.android.nmap",
                notificationId = 301,
                channel = "302_PUBTRANS_POPUP",
                title = "정발산역 3호선까지 걷기",
                text = "오금행 ($clock)",
                timestampMillis = System.currentTimeMillis(),
            ),
        )
        assertEquals("곧", event!!.busInfo!!.arrivals[0].eta)
        assertEquals("3호선, 곧 도착합니다.", NavigationEventSpeech.line(event))
    }

    @Test
    fun `302 board text car cue still reads clocks from bigText`() {
        val event = NaverNotificationParser.parse(
            NaverNotificationParser.Snapshot(
                packageName = "com.nhn.android.nmap",
                notificationId = 301,
                channel = "302_PUBTRANS_POPUP",
                title = "정발산역 3호선 열차 승차",
                text = "마두역 방면 빠른 하차: 2-4",
                bigText = "마두역 방면 빠른 하차: 2-4\n오금행 (11:16)\n오금행 (11:23)\n오금행 (11:35)",
                timestampMillis = wallClock(11, 10, 18),
            ),
        )!!
        val arrivals = event.busInfo!!.arrivals
        assertEquals(NaverMapsTransit.KIND_SUBWAY, event.rawText)
        assertEquals("3호선", arrivals[0].line)
        assertEquals(2, arrivals.size)
        assertEquals("6분", arrivals[0].eta)
        assertEquals("13분", arrivals[1].eta)
    }

    @Test
    fun `302 subway clocks in text are unchanged when bigText also has clocks`() {
        val now = System.currentTimeMillis()
        val event = NaverNotificationParser.parse(
            NaverNotificationParser.Snapshot(
                packageName = "com.nhn.android.nmap",
                notificationId = 301,
                channel = "302_PUBTRANS_POPUP",
                title = "정발산역 3호선까지 걷기",
                text = "오금행 (${clockAfter(3)}), 오금행 (${clockAfter(21)})",
                bigText = "오금행 (${clockAfter(8)}), 오금행 (${clockAfter(30)})",
                timestampMillis = now,
            ),
        )!!
        val arrivals = event.busInfo!!.arrivals
        assertEquals(2, arrivals.size)
        assertEquals("3분", arrivals[0].eta)
        assertEquals("21분", arrivals[1].eta)
    }

    @Test
    fun `live 302 relative two minutes is subway stage 2`() {
        val event = liveBoard("오금행 (2분)")
        assertEquals(NaverMapsTransit.KIND_SUBWAY, event.rawText)
        assertEquals("2분", event.busInfo!!.arrivals[0].eta)
        assertEquals(2, subwayEtaMinutes(event))
        assertTrue(NavigationEventSpeechGate().accept(event))
    }

    @Test
    fun `live 302 relative one minute is subway soon`() {
        val event = liveBoard("오금행 (1분)")
        assertEquals(NaverMapsTransit.KIND_SUBWAY, event.rawText)
        assertEquals("곧", event.busInfo!!.arrivals[0].eta)
        assertEquals(1, subwayEtaMinutes(event))
        assertTrue(NavigationEventSpeechGate().accept(event))
    }

    @Test
    fun `live 302 soon-arriving is subway soon`() {
        val event = liveBoard("오금행 (곧 도착)")
        assertEquals(NaverMapsTransit.KIND_SUBWAY, event.rawText)
        assertEquals("곧", event.busInfo!!.arrivals[0].eta)
        assertEquals(1, subwayEtaMinutes(event))
        assertEquals("3호선, 곧 도착합니다.", NavigationEventSpeech.line(event))
        assertTrue(NavigationEventSpeechGate().accept(event))
    }

    @Test
    fun `live 302 clock triple still uses absolute clocks`() {
        val event = NaverNotificationParser.parse(
            NaverNotificationParser.Snapshot(
                packageName = "com.nhn.android.nmap",
                notificationId = 301,
                channel = "302_PUBTRANS_POPUP",
                title = "정발산역 3호선 열차 승차",
                text = "마두역 방면 빠른 하차: 2-4",
                bigText = "마두역 방면 빠른 하차: 2-4\n오금행 (17:08), 오금행 (17:15), 오금행 (17:20)",
                timestampMillis = wallClock(17, 4, 9),
            ),
        )!!
        val arrivals = event.busInfo!!.arrivals
        assertEquals(NaverMapsTransit.KIND_SUBWAY, event.rawText)
        assertEquals(2, arrivals.size)
        assertEquals("4분", arrivals[0].eta)
        assertEquals("11분", arrivals[1].eta)
        assertEquals(listOf("17:08", "17:15", "17:20"), event.busInfo!!.clocks)
        assertFalse(event.busInfo!!.arrived)
    }

    @Test
    fun `live 302 clock list preserves original heads`() {
        val event = NaverNotificationParser.parse(
            NaverNotificationParser.Snapshot(
                packageName = "com.nhn.android.nmap",
                notificationId = 301,
                channel = "302_PUBTRANS_POPUP",
                title = "정발산역 3호선 열차 승차",
                text = "오금행 (10:01), (10:07), (10:14)",
                timestampMillis = wallClock(9, 50, 0),
            ),
        )!!
        assertEquals(listOf("10:01", "10:07", "10:14"), event.busInfo!!.clocks)
        assertFalse(event.busInfo!!.arrived)
        assertEquals("11분", event.busInfo!!.arrivals[0].eta)
        assertEquals("17분", event.busInfo!!.arrivals[1].eta)
    }

    @Test
    fun `live 302 arrived is arrived not soon`() {
        val event = liveBoard("오금행 (도착)")
        assertEquals(NaverMapsTransit.KIND_SUBWAY, event.rawText)
        assertTrue(event.busInfo!!.arrived)
        assertTrue(event.busInfo!!.clocks.isEmpty())
        assertEquals("도착", event.busInfo!!.arrivals[0].eta)
    }

    @Test
    fun `live 302 soon-arriving is not arrived`() {
        val event = liveBoard("오금행 (곧 도착)")
        assertFalse(event.busInfo!!.arrived)
        assertTrue(event.busInfo!!.clocks.isEmpty())
        assertEquals("곧", event.busInfo!!.arrivals[0].eta)
    }

    @Test
    fun `live 302 bus relative minutes stay a bus wait`() {
        val event = NaverNotificationParser.parse(
            NaverNotificationParser.Snapshot(
                packageName = "com.nhn.android.nmap",
                notificationId = 301,
                channel = "302_PUBTRANS_POPUP",
                title = "라페스타.먹자골목까지 걷기",
                text = "81 (2분), 99 (3분), 81 (19분)",
            ),
        )!!
        assertEquals("81 (2분), 99 (3분), 81 (19분)", event.rawText)
        assertEquals("81", event.busInfo!!.arrivals[0].line)
        assertEquals("2분", event.busInfo!!.arrivals[0].eta)
        assertEquals("99", event.busInfo!!.arrivals[1].line)
    }

    @Test
    fun `unknown channel is not converted`() {
        val event = NaverNotificationParser.parse(
            NaverNotificationParser.Snapshot(
                packageName = "com.nhn.android.nmap",
                notificationId = 1,
                channel = "400_NAVIGATION",
                title = "anything",
                text = "26m 남음",
            ),
        )
        assertNull(event)
    }
}
