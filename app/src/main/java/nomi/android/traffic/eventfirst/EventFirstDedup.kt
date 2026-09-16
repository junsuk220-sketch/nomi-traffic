package nomi.android.traffic.eventfirst

/**
 * Naver posts the same 302 twice on one timestamp, differing only in which
 * side fields (nowbarPrimary / chip / secondary) are filled in. Only
 * `title | text` decides identity, so those re-posts collapse into one event.
 *
 * This is a repeat filter, not a memory of the trip: it holds one key.
 */
internal class EventFirstDedup(private val windowMs: Long = WINDOW_MS) {

    private var lastKey: String? = null
    private var lastAtMs = 0L

    fun isDuplicate(title: String?, text: String?, nowMs: Long): Boolean {
        val key = "${title?.trim().orEmpty()}|${text?.trim().orEmpty()}"
        val repeat = key == lastKey && nowMs - lastAtMs < windowMs
        lastKey = key
        lastAtMs = nowMs
        return repeat
    }

    fun reset() {
        lastKey = null
        lastAtMs = 0L
    }

    companion object {
        /** Long enough for a same-timestamp re-post, short enough to keep ETA updates. */
        const val WINDOW_MS = 2_000L
    }
}
