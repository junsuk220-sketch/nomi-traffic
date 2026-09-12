package nomi.android.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NaverTransferAccessibilityParserTest {

    @Test
    fun `transfer walk after alight door hint`() {
        val event = NaverTransferAccessibilityParser.transferWalk(
            packageName = NaverMapNotification.PACKAGE,
            root = node(
                "안내 중",
                "삼송역 하차",
                "내리는 문: 오른쪽",
                "삼송역 8번 출구에서 도보 165m · 4분",
                "삼송역8번출구 승차",
            ),
        )!!
        assertEquals(NaverMapsTransit.TRANSFER_WALK_ACTION, event.action)
        assertEquals("8번", event.title)
        assertEquals(4, event.distanceMeters)
        assertEquals(
            "8번 출구로 나가서, 버스 정류장까지 걸어서 약 4분입니다.",
            NavigationEventSpeech.line(event),
        )
        assertTrue(NavigationEventSpeechGate().accept(event))
    }

    @Test
    fun `transfer walk stays silent while still far without door hint`() {
        assertNull(
            NaverTransferAccessibilityParser.transferWalk(
                packageName = NaverMapNotification.PACKAGE,
                root = node(
                    "안내 중",
                    "3호선 정발산역 승차",
                    "삼송역 하차",
                    "삼송역 8번 출구에서 도보 165m · 4분",
                ),
            ),
        )
    }

    @Test
    fun `bus board direction shortens long 방면 then eta still works`() {
        val direction = NaverTransferAccessibilityParser.busBoardDirection(
            packageName = NaverMapNotification.PACKAGE,
            root = node(
                "안내 중",
                "삼송역8번출구 승차",
                "037",
                "도착 예정 정보 없음",
                "025",
                "도착 예정 정보 없음",
                "삼송역사거리.지축차량기지입구 방면",
            ),
        )!!
        assertEquals(NaverMapsTransit.BOARD_DIRECTION_ACTION, direction.action)
        assertEquals("037", direction.title)
        assertEquals(
            "037번 버스입니다. 삼송역사거리 방면입니다.",
            NavigationEventSpeech.line(direction),
        )
        val gate = NavigationEventSpeechGate()
        assertTrue(gate.accept(direction))

        val eta = nomi.product.nav.NavigationEvent(
            source = nomi.product.nav.NavigationEventSource.NAVER,
            type = nomi.product.nav.NavigationEventType.TRANSIT,
            notificationId = 0,
            channel = NaverMapsTransit.BUS_CHANNEL,
            title = "037 5분",
            action = "037 5분",
            distanceMeters = null,
            rawText = "037 (5분)",
            busInfo = nomi.product.nav.NavigationBusInfo(
                raw = "037 (5분)",
                arrivals = listOf(
                    nomi.product.nav.NavigationBusArrival("037", "5분", "여유"),
                ),
            ),
            timestampMillis = 0L,
        )
        assertEquals(
            "037번 버스가 5분 후 도착해요. 버스 좌석은 여유입니다.",
            NavigationEventSpeech.line(eta),
        )
        assertFalse(gate.accept(eta))
        val core = nomi.android.traffic.buswait.BusWaitCore()
        core.seed("037")
        assertEquals(5, core.observe(eta.busInfo!!.arrivals)!!.speakStage)
    }

    @Test
    fun `bus board waits while transfer walk is still long`() {
        assertNull(
            NaverTransferAccessibilityParser.busBoardDirection(
                packageName = NaverMapNotification.PACKAGE,
                root = node(
                    "안내 중",
                    "삼송역 8번 출구에서 도보 165m · 4분",
                    "삼송역8번출구 승차",
                    "037",
                    "도착 예정 정보 없음",
                    "삼송역사거리.지축차량기지입구 방면",
                ),
            ),
        )
    }

    @Test
    fun `subway only course never speaks a bus board`() {
        assertNull(
            NaverTransferAccessibilityParser.busBoardDirection(
                packageName = NaverMapNotification.PACKAGE,
                root = node(
                    "안내 중",
                    "정발산역 승차",
                    "3호선",
                    "마두역 방면",
                    "삼송역 하차",
                    "내리는 문: 오른쪽",
                    "삼송역 3번 출구에서",
                    "도보 180m",
                    "2",
                    "분",
                ),
            ),
        )
    }

    private fun node(vararg texts: String) =
        NaverSubwayAccessibilityParser.Node(
            children = texts.map { NaverSubwayAccessibilityParser.Node(text = it) },
        )
}
