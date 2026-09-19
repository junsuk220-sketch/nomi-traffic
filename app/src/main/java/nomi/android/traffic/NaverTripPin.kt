package nomi.android.traffic

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
            NaverSubwayPin.pin(line)
            busWait.closeForSubwayTrip()
            return "subway=$line"
        }
        busWait.pinBusLine(line)
        return "bus seed=$line"
    }
}
