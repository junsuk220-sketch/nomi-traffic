package nomi.android.traffic.scope

/**
 * Identifies the Naver route the user actually started guiding.
 * Own state only. Does not speak, pin, wait, or transfer.
 *
 * Screen/notification blobs are observed only. Legacy voice does not read this.
 */
object CurrentGuidanceScope {

    private val lock = Any()
    private var active = false
    private var generation = 0
    private var kind = GuidanceKind.NONE
    private var line: String? = null
    /** Set on 길안내를 종료합니다. Start-phrase 302 inside the leftover window is ignored. */
    private var closedAtMs = 0L

    /** Test clock. Production uses wall time. */
    internal var clockMs: () -> Long = { System.currentTimeMillis() }

    fun snapshot(): GuidanceSnapshot = synchronized(lock) {
        snapLocked()
    }

    fun onScreen(blobs: List<String>) {
        val cleaned = blobs.map { it.trim() }.filter { it.isNotEmpty() }
        val hasAlt = cleaned.any { isAltBoundary(it) }
        val alts = altClip(cleaned)
        synchronized(lock) {
            val before = snapLocked()
            if (cleaned.any { isGuidanceEndPhrase(it) }) {
                endLocked()
                debug(before, "screen", "end_phrase", "-", hasAlt, alts)
                return
            }
            val live = cleaned.any { isLiveMarker(it) }
            if (!live) {
                debug(before, "screen", "no_live", "-", hasAlt, alts)
                return
            }
            val opening = !active
            if (opening) {
                generation += 1
                active = true
                closedAtMs = 0L
                kind = GuidanceKind.NONE
                line = null
            }
            val selected = selectedRange(cleaned)
            val vehicle = readVehicle(selected)
            if (vehicle != null) {
                kind = vehicle.kind
                line = vehicle.line
            }
            val cue = if (opening) "live_marker:안내 중" else "live_keep:안내 중"
            debug(before, "screen", cue, CurrentGuidanceScopeDebug.clip(selected), hasAlt, alts)
        }
    }

    fun onNotification(title: String?, text: String?) {
        val fields = listOfNotNull(title?.trim(), text?.trim()).filter { it.isNotEmpty() }
        val hasAlt = fields.any { isAltBoundary(it) }
        val alts = altClip(fields)
        synchronized(lock) {
            val before = snapLocked()
            if (fields.any { isGuidanceEndPhrase(it) }) {
                endLocked()
                debug(before, "notification", "end_phrase", "-", hasAlt, alts)
                return
            }
            if (fields.none { isStartPhrase(it) }) {
                if (active && line == null) {
                    val vehicle = uniqueNotificationVehicle(fields)
                    if (vehicle != null) {
                        kind = vehicle.kind
                        line = vehicle.line
                        debug(before, "notification", "wait_fill", "-", hasAlt, alts)
                        return
                    }
                }
                debug(before, "notification", "no_start", "-", hasAlt, alts)
                return
            }
            if (leftoverStartLocked()) {
                debug(before, "notification", "leftover_start", "-", hasAlt, alts)
                return
            }
            val opening = !active
            if (opening) {
                generation += 1
                active = true
                closedAtMs = 0L
                kind = GuidanceKind.NONE
                line = null
            } else {
                val incoming = uniqueNotificationVehicle(fields)
                if (incoming != null && incoming.line != line) {
                    generation += 1
                    kind = GuidanceKind.NONE
                    line = null
                }
            }
            if (line == null) {
                val vehicle = uniqueNotificationVehicle(fields)
                if (vehicle != null) {
                    kind = vehicle.kind
                    line = vehicle.line
                }
            }
            val cue = if (opening || before.generation != generation) "start_phrase" else "start_keep"
            debug(before, "notification", cue, CurrentGuidanceScopeDebug.clip(fields), hasAlt, alts)
        }
    }

    /** Test hook. Not used by production voice. */
    internal fun resetForTest() {
        synchronized(lock) {
            active = false
            generation = 0
            kind = GuidanceKind.NONE
            line = null
            closedAtMs = 0L
            clockMs = { System.currentTimeMillis() }
        }
        CurrentGuidanceScopeDebug.resetForTest()
    }

    private fun snapLocked(): GuidanceSnapshot = GuidanceSnapshot(
        active = active,
        generation = generation,
        kind = kind,
        line = line,
    )

    private fun debug(
        before: GuidanceSnapshot,
        source: String,
        cue: String,
        selected: String,
        hasAlt: Boolean,
        alts: String,
    ) {
        CurrentGuidanceScopeDebug.transition(
            source = source,
            before = before,
            after = snapLocked(),
            cue = cue,
            selected = selected,
            hasAlt = hasAlt,
            alts = alts,
        )
    }

    private fun altClip(blobs: List<String>): String {
        val from = blobs.indexOfFirst { isAltBoundary(it) }
        if (from < 0) return "-"
        return CurrentGuidanceScopeDebug.clip(blobs.subList(from, blobs.size), 8)
    }

    private fun endLocked() {
        active = false
        kind = GuidanceKind.NONE
        line = null
        closedAtMs = clockMs()
    }

    private fun leftoverStartLocked(): Boolean {
        if (closedAtMs == 0L) return false
        return clockMs() - closedAtMs < LEFTOVER_START_MS
    }

    private data class Vehicle(val kind: GuidanceKind, val line: String)

    private fun selectedRange(blobs: List<String>): List<String> {
        val liveIdx = blobs.indexOfFirst { isLiveMarker(it) }
        if (liveIdx < 0) return emptyList()
        val cardEnd = blobs.indices.firstOrNull { it > liveIdx && isCardEndMarker(blobs[it]) }
        val altAfter = blobs.indices.firstOrNull { it > liveIdx && isAltBoundary(blobs[it]) }
        val until = cardEnd?.plus(1) ?: (altAfter ?: blobs.size)
        val from = if (includeHudPrefix(blobs, liveIdx)) 0 else liveIdx
        return blobs.subList(from, until)
    }

    /**
     * Live HUD prints the boarded vehicle above `안내 중`.
     * Subway and bus wait boards both count. Alt cards in that
     * prefix do not.
     */
    private fun includeHudPrefix(blobs: List<String>, liveIdx: Int): Boolean {
        val prefix = blobs.subList(0, liveIdx)
        if (prefix.any { isAltBoundary(it) }) return false
        if (prefix.any { subwayCue(it) }) return true
        for (i in 0 until liveIdx) {
            if (isBusLine(blobs[i]) && busWaitFollows(blobs, i)) return true
        }
        return false
    }

    private fun readVehicle(blobs: List<String>): Vehicle? {
        var subway: Pair<Int, String>? = null
        var bus: Pair<Int, String>? = null
        for (i in blobs.indices) {
            val blob = blobs[i]
            if (subway == null) {
                subwayLine(blob)?.let { subway = i to it }
            }
            if (bus == null && isBusLine(blob) && busWaitFollows(blobs, i)) {
                bus = i to blob
            }
        }
        val s = subway
        val b = bus
        return when {
            s != null && b != null ->
                if (b.first <= s.first) Vehicle(GuidanceKind.BUS, b.second)
                else Vehicle(GuidanceKind.SUBWAY, s.second)
            s != null -> Vehicle(GuidanceKind.SUBWAY, s.second)
            b != null -> Vehicle(GuidanceKind.BUS, b.second)
            else -> null
        }
    }

    private fun subwayLine(blob: String): String? {
        subwayBoard.find(blob)?.groupValues?.get(1)?.trim()?.let { return it }
        subwayUntil.find(blob)?.groupValues?.get(1)?.trim()?.let { return it }
        subwayExact.matchEntire(blob)?.groupValues?.get(1)?.trim()?.let { return it }
        if (blob.contains("버스")) return null
        return subwayToken.find(blob)?.groupValues?.get(1)?.trim()
    }

    private fun subwayCue(blob: String): Boolean = subwayLine(blob) != null

    private fun busWaitFollows(blobs: List<String>, lineIndex: Int): Boolean {
        val window = blobs.subList(lineIndex + 1, minOf(blobs.size, lineIndex + 8))
        return window.any { stopsOnly.matches(it) || minutesAndStops.matches(it) }
    }

    private fun isLiveMarker(blob: String): Boolean =
        blob == "안내 중" || blob == "안내중"

    private fun isCardEndMarker(blob: String): Boolean =
        blob == "안내 종료" || blob == "안내종료"

    private fun isAltBoundary(blob: String): Boolean =
        blob == "바로 안내시작" || blob == "최소시간" || blob == "최소환승"

    private fun isGuidanceEndPhrase(blob: String): Boolean {
        val text = blob.trim()
        return text == "길안내를 종료합니다." || text == "길안내를 종료합니다"
    }

    private fun isStartPhrase(blob: String): Boolean =
        startPhrase.containsMatchIn(blob)

    private fun isBusLine(blob: String): Boolean = busLine.matches(blob)

    /**
     * 302 wait boards print `999 (9분)` in one blob. Screen flatten
     * still uses the exact line token plus a following stop count.
     * Two buses in one 302 are not this trip yet — the live board picks.
     */
    private fun uniqueNotificationVehicle(fields: List<String>): Vehicle? {
        val found = LinkedHashMap<String, Vehicle>()
        readVehicle(fields)?.let { found[it.line] = it }
        for (blob in fields) {
            for (match in busEtaToken.findAll(blob)) {
                val id = match.groupValues[1].trim()
                if (id.isNotEmpty()) {
                    found.putIfAbsent(id, Vehicle(GuidanceKind.BUS, id))
                }
            }
        }
        if (found.size != 1) return null
        return found.values.first()
    }

    internal const val LEFTOVER_START_MS = 8_000L

    private val subwayBoard =
        Regex("""(\d+호선|[가-힣A-Za-z0-9]+선|GTX-[A-Za-z]).*승차""")
    private val subwayUntil =
        Regex("""(\d+호선|[가-힣A-Za-z0-9]+선|GTX-[A-Za-z]).*까지\s*(걷기|승차)""")
    private val subwayExact =
        Regex("""^(\d+호선|[가-힣A-Za-z0-9]+선|GTX-[A-Za-z])$""")
    private val subwayToken =
        Regex("""(\d+호선|GTX-[A-Za-z])""")
    private val busLine = Regex("""^[A-Za-z]?\d{1,4}[A-Za-z]?$""")
    private val busEtaToken =
        Regex("""([A-Za-z]?\d{1,4}[A-Za-z]?)\s*\(\s*(?:\d+\s*분|곧)""")
    private val minutesAndStops = Regex("""^\d+\s*분\s*\d+\s*정류장""")
    private val stopsOnly = Regex("""^\d+\s*정류장$""")
    private val startPhrase = Regex("""길\s*안내를\s*시작""")
}
