package nomi.android.traffic

import nomi.product.nav.NavigationEvent

/** Skips the same Naver subway 빠른 하차 / 빠른 환승 cue. */
class NaverSubwayAccessibilityDedup {

    private val spoken = mutableSetOf<String>()

    fun accept(event: NavigationEvent): Boolean {
        val key = "${event.action}|${event.landmark.orEmpty()}|${event.rawText.trim()}"
        if (key == "||") return false
        return spoken.add(key)
    }
}
