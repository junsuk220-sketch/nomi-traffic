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
                debug(before, "notification", "no_start", "-", hasAlt, alts)
                return
            }
            val opening = !active
            if (opening) {
                generation += 1
                active = true
            }
            if (line == null) {
                val vehicle = readVehicle(fields)
                if (vehicle != null) {
                    kind = vehicle.kind
                    line = vehicle.line
                }
            }
            val cue = if (opening) "start_phrase" else "start_keep"
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
    }

    private data class Vehicle(val kind: GuidanceKind, val line: String)

    private fun selectedRange(blobs: List<String>): List<String> {
        val liveIdx = blobs.indexOfFirst { isLiveMarker(it) }
        if (liveIdx < 0) return emptyList()
        val cardEnd = blobs.indices.firstOrNull { it > liveIdx && isCardEndMarker(blobs[it]) }
        if (cardEnd != null) return blobs.subList(liveIdx, cardEnd + 1)
        val altAfter = blobs.indices.firstOrNull { it > liveIdx && isAltBoundary(blobs[it]) }
        val until = altAfter ?: blobs.size
        val prefix = blobs.subList(0, liveIdx)
        val hudPrefix = prefix.any { subwayCue(it) } && prefix.none { isAltBoundary(it) }
        val from = if (hudPrefix) 0 else liveIdx
        return blobs.subList(from, until)
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

    private val subwayBoard =
        Regex("""(\d+호선|[가-힣A-Za-z0-9]+선|GTX-[A-Za-z]).*승차""")
    private val subwayUntil =
        Regex("""(\d+호선|[가-힣A-Za-z0-9]+선|GTX-[A-Za-z]).*까지\s*(걷기|승차)""")
    private val subwayExact =
        Regex("""^(\d+호선|[가-힣A-Za-z0-9]+선|GTX-[A-Za-z])$""")
    private val subwayToken =
        Regex("""(\d+호선|GTX-[A-Za-z])""")
    private val busLine = Regex("""^[A-Za-z]?\d{1,4}[A-Za-z]?$""")
    private val minutesAndStops = Regex("""^\d+\s*분\s*\d+\s*정류장""")
    private val stopsOnly = Regex("""^\d+\s*정류장$""")
    private val startPhrase = Regex("""길\s*안내를\s*시작""")
}
