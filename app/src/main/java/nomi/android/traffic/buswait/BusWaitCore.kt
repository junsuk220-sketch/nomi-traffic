package nomi.android.traffic.buswait

import nomi.android.traffic.TransitSoonEta
import nomi.product.nav.NavigationBusArrival

/**
 * Constitution core: pin → track(soonest) → stage(10/5/2/soon).
 * No Android services. Adapters feed [observe] and Host speaks [Tick.speak].
 *
 * @see docs/BUS_WAIT_CONSTITUTION.md
 */
class BusWaitCore {

    data class Tick(
        /** Current vehicle among pinned lines, if any. */
        val target: NavigationBusArrival?,
        /** True when the tracked vehicle changed (miss / faster alternate / next same line). */
        val switched: Boolean,
        /** When non-null, Host must speak this stage for [target] (10 / 5 / 2 / 1=soon). */
        val speakStage: Int?,
        /** Set only when [speakStage] is null. Adapter skips never invent this. */
        val silence: BusWaitSilence? = null,
    )

    private var pinned: Set<String> = emptySet()
    private var seeded: Set<String> = emptySet()
    private var tracked: NavigationBusArrival? = null
    private var trackedSeenAtMs = 0L
    private var trackedEtaAtMs = 0L
    private var soonSeenAtMs: Long? = null
    private var pendingEta: String? = null
    private var pendingSinceMs = 0L
    private var lastObservedAtMs = 0L
    private var lastSpokenAtMs: Long? = null
    private val stages = mutableSetOf<Int>()

    fun pinnedLines(): Set<String> = pinned

    /** Lines the trip itself seeded, before same-stop alternates joined. */
    fun seededLines(): Set<String> = seeded

    fun seed(line: String) {
        seed(listOf(line))
    }

    fun seed(lines: Collection<String>) {
        val next = lines.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (next.isEmpty()) return
        pinned = next
        seeded = next
        tracked = null
        trackedSeenAtMs = 0L
        trackedEtaAtMs = 0L
        soonSeenAtMs = null
        pendingEta = null
        lastSpokenAtMs = null
        stages.clear()
    }

    /**
     * Host just spoke (start briefing counts). 10/5/2 stay quiet for
     * [STAGE_COOLDOWN_MS] on the same vehicle; 곧 still speaks.
     */
    fun noteSpoken(nowMs: Long) {
        lastSpokenAtMs = nowMs
    }

    fun leave() {
        pinned = emptySet()
        seeded = emptySet()
        tracked = null
        trackedSeenAtMs = 0L
        trackedEtaAtMs = 0L
        soonSeenAtMs = null
        pendingEta = null
        lastSpokenAtMs = null
        stages.clear()
    }

    /**
     * @param nowMs adapter clock, used to hold a tracked bus that a partial
     * snapshot left out. Defaults to the last observed time so callers that do
     * not track time never time the hold out.
     * @return null when there is no pin yet (adapters must not speak bus stages).
     */
    fun observe(
        arrivals: List<NavigationBusArrival>,
        nowMs: Long = lastObservedAtMs,
    ): Tick? {
        lastObservedAtMs = nowMs
        if (pinned.isEmpty()) return null
        expandAlternativesIfNeeded(arrivals)
        val scoped = arrivals.filter { it.line in pinned }
        if (scoped.isEmpty()) {
            val keep = tracked?.takeIf { it.line in pinned }
            return Tick(
                target = keep,
                switched = false,
                speakStage = null,
                silence = BusWaitSilence.NO_PINNED_ROWS,
            )
        }
        val soonest = soonest(scoped) ?: return Tick(
            null,
            switched = false,
            speakStage = null,
            silence = BusWaitSilence.NO_CANDIDATE,
        )
        val previous = tracked?.takeIf { it.line in pinned }
        if (previous == null) {
            trackReading(soonest, nowMs)
            return tick(soonest, switched = false, nowMs = nowMs, eta = soonest.eta)
        }
        val trackedIsOnScreen = scoped.any { it.line == previous.line }
        if (trackedIsOnScreen) {
            trackedSeenAtMs = nowMs
        } else if (nowMs - trackedSeenAtMs < ABSENT_HOLD_MS) {
            // Partial board: the tracked bus is missing from this snapshot only.
            return Tick(
                previous,
                switched = false,
                speakStage = null,
                silence = BusWaitSilence.ABSENT_HOLD,
            )
        }
        val onTrackedLine = soonest(scoped.filter { it.line == previous.line })
        if (onTrackedLine != null && !isSettledReading(previous, onTrackedLine, nowMs)) {
            // 302 and the sheet disagree: hold the tracked bus and stay quiet.
            return Tick(
                previous,
                switched = false,
                speakStage = null,
                silence = BusWaitSilence.UNSETTLED_FEED,
            )
        }
        val sameAsPrevious = findSame(previous, scoped)
        if (sameAsPrevious != null) {
            if (soonest.line != previous.line && isClearlyFaster(soonest, previous)) {
                return switchTo(soonest, nowMs)
            }
            val merged = carrySheetFields(previous, sameAsPrevious)
            trackReading(merged, nowMs)
            return tick(merged, switched = false, nowMs = nowMs, eta = merged.eta)
        }
        val next = if (soonest.line == previous.line) {
            carrySheetFields(previous, soonest)
        } else {
            soonest
        }
        return switchTo(next, nowMs)
    }

    private fun switchTo(next: NavigationBusArrival, nowMs: Long): Tick {
        trackReading(next, nowMs)
        stages.clear()
        return tick(next, switched = true, nowMs = nowMs, eta = next.eta)
    }

    private fun tick(
        target: NavigationBusArrival,
        switched: Boolean,
        nowMs: Long,
        eta: String,
    ): Tick {
        val spoken = gatedStage(eta, nowMs, switched)
        return Tick(target, switched, spoken.stage, spoken.silence)
    }

    private fun trackReading(arrival: NavigationBusArrival, nowMs: Long) {
        if (tracked?.eta != arrival.eta) trackedEtaAtMs = nowMs
        tracked = arrival
        trackedSeenAtMs = nowMs
        pendingEta = null
        if (TransitSoonEta.minutes(arrival.eta) == 0) soonSeenAtMs = nowMs
    }

    /**
     * The two Naver feeds (302 board, live sheet) can lag each other by a whole
     * vehicle. A reading that the clock cannot explain waits for [CONFIRM_MS] of
     * the same value before it may change the tracked bus.
     */
    private fun isSettledReading(
        previous: NavigationBusArrival,
        candidate: NavigationBusArrival,
        nowMs: Long,
    ): Boolean {
        val previousEta = TransitSoonEta.minutes(previous.eta) ?: return true
        val candidateEta = TransitSoonEta.minutes(candidate.eta) ?: return true
        val suspect = when {
            // No bus follows a 곧 bus by only 2~3분 — one feed is behind.
            previousEta == 0 && candidateEta in 2..ETA_SLACK_MINUTES ->
                nowMs - (soonSeenAtMs ?: nowMs) < SOON_DEPART_MS
            // An ETA cannot drop this far in seconds.
            previousEta - candidateEta >= ETA_DROP_MINUTES ->
                nowMs - trackedEtaAtMs < CONFIRM_MS
            else -> false
        }
        if (!suspect) {
            pendingEta = null
            return true
        }
        if (!continuesPending(candidate.eta)) {
            pendingEta = candidate.eta
            pendingSinceMs = nowMs
            return false
        }
        return nowMs - pendingSinceMs >= CONFIRM_MS
    }

    /** A held reading counting down (9분 → 8분) is the same board; 곧 ↔ 5분 is not. */
    private fun continuesPending(eta: String): Boolean {
        val pending = pendingEta?.let { TransitSoonEta.minutes(it) } ?: return false
        val minutes = TransitSoonEta.minutes(eta) ?: return false
        return minutes in (pending - ETA_DROP_MINUTES)..(pending + 1)
    }

    private fun expandAlternativesIfNeeded(arrivals: List<NavigationBusArrival>) {
        if (pinned.isEmpty() || arrivals.isEmpty()) return
        if (arrivals.none { it.line in pinned }) return
        pinned = pinned + arrivals.map { it.line.trim() }.filter { it.isNotEmpty() }
    }

    private data class StageOut(val stage: Int?, val silence: BusWaitSilence?)

    private fun gatedStage(eta: String, nowMs: Long, switched: Boolean): StageOut {
        val stage = acceptStage(eta) ?: return StageOut(null, silenceForRejectedStage(eta))
        if (stage <= 1) {
            lastSpokenAtMs = nowMs
            return StageOut(stage, null)
        }
        val last = lastSpokenAtMs
        if (!switched && last != null && nowMs - last < STAGE_COOLDOWN_MS) {
            return StageOut(null, BusWaitSilence.STAGE_COOLDOWN)
        }
        lastSpokenAtMs = nowMs
        return StageOut(stage, null)
    }

    private fun silenceForRejectedStage(eta: String): BusWaitSilence {
        val stage = stageForEta(eta) ?: return BusWaitSilence.NOT_A_STAGE
        return if (stage in stages) BusWaitSilence.STAGE_ALREADY else BusWaitSilence.NOT_A_STAGE
    }

    private fun acceptStage(eta: String): Int? {
        val stage = stageForEta(eta) ?: return null
        if (!stages.add(stage)) return null
        for (coarser in listOf(10, 5, 2)) {
            if (coarser > stage) stages.add(coarser)
        }
        return stage
    }

    private fun stageForEta(eta: String): Int? {
        val minutes = when {
            TransitSoonEta.matches(eta) -> 1
            else -> {
                val m = ETA_MINUTES.find(eta)?.groupValues?.get(1)?.toIntOrNull() ?: return null
                if (m <= 1) 1 else m
            }
        }
        return when {
            minutes <= 1 -> 1
            minutes <= 2 -> 2
            minutes <= 5 -> 5
            minutes <= 10 -> 10
            else -> null
        }
    }

    companion object {
        internal const val ETA_SLACK_MINUTES = 3
        internal const val STOP_SLACK = 1
        /** Switch lines only when the other bus is at least this many minutes sooner. */
        internal const val LINE_SWITCH_MINUTES = 2
        /** Keep a tracked bus this long while snapshots leave its row out. */
        internal const val ABSENT_HOLD_MS = 20_000L
        /** A 곧 bus owns the board this long before a rise counts as the next bus. */
        internal const val SOON_DEPART_MS = 60_000L
        /** Same line dropping this many minutes at once needs confirming. */
        internal const val ETA_DROP_MINUTES = 2
        /** A suspect reading must hold this long before it may switch the bus. */
        internal const val CONFIRM_MS = 10_000L
        /** Same vehicle: 10/5/2 stay quiet this long after the last speech. */
        internal const val STAGE_COOLDOWN_MS = 120_000L
        private val ETA_MINUTES = Regex("""(\d+)\s*분""")

        internal fun soonest(arrivals: List<NavigationBusArrival>): NavigationBusArrival? =
            arrivals.minWithOrNull(soonestOrder)

        internal fun findSame(
            previous: NavigationBusArrival,
            arrivals: List<NavigationBusArrival>,
        ): NavigationBusArrival? {
            val candidate = soonest(arrivals.filter { it.line == previous.line }) ?: return null
            if (!isSameVehicle(previous, candidate)) return null
            return candidate
        }

        internal fun carrySheetFields(
            previous: NavigationBusArrival,
            candidate: NavigationBusArrival,
        ): NavigationBusArrival {
            val occupancy = candidate.occupancy?.takeIf { it.isNotBlank() } ?: previous.occupancy
            val stops = candidate.stopsRemaining ?: previous.stopsRemaining
            if (occupancy == candidate.occupancy && stops == candidate.stopsRemaining) return candidate
            return candidate.copy(occupancy = occupancy, stopsRemaining = stops)
        }

        internal fun isSameVehicle(
            previous: NavigationBusArrival,
            candidate: NavigationBusArrival,
        ): Boolean {
            if (previous.line != candidate.line) return false
            val previousEta = TransitSoonEta.minutes(previous.eta) ?: return false
            val candidateEta = TransitSoonEta.minutes(candidate.eta) ?: return false
            // After 곧(0), any ETA ≥2 is the next bus of the same line.
            if (previousEta == 0 && candidateEta >= 2) return false
            // ETA jumping upward beyond slack = missed / next vehicle (or heavy delay).
            if (candidateEta > previousEta + ETA_SLACK_MINUTES) return false
            val previousStops = previous.stopsRemaining
            val candidateStops = candidate.stopsRemaining
            if (previousStops != null && candidateStops != null &&
                candidateStops > previousStops + STOP_SLACK
            ) {
                return false
            }
            return true
        }

        internal fun isClearlyFaster(
            candidate: NavigationBusArrival,
            current: NavigationBusArrival,
        ): Boolean {
            val candidateEta = TransitSoonEta.minutes(candidate.eta) ?: return true
            val currentEta = TransitSoonEta.minutes(current.eta) ?: return true
            return candidateEta <= currentEta - LINE_SWITCH_MINUTES
        }

        private val soonestOrder = compareBy<NavigationBusArrival> {
            TransitSoonEta.minutes(it.eta) ?: Int.MAX_VALUE
        }.thenBy { it.stopsRemaining ?: Int.MAX_VALUE }
    }
}
