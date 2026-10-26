package nomi.android.traffic

import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Field-only Google Maps ride observer. Writes every screen text and Google
 * notification to filesDir JSONL. Does not parse, speak, or change any state.
 * Keeps all tokens (no filter) because Google's wording is not known yet.
 */
internal object GoogleRideObservationLog {

    const val FILE_NAME = "google-ride-observe.jsonl"
    const val ROTATE_NAME = "google-ride-observe.1.jsonl"
    const val MAX_BYTES = 4L * 1024L * 1024L
    const val TREE_HEARTBEAT_MS = 20_000L
    const val MAX_BLOBS = 200
    const val MAX_BLOB_CHARS = 160

    private val lock = Any()
    private var dir: File? = null
    private var lastTreeKey: String? = null
    private var lastTreeAtMs = 0L
    private var lastNotifKey: String? = null

    private val executor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "google-ride-observe").apply { isDaemon = true }
    }

    fun attach(filesDir: File) {
        if (!FieldLogs.enabled) return
        synchronized(lock) { dir = filesDir }
    }

    fun recordTree(ts: Long, live: Boolean, blobs: List<String>) {
        val kept = blobs.mapNotNull { clip(it) }.take(MAX_BLOBS)
        val key = "$live|${kept.joinToString("\u0001")}"
        synchronized(lock) {
            if (key == lastTreeKey && ts - lastTreeAtMs < TREE_HEARTBEAT_MS) return
            lastTreeKey = key
            lastTreeAtMs = ts
        }
        submit(
            buildString {
                append("{\"ts\":").append(ts)
                append(",\"src\":\"a11y_tree\",\"live\":").append(live)
                append(",\"blobs\":[")
                kept.forEachIndexed { i, blob ->
                    if (i > 0) append(',')
                    append(q(blob))
                }
                append("]}")
            },
        )
    }

    fun recordClick(ts: Long, text: String?, contentDescription: String?) {
        submit(
            buildString {
                append("{\"ts\":").append(ts)
                append(",\"src\":\"click\"")
                clip(text)?.let { append(",\"text\":").append(q(it)) }
                clip(contentDescription)?.let { append(",\"desc\":").append(q(it)) }
                append('}')
            },
        )
    }

    fun recordNotification(ts: Long, id: Int, channel: String?, fields: Map<String, String?>) {
        val kept = fields.mapNotNull { (k, v) -> clip(v)?.let { k to it } }
        val key = "$id|$channel|" + kept.joinToString("|") { "${it.first}=${it.second}" }
        synchronized(lock) {
            if (key == lastNotifKey) return
            lastNotifKey = key
        }
        submit(
            buildString {
                append("{\"ts\":").append(ts)
                append(",\"src\":\"notif\",\"id\":").append(id)
                channel?.let { append(",\"channel\":").append(q(it)) }
                kept.forEach { (k, v) -> append(",").append(q(k)).append(':').append(q(v)) }
                append('}')
            },
        )
    }

    fun recordNotificationRemoved(ts: Long, id: Int, channel: String?) {
        submit(
            buildString {
                append("{\"ts\":").append(ts)
                append(",\"src\":\"notif_removed\",\"id\":").append(id)
                channel?.let { append(",\"channel\":").append(q(it)) }
                append('}')
            },
        )
    }

    private fun submit(line: String) {
        executor.execute {
            synchronized(lock) {
                val folder = dir ?: return@execute
                try {
                    val out = File(folder, FILE_NAME)
                    if (out.exists() && out.length() >= MAX_BYTES) {
                        val rotated = File(folder, ROTATE_NAME)
                        rotated.delete()
                        out.renameTo(rotated)
                    }
                    File(folder, FILE_NAME).appendText(line + "\n")
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun clip(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return if (text.length <= MAX_BLOB_CHARS) text else text.substring(0, MAX_BLOB_CHARS)
    }

    private fun q(raw: String): String {
        val escaped = raw
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        return "\"$escaped\""
    }
}
