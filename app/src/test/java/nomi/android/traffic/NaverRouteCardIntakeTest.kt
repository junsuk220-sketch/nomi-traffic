package nomi.android.traffic

import nomi.product.nav.InMemoryRouteCardStore
import nomi.product.nav.NavigationEventSource
import nomi.product.nav.RouteCardMode
import nomi.product.nav.RouteCardRepository
import org.junit.Assert.assertEquals
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

    private fun repo() = RouteCardRepository(InMemoryRouteCardStore())
}
