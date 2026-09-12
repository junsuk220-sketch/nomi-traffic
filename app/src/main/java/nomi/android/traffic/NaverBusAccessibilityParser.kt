package nomi.android.traffic

import nomi.product.nav.NavigationBusArrival
import nomi.product.nav.NavigationBusInfo
import nomi.product.nav.NavigationEvent
import nomi.product.nav.NavigationEventSource
import nomi.product.nav.NavigationEventType

/**
 * Reads on-screen Naver bus rows during live guidance.
 * Notification 302 often stays on "길안내를 시작합니다" while the sheet
 * already shows `81 / 5분 / 5정류장`.
 */
object NaverBusAccessibilityParser {

    private val busLine = Regex("""^[A-Za-z]?\d{1,4}[A-Za-z]?$""")
    private val minutesOnly = Regex("""^(\d+)\s*분$""")
    private val minutesAndStops = Regex("""^(\d+)\s*분\s*(\d+)\s*정류장""")
    private val stopsOnly = Regex("""^(\d+)\s*정류장$""")
    private val notifyStyle = Regex("""([A-Za-z]?\d{1,4}[A-Za-z]?)\s*\(([^)]+)\)""")
    private val occupancyLabel = Regex("""^(여유|보통|혼잡|매우\s*혼잡)$""")

    fun parse(
        packageName: String?,
        root: NaverSubwayAccessibilityParser.Node,
        timestampMillis: Long = 0L,
    ): List<NavigationEvent> {
        if (packageName != NaverMapNotification.PACKAGE) return emptyList()
        if (!NaverTransitDestinationParser.isLiveGuidance(root)) return emptyList()
        val blobs = flatten(root)
        val arrivals = arrivalsFromRows(blobs).ifEmpty { arrivalsFromNotifyStyle(blobs) }
        val first = arrivals.firstOrNull() ?: return emptyList()
        return listOf(busEvent(first, arrivals, timestampMillis))
    }

    fun formatLog(event: NavigationEvent): String = buildString {
        val arrival = event.busInfo?.arrivals?.firstOrNull()
        appendLine("[NAVER_BUS_A11Y]")
        appendLine("line=${arrival?.line.orEmpty()}")
        appendLine("eta=${arrival?.eta.orEmpty()}")
        appendLine("occupancy=${arrival?.occupancy.orEmpty()}")
        append("stops=${arrival?.stopsRemaining?.toString().orEmpty()}")
    }

    private fun arrivalsFromRows(blobs: List<String>): List<NavigationBusArrival> {
        val out = ArrayList<NavigationBusArrival>()
        var i = 0
        while (i < blobs.size) {
            val line = blobs[i]
            if (!busLine.matches(line)) {
                i += 1
                continue
            }
            val window = rowAfterLine(blobs, i)
            val soon = window.firstOrNull { TransitSoonEta.matches(it) }
            val minutes = window.firstNotNullOfOrNull {
                minutesAndStops.matchEntire(it)?.groupValues?.get(1)
                    ?: minutesOnly.matchEntire(it)?.groupValues?.get(1)
            }
            val hasStops = window.any { stopsOnly.matches(it) || minutesAndStops.matches(it) }
            val occupancy = window.firstOrNull { occupancyLabel.matches(it) }
            val stops = window.firstNotNullOfOrNull { blob ->
                minutesAndStops.matchEntire(blob)?.groupValues?.get(2)?.toIntOrNull()
                    ?: stopsOnly.matchEntire(blob)?.groupValues?.get(1)?.toIntOrNull()
            }
            when {
                soon != null && hasStops ->
                    out.add(NavigationBusArrival(line, soon, occupancy, stops))
                minutes != null && hasStops ->
                    out.add(NavigationBusArrival(line, "${minutes}분", occupancy, stops))
            }
            i += 1
        }
        return out
    }

    private fun rowAfterLine(blobs: List<String>, lineIndex: Int): List<String> {
        val line = blobs[lineIndex]
        var start = lineIndex + 1
        while (start < blobs.size && blobs[start] == line) start += 1
        val endExclusive = minOf(blobs.size, start + 8)
        val slice = blobs.subList(start, endExclusive)
        val nextLine = slice.indexOfFirst { busLine.matches(it) }
        return if (nextLine >= 0) slice.subList(0, nextLine) else slice
    }

    private fun arrivalsFromNotifyStyle(blobs: List<String>): List<NavigationBusArrival> =
        blobs.flatMap { blob ->
            notifyStyle.findAll(blob).map {
                NavigationBusArrival(line = it.groupValues[1], eta = it.groupValues[2].trim())
            }
        }

    private fun flatten(node: NaverSubwayAccessibilityParser.Node): List<String> {
        val out = ArrayList<String>()
        collect(node, out)
        return out
    }

    private fun collect(node: NaverSubwayAccessibilityParser.Node, out: MutableList<String>) {
        NaverSubwayAccessibilityParser.clean(node.text).takeIf { it.isNotEmpty() }?.let(out::add)
        NaverSubwayAccessibilityParser.clean(node.contentDesc).takeIf { it.isNotEmpty() }?.let(out::add)
        node.children.forEach { collect(it, out) }
    }

    private fun busEvent(
        first: NavigationBusArrival,
        arrivals: List<NavigationBusArrival>,
        timestampMillis: Long,
    ): NavigationEvent {
        val raw = arrivals.joinToString(", ") { "${it.line} (${it.eta})" }
        return NavigationEvent(
            source = NavigationEventSource.NAVER,
            type = NavigationEventType.TRANSIT,
            notificationId = 0,
            channel = NaverMapsTransit.BUS_CHANNEL,
            title = "${first.line} ${first.eta}",
            action = "${first.line} ${first.eta}",
            distanceMeters = null,
            rawText = raw,
            busInfo = NavigationBusInfo(raw = raw, arrivals = arrivals),
            timestampMillis = timestampMillis,
        )
    }
}
