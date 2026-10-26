package nomi.android.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NaverTripStartSessionTest {

    @Before
    fun reset() {
        NaverTripStartSession.reset()
        NaverTripStartSession.noteBriefed(null, 0L)
    }

    @Test
    fun `click live and notification arm only once`() {
        val t0 = 1_000L
        assertTrue(NaverTripStartSession.request(t0))
        assertFalse(NaverTripStartSession.request(t0 + 100))
        assertFalse(NaverTripStartSession.noteLive(t0 + 200))
        assertFalse(NaverTripStartSession.due(t0 + 1_000))
        assertTrue(NaverTripStartSession.due(t0 + NaverTripStartSession.ARM_DELAY_MS))
        assertTrue(NaverTripStartSession.holdBusWait(t0 + 1_000))
    }

    @Test
    fun `spoken click is ignored until guidance ends`() {
        val t0 = 10_000L
        assertTrue(NaverTripStartSession.request(t0))
        NaverTripStartSession.markSpoken(t0 + 4_000)
        assertFalse(NaverTripStartSession.request(t0 + 5_000))
        assertFalse(NaverTripStartSession.noteLive(t0 + 6_000))
        NaverTripStartSession.reset()
        assertTrue(NaverTripStartSession.request(t0 + 20_000))
    }

    @Test
    fun `new 안내시작 click reopens briefing after a spoken trip`() {
        val t0 = 30_000L
        assertTrue(NaverTripStartSession.request(t0))
        NaverTripStartSession.markSpoken(t0 + 4_000)
        assertFalse(NaverTripStartSession.request(t0 + 5_000))
        assertTrue(NaverTripStartSession.restartFromClick(t0 + 6_000))
        assertFalse(NaverTripStartSession.noteLive(t0 + 7_000))
        assertTrue(NaverTripStartSession.due(t0 + 6_000 + NaverTripStartSession.ARM_DELAY_MS))
    }

    @Test
    fun `10-11 click without guidance never briefs`() {
        // 19:30:50 and 19:49:47 the user only looked at route previews: no 안내 중, no 302.
        val t0 = 2_000_000L
        assertTrue(NaverTripStartSession.restartFromClick(t0))
        assertFalse(NaverTripStartSession.due(t0 + NaverTripStartSession.ARM_DELAY_MS))
        assertFalse(NaverTripStartSession.due(t0 + 30_000L))
        assertFalse(NaverTripStartSession.dropUnconfirmed(t0 + NaverTripStartSession.CONFIRM_WINDOW_MS))
        assertTrue(NaverTripStartSession.dropUnconfirmed(t0 + NaverTripStartSession.CONFIRM_WINDOW_MS + 1))
        assertFalse(NaverTripStartSession.due(t0 + 80 * 60_000L))
    }

    @Test
    fun `click then 안내 중 confirms and briefs on the click clock`() {
        val t0 = 3_000_000L
        assertTrue(NaverTripStartSession.restartFromClick(t0))
        assertFalse(NaverTripStartSession.noteLive(t0 + 1_500L))
        assertFalse(NaverTripStartSession.due(t0 + 3_000L))
        assertTrue(NaverTripStartSession.due(t0 + NaverTripStartSession.ARM_DELAY_MS))
    }

    @Test
    fun `click then 길안내를 시작합니다 confirms`() {
        val t0 = 4_000_000L
        assertTrue(NaverTripStartSession.restartFromClick(t0))
        assertFalse(NaverTripStartSession.request(t0 + 900L))
        assertTrue(NaverTripStartSession.due(t0 + NaverTripStartSession.ARM_DELAY_MS))
    }

    @Test
    fun `길안내를 시작합니다 just before its own click still confirms`() {
        // 2026-09-26 16:18:58: the notice came, then 바로 안내시작 in the same instant.
        val t0 = 4_500_000L
        NaverTripStartSession.request(t0)
        assertTrue(NaverTripStartSession.restartFromClick(t0 + 178L))
        assertTrue(NaverTripStartSession.due(t0 + 178L + NaverTripStartSession.ARM_DELAY_MS))
    }

    @Test
    fun `old notice does not confirm a later click`() {
        val t0 = 4_600_000L
        NaverTripStartSession.request(t0)
        assertTrue(NaverTripStartSession.restartFromClick(t0 + NaverTripStartSession.CLICK_KEEPS_CONFIRM_MS + 1))
        assertFalse(NaverTripStartSession.due(t0 + 30_000L))
    }

    @Test
    fun `bus ETA notification confirms a click but never arms by itself`() {
        // 2026-09-26 15:20:43: only the 5000 (22분) notification followed the click.
        val t0 = 4_700_000L
        NaverTripStartSession.confirm(t0)
        assertFalse(NaverTripStartSession.due(t0 + 30_000L))
        val click = t0 + 60_000L
        assertTrue(NaverTripStartSession.restartFromClick(click))
        NaverTripStartSession.confirm(click + 13_000L)
        assertTrue(NaverTripStartSession.due(click + 13_000L))
    }

    @Test
    fun `unconfirmed click hours ago does not swallow the next real guidance`() {
        val t0 = 5_000_000L
        assertTrue(NaverTripStartSession.restartFromClick(t0))
        val later = t0 + 90 * 60_000L
        assertTrue(NaverTripStartSession.noteLive(later))
        assertTrue(NaverTripStartSession.due(later + NaverTripStartSession.ARM_DELAY_MS))
    }

    @Test
    fun `confirmed guidance is never dropped as unconfirmed`() {
        val t0 = 6_000_000L
        assertTrue(NaverTripStartSession.noteLive(t0))
        assertFalse(NaverTripStartSession.dropUnconfirmed(t0 + 10 * 60_000L))
        assertTrue(NaverTripStartSession.due(t0 + 10 * 60_000L))
    }

    @Test
    fun `guidance end frees a session that never found a briefing`() {
        val t0 = 100_000L
        assertTrue(NaverTripStartSession.request(t0))
        val due = t0 + NaverTripStartSession.ARM_DELAY_MS
        assertTrue(NaverTripStartSession.due(due))
        assertFalse(NaverTripStartSession.giveUpIfStale(due))
        assertTrue(
            NaverTripStartSession.giveUpIfStale(due + NaverTripStartSession.GIVE_UP_AFTER_DUE_MS),
        )
        assertFalse(NaverTripStartSession.holdBusWait(due))
        assertTrue(NaverTripStartSession.due(due + 60_000))
        assertFalse(NaverTripStartSession.hasSpokenBriefing())
        assertFalse(NaverTripStartSession.request(due + 60_000))
        NaverTripStartSession.reset()
        assertTrue(NaverTripStartSession.noteLive(due + 70_000))
    }

    @Test
    fun `briefing spoken is false until markSpoken`() {
        assertFalse(NaverTripStartSession.hasSpokenBriefing())
        NaverTripStartSession.request(1_000L)
        assertFalse(NaverTripStartSession.hasSpokenBriefing())
        NaverTripStartSession.markSpoken(5_000L)
        assertTrue(NaverTripStartSession.hasSpokenBriefing())
    }

    @Test
    fun `10-09 안내 중 gone for minutes does not rebrief the first bus`() {
        // 19:07 the sheet showed the 941 승차 card without 안내 중; 19:18 안내 중 came back
        // and the old rule re-armed and briefed 100번 again.
        val t0 = 200_000L
        assertTrue(NaverTripStartSession.request(t0))
        NaverTripStartSession.markSpoken(t0 + 4_000)
        assertFalse(NaverTripStartSession.noteLive(t0 + 11 * 60_000L))
        assertTrue(NaverTripStartSession.hasSpokenBriefing())
        assertFalse(NaverTripStartSession.due(t0 + 11 * 60_000L + NaverTripStartSession.ARM_DELAY_MS))
        assertFalse(NaverTripStartSession.request(t0 + 12 * 60_000L))
    }

    @Test
    fun `안내종료 reset ends immediately`() {
        val t0 = 400_000L
        assertTrue(NaverTripStartSession.request(t0))
        assertFalse(NaverTripStartSession.noteLive(t0 + 100))
        NaverTripStartSession.markSpoken(t0 + 4_000)
        assertTrue(NaverTripStartSession.hasSpokenBriefing())
        NaverTripStartSession.reset()
        assertFalse(NaverTripStartSession.hasSpokenBriefing())
        assertTrue(NaverTripStartSession.request(t0 + 4_100))
    }

    @Test
    fun `briefing waits seven seconds after the arm`() {
        // 2026-10-02 17:39:00: the live edge armed and the briefing spoke over 길안내를 시작합니다.
        val t0 = 600_000L
        assertTrue(NaverTripStartSession.noteLive(t0))
        assertEquals(6_952L, NaverTripStartSession.msUntilBriefing(t0 + 48))
        assertEquals(
            NaverTripStartSession.BRIEF_AFTER_NAVER_MS - 2_000L,
            NaverTripStartSession.msUntilBriefing(t0 + 2_000L),
        )
        assertEquals(0L, NaverTripStartSession.msUntilBriefing(t0 + 20_000L))
    }

    @Test
    fun `briefing never armed waits the full seven seconds`() {
        assertEquals(
            NaverTripStartSession.BRIEF_AFTER_NAVER_MS,
            NaverTripStartSession.msUntilBriefing(5_000L),
        )
    }

    @Test
    fun `start briefing repeats only the first vehicle sentence`() {
        assertEquals(
            "99번, 99번 버스가 5분 후 도착합니다. 99번, 99번 버스가 5분 후 도착합니다.",
            startBriefTwice("99번, 99번 버스가 5분 후 도착합니다."),
        )
        assertEquals(
            "81번, 81번 버스가 10분 후 도착합니다. 정류장까지는 걸어서 약 5분입니다. " +
                "다음은 81번, 20분 후 도착입니다. 81번, 81번 버스가 10분 후 도착합니다.",
            startBriefTwice(
                "81번, 81번 버스가 10분 후 도착합니다. 정류장까지는 걸어서 약 5분입니다. " +
                    "다음은 81번, 20분 후 도착입니다.",
            ),
        )
        assertEquals(
            "3호선이 7분 후 출발합니다. 역까지는 걸어서 약 3분입니다. 3호선이 7분 후 출발합니다.",
            startBriefTwice("3호선이 7분 후 출발합니다. 역까지는 걸어서 약 3분입니다."),
        )
        assertEquals(
            "88번 버스 도착 예정 정보가 없습니다. 정류장까지는 걸어서 약 2분입니다. " +
                "88번 버스 도착 예정 정보가 없습니다.",
            startBriefTwice("88번 버스 도착 예정 정보가 없습니다. 정류장까지는 걸어서 약 2분입니다."),
        )
    }

    @Test
    fun `briefing route key is the opening line name`() {
        assertEquals("99번", briefRouteKey("99번, 99번 버스가 3분 후 도착합니다."))
        assertEquals("3호선", briefRouteKey("3호선이 7분 후 출발합니다."))
        assertEquals("3호선", briefRouteKey("3호선, 곧 도착합니다."))
        assertEquals("88번", briefRouteKey("88번 버스 도착 예정 정보가 없습니다."))
        assertEquals("3호선", briefRouteKey("3호선 오금행 열차 도착 예정 정보가 없습니다."))
    }

    @Test
    fun `10-02 field late click 13s after the 99 briefing is the same trip`() {
        val spokenAt = 1_000_000L
        NaverTripStartSession.markSpoken(spokenAt)
        NaverTripStartSession.noteBriefed("99번", spokenAt)
        NaverTripStartSession.restartFromClick(spokenAt + 13_600L)
        assertTrue(NaverTripStartSession.isLateClickRepeat("99번"))
    }

    @Test
    fun `late click on a different line briefs again`() {
        NaverTripStartSession.noteBriefed("99번", 1_000_000L)
        NaverTripStartSession.restartFromClick(1_010_000L)
        assertFalse(NaverTripStartSession.isLateClickRepeat("81번"))
    }

    @Test
    fun `click more than a minute after the briefing briefs again`() {
        NaverTripStartSession.noteBriefed("99번", 1_000_000L)
        NaverTripStartSession.restartFromClick(1_000_000L + NaverTripStartSession.LATE_CLICK_SAME_TRIP_MS + 1)
        assertFalse(NaverTripStartSession.isLateClickRepeat("99번"))
    }

    @Test
    fun `live arm after a briefing is not a late click`() {
        NaverTripStartSession.noteBriefed("99번", 1_000_000L)
        NaverTripStartSession.reset()
        NaverTripStartSession.noteLive(1_005_000L)
        assertFalse(NaverTripStartSession.isLateClickRepeat("99번"))
    }

    @Test
    fun `bus hold lifts after briefing window without consuming stages`() {
        val t0 = 50_000L
        NaverTripStartSession.request(t0)
        NaverTripStartSession.markSpoken(t0 + 4_000)
        assertTrue(NaverTripStartSession.holdBusWait(t0 + 4_000 + 100))
        assertFalse(
            NaverTripStartSession.holdBusWait(
                t0 + 4_000 + NaverTripStartSession.BUS_HOLD_AFTER_SPEAK_MS,
            ),
        )
    }
}
