package nomi.android.traffic

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NaverRideObservationLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun tearDown() {
        NaverRideObservationLog.disableForTest()
    }

    @Test
    fun `default is off and writes nothing`() {
        NaverRideObservationLog.enabled = false
        NaverRideObservationLog.writeInline = true
        NaverRideObservationLog.attach(tmp.root)
        NaverRideObservationLog.recordNotification(
            ts = 1L,
            pkg = NaverMapNotification.PACKAGE,
            interactive = true,
            id = 301,
            channel = NaverMapNotification.CHANNEL_TRANSIT,
            title = "일산동부경찰서(중) 도보 후 버스 승차",
            text = "89 (9분)",
            bigText = "89 (9분)",
            nowbarPrimary = "도보 후 버스 승차",
            nowbarSecondary = "89 (9분)",
            chip = "9분",
            primary = "도보 후 버스 승차",
            secondary = "89 (9분)",
            progress = 23,
            chipExpandedText = "9분",
        )
        assertFalse(NaverRideObservationLog.file().exists())
    }

    @Test
    fun `flag file turns logger on at attach`() {
        tmp.newFile(NaverRideObservationLog.FLAG_NAME)
        NaverRideObservationLog.enabled = false
        NaverRideObservationLog.attach(tmp.root)
        assertTrue(NaverRideObservationLog.enabled)
    }

    @Test
    fun `notification jsonl has requested fields`() {
        NaverRideObservationLog.resetForTest(tmp.root)
        NaverRideObservationLog.recordNotification(
            ts = 1_700L,
            pkg = NaverMapNotification.PACKAGE,
            interactive = false,
            id = 301,
            channel = NaverMapNotification.CHANNEL_TRANSIT,
            title = "일산동부경찰서(중) 도보 후 버스 승차",
            text = "89 (9분), 89 (32분)",
            bigText = "89 (9분), 89 (32분)",
            nowbarPrimary = "일산동부경찰서(중) 도보 후 버스 승차",
            nowbarSecondary = "89 (9분), 89 (32분)",
            chip = "9분",
            primary = "일산동부경찰서(중) 도보 후 버스 승차",
            secondary = "89 (9분), 89 (32분)",
            progress = 23,
            chipExpandedText = "9분",
        )
        val line = NaverRideObservationLog.file().readLines().single()
        assertTrue(line.contains("\"src\":\"notif\""))
        assertTrue(line.contains("\"id\":301"))
        assertTrue(line.contains("\"channel\":\"302_PUBTRANS_POPUP\""))
        assertTrue(line.contains("\"title\":\"일산동부경찰서(중) 도보 후 버스 승차\""))
        assertTrue(line.contains("\"text\":\"89 (9분), 89 (32분)\""))
        assertTrue(line.contains("\"bigText\""))
        assertTrue(line.contains("\"nowbarPrimary\""))
        assertTrue(line.contains("\"nowbarSecondary\""))
        assertTrue(line.contains("\"chip\":\"9분\""))
        assertTrue(line.contains("\"primary\""))
        assertTrue(line.contains("\"secondary\""))
        assertTrue(line.contains("\"progress\":23"))
        assertTrue(line.contains("\"chipExpandedText\":\"9분\""))
        assertTrue(line.contains("\"interactive\":false"))
        assertFalse(line.contains("<node"))
    }

    @Test
    fun `removed notification writes id and channel`() {
        NaverRideObservationLog.resetForTest(tmp.root)
        NaverRideObservationLog.recordNotificationRemoved(
            ts = 2L,
            pkg = NaverMapNotification.PACKAGE,
            interactive = true,
            id = 301,
            channel = NaverMapNotification.CHANNEL_TRANSIT,
        )
        val line = NaverRideObservationLog.file().readLines().single()
        assertTrue(line.contains("\"src\":\"notif_removed\""))
        assertTrue(line.contains("\"id\":301"))
        assertTrue(line.contains("\"channel\":\"302_PUBTRANS_POPUP\""))
    }

    @Test
    fun `selectBlobs keeps transit text and drops map address`() {
        val kept = NaverRideObservationLog.selectBlobs(
            listOf(
                "지도",
                "경기 고양시 일산동구 무궁화로 20-18",
                "안내 중",
                "일산동부경찰서(중) 승차",
                "89",
                "7분",
                "7정류장",
                "여유",
                "오후 6:02 도착",
                "현재 경로",
                "다음 정류장",
                "스타필드마켓 일산점까지 이동",
            ),
        )
        assertEquals(
            listOf(
                "안내 중",
                "일산동부경찰서(중) 승차",
                "89",
                "7분",
                "7정류장",
                "여유",
                "오후 6:02 도착",
                "현재 경로",
                "다음 정류장",
                "스타필드마켓 일산점까지 이동",
            ),
            kept,
        )
        assertFalse(kept.any { it.startsWith("경기") })
        assertFalse(kept.contains("지도"))
    }

    @Test
    fun `a11y tree writes on change then heartbeats`() {
        NaverRideObservationLog.resetForTest(tmp.root)
        val blobs = listOf("안내 중", "89", "7분")
        NaverRideObservationLog.recordA11yTree(1_000L, NaverMapNotification.PACKAGE, true, blobs)
        NaverRideObservationLog.recordA11yTree(2_000L, NaverMapNotification.PACKAGE, true, blobs)
        assertEquals(1, NaverRideObservationLog.file().readLines().size)
        NaverRideObservationLog.recordA11yTree(
            1_000L + NaverRideObservationLog.TREE_HEARTBEAT_MS,
            NaverMapNotification.PACKAGE,
            true,
            blobs,
        )
        assertEquals(2, NaverRideObservationLog.file().readLines().size)
        NaverRideObservationLog.recordA11yTree(
            1_000L + NaverRideObservationLog.TREE_HEARTBEAT_MS + 1,
            NaverMapNotification.PACKAGE,
            true,
            listOf("안내 중", "현재 경로", "다음 정류장"),
        )
        val lines = NaverRideObservationLog.file().readLines()
        assertEquals(3, lines.size)
        assertTrue(lines.last().contains("\"src\":\"a11y_tree\""))
        assertTrue(lines.last().contains("\"live\":true"))
        assertTrue(lines.last().contains("현재 경로"))
    }

    @Test
    fun `content changed events heartbeat but clicks always write`() {
        NaverRideObservationLog.resetForTest(tmp.root)
        fun contentChanged(ts: Long) {
            NaverRideObservationLog.recordA11yEvent(
                ts = ts,
                pkg = NaverMapNotification.PACKAGE,
                interactive = false,
                a11yType = "TYPE_WINDOW_CONTENT_CHANGED",
                className = "android.widget.FrameLayout",
                text = "89, 17분",
                contentDescription = null,
                viewId = null,
            )
        }
        contentChanged(1_000L)
        contentChanged(2_000L)
        assertEquals(1, NaverRideObservationLog.file().readLines().size)
        NaverRideObservationLog.recordA11yEvent(
            ts = 2_001L,
            pkg = NaverMapNotification.PACKAGE,
            interactive = false,
            a11yType = "TYPE_VIEW_CLICKED",
            className = "android.widget.Button",
            text = "안내종료",
            contentDescription = null,
            viewId = "com.nhn.android.nmap:id/end",
            skipDedup = true,
        )
        NaverRideObservationLog.recordA11yEvent(
            ts = 2_002L,
            pkg = NaverMapNotification.PACKAGE,
            interactive = false,
            a11yType = "TYPE_VIEW_CLICKED",
            className = "android.widget.Button",
            text = "안내종료",
            contentDescription = null,
            viewId = "com.nhn.android.nmap:id/end",
            skipDedup = true,
        )
        val lines = NaverRideObservationLog.file().readLines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("\"interactive\":false"))
        assertTrue(lines[1].contains("TYPE_VIEW_CLICKED"))
        assertTrue(lines[2].contains("TYPE_VIEW_CLICKED"))
    }

    @Test
    fun `identical notification heartbeats, payload change writes immediately`() {
        NaverRideObservationLog.resetForTest(tmp.root)
        fun notif(ts: Long, chip: String) {
            NaverRideObservationLog.recordNotification(
                ts = ts,
                pkg = NaverMapNotification.PACKAGE,
                interactive = true,
                id = 301,
                channel = NaverMapNotification.CHANNEL_TRANSIT,
                title = "도보 후 버스 승차",
                text = "89 ($chip)",
                bigText = "89 ($chip)",
                nowbarPrimary = "도보 후 버스 승차",
                nowbarSecondary = "89 ($chip)",
                chip = chip,
                primary = "도보 후 버스 승차",
                secondary = "89 ($chip)",
                progress = 23,
                chipExpandedText = chip,
            )
        }
        notif(1_000L, "9분")
        notif(2_000L, "9분")
        assertEquals(1, NaverRideObservationLog.file().readLines().size)
        notif(2_001L, "8분")
        assertEquals(2, NaverRideObservationLog.file().readLines().size)
    }

    @Test
    fun `age rotation renames without reading lines`() {
        NaverRideObservationLog.resetForTest(tmp.root)
        NaverRideObservationLog.recordA11yEvent(
            ts = 1L,
            pkg = NaverMapNotification.PACKAGE,
            interactive = true,
            a11yType = "TYPE_WINDOW_STATE_CHANGED",
            className = "android.widget.FrameLayout",
            text = "안내 중",
            contentDescription = null,
            viewId = null,
            skipDedup = true,
        )
        val original = NaverRideObservationLog.file().readText()
        NaverRideObservationLog.rotateIfNeeded(
            NaverRideObservationLog.file(),
            System.currentTimeMillis() + NaverRideObservationLog.MAX_AGE_MS + 1,
        )
        assertFalse(NaverRideObservationLog.file().exists())
        assertEquals(original, NaverRideObservationLog.rotateFile().readText())
    }

    @Test
    fun `size rotation uses file length not readLines`() {
        NaverRideObservationLog.resetForTest(tmp.root)
        val out = NaverRideObservationLog.file()
        out.writeBytes(ByteArray(NaverRideObservationLog.MAX_BYTES.toInt() + 8) { 'x'.code.toByte() })
        NaverRideObservationLog.rotateIfNeeded(out, System.currentTimeMillis())
        assertFalse(NaverRideObservationLog.file().exists())
        assertTrue(NaverRideObservationLog.rotateFile().length() > NaverRideObservationLog.MAX_BYTES)
    }

    @Test
    fun `writer runs on dedicated thread`() {
        NaverRideObservationLog.resetForTest(tmp.root, inline = false)
        val caller = Thread.currentThread().name
        NaverRideObservationLog.recordNotificationRemoved(
            ts = 3L,
            pkg = NaverMapNotification.PACKAGE,
            interactive = true,
            id = 301,
            channel = NaverMapNotification.CHANNEL_TRANSIT,
        )
        NaverRideObservationLog.flushForTest()
        assertEquals("naver-ride-observe", NaverRideObservationLog.lastWriteThreadName)
        assertNotEquals(caller, NaverRideObservationLog.lastWriteThreadName)
    }

    @Test
    fun `type names match a11y constants`() {
        assertEquals("TYPE_VIEW_CLICKED", NaverRideObservationLog.typeName(1))
        assertEquals("TYPE_WINDOW_STATE_CHANGED", NaverRideObservationLog.typeName(32))
        assertEquals("TYPE_WINDOW_CONTENT_CHANGED", NaverRideObservationLog.typeName(2048))
    }
}
