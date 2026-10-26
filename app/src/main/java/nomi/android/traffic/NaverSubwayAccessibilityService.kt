package nomi.android.traffic

import android.accessibilityservice.AccessibilityService
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import nomi.android.traffic.eventfirst.EventFirstEngine
import nomi.android.traffic.eventfirst.NaverA11yGuidanceEnd
import nomi.android.traffic.api.briefing.StartBriefingShadow
import nomi.android.traffic.api.briefing.StartBriefingShadowDebug
import nomi.android.traffic.api.transfer.TransferShadow
import nomi.android.traffic.api.transfer.TransferShadowDebug
import nomi.android.traffic.api.eta.EtaShadow
import nomi.android.traffic.api.nextbus.NextBusNoInfoDebug
import nomi.android.traffic.api.nexttrain.NextTrainDebug
import nomi.android.traffic.api.nextbus.NextBusNoInfoShadow
import nomi.android.traffic.api.routelegs.RouteLegs
import nomi.android.traffic.api.routelegs.RouteLegsDebug
import nomi.android.traffic.observe.TransitObservation
import nomi.android.traffic.observe.TransitObservationDebug
import nomi.android.traffic.observe.TransitReading
import nomi.android.traffic.scope.CurrentGuidanceScope
import nomi.android.traffic.scope.CurrentGuidanceScopeDebug
import nomi.android.traffic.setupnudge.SetupNudgeShadow
import nomi.android.traffic.speech.SpeechLedger
import nomi.android.traffic.track.VehicleTracking
import nomi.android.traffic.track.VehicleTrackingDebug
import nomi.traffic.A11yDoneNotice
import nomi.traffic.A11yItem

/**
 * Reads the current Naver Maps screen for subway 빠른 하차 / 빠른 환승,
 * transfer walk after alight, bus board direction, live bus rows,
 * and trip-start briefing after guidance begins.
 * Does not call TTS itself or click.
 */
class NaverSubwayAccessibilityService : AccessibilityService() {

    private val dedup = NaverSubwayAccessibilityDedup()
    private var wasLive = false
    private var awaitTransferWalk = false
    private var transferWalkReadyAtMs = 0L
    private var transferWalkSpokenThisLive = false
    private var boardDirectionSpokenThisLive = false
    private var awaitBoardDirection = false
    private var boardDirectionReadyAtMs = 0L
    private var pendingBoardDirection: nomi.product.nav.NavigationEvent? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != NaverMapNotification.PACKAGE) return
        NavigationEventVoice.tickStartBrief()
        val interactive = getSystemService(PowerManager::class.java)?.isInteractive != false
        NaverRideObservationLog.recordA11yEvent(
            ts = System.currentTimeMillis(),
            pkg = pkg,
            interactive = interactive,
            a11yType = NaverRideObservationLog.typeName(event.eventType),
            className = event.className?.toString(),
            text = event.text?.joinToString(", ") { it?.toString().orEmpty() },
            contentDescription = event.contentDescription?.toString(),
            viewId = clickViewId(event),
            skipDedup = event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        )
        val diagSeq = NaverTripStartDiag.nextSeq()
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val texts = event.text?.joinToString("") ?: ""
            val desc = event.contentDescription?.toString().orEmpty()
            val startClick = NaverTripStartParser.isStartButton(texts) ||
                NaverTripStartParser.isStartButton(desc) ||
                texts.contains("안내시작") ||
                desc.contains("안내시작")
            NaverTripStartDiag.logClick(diagSeq, event, texts, desc, startClick)
            if (startClick || NaverTripStartParser.isEndButton(texts) || NaverTripStartParser.isEndButton(desc)) {
                NaverGuidanceRemoved.flush()
            }
            if (startClick) {
                NaverTripStartDebug.note("click_arm text=[${texts.take(120)}] desc=[${desc.take(120)}]")
                val armed = NaverTripStartSession.restartFromClick(System.currentTimeMillis())
                NavigationEventVoice.resetNaverTripStart()
                NaverTripStartDiag.onSessionClockReset()
                Log.i(NaverMapsTransit.TAG, "[NAVER_TRIP] start button armed")
                NaverTripStartDebug.step1(
                    how = "click",
                    armed = armed,
                    session = NaverTripStartSession.debugSnapshot(),
                    due = NaverTripStartSession.due(System.currentTimeMillis()),
                )
                NavigationEventVoice.pinNaverFromTripCache()
            }
            if (NaverTripStartParser.isEndButton(texts) ||
                NaverTripStartParser.isEndButton(desc)
            ) {
                // Do not wait for the 안내 중 label to fade — the stop board that
                // replaces it must not speak one more bus.
                NaverTripStartSession.reset()
                NaverTripStartCache.clear()
                NavigationEventVoice.onNaverGuidanceEnded()
                wasLive = false
                resetTransferState()
                Log.i(NaverMapsTransit.TAG, "[NAVER_TRIP] end button, guidance ended")
            }
        }
        if (getSystemService(PowerManager::class.java)?.isInteractive == false) return
        val root = rootInActiveWindow
        if (root == null) {
            NaverTripStartDiag.logNoRoot(diagSeq, event, System.currentTimeMillis())
            return
        }
        try {
            val tree = collect(root)
            val now = System.currentTimeMillis()
            val live = NaverTransitDestinationParser.isLiveGuidance(tree)
            val screenBlobs = flattenBlobs(tree)
            try {
                CurrentGuidanceScope.onScreen(screenBlobs)
            } catch (_: Throwable) {
            }
            if (LeftoverSubway.dropPinIfBusScope()) {
                Log.i(NaverMapNotification.TAG, "[NAVER_PIN] leftover subway dropped, scope=BUS")
            }
            val hasStartButton = screenBlobs.any { NaverTripStartParser.isStartButton(it) }
            NaverTripStartDiag.logTree(diagSeq, event, now, root, live, hasStartButton)
            if (root.packageName?.toString() != NaverMapNotification.PACKAGE) return
            if (!live) {
                // Back on the options list: the detail looked at before is not the chosen route.
                if (NaverTripStartParser.isRouteList(screenBlobs)) NaverTripStartCache.clear()
                when (
                    val preview = NaverTripStartParser.decision(
                        packageName = pkg,
                        root = tree,
                        timestampMillis = now,
                        requireLive = false,
                    )
                ) {
                    is NaverTripStartParser.Decision.Speak -> {
                        NaverTripStartCache.remember(preview.event)
                        Log.i(NaverMapsTransit.TAG, "[NAVER_TRIP] preview cached")
                    }
                    NaverTripStartParser.Decision.Pending -> Unit
                }
                if (NaverTripStartDiag.consumeNotLiveBegin()) {
                    NaverTripStartDiag.logNotLiveBegin(diagSeq, event, now, root, live)
                }
            } else {
                NaverTripStartDiag.onLiveSeen()
            }
            if (live != wasLive) {
                NaverTripStartDiag.logLiveState(diagSeq, wasLive, live, event, now, root, live)
            }
            if (live && !wasLive) {
                if (NaverTripStartSession.noteLive(now)) {
                    Log.i(NaverMapsTransit.TAG, "[NAVER_TRIP] live guidance armed")
                    NaverTripStartDebug.step1(
                        how = "live_edge",
                        armed = true,
                        session = NaverTripStartSession.debugSnapshot(now),
                        due = NaverTripStartSession.due(now),
                    )
                    resetTransferState()
                    NavigationEventVoice.onNaverGuidanceStarted()
                    NavigationEventVoice.pinNaverFromTripCache()
                } else {
                    // 안내 중 came back inside the same guidance. Not a new trip.
                    Log.i(NaverMapsTransit.TAG, "[NAVER_TRIP] live guidance began")
                    NaverTripStartDebug.note(
                        "STEP1 live_edge_skip ${NaverTripStartSession.debugSnapshot(now)}",
                    )
                }
            }
            if (!live && wasLive) {
                resetTransferState()
                NavigationEventVoice.onNaverGuidanceOffScreen()
            }
            wasLive = live
            try {
                if (live) SetupNudgeShadow.onLiveScreen(this)
            } catch (_: Throwable) {
            }
            if (live) {
                NaverFirstLeg.read(screenBlobs)?.let { leg ->
                    if (NaverTripStartSession.noteFirstLeg(leg, now)) {
                        Log.i(NaverMapsTransit.TAG, "[NAVER_TRIP] first leg ${leg.label()}")
                    }
                }
            }

            if (NaverTripStartSession.dropUnconfirmed(now)) {
                NaverTripStartCache.clear()
                NaverTripStartDebug.note("click_unconfirmed_dropped")
            }
            if (NaverTripStartSession.due(now)) {
                val legBlock = NaverTripStartSession.firstLeg()?.block.orEmpty()
                val fromLeg = if (legBlock.isEmpty()) {
                    null
                } else {
                    NaverTripStartParser.decision(
                        packageName = pkg,
                        root = NaverFirstLeg.node(legBlock),
                        timestampMillis = now,
                        requireLive = true,
                    ) as? NaverTripStartParser.Decision.Speak
                }
                val liveDecision = fromLeg ?: NaverTripStartParser.decision(
                    packageName = pkg,
                    root = tree,
                    timestampMillis = now,
                    requireLive = true,
                )
                val fromSpeak = liveDecision is NaverTripStartParser.Decision.Speak
                val cached = if (!fromSpeak) NaverTripStartCache.peek() else null
                val tripEvent = when (liveDecision) {
                    is NaverTripStartParser.Decision.Speak -> liveDecision.event
                    NaverTripStartParser.Decision.Pending -> NaverTripStartCache.take()
                }
                val decisionLabel = when (liveDecision) {
                    is NaverTripStartParser.Decision.Speak -> "Speak"
                    NaverTripStartParser.Decision.Pending -> "Pending"
                }
                val tripLine = tripEvent?.let { NavigationEventSpeech.line(it) }
                if (tripEvent != null &&
                    !NaverFirstLeg.allows(tripLine, NaverTripStartSession.firstLeg())
                ) {
                    Log.i(
                        NaverMapsTransit.TAG,
                        "[NAVER_TRIP] screen briefing not first leg " +
                            "line=${tripLine.orEmpty()} leg=${NaverTripStartSession.firstLeg()?.label()}",
                    )
                } else if (tripEvent != null) {
                    NaverTripStartDebug.step3(
                        due = true,
                        decision = decisionLabel,
                        fromSpeak = fromSpeak,
                        cacheHit = !fromSpeak && cached != null,
                        tripNull = false,
                        action = tripEvent.action,
                        raw = tripEvent.busInfo?.raw.orEmpty(),
                        spokenAlready = NaverTripStartSession.hasSpokenBriefing(),
                        giveUp = false,
                    )
                    Log.i(
                        NaverMapsTransit.TAG,
                        NaverBusAccessibilityParser.formatLog(tripEvent),
                    )
                    NavigationEventVoice.pinNaverFromTripEvent(tripEvent)
                    NavigationEventVoice.offer(this, tripEvent)
                    NaverTripStartCache.clear()
                    if (NaverTripStartSession.hasSpokenBriefing()) {
                        Log.i(NaverMapsTransit.TAG, "[NAVER_TRIP] spoken ${tripEvent.busInfo?.raw.orEmpty()}")
                    }
                } else {
                    val gaveUp = NaverTripStartSession.giveUpIfStale(now)
                    NaverTripStartDebug.step3(
                        due = true,
                        decision = decisionLabel,
                        fromSpeak = false,
                        cacheHit = false,
                        tripNull = true,
                        action = "-",
                        raw = "-",
                        spokenAlready = NaverTripStartSession.hasSpokenBriefing(),
                        giveUp = gaveUp,
                    )
                    if (gaveUp) {
                        Log.i(NaverMapsTransit.TAG, "[NAVER_TRIP] still waiting for briefing, hold lifted")
                    }
                }
            }

            var transferReading: TransitReading? = null
            try {
                val reading = TransitObservation.fromScreen(pkg, tree, now)
                transferReading = reading
                TransitObservationDebug.emit(reading)
                val scope = CurrentGuidanceScope.snapshot()
                val vehicle = VehicleTracking.observe(scope, reading)
                VehicleTrackingDebug.emit(vehicle)
                EtaShadow.tick(
                    vehicle,
                    NavigationEventVoice.speechLedger() ?: SpeechLedger(),
                    now,
                )
            } catch (_: Throwable) {
            }
            try {
                TransferShadow.tick(screenBlobs, transferReading, liveScreen = live)
            } catch (_: Throwable) {
            }
            try {
                val scope = CurrentGuidanceScope.snapshot()
                StartBriefingShadow.tick(scope, transferReading)
            } catch (_: Throwable) {
            }
            try {
                NextBusNoInfoShadow.tickScreen(screenBlobs, now)
            } catch (_: Throwable) {
            }
            try {
                RouteLegs.observe(screenBlobs, live, now)
            } catch (_: Throwable) {
            }
            // The trip-start hold below returns before the rest of this event.
            // Save the route just read, or the card keeps the previous note.
            try {
                RouteCardAccess.naverIntake(this).onScreen(
                    destinationLabel = NaverTransitDestinationParser.destination(tree),
                    liveGuidance = live,
                    atMillis = now,
                )
            } catch (_: Throwable) {
            }
            NaverRideObservationLog.recordA11yTree(
                ts = now,
                pkg = pkg,
                live = live,
                blobs = screenBlobs,
            )
            if (NaverA11yGuidanceEnd.read(screenBlobs)) {
                EventFirstEngine.onGuidanceEnd(now)
                NaverTripStartSession.reset()
                NavigationEventVoice.onNaverGuidanceEnded()
            }
            screenBlobs.forEach {
                NavigationEventVoice.noteNaverNearBoard(it)
                NavigationEventVoice.noteNaverPrepareAlight(it)
            }
            NavigationEventVoice.noteNaverAlightStops(screenBlobs)

            if (live && NaverTripStartSession.holdBusWait(now)) {
                return
            }

            val transferWalk = NaverTransferAccessibilityParser.transferWalk(
                packageName = pkg,
                root = tree,
                timestampMillis = now,
            )
            if (transferWalk != null && !transferWalkSpokenThisLive) {
                if (!awaitTransferWalk) {
                    awaitTransferWalk = true
                    transferWalkReadyAtMs = now + AFTER_NAVER_ALIGHT_MS
                    Log.i(NaverMapsTransit.TAG, "[NAVER_TRANSFER] walk armed")
                }
            }
            if (awaitTransferWalk &&
                now >= transferWalkReadyAtMs &&
                transferWalk != null &&
                !transferWalkSpokenThisLive
            ) {
                Log.i(
                    NaverMapsTransit.TAG,
                    NaverTransferAccessibilityParser.formatLog(transferWalk),
                )
                NavigationEventVoice.offer(this, transferWalk)
                awaitTransferWalk = false
                transferWalkSpokenThisLive = true
            }

            val boardDirection = NaverTransferAccessibilityParser.busBoardDirection(
                packageName = pkg,
                root = tree,
                timestampMillis = now,
            )
            if (boardDirection != null && !boardDirectionSpokenThisLive && !awaitBoardDirection) {
                awaitBoardDirection = true
                boardDirectionReadyAtMs = now + AFTER_NAVER_ALIGHT_MS
                pendingBoardDirection = boardDirection
                Log.i(NaverMapsTransit.TAG, "[NAVER_TRANSFER] board direction armed")
            }
            if (awaitBoardDirection &&
                now >= boardDirectionReadyAtMs &&
                pendingBoardDirection != null &&
                !boardDirectionSpokenThisLive
            ) {
                Log.i(
                    NaverMapsTransit.TAG,
                    NaverTransferAccessibilityParser.formatLog(pendingBoardDirection!!),
                )
                NavigationEventVoice.offer(this, pendingBoardDirection!!)
                boardDirectionSpokenThisLive = true
                awaitBoardDirection = false
                pendingBoardDirection = null
            }

            val parsed = NaverSubwayAccessibilityParser.parse(
                packageName = pkg,
                root = tree,
                timestampMillis = event.eventTime,
            )
            for (item in parsed) {
                if (!dedup.accept(item)) continue
                val afterNaver =
                    item.action == NaverMapsTransit.QUICK_TRANSFER ||
                        item.action == NaverMapsTransit.QUICK_EXIT
                if (afterNaver) {
                    if (!boardDirectionSpokenThisLive && !awaitBoardDirection) {
                        awaitBoardDirection = true
                        boardDirectionReadyAtMs = now + AFTER_NAVER_ALIGHT_MS
                        pendingBoardDirection = item
                        Log.i(NaverMapsTransit.TAG, "[NAVER_TRANSFER] board car cue armed")
                    }
                    continue
                }
                Log.i(NaverMapsTransit.TAG, NaverSubwayAccessibilityParser.formatLog(item))
                NavigationEventVoice.offer(this, item)
            }
            val busEvents = NaverBusAccessibilityParser.parse(
                packageName = pkg,
                root = tree,
                timestampMillis = event.eventTime,
            )
            if (!live) {
                NavigationEventVoice.releaseNaverWaitSheet()
            } else if (NavigationEventVoice.ignoreNaverSheetBus(this)) {
                // Screen dark: do not let stale/empty a11y reclaim sheet ownership.
                // 302 notifications own 10/5/2/곧 until the display is interactive again.
                Log.i(NaverMapsTransit.TAG, "[NAVER_BUS_A11Y] skipped while screen off")
            } else if (busEvents.isEmpty()) {
                NavigationEventVoice.onNaverWaitSheetEmpty()
            } else {
                for (item in busEvents) {
                    Log.i(NaverMapsTransit.TAG, NaverBusAccessibilityParser.formatLog(item))
                    NavigationEventVoice.offer(this, item)
                }
            }
        } finally {
            root.recycle()
        }
    }

    private fun resetTransferState() {
        awaitTransferWalk = false
        transferWalkReadyAtMs = 0L
        transferWalkSpokenThisLive = false
        boardDirectionSpokenThisLive = false
        awaitBoardDirection = false
        boardDirectionReadyAtMs = 0L
        pendingBoardDirection = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        NaverRideObservationLog.attach(filesDir)
        NaverTripStartDebug.attach(filesDir)
        NavigationEventVoice.prepare(this)
        Log.i(NaverMapsTransit.TAG, "service connected")
        NaverTripStartDebug.note("service_connected ${NaverTripStartDebug.buildMark()}")
        CurrentGuidanceScopeDebug.emit("feed_attached a11y")
        TransitObservationDebug.emit("feed_attached a11y")
        VehicleTrackingDebug.emit("feed_attached a11y")
        StartBriefingShadowDebug.emit("feed_attached a11y")
        TransferShadowDebug.attach(filesDir)
        TransferShadowDebug.emit("feed_attached a11y")
        NextTrainDebug.attach(filesDir)
        NextTrainDebug.emit("feed_attached a11y")
        NextBusNoInfoDebug.attach(filesDir)
        NextBusNoInfoDebug.emit("feed_attached a11y")
        RouteLegsDebug.attach(filesDir)
        RouteLegsDebug.emit("feed_attached a11y")
        try {
            A11yDoneNotice.showIfArmed(this, A11yItem.NAVER)
        } catch (_: Throwable) {
        }
    }

    override fun onInterrupt() = Unit

    private fun clickViewId(event: AccessibilityEvent): String? {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return null
        val src = event.source ?: return null
        return try {
            src.viewIdResourceName?.takeIf { it.isNotBlank() }
        } finally {
            src.recycle()
        }
    }

    private fun flattenBlobs(node: NaverSubwayAccessibilityParser.Node): List<String> {
        val out = ArrayList<String>()
        fun walk(n: NaverSubwayAccessibilityParser.Node) {
            n.text?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
            n.contentDesc?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
            n.children.forEach(::walk)
        }
        walk(node)
        return out
    }

    private fun collect(node: AccessibilityNodeInfo): NaverSubwayAccessibilityParser.Node {
        val children = ArrayList<NaverSubwayAccessibilityParser.Node>(node.childCount)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                children.add(collect(child))
            } finally {
                child.recycle()
            }
        }
        return NaverSubwayAccessibilityParser.Node(
            text = node.text?.toString(),
            contentDesc = node.contentDescription?.toString(),
            children = children,
        )
    }

    companion object {
        /** Let Naver finish the alight cue before transfer walk. */
        private const val AFTER_NAVER_ALIGHT_MS = 5_000L
    }
}
