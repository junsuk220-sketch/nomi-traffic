package nomi.android.traffic.api.briefing

import nomi.android.traffic.observe.TransitReading
import nomi.android.traffic.scope.GuidanceSnapshot

/**
 * Shadow host only. Calls StartBriefing and logs. Does not send
 * [nomi.android.traffic.speech.SpeechRequest] to SpeechSink.
 */
object StartBriefingShadow {

    fun tick(
        scope: GuidanceSnapshot,
        reading: TransitReading?,
    ) {
        StartBriefingShadowDebug.emit(StartBriefing.consider(scope, reading), scope, reading)
    }
}
