package nomi.android.traffic.api.routelegs

/** One boarding on a Naver route card. Bus [stop] may be missing on Naver's list. */
data class RouteLeg(
    val bus: Boolean,
    val label: String,
    val stop: String?,
) {
    val cardText: String
        get() = when {
            !bus -> "$label ${stop.orEmpty()}".trim()
            stop.isNullOrBlank() -> "${label}번"
            else -> "${label}번 $stop"
        }
}

data class RouteOption(
    val total: String,
    val summary: String,
    val legs: List<RouteLeg>,
    val running: Boolean,
)

/**
 * Card monitoring only: every boarding of the running Naver route, bus and
 * subway alike. Picks the route from Naver's own list (`안내 중 … 안내 종료`),
 * or right after start by total time + first boarding. Unsure → no note.
 */
object RouteLegs {

    private val options = LinkedHashMap<String, RouteOption>()
    private var chosen: RouteOption? = null
    private var awaitingStart = false
    private var lastStartAtMs = Long.MIN_VALUE
    private var freshChoice = false
    /** Boards read from the live detail when the route list could not pick one. */
    private val liveLegs = ArrayList<RouteLeg>()

    @Synchronized
    fun observe(blobs: List<String>, live: Boolean, nowMs: Long) {
        // freshChoice stays until a destination claims it. The next screen used
        // to wipe it, and the destination often arrives a few seconds later.
        val cleaned = deduped(blobs.map { it.trim() }.filter { it.isNotEmpty() })
        if (cleaned.any { endPhrase.containsMatchIn(it) }) {
            options.clear()
            chosen = null
            liveLegs.clear()
            awaitingStart = false
            lastStartAtMs = Long.MIN_VALUE
            freshChoice = false
        }
        // Naver keeps `길안내를 시작합니다.` on screen for several sweeps; one start per window.
        val hasStart = cleaned.any { startPhrase.containsMatchIn(it) }
        if (hasStart && (lastStartAtMs == Long.MIN_VALUE || nowMs - lastStartAtMs > START_WINDOW_MS)) {
            chosen = null
            liveLegs.clear()
            awaitingStart = true
            freshChoice = false
            RouteLegsDebug.emit("start awaiting options=${options.size}")
        }
        if (hasStart) lastStartAtMs = nowMs
        for (option in RouteOptionParser.parse(cleaned)) {
            if (option.running) {
                if (chosen != option) {
                    freshChoice = true
                    RouteLegsDebug.emit("chosen running total=${option.total} note=${noteOf(option)}")
                }
                chosen = option
                awaitingStart = false
                continue
            }
            options.remove(option.summary)
            options[option.summary] = option
            while (options.size > MAX_OPTIONS) options.remove(options.keys.first())
            RouteLegsDebug.emit("learned options=${options.size}")
        }
        // Totals drift as the clock runs. 2026-10-09 19:27:56, 8 min after the start,
        // a 58분 total matched an option the rider never took.
        if (awaitingStart && nowMs - lastStartAtMs > START_MATCH_MS) {
            awaitingStart = false
            RouteLegsDebug.emit("start window over")
        }
        if (awaitingStart && live && matchStart(cleaned)) freshChoice = true
        if (live && (chosen == null || chosen?.summary == LIVE_DETAIL)) absorbLive(cleaned)
    }

    /** `서해 백마역 · 7호선 부천종합운동장역 · 661번 춘의역6번출구`; null when unsure. */
    @Synchronized
    fun cardNote(): String? = chosen?.let { noteOf(it) }

    /** True until a destination claims this pick. A later destination must not reuse an older, already claimed pick. */
    @Synchronized
    internal fun takeFresh(): Boolean {
        val value = freshChoice
        freshChoice = false
        return value
    }

    @Synchronized
    internal fun resetForTest() {
        options.clear()
        chosen = null
        liveLegs.clear()
        awaitingStart = false
        lastStartAtMs = Long.MIN_VALUE
        freshChoice = false
    }

    /**
     * The route list sometimes names only the last subway (`2호선 당산역`) and
     * hides `9호선(급)`. The live detail prints each board. Used only while no
     * list card was chosen, so a unique list pick is left as it was.
     */
    private fun absorbLive(blobs: List<String>) {
        if (blobs.none { it == "안내 중" || it == "안내중" }) return
        for (i in blobs.indices) {
            val blob = blobs[i]
            liveSubway(blob)?.let { leg ->
                if (liveLegs.none { !it.bus && it.label == leg.label && it.stop == leg.stop }) {
                    liveLegs += leg
                }
                return@let
            }
            liveBus(blob, blobs, i)?.let { leg ->
                if (liveLegs.none { it.bus && it.stop == leg.stop }) liveLegs += leg
            }
        }
        val useful = liveLegs.size >= 2 || liveLegs.firstOrNull()?.bus == false
        if (!useful) return
        val option = RouteOption(
            total = "",
            summary = LIVE_DETAIL,
            legs = liveLegs.toList(),
            running = true,
        )
        if (chosen != option) {
            freshChoice = true
            RouteLegsDebug.emit("chosen live note=${noteOf(option)}")
        }
        chosen = option
        awaitingStart = false
    }

    private fun liveSubway(blob: String): RouteLeg? {
        val match = liveSubwayBoard.matchEntire(blob.trim()) ?: return null
        val line = match.groupValues[1].trim()
        val service = when (match.groupValues[2].trim()) {
            "급", "급행" -> "급행"
            else -> ""
        }
        val station = match.groupValues[3].trim()
        val label = if (service.isEmpty()) line else "$line $service"
        return RouteLeg(bus = false, label = label, stop = station)
    }

    private fun liveBus(blob: String, blobs: List<String>, at: Int): RouteLeg? {
        val match = boardSuffix.find(blob.trim()) ?: return null
        val name = match.groupValues[1].trim()
        if (name.length !in 2..30) return null
        // `킨텍스역1번출구… / 에서 승차`: the stop sits in the blob before.
        if (name == "에서") return null
        if (liveSubwayBoard.matches(blob.trim())) return null
        if (name.contains("호선") || name.endsWith("선")) return null
        val line = busLineNear(blobs, at) ?: return null
        return RouteLeg(bus = true, label = line, stop = name)
    }

    private fun busLineNear(blobs: List<String>, at: Int): String? {
        val from = maxOf(0, at - 6)
        val to = minOf(blobs.size, at + 7)
        for (i in from until to) {
            if (i == at) continue
            val text = blobs[i]
            if (carPair.matches(text)) continue
            if (stopId.matches(text)) continue
            if (busLine.matches(text)) return text
        }
        return null
    }

    private fun noteOf(option: RouteOption): String? {
        val legs = option.legs
        if (legs.isEmpty()) return null
        // A live detail that starts on a subway never showed the first bus.
        val body = if (option.summary == LIVE_DETAIL && legs.first().bus.not()) {
            legs
        } else {
            if (legs.size < 2) return null
            legs.drop(1)
        }
        if (body.isEmpty()) return null
        return body.joinToString(" · ") { it.cardText }
    }

    private fun matchStart(blobs: List<String>): Boolean {
        val board = blobs.firstNotNullOfOrNull { liveBoard(it) }
        val lines = blobs.filter { busLine.matches(it) }.toSet()
        if (board == null && lines.isEmpty()) return false
        val arriveAt = blobs.indexOfFirst { arrivalClock.matches(it) }
        val total = blobs.getOrNull(arriveAt - 1)?.takeIf { duration.matches(it) }
        val sameFirst = options.values.filter { option ->
            val first = option.legs.firstOrNull() ?: return@filter false
            val byStop = board != null && sameBoard(first, board)
            val byLine = first.bus && first.label in lines
            byStop || byLine
        }
        val picks = if (total != null) sameFirst.filter { it.total == total } else emptyList()
        val pool = picks.ifEmpty { sameFirst }
        val notes = pool.map { it.legs }.distinct()
        if (notes.size != 1) {
            RouteLegsDebug.emit(
                "unsure board=${board ?: "-"} total=${total ?: "-"} sameFirst=${sameFirst.size} picks=${picks.size}",
            )
            return false
        }
        chosen = pool.first()
        awaitingStart = false
        RouteLegsDebug.emit("chosen start board=${board ?: "-"} total=${total ?: "-"} note=${noteOf(pool.first())}")
        return true
    }

    /** `3호선 정발산역 승차`, and `일산동부경찰서(중) 승차 중앙` (a word may follow). */
    private fun liveBoard(blob: String): String? {
        val m = boardSuffix.find(blob) ?: return null
        val name = m.groupValues[1].trim()
        if (name.isEmpty() || name.length > 30) return null
        return lineThenStation.matchEntire(name)?.groupValues?.get(2) ?: name
    }

    private fun deduped(blobs: List<String>): List<String> {
        val out = ArrayList<String>(blobs.size)
        for (blob in blobs) {
            if (out.lastOrNull() != blob) out.add(blob)
        }
        return out
    }

    private fun sameBoard(leg: RouteLeg, board: String): Boolean =
        !leg.stop.isNullOrBlank() && leg.stop == board

    private val startPhrase = Regex("""길\s*안내를\s*시작""")
    private val endPhrase = Regex("""길\s*안내를\s*종료""")
    private val boardSuffix = Regex("""(.+?)\s+승차""")
    private val busLine = Regex("""[A-Za-z가-힣]{0,3}\d+[A-Za-z0-9-]*""")
    private val lineThenStation = Regex("""(\d+호선|[가-힣A-Za-z0-9]+선)\s+(.+역)""")
    private val liveSubwayBoard =
        Regex(
            """^((?:\d+호선)|(?:[가-힣A-Za-z0-9]+선))(?:\((급행|급)\))?\s+""" +
                """([가-힣0-9]+역)\s*승차$""",
        )
    private val carPair = Regex("""\d+-\d+""")
    /** Naver prints the 5-digit stop number next to the board (`승차 / 42454 / 308`). */
    private val stopId = Regex("""\d{5}""")
    private const val LIVE_DETAIL = "live-detail"
    private val arrivalClock = Regex("""(?:오전|오후)\s*\d{1,2}:\d{2}\s*도착""")
    private val duration = Regex("""(?:\d+시간\s*)?\d+분""")
    private const val MAX_OPTIONS = 40
    private const val START_WINDOW_MS = 20_000L
    internal const val START_MATCH_MS = 180_000L
}

/**
 * Naver route list card (observed):
 * `[안내 중] / 1시간 27분 / 경로 고정 / 도보 2분, 81번 일반 버스 16분, … / 2분 / 16분 / …
 *  / 일반 / 라페스타.먹자골목 / 81 / 12분 / … / 서해 / 백마역 / 7호선 / 부천종합운동장역
 *  / 간선 / 춘의역6번출구 / 661 / 하차 / 법원.검찰청 / 바로 안내시작|안내 종료`.
 * Cards cut off by scrolling (no closing button) are skipped.
 */
internal object RouteOptionParser {

    fun parse(blobs: List<String>): List<RouteOption> {
        val out = ArrayList<RouteOption>()
        for (i in blobs.indices) {
            if (blobs[i] != PIN) continue
            val totalAt = look(blobs, i - 1, BACK, forward = false) { duration.matches(it) } ?: continue
            val summaryAt = look(blobs, i + 1, FORWARD, forward = true) { it.contains(",") } ?: continue
            val close = (summaryAt + 1 until blobs.size).firstOrNull {
                blobs[it] == START_BUTTON || blobs[it] == END_BUTTON || blobs[it] == PIN
            }
            val running = look(blobs, totalAt - 1, BACK, forward = false) { it == RUNNING } != null
            if (close == null && !running) continue
            if (close != null && blobs[close] == PIN) continue
            val end = close ?: blobs.size
            val legs = legs(blobs.subList(summaryAt + 1, end))
            if (legs.isEmpty()) continue
            out += RouteOption(
                total = blobs[totalAt],
                summary = blobs[summaryAt],
                legs = legs,
                running = running,
            )
        }
        return out
    }

    internal fun legs(tokens: List<String>): List<RouteLeg> {
        val out = ArrayList<RouteLeg>()
        var j = 0
        while (j < tokens.size) {
            val t = tokens[j]
            if (t == ALIGHT || t == START_BUTTON || t == END_BUTTON) break
            if (t in busCategory) {
                val window = tokens.subList(j + 1, minOf(j + 1 + FORWARD, tokens.size))
                val lineAt = window.indexOfFirst { busLine.matches(it) }
                if (lineAt >= 0) {
                    val stop = window.subList(0, lineAt).firstOrNull { isStopName(it) }
                    out += RouteLeg(bus = true, label = window[lineAt], stop = stop)
                    j += 1 + lineAt + 1
                    continue
                }
            }
            if (isSubwayLabel(t)) {
                val window = tokens.subList(j + 1, minOf(j + 1 + FORWARD, tokens.size))
                val stationAt = window.indexOfFirst { stationName.matches(it) }
                if (stationAt >= 0) {
                    out += RouteLeg(bus = false, label = t, stop = window[stationAt])
                    j += 1 + stationAt + 1
                    continue
                }
            }
            j++
        }
        return out
    }

    /** Nearest token matching [pred], skipping up to [limit] others. Stops at a card boundary. */
    private fun look(
        blobs: List<String>,
        from: Int,
        limit: Int,
        forward: Boolean,
        pred: (String) -> Boolean,
    ): Int? {
        var i = from
        var left = limit
        while (i in blobs.indices && left > 0) {
            val text = blobs[i]
            if (pred(text)) return i
            if (text == PIN || text == START_BUTTON || text == END_BUTTON) return null
            i += if (forward) 1 else -1
            left--
        }
        return null
    }

    private fun isStopName(text: String): Boolean {
        if (text.length < 2 || text in busCategory || text in noise) return false
        if (busLine.matches(text) || duration.matches(text)) return false
        if (text.endsWith("분") || text.endsWith("정류장") || text.endsWith("석") || text.endsWith("행")) return false
        if (text.contains("방면") || text.contains("도보") || text.contains("버튼")) return false
        return text.any { it in '가'..'힣' }
    }

    private fun isSubwayLabel(text: String): Boolean {
        if (text in busCategory || text in noise) return false
        if (text.endsWith("행") || text.endsWith("분") || text.endsWith("석")) return false
        return subwayLabel.matches(text)
    }

    private const val PIN = "경로 고정"
    private const val START_BUTTON = "바로 안내시작"
    private const val END_BUTTON = "안내 종료"
    private const val RUNNING = "안내 중"
    private const val ALIGHT = "하차"
    private const val BACK = 6
    private const val FORWARD = 5
    private val duration = Regex("""(?:\d+시간\s*)?\d+분""")
    private val busLine = Regex("""[A-Za-z가-힣]{0,3}\d+[A-Za-z0-9-]*""")
    private val stationName = Regex("""[가-힣A-Za-z0-9]+역""")
    private val subwayLabel = Regex("""\d+호선|[가-힣A-Za-z][가-힣A-Za-z0-9-]{0,7}""")
    private val busCategory = setOf(
        "일반", "마을", "지선", "간선", "광역", "급행", "좌석", "직행", "순환",
        "공항", "심야", "농어촌", "시외", "고속",
    )
    private val noise = setOf(
        "실시간", "시간표", "도착정보", "여유", "보통", "혼잡", "하차", "곧 도착",
    )
}
