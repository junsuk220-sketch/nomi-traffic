package nomi.android.traffic.eventfirst

import nomi.android.traffic.NaverNextVehicle
import nomi.android.traffic.TransitSoonEta
import nomi.android.traffic.buswait.BusWaitCore
import nomi.product.nav.NavigationBusArrival

/**
 * The only door between Event-First and the verified wait policy. Every rule
 * here delegates to [BusWaitCore] / [NaverNextVehicle] / [TransitSoonEta];
 * nothing is re-derived. The one thing that could not be delegated is the
 * hold-a-suspect-reading test: [BusWaitCore.isSettledReading] writes
 * BusWaitCore's own `pendingEta` fields, so Event-First applies the same two
 * conditions over its own [EventFirstState.pending] using BusWaitCore's
 * constants.
 */
internal object EventFirstPolicy {

    /** Same 120s window 10/5/2 stay quiet in. 곧 is exempt. */
    const val COOLDOWN_MS = BusWaitCore.STAGE_COOLDOWN_MS

    /** A suspect reading must repeat for this long before it may be believed. */
    const val CONFIRM_MS = BusWaitCore.CONFIRM_MS

    fun soonest(arrivals: List<NavigationBusArrival>): NavigationBusArrival? =
        BusWaitCore.soonest(arrivals)

    /** Next vehicle on this board: exclude the tracked one, take the soonest left. */
    fun nextVehicle(arrivals: List<NavigationBusArrival>): NavigationBusArrival? {
        val current = soonest(arrivals) ?: return null
        return NaverNextVehicle.afterSoonest(arrivals, current)
    }

    fun isSameVehicle(previous: NavigationBusArrival, candidate: NavigationBusArrival): Boolean =
        BusWaitCore.isSameVehicle(previous, candidate)

    fun stageForEta(eta: String): Int? = BusWaitCore.stageForEta(eta)

    fun minutes(eta: String): Int? = TransitSoonEta.minutes(eta)

    fun isSoon(eta: String): Boolean = TransitSoonEta.minutes(eta) == 0

    /**
     * True when the clock cannot explain the jump from [previous] to [candidate],
     * so the reading must be held instead of spoken. Same two conditions as
     * [BusWaitCore]'s settled-reading gate.
     */
    fun isSuspectJump(
        previous: EventFirstState.Reading,
        candidateEta: String,
        nowMs: Long,
    ): Boolean {
        val previousMinutes = minutes(previous.eta) ?: return false
        val candidateMinutes = minutes(candidateEta) ?: return false
        return when {
            // No bus follows a 곧 bus by only 2~3분 — one feed is behind.
            previousMinutes == 0 && candidateMinutes in 2..BusWaitCore.ETA_SLACK_MINUTES ->
                nowMs - (previous.soonAtMs ?: nowMs) < BusWaitCore.SOON_DEPART_MS
            // An ETA cannot drop this far in seconds.
            previousMinutes - candidateMinutes >= BusWaitCore.ETA_DROP_MINUTES ->
                nowMs - previous.firstSeenAtMs < BusWaitCore.CONFIRM_MS
            else -> false
        }
    }

    /** A held reading counting down (9분 → 8분) is the same board; 곧 ↔ 5분 is not. */
    fun continuesPending(pending: EventFirstState.Pending, eta: String): Boolean {
        val pendingMinutes = minutes(pending.eta) ?: return false
        val candidateMinutes = minutes(eta) ?: return false
        val low = pendingMinutes - BusWaitCore.ETA_DROP_MINUTES
        return candidateMinutes in low..(pendingMinutes + 1)
    }

    /** Stage number → the mark that records it. */
    fun markForStage(stage: Int): EventFirstState.Mark = when (stage) {
        1 -> EventFirstState.Mark.SOON
        2 -> EventFirstState.Mark.STAGE_2
        5 -> EventFirstState.Mark.STAGE_5
        else -> EventFirstState.Mark.STAGE_10
    }

    /**
     * Speaking a stage also consumes the coarser ones, so a later 10분 reading
     * cannot re-open a ladder we already walked past. The rule itself lives in
     * BusWaitCore; here it is only mapped onto [EventFirstState.spokenKeys].
     */
    fun marksConsumedBy(stage: Int): Set<EventFirstState.Mark> {
        val marks = mutableSetOf(markForStage(stage))
        BusWaitCore.coarserStages(stage).forEach { marks += markForStage(it) }
        return marks
    }
}
