package nomi.android.traffic.track

import nomi.android.traffic.TransitSoonEta
import nomi.android.traffic.buswait.BusWaitCore
import nomi.android.traffic.buswait.SettleClock
import nomi.android.traffic.observe.TransitKind
import nomi.android.traffic.observe.TransitReading
import nomi.android.traffic.observe.TransitRow
import nomi.android.traffic.scope.GuidanceKind
import nomi.android.traffic.scope.GuidanceSnapshot
import nomi.product.nav.NavigationBusArrival

/**
 * Tracks one vehicle inside the current [GuidanceSnapshot] line.
 * Does not speak, grade stages, or change Scope / Observation.
 */
object VehicleTracking {

    private val lock = Any()
    private var generation = 0
    private var pin: String? = null
    private var kind = TransitKind.NONE
    private var tracked: NavigationBusArrival? = null
    private var track = 0
    private var trackedSeenAtMs = 0L
    private var trackedEtaAtMs = 0L
    private var soonSeenAtMs: Long? = null
    private var pendingEta: String? = null
    private var pendingSinceMs = 0L
    private var boardStop: String? = null
    private var last: VehicleSnapshot? = null

    fun snapshot(): VehicleSnapshot? = synchronized(lock) {
        last?.copy()
    }

    fun observe(
        scope: GuidanceSnapshot,
        reading: TransitReading?,
    ): VehicleSnapshot? = synchronized(lock) {
        if (!scope.active || scope.line.isNullOrBlank()) {
            clearLocked()
            return null
        }
        syncPinLocked(scope)
        if (reading == null) return last?.copy()
        if (!acceptsStopLocked(reading.stop)) return last?.copy()
        val nowMs = reading.atMs
        val arrivals = reading.rows.map { it.toArrival() }
        val scoped = arrivals.filter { it.line == pin }
        val previous = tracked
        if (scoped.isEmpty()) {
            if (previous == null) return null
            if (nowMs - trackedSeenAtMs < BusWaitCore.ABSENT_HOLD_MS) {
                return publishLocked(
                    arrival = previous,
                    nextVehicle = false,
                    settled = true,
                    stop = reading.stop ?: boardStop,
                    bumpTrack = false,
                )
            }
            clearTrackedLocked()
            return null
        }
        val soonest = BusWaitCore.soonest(scoped) ?: return last?.copy()
        if (previous == null) {
            rememberLocked(soonest, nowMs)
            return publishLocked(
                arrival = soonest,
                nextVehicle = false,
                settled = true,
                stop = reading.stop ?: boardStop,
                bumpTrack = true,
            )
        }
        val trackedIsOnScreen = scoped.any { it.line == previous.line }
        if (trackedIsOnScreen) {
            trackedSeenAtMs = nowMs
        } else if (nowMs - trackedSeenAtMs < BusWaitCore.ABSENT_HOLD_MS) {
            return publishLocked(
                arrival = previous,
                nextVehicle = false,
                settled = true,
                stop = reading.stop ?: boardStop,
                bumpTrack = false,
            )
        }
        val onTrackedLine = BusWaitCore.soonest(scoped.filter { it.line == previous.line })
        if (onTrackedLine != null) {
            val settle = BusWaitCore.isSettledReading(
                previous,
                onTrackedLine,
                nowMs,
                SettleClock(
                    soonSeenAtMs = soonSeenAtMs,
                    trackedEtaAtMs = trackedEtaAtMs,
                    pendingEta = pendingEta,
                    pendingSinceMs = pendingSinceMs,
                ),
            )
            pendingEta = settle.pendingEta
            pendingSinceMs = settle.pendingSinceMs
            if (!settle.settled) {
                return publishLocked(
                    arrival = previous,
                    nextVehicle = false,
                    settled = false,
                    stop = reading.stop ?: boardStop,
                    bumpTrack = false,
                )
            }
        }
        val sameAsPrevious = BusWaitCore.findSame(previous, scoped)
        if (sameAsPrevious != null) {
            val merged = BusWaitCore.carrySheetFields(previous, sameAsPrevious)
            rememberLocked(merged, nowMs)
            return publishLocked(
                arrival = merged,
                nextVehicle = false,
                settled = true,
                stop = reading.stop ?: boardStop,
                bumpTrack = false,
            )
        }
        val next = if (soonest.line == previous.line) {
            BusWaitCore.carrySheetFields(previous, soonest)
        } else {
            soonest
        }
        rememberLocked(next, nowMs)
        return publishLocked(
            arrival = next,
            nextVehicle = true,
            settled = true,
            stop = reading.stop ?: boardStop,
            bumpTrack = true,
        )
    }

    internal fun resetForTest() {
        synchronized(lock) { clearLocked() }
        VehicleTrackingDebug.resetForTest()
    }

    private fun syncPinLocked(scope: GuidanceSnapshot) {
        val line = scope.line?.trim().orEmpty()
        val nextKind = kindOf(scope.kind)
        if (scope.generation == generation && line == pin && nextKind == kind) return
        generation = scope.generation
        pin = line
        kind = nextKind
        clearTrackedLocked()
        boardStop = null
    }

    private fun acceptsStopLocked(stop: String?): Boolean {
        val incoming = stop?.trim()?.ifEmpty { null }
        if (boardStop == null) {
            if (incoming != null) boardStop = incoming
            return true
        }
        if (incoming == null) return true
        return incoming == boardStop
    }

    private fun rememberLocked(arrival: NavigationBusArrival, nowMs: Long) {
        val previous = tracked
        if (previous != null && previous.eta != arrival.eta) {
            trackedEtaAtMs = nowMs
        }
        tracked = arrival
        trackedSeenAtMs = nowMs
        pendingEta = null
        if (TransitSoonEta.minutes(arrival.eta) == 0) soonSeenAtMs = nowMs
    }

    private fun publishLocked(
        arrival: NavigationBusArrival,
        nextVehicle: Boolean,
        settled: Boolean,
        stop: String?,
        bumpTrack: Boolean,
    ): VehicleSnapshot {
        if (bumpTrack) track += 1
        val snap = VehicleSnapshot(
            vehicleKey = VehicleKey(
                generation = generation,
                kind = kind,
                line = arrival.line,
                track = track,
            ),
            line = arrival.line,
            kind = kind,
            eta = arrival.eta,
            etaMinutes = TransitSoonEta.minutes(arrival.eta),
            nextVehicle = nextVehicle,
            settled = settled,
            stop = stop,
        )
        last = snap
        return snap.copy()
    }

    private fun clearTrackedLocked() {
        tracked = null
        track = 0
        trackedSeenAtMs = 0L
        trackedEtaAtMs = 0L
        soonSeenAtMs = null
        pendingEta = null
        pendingSinceMs = 0L
        last = null
    }

    private fun clearLocked() {
        generation = 0
        pin = null
        kind = TransitKind.NONE
        boardStop = null
        clearTrackedLocked()
    }

    private fun kindOf(kind: GuidanceKind): TransitKind = when (kind) {
        GuidanceKind.BUS -> TransitKind.BUS
        GuidanceKind.SUBWAY -> TransitKind.SUBWAY
        GuidanceKind.NONE -> TransitKind.NONE
    }

    private fun TransitRow.toArrival(): NavigationBusArrival =
        NavigationBusArrival(
            line = line,
            eta = eta,
            occupancy = occupancy,
            stopsRemaining = stopsRemaining,
        )
}
