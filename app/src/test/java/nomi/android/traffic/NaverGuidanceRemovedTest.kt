package nomi.android.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NaverGuidanceRemovedTest {

    private val transit = NaverMapNotification.CHANNEL_TRANSIT

    @Test
    fun `riding card removed ends the guidance`() {
        // 2026-10-02 15:39:59: 안내 종료 tap never arrived; the card went away.
        assertTrue(NaverGuidanceRemoved.endsGuidance(transit, "고양세무서 정차"))
        assertTrue(NaverGuidanceRemoved.endsGuidance(transit, "라페스타.먹자골목까지 걷기"))
    }

    @Test
    fun `end notice removed while a new guidance opens ends nothing`() {
        // 2026-10-02 15:37:59: the old 종료 card left as the new trip started.
        assertFalse(NaverGuidanceRemoved.endsGuidance(transit, "길안내를 종료합니다."))
        assertFalse(NaverGuidanceRemoved.endsGuidance(transit, "길안내를 시작합니다."))
    }

    @Test
    fun `other channels and blank titles end nothing`() {
        assertFalse(NaverGuidanceRemoved.endsGuidance("350_WALK_NAVIGATION", "어구어구 건대본점"))
        assertFalse(NaverGuidanceRemoved.endsGuidance(transit, null))
        assertFalse(NaverGuidanceRemoved.endsGuidance(transit, "  "))
    }

    @Test
    fun `10-13 걷기 card swapped for 버스 승차 keeps the guidance`() {
        // 18:14:22 removed, 18:14:23 posted again; the end wiped the 1500번 10/5/2/곧.
        var ended = 0
        NaverGuidanceRemoved.hold(1_000L) { ended++ }
        assertTrue(NaverGuidanceRemoved.onPosted(transit, "당산역삼성래미안아파트 버스 승차"))
        NaverGuidanceRemoved.expire(1_000L + NaverGuidanceRemoved.REPOST_GRACE_MS + 1)
        assertEquals(0, ended)
    }

    @Test
    fun `no card back within the grace ends the guidance`() {
        var ended = 0
        NaverGuidanceRemoved.hold(2_000L) { ended++ }
        NaverGuidanceRemoved.expire(2_000L + NaverGuidanceRemoved.REPOST_GRACE_MS - 1)
        assertEquals(0, ended)
        NaverGuidanceRemoved.expire(2_000L + NaverGuidanceRemoved.REPOST_GRACE_MS)
        assertEquals(1, ended)
        NaverGuidanceRemoved.expire(2_000L + 10 * NaverGuidanceRemoved.REPOST_GRACE_MS)
        assertEquals(1, ended)
    }

    @Test
    fun `new guidance notice runs the held end first`() {
        // Field: 걷기 card removed, 길안내를 시작합니다 0.36 s later.
        var ended = 0
        NaverGuidanceRemoved.hold(3_000L) { ended++ }
        assertFalse(NaverGuidanceRemoved.onPosted(transit, "길안내를 시작합니다."))
        assertEquals(1, ended)
        assertFalse(NaverGuidanceRemoved.isHolding())
    }

    @Test
    fun `안내시작 tap in the grace runs the held end before the new session`() {
        var ended = 0
        NaverGuidanceRemoved.hold(4_000L) { ended++ }
        NaverGuidanceRemoved.flush()
        assertEquals(1, ended)
        NaverGuidanceRemoved.flush()
        assertEquals(1, ended)
    }

    @Test
    fun `walk channel posts neither cancel nor run the held end`() {
        var ended = 0
        NaverGuidanceRemoved.hold(5_000L) { ended++ }
        assertFalse(NaverGuidanceRemoved.onPosted("350_WALK_NAVIGATION", "어구어구"))
        assertTrue(NaverGuidanceRemoved.isHolding())
        NaverGuidanceRemoved.flush()
        assertEquals(1, ended)
    }
}
