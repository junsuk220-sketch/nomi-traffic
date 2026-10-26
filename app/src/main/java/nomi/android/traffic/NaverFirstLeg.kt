package nomi.android.traffic

/**
 * The first vehicle of the guided route, read from the live sheet at guidance start.
 * Naver scrolls the sheet to later legs within seconds, so only a board at the sheet top
 * (origin / first walk above it, no ride or alight before it) counts.
 * Pure reader. [NaverTripStartSession] holds the locked leg for the trip.
 */
internal object NaverFirstLeg {

    /** [lines] are every line Naver lists on that board. [block] is the board as printed, empty when unknown. */
    data class Leg(val subway: Boolean, val lines: Set<String>, val block: List<String>) {
        fun label(): String = (if (subway) "SUBWAY:" else "BUS:") + lines.joinToString("/")
    }

    fun read(blobs: List<String>): Leg? {
        val cleaned = blobs.map(::clean).filter { it.isNotEmpty() }
        val live = cleaned.indexOfFirst { it == "안내 중" || it == "안내중" }
        if (live < 0) return null
        val head = cleaned.subList(0, live)
        val board = head.indexOfFirst { boardBlob.containsMatchIn(" $it") }
        if (board >= 0) {
            topBoard(head, board)?.let { return it }
        }
        return hudSummary(cleaned, live)
    }

    /** 302 `정발산역 3호선까지 걷기`. Bus walk titles name only the stop, so they never lock. */
    fun fromWalkTitle(title: String?): Leg? {
        val match = subwayWalkTitle.matchEntire(clean(title)) ?: return null
        return Leg(subway = true, lines = setOf(match.groupValues[1]), block = emptyList())
    }

    /** A briefing line or vehicle may speak only when it is the locked first leg. No lock: no opinion. */
    fun allows(vehicle: String?, leg: Leg?): Boolean {
        if (leg == null) return true
        val key = vehicleKey(vehicle) ?: return true
        return key in leg.lines
    }

    /** The board as a live sheet for [NaverTripStartParser.decision]. */
    fun node(block: List<String>): NaverSubwayAccessibilityParser.Node =
        NaverSubwayAccessibilityParser.Node(
            children = (listOf("안내 중") + block).map { NaverSubwayAccessibilityParser.Node(text = it) },
        )

    private fun topBoard(head: List<String>, board: Int): Leg? {
        val above = head.subList(0, board)
        if (above.none { isSheetTop(it) }) return null
        if (above.any { rideMarker.containsMatchIn(it) || it.endsWith("하차") }) return null
        val boardText = head[board].substringBefore('\u00a0').trim()
        subwayBoard.matchEntire(boardText)?.let { match ->
            val end = (board + 1 until head.size).firstOrNull {
                stationRide.containsMatchIn(head[it]) || head[it].endsWith("하차")
            } ?: head.size
            return Leg(subway = true, lines = setOf(match.groupValues[1]), block = head.subList(0, end))
        }
        val end = (board + 1 until head.size).firstOrNull {
            head[it] == "버스 도착정보 더보기" || head[it].endsWith("하차") || stopRide.containsMatchIn(head[it])
        } ?: head.size
        val block = head.subList(0, end)
        val lines = NaverBusAccessibilityParser.arrivalsFromBlobs(block.subList(board + 1, block.size))
            .map { it.line }
            .toCollection(LinkedHashSet())
        if (lines.isEmpty()) return null
        return Leg(subway = false, lines = lines, block = block)
    }

    /** Re-opened guidance prints the chosen route summary right after `안내 중`. */
    private fun hudSummary(blobs: List<String>, live: Int): Leg? {
        val summary = blobs.subList(live + 1, minOf(blobs.size, live + 4))
            .firstOrNull { routeSummary.containsMatchIn(it) } ?: return null
        val first = summary.split(",").map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("도보") } ?: return null
        busLeg.find(first)?.let {
            return Leg(subway = false, lines = setOf(it.groupValues[1]), block = emptyList())
        }
        val line = first.replace(trailingMinutes, "").trim().split(" ").firstOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return null
        return Leg(subway = true, lines = setOf(line), block = emptyList())
    }

    private fun vehicleKey(raw: String?): String? {
        val head = clean(raw).split(' ', ',').firstOrNull { it.isNotBlank() } ?: return null
        return head.removeSuffix("이").removeSuffix("번").ifBlank { null }
    }

    private fun isSheetTop(blob: String): Boolean =
        walkMeters.matches(blob) || blob.endsWith("출구까지") || region.containsMatchIn(blob)

    private fun clean(raw: String?): String =
        raw?.replace(formatChars, "")?.trim().orEmpty()

    private val formatChars = Regex("""[\u200b\u200c\u200d\ufeff]""")
    private val boardBlob = Regex("""\s승차(\s|$)""")
    private val subwayBoard = Regex("""^(\d+호선|[가-힣A-Za-z0-9]+선|GTX-[A-Za-z])\s+\S.*승차$""")
    private val subwayWalkTitle = Regex("""^\S+\s+(\d+호선|[가-힣A-Za-z0-9]+선|GTX-[A-Za-z])까지 걷기$""")
    private val walkMeters = Regex("""^도보\s*\d+\s*m$""", RegexOption.IGNORE_CASE)
    private val region = Regex("""^(서울|경기|인천|부산|대구|대전|광주|울산|세종|강원|충북|충남|전북|전남|경북|경남|제주)\s""")
    private val rideMarker = Regex("""개\s*(역|정류장)\s*이동$""")
    private val stationRide = Regex("""개\s*역\s*이동$""")
    private val stopRide = Regex("""개\s*정류장\s*이동$""")
    private val routeSummary = Regex("""^도보 \d+분, """)
    private val busLeg = Regex("""^([A-Za-z]?\d{1,4}[A-Za-z-]?\d*)번""")
    private val trailingMinutes = Regex("""\s*\d+분$""")
}
