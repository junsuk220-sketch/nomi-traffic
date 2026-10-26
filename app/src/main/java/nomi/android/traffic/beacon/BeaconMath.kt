package nomi.android.traffic.beacon

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(val lat: Double, val lng: Double)

/** What one beep should sound like. Pure data; no Android. */
data class BeaconCue(
    val left: Float,
    val right: Float,
    val behind: Boolean,
    val arrived: Boolean,
    val intervalMillis: Long,
)

object BeaconMath {

    const val ARRIVED_METERS = 15.0
    const val BEHIND_DEGREES = 100.0
    private const val EARTH_METERS = 6_371_000.0

    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_METERS * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Compass bearing from [a] to [b], 0..360, 0 = north, 90 = east. */
    fun bearingDeg(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Target direction relative to where the walker faces, -180..180, + = right. */
    fun relativeDeg(targetBearing: Double, heading: Double): Double {
        var d = (targetBearing - heading) % 360.0
        if (d < -180.0) d += 360.0
        if (d >= 180.0) d -= 360.0
        return d
    }

    /** Equal-power stereo gains: 0° both ears, +90° right only, -90° left only. */
    fun pan(relative: Double): Pair<Float, Float> {
        val x = sin(Math.toRadians(relative))
        val left = sqrt((1 - x) / 2).toFloat()
        val right = sqrt((1 + x) / 2).toFloat()
        return left to right
    }

    fun isBehind(relative: Double): Boolean = abs(relative) > BEHIND_DEGREES

    fun intervalMillis(distance: Double): Long = when {
        distance > 300 -> 1500L
        distance > 100 -> 1100L
        distance > 30 -> 800L
        else -> 500L
    }

    /** Null when the walker's facing is unknown (standing still): stay silent. */
    fun cue(here: GeoPoint, target: GeoPoint, heading: Double?): BeaconCue? {
        val distance = distanceMeters(here, target)
        if (distance <= ARRIVED_METERS) {
            return BeaconCue(1f, 1f, behind = false, arrived = true, intervalMillis = 3000L)
        }
        heading ?: return null
        val relative = relativeDeg(bearingDeg(here, target), heading)
        val (left, right) = pan(relative)
        return BeaconCue(left, right, isBehind(relative), arrived = false, intervalMillis(distance))
    }
}
