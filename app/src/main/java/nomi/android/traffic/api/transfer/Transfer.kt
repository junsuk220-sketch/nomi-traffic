package nomi.android.traffic.api.transfer

import nomi.android.traffic.TransitSoonEta
import nomi.android.traffic.api.nextsubway.NextSubwayFact
import nomi.android.traffic.observe.TransitKind
import nomi.android.traffic.observe.TransitReading
import nomi.android.traffic.observe.TransitRow
import nomi.android.traffic.speech.SpeechRequest

/**
 * Transfer briefings on two cues:
 * - Alight/transfer: Seoul arrival minutes once ([considerSeoul]).
 * - Near board (`승차역 부근`): Naver sheet minutes once ([consider] with reading).
 * Naver cues name station, line, and cars. Own state only.
 */
object Transfer {

    private var armed = false
    private var spoken = false
    private var nearBoardArmed = false
    private var nearBoardSpoken = false
    private var board: TransferBoard? = null
    private var nextLine: String? = null
    private var armKey: String? = null
    private var lastArmCueAtMs = 0L
    private var armGeneration = 0
    private var seoulWaitEnded = false

    /**
     * @param chain subway boards of the running route in order. When not
     * empty, each new `하차 후 환승` takes its board from [takeTarget]
     * (one transfer after another). Empty keeps the single-subway path.
     * @param startLeg first leg when the route starts on a subway.
     */
    fun consider(
        cues: List<String>,
        reading: TransitReading?,
        next: NextSubwayFact? = null,
        nowMs: Long = 0L,
        chain: List<NextSubwayFact> = emptyList(),
        startLeg: NextSubwayFact? = null,
        takeTarget: (List<String>) -> NextSubwayFact? = { null },
    ): TransferOutcome {
        val cleaned = cues.map { it.trim() }.filter { it.isNotEmpty() }
        val chainMode = chain.isNotEmpty()
        val armCue = cleaned.any { isArmCue(it) }
        val nearCue = cleaned.any { isNearBoardCue(it) }
        if (cleaned.any { isEndPhrase(it) }) {
            resetLocked()
            return TransferOutcome(silence = TransferSilence.RESET)
        }
        if (cleaned.any { isStartPhrase(it) } && !armCue && !nearCue) {
            resetLocked()
            return TransferOutcome(silence = TransferSilence.RESET)
        }
        if (chainMode) {
            if (!armed && board == null) board = startLeg?.toBoard()
            enrichBoard(cleaned)
        } else {
            rememberFact(next)
            rememberBoard(cleaned)
        }
        if (armCue) {
            val key = alightKey(cleaned)
            val keyChanged = key != null && armKey != null && key != armKey
            val gap = lastArmCueAtMs > 0L && nowMs - lastArmCueAtMs > REARM_GAP_MS
            val newArm = !armed || (chainMode && (keyChanged || gap))
            if (armKey == null && key != null) armKey = key
            lastArmCueAtMs = nowMs
            if (newArm) {
                armed = true
                spoken = false
                seoulWaitEnded = false
                nearBoardArmed = false
                nearBoardSpoken = false
                armKey = key
                armGeneration += 1
                if (chainMode) {
                    val target = takeTarget(cleaned)
                    board = target?.toBoard()
                    nextLine = target?.line
                }
            }
        }
        if (armed && spoken && cleaned.any(::isRidingCue)) {
            seoulWaitEnded = true
        }
        if (nearCue && subwayKnown() && !nearBoardSpoken) {
            nearBoardArmed = true
        }
        val nearSpeak = considerNearBoard(reading)
        if (nearSpeak.request != null) {
            return nearSpeak
        }
        if (!armed) {
            if (nearBoardArmed && !nearBoardSpoken) {
                return TransferOutcome(
                    silence = if (nearCue) TransferSilence.NEAR_ARMED
                    else TransferSilence.NEAR_WAIT,
                )
            }
            if (nearBoardSpoken) {
                return TransferOutcome(silence = TransferSilence.NEAR_ALREADY)
            }
            return TransferOutcome(silence = TransferSilence.INACTIVE)
        }
        if (!spoken) {
            if (!subwayKnown()) {
                return TransferOutcome(silence = TransferSilence.NOT_SUBWAY)
            }
            // Alight still needs Seoul; keep ARMED/WAIT so the host can fetch.
            if (armCue) {
                return TransferOutcome(silence = TransferSilence.ARMED)
            }
            return TransferOutcome(silence = TransferSilence.WAIT_ETA)
        }
        if (nearBoardArmed && !nearBoardSpoken) {
            return TransferOutcome(
                silence = if (nearCue) TransferSilence.NEAR_ARMED
                else TransferSilence.NEAR_WAIT,
            )
        }
        if (nearBoardSpoken) {
            return TransferOutcome(silence = TransferSilence.NEAR_ALREADY)
        }
        return TransferOutcome(silence = TransferSilence.ALREADY)
    }

    /**
     * Seoul realtime rows after alight arm. Speaks once.
     * Does not invent rows. Does not use Naver minutes.
     */
    fun considerSeoul(rows: List<TransitRow>): TransferOutcome {
        if (!armed) {
            return TransferOutcome(silence = TransferSilence.INACTIVE)
        }
        if (spoken) {
            return TransferOutcome(silence = TransferSilence.ALREADY)
        }
        val filtered = filterLineRows(rows)
        if (filtered.isEmpty()) {
            return TransferOutcome(silence = TransferSilence.WAIT_ETA)
        }
        return speakAlight(filtered)
    }

    fun board(): TransferBoard? = board

    fun needsSeoulFetch(): Boolean = armed && !spoken && board != null

    fun mayTrackSeoulCountdown(): Boolean =
        armed && spoken && board != null && !seoulWaitEnded

    /**
     * This alight belongs to Seoul, including after the Seoul line has played.
     * The Naver walk-sheet briefing for the same line must not also speak.
     * 10/5/2/곧 stays on its own path. A new guidance start clears this.
     */
    fun seoulOwnsAlight(line: String?): Boolean {
        val want = line?.trim().orEmpty()
        if (!armed || want.isEmpty()) return false
        return board?.line == want
    }

    fun alreadySpoken(): Boolean = spoken

    fun alreadyNearBoardSpoken(): Boolean = nearBoardSpoken

    fun mayPlayBriefing(): Boolean = spoken || nearBoardSpoken

    /** Bumps on every new `하차 후 환승`. Hosts reset their fetch state on change. */
    fun armGeneration(): Int = armGeneration

    private fun NextSubwayFact.toBoard(): TransferBoard? {
        val st = station?.trim().orEmpty()
        val ln = line.trim()
        if (st.isEmpty() || ln.isEmpty()) return null
        return TransferBoard(
            station = st,
            line = ln,
            direction = direction,
            cars = cars,
            service = service,
        )
    }

    /** Fills direction/cars only when the cue names the same board. */
    private fun enrichBoard(cues: List<String>) {
        val current = board ?: return
        val parsed = TransferBoardParser.parse(cues) ?: return
        if (parsed.station != current.station) return
        board = current.copy(
            direction = current.direction ?: parsed.direction,
            cars = current.cars ?: parsed.cars,
        )
    }

    private fun alightKey(cues: List<String>): String? =
        cues.firstNotNullOfOrNull { alightInParens.find(it)?.groupValues?.get(1)?.trim() }
            ?.removeSuffix("역")
            ?.takeIf { it.isNotEmpty() }

    internal fun resetForTest() {
        resetLocked()
    }

    private fun considerNearBoard(reading: TransitReading?): TransferOutcome {
        if (!nearBoardArmed) {
            return TransferOutcome(silence = TransferSilence.INACTIVE)
        }
        if (nearBoardSpoken) {
            return TransferOutcome(silence = TransferSilence.NEAR_ALREADY)
        }
        if (!subwayKnown()) {
            return TransferOutcome(silence = TransferSilence.NOT_SUBWAY)
        }
        if (reading == null || reading.kind != TransitKind.SUBWAY) {
            return TransferOutcome(silence = TransferSilence.NEAR_WAIT)
        }
        val filtered = filterLineRows(reading.rows)
        if (filtered.isEmpty()) {
            return TransferOutcome(silence = TransferSilence.NEAR_WAIT)
        }
        return speakNearBoard(filtered)
    }

    private fun filterLineRows(rows: List<TransitRow>): List<TransitRow> {
        val want = board?.line?.trim().orEmpty()
        return if (want.isEmpty()) {
            rows.filter { it.line.isNotBlank() && it.eta.isNotBlank() }
        } else {
            rows.filter { it.line == want && it.eta.isNotBlank() }
        }
    }

    private fun speakAlight(rows: List<TransitRow>): TransferOutcome {
        val text = briefingText(rows) ?: return TransferOutcome(
            silence = TransferSilence.NO_TEXT,
        )
        spoken = true
        return TransferOutcome(request = SpeechRequest(text))
    }

    private fun speakNearBoard(rows: List<TransitRow>): TransferOutcome {
        val text = briefingText(rows) ?: return TransferOutcome(
            silence = TransferSilence.NO_TEXT,
        )
        nearBoardSpoken = true
        return TransferOutcome(request = SpeechRequest(text))
    }

    private fun briefingText(rows: List<TransitRow>): String? {
        val soonest = pickSoonest(rows) ?: return null
        val routeService = board?.service?.trim().orEmpty()
        val first = if (soonest.service.isNullOrBlank() && routeService.isNotEmpty()) {
            soonest.copy(service = routeService)
        } else {
            soonest
        }
        val next = pickSoonest(
            rows.filterNot {
                it.line == first.line && it.eta == first.eta && it.service == soonest.service
            },
        )
        return TransferLine.text(first = first, next = next)
    }

    private fun rememberBoard(cues: List<String>) {
        val parsed = TransferBoardParser.parse(cues) ?: return
        val prev = board
        board = TransferBoard(
            station = parsed.station,
            line = parsed.line,
            direction = parsed.direction ?: prev?.direction,
            cars = parsed.cars ?: prev?.cars,
            service = parsed.service ?: prev?.service,
        )
    }

    private fun rememberFact(next: NextSubwayFact?) {
        val line = next?.line?.trim().orEmpty()
        if (line.isNotEmpty()) nextLine = line
        val station = next?.station?.trim().orEmpty()
        if (station.isEmpty() && line.isEmpty()) {
            if (next == null || board == null) return
            val current = board ?: return
            board = current.copy(
                direction = current.direction ?: next.direction,
                cars = current.cars ?: next.cars,
                service = current.service ?: next.service,
            )
            return
        }
        if (station.isEmpty()) return
        val prev = board
        board = TransferBoard(
            station = prev?.station ?: station,
            line = prev?.line?.takeIf { it.isNotBlank() } ?: line,
            direction = prev?.direction ?: next?.direction,
            cars = prev?.cars ?: next?.cars,
            service = prev?.service ?: next?.service,
        )
    }

    private fun subwayKnown(): Boolean =
        !board?.station.isNullOrBlank() ||
            !board?.line.isNullOrBlank() ||
            !nextLine.isNullOrBlank()

    private fun resetLocked() {
        armed = false
        spoken = false
        nearBoardArmed = false
        nearBoardSpoken = false
        board = null
        nextLine = null
        armKey = null
        lastArmCueAtMs = 0L
        seoulWaitEnded = false
    }

    internal fun isArmCue(blob: String): Boolean {
        val text = blob.trim()
        if (isNearBoardCue(text)) return false
        return text.contains("하차 후 환승")
    }

    internal fun isNearBoardCue(blob: String): Boolean {
        val text = blob.trim()
        return text.contains("승차역 부근")
    }

    private fun isEndPhrase(blob: String): Boolean {
        val text = blob.trim()
        return text == "길안내를 종료합니다." || text == "길안내를 종료합니다"
    }

    private fun isStartPhrase(blob: String): Boolean =
        startPhrase.containsMatchIn(blob)

    private fun isRidingCue(blob: String): Boolean {
        val text = blob.trim()
        return text.endsWith("역 정차") || text.contains("역으로 이동 중")
    }

    private fun pickSoonest(rows: List<TransitRow>): TransitRow? =
        rows.minWithOrNull(
            compareBy { TransitSoonEta.minutes(it.eta) ?: Int.MAX_VALUE },
        )

    private val startPhrase = Regex("""길\s*안내를\s*시작""")
    private val alightInParens = Regex("""이번\s*역\s*\(([^)]+)\)""")

    /** Two alights of one ride are always minutes apart. */
    private const val REARM_GAP_MS = 90_000L
}
