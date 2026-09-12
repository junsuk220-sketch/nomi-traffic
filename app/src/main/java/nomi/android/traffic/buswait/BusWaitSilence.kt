package nomi.android.traffic.buswait

/**
 * Why [BusWaitCore] chose not to speak. Adapter skips that never reach the core
 * do not use these codes.
 */
enum class BusWaitSilence {
    /** This snapshot has no pinned line. */
    NO_PINNED_ROWS,
    /** Pinned rows exist but none could be ordered. */
    NO_CANDIDATE,
    /** Tracked bus missing from a partial board; hold it. */
    ABSENT_HOLD,
    /** ETA is not yet trusted (feeds disagree). */
    UNSETTLED_FEED,
    /** ETA is outside 10 / 5 / 2 / soon. */
    NOT_A_STAGE,
    /** That stage was already used on this vehicle. */
    STAGE_ALREADY,
    /** Same vehicle, last speech is still inside the gap. */
    STAGE_COOLDOWN,
}
