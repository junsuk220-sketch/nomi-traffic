package nomi.android.traffic.buswait

/** Name of the existing [BusWaitCore.observe] return. Not a new rule. */
enum class BusWaitTracePath {
    INITIAL,
    KEEP_SAME,
    HOLD_ABSENT,
    HOLD_UNSETTLED,
    HOLD_NO_PINNED_ROWS,
    NO_CANDIDATE,
    FASTER_ALTERNATE,
    NO_SAME_VEHICLE,
}
