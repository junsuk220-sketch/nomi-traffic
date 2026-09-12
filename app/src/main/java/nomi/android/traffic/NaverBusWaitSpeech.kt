package nomi.android.traffic

import android.util.Log
import nomi.android.traffic.buswait.BusWaitSilenceLog
import nomi.product.nav.NavigationEvent

/**
 * Thin Host adapter: Naver bus wait events → [NaverBusWaitTracker]/BusWaitCore] → speakable event.
 * Trip-start / transfer / subway code must not grow here.
 */
internal object NaverBusWaitSpeech {

    @Volatile
    private var skipLog = BusWaitSilenceLog.State()

    fun focusedEvent(
        tracker: NaverBusWaitTracker,
        event: NavigationEvent,
        ensureSeed: () -> Unit,
    ): NavigationEvent? {
        val arrivals = event.busInfo?.arrivals ?: return event
        if (arrivals.isEmpty()) return event
        val seen = arrivals.joinToString(",") { "${it.line}:${it.eta}" }
        if (tracker.isBusClosedForSubwayTrip()) {
            logSkip(
                "[NAVER_BUS_STAGE] skip, subway trip " +
                    "subway=${tracker.pinnedSubwayLine().orEmpty()} seen=$seen",
            )
            return null
        }
        if (tracker.pinnedBusLines().isEmpty()) {
            ensureSeed()
        }
        if (tracker.pinnedBusLines().isEmpty()) {
            logSkip("[NAVER_BUS_STAGE] skip until pin seen=$seen")
            return null
        }
        val decision = if (event.channel == NaverMapsTransit.BUS_CHANNEL) {
            tracker.onSheet(arrivals)
        } else {
            val stop = NaverNotificationParser.boardingStop(event.action)
            tracker.onNotification(arrivals, stop) ?: run {
                logSkip(
                    "[NAVER_BUS_STAGE] skip other stop=${stop.orEmpty()} " +
                        "trip=${tracker.lockedBoardStop().orEmpty()} seen=$seen",
                )
                return null
            }
        }
        if (decision.switched) {
            Log.i(
                NaverMapNotification.TAG,
                "[NAVER_BUS_TRACK] miss -> ${decision.target?.line.orEmpty()} ${decision.target?.eta.orEmpty()} " +
                    "pins=${tracker.pinnedBusLines().joinToString(",")}",
            )
        }
        val target = decision.target ?: run {
            logSkip(
                "[NAVER_BUS_STAGE] skip unpinned " +
                    "pins=${tracker.pinnedBusLines().joinToString(",")} seen=$seen",
            )
            return null
        }
        if (decision.speakStage == null) {
            val reason = decision.silence
            logSkip(
                if (reason != null) {
                    "[NAVER_BUS_STAGE] skip reason=$reason line=${target.line} eta=${target.eta}"
                } else {
                    "[NAVER_BUS_STAGE] skip line=${target.line} eta=${target.eta}"
                },
            )
            return null
        }
        skipLog = BusWaitSilenceLog.State()
        val others = arrivals.filterNot { it.line == target.line && it.eta == target.eta }
        return event.copy(
            title = "${target.line} ${target.eta}",
            action = "${target.line} ${target.eta}",
            busInfo = event.busInfo?.copy(arrivals = listOf(target) + others),
        )
    }

    /** Accessibility repeats the same screen many times a second — fold, then heartbeat. */
    private fun logSkip(message: String) {
        val next = BusWaitSilenceLog.next(
            skipLog,
            message,
            System.currentTimeMillis(),
        )
        skipLog = next.state
        val line = next.line ?: return
        Log.i(NaverMapNotification.TAG, line)
    }
}
