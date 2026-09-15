package nomi.android.traffic

import nomi.product.nav.NavigationBusArrival

/**
 * Next bus on a wait board: exclude [current], then the soonest remaining
 * candidate. Line identity is not a preference. Current is [line] + [eta].
 */
internal object NaverNextVehicle {

    fun afterSoonest(
        arrivals: List<NavigationBusArrival>,
        current: NavigationBusArrival,
    ): NavigationBusArrival? {
        val remaining = arrivals.filterNot {
            it.line == current.line && it.eta == current.eta
        }
        return remaining.minWithOrNull(soonestOrder)
    }

    private val soonestOrder = compareBy<NavigationBusArrival> {
        TransitSoonEta.minutes(it.eta) ?: Int.MAX_VALUE
    }.thenBy { it.stopsRemaining ?: Int.MAX_VALUE }
}
