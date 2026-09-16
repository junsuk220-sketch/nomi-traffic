package nomi.android.traffic.eventfirst

import java.io.File

/**
 * `[EVENT_FIRST]` decision trail: timestamp, event, scope, state, decision,
 * reason. Separate file from the ride observer so field capture keeps working
 * unchanged; same rotate-by-age-and-size shape.
 */
internal object EventFirstLog {

    const val FILE_NAME = "nomi-eventfirst.jsonl"
    const val MAX_AGE_MS = 24L * 60L * 60L * 1000L
    const val MAX_BYTES = 1L * 1024L * 1024L

    @Volatile
    private var dir: File? = null
    private val lock = Any()

    fun attach(filesDir: File) {
        synchronized(lock) { dir = filesDir }
    }

    fun resetForTest(filesDir: File) {
        synchronized(lock) {
            dir = filesDir
            File(filesDir, FILE_NAME).delete()
        }
    }

    fun append(line: String, nowMs: Long) {
        try {
            synchronized(lock) {
                val folder = dir ?: return
                val out = File(folder, FILE_NAME)
                out.appendText(line + "\n")
                trim(out, nowMs)
            }
        } catch (_: Exception) {
        }
    }

    /** One JSONL record, also used verbatim as the Logcat body. */
    fun encode(
        nowMs: Long,
        title: String?,
        text: String?,
        event: NaverTransitEvent?,
        state: EventFirstState,
        decision: EventFirstDecision,
        spoken: String?,
    ): String = buildString {
        append("{\"ts\":").append(nowMs)
        append(",\"title\":").append(q(title))
        append(",\"text\":").append(q(text))
        append(",\"event\":").append(q(event?.javaClass?.simpleName))
        append(",\"scope\":").append(q(event?.scopeKey))
        append(",\"speak\":").append(decision.speak)
        append(",\"reason\":").append(q(decision.reason.name))
        append(",\"speech\":").append(q(decision.speechType?.name))
        append(",\"reading\":").append(q(state.reading?.let { "${it.line}:${it.eta}" }))
        append(",\"marks\":").append(q(marks(state, event?.scopeKey)))
        append(",\"said\":").append(q(spoken))
        append('}')
    }

    private fun marks(state: EventFirstState, scopeKey: String?): String? {
        if (scopeKey == null) return null
        val marks = state.marks(scopeKey)
        if (marks.isEmpty()) return null
        return marks.joinToString(",") { it.name }
    }

    private fun trim(out: File, nowMs: Long) {
        if (!out.exists()) return
        if (out.length() <= MAX_BYTES) return
        val kept = out.readLines().filter { line ->
            val ts = readTs(line) ?: return@filter false
            nowMs - ts <= MAX_AGE_MS
        }.toMutableList()
        var bytes = kept.sumOf { it.length + 1L }
        while (bytes > MAX_BYTES && kept.isNotEmpty()) {
            bytes -= kept.removeAt(0).length + 1L
        }
        out.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n", postfix = "\n"))
    }

    private fun readTs(line: String): Long? {
        val key = "\"ts\":"
        val start = line.indexOf(key)
        if (start < 0) return null
        val from = start + key.length
        val end = line.indexOf(',', from).let { if (it < 0) line.indexOf('}', from) else it }
        if (end < 0) return null
        return line.substring(from, end).trim().toLongOrNull()
    }

    private fun q(raw: String?): String {
        if (raw == null) return "null"
        val escaped = raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return "\"$escaped\""
    }
}
