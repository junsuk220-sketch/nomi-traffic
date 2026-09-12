package nomi.android.traffic

import nomi.product.nav.NavigationBusArrival
import nomi.product.nav.NavigationBusInfo
import nomi.product.nav.NavigationEvent
import nomi.product.nav.NavigationEventSource
import nomi.product.nav.NavigationEventType

/**
 * Naver transfer cues after subway alight → walk to exit → bus wait.
 * Complements Naver's own alight TTS; does not repeat 하차 / 내리는 문.
 */
object NaverTransferAccessibilityParser {

    private val exitWalkOneBlob = Regex(
        """(\d+)\s*번\s*출구에서\s*도보(?:\s*\d+\s*m)?\s*[·•.]?\s*(\d+)\s*분""",
        RegexOption.IGNORE_CASE,
    )
    private val exitOnly = Regex("""(\d+)\s*번\s*출구""")
    private val walkMetersOnly = Regex("""도보\s*\d+\s*m""", RegexOption.IGNORE_CASE)
    private val bareMinutes = Regex("""^(\d+)\s*분$""")
    private val doorHint = Regex("""^내리는\s*문""")
    private val alightStop = Regex("""^(?!빠른 하차|빠른 환승).+하차$""")
    private val busBoard = Regex("""^(?!.*호선).*승차$""")
    private val directionPattern = Regex("""^.+방면$""")
    private val busLine = Regex("""^\d{1,4}$""")
    private val occupancyLabel = Regex("""^(여유|보통|혼잡|매우\s*혼잡)$""")
    private val minutesOnly = Regex("""^(\d+)\s*분$""")
    private val minutesAndStops = Regex("""^(\d+)\s*분\s*(\d+)\s*정류장""")
    private val stopsOnly = Regex("""^(\d+)\s*정류장$""")

    fun transferWalk(
        packageName: String?,
        root: NaverSubwayAccessibilityParser.Node,
        timestampMillis: Long = 0L,
    ): NavigationEvent? {
        if (packageName != NaverMapNotification.PACKAGE) return null
        if (!NaverTransitDestinationParser.isLiveGuidance(root)) return null
        val blobs = flatten(root)
        val walk = exitWalk(blobs) ?: return null
        if (!maySpeakTransferWalk(blobs, walk.minutes)) return null
        return NavigationEvent(
            source = NavigationEventSource.NAVER,
            type = NavigationEventType.TRANSIT,
            notificationId = 0,
            channel = NaverMapsTransit.CHANNEL,
            title = "${walk.exit}번",
            action = NaverMapsTransit.TRANSFER_WALK_ACTION,
            distanceMeters = walk.minutes,
            rawText = "${walk.exit}|${walk.minutes}",
            busInfo = null,
            timestampMillis = timestampMillis,
            landmark = null,
        )
    }

    fun busBoardDirection(
        packageName: String?,
        root: NaverSubwayAccessibilityParser.Node,
        timestampMillis: Long = 0L,
    ): NavigationEvent? {
        if (packageName != NaverMapNotification.PACKAGE) return null
        if (!NaverTransitDestinationParser.isLiveGuidance(root)) return null
        val blobs = flatten(root)
        // A bus board needs a bus 승차 header with a real wait row under it.
        // A subway-only course shows a lone number (도보 2분 / 출구) plus the
        // subway 방면 — that must never become "2번 버스".
        val boardIdx = busBoardIndex(blobs) ?: return null
        val window = blobs.subList(boardIdx, minOf(blobs.size, boardIdx + BOARD_WINDOW))
        if (!maySpeakBusBoard(blobs)) return null
        val line = firstBusLine(window) ?: return null
        val direction = window.firstOrNull { directionPattern.matches(it) }?.trim() ?: return null
        return NavigationEvent(
            source = NavigationEventSource.NAVER,
            type = NavigationEventType.TRANSIT,
            notificationId = 0,
            channel = NaverMapsTransit.BUS_CHANNEL,
            title = line,
            action = NaverMapsTransit.BOARD_DIRECTION_ACTION,
            distanceMeters = null,
            rawText = NaverMapsTransit.KIND_BUS,
            busInfo = NavigationBusInfo(
                raw = "$line $direction",
                arrivals = listOf(NavigationBusArrival(line = line, eta = "")),
            ),
            timestampMillis = timestampMillis,
            landmark = direction,
        )
    }

    fun formatLog(event: NavigationEvent): String = buildString {
        appendLine("[NAVER_TRANSFER_A11Y]")
        appendLine("action=${event.action}")
        appendLine("title=${event.title}")
        appendLine("landmark=${event.landmark.orEmpty()}")
        append("walkMin=${event.distanceMeters?.toString().orEmpty()}")
    }

    private fun maySpeakTransferWalk(blobs: List<String>, walkMinutes: Int): Boolean {
        if (blobs.any { doorHint.containsMatchIn(it) }) return true
        val alightIdx = blobs.indexOfFirst { alightStop.matches(it) }
        if (alightIdx < 0 || walkMinutes > 5) return false
        // Still on an earlier subway leg shown above this alight — stay silent.
        val subwayBoardBefore = blobs.withIndex().any { (i, blob) ->
            i < alightIdx && blob.endsWith("승차") && blob.contains("호선")
        }
        return !subwayBoardBefore
    }

    /** Index of a bus 승차 header. A subway board split across nodes is not one. */
    private fun busBoardIndex(blobs: List<String>): Int? {
        for (i in blobs.indices) {
            if (!busBoard.matches(blobs[i])) continue
            if (mentionsSubwayNear(blobs, i)) continue
            return i
        }
        return null
    }

    private fun mentionsSubwayNear(blobs: List<String>, index: Int): Boolean {
        val from = maxOf(0, index - SUBWAY_NEAR)
        val to = minOf(blobs.size, index + SUBWAY_NEAR + 1)
        return blobs.subList(from, to).any { it.contains("호선") }
    }

    private fun maySpeakBusBoard(blobs: List<String>): Boolean {
        val walk = exitWalk(blobs)
        if (walk != null && walk.minutes > 2) {
            // Still walking from the subway exit — wait until near the stop,
            // unless live bus ETA rows are already up.
            if (!hasBusWaitRow(blobs)) return false
        }
        return true
    }

    private data class ExitWalk(val exit: String, val minutes: Int)

    private fun exitWalk(blobs: List<String>): ExitWalk? {
        blobs.firstNotNullOfOrNull { exitWalkOneBlob.find(it) }?.let {
            val exit = it.groupValues[1]
            val minutes = it.groupValues[2].toIntOrNull() ?: return@let null
            if (minutes in 1..30) return ExitWalk(exit, minutes)
        }
        for (i in blobs.indices) {
            val exit = exitOnly.find(blobs[i])?.groupValues?.get(1) ?: continue
            val window = blobs.subList(i, minOf(blobs.size, i + 6))
            if (!window.any { walkMetersOnly.containsMatchIn(it) || it.contains("도보") }) continue
            val minutes = window.firstNotNullOfOrNull {
                bareMinutes.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull()
            } ?: continue
            if (minutes in 1..30) return ExitWalk(exit, minutes)
        }
        return null
    }

    /** Only a number that already carries a wait row counts as a bus line. */
    private fun firstBusLine(blobs: List<String>): String? {
        for (i in blobs.indices) {
            if (!busLine.matches(blobs[i])) continue
            val window = blobs.subList(i + 1, minOf(blobs.size, i + 8))
            if (window.any {
                    TransitSoonEta.matches(it) ||
                        minutesOnly.matches(it) ||
                        minutesAndStops.matches(it) ||
                        stopsOnly.matches(it) ||
                        occupancyLabel.matches(it) ||
                        it.contains("도착 예정 정보 없음")
                }
            ) {
                return blobs[i]
            }
        }
        return null
    }

    private fun hasBusWaitRow(blobs: List<String>): Boolean {
        for (i in blobs.indices) {
            if (!busLine.matches(blobs[i])) continue
            val window = blobs.subList(i + 1, minOf(blobs.size, i + 8))
            if (window.any {
                    TransitSoonEta.matches(it) ||
                        minutesAndStops.matches(it) ||
                        (minutesOnly.matches(it) && window.any { s -> stopsOnly.matches(s) }) ||
                        occupancyLabel.matches(it)
                }
            ) {
                return true
            }
        }
        return false
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

    /** Rows that belong to one 승차 card. */
    private const val BOARD_WINDOW = 16
    private const val SUBWAY_NEAR = 3
}
