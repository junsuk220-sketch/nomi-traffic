package nomi.android.traffic

import nomi.android.traffic.buswait.BusWaitCore
import nomi.android.traffic.buswait.BusWaitFieldTrace
import nomi.android.traffic.buswait.BusWaitSilence
import nomi.product.nav.NavigationBusArrival

/**
 * Adapter around [BusWaitCore]: sheet ownership for occupancy vs 302.
 * Pin / track / stage live in the core (constitution).
 */
class NaverBusWaitTracker(
    private val core: BusWaitCore = BusWaitCore(),
) {

    data class Snapshot(
        val target: NavigationBusArrival?,
        val switched: Boolean,
        /** Non-null ⇒ Host must speak this wait stage for [target]. */
        val speakStage: Int? = null,
        /** From [BusWaitCore.Tick.silence]. Null when this snapshot never reached the core. */
        val silence: BusWaitSilence? = null,
    )

    private val lock = Any()
    private var sheetOwns = false
    private var lastSheetAtMs = 0L
    private var lastTarget: NavigationBusArrival? = null
    /** Bus-side latch. The subway line itself belongs to [NaverSubwayPin]. */
    private var closedForSubwayTrip = false
    private var boardStop: String? = null
    private var boardStopLocked = false
    /** Naver said `이번 정류장에서 하차`; the next 302 wait board may name the transfer bus. */
    private var busAlighted = false
    /**
     * Naver said `이동 중` / `정차` — the rider is already aboard.
     * Sheet boards must stay silent: the live route list still shows the left stop's
     * next buses (2026-10-16 15:37: 98번 곧 → sheet 98번 2분 while Riding).
     */
    private var riding = false
    /** Naver said a subway `하차 후 환승`; the next bus wait board opens the bus ladder. */
    private var subwayTransferAtMs = 0L

    fun hasSheetTarget(): Boolean = synchronized(lock) { sheetOwns && lastTarget != null }

    fun pinnedBusLine(): String? = synchronized(lock) { core.pinnedLines().firstOrNull() }

    fun pinnedBusLines(): Set<String> = synchronized(lock) { core.pinnedLines() }

    fun core(): BusWaitCore = core

    fun pinBusLine(line: String) {
        synchronized(lock) {
            core.seed(line)
            openBusForThisStop()
        }
    }

    fun pinBusLines(lines: Collection<String>) {
        synchronized(lock) {
            core.seed(lines)
            openBusForThisStop()
        }
    }

    /**
     * The trip boards a subway first, so the bus wait shuts itself until a
     * transfer names the bus to ride ([pinBusLine]). Only the owner of the
     * ladder may clear it — a subway pin cannot reach in (8-2).
     */
    fun closeForSubwayTrip() {
        synchronized(lock) {
            if (closedForSubwayTrip) return
            closedForSubwayTrip = true
            core.leave()
            boardStop = null
            boardStopLocked = false
            lastTarget = null
        }
    }

    fun isClosedForSubwayTrip(): Boolean = synchronized(lock) { closedForSubwayTrip }

    fun noteBusAlighted() {
        synchronized(lock) {
            busAlighted = true
            riding = false
            sheetOwns = false
        }
    }

    /** 302 `…으로 이동 중` / `… 정차`. Sheet wait boards stay quiet until the next wait board. */
    fun noteRiding() {
        synchronized(lock) {
            riding = true
            sheetOwns = false
        }
    }

    fun isRiding(): Boolean = synchronized(lock) { riding }

    fun noteSubwayTransfer(nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) { subwayTransferAtMs = nowMs }
    }

    /**
     * The first bus board shortly after Naver's `하차 후 환승`.
     * A subway-first trip is closed until then. A bus-first trip that later
     * rides a subway re-seeds the same way, so the transfer bus is not rejected
     * as another stop.
     */
    fun openFromSubwayTransfer(
        lines: Collection<String>,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = synchronized(lock) {
        if (subwayTransferAtMs <= 0L) return@synchronized false
        if (nowMs - subwayTransferAtMs !in 0..SUBWAY_TRANSFER_WINDOW_MS) {
            subwayTransferAtMs = 0L
            return@synchronized false
        }
        val clean = lines.map(String::trim).filter(String::isNotEmpty).distinct()
        if (clean.isEmpty()) return@synchronized false
        core.seed(clean)
        openBusForThisStop()
        subwayTransferAtMs = 0L
        true
    }

    fun onSheet(
        arrivals: List<NavigationBusArrival>,
        nowMs: Long = System.currentTimeMillis(),
    ): Snapshot = synchronized(lock) {
        if (riding) {
            sheetOwns = false
            BusWaitFieldTrace.record(
                nowMs = nowMs,
                source = "sheet",
                stop = null,
                pinned = core.pinnedLines(),
                seeded = core.seededLines(),
                prevLine = lastTarget?.line,
                prevEta = lastTarget?.eta,
                arrivals = arrivals,
                targetLine = lastTarget?.line,
                targetEta = lastTarget?.eta,
                switched = false,
                speakStage = null,
                silence = null,
                path = null,
            )
            return Snapshot(
                target = lastTarget,
                switched = false,
                speakStage = null,
                silence = null,
            )
        }
        if (arrivals.isEmpty()) {
            return observeAndTrace(arrivals, nowMs, source = "sheet", stop = null)
        }
        sheetOwns = true
        lastSheetAtMs = nowMs
        observeAndTrace(arrivals, nowMs, source = "sheet", stop = null)
    }

    /**
     * @param stop boarding stop the 302 board belongs to. A board from another
     * stop belongs to a leftover trip and must not drive this wait.
     */
    fun onNotification(
        arrivals: List<NavigationBusArrival>,
        stop: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): Snapshot? = synchronized(lock) {
        if (arrivals.isEmpty()) return@synchronized null
        if (busAlighted) {
            busAlighted = false
            val pinned = core.pinnedLines()
            if (pinned.isNotEmpty() && arrivals.none { it.line in pinned }) {
                core.seed(arrivals.map { it.line })
                openBusForThisStop()
            }
        }
        if (!acceptsBoard(arrivals, stop)) return@synchronized null
        riding = false
        sheetOwns = false
        observeAndTrace(arrivals, nowMs, source = "notification", stop = stop)
    }

    fun noteSpoken(nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) { core.noteSpoken(nowMs) }
    }

    fun lockedBoardStop(): String? = synchronized(lock) { boardStop }

    private fun acceptsBoard(arrivals: List<NavigationBusArrival>, stop: String?): Boolean {
        val seeds = core.seededLines()
        if (seeds.isEmpty()) return true
        if (!boardStopLocked) {
            // Lock onto the stop whose board still carries the line this trip seeded.
            if (arrivals.none { it.line in seeds }) return false
            boardStop = stop
            boardStopLocked = true
            return true
        }
        val locked = boardStop ?: return true
        if (stop == null) return true
        return stop == locked
    }

    private fun openBusForThisStop() {
        sheetOwns = false
        closedForSubwayTrip = false
        riding = false
        boardStop = null
        boardStopLocked = false
    }

    fun releaseSheetOwnership() {
        synchronized(lock) {
            sheetOwns = false
        }
    }

    fun isSheetOwning(): Boolean = synchronized(lock) { sheetOwns }

    fun leave() {
        synchronized(lock) {
            core.leave()
            sheetOwns = false
            lastSheetAtMs = 0L
            lastTarget = null
            closedForSubwayTrip = false
            boardStop = null
            boardStopLocked = false
            busAlighted = false
            riding = false
            subwayTransferAtMs = 0L
        }
    }

    private fun observeAndTrace(
        arrivals: List<NavigationBusArrival>,
        nowMs: Long,
        source: String,
        stop: String?,
    ): Snapshot {
        val prev = lastTarget
        val tick = core.observe(arrivals, nowMs)
        BusWaitFieldTrace.record(
            nowMs = nowMs,
            source = source,
            stop = stop,
            pinned = core.pinnedLines(),
            seeded = core.seededLines(),
            prevLine = prev?.line,
            prevEta = prev?.eta,
            arrivals = arrivals,
            targetLine = tick?.target?.line,
            targetEta = tick?.target?.eta,
            switched = tick?.switched ?: false,
            speakStage = tick?.speakStage,
            silence = tick?.silence,
            path = tick?.path,
            skippedStages = tick?.skippedStages ?: emptyList(),
        )
        return toSnapshot(tick)
    }

    private fun toSnapshot(tick: BusWaitCore.Tick?): Snapshot {
        if (tick == null) {
            lastTarget = null
            return Snapshot(target = null, switched = false, speakStage = null, silence = null)
        }
        lastTarget = tick.target
        return Snapshot(
            target = tick.target,
            switched = tick.switched,
            speakStage = tick.speakStage,
            silence = tick.silence,
        )
    }

    companion object {
        private const val SUBWAY_TRANSFER_WINDOW_MS = 5 * 60_000L
        internal const val SHEET_STALE_MS = 15_000L
        internal const val ETA_SLACK_MINUTES = BusWaitCore.ETA_SLACK_MINUTES
        internal const val STOP_SLACK = BusWaitCore.STOP_SLACK

        internal fun soonest(arrivals: List<NavigationBusArrival>) = BusWaitCore.soonest(arrivals)

        internal fun findSame(
            previous: NavigationBusArrival,
            arrivals: List<NavigationBusArrival>,
        ) = BusWaitCore.findSame(previous, arrivals)

        internal fun carrySheetFields(
            previous: NavigationBusArrival,
            candidate: NavigationBusArrival,
        ) = BusWaitCore.carrySheetFields(previous, candidate)

        internal fun isSameVehicle(
            previous: NavigationBusArrival,
            candidate: NavigationBusArrival,
        ) = BusWaitCore.isSameVehicle(previous, candidate)
    }
}
