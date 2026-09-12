package nomi.android.traffic

import nomi.product.nav.NavigationEvent
import nomi.product.nav.NavigationEventSource
import nomi.product.nav.NavigationEventType

/**
 * Maps already-read Naver Maps subway nodes to [NavigationEvent].
 * Uses only on-screen 방면 + 빠른 하차/빠른 환승 + car numbers. No GPS, no route math.
 */
object NaverSubwayAccessibilityParser {

    data class Node(
        val text: String? = null,
        val contentDesc: String? = null,
        val children: List<Node> = emptyList(),
    )

    private val formatChars = Regex("""[\u200b\u200c\u200d\ufeff]""")
    private val directionPattern = Regex("""(.+방면)$""")
    private val cuePattern = Regex("""^(빠른 하차|빠른 환승):\s*(.+)$""")
    private val carsPattern = Regex("""^\d+-\d+(?:\s*,\s*\d+-\d+)*$""")
    private val earlierAlightPattern = Regex("""^(?!빠른 하차|빠른 환승).+하차$""")

    fun parse(
        packageName: String?,
        root: Node,
        timestampMillis: Long = 0L,
    ): List<NavigationEvent> {
        if (packageName != NaverMapNotification.PACKAGE) return emptyList()
        val blobs = flatten(root)
        return pairCues(blobs, timestampMillis)
    }

    fun formatLog(event: NavigationEvent): String = buildString {
        appendLine("[NAVER_SUBWAY_A11Y]")
        appendLine("action=${event.action}")
        appendLine("direction=${event.landmark.orEmpty()}")
        append("cars=${event.rawText}")
    }

    internal fun clean(raw: String?): String =
        raw?.replace(formatChars, "")?.trim().orEmpty()

    private fun pairCues(blobs: List<String>, timestampMillis: Long): List<NavigationEvent> {
        if (!subwayLegIsCurrent(blobs)) return emptyList()
        val events = ArrayList<NavigationEvent>()
        var board: String? = null
        var direction: String? = null
        for (blob in blobs) {
            if (isSubwayBoard(blob)) {
                board = blob
                continue
            }
            if (directionPattern.matches(blob)) {
                direction = blob
                continue
            }
            val cue = cuePattern.matchEntire(blob) ?: continue
            val kind = cue.groupValues[1]
            val cars = cue.groupValues[2].trim()
            val matchedDirection = direction
            direction = null
            if (matchedDirection == null) continue
            if (!carsPattern.matches(cars)) continue
            events.add(carEvent(kind, matchedDirection, cars, board, timestampMillis))
        }
        return events
    }

    /**
     * Itinerary sheets show later subway 방면/빠른 하차 while the current step is
     * still a bus 하차. Speak car cues only when that earlier 하차 is gone, or when
     * the first vehicle step is already 승차 / the HUD has no step header.
     */
    private fun subwayLegIsCurrent(blobs: List<String>): Boolean {
        for (blob in blobs) {
            if (directionPattern.matches(blob)) continue
            if (cuePattern.matches(blob)) continue
            if (earlierAlightPattern.matches(blob)) return false
            if (isSubwayBoard(blob)) return true
        }
        return true
    }

    private fun isSubwayBoard(blob: String): Boolean =
        blob.endsWith("승차") && !blob.contains("버스") && !blob.contains("도보")

    private fun flatten(node: Node): List<String> {
        val out = ArrayList<String>()
        collectBlobs(node, out)
        return out
    }

    private fun collectBlobs(node: Node, out: MutableList<String>) {
        clean(node.text).takeIf { it.isNotEmpty() }?.let(out::add)
        clean(node.contentDesc).takeIf { it.isNotEmpty() }?.let(out::add)
        node.children.forEach { collectBlobs(it, out) }
    }

    private fun carEvent(
        kind: String,
        direction: String,
        cars: String,
        board: String?,
        timestampMillis: Long,
    ): NavigationEvent =
        NavigationEvent(
            source = NavigationEventSource.NAVER,
            type = NavigationEventType.TRANSIT,
            notificationId = 0,
            channel = NaverMapsTransit.CHANNEL,
            title = board.orEmpty(),
            action = kind,
            distanceMeters = null,
            rawText = cars,
            busInfo = null,
            timestampMillis = timestampMillis,
            landmark = direction,
        )
}
