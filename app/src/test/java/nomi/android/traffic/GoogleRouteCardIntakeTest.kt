package nomi.android.traffic

import nomi.product.nav.InMemoryRouteCardStore
import nomi.product.nav.NavigationEventSource
import nomi.product.nav.RouteCardMode
import nomi.product.nav.RouteCardRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleRouteCardIntakeTest {

    @Test
    fun `preview destination does not create a card`() {
        val repo = repo()
        val google = GoogleRouteCardIntake(repo)
        val preview = screen("목적지, 혜화")
        assertEquals("혜화", GoogleTransitAccessibilityParser.destinationLabel(preview))
        assertFalse(GoogleTransitAccessibilityParser.isLiveGuidance(preview))
        google.onScreen(
            GoogleTransitAccessibilityParser.destinationLabel(preview),
            GoogleTransitAccessibilityParser.isLiveGuidance(preview),
            10L,
        )
        assertTrue(repo.recent().isEmpty())
    }

    @Test
    fun `glance start does not create a card`() {
        val repo = repo()
        val google = GoogleRouteCardIntake(repo)
        val start = screen("목적지, 혜화", "한눈에 보기 시작")
        assertTrue(GoogleTransitAccessibilityParser.isGlanceStartOnly(start))
        assertFalse(GoogleTransitAccessibilityParser.isLiveGuidance(start))
        google.onScreen(
            GoogleTransitAccessibilityParser.destinationLabel(start),
            GoogleTransitAccessibilityParser.isLiveGuidance(start),
            10L,
        )
        assertTrue(repo.recent().isEmpty())
    }

    @Test
    fun `glance end with destination snapshot creates a card`() {
        val repo = repo()
        val google = GoogleRouteCardIntake(repo)
        google.onScreen("혜화", liveGuidance = false, atMillis = 10L)
        val live = screen("이 이동의 경로 한눈에 보기 종료")
        assertTrue(GoogleTransitAccessibilityParser.isLiveGuidance(live))
        google.onScreen(
            GoogleTransitAccessibilityParser.destinationLabel(live),
            GoogleTransitAccessibilityParser.isLiveGuidance(live),
            20L,
        )
        val cards = repo.recent()
        assertEquals(1, cards.size)
        assertEquals(NavigationEventSource.GOOGLE, cards[0].provider)
        assertEquals("혜화", cards[0].destinationName)
        assertEquals(RouteCardMode.TRANSIT, cards[0].mode)
        assertEquals(20L, cards[0].lastUsedAtMillis)
    }

    @Test
    fun `same destination again updates last used`() {
        val repo = repo()
        val google = GoogleRouteCardIntake(repo)
        google.onScreen("혜화", liveGuidance = false, atMillis = 10L)
        google.onScreen(null, liveGuidance = true, atMillis = 20L)
        google.onScreen(null, liveGuidance = false, atMillis = 30L)
        google.onScreen("혜화", liveGuidance = false, atMillis = 40L)
        google.onScreen(null, liveGuidance = true, atMillis = 50L)
        val cards = repo.recent()
        assertEquals(1, cards.size)
        assertEquals("혜화", cards[0].destinationName)
        assertEquals(50L, cards[0].lastUsedAtMillis)
    }

    @Test
    fun `live guidance without destination snapshot does not create a card`() {
        val repo = repo()
        val google = GoogleRouteCardIntake(repo)
        val live = screen(
            "이 이동의 경로 한눈에 보기 종료",
            "2호선, 교대까지 이동, 경로 세부정보를 접으려면 두 번 탭하세요.",
        )
        assertTrue(GoogleTransitAccessibilityParser.isLiveGuidance(live))
        assertNull(GoogleTransitAccessibilityParser.destinationLabel(live))
        google.onScreen(
            GoogleTransitAccessibilityParser.destinationLabel(live),
            GoogleTransitAccessibilityParser.isLiveGuidance(live),
            20L,
        )
        assertTrue(repo.recent().isEmpty())
    }

    private fun repo() = RouteCardRepository(InMemoryRouteCardStore())

    private fun screen(vararg blobs: String) = GoogleTransitAccessibilityParser.Node(
        children = blobs.map { blob ->
            GoogleTransitAccessibilityParser.Node(text = blob, contentDesc = blob)
        },
    )
}
