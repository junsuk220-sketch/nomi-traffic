package nomi.android.traffic.observe

import android.util.Log

/**
 * Field-only logs for TransitObservation. Does not speak or change readings.
 * [emit] must not throw on JVM unit tests (no android.util.Log).
 */
internal object TransitObservationDebug {
    const val TAG = "OBSERVE_DEBUG"
    private const val BUILD = "vc91-observe"

    @Volatile
    private var lastKey: String? = null

    fun emit(message: String) {
        try {
            Log.i(TAG, "[OBSERVE_DEBUG] $message build=$BUILD")
        } catch (_: Throwable) {
        }
    }

    fun emit(reading: TransitReading?) {
        if (reading == null) return
        val rows = reading.rows.joinToString(", ") { row ->
            "${row.line}:${row.eta}"
        }.ifEmpty { "-" }
        val key = listOf(
            reading.source, reading.kind, reading.stop, rows,
        ).joinToString("|")
        if (key == lastKey) return
        lastKey = key
        emit(
            "source=${reading.source} kind=${reading.kind} " +
                "stop=${reading.stop ?: "-"} rows=$rows " +
                "n=${reading.rows.size} raw=${clip(reading.raw)}",
        )
    }

    fun resetForTest() {
        lastKey = null
    }

    private fun clip(raw: String): String =
        raw.take(80).ifEmpty { "-" }
}
