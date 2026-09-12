package nomi.android.traffic

import nomi.product.nav.NavigationEventSource
import nomi.product.nav.RouteCard
import nomi.product.nav.RouteCardMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteCardMapLinksTest {

    @Test
    fun `naver card opens nmap search with the destination name`() {
        val name = "광닭발 삼송신원점"
        assertEquals(
            "nmap://search?query=${RouteCardMapLinks.encode(name)}&appname=nomi.traffic",
            RouteCardMapLinks.of(card(NavigationEventSource.NAVER, name)),
        )
    }

    @Test
    fun `google card opens maps search with the destination name`() {
        val name = "혜화 서울특별시 종로구 대학로 120"
        assertEquals(
            "geo:0,0?q=${RouteCardMapLinks.encode(name)}",
            RouteCardMapLinks.of(card(NavigationEventSource.GOOGLE, name)),
        )
    }

    @Test
    fun `blank destination is not a link`() {
        assertNull(RouteCardMapLinks.of(card(NavigationEventSource.NAVER, "  ")))
    }

    private fun card(provider: NavigationEventSource, name: String) = RouteCard(
        provider = provider,
        destinationName = name,
        mode = RouteCardMode.TRANSIT,
        lastUsedAtMillis = 1L,
    )
}
