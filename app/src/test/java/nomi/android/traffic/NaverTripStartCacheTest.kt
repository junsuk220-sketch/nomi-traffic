package nomi.android.traffic

import nomi.product.nav.NavigationBusArrival
import nomi.product.nav.NavigationBusInfo
import nomi.product.nav.NavigationEvent
import nomi.product.nav.NavigationEventSource
import nomi.product.nav.NavigationEventType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NaverTripStartCacheTest {

    @After
    fun clear() {
        NaverTripStartCache.clear()
    }

    @Test
    fun `fresh preview is used`() {
        val seen = 1_000_000L
        NaverTripStartCache.remember(preview("150"), seen)
        assertEquals("150", NaverTripStartCache.peek(seen + 30_000L)?.busInfo?.arrivals?.first()?.line)
        assertEquals("150", NaverTripStartCache.take(seen + 30_000L)?.busInfo?.arrivals?.first()?.line)
        assertNull(NaverTripStartCache.peek(seen + 30_000L))
    }

    @Test
    fun `10-11 preview from 80 minutes ago is not spoken`() {
        // 19:49:47 preview 272 2분; 21:09:51 the old briefing spoke it when Naver was opened.
        val seen = 2_000_000L
        NaverTripStartCache.remember(preview("272"), seen)
        assertNull(NaverTripStartCache.peek(seen + 80 * 60_000L))
        assertNull(NaverTripStartCache.take(seen + 80 * 60_000L))
    }

    @Test
    fun `preview ages out right after the limit`() {
        val seen = 3_000_000L
        NaverTripStartCache.remember(preview("11"), seen)
        assertEquals("11", NaverTripStartCache.peek(seen + NaverTripStartCache.MAX_AGE_MS)?.busInfo?.arrivals?.first()?.line)
        assertNull(NaverTripStartCache.peek(seen + NaverTripStartCache.MAX_AGE_MS + 1))
    }

    private fun preview(line: String) = NavigationEvent(
        source = NavigationEventSource.NAVER,
        type = NavigationEventType.TRANSIT,
        notificationId = 0,
        channel = "a11y",
        title = "",
        action = NaverMapsTransit.TRIP_START_ACTION,
        distanceMeters = null,
        rawText = NaverMapsTransit.KIND_BUS,
        busInfo = NavigationBusInfo(
            raw = "$line 곧 도착",
            arrivals = listOf(NavigationBusArrival(line, "곧 도착")),
        ),
        timestampMillis = 0L,
    )
}
