package nomi.android.traffic.api.briefing

import nomi.android.traffic.NaverTripStartSession
import nomi.android.traffic.observe.ObservationSource
import nomi.android.traffic.observe.TransitKind
import nomi.android.traffic.observe.TransitReading
import nomi.android.traffic.observe.TransitRow
import nomi.android.traffic.scope.GuidanceKind
import nomi.android.traffic.scope.GuidanceSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class StartBriefingTest {

    @Before
    fun setUp() {
        StartBriefing.resetForTest()
        StartBriefingShadowDebug.resetForTest()
        NaverTripStartSession.reset()
    }

    @After
    fun tearDown() {
        StartBriefing.resetForTest()
        NaverTripStartSession.reset()
    }

    @Test
    fun `inactive scope is silent`() {
        val out = StartBriefing.consider(
            scope(active = false, line = "88B"),
            reading("88B", "곧 도착"),
        )
        assertNull(out.request)
        assertEquals(StartBriefingSilence.INACTIVE, out.silence)
    }

    @Test
    fun `active without line waits`() {
        val out = StartBriefing.consider(
            scope(line = null),
            reading("88B", "곧 도착"),
        )
        assertNull(out.request)
        assertEquals(StartBriefingSilence.NO_LINE, out.silence)
    }

    @Test
    fun `88B soon speaks once with existing letters`() {
        val scope = scope(line = "88B")
        val first = StartBriefing.consider(scope, reading("88B", "곧 도착"))
        assertEquals(
            "88B번, 88B번 버스, 곧 도착합니다.",
            first.request!!.text,
        )
        assertNull(first.silence)
        val again = StartBriefing.consider(scope, reading("88B", "곧 도착"))
        assertNull(again.request)
        assertEquals(StartBriefingSilence.ALREADY, again.silence)
    }

    @Test
    fun `new generation can speak again`() {
        StartBriefing.consider(scope(generation = 1, line = "88B"), reading("88B", "8분"))
        val next = StartBriefing.consider(
            scope(generation = 2, line = "88B"),
            reading("88B", "곧 도착"),
        )
        assertEquals(
            "88B번, 88B번 버스, 곧 도착합니다.",
            next.request!!.text,
        )
    }

    @Test
    fun `alt 88A is ignored when scope is 88B`() {
        val out = StartBriefing.consider(
            scope(line = "88B"),
            reading(
                TransitRow("88A", "곧 도착"),
                TransitRow("88B", "8분"),
                TransitRow("88B", "12분"),
            ),
        )
        assertEquals(
            "88B번, 88B번 버스가 8분 후 도착합니다. 다음은 88B번, 12분 후 도착입니다.",
            out.request!!.text,
        )
    }

    @Test
    fun `only alt rows stay pending`() {
        val out = StartBriefing.consider(
            scope(line = "88B"),
            reading(TransitRow("88A", "곧 도착")),
        )
        assertNull(out.request)
        assertEquals(StartBriefingSilence.NO_SCOPE_ROW, out.silence)
    }

    @Test
    fun `missing reading waits and does not invent eta`() {
        val out = StartBriefing.consider(scope(line = "88B"), reading = null)
        assertNull(out.request)
        assertEquals(StartBriefingSilence.NO_READING, out.silence)
    }

    @Test
    fun `subway uses existing trip-start letters`() {
        val out = StartBriefing.consider(
            scope(kind = GuidanceKind.SUBWAY, line = "3호선"),
            reading(TransitKind.SUBWAY, TransitRow("3호선", "5분")),
        )
        assertEquals("3호선이 5분 후 출발합니다.", out.request!!.text)
    }

    @Test
    fun `does not hold the legacy bus wait`() {
        assertFalse(NaverTripStartSession.holdBusWait(0L))
        StartBriefing.consider(scope(line = "88B"), reading("88B", "곧 도착"))
        assertFalse(NaverTripStartSession.holdBusWait(0L))
        assertFalse(NaverTripStartSession.hasSpokenBriefing())
    }

    @Test
    fun `blank eta uses the no-info sentence`() {
        val out = StartBriefing.consider(
            scope(line = "88B"),
            reading(TransitRow("88B", "")),
        )
        assertEquals("88B번 버스 도착 예정 정보가 없습니다.", out.request!!.text)
    }
}

private fun scope(
    active: Boolean = true,
    generation: Int = 1,
    kind: GuidanceKind = GuidanceKind.BUS,
    line: String? = "88B",
) = GuidanceSnapshot(
    active = active,
    generation = generation,
    kind = kind,
    line = line,
)

private fun reading(line: String, eta: String) = reading(TransitRow(line, eta))

private fun reading(vararg rows: TransitRow) = reading(TransitKind.BUS, *rows)

private fun reading(kind: TransitKind, vararg rows: TransitRow) = TransitReading(
    source = ObservationSource.SCREEN,
    atMs = 1_700_000_000_000L,
    kind = kind,
    stop = "대우.삼성오피스텔",
    rows = rows.toList(),
    raw = "",
)
