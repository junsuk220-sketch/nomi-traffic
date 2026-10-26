package nomi.android.traffic.api.transfer

import nomi.android.traffic.api.nextsubway.NextSubwayFact
import nomi.android.traffic.observe.ObservationSource
import nomi.android.traffic.observe.TransitKind
import nomi.android.traffic.observe.TransitReading
import nomi.android.traffic.observe.TransitRow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TransferTest {

    @Before
    fun setUp() {
        Transfer.resetForTest()
        TransferShadowDebug.resetForTest()
    }

    @After
    fun tearDown() {
        Transfer.resetForTest()
    }

    @Test
    fun `transfer briefing repeats only the first train sentence`() {
        assertEquals(
            "3호선이 2분 후 출발합니다. 다음 열차는 10분 후 도착입니다. " +
                "3호선이 2분 후 출발합니다.",
            spokenTwice("3호선이 2분 후 출발합니다. 다음 열차는 10분 후 도착입니다."),
        )
        assertEquals(
            "3호선이 4분 후 출발합니다. 다음 열차 정보는 아직 없습니다. 3호선이 4분 후 출발합니다.",
            spokenTwice("3호선이 4분 후 출발합니다. 다음 열차 정보는 아직 없습니다."),
        )
        assertEquals(
            "서해선, 곧 도착합니다. 서해선, 곧 도착합니다.",
            spokenTwice("서해선, 곧 도착합니다."),
        )
        assertEquals("", spokenTwice("  "))
    }

    @Test
    fun `10-13 destination named 선릉역 2호선 is not a subway to transfer to`() {
        // M7412 → 신논현 → 146번 → 선릉역 bus stop. 2호선 spoke for 20 minutes.
        Transfer.consider(listOf("안내 중", "선릉역 2호선", "까지 이동"), reading = null)
        Transfer.consider(listOf("길안내를 시작합니다.", "선릉역 2호선까지 이동"), reading = null)
        val out = Transfer.consider(
            listOf("이번 정류장에서 하차 후 환승", "신논현역.(구)교보타워사거리"),
            reading = null,
        )
        assertNull(out.request)
        assertEquals(TransferSilence.NOT_SUBWAY, out.silence)
        assertNull(TransferBoardParser.parse(listOf("강남역 2호선", "도로의 왼쪽에 있습니다.")))
        assertNull(TransferBoardParser.parse(listOf("선릉역 2호선 주변 도착")))
    }

    @Test
    fun `real 302 boards still name the subway`() {
        assertEquals("2호선", TransferBoardParser.parse(listOf("영등포구청역 2호선 열차 승차"))!!.line)
        assertEquals("영등포구청역", TransferBoardParser.parse(listOf("영등포구청역 2호선 열차 승차"))!!.station)
        assertEquals("3호선", TransferBoardParser.parse(listOf("정발산역 3호선까지 걷기"))!!.line)
        assertEquals("3호선", TransferBoardParser.parse(listOf("정발산역 3호선 도보 후 열차 승차"))!!.line)
    }

    @Test
    fun `subway minutes without alight stay silent`() {
        val out = Transfer.consider(
            listOf("정발산역 3호선까지 걷기"),
            subway("3호선", "5분"),
        )
        assertNull(out.request)
        assertEquals(TransferSilence.INACTIVE, out.silence)
    }

    @Test
    fun `near-board without subway memory stays silent`() {
        val out = Transfer.consider(
            listOf("승차역 부근입니다"),
            subway("3호선", "5분", "15분"),
        )
        assertNull(out.request)
        assertEquals(TransferSilence.INACTIVE, out.silence)
    }

    @Test
    fun `near-board speaks Naver sheet once after cue`() {
        Transfer.consider(
            listOf("정발산역 3호선 열차 승차", "마두역 방면 빠른 환승: 6-3"),
            reading = null,
            next = subwayFact(),
        )
        val wait = Transfer.consider(
            listOf("승차역 부근입니다"),
            reading = null,
            next = subwayFact(),
        )
        assertNull(wait.request)
        assertEquals(TransferSilence.NEAR_ARMED, wait.silence)
        val speak = Transfer.consider(
            listOf("승차역 부근입니다", "정발산역 3호선"),
            subway("3호선", "3분", "10분"),
            next = subwayFact(),
        )
        assertEquals(
            "3호선이 3분 후 출발합니다. 다음 열차는 10분 후 도착입니다.",
            speak.request!!.text,
        )
        val again = Transfer.consider(
            listOf("승차역 부근입니다"),
            subway("3호선", "2분", "9분"),
            next = subwayFact(),
        )
        assertNull(again.request)
        assertEquals(TransferSilence.NEAR_ALREADY, again.silence)
    }

    @Test
    fun `near-board after Seoul still uses Naver sheet`() {
        Transfer.consider(
            listOf("이번 역에서 하차 후 환승"),
            reading = null,
            next = subwayFact(),
        )
        val seoul = Transfer.considerSeoul(
            listOf(
                TransitRow("3호선", "곧 도착"),
                TransitRow("3호선", "4분"),
            ),
        )
        assertTrue(seoul.request!!.text.contains("곧 도착"))
        assertTrue(Transfer.alreadySpoken())
        val near = Transfer.consider(
            listOf("승차역 부근입니다"),
            subway("3호선", "2분", "8분"),
            next = subwayFact(),
        )
        assertEquals(
            "3호선이 2분 후 출발합니다. 다음 열차는 8분 후 도착입니다.",
            near.request!!.text,
        )
        assertTrue(Transfer.alreadyNearBoardSpoken())
    }

    @Test
    fun `alight transfer arms when the next leg is subway`() {
        val out = Transfer.consider(
            listOf("이번 역(정발산)에서 하차 후 환승", "내리는 문 오른쪽, 3호선으로 환승"),
            reading = null,
            next = subwayFact(),
        )
        assertNull(out.request)
        assertEquals(TransferSilence.ARMED, out.silence)
        assertEquals(true, Transfer.needsSeoulFetch())
    }

    @Test
    fun `alight transfer without a subway stays silent`() {
        val out = Transfer.consider(
            listOf("이번 정류장에서 하차 후 환승"),
            reading = null,
        )
        assertNull(out.request)
        assertEquals(TransferSilence.NOT_SUBWAY, out.silence)
        assertEquals(false, Transfer.needsSeoulFetch())
    }

    @Test
    fun `seoul owns the walk sheet after a bus alight finds the subway`() {
        assertFalse(Transfer.seoulOwnsAlight("2호선"))
        Transfer.consider(listOf("이번 정류장에서 하차 후 환승"), reading = null)
        assertFalse(Transfer.seoulOwnsAlight("2호선"))
        Transfer.consider(
            listOf("당산역 2호선까지 걷기", "외선순환행 (7분), 외선순환행 (13분)"),
            reading = null,
        )
        assertTrue(Transfer.seoulOwnsAlight("2호선"))
        assertFalse(Transfer.seoulOwnsAlight("3호선"))
        Transfer.considerSeoul(listOf(TransitRow("2호선", "2분"), TransitRow("2호선", "5분")))
        assertTrue(Transfer.alreadySpoken())
        assertTrue(Transfer.seoulOwnsAlight("2호선"))
        Transfer.consider(listOf("길안내를 시작합니다."), reading = null)
        assertFalse(Transfer.seoulOwnsAlight("2호선"))
    }

    @Test
    fun `naver minutes after alight arm stay silent until Seoul`() {
        Transfer.consider(
            listOf("이번 역에서 하차 후 환승"),
            reading = null,
            next = subwayFact(),
        )
        val naver = Transfer.consider(
            listOf("정발산역 3호선까지 걷기", "오금행 (5분), 오금행 (15분)"),
            subway("3호선", "5분", "15분"),
        )
        assertNull(naver.request)
        assertEquals(TransferSilence.WAIT_ETA, naver.silence)
        val seoul = Transfer.considerSeoul(
            listOf(
                TransitRow("3호선", "5분"),
                TransitRow("3호선", "15분"),
            ),
        )
        assertEquals(
            "3호선이 5분 후 출발합니다. 다음 열차는 15분 후 도착입니다.",
            seoul.request!!.text,
        )
        val again = Transfer.considerSeoul(
            listOf(TransitRow("3호선", "4분"), TransitRow("3호선", "14분")),
        )
        assertNull(again.request)
        assertEquals(TransferSilence.ALREADY, again.silence)
    }

    @Test
    fun `bus leftover after arm does not invent subway minutes`() {
        Transfer.consider(
            listOf("이번 역에서 하차 후 환승"),
            reading = null,
            next = subwayFact(),
        )
        val out = Transfer.consider(
            listOf("대우.삼성오피스텔 81까지 걷기"),
            reading(
                TransitKind.BUS,
                TransitRow("81", "2분"),
            ),
        )
        assertNull(out.request)
        assertEquals(TransferSilence.WAIT_ETA, out.silence)
    }

    @Test
    fun `one remaining station does not arm`() {
        val out = Transfer.consider(
            listOf("하차까지 1개 역", "정발산에서 하차"),
            reading = null,
            next = subwayFact(),
        )
        assertNull(out.request)
        assertEquals(TransferSilence.INACTIVE, out.silence)
    }

    @Test
    fun `one seoul train says next info is not here yet`() {
        Transfer.consider(
            listOf("이번 역에서 하차 후 환승"),
            reading = null,
            next = subwayFact(),
        )
        Transfer.consider(
            listOf("정발산역 3호선까지 걷기"),
            subway("3호선", "1분"),
        )
        val out = Transfer.considerSeoul(listOf(TransitRow("3호선", "1분")))
        assertEquals(
            "3호선이 1분 후 출발합니다. 다음 열차 정보는 아직 없습니다.",
            out.request!!.text,
        )
    }

    @Test
    fun `alight arrival briefing omits fast transfer cars`() {
        Transfer.consider(
            listOf("정발산역 3호선 열차 승차", "대화행 방면 빠른 환승: 8-4"),
            reading = null,
        )
        Transfer.consider(
            listOf("이번 역에서 하차 후 환승"),
            reading = null,
            next = subwayFact(),
        )
        val out = Transfer.considerSeoul(listOf(TransitRow("3호선", "5분")))
        assertEquals(
            "3호선이 5분 후 출발합니다. 다음 열차 정보는 아직 없습니다.",
            out.request!!.text,
        )
    }

    @Test
    fun `end phrase clears a leftover arm`() {
        Transfer.consider(
            listOf("이번 역에서 하차 후 환승"),
            reading = null,
            next = subwayFact(),
        )
        val reset = Transfer.consider(listOf("길안내를 종료합니다."), reading = null)
        assertEquals(TransferSilence.RESET, reset.silence)
        val later = Transfer.consider(
            listOf("정발산역 3호선까지 걷기"),
            subway("3호선", "5분"),
        )
        assertNull(later.request)
        assertEquals(TransferSilence.INACTIVE, later.silence)
    }

    @Test
    fun `one remaining stop does not arm`() {
        val out = Transfer.consider(
            listOf("하차까지 1개 정류장", "롯데백화점에서 하차"),
            reading = null,
            next = subwayFact(),
        )
        assertNull(out.request)
        assertEquals(TransferSilence.INACTIVE, out.silence)
    }

    @Test
    fun `seoul rows after arm speak once without inventing`() {
        Transfer.consider(
            listOf("3호선 정발산역 승차", "마두역 방면", "빠른 하차: 9-1"),
            reading = null,
        )
        Transfer.consider(listOf("이번 정류장에서 하차 후 환승"), reading = null)
        val first = Transfer.considerSeoul(
            listOf(
                TransitRow("3호선", "2분"),
                TransitRow("3호선", "12분"),
            ),
        )
        assertEquals(
            "3호선이 2분 후 출발합니다. 다음 열차는 12분 후 도착입니다.",
            first.request!!.text,
        )
        val again = Transfer.considerSeoul(listOf(TransitRow("3호선", "1분")))
        assertNull(again.request)
        assertEquals(TransferSilence.ALREADY, again.silence)
    }

    @Test
    fun `Seoul countdown stays active until train starts moving`() {
        Transfer.consider(
            listOf("이번 역(백마역)에서 하차 후 환승"),
            reading = null,
            next = NextSubwayFact(
                line = "경의중앙선",
                station = "백마역",
                direction = "풍산역",
            ),
        )
        assertFalse(Transfer.mayTrackSeoulCountdown())
        Transfer.considerSeoul(
            listOf(
                TransitRow("경의중앙선", "10분"),
                TransitRow("경의중앙선", "14분"),
            ),
        )
        assertTrue(Transfer.mayTrackSeoulCountdown())

        Transfer.consider(
            listOf("백마역 경의중앙선 열차 승차", "풍산역 방면"),
            reading = null,
        )
        assertTrue(Transfer.mayTrackSeoulCountdown())
        Transfer.consider(listOf("풍산역 정차", "하차까지 3개 역"), reading = null)
        assertFalse(Transfer.mayTrackSeoulCountdown())
    }

    @Test
    fun `start phrase clears a leftover arm`() {
        Transfer.consider(
            listOf("이번 역에서 하차 후 환승"),
            reading = null,
            next = subwayFact(),
        )
        val reset = Transfer.consider(listOf("길안내를 시작합니다"), reading = null)
        assertEquals(TransferSilence.RESET, reset.silence)
        assertFalse(Transfer.isArmCue("승차역 부근입니다"))
        assertTrue(Transfer.isNearBoardCue("승차역 부근입니다"))
    }

    @Test
    fun `each alight in a subway chain arms its own transfer`() {
        val chain = listOf(
            NextSubwayFact(line = "3호선", station = "정발산역"),
            NextSubwayFact(line = "서해선", station = "대곡역", direction = "능곡역", cars = "1-1"),
            NextSubwayFact(line = "1호선", station = "소사역", direction = "부천역"),
        )
        val targets = ArrayDeque(chain.drop(1))
        fun tick(cues: List<String>, at: Long) = Transfer.consider(
            cues = cues,
            reading = null,
            nowMs = at,
            chain = chain,
            startLeg = chain[0],
            takeTarget = { targets.removeFirstOrNull() },
        )
        val t0 = 1_000_000L
        assertEquals("정발산역", Transfer.board()?.station ?: tick(listOf("안내 중"), t0).let { Transfer.board()!!.station })

        assertEquals(TransferSilence.ARMED, tick(listOf("이번 역(대곡)에서 하차 후 환승"), t0 + 60_000).silence)
        assertEquals("대곡역", Transfer.board()!!.station)
        assertEquals("서해선", Transfer.board()!!.line)
        val first = Transfer.considerSeoul(listOf(TransitRow("서해선", "3분"), TransitRow("서해선", "13분")))
        assertEquals(
            "서해선이 3분 후 출발합니다. 다음 열차는 13분 후 도착입니다.",
            first.request!!.text,
        )
        // Same alight repeating on later ticks does not re-arm.
        assertEquals(TransferSilence.ALREADY, tick(listOf("이번 역(대곡)에서 하차 후 환승"), t0 + 65_000).silence)
        assertEquals("대곡역", Transfer.board()!!.station)

        val gen = Transfer.armGeneration()
        assertEquals(TransferSilence.ARMED, tick(listOf("이번 역(소사)에서 하차 후 환승"), t0 + 1_500_000).silence)
        assertEquals(gen + 1, Transfer.armGeneration())
        assertEquals("소사역", Transfer.board()!!.station)
        assertEquals(true, Transfer.needsSeoulFetch())
        val second = Transfer.considerSeoul(listOf(TransitRow("1호선", "5분")))
        assertEquals(
            "1호선이 5분 후 출발합니다. 다음 열차 정보는 아직 없습니다.",
            second.request!!.text,
        )

        val bus = tick(listOf("이번 역(주안)에서 하차 후 환승"), t0 + 3_000_000)
        assertNull(bus.request)
        assertEquals(TransferSilence.NOT_SUBWAY, bus.silence)
        assertEquals(false, Transfer.needsSeoulFetch())
    }

    @Test
    fun `near-board one train says next info is not here yet`() {
        val out = Transfer.consider(
            listOf("승차역 부근입니다"),
            subway("3호선", "1분"),
            next = subwayFact(),
        )
        assertEquals(
            "3호선이 1분 후 출발합니다. 다음 열차 정보는 아직 없습니다.",
            out.request!!.text,
        )
    }

    @Test
    fun `seoul express and local are named and a route express fills only a blank row`() {
        val chain = listOf(
            NextSubwayFact(line = "9호선", station = "당산역", service = "급행"),
        )
        Transfer.consider(
            listOf("이번 정류장에서 하차 후 환승"),
            reading = null,
            chain = chain,
            takeTarget = { chain.first() },
        )
        val named = Transfer.considerSeoul(
            listOf(
                TransitRow("9호선", "2분", service = "급행"),
                TransitRow("9호선", "9분", service = "일반"),
            ),
        )
        assertEquals(
            "9호선 급행이 2분 후 출발합니다. 다음 열차는 일반, 9분 후 도착입니다.",
            named.request!!.text,
        )

        Transfer.resetForTest()
        Transfer.consider(
            listOf("이번 정류장에서 하차 후 환승"),
            reading = null,
            chain = chain,
            takeTarget = { chain.first() },
        )
        val fromRoute = Transfer.considerSeoul(
            listOf(TransitRow("9호선", "2분"), TransitRow("9호선", "9분")),
        )
        assertEquals(
            "9호선 급행이 2분 후 출발합니다. 다음 열차는 9분 후 도착입니다.",
            fromRoute.request!!.text,
        )
    }
}

private fun subwayFact() = NextSubwayFact(
    line = "3호선",
    station = "정발산역",
)

private fun subway(line: String, vararg etas: String) = reading(
    TransitKind.SUBWAY,
    *etas.map { TransitRow(line, it) }.toTypedArray(),
)

private fun reading(
    kind: TransitKind,
    vararg rows: TransitRow,
) = TransitReading(
    source = ObservationSource.NOTIFICATION,
    atMs = 1_700_000_000_000L,
    kind = kind,
    stop = "정발산역",
    rows = rows.toList(),
    raw = "",
)
