package nomi.android.traffic.beacon

import kotlin.math.max

/**
 * Which way the walker faces, from GPS only (the phone may be in a pocket).
 * Uses the GPS course while moving; otherwise the line between two fixes far
 * enough apart. Unknown when standing still.
 */
class WalkHeading {

    var heading: Double? = null
        private set
    var source: String = "none"
        private set

    private var anchor: GeoPoint? = null
    private var anchorAt = 0L
    private var headingAt = 0L

    fun update(
        nowMillis: Long,
        here: GeoPoint,
        accuracyMeters: Float?,
        speedMps: Float?,
        gpsBearing: Float?,
    ): Double? {
        if (gpsBearing != null && speedMps != null && speedMps >= MIN_SPEED_MPS) {
            set(nowMillis, gpsBearing.toDouble(), "gps")
            anchor = here
            anchorAt = nowMillis
        } else {
            val from = anchor
            if (from == null || nowMillis - anchorAt > ANCHOR_MAX_AGE_MILLIS) {
                anchor = here
                anchorAt = nowMillis
            } else if (BeaconMath.distanceMeters(from, here) >= max(MIN_MOVE_METERS, (accuracyMeters ?: 0f).toDouble())) {
                set(nowMillis, BeaconMath.bearingDeg(from, here), "move")
                anchor = here
                anchorAt = nowMillis
            }
        }
        if (heading != null && nowMillis - headingAt > STALE_MILLIS) {
            heading = null
            source = "stale"
        }
        return heading
    }

    private fun set(nowMillis: Long, value: Double, from: String) {
        heading = value
        source = from
        headingAt = nowMillis
    }

    companion object {
        const val MIN_SPEED_MPS = 0.7f
        const val MIN_MOVE_METERS = 6.0
        const val ANCHOR_MAX_AGE_MILLIS = 15_000L
        const val STALE_MILLIS = 8_000L
    }
}
