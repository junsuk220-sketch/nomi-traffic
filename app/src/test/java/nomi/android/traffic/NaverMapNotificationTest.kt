package nomi.android.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NaverMapNotificationTest {

    @Test
    fun `only transit channel is watched`() {
        assertTrue(NaverMapNotification.isWatchedChannel("350_WALK_NAVIGATION"))
        assertTrue(NaverMapNotification.isWatchedChannel("302_PUBTRANS_POPUP"))
        assertFalse(NaverMapNotification.isWatchedChannel("301_PUBTRANS_ALARM"))
        assertFalse(NaverMapNotification.isWatchedChannel("400_NAVIGATION"))
        assertFalse(NaverMapNotification.isWatchedChannel(null))
    }

    @Test
    fun `walk log prints raw fields`() {
        val log = NaverMapNotification.walkLog(
            id = 501,
            channel = "350_WALK_NAVIGATION",
            title = "고두리김치생삼겹살 방면으로 횡단보도 건너기",
            text = "25m 남음",
            nowbarPrimary = "횡단보도 건너기",
            chip = "25m",
        )
        assertEquals(
            """
            [NAVER_WALK]
            id=501
            channel=350_WALK_NAVIGATION
            title=고두리김치생삼겹살 방면으로 횡단보도 건너기
            action=횡단보도 건너기
            distance=25m 남음
            chip=25m
            """.trimIndent(),
            log,
        )
    }

    @Test
    fun `extras dump keeps key value pairs`() {
        val dump = NaverMapNotification.extrasDump(
            listOf(
                "android.title" to "고두리김치생삼겹살 방면으로 횡단보도 건너기",
                "android.text" to "26m 남음",
            ),
        )
        assertEquals(
            "android.title=고두리김치생삼겹살 방면으로 횡단보도 건너기 | android.text=26m 남음",
            dump,
        )
    }

    @Test
    fun `transit log prints raw fields`() {
        val log = NaverMapNotification.transitLog(
            id = 301,
            channel = "302_PUBTRANS_POPUP",
            title = "일산동부경찰서(중) 도보 후 버스 승차",
            text = "11 (10분), 11 (22분)",
            nowbarPrimary = "일산동부경찰서(중) 도보 후 버스 승차",
            chip = "10분",
            extrasNote = "busArrival=11 (10분), 11 (22분)",
        )
        assertTrue(log.contains("[NAVER_TRANSIT]"))
        assertTrue(log.contains("id=301"))
        assertTrue(log.contains("text=11 (10분), 11 (22분)"))
        assertTrue(log.contains("chipExpandedText=10분"))
        assertTrue(log.contains("extras=busArrival=11 (10분), 11 (22분)"))
    }
}
