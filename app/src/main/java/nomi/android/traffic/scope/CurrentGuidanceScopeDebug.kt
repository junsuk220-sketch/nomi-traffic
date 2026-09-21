package nomi.android.traffic.scope

import android.util.Log

/**
 * Field-only logs for CurrentGuidanceScope. Does not speak or change Scope
 * decisions. [emit] must not throw on JVM unit tests (no android.util.Log).
 */
internal object CurrentGuidanceScopeDebug {
    const val TAG = "SCOPE_DEBUG"
    private const val BUILD = "vc89-scope-debug"

    @Volatile
    private var lastHoldKey: String? = null

    fun emit(message: String) {
        try {
            Log.i(TAG, "[SCOPE_DEBUG] $message build=$BUILD")
        } catch (_: Throwable) {
        }
    }

    fun clip(blobs: List<String>, take: Int = 6): String =
        blobs.take(take).joinToString(" | ").ifEmpty { "-" }

    fun transition(
        source: String,
        before: GuidanceSnapshot,
        after: GuidanceSnapshot,
        cue: String,
        selected: String,
        hasAlt: Boolean,
        alts: String,
    ) {
        val event = when {
            !before.active && after.active -> "CREATED"
            before.active && !after.active -> "ENDED"
            before.active && after.active &&
                (before.line != after.line || before.kind != after.kind ||
                    before.generation != after.generation) -> "CHANGED"
            after.active && hasAlt &&
                before.generation == after.generation &&
                before.kind == after.kind &&
                before.line == after.line -> "HOLD_ALTS"
            else -> return
        }
        if (event == "HOLD_ALTS") {
            val key = listOf(
                after.generation, after.kind, after.line, alts,
            ).joinToString("|")
            if (key == lastHoldKey) return
            lastHoldKey = key
        }
        emit(
            "event=$event source=$source " +
                "active=${after.active} generation=${after.generation} " +
                "kind=${after.kind} line=${after.line.orEmpty()} " +
                "cue=$cue selected=[$selected] hasAlt=$hasAlt alts=[$alts]",
        )
    }

    fun resetForTest() {
        lastHoldKey = null
    }
}
