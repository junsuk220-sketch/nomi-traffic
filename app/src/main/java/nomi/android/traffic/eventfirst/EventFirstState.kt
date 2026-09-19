package nomi.android.traffic.eventfirst

/**
 * Everything Event-First remembers. Five fields, all short-lived, all rebuilt
 * from the events themselves. There is deliberately no Journey id, no leg
 * index, no pin, no boardStopLocked, no closedForSubwayTrip and no copy of
 * NaverTripStartSession — an event that describes the current situation is
 * trusted instead of re-derived.
 *
 * Immutable: [EventFirstJudge] returns the next state rather than mutating.
 */
internal data class EventFirstState(
    val reading: Reading? = null,
    val spokenKeys: Map<String, Set<Mark>> = emptyMap(),
    val lastSpokenAtMs: Long? = null,
    val pending: Pending? = null,
    /**
     * Guidance ended. WaitBus / WaitTrain stay silent until the next
     * [NaverTransitEvent.GuidanceStart] lifts this, so a leftover 302 cannot
     * brief as if a new trip had begun.
     */
    val awaitingStart: Boolean = false,
) {

    /**
     * The vehicle the wait cues are currently about.
     *
     * @param firstSeenAtMs when [eta] first appeared — the 10초 grace window for
     * a sudden drop measures from here.
     * @param soonAtMs last time this vehicle read 곧, so a later rise can be
     * recognised as the next vehicle instead of a feed lagging behind.
     */
    data class Reading(
        val scopeKey: String,
        val line: String,
        val eta: String,
        val firstSeenAtMs: Long,
        val soonAtMs: Long? = null,
    )

    /** A reading the clock cannot explain, waiting to repeat before it counts. */
    data class Pending(val eta: String, val sinceMs: Long)

    /**
     * What has already been said inside one scope. [STAGE_10]/[STAGE_5]/
     * [STAGE_2]/[SOON] are the 10 / 5 / 2 / 곧 rungs (a numeric `1분` is the 곧
     * rung, exactly as BusWaitCore grades it).
     */
    enum class Mark {
        BRIEF,
        STAGE_10,
        STAGE_5,
        STAGE_2,
        SOON,
        BOARD,
        ALIGHT_SOON,
        ALIGHT_NOW,
        TRANSFER,
    }

    fun marks(scopeKey: String): Set<Mark> = spokenKeys[scopeKey].orEmpty()

    fun hasMark(scopeKey: String, mark: Mark): Boolean = mark in marks(scopeKey)

    fun withMarks(scopeKey: String, added: Set<Mark>): EventFirstState =
        copy(spokenKeys = spokenKeys + (scopeKey to marks(scopeKey) + added))

    /**
     * Continuity broke (곧 → 15분, a different bus, boarded). The scope keeps
     * its identity; only what we said about the old vehicle is forgotten, so no
     * generation counter is needed.
     */
    fun forgetScope(scopeKey: String): EventFirstState =
        copy(spokenKeys = spokenKeys - scopeKey)

    fun spokenAt(nowMs: Long): EventFirstState = copy(lastSpokenAtMs = nowMs)

    /** Aboard, or guidance ended: no vehicle is being waited for any more. */
    fun clearWait(): EventFirstState = copy(reading = null, pending = null)
}
