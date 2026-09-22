package nomi.android.traffic.track

import android.util.Log

/**
 * Field-only logs for VehicleTracking. Does not speak or change tracking.
 * [emit] must not throw on JVM unit tests (no android.util.Log).
 */
internal object VehicleTrackingDebug {
    const val TAG = "TRACK_DEBUG"
    private const val BUILD = "vc92-track"

    @Volatile
    private var lastKey: String? = null

    fun emit(message: String) {
        try {
            Log.i(TAG, "[TRACK_DEBUG] $message build=$BUILD")
        } catch (_: Throwable) {
        }
    }

    fun emit(snapshot: VehicleSnapshot?) {
        if (snapshot == null) {
            if (lastKey == "none") return
            lastKey = "none"
            emit("active=false")
            return
        }
        val key = snapshot.vehicleKey
        val line = listOf(
            key.generation, key.kind, key.line, key.track,
            snapshot.eta, snapshot.settled, snapshot.nextVehicle, snapshot.stop,
        ).joinToString("|")
        if (line == lastKey) return
        lastKey = line
        emit(
            "key=${key.generation}/${key.kind}/${key.line}#${key.track} " +
                "eta=${snapshot.eta} min=${snapshot.etaMinutes ?: "-"} " +
                "settled=${snapshot.settled} next=${snapshot.nextVehicle} " +
                "stop=${snapshot.stop ?: "-"}",
        )
    }

    fun resetForTest() {
        lastKey = null
    }
}
