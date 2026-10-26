package nomi.android.traffic.api.google.brief

import nomi.android.traffic.GoogleMapsTransit
import nomi.android.traffic.GoogleTransitAccessibilityParser
import nomi.android.traffic.NavigationEventSpeech
import nomi.android.traffic.api.google.read.GoogleStep
import nomi.product.nav.NavigationBusArrival
import nomi.product.nav.NavigationBusInfo
import nomi.product.nav.NavigationEvent
import nomi.product.nav.NavigationEventSource
import nomi.product.nav.NavigationEventType
import java.util.Calendar

/**
 * Remembers the opened route screen before guidance starts. Google removes
 * "도보 약 N분" once guidance is live, so the live screen is never read.
 * One sentence when guidance turns on. A later flicker does not repeat it.
 */
object GoogleStartBrief {

    private val clock = Regex("""(오전|오후)\s*(\d{1,2}):(\d{2})""")
    private val minuteAfter = Regex("""(\d+)\s*분\s*\(\s*(?:실시간|예정|정시)\s*\)\s*후""")
    private val departClock = Regex("""^(오전|오후)\s*\d{1,2}:\d{2}에 출발$""")
    private val legSummary = Regex("""^(도보|버스|지하철|기차|열차)(?:\s*\d+분후|,\s)""")
    private val lineLabel = Regex("""^\u200b+([^\u200b\s]+)\u200b*$""")

    private var wasLive = false
    private var spoken = false
    private var remembered: GoogleTransitAccessibilityParser.Node? = null

    @Synchronized
    fun onStartClick() {
        spoken = false
    }

    @Synchronized
    fun onScreen(root: GoogleTransitAccessibilityParser.Node, live: Boolean, nowMillis: Long): String? {
        val becameLive = live && !wasLive
        wasLive = live
        if (!live) {
            // The route list shows every option; its first row is not the chosen route.
            if (!isRouteList(root) && sentence(root, nowMillis) != null) remembered = root
            return null
        }
        if (!becameLive || spoken) return null
        val saved = remembered ?: return null
        val text = sentence(saved, nowMillis) ?: return null
        spoken = true
        return text
    }

    private fun sentence(root: GoogleTransitAccessibilityParser.Node, nowMillis: Long): String? {
        val decision = GoogleTransitAccessibilityParser.tripStartDecision(
            packageName = GoogleMapsTransit.PACKAGE,
            root = root,
            timestampMillis = nowMillis,
        )
        val blobs = flatten(root)
        val event = (decision as? GoogleTransitAccessibilityParser.TripStartDecision.Speak)?.event
            ?: boardHereEvent(blobs, nowMillis)
            ?: return null
        val text = NavigationEventSpeech.line(event) ?: return null
        val first = Regex("""(\d+)분 후""").find(text)?.groupValues?.get(1)?.toIntOrNull()
        val next = nextMinutes(blobs, nowMillis, first) ?: return text
        val vehicle = if (event.rawText == GoogleMapsTransit.KIND_SUBWAY) "열차" else "버스"
        return "$text 다음 ${vehicle}는 ${next}분 후입니다."
    }

    private fun isRouteList(root: GoogleTransitAccessibilityParser.Node): Boolean =
        flatten(root).any { it.contains("소요 시간:") || it.contains("대중교통 모드:") }

    /**
     * A route that starts on board at the stop has no "도보 약 N분" before the
     * first vehicle, so the trip-start parser gives nothing. The boarded line is
     * the label just above the first departure clock.
     */
    private fun boardHereEvent(blobs: List<String>, nowMillis: Long): NavigationEvent? {
        if (blobs.any { it.contains("도보 약") }) return null
        val summary = blobs.firstOrNull { legSummary.containsMatchIn(it.trim()) } ?: return null
        val mode = legSummary.find(summary.trim())?.groupValues?.get(1) ?: return null
        if (mode == "도보") return null
        val depart = blobs.indexOfFirst { departClock.matches(it.trim()) }
        if (depart < 0) return null
        val line = (depart - 1 downTo 0).firstNotNullOfOrNull { i ->
            lineLabel.matchEntire(blobs[i].trim('\n', ' '))?.groupValues?.get(1)
        } ?: return null
        val minutes = clockMinutes(blobs[depart], nowMillis) ?: return null
        if (minutes < 1) return null
        val kind = if (mode == "버스") GoogleMapsTransit.KIND_BUS else GoogleMapsTransit.KIND_SUBWAY
        return NavigationEvent(
            source = NavigationEventSource.GOOGLE,
            type = NavigationEventType.TRANSIT,
            notificationId = 0,
            channel = GoogleMapsTransit.CHANNEL,
            title = "",
            action = GoogleMapsTransit.TRIP_START_ACTION,
            distanceMeters = null,
            rawText = kind,
            busInfo = NavigationBusInfo(
                raw = "$line ${minutes}분 board_here",
                arrivals = listOf(NavigationBusArrival(line = line, eta = "${minutes}분")),
            ),
            timestampMillis = nowMillis,
        )
    }

    @Suppress("UNUSED_PARAMETER")
    fun onStep(step: GoogleStep): String? = null

    @Synchronized
    fun reset() {
        wasLive = false
        spoken = false
        remembered = null
    }

    /** The first "기타:" after the first "에 출발" clock; a far clock like 오전 6:03 is dropped. */
    private fun nextMinutes(blobs: List<String>, nowMillis: Long, first: Int?): Int? {
        val start = blobs.indexOfFirst { clock.containsMatchIn(it) && it.contains("에 출발") }
        if (start < 0) return null
        val extra = blobs.drop(start + 1)
            .takeWhile { !it.contains("에 출발") }
            .firstOrNull { it.trim().startsWith("기타:") }
            ?: return null
        val minutes = minuteAfter.find(extra)?.groupValues?.get(1)?.toIntOrNull()
            ?: clockMinutes(extra, nowMillis)
            ?: return null
        if (minutes < 1 || minutes > 60) return null
        if (first != null && minutes <= first) return null
        return minutes
    }

    private fun clockMinutes(blob: String, nowMillis: Long): Int? {
        if (nowMillis <= 0L) return null
        val match = clock.find(blob) ?: return null
        val hour12 = match.groupValues[2].toInt()
        val minute = match.groupValues[3].toInt()
        val hour24 = hour12 % 12 + if (match.groupValues[1] == "오후") 12 else 0
        val target = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, hour24)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val delta = target.timeInMillis - nowMillis
        if (delta < 0) return null
        return ((delta + 59_999L) / 60_000L).toInt()
    }

    private fun flatten(node: GoogleTransitAccessibilityParser.Node, out: MutableList<String> = ArrayList()): List<String> {
        node.text?.let(out::add)
        node.contentDesc?.let(out::add)
        node.children.forEach { flatten(it, out) }
        return out
    }

    internal fun resetForTest() = reset()
}
