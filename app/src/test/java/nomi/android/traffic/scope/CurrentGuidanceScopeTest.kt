package nomi.android.traffic.scope

import nomi.android.traffic.NaverSubwayPin
import nomi.android.traffic.NaverTripStartSession
import nomi.android.traffic.buswait.BusWaitCore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CurrentGuidanceScopeTest {

    @Before
    fun setUp() {
        CurrentGuidanceScope.resetForTest()
        NaverSubwayPin.reset()
        NaverTripStartSession.reset()
    }

    @After
    fun tearDown() {
        CurrentGuidanceScope.resetForTest()
        NaverSubwayPin.reset()
        NaverTripStartSession.reset()
    }

    @Test
    fun `guidance start creates an active scope`() {
        CurrentGuidanceScope.onScreen(
            listOf("안내 중", "도보 약 11분", "3호선", "오금행"),
        )
        val snap = CurrentGuidanceScope.snapshot()
        assertTrue(snap.active)
        assertEquals(1, snap.generation)
        assertEquals(GuidanceKind.SUBWAY, snap.kind)
        assertEquals("3호선", snap.line)
    }

    @Test
    fun `selected card keeps 3호선 and drops 99 and 81`() {
        CurrentGuidanceScope.onScreen(
            listOf(
                "안내 중",
                "55분",
                "3호선",
                "정발산역",
                "오금행",
                "안내 종료",
                "최소시간",
                "53분",
                "도보 2분, 99번 일반 버스 2분",
                "99",
                "2분",
                "7정류장",
                "여유",
                "81",
                "7분",
                "7정류장",
                "여유",
                "바로 안내시작",
            ),
        )
        val snap = CurrentGuidanceScope.snapshot()
        assertTrue(snap.active)
        assertEquals("3호선", snap.line)
        assertEquals(GuidanceKind.SUBWAY, snap.kind)
        assertNotEquals("99", snap.line)
        assertNotEquals("81", snap.line)
    }

    @Test
    fun `HUD prefix before 안내 중 keeps 3호선 and drops later 99 and 81`() {
        CurrentGuidanceScope.onScreen(
            listOf(
                "3호선",
                "오금행",
                "안내 중",
                "바로 안내시작",
                "최소시간",
                "99",
                "2분",
                "7정류장",
                "81",
                "7분",
                "7정류장",
            ),
        )
        val snap = CurrentGuidanceScope.snapshot()
        assertTrue(snap.active)
        assertEquals("3호선", snap.line)
        assertEquals(GuidanceKind.SUBWAY, snap.kind)
    }

    @Test
    fun `alternative cards before 안내 중 stay out of scope`() {
        CurrentGuidanceScope.onScreen(
            listOf(
                "최소시간",
                "53분",
                "99",
                "2분",
                "7정류장",
                "여유",
                "81",
                "7분",
                "7정류장",
                "바로 안내시작",
                "안내 중",
                "55분",
                "3호선",
                "오금행",
                "안내 종료",
            ),
        )
        val snap = CurrentGuidanceScope.snapshot()
        assertEquals("3호선", snap.line)
        assertEquals(GuidanceKind.SUBWAY, snap.kind)
    }

    @Test
    fun `end phrase closes only this scope`() {
        CurrentGuidanceScope.onScreen(listOf("안내 중", "3호선"))
        assertTrue(CurrentGuidanceScope.snapshot().active)
        CurrentGuidanceScope.onScreen(listOf("길안내를 종료합니다."))
        val snap = CurrentGuidanceScope.snapshot()
        assertFalse(snap.active)
        assertNull(snap.line)
        assertEquals(GuidanceKind.NONE, snap.kind)
        assertEquals(1, snap.generation)
    }

    @Test
    fun `live card button 안내 종료 does not end the scope`() {
        CurrentGuidanceScope.onScreen(
            listOf("안내 중", "3호선", "안내 종료"),
        )
        val snap = CurrentGuidanceScope.snapshot()
        assertTrue(snap.active)
        assertEquals("3호선", snap.line)
    }

    @Test
    fun `scope does not change subway pin or trip-start session or bus core`() {
        NaverSubwayPin.pin("6호선")
        val core = BusWaitCore()
        core.seed("81")
        assertFalse(NaverTripStartSession.hasSpokenBriefing())
        assertFalse(NaverTripStartSession.due(0L))

        CurrentGuidanceScope.onScreen(
            listOf("안내 중", "3호선", "안내 종료", "99", "2분", "7정류장"),
        )

        assertEquals("6호선", NaverSubwayPin.pinned())
        assertEquals(setOf("81"), core.pinnedLines())
        assertFalse(NaverTripStartSession.hasSpokenBriefing())
        assertFalse(NaverTripStartSession.due(System.currentTimeMillis()))
        assertEquals("3호선", CurrentGuidanceScope.snapshot().line)
    }

    @Test
    fun `snapshot is a detached copy`() {
        CurrentGuidanceScope.onScreen(listOf("안내 중", "3호선"))
        val first = CurrentGuidanceScope.snapshot()
        val second = CurrentGuidanceScope.snapshot()
        assertEquals(first, second)
        assertNotSame(first, second)
        CurrentGuidanceScope.onScreen(listOf("길안내를 종료합니다"))
        val after = CurrentGuidanceScope.snapshot()
        assertTrue(first.active)
        assertEquals("3호선", first.line)
        assertFalse(after.active)
        assertNull(after.line)
    }

    @Test
    fun `notification start phrase can open scope without mixing a leftover bus board`() {
        CurrentGuidanceScope.onNotification(
            title = "길안내를 시작합니다.",
            text = "독바위역 6호선까지 이동",
        )
        val snap = CurrentGuidanceScope.snapshot()
        assertTrue(snap.active)
        assertEquals(GuidanceKind.SUBWAY, snap.kind)
        assertEquals("6호선", snap.line)
    }

    @Test
    fun `bus wait board without live or start phrase does not open scope`() {
        CurrentGuidanceScope.onNotification(
            title = "라페스타.먹자골목까지 걷기",
            text = "99 (2분), 81 (7분)",
        )
        val snap = CurrentGuidanceScope.snapshot()
        assertFalse(snap.active)
        assertNull(snap.line)
        assertEquals(0, snap.generation)
    }
}
