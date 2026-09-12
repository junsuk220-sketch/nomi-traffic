package nomi.android.traffic

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import nomi.product.nav.NavigationEventSource
import nomi.product.nav.RouteCard

/**
 * Opens the map app that created the card, searching the started destination.
 * Host-only. Does not start guidance.
 */
object RouteCardMapLinks {

    const val APP_NAME = "nomi.traffic"

    fun of(card: RouteCard): String? {
        val name = card.destinationName.trim()
        if (name.isEmpty()) return null
        return when (card.provider) {
            NavigationEventSource.NAVER -> naverSearch(name)
            NavigationEventSource.GOOGLE -> googleSearch(name)
        }
    }

    fun naverSearch(name: String): String =
        "nmap://search?query=${encode(name)}&appname=$APP_NAME"

    fun googleSearch(name: String): String =
        "geo:0,0?q=${encode(name)}"

    internal fun encode(name: String): String =
        URLEncoder.encode(name.trim(), StandardCharsets.UTF_8.name()).replace("+", "%20")
}
