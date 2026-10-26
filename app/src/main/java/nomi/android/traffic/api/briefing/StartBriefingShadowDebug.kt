package nomi.android.traffic.api.briefing

import android.util.Log
import nomi.android.traffic.observe.TransitReading
import nomi.android.traffic.scope.GuidanceSnapshot

/**
 * Field-only logs for start-briefing shadow. Does not speak.
 * [emit] must not throw on JVM unit tests (no android.util.Log).
 */
internal object StartBriefingShadowDebug {
    const val TAG = "BRIEFING_SHADOW"
    private const val BUILD = "vc103-briefing-voice"

    @Volatile
    private var lastKey: String? = null

    fun emit(message: String) {
        try {
            Log.i(TAG, "[BRIEFING_SHADOW] $message build=$BUILD")
        } catch (_: Throwable) {
        }
    }

    fun emit(
        outcome: StartBriefingOutcome,
        scope: GuidanceSnapshot,
        reading: TransitReading?,
    ) {
        val request = outcome.request
        val reason = if (request != null) "SPEAK" else outcome.silence?.name ?: "SILENCE"
        if (request == null && outcome.silence == StartBriefingSilence.INACTIVE) {
            return
        }
        val own = reading?.rows?.filter { it.line == scope.line }.orEmpty()
        val key = listOf(
            reason,
            scope.active,
            scope.generation,
            scope.kind,
            scope.line,
            own.joinToString(",") { "${it.line}:${it.eta}" },
            request?.text,
        ).joinToString("|")
        if (key == lastKey) return
        lastKey = key
        emit(
            "decision=$reason " +
                "active=${scope.active} gen=${scope.generation} " +
                "kind=${scope.kind} line=${scope.line ?: "-"} " +
                "rows=${own.joinToString(",") { "${it.line}:${it.eta}" }.ifEmpty { "-" }} " +
                "text=${request?.text ?: "-"}",
        )
    }

    fun resetForTest() {
        lastKey = null
    }
}
