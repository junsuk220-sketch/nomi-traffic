package nomi.android.traffic.api.bustransfer

import nomi.android.traffic.NaverNotificationParser
import nomi.android.traffic.observe.ObservationSource
import nomi.android.traffic.observe.TransitKind
import nomi.android.traffic.observe.TransitObservation
import nomi.android.traffic.observe.TransitReading
import nomi.android.traffic.observe.TransitRow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class BusTransferTest {

    @Before
    fun setUp() = BusTransfer.reset()

    @After
    fun tearDown() = BusTransfer.reset()

    @Test
    fun `10-09 2355 bus to bus names 96 at 13 minutes once`() {
        val t0 = 1_000_000L
        assertNull(tick(t0, "이번 정류장에서 하차", "길훈4차아파트").text)
        assertNull(tick(t0, "이번 정류장에서 하차 후 환승", "길훈4차아파트").text)
        assertEquals(
            "다음 버스는 96번, 13분 후 도착입니다.",
            tick(t0 + 28_000, "조리미입구까지 걷기", "96 (13분)").text,
        )
        assertNull(tick(t0 + 28_100, "조리미입구까지 걷기", "96 (13분)").text)
        assertNull(tick(t0 + 62_000, "조리미입구까지 걷기", "96 (12분)").text)
    }

    @Test
    fun `10-09 1907 bus to bus names 941 at 27 minutes`() {
        val t0 = 2_000_000L
        tick(t0, "이번 정류장에서 하차 후 환승", "킨텍스역.원시티1단지")
        assertEquals(
            "다음 버스는 941번, 27분 후 도착입니다.",
            tick(t0 + 29_000, "킨텍스역1번출구.현대모터스튜디오고양까지 걷기", "941 (27분)").text,
        )
    }

    @Test
    fun `10 minutes and under is left to the bus wait ladder`() {
        val t0 = 3_000_000L
        tick(t0, "이번 정류장에서 하차 후 환승", "길훈4차아파트")
        assertNull(tick(t0 + 28_000, "조리미입구까지 걷기", "96 (10분)").text)
        assertNull(tick(t0 + 40_000, "조리미입구까지 걷기", "96 (13분)").text)
    }

    @Test
    fun `곧 도착 says nothing`() {
        val t0 = 4_000_000L
        tick(t0, "이번 정류장에서 하차 후 환승", "길훈4차아파트")
        assertNull(tick(t0 + 28_000, "조리미입구까지 걷기", "96 (곧 도착)").text)
    }

    @Test
    fun `회차대기 says the minutes are missing`() {
        val t0 = 4_100_000L
        tick(t0, "이번 정류장에서 하차 후 환승", "길훈4차아파트")
        val said = tick(t0 + 28_000, "조리미입구까지 걷기", "96 (회차대기)")
        assertEquals("다음 버스는 96번, 아직 도착 정보가 없습니다.", said.text)
        assertEquals(true, said.noInfo)
        assertEquals(t0, said.armedAtMs)
    }

    @Test
    fun `도착 and a clock time say nothing`() {
        val t0 = 4_200_000L
        tick(t0, "이번 정류장에서 하차 후 환승", "길훈4차아파트")
        assertNull(tick(t0 + 28_000, "조리미입구까지 걷기", "96 (도착)").text)
        tick(t0 + 600_000, "이번 정류장에서 하차 후 환승", "조리미입구")
        assertNull(tick(t0 + 628_000, "장릉입구까지 걷기", "96 (15:49)").text)
    }

    @Test
    fun `speaks on the board, never before 하차 후 환승 plus 7 s`() {
        val armed = 1_000_000L
        // 10-09: the board came 28 s later, so right away.
        assertEquals(0L, BusTransfer.delayMs(armed, armed + 28_000))
        assertEquals(4_000L, BusTransfer.delayMs(armed, armed + 3_000))
    }

    @Test
    fun `no 하차 후 환승 says nothing`() {
        assertNull(tick(5_000_000L, "조리미입구까지 걷기", "96 (13분)").text)
    }

    @Test
    fun `a subway board after the transfer ends it silently`() {
        val t0 = 6_000_000L
        tick(t0, "이번 역에서 하차 후 환승", "대화역")
        val subway = TransitReading(
            source = ObservationSource.NOTIFICATION,
            atMs = t0 + 20_000,
            kind = TransitKind.SUBWAY,
            stop = null,
            rows = listOf(TransitRow(line = "3호선", eta = "15분")),
            raw = "",
        )
        assertNull(BusTransfer.consider(listOf("정발산역 3호선 열차 승차"), subway, t0 + 20_000).text)
        assertNull(tick(t0 + 40_000, "조리미입구까지 걷기", "96 (13분)").text)
    }

    @Test
    fun `printed walk minutes are added, never guessed`() {
        val t0 = 7_000_000L
        tick(t0, "이번 정류장에서 하차 후 환승", "길훈4차아파트")
        assertEquals(
            "다음 버스는 96번, 13분 후 도착입니다. 정류장까지는 걸어서 약 4분입니다.",
            tick(t0 + 28_000, "조리미입구까지 걷기 도보 약 4분", "96 (13분)").text,
        )
    }

    @Test
    fun `a board long after the transfer is not this transfer`() {
        val t0 = 8_000_000L
        tick(t0, "이번 정류장에서 하차 후 환승", "길훈4차아파트")
        assertNull(tick(t0 + BusTransfer.BOARD_WAIT_MS + 1, "조리미입구까지 걷기", "96 (13분)").text)
    }

    @Test
    fun `the same transfer notice posted again does not speak twice`() {
        val t0 = 9_000_000L
        tick(t0, "이번 정류장에서 하차 후 환승", "길훈4차아파트")
        tick(t0 + 28_000, "조리미입구까지 걷기", "96 (13분)")
        tick(t0 + 30_000, "이번 정류장에서 하차 후 환승", "길훈4차아파트")
        assertNull(tick(t0 + 32_000, "조리미입구까지 걷기", "96 (13분)").text)
    }

    @Test
    fun `a second transfer at another stop speaks again`() {
        val t0 = 10_000_000L
        tick(t0, "이번 정류장에서 하차 후 환승", "킨텍스역.원시티1단지")
        tick(t0 + 29_000, "킨텍스역1번출구.현대모터스튜디오고양까지 걷기", "941 (27분)")
        tick(t0 + 1_800_000, "이번 정류장에서 하차 후 환승", "길훈4차아파트")
        assertEquals(
            "다음 버스는 96번, 13분 후 도착입니다.",
            tick(t0 + 1_828_000, "조리미입구까지 걷기", "96 (13분)").text,
        )
    }

    private fun tick(atMs: Long, title: String, text: String): BusTransferOutcome {
        val reading = TransitObservation.fromNotification(
            NaverNotificationParser.Snapshot(
                packageName = "com.nhn.android.nmap",
                notificationId = 301,
                channel = "302_PUBTRANS_POPUP",
                title = title,
                text = text,
                bigText = text,
                timestampMillis = atMs,
            ),
        )
        return BusTransfer.consider(listOf(title, text, text), reading, atMs)
    }
}
