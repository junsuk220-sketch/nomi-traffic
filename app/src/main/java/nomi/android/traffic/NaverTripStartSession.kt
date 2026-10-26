package nomi.android.traffic

/**
 * One Naver trip-start briefing per guidance.
 * Notification / live-edge never reopen a spoken session.
 * A new 안내시작 tap always starts a fresh briefing.
 * `안내 중` leaving the screen is not an end: Naver hides it whenever the sheet
 * shows the next 승차 card (2026-10-09 941번, 96번). Only [reset] ends.
 *
 * A click alone never speaks. Naver itself must confirm the guidance: `안내 중` on screen
 * ([noteLive]), the 길안내를 시작합니다 notice ([request]) or any transit notification
 * ([confirm]). A click not confirmed within
 * [CONFIRM_WINDOW_MS] is dropped (2026-10-11: no guidance all day, two briefings spoke
 * from route previews).
 */
internal object NaverTripStartSession {

    const val ARM_DELAY_MS = 3_500L
    const val BUS_HOLD_AFTER_SPEAK_MS = 12_000L
    const val GIVE_UP_AFTER_DUE_MS = 8_000L
    /** Naver says 길안내를 시작합니다 when guidance arms; the briefing waits this long after it. */
    const val BRIEF_AFTER_NAVER_MS = 7_000L
    /** A 안내시작 click this soon after a briefing, on the same line, is the same trip arriving late. */
    const val LATE_CLICK_SAME_TRIP_MS = 60_000L
    /** The sheet top shows the first leg only in the first seconds; later boards are other legs. */
    const val FIRST_LEG_WINDOW_MS = 15_000L
    const val CONFIRM_WINDOW_MS = 60_000L
    /** 길안내를 시작합니다 often lands just before its own 안내시작 click (field: up to 178 ms). */
    const val CLICK_KEEPS_CONFIRM_MS = 3_000L

    private var state = State.IDLE
    private var confirmed = false
    private var lastRequestAtMs = 0L
    private var firstLeg: NaverFirstLeg.Leg? = null
    private var lateClick = false
    private var lastBriefKey: String? = null
    private var lastBriefAtMs = 0L
    private var armedAtMs = 0L
    private var readyAtMs = 0L
    private var holdBusUntilMs = 0L

    fun reset() {
        state = State.IDLE
        confirmed = false
        firstLeg = null
        lateClick = false
        armedAtMs = 0L
        readyAtMs = 0L
        holdBusUntilMs = 0L
    }

    /**
     * Live guidance. Arms once if idle, else confirms a click-armed session.
     * 안내 중 coming back inside a guidance does not re-arm.
     */
    fun noteLive(nowMs: Long): Boolean = request(nowMs)

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

    /**
     * Naver confirmed guidance: notification start phrase or live edge. Arms if idle
     * (true), else confirms a click-armed session. No re-arm if already armed or spoken.
     */
    fun request(nowMs: Long): Boolean {
        dropUnconfirmed(nowMs)
        confirmed = true
        lastRequestAtMs = nowMs
        if (state != State.IDLE) return false
        arm(nowMs)
        return true
    }

    /**
     * User tapped 안내시작 on a new route. Always open a fresh briefing,
     * even if the previous trip spoke a few seconds ago. Silent until Naver confirms.
     */
    fun restartFromClick(nowMs: Long): Boolean {
        reset()
        lateClick = lastBriefAtMs != 0L && nowMs - lastBriefAtMs <= LATE_CLICK_SAME_TRIP_MS
        arm(nowMs)
        confirmed = lastRequestAtMs != 0L && nowMs - lastRequestAtMs in 0..CLICK_KEEPS_CONFIRM_MS
        return true
    }

    /**
     * Any Naver transit notification: Naver posts them only while guidance runs.
     * Confirms a click-armed session; never arms (ETA updates must not re-arm).
     */
    fun confirm(nowMs: Long) {
        dropUnconfirmed(nowMs)
        if (state == State.ARMED) confirmed = true
    }

    /** True when a click-armed session went [CONFIRM_WINDOW_MS] without Naver's confirmation; it is reset. */
    fun dropUnconfirmed(nowMs: Long): Boolean {
        if (state != State.ARMED || confirmed) return false
        if (nowMs - armedAtMs <= CONFIRM_WINDOW_MS) return false
        reset()
        return true
    }

    private fun arm(nowMs: Long) {
        state = State.ARMED
        firstLeg = null
        armedAtMs = nowMs
        readyAtMs = nowMs + ARM_DELAY_MS
        holdBusUntilMs = readyAtMs + BUS_HOLD_AFTER_SPEAK_MS
    }

    /** A briefing was said for this route. Survives [reset] so a late click can be recognised. */
    fun noteBriefed(key: String?, nowMs: Long) {
        lastBriefKey = key
        lastBriefAtMs = nowMs
    }

    /** The click that armed this session came late for the route just briefed. */
    fun isLateClickRepeat(key: String?): Boolean =
        lateClick && key != null && key == lastBriefKey

    /** The trip's first leg, once read. Survives until the next arm or reset. */
    fun firstLeg(): NaverFirstLeg.Leg? = firstLeg

    /**
     * Locks the first board read within [FIRST_LEG_WINDOW_MS] of the arm. Afterwards the same
     * board only refreshes its printed rows; another leg never replaces it. True when it locked now.
     */
    fun noteFirstLeg(leg: NaverFirstLeg.Leg, nowMs: Long): Boolean {
        if (state == State.IDLE) return false
        val held = firstLeg
        if (held == null) {
            if (nowMs - armedAtMs > FIRST_LEG_WINDOW_MS) return false
            firstLeg = leg
            return true
        }
        if (leg.block.isNotEmpty() && held.subway == leg.subway && held.lines.any { it in leg.lines }) {
            firstLeg = held.copy(block = leg.block)
        }
        return false
    }

    /** The briefing about to speak named another leg. Let a later, matching one speak. */
    fun reopenBriefing() {
        if (state == State.SPOKEN) state = State.ARMED
    }

    fun due(nowMs: Long): Boolean = state == State.ARMED && confirmed && nowMs >= readyAtMs

    fun hasSpokenBriefing(): Boolean = state == State.SPOKEN

    /** Wait left until [BRIEF_AFTER_NAVER_MS] past the arm. Never armed: the full wait. */
    fun msUntilBriefing(nowMs: Long): Long {
        if (armedAtMs == 0L) return BRIEF_AFTER_NAVER_MS
        return (armedAtMs + BRIEF_AFTER_NAVER_MS - nowMs).coerceIn(0L, BRIEF_AFTER_NAVER_MS)
    }

    /** Read-only snapshot for field trip-start tracing. */
    fun debugSnapshot(nowMs: Long = System.currentTimeMillis()): String =
        "state=$state confirmed=$confirmed readyAt=$readyAtMs holdUntil=$holdBusUntilMs " +
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
