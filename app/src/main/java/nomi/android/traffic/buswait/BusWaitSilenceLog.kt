package nomi.android.traffic.buswait

/**
 * Folds repeated skip lines. Not a speech timer, ETA rule, or dedup store.
 */
internal object BusWaitSilenceLog {

    internal const val HEARTBEAT_MS = 10_000L

    data class State(
        val message: String? = null,
        val startedAtMs: Long = 0L,
        val lastEmitAtMs: Long = 0L,
    )

    data class Next(
        val state: State,
        val line: String?,
    )

    fun next(
        state: State,
        message: String,
        nowMs: Long,
        heartbeatMs: Long = HEARTBEAT_MS,
    ): Next {
        if (message != state.message) {
            return Next(State(message, nowMs, nowMs), message)
        }
        if (nowMs - state.lastEmitAtMs >= heartbeatMs) {
            val elapsed = nowMs - state.startedAtMs
            return Next(
                state.copy(lastEmitAtMs = nowMs),
                "$message silentForMs=$elapsed",
            )
        }
        return Next(state, null)
    }
}
