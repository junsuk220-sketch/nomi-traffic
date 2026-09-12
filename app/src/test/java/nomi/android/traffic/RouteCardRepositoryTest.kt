package nomi.android.traffic

import nomi.product.nav.FileRouteCardStore
import nomi.product.nav.InMemoryRouteCardStore
import nomi.product.nav.NavigationEventSource
import nomi.product.nav.RouteCard
import nomi.product.nav.RouteCardMode
import nomi.product.nav.RouteCardRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.util.Calendar
import java.util.TimeZone

class RouteCardRepositoryTest {

    @Test
    fun `recent cards are newest first`() {
        val repo = RouteCardRepository(InMemoryRouteCardStore())
        repo.record(NavigationEventSource.NAVER, "서울역", RouteCardMode.TRANSIT, 10L)
        repo.record(NavigationEventSource.GOOGLE, "혜화", RouteCardMode.TRANSIT, 30L)
        repo.record(NavigationEventSource.NAVER, "회사", RouteCardMode.TRANSIT, 20L)
        assertEquals(
            listOf("혜화", "회사", "서울역"),
            repo.recent().map { it.destinationName },
        )
    }

    @Test
    fun `file store keeps last used after reload`() {
        val path = Files.createTempDirectory("nomi-route").resolve("nomi-route-cards-v1.json")
        val first = RouteCardRepository(FileRouteCardStore(path))
        first.record(NavigationEventSource.NAVER, "회사", RouteCardMode.TRANSIT, 20L)
        val reloaded = RouteCardRepository(FileRouteCardStore(path))
        val cards = reloaded.recent()
        assertEquals(1, cards.size)
        assertEquals("회사", cards[0].destinationName)
        assertEquals(20L, cards[0].lastUsedAtMillis)
    }

    @Test
    fun `last used copy matches today yesterday and days ago`() {
        val zone = TimeZone.getTimeZone("Asia/Seoul")
        val calendar = Calendar.getInstance(zone)
        calendar.set(2026, Calendar.SEPTEMBER, 7, 8, 32, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val used = calendar.timeInMillis
        assertEquals("방금 전", RouteCardCopy.lastUsedLabel(used, used + 10_000, zone))
        assertEquals("오늘 08:32", RouteCardCopy.lastUsedLabel(used, used + 120_000, zone))
        calendar.add(Calendar.DAY_OF_MONTH, 1)
        val nextDay = calendar.timeInMillis
        assertEquals("어제", RouteCardCopy.lastUsedLabel(used, nextDay, zone))
        calendar.add(Calendar.DAY_OF_MONTH, 2)
        assertEquals("3일 전", RouteCardCopy.lastUsedLabel(used, calendar.timeInMillis, zone))
        assertEquals(
            "Naver · 대중교통",
            RouteCardCopy.detailLine(
                RouteCard(
                    provider = NavigationEventSource.NAVER,
                    destinationName = "회사",
                    mode = RouteCardMode.TRANSIT,
                    lastUsedAtMillis = used,
                ),
            ),
        )
    }
}
