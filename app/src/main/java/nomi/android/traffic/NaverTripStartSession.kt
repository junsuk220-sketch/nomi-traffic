package nomi.android.traffic

/**
 * One Naver trip-start briefing per guidance.
 * Notification / live-edge never reopen a spoken session.
 * A new 안내시작 tap always starts a fresh briefing.
 */
internal object NaverTripStartSession {

    const val ARM_DELAY_MS = 3_500L
    const val BUS_HOLD_AFTER_SPEAK_MS = 12_000L
    const val NOT_LIVE_END_MS = 1_500L
    const val GIVE_UP_AFTER_DUE_MS = 8_000L

    private var state = State.IDLE
    private var readyAtMs = 0L
    private var holdBusUntilMs = 0L
    private var notLiveSinceMs = 0L
    private var lastLiveWindowId: Long? = null

    fun reset() {
        state = State.IDLE
        readyAtMs = 0L
        holdBusUntilMs = 0L
        notLiveSinceMs = 0L
        lastLiveWindowId = null
    }

    /** Last window where 안내 중 was seen. Android window id as Long. */
    fun noteLiveWindow(windowId: Long) {
        lastLiveWindowId = windowId
    }

    /**
     * Same-window 안내 중 gone. Ends the session so the next 안내시작 can speak again.
     * A different windowId is not end evidence — do not start or fire the timer.
     */
    fun noteNotLive(nowMs: Long, windowId: Long? = null): Boolean {
        val last = lastLiveWindowId
        if (last != null && windowId != null && last != windowId) return false
        if (notLiveSinceMs == 0L) notLiveSinceMs = nowMs
        if (state == State.IDLE) return false
        if (nowMs - notLiveSinceMs < NOT_LIVE_END_MS) return false
        reset()
        return true
    }

    /** Live guidance. Arms once if idle. Flicker back to live does not re-arm. */
    fun noteLive(nowMs: Long, windowId: Long? = null): Boolean {
        if (windowId != null) noteLiveWindow(windowId)
        notLiveSinceMs = 0L
        if (state != State.IDLE) return false
        request(nowMs)
        return true
    }

    /**
     * Sheet is still empty after the arm delay. Lift the wait hold so 10/5/2
     * can start, but stay armed — a late sheet or 302 can still brief.
     */
    fun giveUpIfStale(nowMs: Long): Boolean {
        if (state != State.ARMED) return false
        if (holdBusUntilMs == 0L) return false
        if (nowMs - readyAtMs < GIVE_UP_AFTER_DUE_MS) return false
        holdBusUntilMs = 0L
        return true
    }

    /** Click or notification start phrase. No-op if already armed or spoken. */
    fun request(nowMs: Long): Boolean {
        notLiveSinceMs = 0L
        if (state != State.IDLE) return false
        state = State.ARMED
        readyAtMs = nowMs + ARM_DELAY_MS
        holdBusUntilMs = readyAtMs + BUS_HOLD_AFTER_SPEAK_MS
        return true
    }

    /**
     * User tapped 안내시작 on a new route. Always open a fresh briefing,
     * even if the previous trip spoke a few seconds ago.
     */
    fun restartFromClick(nowMs: Long): Boolean {
        reset()
        return request(nowMs)
    }

    fun due(nowMs: Long): Boolean = state == State.ARMED && nowMs >= readyAtMs

    fun hasSpokenBriefing(): Boolean = state == State.SPOKEN

    /** Read-only snapshot for field trip-start tracing. */
    fun debugSnapshot(nowMs: Long = System.currentTimeMillis()): String =
        "state=$state readyAt=$readyAtMs holdUntil=$holdBusUntilMs " +
            "notLiveSince=$notLiveSinceMs lastWin=$lastLiveWindowId " +
            "due=${due(nowMs)} spoken=${state == State.SPOKEN}"

    fun markSpoken(nowMs: Long) {
        state = State.SPOKEN
        holdBusUntilMs = nowMs + BUS_HOLD_AFTER_SPEAK_MS
    }

    /**
     * Bus/subway wait must not speak until the briefing is out.
     * Does not touch [nomi.android.traffic.buswait.BusWaitCore] stages.
     */
    fun holdBusWait(nowMs: Long = System.currentTimeMillis()): Boolean {
        return nowMs < holdBusUntilMs
    }

    private enum class State { IDLE, ARMED, SPOKEN }
}
