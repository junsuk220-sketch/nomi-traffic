package nomi.android.traffic

/**
 * Naver Maps subway car cues verified on device. Host-only. Not Navigation Core.
 */
internal object NaverMapsTransit {
    const val TAG = "NAVER_SUBWAY"
    const val CHANNEL = "a11y_subway"
    const val BUS_CHANNEL = "a11y_bus"
    const val TRIP_START_ACTION = "안내시작"
    const val KIND_BUS = "bus"
    const val KIND_SUBWAY = "subway"
    const val QUICK_EXIT = "빠른 하차"
    const val QUICK_TRANSFER = "빠른 환승"
    const val PREPARE_ALIGHT_ACTION = "하차준비"
    const val TRANSFER_WALK_ACTION = "환승도보"
    const val BOARD_DIRECTION_ACTION = "승차방향"
}
