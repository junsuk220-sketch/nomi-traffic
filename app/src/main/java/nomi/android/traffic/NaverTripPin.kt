package nomi.android.traffic

import nomi.android.traffic.scope.CurrentGuidanceScope
import nomi.product.nav.NavigationEvent

/**
 * What a Naver trip-start event pins. The Speaker and the Voice entry both reach
 * this decision, so it lives here once: a subway first leg pins the line and asks
 * the bus wait to close itself, anything else seeds the bus wait.
 */
internal object NaverTripPin {

    /** @return the log tail for `[NAVER_PIN]`, or null when no line is named. */
    fun apply(event: NavigationEvent, busWait: NaverBusWaitTracker): String? {
        val line = event.busInfo?.arrivals?.firstOrNull()?.line?.trim().orEmpty()
        if (line.isEmpty()) return null
        if (event.rawText == NaverMapsTransit.KIND_SUBWAY) {
            if (LeftoverSubway.againstBusScope(event.rawText)) {
                LeftoverSubway.dropPinIfBusScope()
                val busLine = CurrentGuidanceScope.snapshot().line?.trim().orEmpty()
                if (busLine.isNotEmpty()) {
                    busWait.pinBusLine(busLine)
                    return "leftover_subway_skip bus seed=$busLine"
                }
                return null
            }
            NaverSubwayPin.pin(line)
            busWait.closeForSubwayTrip()
            return "subway=$line"
        }
        busWait.pinBusLine(line)
        return "bus seed=$line"
    }
}
