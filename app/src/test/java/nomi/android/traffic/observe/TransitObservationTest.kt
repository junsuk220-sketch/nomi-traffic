package nomi.android.traffic.observe

import nomi.android.traffic.NaverActiveGuidanceSlice
import nomi.android.traffic.NaverMapNotification
import nomi.android.traffic.NaverNotificationParser
import nomi.android.traffic.NaverSubwayAccessibilityParser
import nomi.android.traffic.NaverSubwayPin
import nomi.android.traffic.NaverTripStartSession
import nomi.android.traffic.buswait.BusWaitCore
import nomi.android.traffic.scope.CurrentGuidanceScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TransitObservationTest {

    @Before
    fun setUp() {
        CurrentGuidanceScope.resetForTest()
        NaverSubwayPin.reset()
        NaverTripStartSession.reset()
        TransitObservationDebug.resetForTest()
    }

    @After
    fun tearDown() {
        CurrentGuidanceScope.resetForTest()
        NaverSubwayPin.reset()
        NaverTripStartSession.reset()
        TransitObservationDebug.resetForTest()
    }

    @Test
    fun `302 bus keeps every arrival and does not pick the soonest only`() {
        val reading = TransitObservation.fromNotification(
            NaverNotificationParser.Snapshot(
                packageName = NaverMapNotification.PACKAGE,
                notificationId = 301,
                channel = NaverMapNotification.CHANNEL_TRANSIT,
                title = "대우.삼성오피스텔까지 걷기",
                text = "88B (4분), 88B (7분)",
                timestampMillis = 1_700_000_000_000L,
            ),
        )!!
        assertEquals(ObservationSource.NOTIFICATION, reading.source)
        assertEquals(TransitKind.BUS, reading.kind)
        assertEquals("대우.삼성오피스텔", reading.stop)
        assertEquals(2, reading.rows.size)
        assertEquals("88B", reading.rows[0].line)
        assertEquals("4분", reading.rows[0].eta)
        assertEquals("88B", reading.rows[1].line)
        assertEquals("7분", reading.rows[1].eta)
        assertEquals(1_700_000_000_000L, reading.atMs)
    }

    @Test
    fun `302 mixed lines keep both rows instead of the closer line`() {
        val reading = TransitObservation.fromNotification(
            NaverNotificationParser.Snapshot(
                packageName = NaverMapNotification.PACKAGE,
                notificationId = 301,
                channel = NaverMapNotification.CHANNEL_TRANSIT,
                title = "라페스타.먹자골목까지 걷기",
                text = "99 (2분), 81 (7분)",
                timestampMillis = 1L,
            ),
        )!!
        assertEquals(listOf("99", "81"), reading.rows.map { it.line })
        assertEquals(listOf("2분", "7분"), reading.rows.map { it.eta })
        assertNotEquals(listOf("99"), reading.rows.map { it.line })
    }

    @Test
    fun `302 subway board is subway kind with both trains`() {
        val now = System.currentTimeMillis()
        val reading = TransitObservation.fromNotification(
            NaverNotificationParser.Snapshot(
                packageName = NaverMapNotification.PACKAGE,
                notificationId = 301,
                channel = NaverMapNotification.CHANNEL_TRANSIT,
                title = "정발산역 3호선까지 걷기",
                text = "오금행 (${clockAfter(3)}), 오금행 (${clockAfter(21)})",
                timestampMillis = now,
            ),
        )!!
        assertEquals(TransitKind.SUBWAY, reading.kind)
        assertEquals(2, reading.rows.size)
        assertEquals("3호선", reading.rows[0].line)
        assertEquals("3분", reading.rows[0].eta)
        assertEquals("21분", reading.rows[1].eta)
        assertEquals("정발산역 3호선", reading.stop)
    }

    @Test
    fun `live sheet keeps 88B rows and drops 88A after the card end`() {
        val reading = TransitObservation.fromScreen(
            packageName = NaverMapNotification.PACKAGE,
            root = board(
                "안내 중",
                "대우.삼성오피스텔 승차",
                "88B",
                "3분",
                "4정류장",
                "여유",
                "88B",
                "5분",
                "5정류장",
                "여유",
                "안내 종료",
                "바로 안내시작",
                "88A",
                "곧 도착",
                "1정류장",
                "여유",
            ),
            atMs = 9L,
        )!!
        assertEquals(ObservationSource.SCREEN, reading.source)
        assertEquals(TransitKind.BUS, reading.kind)
        assertEquals(2, reading.rows.size)
        assertEquals("88B", reading.rows[0].line)
        assertEquals("3분", reading.rows[0].eta)
        assertEquals("여유", reading.rows[0].occupancy)
        assertEquals(4, reading.rows[0].stopsRemaining)
        assertEquals("88B", reading.rows[1].line)
        assertEquals("5분", reading.rows[1].eta)
        assertTrue(reading.rows.none { it.line == "88A" })
    }

    @Test
    fun `preview 88A without live guidance is not a reading`() {
        val reading = TransitObservation.fromScreen(
            packageName = NaverMapNotification.PACKAGE,
            root = board(
                "바로 안내시작",
                "88A",
                "곧 도착",
                "1정류장",
                "여유",
                "88A",
                "2분",
                "2정류장",
                "여유",
            ),
            atMs = 1L,
        )
        assertNull(reading)
    }

    @Test
    fun `live HUD without card end keeps 88B above 안내 중`() {
        val reading = TransitObservation.fromScreen(
            packageName = NaverMapNotification.PACKAGE,
            root = NaverSubwayAccessibilityParser.Node(
                children = listOf(
                    "도보 309m",
                    "5분",
                    "대우.삼성오피스텔 승차",
                    "20155",
                    "88B",
                    "2분",
                    "2정류장",
                    "여유",
                    "88B",
                    "10분",
                    "6정류장",
                    "여유",
                    "안내 중",
                    "길안내를 시작합니다.",
                ).map { NaverSubwayAccessibilityParser.Node(text = it) },
            ),
            atMs = 10L,
        )!!
        assertEquals(ObservationSource.SCREEN, reading.source)
        assertEquals(TransitKind.BUS, reading.kind)
        assertEquals("대우.삼성오피스텔", reading.stop)
        assertEquals(listOf("88B", "88B"), reading.rows.map { it.line })
        assertEquals(listOf("2분", "10분"), reading.rows.map { it.eta })
        assertEquals(10L, reading.atMs)
    }

    @Test
    fun `live HUD keeps 88B when 3호선 follows 안내 중`() {
        val reading = TransitObservation.fromScreen(
            packageName = NaverMapNotification.PACKAGE,
            root = NaverSubwayAccessibilityParser.Node(
                children = listOf(
                    "대우.삼성오피스텔 승차",
                    "88B",
                    "2분",
                    "2정류장",
                    "여유",
                    "88B",
                    "10분",
                    "6정류장",
                    "여유",
                    "안내 중",
                    "대화역 3호선",
                    "까지 이동",
                ).map { NaverSubwayAccessibilityParser.Node(text = it) },
            ),
            atMs = 11L,
        )!!
        assertEquals(listOf("88B", "88B"), reading.rows.map { it.line })
        assertTrue(reading.rows.none { it.line.contains("3") })
    }

    @Test
    fun `live sheet without card end does not leak the flatten`() {
        val reading = TransitObservation.fromScreen(
            packageName = NaverMapNotification.PACKAGE,
            root = NaverSubwayAccessibilityParser.Node(
                children = listOf(
                    "안내 중",
                    "88B",
                    "3분",
                    "4정류장",
                    "여유",
                    "최소시간",
                    "88A",
                    "곧 도착",
                    "1정류장",
                    "여유",
                ).map { NaverSubwayAccessibilityParser.Node(text = it) },
            ),
            atMs = 1L,
        )!!
        assertEquals(listOf("88B"), reading.rows.map { it.line })
        assertEquals(listOf("3분"), reading.rows.map { it.eta })
        assertTrue(reading.rows.none { it.line == "88A" })
    }

    @Test
    fun `observation does not change scope pin session or bus core`() {
        CurrentGuidanceScope.onScreen(listOf("안내 중", "3호선", "안내 종료"))
        NaverSubwayPin.pin("6호선")
        val core = BusWaitCore()
        core.seed("81")
        assertEquals("3호선", CurrentGuidanceScope.snapshot().line)

        TransitObservation.fromNotification(
            NaverNotificationParser.Snapshot(
                packageName = NaverMapNotification.PACKAGE,
                notificationId = 301,
                channel = NaverMapNotification.CHANNEL_TRANSIT,
                title = "대우.삼성오피스텔까지 걷기",
                text = "88B (3분), 88B (7분)",
                timestampMillis = 1L,
            ),
        )
        TransitObservation.fromScreen(
            packageName = NaverMapNotification.PACKAGE,
            root = board("안내 중", "88B", "3분", "4정류장", "여유"),
            atMs = 1L,
        )

        assertEquals("3호선", CurrentGuidanceScope.snapshot().line)
        assertEquals("6호선", NaverSubwayPin.pinned())
        assertEquals(setOf("81"), core.pinnedLines())
        assertFalse(NaverTripStartSession.hasSpokenBriefing())
    }

    @Test
    fun `each call returns a detached reading`() {
        val snapshot = NaverNotificationParser.Snapshot(
            packageName = NaverMapNotification.PACKAGE,
            notificationId = 301,
            channel = NaverMapNotification.CHANNEL_TRANSIT,
            title = "대우.삼성오피스텔까지 걷기",
            text = "88B (3분), 88B (7분)",
            timestampMillis = 1_700_000_000_000L,
        )
        val first = TransitObservation.fromNotification(snapshot)!!
        val second = TransitObservation.fromNotification(snapshot)!!
        assertEquals(first, second)
        assertNotSame(first, second)
        assertNotSame(first.rows, second.rows)
    }

    @Test
    fun `wrong package is not a reading`() {
        assertNull(
            TransitObservation.fromNotification(
                NaverNotificationParser.Snapshot(
                    packageName = "com.google.android.apps.maps",
                    notificationId = 1,
                    channel = NaverMapNotification.CHANNEL_TRANSIT,
                    title = "88B (1분)",
                    text = "88B (1분)",
                ),
            ),
        )
        assertNull(
            TransitObservation.fromScreen(
                packageName = "com.google.android.apps.maps",
                root = board("안내 중", "88B", "3분", "4정류장", "여유"),
            ),
        )
    }

    private fun board(vararg texts: String): NaverSubwayAccessibilityParser.Node {
        val blobs = texts.toList().let { list ->
            if (list.any { NaverActiveGuidanceSlice.isStart(it) } &&
                list.none { NaverActiveGuidanceSlice.isEnd(it) }
            ) {
                list + NaverActiveGuidanceSlice.END
            } else {
                list
            }
        }
        return NaverSubwayAccessibilityParser.Node(
            children = blobs.map { NaverSubwayAccessibilityParser.Node(text = it) },
        )
    }

    private fun clockAfter(minutes: Int): String {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.MINUTE, minutes)
        return "%02d:%02d".format(
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
        )
    }
}
