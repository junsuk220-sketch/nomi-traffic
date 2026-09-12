package nomi.android.traffic

import nomi.product.nav.NavigationBusArrival

/**
 * Second vehicle on a wait board for the start briefing.
 * Prefer another line at the same stop
 * (98 곧 1정류장 → 2000 3분 1정류장, not 98 5분).
 * If the board is only this line, keep the later same-number bus
 * (88B 5분 → 88B 11분). Subway always keeps the later same line.
 */
internal object NaverNextVehicle {

    fun afterSoonest(
        arrivals: List<NavigationBusArrival>,
        sameLine: Boolean = false,
    ): NavigationBusArrival? {
        if (arrivals.size < 2) return null
        val ordered = arrivals.sortedWith(soonestOrder)
        val first = ordered.first()
        val others = ordered.filter { it.line != first.line }
        if (!sameLine) {
            val firstStops = first.stopsRemaining
            if (firstStops != null) {
                others.filter { it.stopsRemaining == firstStops }
                    .minWithOrNull(soonestOrder)
                    ?.let { return it }
            }
            others.minWithOrNull(soonestOrder)?.let { return it }
        }
        val firstMin = TransitSoonEta.minutes(first.eta) ?: 0
        return ordered.drop(1).firstOrNull {
            it.line == first.line &&
                (TransitSoonEta.minutes(it.eta) ?: Int.MAX_VALUE) > firstMin
        } ?: others.minWithOrNull(soonestOrder)
    }

    private val soonestOrder = compareBy<NavigationBusArrival> {
        TransitSoonEta.minutes(it.eta) ?: Int.MAX_VALUE
    }.thenBy { it.stopsRemaining ?: Int.MAX_VALUE }
}
