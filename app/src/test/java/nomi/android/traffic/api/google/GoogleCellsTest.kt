package nomi.android.traffic.api.google

import nomi.android.traffic.api.google.board.GoogleBoard
import nomi.android.traffic.api.google.brief.GoogleStartBrief
import nomi.android.traffic.api.google.read.GoogleSay
import nomi.android.traffic.api.google.read.GoogleStep
import nomi.android.traffic.api.google.read.GoogleStepReader
import nomi.android.traffic.api.google.ride.GoogleRide
import nomi.android.traffic.api.google.transfer.GoogleTransfer
import nomi.android.traffic.api.google.walk.GoogleWalk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Built from the 2026-09-26 17:32–17:42 Google notifications, in order. */
class GoogleCellsTest {

    private val arrive = "오후 7:01 도착"

    private val field = listOf(
        Triple("도보 0분(0m)", "라페스타.먹자골목 · 오후 5:39 출발", arrive),
        Triple("도보 1분(0m)", "라페스타.먹자골목 · 오후 5:39 출발", arrive),
        Triple("81 노선을 이용하세요.", "풍산역 · 오후 5:39 출발", arrive),
        Triple("81 탑승", "풍산역 · 오후 5:39 출발", arrive),
        Triple("저동중고교까지 이동", "정류장 5개 · 4분", arrive),
        Triple("저동중고교까지 이동", "정류장 5개 · 3분", arrive),
        Triple("저동중고교까지 이동", "정류장 4개 · 3분", arrive),
        Triple("저동중고교까지 이동", "정류장 4개 · 2분", arrive),
        Triple("저동중고교에서 하차", "정류장 4개 · 2분 · 999(으)로 환승 · 오후 5:56", arrive),
        Triple("저동중고교에서 하차", "정류장 3개 · 2분 · 999(으)로 환승 · 오후 5:56", arrive),
        Triple("저동중고교에서 하차", "정류장 3개 · 1분 · 999(으)로 환승 · 오후 5:56", arrive),
    )

    @Before
    fun setUp() {
        GoogleStartBrief.resetForTest()
        GoogleWalk.resetForTest()
        GoogleBoard.resetForTest()
        GoogleRide.resetForTest()
        GoogleTransfer.resetForTest()
    }

    private fun steps() = field.map { (t, x, s) -> GoogleStepReader.read(t, x, s)!! }

    private fun at(hour: Int, minute: Int) = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, hour)
        set(java.util.Calendar.MINUTE, minute)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun run(cell: (GoogleStep) -> String?) = steps().mapNotNull(cell)

    @Test
    fun `reader keeps google words`() {
        val s = steps()
        assertEquals(GoogleStep.Walk(1, "0m", "라페스타.먹자골목", "오후 5:39", "오후 7:01"), s[1])
        assertEquals(GoogleStep.Wait("81", "풍산역", "오후 5:39", "오후 7:01"), s[2])
        assertEquals(GoogleStep.Board("81", "풍산역", "오후 5:39", "오후 7:01"), s[3])
        assertEquals(GoogleStep.Ride("저동중고교", 5, "정류장", 4, "오후 7:01"), s[4])
        assertEquals(
            GoogleStep.Alight("저동중고교", 4, "정류장", 2, "999", "오후 5:56", "오후 7:01"),
            s[8],
        )
    }

    @Test
    fun `survey notification is not a step`() {
        assertNull(GoogleStepReader.read("차량 내부가 얼마나 혼잡한가요?", "답변하시면 81 이용객에게 도움이 됩니다", null))
    }

    @Test
    fun `brief never speaks from notifications`() {
        assertEquals(emptyList<String>(), run(GoogleStartBrief::onStep))
    }

    @Test
    fun `walk skips zero and speaks once`() {
        assertEquals(listOf("걸어서 약 1분입니다."), run(GoogleWalk::onStep))
    }

    @Test
    fun `walk speaks at five and one`() {
        val said = listOf(9, 8, 6, 5, 4, 3, 2, 1, 0).mapNotNull {
            GoogleWalk.onStep(GoogleStep.Walk(it, null, null, null, null))
        }
        assertEquals(listOf("걸어서 약 9분입니다.", "걸어서 약 5분입니다.", "걸어서 약 1분입니다."), said)
    }

    @Test
    fun `board stays silent`() {
        assertEquals(emptyList<String>(), run(GoogleBoard::onStep))
    }

    @Test
    fun `ride speaks count once and nothing at four stops`() {
        assertEquals(listOf("저동중고교까지 정류장 5개, 4분 남았습니다."), run(GoogleRide::onStep))
    }

    @Test
    fun `ride says next stop at one`() {
        run(GoogleRide::onStep)
        val one = GoogleStepReader.read(
            "저동중고교에서 하차", "정류장 1개 · 1분 · 999(으)로 환승 · 오후 5:56", arrive,
        )!!
        assertEquals("다음 정류장 저동중고교에서 내리세요.", GoogleRide.onStep(one))
        assertNull(GoogleRide.onStep(one))
    }

    @Test
    fun `transfer speaks once at this stop and names the bus once`() {
        assertNull(GoogleTransfer.onStep(steps()[8], 0L))
        val here = GoogleStepReader.read(
            "저동초교삼거리에서 하차",
            "다음 경유지 · 1분 · 999(으)로 환승 · 오후 8:56",
            arrive,
        )!!
        assertEquals(
            "다음 버스는 999번입니다. 12분 후 도착합니다.",
            GoogleTransfer.onStep(here, at(20, 44)),
        )
        assertNull(GoogleTransfer.onStep(here, at(20, 44)))
    }

    @Test
    fun `transfer notice clock due now points to the phone screen`() {
        val here = GoogleStepReader.read(
            "저동초교삼거리에서 하차",
            "다음 경유지 · 1분 · 999(으)로 환승 · 오후 8:56",
            arrive,
        )!!
        assertEquals(
            "다음 버스는 999번입니다. 도착 시간은 휴대폰 화면에서 확인해 주세요.",
            GoogleTransfer.onStep(here, at(20, 56)),
        )
    }

    /** 2026-09-30 17:34:31. The notice kept 오후 5:34; the screen showed 7727 at 오후 5:43. */
    private val transferScreen = listOf(
        "\u200b\u200b81\u200b", "오후 5:31에 출발", "기타: 오후 5:44(예정)",
        "정류장 1개(1분) 이동", "일산동구청(중). 오후 5:36", "환승",
        "일산동구청(중)", "\u200b\u200b7727\u200b", "\u200b7727", "신촌", "실시간",
        "오후\n5:43", "오후 5:43에 출발", "출발편 더보기", "기타: 오후 5:39(실시간)",
        "정류장 10개(21분) 이동", "이 이동의 경로 한눈에 보기 종료",
    )
    private val transferHere = GoogleStepReader.read(
        "일산동구청(중)에서 하차",
        "다음 경유지 · 1분 · 7727(으)로 환승 · 오후 5:34",
        "오후 6:03 도착",
    )!!

    private fun atSec(hour: Int, minute: Int, second: Int) = at(hour, minute) + second * 1000L

    @Test
    fun `transfer takes the bus clock from the guidance screen`() {
        GoogleTransfer.onScreen(transferScreen, live = true, nowMillis = atSec(17, 34, 23))
        assertEquals(
            "다음 버스는 7727번입니다. 9분 후 도착합니다.",
            GoogleTransfer.onStep(transferHere, atSec(17, 34, 31)),
        )
    }

    @Test
    fun `transfer without a screen clock and a passed notice clock points to the phone screen`() {
        assertEquals(
            "다음 버스는 7727번입니다. 도착 시간은 휴대폰 화면에서 확인해 주세요.",
            GoogleTransfer.onStep(transferHere, atSec(17, 35, 10)),
        )
    }

    @Test
    fun `transfer ignores a screen that is not live`() {
        GoogleTransfer.onScreen(transferScreen, live = false, nowMillis = atSec(17, 34, 23))
        assertEquals(
            "다음 버스는 7727번입니다. 도착 시간은 휴대폰 화면에서 확인해 주세요.",
            GoogleTransfer.onStep(transferHere, atSec(17, 35, 10)),
        )
    }

    @Test
    fun `transfer uses the notice clock when the screen shows another line`() {
        GoogleTransfer.onScreen(transferScreen, live = true, nowMillis = at(20, 44))
        val here = GoogleStepReader.read(
            "저동초교삼거리에서 하차",
            "다음 경유지 · 1분 · 999(으)로 환승 · 오후 8:56",
            arrive,
        )!!
        assertEquals(
            "다음 버스는 999번입니다. 12분 후 도착합니다.",
            GoogleTransfer.onStep(here, at(20, 44)),
        )
    }

    @Test
    fun `last alight without transfer is silent in transfer`() {
        val last = GoogleStepReader.read("일산역에서 하차", "정류장 2개 · 3분", arrive)!!
        assertNull(GoogleTransfer.onStep(last, 0L))
    }

    @Test
    fun `cells do not share state`() {
        steps().forEach { GoogleTransfer.onStep(it, 0L) }
        GoogleRide.reset()
        assertNull(GoogleTransfer.onStep(steps()[8], 0L))
        assertEquals(listOf("저동중고교까지 정류장 5개, 4분 남았습니다."), run(GoogleRide::onStep))
    }

    @Test
    fun `say helpers`() {
        assertEquals("오후 6시", GoogleSay.clock("오후 6:00"))
        assertEquals("3호선", GoogleSay.line("3호선"))
        assertEquals("3호선으로", GoogleSay.ro("3호선"))
        assertEquals("경의중앙선으로", GoogleSay.ro("경의중앙선"))
        assertEquals("공항철도로", GoogleSay.ro("공항철도"))
    }
}
