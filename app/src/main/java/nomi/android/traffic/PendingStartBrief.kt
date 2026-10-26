package nomi.android.traffic

/**
 * The one trip-start briefing waiting for its time. A new schedule replaces the old one.
 * Its own timer takes it at any time; anyone else only once the due time has passed.
 */
internal class PendingStartBrief {

    data class Due(val line: String, val bus: Boolean)

    private var id = 0
    private var pending: Due? = null
    private var dueAtMs = 0L

    fun schedule(line: String, bus: Boolean, dueAtMs: Long): Int {
        id += 1
        pending = Due(line, bus)
        this.dueAtMs = dueAtMs
        return id
    }

    fun takeDue(nowMs: Long, timerId: Int? = null): Due? {
        val due = pending ?: return null
        val ownTimer = timerId != null && timerId == id
        if (!ownTimer && nowMs < dueAtMs) return null
        pending = null
        return due
    }

    /** True when a waiting briefing was dropped. */
    fun cancel(): Boolean {
        val had = pending != null
        pending = null
        id += 1
        return had
    }
}
