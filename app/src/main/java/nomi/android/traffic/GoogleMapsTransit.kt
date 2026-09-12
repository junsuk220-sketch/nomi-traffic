package nomi.android.traffic

/**
 * Google Maps fields verified on device. Host-only. Not Navigation Core.
 */
internal object GoogleMapsTransit {
    const val PACKAGE = "com.google.android.apps.maps"
    const val TAG = "GOOGLE_TRANSIT"
    const val CHANNEL = "a11y_transit"
    const val ALIGHT_ACTION = "하차"
    const val PREPARE_ALIGHT_ACTION = "하차준비"
    const val TRIP_START_ACTION = "안내시작"
    const val BOARD_DIRECTION_ACTION = "승차방향"
    const val KIND_BUS = "bus"
    const val KIND_SUBWAY = "subway"
}
