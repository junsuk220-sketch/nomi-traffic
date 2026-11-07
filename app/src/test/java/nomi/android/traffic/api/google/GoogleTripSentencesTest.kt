package nomi.android.traffic.api.google

import nomi.android.traffic.api.google.alight.GoogleAlight
import nomi.android.traffic.api.google.board.GoogleBoard
import nomi.android.traffic.api.google.brief.GoogleStartBrief
import nomi.android.traffic.api.google.countdown.GoogleCountdown
import nomi.android.traffic.api.google.prepare.GooglePrepare
import nomi.android.traffic.api.google.read.GoogleStepReader
import nomi.android.traffic.api.google.ride.GoogleRide
import nomi.android.traffic.api.google.transfer.GoogleTransfer
import nomi.android.traffic.api.google.walk.GoogleWalk
import nomi.android.traffic.speech.SpeechMouth
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * One whole Google trip, in the order the cells run (GoogleFeed.onPosted), through
 * the same mouth the phone uses. This is the list of sentences Google mode says.
 * Notification texts are the real 2026-09-26 / 2026-11-02 Google ones.
 */
class GoogleTripSentencesTest {

    private val arrive = "오후 6:30 도착"
    private var last: SpeechMouth.Spoken? = null
    private val said = ArrayList<String>()

    @Before
    fun setUp() {
        GoogleStartBrief.resetForTest()
        GoogleWalk.resetForTest()
        GoogleBoard.resetForTest()
        GoogleRide.resetForTest()
        GooglePrepare.resetForTest()
        GoogleAlight.resetForTest()
        GoogleTransfer.resetForTest()
        GoogleCountdown.resetForTest()
        last = null
        said.clear()
    }

    private fun at(hour: Int, minute: Int, second: Int = 0) = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, second)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** The cells in GoogleFeed order; every line goes through SpeechMouth. */
    private fun post(title: String, text: String, now: Long) {
        val step = GoogleStepReader.read(title, text, arrive) ?: return
        val lines = listOfNotNull(
            GoogleStartBrief.onStep(step),
            GoogleWalk.onStep(step),
            GoogleBoard.onStep(step),
            GoogleRide.onStep(step),
            GooglePrepare.onStep(step),
            GoogleAlight.onStep(step),
            GoogleTransfer.onStep(step, now),
            GoogleCountdown.onStep(step, now),
        )
        for (line in lines) {
            val admitted = SpeechMouth.admit(line, now, last) ?: continue
            last = admitted.second
            said += admitted.first
        }
    }

    private fun tick(now: Long) {
        val line = GoogleCountdown.onTick(now) ?: return
        val admitted = SpeechMouth.admit(line, now, last) ?: return
        last = admitted.second
        said += admitted.first
    }

    @Test
    fun `bus trip with a transfer says exactly these sentences`() {
        // Walk to the stop. The brief already said the walk, so it stays out of this list.
        post("도보 9분(600m)", "풍산역 · 오후 5:39 출발", at(17, 20))
        post("도보 5분(300m)", "풍산역 · 오후 5:39 출발", at(17, 24))
        post("도보 1분(50m)", "풍산역 · 오후 5:39 출발", at(17, 28))
        // Waiting at the stop: Google's own departure clock 오후 5:39.
        post("81 노선을 이용하세요.", "풍산역 · 오후 5:39 출발", at(17, 29, 30))
        tick(at(17, 34, 10))
        tick(at(17, 37, 10))
        tick(at(17, 38, 10))
        post("81 탑승", "풍산역 · 오후 5:39 출발", at(17, 39))
        // On the bus. Google flips Ride and Board; nothing repeats.
        post("저동중고교까지 이동", "정류장 5개 · 4분", at(17, 40))
        post("81 탑승", "풍산역 · 오후 5:39 출발", at(17, 40, 5))
        post("저동중고교까지 이동", "정류장 5개 · 4분", at(17, 40, 10))
        post("저동중고교까지 이동", "정류장 4개 · 3분", at(17, 41))
        post("저동중고교까지 이동", "정류장 2개 · 2분", at(17, 42))
        post("저동중고교에서 하차", "정류장 1개 · 1분 · 999(으)로 환승 · 오후 5:56", at(17, 43))
        post("저동중고교에서 하차", "다음 경유지 · 1분 · 999(으)로 환승 · 오후 5:56", at(17, 44))

        assertEquals(
            listOf(
                "걸어서 약 9분입니다.",
                "걸어서 약 5분입니다.",
                "걸어서 약 1분입니다.",
                "81번, 81번 버스가 5분 후 도착해요.",
                "81번, 81번 버스가 2분 후 도착해요.",
                "81번, 81번 버스, 곧 도착합니다.",
                "저동중고교까지 정류장 5개, 4분 남았습니다.",
                "다음 정류장 저동중고교에서 하차 준비하세요.",
                "다음 정류장 저동중고교에서 내리세요.",
                "이번 정류장 저동중고교에서 하차하세요.",
                "다음 버스는 999번입니다. 12분 후 도착합니다.",
            ),
            said,
        )
    }
}
