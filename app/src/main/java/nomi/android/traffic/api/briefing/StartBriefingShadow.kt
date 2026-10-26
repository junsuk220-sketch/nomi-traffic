package nomi.android.traffic.api.briefing

import nomi.android.traffic.NaverTripStartSession
import nomi.android.traffic.NavigationEventVoice
import nomi.android.traffic.api.transfer.Transfer
import nomi.android.traffic.observe.TransitReading
import nomi.android.traffic.scope.GuidanceKind
import nomi.android.traffic.scope.GuidanceSnapshot

/**
 * Host for StartBriefing. Speaks one briefing per Scope generation.
 * Leftover subway skip and wait rungs stay outside this host.
 */
object StartBriefingShadow {

    fun tick(
        scope: GuidanceSnapshot,
        reading: TransitReading?,
    ) {
        val outcome = StartBriefing.consider(scope, reading)
        StartBriefingShadowDebug.emit(outcome, scope, reading)
        val text = outcome.request?.text?.trim().orEmpty()
        if (text.isEmpty()) return
        if (Transfer.seoulOwnsAlight(scope.line)) {
            StartBriefingShadowDebug.emit("skip_naver_walk seoul_owns line=${scope.line}")
            return
        }
        if (NaverTripStartSession.hasSpokenBriefing()) return
        NavigationEventVoice.playStartBriefing(
            text = text,
            bus = scope.kind == GuidanceKind.BUS,
        )
    }
}
