package nomi.android.traffic

/**
 * Google took its ongoing guidance notification away. It also rebuilds that
 * card by removing it and posting again (field 2026-11-01 ride: trip_end then
 * the same ride count spoke again in 4 ms). Ending on the remove wiped every
 * cell. Hold the end: an ongoing Google card back within [REPOST_GRACE_MS]
 * cancels it. 2026-11-02 83번: the card went quiet mid-ride and left for good
 * only later — a short grace still ends a true finish, and keeps a brief gap
 * from resetting the ride.
 */
internal object GoogleGuidanceRemoved {

    /** Longer than Naver: Google's foreground card can sit away while it rebuilds. */
    const val REPOST_GRACE_MS = 30_000L

    private var pendingEnd: (() -> Unit)? = null
    private var removedAtMs = 0L

    @Synchronized
    fun hold(nowMs: Long, end: () -> Unit) {
        pendingEnd = end
        removedAtMs = nowMs
    }

    /** An ongoing Google guidance card came back. True when it cancelled a held end. */
    @Synchronized
    fun onPosted(): Boolean {
        if (pendingEnd == null) return false
        pendingEnd = null
        return true
    }

    fun flush() {
        val held = synchronized(this) {
            val end = pendingEnd ?: return
            pendingEnd = null
            end
        }
        held()
    }

    fun expire(nowMs: Long) {
        val due = synchronized(this) {
            pendingEnd != null && nowMs - removedAtMs >= REPOST_GRACE_MS
        }
        if (due) flush()
    }

    @Synchronized
    fun isHolding(): Boolean = pendingEnd != null

    @Synchronized
    internal fun resetForTest() {
        pendingEnd = null
        removedAtMs = 0L
    }
}
