package nomi.android.traffic.api.nextbus

/**
 * One bus stop block on Naver's route screen.
 * @param alight station (without 역) the screen lists right before this stop.
 */
data class NextBusStop(
    val stop: String,
    val stopNo: String,
    val alight: String?,
    val noInfoLines: List<String>,
    val infoLines: List<String>,
    val seenAtMs: Long,
)

data class NextBusNoInfoOutcome(
    val text: String? = null,
    val note: String,
    val stopNo: String? = null,
)

/**
 * Subway alight into a bus leg: when Naver's screen says the next bus has
 * `도착 예정 정보 없음`, say so once per alight. Never names minutes.
 * Own state only; reads Naver screen text and alight cues.
 */
object NextBusNoInfo {

    private val stops = LinkedHashMap<String, NextBusStop>()
    private var decidedKey: String? = null

    @Synchronized
    fun observeScreen(blobs: List<String>, nowMs: Long) {
        val cleaned = blobs.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.any { isResetPhrase(it) }) resetLocked()
        for (parsed in NextBusStopParser.parse(cleaned, nowMs)) {
            val prev = stops[parsed.stopNo]
            stops.remove(parsed.stopNo)
            stops[parsed.stopNo] = parsed.copy(alight = parsed.alight ?: prev?.alight)
        }
    }

    /** Called with Naver cues. Decides once per `이번 역(X)에서 하차 후 환승`. */
    @Synchronized
    fun onCues(cues: List<String>, nowMs: Long): NextBusNoInfoOutcome? {
        val cleaned = cues.map { it.trim() }.filter { it.isNotEmpty() }
        val key = cleaned.firstNotNullOfOrNull { alightKey(it) }
        if (key == null) {
            if (cleaned.any { isResetPhrase(it) }) resetLocked()
            return null
        }
        if (key == decidedKey) return null
        decidedKey = key
        val stop = stops.values.lastOrNull { it.alight == key }
            ?: return NextBusNoInfoOutcome(note = "no_stop alight=$key")
        val ageSec = (nowMs - stop.seenAtMs) / 1000
        val where = "alight=$key stop=${stop.stop}/${stop.stopNo} ageSec=$ageSec"
        if (stop.infoLines.isNotEmpty()) {
            return NextBusNoInfoOutcome(
                note = "has_info $where lines=${stop.infoLines.joinToString(",")}",
                stopNo = stop.stopNo,
            )
        }
        if (stop.noInfoLines.isEmpty()) {
            return NextBusNoInfoOutcome(note = "no_lines $where", stopNo = stop.stopNo)
        }
        return NextBusNoInfoOutcome(
            text = "${stop.noInfoLines.joinToString(", ")} 도착 정보가 아직 없습니다.",
            note = "no_info $where",
            stopNo = stop.stopNo,
        )
    }

    /** False once the screen has shown minutes for this stop. */
    @Synchronized
    fun stillNoInfo(stopNo: String): Boolean {
        val stop = stops[stopNo] ?: return false
        return stop.infoLines.isEmpty() && stop.noInfoLines.isNotEmpty()
    }

    @Synchronized
    internal fun resetForTest() {
        resetLocked()
    }

    private fun resetLocked() {
        stops.clear()
        decidedKey = null
    }

    private fun alightKey(blob: String): String? {
        if (!blob.contains("하차 후 환승")) return null
        return alightInParens.find(blob)?.groupValues?.get(1)?.trim()
            ?.removeSuffix("역")
            ?.takeIf { it.isNotEmpty() }
    }

    private fun isResetPhrase(blob: String): Boolean =
        startPhrase.containsMatchIn(blob) || endPhrase.containsMatchIn(blob)

    private val alightInParens = Regex("""이번\s*역\s*\(([^)]+)\)""")
    private val startPhrase = Regex("""길\s*안내를\s*시작""")
    private val endPhrase = Regex("""길\s*안내를\s*종료""")
}

/**
 * Screen shape (observed):
 * `서울대입구역 하차`, …, `서울대입구역2호선 승차`, `21540`, `관악07`, `마을`,
 * `도착 예정 정보 없음`, `과거 시간표`, `버스 도착정보 더보기`.
 * Blocks without the closing `버스 도착정보 더보기` are still loading and skipped.
 */
internal object NextBusStopParser {

    fun parse(blobs: List<String>, nowMs: Long): List<NextBusStop> {
        val out = ArrayList<NextBusStop>()
        for (i in blobs.indices) {
            val stop = boardStop.matchEntire(blobs[i])?.groupValues?.get(1)?.trim() ?: continue
            val stopNo = blobs.getOrNull(i + 1)?.takeIf { stopNumber.matches(it) } ?: continue
            val block = readLines(blobs, i + 2) ?: continue
            out += NextBusStop(
                stop = stop,
                stopNo = stopNo,
                alight = alightBefore(blobs, i),
                noInfoLines = block.first,
                infoLines = block.second,
                seenAtMs = nowMs,
            )
        }
        return out
    }

    private fun readLines(blobs: List<String>, from: Int): Pair<List<String>, List<String>>? {
        val noInfo = LinkedHashSet<String>()
        val info = LinkedHashSet<String>()
        var line: String? = null
        var j = from
        while (j < blobs.size && j < from + MAX_BLOCK) {
            val text = blobs[j]
            if (text == MORE_MARK) {
                if (noInfo.isEmpty() && info.isEmpty()) return null
                return noInfo.toList() to info.toList()
            }
            if (boardStop.matches(text) || alightStop.matches(text)) return null
            when {
                text == NO_INFO -> line?.let { noInfo += it }
                text == SOON || minutes.matches(text) -> line?.let { info += it }
                isLineName(text) -> line = text
            }
            j++
        }
        return null
    }

    private fun alightBefore(blobs: List<String>, boardIndex: Int): String? {
        var j = boardIndex - 1
        while (j >= 0 && j >= boardIndex - MAX_BACK) {
            val text = blobs[j]
            if (boardStop.matches(text)) return null
            alightStop.matchEntire(text)?.groupValues?.get(1)?.trim()?.let {
                return it.removeSuffix("역").takeIf { name -> name.isNotEmpty() }
            }
            j--
        }
        return null
    }

    private fun isLineName(text: String): Boolean {
        if (!lineName.matches(text)) return false
        return !text.endsWith("정류장") && !text.endsWith("석") && !text.endsWith("분")
    }

    private val boardStop = Regex("""(.+?)\s+승차""")
    private val alightStop = Regex("""(.+?)\s+하차""")
    private val stopNumber = Regex("""\d{5}""")
    private val minutes = Regex("""\d+분""")
    private val lineName = Regex("""[가-힣A-Za-z]{0,4}\d+[가-힣A-Za-z0-9-]*""")
    private const val NO_INFO = "도착 예정 정보 없음"
    private const val SOON = "곧 도착"
    private const val MORE_MARK = "버스 도착정보 더보기"
    private const val MAX_BLOCK = 40
    private const val MAX_BACK = 12
}
