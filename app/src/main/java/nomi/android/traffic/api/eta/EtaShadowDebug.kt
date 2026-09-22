package nomi.android.traffic.api.eta

import android.util.Log
import nomi.android.traffic.track.VehicleSnapshot

/**
 * Field-only logs for ETA shadow. Does not speak.
 * [emit] must not throw on JVM unit tests (no android.util.Log).
 */
internal object EtaShadowDebug {
    const val TAG = "ETA_SHADOW"
    private const val BUILD = "vc93-eta-shadow"

    @Volatile
    private var lastKey: String? = null

    fun emit(message: String) {
        try {
            Log.i(TAG, "[ETA_SHADOW] $message build=$BUILD")
        } catch (_: Throwable) {
        }
    }

    fun emit(api: String, outcome: EtaOutcome, vehicle: VehicleSnapshot?) {
        val request = outcome.request
        val reason = if (request != null) "SPEAK" else outcome.silence?.name ?: "SILENCE"
        if (request == null &&
            (outcome.silence == EtaSilence.OUT_OF_BAND ||
                outcome.silence == EtaSilence.NO_VEHICLE)
        ) {
            return
        }
        val key = listOf(
            api,
            reason,
            vehicle?.vehicleKey?.generation,
            vehicle?.vehicleKey?.line,
            vehicle?.vehicleKey?.track,
            vehicle?.eta,
            request?.text,
        ).joinToString("|")
        if (key == lastKey) return
        lastKey = key
        emit(
            "api=$api decision=$reason " +
                "line=${vehicle?.line ?: "-"} eta=${vehicle?.eta ?: "-"} " +
                "min=${vehicle?.etaMinutes ?: "-"} " +
                "text=${request?.text ?: "-"}",
        )
    }

    fun resetForTest() {
        lastKey = null
    }
}
