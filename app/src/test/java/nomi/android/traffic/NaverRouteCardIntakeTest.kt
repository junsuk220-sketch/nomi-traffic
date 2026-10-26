package nomi.android.traffic

import nomi.product.nav.InMemoryRouteCardStore
import nomi.product.nav.NavigationEventSource
import nomi.product.nav.RouteCardMode
import nomi.product.nav.RouteCardRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NaverRouteCardIntakeTest {

    @Test
    fun `search only does not create a card`() {
        val repo = repo()
        val naver = NaverRouteCardIntake(repo)
        naver.onScreen("회사", liveGuidance = false, atMillis = 10L)
        assertTrue(repo.recent().isEmpty())
    }

    @Test
    fun `live hud creates a card without notification`() {
        val repo = repo()
        val naver = NaverRouteCardIntake(repo)
        naver.onScreen("원흥역", liveGuidance = true, atMillis = 20L)
        val cards = repo.recent()
        assertEquals(1, cards.size)
        assertEquals(NavigationEventSource.NAVER, cards[0].provider)
        assertEquals("원흥역", cards[0].destinationName)
        assertEquals(RouteCardMode.TRANSIT, cards[0].mode)
        assertEquals(20L, cards[0].lastUsedAtMillis)
    }

    @Test
    fun `guidance start notification creates a card`() {
        val repo = repo()
        val naver = NaverRouteCardIntake(repo)
        naver.onScreen("회사", liveGuidance = false, atMillis = 10L)
        naver.onNotificationPosted(302, 20L)
        val cards = repo.recent()
        assertEquals(1, cards.size)
        assertEquals(NavigationEventSource.NAVER, cards[0].provider)
        assertEquals("회사", cards[0].destinationName)
        assertEquals(RouteCardMode.TRANSIT, cards[0].mode)
        assertEquals(20L, cards[0].lastUsedAtMillis)
    }

    @Test
    fun `same destination again keeps one card and updates last used`() {
        val repo = repo()
        val naver = NaverRouteCardIntake(repo)
        naver.onScreen("원흥역", liveGuidance = true, atMillis = 20L)
        naver.onScreen(null, liveGuidance = false, atMillis = 25L)
        naver.onNotificationRemoved(302)
        naver.onScreen("원흥역", liveGuidance = true, atMillis = 40L)
        val cards = repo.recent()
        assertEquals(1, cards.size)
        assertEquals("원흥역", cards[0].destinationName)
        assertEquals(40L, cards[0].lastUsedAtMillis)
    }

    @Test
    fun `other destination creates a new card`() {
        val repo = repo()
        val naver = NaverRouteCardIntake(repo)
        naver.onScreen("원흥역", liveGuidance = true, atMillis = 20L)
        naver.onScreen(null, liveGuidance = false, atMillis = 25L)
        naver.onNotificationRemoved(1)
        naver.onScreen("회사", liveGuidance = true, atMillis = 40L)
        val names = repo.recent().map { it.destinationName }
        assertEquals(listOf("회사", "원흥역"), names)
    }

    @Test
    fun `naver and google same destination stay separate cards`() {
        val repo = repo()
        val naver = NaverRouteCardIntake(repo)
        val google = GoogleRouteCardIntake(repo)
        naver.onScreen("원흥역", liveGuidance = true, atMillis = 20L)
        google.onScreen("원흥역", liveGuidance = false, atMillis = 30L)
        google.onScreen(null, liveGuidance = true, atMillis = 40L)
        val cards = repo.recent()
        assertEquals(2, cards.size)
        assertEquals(setOf(NavigationEventSource.NAVER, NavigationEventSource.GOOGLE), cards.map { it.provider }.toSet())
        assertTrue(cards.all { it.destinationName == "원흥역" })
        assertEquals(40L, cards[0].lastUsedAtMillis)
        assertEquals(20L, cards[1].lastUsedAtMillis)
    }

    @Test
    fun `notification without destination does not create a card`() {
        val repo = repo()
        val naver = NaverRouteCardIntake(repo)
        naver.onNotificationPosted(302, 20L)
        assertTrue(repo.recent().isEmpty())
        naver.onScreen("회사", liveGuidance = false, atMillis = 30L)
        val cards = repo.recent()
        assertEquals(1, cards.size)
        assertEquals("회사", cards[0].destinationName)
        assertEquals(20L, cards[0].lastUsedAtMillis)
    }

    @Test
    fun `notification until-move daehwa creates a card`() {
        val repo = repo()
        val naver = NaverRouteCardIntake(repo)
        naver.onNotificationPosted(
            302,
            20L,
            listOf("대화역 3호선까지 이동", "길안내를 시작합니다"),
        )
        val cards = repo.recent()
        assertEquals(1, cards.size)
        assertEquals(NavigationEventSource.NAVER, cards[0].provider)
        assertEquals("대화역", cards[0].destinationName)
        assertEquals(RouteCardMode.TRANSIT, cards[0].mode)
        assertEquals(20L, cards[0].lastUsedAtMillis)
    }

    @Test
    fun `notification until-move wonheung creates a card`() {
        val repo = repo()
        val naver = NaverRouteCardIntake(repo)
        naver.onNotificationPosted(302, 20L, listOf("원흥역 3호선까지 이동"))
        assertEquals(listOf("원흥역"), repo.recent().map { it.destinationName })
    }

    @Test
    fun `walk-to-stop 302 does not create a destination card`() {
        val repo = repo()
        val naver = NaverRouteCardIntake(repo)
        naver.onNotificationPosted(
            302,
            20L,
            listOf("일산동부경찰서(중)까지 걷기", "150 (곧 도착), 2000 (3분)"),
        )
        assertTrue(repo.recent().isEmpty())
    }

    @Test
    fun `subway walk-to-stop 302 does not create a destination card`() {
        val repo = repo()
        val naver = NaverRouteCardIntake(repo)
        naver.onNotificationPosted(302, 20L, listOf("정발산역 3호선까지 걷기", "오금행 (16:29)"))
        assertTrue(repo.recent().isEmpty())
    }

    @Test
    fun `plain 302 without destination pattern does not create a card`() {
        val repo = repo()
        val naver = NaverRouteCardIntake(repo)
        naver.onNotificationPosted(302, 20L, listOf("길안내를 시작합니다", "98 (곧 도착), 67 (2분)"))
        assertTrue(repo.recent().isEmpty())
    }

    @Test
    fun `until-walk mixed with until-move still uses final destination`() {
        val repo = repo()
        val naver = NaverRouteCardIntake(repo)
        naver.onNotificationPosted(
            301,
            20L,
            listOf("일산동부경찰서(중)까지 걷기", "대화역 3호선까지 이동"),
        )
        assertEquals(listOf("대화역"), repo.recent().map { it.destinationName })
    }

    @Test
    fun `live card lists every boarding after the first`() {
        val legs = nomi.android.traffic.api.routelegs.RouteLegs
        legs.resetForTest()
        legs.observe(
            listOf(
                "1시간 27분", "경로 고정",
                "도보 2분, 81번 일반 버스 16분, 도보 4분, 서해선 24분, 도보 4분, 7호선 1분, 도보 4분, 661번 간선 버스 21분, 도보 1분",
                "2분", "16분", "일반", "라페스타.먹자골목", "81", "12분", "10정류장", "여유",
                "도착정보", "도착정보 버튼", "서해", "백마역", "7호선", "부천종합운동장역",
                "간선", "춘의역6번출구", "661", "하차", "법원.검찰청", "바로 안내시작",
            ),
            live = false,
            nowMs = 1_000L,
        )
        legs.observe(listOf("길안내를 시작합니다."), live = false, nowMs = 2_000L)
        legs.observe(
            listOf("도보 188m", "2분", "라페스타.먹자골목 승차", "58117", "81", "1시간 27분", "오후 4:30 도착"),
            live = true,
            nowMs = 3_000L,
        )
        val repo = repo()
        val naver = NaverRouteCardIntake(repo)
        naver.onScreen("인천지방법원 부천지원", liveGuidance = true, atMillis = 30L)
        val cards = repo.recent()
        assertEquals(1, cards.size)
        assertEquals(
            "서해 백마역 · 7호선 부천종합운동장역 · 661번 춘의역6번출구",
            cards[0].transferNote,
        )
        legs.resetForTest()
    }

    @Test
    fun `running route replaces a stale transfer note`() {
        val repo = repo()
        repo.record(
            provider = NavigationEventSource.NAVER,
            destinationName = "인천지방법원 부천지원",
            mode = RouteCardMode.TRANSIT,
            atMillis = 1L,
            transferNote = "3호선 정발산역",
        )
        val legs = nomi.android.traffic.api.routelegs.RouteLegs
        legs.resetForTest()
        val naver = NaverRouteCardIntake(repo)
        naver.onScreen("인천지방법원 부천지원", liveGuidance = true, atMillis = 10L)
        assertEquals("3호선 정발산역", repo.recent().single().transferNote)
        legs.observe(
            listOf("안내 중", "1시간 19분", "경로 고정") +
                listOf("도보 11분, 3호선 7분, 도보 3분, 경의중앙선 2분, 도보 1분, 서해선 14분, 도보 2분, 8번 일반 버스 19분, 도보 7분") +
                listOf("3호선", "정발산역", "경의중앙", "대곡역", "서해", "능곡역", "일반", "부천종합운동장역5번출구.부천FC", "8", "하차", "솔안공원", "안내 종료"),
            live = true,
            nowMs = 2_000L,
        )
        naver.onScreen("인천지방법원 부천지원", liveGuidance = true, atMillis = 20L)
        assertEquals(
            "경의중앙 대곡역 · 서해 능곡역 · 8번 부천종합운동장역5번출구.부천FC",
            repo.recent().single().transferNote,
        )
        legs.resetForTest()
    }

    @Test
    fun `a new destination does not inherit the card below`() {
        val legs = nomi.android.traffic.api.routelegs.RouteLegs
        legs.resetForTest()
        legs.observe(
            listOf("안내 중", "1시간 19분", "경로 고정") +
                listOf("도보 11분, 3호선 7분, 도보 3분, 경의중앙선 2분, 도보 1분, 서해선 14분, 도보 2분, 8번 일반 버스 19분, 도보 7분") +
                listOf("3호선", "정발산역", "경의중앙", "대곡역", "서해", "능곡역", "일반", "부천종합운동장역5번출구.부천FC", "8", "하차", "솔안공원", "안내 종료"),
            live = true,
            nowMs = 1_000L,
        )
        val repo = repo()
        val naver = NaverRouteCardIntake(repo)
        naver.onScreen("인천지방법원", liveGuidance = true, atMillis = 10L)
        naver.onNotificationPosted(
            302,
            20L,
            listOf("인천지방법원 부천지원까지 이동", "길안내를 시작합니다"),
        )
        val court = repo.recent().first { it.destinationName == "인천지방법원" }
        val branch = repo.recent().first { it.destinationName == "인천지방법원 부천지원" }
        assertEquals("경의중앙 대곡역 · 서해 능곡역 · 8번 부천종합운동장역5번출구.부천FC", court.transferNote)
        assertNull(branch.transferNote)
        legs.observe(
            listOf(
                "안내 중", "1시간 56분", "경로 고정",
                "도보 11분, 3호선 7분, 도보 5분, 서해선 21분, 도보 3분, 1호선 25분, 도보 5분, 4번 간선 버스 14분, 도보 6분",
                "3호선", "정발산역", "서해", "대곡역", "1호선", "소사역",
                "간선", "제물포역", "4", "하차", "학익시장(법원검찰청)", "안내 종료",
            ),
            live = true,
            nowMs = 3_000L,
        )
        naver.onScreen("인천지방법원 부천지원", liveGuidance = true, atMillis = 30L)
        assertEquals(
            "경의중앙 대곡역 · 서해 능곡역 · 8번 부천종합운동장역5번출구.부천FC",
            repo.recent().first { it.destinationName == "인천지방법원" }.transferNote,
        )
        assertEquals(
            "서해 대곡역 · 1호선 소사역 · 4번 제물포역",
            repo.recent().first { it.destinationName == "인천지방법원 부천지원" }.transferNote,
        )
        legs.resetForTest()
    }

    /** 2026-09-28 15:43. The destination notification arrived after the next screen. */
    @Test
    fun `destination arriving later still gets every transfer`() {
        val legs = nomi.android.traffic.api.routelegs.RouteLegs
        legs.resetForTest()
        val repo = repo()
        repo.record(
            provider = NavigationEventSource.NAVER,
            destinationName = "인천지방법원",
            mode = RouteCardMode.TRANSIT,
            atMillis = 1L,
            transferNote = "서해선 대곡역",
        )
        legs.observe(
            listOf(
                "1시간 56분", "경로 고정",
                "도보 11분, 3호선 7분, 도보 5분, 서해선 21분, 도보 3분, 1호선 25분, 도보 5분, 4번 간선 버스 14분, 도보 6분",
                "3호선", "정발산역", "서해", "대곡역", "1호선", "소사역",
                "간선", "제물포역", "4", "하차", "학익시장(법원검찰청)", "바로 안내시작",
            ),
            live = false,
            nowMs = 1_000L,
        )
        legs.observe(listOf("길안내를 시작합니다."), live = false, nowMs = 2_000L)
        legs.observe(
            listOf("3호선 정발산역 승차", "대곡역 하차", "1시간 56분", "오후 6:05 도착", "안내 중"),
            live = true,
            nowMs = 3_000L,
        )
        legs.observe(
            listOf("3호선 정발산역 승차", "대곡역 하차", "안내 중"),
            live = true,
            nowMs = 4_000L,
        )
        NaverRouteCardIntake(repo).onNotificationPosted(
            302,
            5_000L,
            listOf("길안내를 시작합니다.", "인천지방법원까지 이동"),
        )
        assertEquals(
            "서해 대곡역 · 1호선 소사역 · 4번 제물포역",
            repo.recent().single().transferNote,
        )
        legs.resetForTest()
    }

    @Test
    fun `10-09 a ride stop list is not a new destination`() {
        val repo = cleanRepo()
        val naver = NaverRouteCardIntake(repo)
        naver.onNotificationPosted(301, 10L, listOf("길안내를 시작합니다.", "심쿵아라가자까지 이동"))
        naver.onScreen("아라역3번출구", liveGuidance = false, atMillis = 20L)
        naver.noteTransfer("941번 킨텍스역1번출구.현대모터스튜디오고양", 30L)

        assertEquals(listOf("심쿵아라가자"), repo.recent().map { it.destinationName })
        assertEquals("941번 킨텍스역1번출구.현대모터스튜디오고양", repo.recent().single().transferNote)
    }

    @Test
    fun `10-09 a new guidance drops an older trip's note and takes its own`() {
        val repo = cleanRepo()
        repo.record(
            provider = NavigationEventSource.NAVER,
            destinationName = "우림보보카운티2",
            mode = RouteCardMode.TRANSIT,
            atMillis = 1L,
            transferNote = "M7412번 신사역 중앙",
        )
        val naver = NaverRouteCardIntake(repo)
        naver.onNotificationPosted(301, 10L, listOf("길안내를 시작합니다.", "우림보보카운티2까지 이동"))
        assertNull(repo.recent().single().transferNote)

        // 안내 중 off screen before the route is picked: the card must still take it.
        naver.onScreen(null, liveGuidance = false, atMillis = 20L)
        naver.noteTransfer("96번 조리미입구", 30L)
        assertEquals("96번 조리미입구", repo.recent().single().transferNote)
    }

    @Test
    fun `restart to the same place keeps the first pick until the guidance ends`() {
        val repo = cleanRepo()
        val naver = NaverRouteCardIntake(repo)
        val start = listOf("길안내를 시작합니다.", "심쿵아라가자까지 이동")
        naver.onNotificationPosted(301, 10L, start)
        naver.noteTransfer("941번 킨텍스역1번출구.현대모터스튜디오고양", 20L)
        naver.onNotificationPosted(301, 30L, start)
        assertEquals("941번 킨텍스역1번출구.현대모터스튜디오고양", repo.recent().single().transferNote)

        naver.onNotificationPosted(302, 40L, listOf("길안내를 종료합니다.", "심쿵아라가자 주변 도착"))
        naver.onNotificationRemoved(301)
        naver.onNotificationPosted(301, 50L, start)
        assertNull(repo.recent().single().transferNote)
    }

    private fun repo() = RouteCardRepository(InMemoryRouteCardStore())

    private fun cleanRepo(): RouteCardRepository {
        nomi.android.traffic.api.routelegs.RouteLegs.resetForTest()
        return repo()
    }
}
