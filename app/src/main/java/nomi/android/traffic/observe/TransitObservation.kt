package nomi.android.traffic.observe

import nomi.android.traffic.NaverBusAccessibilityParser
import nomi.android.traffic.NaverMapNotification
import nomi.android.traffic.NaverMapsTransit
import nomi.android.traffic.NaverNotificationParser
import nomi.android.traffic.NaverSubwayAccessibilityParser
import nomi.android.traffic.NaverTransitDestinationParser
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
        if (packageName != NaverMapNotification.PACKAGE) return null
        if (!NaverTransitDestinationParser.isLiveGuidance(root)) return null
        val blobs = flatten(root)
        val scoped = liveScreenFacts(blobs)
        val arrivals = NaverBusAccessibilityParser.arrivalsFromBlobs(scoped)
        if (arrivals.isNotEmpty()) {
            return TransitReading(
                source = ObservationSource.SCREEN,
                atMs = atMs,
                kind = TransitKind.BUS,
                stop = stopOf(scoped),
                rows = arrivals.map { arrival ->
                    TransitRow(
                        line = arrival.line,
                        eta = arrival.eta,
                        occupancy = arrival.occupancy,
                        stopsRemaining = arrival.stopsRemaining,
                    )
                },
                raw = arrivals.joinToString(", ") { "${it.line} (${it.eta})" },
                walkMinutes = walkMinutesOf(scoped),
            )
        }
        val subway = NaverSubwayAccessibilityParser.parse(
            packageName = packageName,
            root = root,
            timestampMillis = atMs,
        ).firstOrNull()
        if (subway != null) {
            return toReading(ObservationSource.SCREEN, subway)
                .copy(walkMinutes = walkMinutesOf(scoped.ifEmpty { blobs }))
        }
        return null
    }

    /**
     * Live HUD facts only. Includes the boarded vehicle printed above
     * `안내 중` when that prefix is not an alt card. Does not require
     * `안내 종료`. Cuts at the first alt boundary.
     */
    private fun liveScreenFacts(blobs: List<String>): List<String> {
        val liveIdx = blobs.indexOfFirst { it == "안내 중" || it == "안내중" }
        if (liveIdx < 0) return emptyList()
        val cardEnd = blobs.indices.firstOrNull { it > liveIdx && isCardEnd(blobs[it]) }
        val altAfter = blobs.indices.firstOrNull { it > liveIdx && isAltBoundary(blobs[it]) }
        val until = cardEnd?.plus(1) ?: (altAfter ?: blobs.size)
        val from = if (includeHudPrefix(blobs, liveIdx)) 0 else liveIdx
        return blobs.subList(from, until)
    }

    private fun includeHudPrefix(blobs: List<String>, liveIdx: Int): Boolean {
        val prefix = blobs.subList(0, liveIdx)
        if (prefix.any { isAltBoundary(it) }) return false
        for (i in 0 until liveIdx) {
            if (isBusLine(blobs[i]) && busWaitFollows(blobs, i)) return true
        }
        return false
    }

    private fun stopOf(blobs: List<String>): String? {
        for (blob in blobs) {
            NaverNotificationParser.boardingStop(blob)?.let { return it }
            if (blob.endsWith(" 승차")) {
                val stop = blob.removeSuffix(" 승차").trim()
                if (stop.isNotEmpty()) return stop
            }
        }
        return null
    }

    private fun flatten(root: NaverSubwayAccessibilityParser.Node): List<String> {
        val out = ArrayList<String>()
        collect(root, out)
        return out
    }

    private fun collect(node: NaverSubwayAccessibilityParser.Node, out: MutableList<String>) {
        NaverSubwayAccessibilityParser.clean(node.text).takeIf { it.isNotEmpty() }?.let(out::add)
        NaverSubwayAccessibilityParser.clean(node.contentDesc).takeIf { it.isNotEmpty() }?.let(out::add)
        node.children.forEach { collect(it, out) }
    }

    private fun busWaitFollows(blobs: List<String>, lineIndex: Int): Boolean {
        val window = blobs.subList(lineIndex + 1, minOf(blobs.size, lineIndex + 8))
        return window.any { stopsOnly.matches(it) || minutesAndStops.matches(it) }
    }

    private fun isBusLine(blob: String): Boolean = busLine.matches(blob)

    private fun isCardEnd(blob: String): Boolean =
        blob == "안내 종료" || blob == "안내종료"

    private fun isAltBoundary(blob: String): Boolean =
        blob == "바로 안내시작" || blob == "최소시간" || blob == "최소환승"

    private val busLine = Regex("""^[A-Za-z]?\d{1,4}[A-Za-z]?$""")
    private val minutesAndStops = Regex("""^\d+\s*분\s*\d+\s*정류장""")
    private val stopsOnly = Regex("""^\d+\s*정류장$""")
    private val walkAbout = Regex("""도보\s*약\s*(\d+)\s*분""")
    private val walkMinutes = Regex("""도보\s*(\d+)\s*분""")
    private val walkMetersMinutes =
        Regex("""도보\s*\d+\s*m\s*[·•.\-]?\s*(\d+)\s*분""", RegexOption.IGNORE_CASE)
    private val walkMetersOnly = Regex("""도보\s*\d+\s*m""", RegexOption.IGNORE_CASE)
    private val bareMinutes = Regex("""^(\d+)\s*분$""")

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
            walkMinutes = walkMinutesOf(
                listOfNotNull(event.title, event.action, event.rawText),
            ),
        )
    }

    private fun walkMinutesOf(blobs: List<String>): Int? {
        for (blob in blobs) {
            walkAbout.find(blob)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        }
        for (blob in blobs) {
            walkMetersMinutes.find(blob)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        }
        for (blob in blobs) {
            walkMinutes.find(blob)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        }
        for (i in blobs.indices) {
            if (!walkMetersOnly.containsMatchIn(blobs[i])) continue
            for (j in (i + 1)..minOf(i + 4, blobs.lastIndex)) {
                bareMinutes.matchEntire(blobs[j])?.groupValues?.get(1)
                    ?.toIntOrNull()
                    ?.takeIf { it in 1..120 }
                    ?.let { return it }
            }
        }
        return null
    }

    private fun kindOf(event: NavigationEvent, hasRows: Boolean): TransitKind = when {
        event.type == NavigationEventType.WALK -> TransitKind.WALK
        event.rawText == NaverMapsTransit.KIND_SUBWAY -> TransitKind.SUBWAY
        event.channel == NaverMapsTransit.CHANNEL -> TransitKind.SUBWAY
        hasRows -> TransitKind.BUS
        else -> TransitKind.NONE
    }
}
