package nomi.android.traffic.api.nextsubway

import android.util.Log

/**
 * Remembers the subway the rider will transfer to.
 * Learns only after guidance has started. Does not speak or fetch.
 */
object NextSubway {

    private var open = false
    private var fact: NextSubwayFact? = null
    private var stationExplicit = false
    private var lineExplicit = false
    private var lastLog: String? = null
    private var lastCandidate: Candidate? = null
    private val byBoardStop = LinkedHashMap<String, Candidate>()
    private val byStation = LinkedHashMap<String, Candidate>()
    private val chain = ArrayList<NextSubwayFact>()
    private var consumedIndex = -1
    private var startLegStation: String? = null
    private var startDecided = false

    private data class Candidate(
        val line: String,
        val station: String,
        val direction: String?,
        val boardStop: String?,
    )

    /**
     * @param learn false for Naver screens outside live guidance (search,
     * history, route list). Those only note route-card candidates; their
     * text never becomes the remembered subway.
     */
    fun consider(cues: List<String>, learn: Boolean = true): NextSubwayFact? {
        val cleaned = cues.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.any { isEndPhrase(it) }) {
            resetLocked()
            log(null)
            return null
        }
        if (!learn) {
            if (cleaned.any { isRouteStartButton(it) }) {
                clearFact()
                log(null)
            }
            noteCandidate(cleaned)
            return fact
        }
        noteCandidate(cleaned)
        val started = cleaned.any { isStartPhrase(it) || isLiveMarker(it) }
        if (!open && !started) return null
        open = true
        val scoped = selected(cleaned)
        learnChain(scoped, startTick = cleaned.any { isStartPhrase(it) })
        val parsed = NextSubwayParser.parse(scoped)
        val merged = if (parsed == null) fact else merge(fact, parsed)
        fact = applyCandidate(merged, firstBoardStop(cleaned))
        log(fact)
        return fact
    }

    fun snapshot(): NextSubwayFact? = fact

    /** Every subway board of the running route, in route order. */
    fun chain(): List<NextSubwayFact> = chain.toList()

    /** The first leg when the route starts on a subway (not a transfer). */
    fun startLeg(): NextSubwayFact? = startLegStation?.let { st -> chain.firstOrNull { it.station == st } }

    /**
     * Subway boards the rider transfers to (chain without the start leg).
     * Empty when the route never showed a subway board on the live screen.
     */
    fun transferTargets(): List<NextSubwayFact> {
        val start = startLegStation
        return chain.filter { it.station != start }
    }

    /**
     * Pick the subway for this `하차 후 환승` and mark it (and earlier ones) used.
     * Matches the alight station in `이번 역(대곡)` or the line in `서해선으로 환승`.
     * A named station or line that matches nothing is not a subway transfer
     * (신논현에서 버스로 환승). An unnamed cue still takes the next unused board.
     * Null when no subway is left.
     */
    fun takeTransferTarget(cues: List<String>): NextSubwayFact? {
        if (chain.isEmpty()) return null
        val start = startLegStation
        val open = chain.indices.filter { it > consumedIndex && chain[it].station != start }
        if (open.isEmpty()) return null
        val alight = cues.firstNotNullOfOrNull { alightInParens.find(it)?.groupValues?.get(1)?.trim() }
            ?.removeSuffix("역")
        val toLine = cues.firstNotNullOfOrNull { transferToLine.find(it)?.groupValues?.get(1)?.trim() }
        val named = !alight.isNullOrEmpty() || !toLine.isNullOrEmpty()
        val pick = open.firstOrNull { i ->
            !alight.isNullOrEmpty() && chain[i].station?.removeSuffix("역") == alight
        } ?: open.firstOrNull { i ->
            !toLine.isNullOrEmpty() && chain[i].line == toLine
        } ?: if (named) null else open.first()
        if (pick == null) return null
        consumedIndex = pick
        return chain[pick]
    }

    /** Card text: every transfer subway, e.g. `서해선 대곡역 · 1호선 소사역`. */
    fun cardNote(): String? {
        val targets = transferTargets()
        if (targets.isNotEmpty()) {
            return targets.joinToString(" · ") { label(it) }
        }
        val single = fact ?: return null
        val station = single.station?.trim().orEmpty()
        if (station.isEmpty()) return null
        return label(single)
    }

    internal fun resetForTest() {
        resetLocked()
        lastLog = null
    }

    private fun label(f: NextSubwayFact): String {
        val line = f.line.trim()
        val station = f.station?.trim().orEmpty()
        val named = if (f.service.isNullOrBlank()) line else "$line ${f.service}"
        return if (named.isEmpty()) station else "$named $station".trim()
    }

    private fun serviceName(raw: String): String? =
        when (raw.trim()) {
            "급", "급행" -> "급행"
            else -> null
        }

    /**
     * Live screen lists each subway board as `3호선 정발산역 승차` (or 302
     * `정발산역 3호선 열차 승차`). Keeps them in route order across ticks.
     */
    private fun learnChain(scoped: List<String>, startTick: Boolean) {
        val boards = ArrayList<NextSubwayFact>()
        var firstBoardIsSubway: Boolean? = null
        var firstSubwayStation: String? = null
        var current: NextSubwayFact? = null
        for (blob in scoped) {
            val subway = subwayBoard(blob)
            val anyBoard = subway != null || boardStopToken.matches(blob)
            if (anyBoard) {
                current?.let { boards.add(it) }
                current = subway
                if (firstBoardIsSubway == null) {
                    firstBoardIsSubway = subway != null
                    firstSubwayStation = subway?.station
                }
                continue
            }
            val open = current ?: continue
            val dir = directionToken.find(blob)?.groupValues?.get(1)
            val cars = carsToken.find(blob)?.groupValues?.get(1)
            current = open.copy(
                direction = open.direction ?: dir,
                cars = open.cars ?: cars,
            )
        }
        current?.let { boards.add(it) }
        if (startTick && !startDecided && firstBoardIsSubway != null) {
            startDecided = true
            startLegStation = if (firstBoardIsSubway == true) firstSubwayStation else null
        }
        mergeChain(boards)
    }

    private fun mergeChain(boards: List<NextSubwayFact>) {
        for ((i, board) in boards.withIndex()) {
            val station = board.station ?: continue
            val at = chain.indexOfFirst { it.station == station }
            if (at >= 0) {
                val old = chain[at]
                chain[at] = old.copy(
                    direction = old.direction ?: board.direction,
                    cars = old.cars ?: board.cars,
                )
                continue
            }
            val before = boards.subList(0, i).asReversed()
                .firstNotNullOfOrNull { prev -> chain.indexOfFirst { it.station == prev.station }.takeIf { it >= 0 } }
            val after = boards.subList(i + 1, boards.size)
                .firstNotNullOfOrNull { next -> chain.indexOfFirst { it.station == next.station }.takeIf { it >= 0 } }
            val insertAt = when {
                before != null -> before + 1
                after != null -> after
                else -> chain.size
            }
            chain.add(insertAt, board)
            if (insertAt <= consumedIndex) consumedIndex += 1
        }
    }

    private fun subwayBoard(blob: String): NextSubwayFact? {
        val text = blob.trim()
        lineStationBoard.matchEntire(text)?.let {
            return NextSubwayFact(
                line = it.groupValues[1].trim(),
                station = it.groupValues[3].trim(),
                service = serviceName(it.groupValues[2]),
            )
        }
        stationLineBoard.matchEntire(text)?.let {
            return NextSubwayFact(line = it.groupValues[2].trim(), station = it.groupValues[1].trim())
        }
        return null
    }

    private fun merge(prev: NextSubwayFact?, parsed: NextSubwayParser.Parsed): NextSubwayFact {
        val station = when {
            parsed.stationExplicit -> parsed.station
            prev?.station != null -> prev.station
            else -> parsed.station
        }
        if (parsed.stationExplicit && parsed.station != null) stationExplicit = true
        val line = when {
            parsed.lineExplicit -> parsed.line
            lineExplicit && prev != null -> prev.line
            prev != null && parsed.line.isEmpty() -> prev.line
            else -> parsed.line.ifEmpty { prev?.line.orEmpty() }
        }
        if (parsed.lineExplicit && line.isNotEmpty()) lineExplicit = true
        return NextSubwayFact(
            line = line,
            station = station,
            direction = parsed.direction ?: prev?.direction,
            cars = parsed.cars ?: prev?.cars,
            service = parsed.service ?: prev?.service,
        )
    }

    private fun selected(cues: List<String>): List<String> {
        val end = cues.indexOfFirst { isAltBoundary(it) }
        if (end < 0) return cues
        return cues.subList(0, end)
    }

    private fun resetLocked() {
        clearFact()
        lastCandidate = null
        byBoardStop.clear()
        byStation.clear()
    }

    private fun clearFact() {
        open = false
        fact = null
        stationExplicit = false
        lineExplicit = false
        chain.clear()
        consumedIndex = -1
        startLegStation = null
        startDecided = false
    }

    private fun noteCandidate(cues: List<String>) {
        // Route-list "바로 안내시작" and detail "안내시작" only.
        // Search history / live HUD without those buttons must not become
        // the remembered subway.
        if (cues.none { isRouteStartButton(it) }) return
        var start = 0
        while (start < cues.size) {
            val rel = cues.subList(start, cues.size).indexOfFirst { isRouteStartButton(it) }
            if (rel < 0) break
            noteCandidateInCard(cues.subList(start, start + rel))
            start += rel + 1
        }
    }

    private fun noteCandidateInCard(card: List<String>) {
        var line: String? = null
        var lineAt = -1
        var station: String? = null
        var direction: String? = null
        var boardStop: String? = null
        for (i in card.indices) {
            val text = card[i]
            if (line == null && text !in busCategory) {
                lineOnly.matchEntire(text)?.groupValues?.get(1)?.let {
                    line = it
                    lineAt = i
                }
            }
            if (line != null && station == null) {
                bareStation.matchEntire(text)?.groupValues?.get(1)?.let { station = it }
            }
            directionToken.find(text)?.groupValues?.get(1)?.let { direction = it }
            boardStopToken.matchEntire(text)?.groupValues?.get(1)?.trim()?.let {
                if (it.isNotEmpty()) boardStop = it
            }
        }
        val wantLine = line ?: return
        if (boardStop == null) {
            val beforeSubway = if (lineAt > 0) card.subList(0, lineAt) else emptyList()
            for (i in beforeSubway.indices) {
                if (beforeSubway[i] in busCategory && i + 1 < beforeSubway.size) {
                    plainBoardStop(beforeSubway[i + 1])?.let { boardStop = it }
                }
            }
            if (boardStop == null) {
                for (i in beforeSubway.indices.reversed()) {
                    val name = plainBoardStop(beforeSubway[i]) ?: continue
                    boardStop = name
                    break
                }
            }
        }
        val wantStation = station
            ?: boardStop?.let { byBoardStop[it]?.station }
            ?: lastCandidate?.takeIf { it.boardStop != null && it.boardStop == boardStop }?.station
            ?: return
        val saved = Candidate(
            wantLine,
            wantStation,
            direction
                ?: boardStop?.let { byBoardStop[it]?.direction }
                ?: lastCandidate?.takeIf { it.station == wantStation }?.direction,
            boardStop,
        )
        lastCandidate = saved
        byStation[wantStation] = saved
        val stop = boardStop
        if (!stop.isNullOrBlank()) byBoardStop[stop] = saved
    }

    private fun isRouteStartButton(blob: String): Boolean {
        val text = blob.trim()
        return text == "바로 안내시작" || text == "안내시작"
    }

    private fun applyCandidate(
        current: NextSubwayFact?,
        boardStop: String?,
    ): NextSubwayFact? {
        val saved = when {
            !boardStop.isNullOrBlank() -> byBoardStop[boardStop]
            !current?.station.isNullOrBlank() -> byStation[current!!.station!!]
            else -> null
        } ?: lastCandidate ?: return current

        val stationMatch =
            !current?.station.isNullOrBlank() && current!!.station == saved.station
        val stopMatch =
            !boardStop.isNullOrBlank() &&
                !saved.boardStop.isNullOrBlank() &&
                boardStop == saved.boardStop
        val mappedStop =
            !boardStop.isNullOrBlank() && byBoardStop.containsKey(boardStop)
        if (!stationMatch && !stopMatch && !mappedStop) return current
        if (!stopMatch &&
            !mappedStop &&
            current?.line?.isNotBlank() == true &&
            current.line != saved.line
        ) {
            return current
        }
        val base = current ?: NextSubwayFact(line = "", station = null)
        val sameLine = base.line.isBlank() || base.line == saved.line
        return base.copy(
            line = base.line.ifBlank { saved.line },
            station = base.station ?: saved.station,
            direction = base.direction ?: saved.direction?.takeIf { sameLine },
        )
    }

    private fun firstBoardStop(cues: List<String>): String? {
        for (blob in selected(cues)) {
            boardStopToken.matchEntire(blob)?.groupValues?.get(1)?.trim()?.let {
                if (it.isNotEmpty()) return it
            }
        }
        return null
    }

    private fun plainBoardStop(blob: String): String? {
        val text = blob.trim()
        if (text.length < 2) return null
        if (lineOnly.matches(text)) return null
        if (bareStation.matches(text)) return null
        if (directionToken.containsMatchIn(text)) return null
        if (text.matches(Regex("""^\d+$"""))) return null
        if (text.endsWith("분") || text.endsWith("정류장") || text.endsWith("대")) return null
        if (text in skipBoardStop) return null
        if (text in busCategory) return null
        if (text.contains("도보") || text.contains("버스") || text.contains("호선")) return null
        if (text.contains("경로") || text.contains("시간표") || text.contains("도착")) return null
        if (text.none { it in '가'..'힣' }) return null
        return text
    }

    private fun log(current: NextSubwayFact?) {
        val text = current?.let {
            "line=${it.line} station=${it.station ?: "-"} " +
                "dir=${it.direction ?: "-"} cars=${it.cars ?: "-"}"
        } ?: "cleared"
        if (text == lastLog) return
        lastLog = text
        try {
            Log.i(TAG, "[NEXT_SUBWAY] $text")
        } catch (_: Throwable) {
        }
    }

    private fun isEndPhrase(blob: String): Boolean {
        val text = blob.trim()
        return text == "길안내를 종료합니다." || text == "길안내를 종료합니다"
    }

    private fun isStartPhrase(blob: String): Boolean =
        startPhrase.containsMatchIn(blob)

    private fun isLiveMarker(blob: String): Boolean =
        blob == "안내 중" || blob == "안내중"

    private fun isAltBoundary(blob: String): Boolean =
        isRouteStartButton(blob) ||
            blob == "최소시간" ||
            blob == "최소환승" ||
            blob.startsWith("최소시간") ||
            blob.startsWith("최소환승")

    private val startPhrase = Regex("""길\s*안내를\s*시작""")
    private val lineOnly = Regex("""^(\d+호선|[가-힣A-Za-z0-9]+선)$""")
    private val bareStation = Regex("""^([가-힣0-9]+역)$""")
    private val directionToken = Regex("""([가-힣0-9]+역)\s*방면""")
    private val boardStopToken = Regex("""^(.+?)\s*승차$""")
    private val lineStationBoard =
        Regex(
            """^((?:\d+호선)|(?:[가-힣A-Za-z0-9]+선))(?:\((급행|급)\))?\s+""" +
                """([가-힣0-9]+역)\s*승차$""",
        )
    private val stationLineBoard =
        Regex("""^([가-힣0-9]+역)\s+((?:\d+호선)|(?:[가-힣A-Za-z0-9]+선))\s*열차\s*승차$""")
    private val carsToken =
        Regex("""빠른\s*(?:하차|환승)\s*[:：]?\s*([0-9]+(?:-[0-9]+)?)""")
    private val alightInParens = Regex("""이번\s*역\s*\(([^)]+)\)""")
    private val transferToLine = Regex("""((?:\d+호선)|(?:[가-힣A-Za-z0-9]+선))\s*으로\s*환승""")
    private val busCategory = setOf(
        "일반", "마을", "지선", "간선", "광역", "급행", "좌석", "직행", "순환",
    )
    private val skipBoardStop = setOf(
        "실시간", "시간표", "시간표 버튼", "하차", "여유",
        "도착정보", "도착정보 버튼", "경로 고정",
        "오금행", "대화행", "포함 3대", "포함 4대", "최적", "기후동행",
        "최소시간", "최소환승", "최소도보",
    )

    private const val TAG = "NEXT_SUBWAY"
}

data class NextSubwayFact(
    val line: String,
    val station: String?,
    val direction: String? = null,
    val cars: String? = null,
    /** `급행` when Naver wrote `9호선(급)`. Absent means the screen did not say. */
    val service: String? = null,
)
