package nomi.android.traffic

import nomi.product.nav.NavigationBusArrival
import nomi.product.nav.NavigationBusInfo
import nomi.product.nav.NavigationEvent
import nomi.product.nav.NavigationEventSource
import nomi.product.nav.NavigationEventType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NaverTransferSubwayBriefTest {

    private lateinit var gate: NavigationEventSpeechGate

    @Before
    fun setUp() {
        NaverNearBoardNotice.reset()
        gate = NavigationEventSpeechGate()
    }

    @After
    fun tearDown() {
        NaverNearBoardNotice.reset()
    }

    @Test
    fun `1 하차 후 환승 arms pending`() {
        assertFalse(gate.isTransferSubwayBriefPending())
        assertTrue(
            gate.noteAlightThenTransfer(
                title = "이번 정류장에서 하차 후 환승",
                action = "이번 정류장에서 하차 후 환승",
            ),
        )
        assertTrue(gate.isTransferSubwayBriefPending())
    }

    @Test
    fun `2 plain alight does not arm pending`() {
        assertFalse(
            gate.noteAlightThenTransfer(
                title = "이번 정류장에서 하차",
                action = "이번 정류장에서 하차",
            ),
        )
        assertFalse(gate.isTransferSubwayBriefPending())
        assertFalse(
            NavigationEventSpeechGate.isAlightThenTransfer("이번 정류장에서 하차"),
        )
        assertTrue(
            NavigationEventSpeechGate.isAlightThenTransfer("이번 정류장에서 하차 후 환승"),
        )
        assertTrue(
            NavigationEventSpeechGate.isAlightThenTransfer("이번 역(부평구청역)에서 하차 후 환승"),
        )
        assertFalse(
            NavigationEventSpeechGate.isAlightImminent("하차까지 2개 정류장"),
        )
        assertFalse(
            gate.noteAlightThenTransfer("하차까지 2개 정류장", null),
        )
        assertFalse(gate.isTransferSubwayBriefPending())
    }

    @Test
    fun `2b last stop before alight arms pending`() {
        assertTrue(NavigationEventSpeechGate.isAlightImminent("하차까지 1개 정류장"))
        assertTrue(NavigationEventSpeechGate.isAlightImminent("하차까지 1개 역"))
        assertTrue(
            gate.noteAlightThenTransfer(
                title = "하차까지 1개 정류장",
                action = "하차까지 1개 정류장",
            ),
        )
        assertTrue(gate.isTransferSubwayBriefPending())
        val first = subwayMinutes(current = 11, next = 18)
        assertTrue(gate.acceptTransferSubwayBrief(first))
        assertFalse(gate.isTransferSubwayBriefPending())
    }

    @Test
    fun `3 first subway ETA after pending is the transfer brief`() {
        gate.noteAlightThenTransfer("이번 정류장에서 하차 후 환승", null)
        val first = parseTodayBoard()
        assertEquals(NaverMapsTransit.KIND_SUBWAY, first.rawText)
        assertTrue(first.busInfo!!.arrivals.isNotEmpty())
        assertEquals("3호선, 곧 도착합니다.", NavigationEventSpeech.line(first))
        assertTrue(gate.acceptTransferSubwayBrief(first))
        assertFalse(gate.isTransferSubwayBriefPending())
    }

    @Test
    fun `4 pending stays silent without subway ETA`() {
        gate.noteAlightThenTransfer("이번 정류장에서 하차 후 환승", null)
        val transfer = parse(
            title = "이번 정류장에서 하차 후 환승",
            text = "롯데백화점",
        )
        assertNotEquals(NaverMapsTransit.KIND_SUBWAY, transfer.rawText)
        assertTrue(transfer.busInfo?.arrivals.isNullOrEmpty())
        assertFalse(gate.acceptTransferSubwayBrief(transfer))
        assertTrue(gate.isTransferSubwayBriefPending())

        val noEta = parse(
            title = "정발산역 3호선 열차 승차",
            text = "마두역 방면 빠른 하차: 2-4",
        )
        assertTrue(noEta.busInfo?.arrivals.isNullOrEmpty())
        assertFalse(gate.acceptTransferSubwayBrief(noEta))
        assertTrue(gate.isTransferSubwayBriefPending())
    }

    @Test
    fun `5 same subway ETA after first brief does not transfer-brief again`() {
        gate.noteAlightThenTransfer("이번 정류장에서 하차 후 환승", null)
        val first = parseTodayBoard()
        assertTrue(gate.acceptTransferSubwayBrief(first))
        assertFalse(gate.acceptTransferSubwayBrief(first))
        assertFalse(gate.acceptNaverSubwayWalkBrief(first))
        assertFalse(gate.accept(first))
        assertFalse(gate.isTransferSubwayBriefPending())
    }

    @Test
    fun `6 current and next train both appear in the sentence`() {
        gate.noteAlightThenTransfer("이번 정류장에서 하차 후 환승", null)
        val both = subwayMinutes(current = 5, next = 14)
        assertEquals(
            "3호선이 5분 후 도착합니다. 다음 열차는 14분 후 도착입니다.",
            NavigationEventSpeech.line(both),
        )
        assertTrue(gate.acceptTransferSubwayBrief(both))
    }

    @Test
    fun `7 one train does not invent a next train`() {
        gate.noteAlightThenTransfer("이번 정류장에서 하차 후 환승", null)
        val only = parseTodayBoard()
        assertEquals(1, only.busInfo!!.arrivals.size)
        val line = NavigationEventSpeech.line(only)!!
        assertEquals("3호선, 곧 도착합니다.", line)
        assertFalse(line.contains("다음 열차"))
        assertTrue(gate.acceptTransferSubwayBrief(only))
    }

    @Test
    fun `8 two and soon stages continue after the transfer brief`() {
        gate.noteAlightThenTransfer("이번 정류장에서 하차 후 환승", null)
        val first = subwayMinutes(current = 5, next = 14)
        assertTrue(gate.acceptTransferSubwayBrief(first))
        assertFalse(gate.accept(first))
        assertFalse(gate.acceptNaverSubwayWalkBrief(first))

        val two = subwayMinutes(current = 2, next = 11)
        assertFalse(gate.acceptTransferSubwayBrief(two))
        assertFalse(gate.acceptNaverSubwayWalkBrief(two))
        assertTrue(gate.accept(two))

        val soon = subwayMinutes(current = 1, next = 10)
        assertFalse(gate.acceptTransferSubwayBrief(soon))
        assertFalse(gate.acceptNaverSubwayWalkBrief(soon))
        assertTrue(gate.accept(soon))
        assertFalse(gate.accept(soon))
    }

    /**
     * 8-1: an experiment must be reversible. With the pending bit never armed —
     * the experiment switched off — the policy path still briefs the board once
     * and the ladder still walks down.
     */
    @Test
    fun `9 with the experiment off the policy path still completes`() {
        val first = subwayMinutes(current = 5, next = 14)
        assertFalse(gate.isTransferSubwayBriefPending())
        assertFalse(gate.acceptTransferSubwayBrief(first))
        assertTrue(gate.acceptNaverSubwayWalkBrief(first))
        assertFalse(gate.accept(first))

        val two = subwayMinutes(current = 2, next = 11)
        assertTrue(gate.accept(two))
        val soon = subwayMinutes(current = 1, next = 10)
        assertTrue(gate.accept(soon))
    }

    /** 제7원칙: the ladder's own reset must not clear someone else's state. */
    @Test
    fun `10 resetting the ladder alone leaves the experiment bit`() {
        gate.noteAlightThenTransfer("이번 정류장에서 하차 후 환승", null)
        gate.resetNaverSubwayStages()
        assertTrue(gate.isTransferSubwayBriefPending())
        gate.resetNaverSubwayBoardBriefs()
        assertTrue(gate.isTransferSubwayBriefPending())
        gate.resetTransferSubwayBrief()
        assertFalse(gate.isTransferSubwayBriefPending())
    }

    /** A new guidance clears every cue, including the experiment. */
    @Test
    fun `11 a new guidance clears the experiment with the rest`() {
        gate.noteAlightThenTransfer("이번 정류장에서 하차 후 환승", null)
        val first = subwayMinutes(current = 5, next = 14)
        assertTrue(gate.acceptTransferSubwayBrief(first))
        assertFalse(gate.acceptNaverSubwayWalkBrief(first))

        gate.resetNaverJourneyCues()
        assertFalse(gate.isTransferSubwayBriefPending())
        assertTrue(gate.acceptNaverSubwayWalkBrief(first))
    }

    private fun parseTodayBoard() = parse(
        title = "정발산역 3호선 열차 승차",
        text = "마두역 방면 빠른 하차: 2-4",
        bigText = "삼송행 (1분)",
    )

    private fun subwayMinutes(current: Int, next: Int) = NavigationEvent(
        source = NavigationEventSource.NAVER,
        type = NavigationEventType.TRANSIT,
        notificationId = 301,
        channel = "302_PUBTRANS_POPUP",
        title = "정발산역 3호선 열차 승차",
        action = "정발산역 3호선 열차 승차",
        distanceMeters = null,
        rawText = NaverMapsTransit.KIND_SUBWAY,
        busInfo = NavigationBusInfo(
            raw = "3호선 ${current}분 | 삼송행",
            arrivals = listOf(
                NavigationBusArrival("3호선", "${current}분"),
                NavigationBusArrival("3호선", "${next}분"),
            ),
        ),
        timestampMillis = 0L,
    )

    private fun parse(
        title: String,
        text: String,
        bigText: String? = null,
    ) = NaverNotificationParser.parse(
        NaverNotificationParser.Snapshot(
            packageName = "com.nhn.android.nmap",
            notificationId = 301,
            channel = "302_PUBTRANS_POPUP",
            title = title,
            text = text,
            bigText = bigText,
            timestampMillis = System.currentTimeMillis(),
        ),
    )!!
}
