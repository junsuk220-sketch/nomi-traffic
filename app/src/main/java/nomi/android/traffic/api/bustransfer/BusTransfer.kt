package nomi.android.traffic.api.bustransfer

import nomi.android.traffic.observe.TransitKind
import nomi.android.traffic.observe.TransitReading

data class BusTransferOutcome(
    val text: String? = null,
    /** Debug note. Null while nothing is armed, so idle ticks stay out of the log. */
    val note: String? = null,
    /** When this transfer's `하차 후 환승` arrived. */
    val armedAtMs: Long = 0L,
    /** The sentence only says the minutes are missing. */
    val noInfo: Boolean = false,
)

/**
 * The next vehicle after Naver's `하차 후 환승` is a bus (bus→bus or subway→bus).
 * Names the first row of the next bus board once, when it is more than 10 minutes away,
 * or says its minutes are missing when the board waits (`회차대기`, `정보 없음`).
 * 10 minutes and under belongs to the bus wait ladder (10/5/2/곧).
 * A subway board, 곧 도착 / 도착, or a clock time ends the transfer silently.
 * Walk minutes only when that board printed them. Own state only.
 */
object BusTransfer {

    const val LADDER_TOP_MINUTES = 10
    const val BOARD_WAIT_MS = 180_000L
    /** Same as the subway transfer sentence: never before `하차 후 환승` + 7 s. */
    const val AFTER_TRANSFER_MS = 7_000L

    /** Right on the bus board, but not before `하차 후 환승` + 7 s. */
    fun delayMs(armedAtMs: Long, nowMs: Long): Long =
        maxOf(0L, armedAtMs + AFTER_TRANSFER_MS - nowMs)

    private var armed = false
    private var armedAtMs = 0L
    private var armStop: String? = null
    private var decidedStop: String? = null

    fun consider(cues: List<String>, reading: TransitReading?, nowMs: Long): BusTransferOutcome {
        val cleaned = cues.map { it.trim() }.filter { it.isNotEmpty() }
        val armCue = cleaned.any(::isArmCue)
        if (cleaned.any(::isEndPhrase) || (!armCue && cleaned.any(::isStartPhrase))) {
            val was = armed
            reset()
            return BusTransferOutcome(note = if (was) "reset" else null)
        }
        if (armCue) {
            val stop = cleaned.firstOrNull { !isArmCue(it) }
            if (armed) return BusTransferOutcome(note = "armed_repeat stop=${stop ?: "-"}")
            if (stop != null && stop == decidedStop) {
                return BusTransferOutcome(note = "armed_done stop=$stop")
            }
            armed = true
            armedAtMs = nowMs
            armStop = stop
            return BusTransferOutcome(note = "armed stop=${stop ?: "-"}")
        }
        if (!armed) return BusTransferOutcome()
        if (nowMs - armedAtMs > BOARD_WAIT_MS) {
            decide()
            return BusTransferOutcome(note = "expired")
        }
        if (reading == null) return BusTransferOutcome(note = "wait reading=-")
        return when (reading.kind) {
            TransitKind.SUBWAY -> {
                if (reading.rows.isEmpty()) {
                    BusTransferOutcome(note = "wait kind=SUBWAY rows=0")
                } else {
                    decide()
                    BusTransferOutcome(note = "next_subway")
                }
            }
            TransitKind.BUS -> busBoard(reading)
            else -> BusTransferOutcome(note = "wait kind=${reading.kind}")
        }
    }

    fun reset() {
        armed = false
        armedAtMs = 0L
        armStop = null
        decidedStop = null
    }

    private fun busBoard(reading: TransitReading): BusTransferOutcome {
        val row = reading.rows.firstOrNull { it.line.isNotBlank() && it.eta.isNotBlank() }
            ?: return BusTransferOutcome(note = "wait kind=BUS rows=0")
        decide()
        val eta = row.eta.trim()
        val bus = row.line.trim().let { if (it.endsWith("번")) it else "${it}번" }
        val walk = reading.walkMinutes?.let { " 정류장까지는 걸어서 약 ${it}분입니다." }.orEmpty()
        val where = "line=${row.line} eta=$eta walk=${reading.walkMinutes ?: "-"}"
        val minutes = leadingMinutes.find(eta)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            minutes != null && minutes <= LADDER_TOP_MINUTES ->
                BusTransferOutcome(note = "ladder $where")
            minutes != null -> BusTransferOutcome(
                text = "다음 버스는 $bus, ${minutes}분 후 도착입니다.$walk",
                note = "speak $where",
                armedAtMs = armedAtMs,
            )
            eta.contains("곧") || eta == "도착" -> BusTransferOutcome(note = "soon $where")
            clock.matches(eta) -> BusTransferOutcome(note = "clock $where")
            eta.contains("대기") || eta.contains("정보") -> BusTransferOutcome(
                text = "다음 버스는 $bus, 아직 도착 정보가 없습니다.$walk",
                note = "speak_no_info $where",
                armedAtMs = armedAtMs,
                noInfo = true,
            )
            else -> BusTransferOutcome(note = "unknown_eta $where")
        }
    }

    private fun decide() {
        armed = false
        decidedStop = armStop
    }

    private fun isArmCue(blob: String): Boolean =
        blob.contains("하차 후 환승") && !blob.contains("승차역 부근")

    private fun isEndPhrase(blob: String): Boolean =
        blob == "길안내를 종료합니다." || blob == "길안내를 종료합니다"

    private fun isStartPhrase(blob: String): Boolean = startPhrase.containsMatchIn(blob)

    private val startPhrase = Regex("""길\s*안내를\s*시작""")
    private val leadingMinutes = Regex("""^(\d+)\s*분""")
    private val clock = Regex("""\d{1,2}:\d{2}""")
}
