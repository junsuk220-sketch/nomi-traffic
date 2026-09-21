package nomi.android.traffic.observe

import nomi.android.traffic.NaverBusAccessibilityParser
import nomi.android.traffic.NaverMapsTransit
import nomi.android.traffic.NaverNotificationParser
import nomi.android.traffic.NaverSubwayAccessibilityParser
import nomi.product.nav.NavigationEvent
import nomi.product.nav.NavigationEventType

/**
 * Normalizes one Naver 302 or a11y tick into [TransitReading].
 * No stored state. Does not pick a line, speak, pin, or wait.
 *
 * Transfer walk / board-direction parsers are not called here: they gate
 * on "may speak", which is judgment.
 */
object TransitObservation {

    fun fromNotification(snapshot: NaverNotificationParser.Snapshot): TransitReading? {
        val event = NaverNotificationParser.parse(snapshot) ?: return null
        return toReading(ObservationSource.NOTIFICATION, event)
    }

    fun fromScreen(
        packageName: String?,
        root: NaverSubwayAccessibilityParser.Node,
        atMs: Long = 0L,
    ): TransitReading? {
        val bus = NaverBusAccessibilityParser.parse(
            packageName = packageName,
            root = root,
            timestampMillis = atMs,
        ).firstOrNull()
        if (bus != null) return toReading(ObservationSource.SCREEN, bus)
        val subway = NaverSubwayAccessibilityParser.parse(
            packageName = packageName,
            root = root,
            timestampMillis = atMs,
        ).firstOrNull()
        if (subway != null) return toReading(ObservationSource.SCREEN, subway)
        return null
    }

    private fun toReading(
        source: ObservationSource,
        event: NavigationEvent,
    ): TransitReading {
        val arrivals = event.busInfo?.arrivals.orEmpty()
        return TransitReading(
            source = source,
            atMs = event.timestampMillis,
            kind = kindOf(event, arrivals.isNotEmpty()),
            stop = NaverNotificationParser.boardingStop(event.title)
                ?: NaverNotificationParser.boardingStop(event.action),
            rows = arrivals.map { arrival ->
                TransitRow(
                    line = arrival.line,
                    eta = arrival.eta,
                    occupancy = arrival.occupancy,
                    stopsRemaining = arrival.stopsRemaining,
                )
            },
            raw = event.rawText,
        )
    }

    private fun kindOf(event: NavigationEvent, hasRows: Boolean): TransitKind = when {
        event.type == NavigationEventType.WALK -> TransitKind.WALK
        event.rawText == NaverMapsTransit.KIND_SUBWAY -> TransitKind.SUBWAY
        event.channel == NaverMapsTransit.CHANNEL -> TransitKind.SUBWAY
        hasRows -> TransitKind.BUS
        else -> TransitKind.NONE
    }
}
