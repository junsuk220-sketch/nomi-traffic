package nomi.android.traffic.buswait

import nomi.product.nav.NavigationBusArrival
import java.io.File

/**
 * Writes observe() input and Tick fields. Does not choose a bus.
 */
internal object BusWaitFieldTrace {

    const val FILE_NAME = "nomi-buswait-trace.jsonl"
    const val HEARTBEAT_MS = 10_000L
    const val MAX_AGE_MS = 24L * 60L * 60L * 1000L
    const val MAX_BYTES = 2L * 1024L * 1024L

    @Volatile
    private var dir: File? = null
    private val lock = Any()
    private var lastKey: String? = null
    private var lastWriteAtMs = 0L

    fun attach(filesDir: File) {
        synchronized(lock) { dir = filesDir }
    }

    fun resetForTest(filesDir: File) {
        synchronized(lock) {
            dir = filesDir
            lastKey = null
            lastWriteAtMs = 0L
            file().delete()
        }
    }

    fun file(): File = File(dir ?: error("BusWaitFieldTrace not attached"), FILE_NAME)

    fun record(
        nowMs: Long,
        source: String?,
        stop: String?,
        pinned: Set<String>,
        seeded: Set<String>,
        prevLine: String?,
        prevEta: String?,
        arrivals: List<NavigationBusArrival>,
        targetLine: String?,
        targetEta: String?,
        switched: Boolean,
        speakStage: Int?,
        silence: BusWaitSilence?,
        path: BusWaitTracePath?,
    ) {
        try {
            synchronized(lock) {
                val folder = dir ?: return
                val key = buildString {
                    append(source)
                    append('|')
                    append(stop)
                    append('|')
                    append(path)
                    append('|')
                    append(targetLine)
                    append(':')
                    append(targetEta)
                    append('|')
                    arrivals.forEach { append(it.line).append(':').append(it.eta).append(',') }
                }
                if (!switched && key == lastKey && nowMs - lastWriteAtMs < HEARTBEAT_MS) {
                    return
                }
                val line = encode(
                    nowMs = nowMs,
                    source = source,
                    stop = stop,
                    pinned = pinned,
                    seeded = seeded,
                    prevLine = prevLine,
                    prevEta = prevEta,
                    arrivals = arrivals,
                    targetLine = targetLine,
                    targetEta = targetEta,
                    switched = switched,
                    speakStage = speakStage,
                    silence = silence,
                    path = path,
                )
                val out = File(folder, FILE_NAME)
                out.appendText(line + "\n")
                lastKey = key
                lastWriteAtMs = nowMs
                trim(out, nowMs)
            }
        } catch (_: Exception) {
        }
    }

    private fun trim(out: File, nowMs: Long) {
        if (!out.exists()) return
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

    private fun encode(
        nowMs: Long,
        source: String?,
        stop: String?,
        pinned: Set<String>,
        seeded: Set<String>,
        prevLine: String?,
        prevEta: String?,
        arrivals: List<NavigationBusArrival>,
        targetLine: String?,
        targetEta: String?,
        switched: Boolean,
        speakStage: Int?,
        silence: BusWaitSilence?,
        path: BusWaitTracePath?,
    ): String {
        val seen = arrivals.joinToString(",") { a ->
            "{\"line\":${q(a.line)},\"eta\":${q(a.eta)}}"
        }
        return buildString {
            append("{\"ts\":").append(nowMs)
            append(",\"src\":").append(q(source))
            append(",\"stop\":").append(q(stop))
            append(",\"pin\":").append(strings(pinned))
            append(",\"seed\":").append(strings(seeded))
            append(",\"prev\":").append(q(listOf(prevLine, prevEta).filter { !it.isNullOrEmpty() }.joinToString(":")))
            append(",\"tgt\":").append(q(listOf(targetLine, targetEta).filter { !it.isNullOrEmpty() }.joinToString(":")))
            append(",\"sw\":").append(switched)
            append(",\"stage\":").append(speakStage?.toString() ?: "null")
            append(",\"sil\":").append(q(silence?.name))
            append(",\"path\":").append(q(path?.name))
            append(",\"seen\":[").append(seen).append("]}")
        }
    }

    private fun strings(values: Set<String>): String =
        values.joinToString(",", "[", "]") { q(it) }

    private fun q(raw: String?): String {
        if (raw == null) return "null"
        val escaped = raw.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }
}
