package nomi.android.traffic

import nomi.product.nav.NavigationEvent

/** Skips the same Google transit bus pair, passage cue, alight warning, or trip-start briefing. */
class GoogleTransitAccessibilityDedup {

    private var lastBus: String? = null
    private val lastPassages = mutableSetOf<String>()
    private val lastAlights = mutableSetOf<String>()
    private var tripStartKey: String? = null

    fun accept(event: NavigationEvent): Boolean {
        if (event.action == GoogleMapsTransit.TRIP_START_ACTION) {
            val arrival = event.busInfo?.arrivals?.firstOrNull()
            val key = "${arrival?.line.orEmpty()}|${arrival?.eta.orEmpty()}|${event.distanceMeters}"
            if (key == tripStartKey) return false
            tripStartKey = key
            return true
        }
        if (event.action == GoogleMapsTransit.BOARD_DIRECTION_ACTION) {
            val key = "${event.title.trim()}|${event.landmark.orEmpty()}"
            if (key == "|") return false
            return lastPassages.add("board|$key")
        }
        if (event.action == GoogleMapsTransit.ALIGHT_ACTION ||
            event.action == GoogleMapsTransit.PREPARE_ALIGHT_ACTION
        ) {
            val key = "${event.action}|${event.landmark.orEmpty()}|${event.rawText.trim()}"
            if (key == "||") return false
            return lastAlights.add(key)
        }
        val arrival = event.busInfo?.arrivals?.firstOrNull()
        if (arrival != null) {
            val key = "${arrival.line}|${arrival.eta}"
            if (key == lastBus) return false
            lastBus = key
            return true
        }
        val cue = event.action.trim()
        if (cue.isEmpty()) return false
        return lastPassages.add(cue)
    }

    fun resetTripStart() {
        tripStartKey = null
    }
}
