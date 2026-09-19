package nomi.android.traffic

/**
 * Which subway line this journey rides. Policy tier (부록 E), and it owns only
 * this — it has no way to reach the bus ladder, which is 확정 tier. When a trip
 * boards a subway first the bus wait closes itself; see
 * [NaverBusWaitTracker.closeForSubwayTrip].
 */
internal object NaverSubwayPin {

    @Volatile
    private var line: String? = null

    fun pinned(): String? = line

    fun pin(raw: String) {
        val normalized = raw.trim()
        if (normalized.isEmpty()) return
        line = normalized
    }

    /** Unpinned journeys allow any line; a pinned one allows only its own. */
    fun allows(candidate: String): Boolean {
        val pin = line ?: return true
        return pin == candidate.trim()
    }

    fun reset() {
        line = null
    }
}
