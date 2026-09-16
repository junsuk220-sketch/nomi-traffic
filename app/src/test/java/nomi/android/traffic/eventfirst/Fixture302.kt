package nomi.android.traffic.eventfirst

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Reads a captured 302 log. The fixture holds the observation log's own
 * `ts` / `title` / `text` values verbatim — nothing is normalised here, so a
 * replay sees exactly what the device saw.
 */
internal object Fixture302 {

    data class Record(val ts: Long, val title: String?, val text: String?)

    fun load(resource: String): List<Record> {
        val stream = Fixture302::class.java.classLoader?.getResourceAsStream(resource)
            ?: error("fixture not found: $resource")
        return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { record(it) }
                .toList()
        }
    }

    /** The capture happened in Asia/Seoul; pin it so the test reads the same clock. */
    fun clock(ts: Long): String =
        FORMAT.format(Instant.ofEpochMilli(ts).atZone(SEOUL))

    private fun record(line: String): Record {
        val ts = number(line, "ts") ?: error("no ts in $line")
        return Record(ts = ts, title = string(line, "title"), text = string(line, "text"))
    }

    private fun number(line: String, key: String): Long? {
        val at = line.indexOf("\"$key\":")
        if (at < 0) return null
        val from = at + key.length + 3
        val end = line.indexOfFirst(from) { it == ',' || it == '}' }
        return line.substring(from, end).trim().toLongOrNull()
    }

    private fun string(line: String, key: String): String? {
        val at = line.indexOf("\"$key\":")
        if (at < 0) return null
        var index = at + key.length + 3
        while (index < line.length && line[index] == ' ') index++
        if (line.startsWith("null", index)) return null
        if (line[index] != '"') return null
        index++
        val out = StringBuilder()
        while (index < line.length) {
            val c = line[index]
            when {
                c == '\\' -> {
                    index++
                    when (val escaped = line[index]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'u' -> {
                            out.append(line.substring(index + 1, index + 5).toInt(16).toChar())
                            index += 4
                        }
                        else -> out.append(escaped)
                    }
                }
                c == '"' -> return out.toString()
                else -> out.append(c)
            }
            index++
        }
        return out.toString()
    }

    private inline fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
        for (i in from until length) if (predicate(this[i])) return i
        return length
    }

    private val SEOUL = ZoneId.of("Asia/Seoul")
    private val FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")
}
