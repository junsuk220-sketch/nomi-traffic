package nomi.android.traffic

import android.accessibilityservice.AccessibilityService
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import nomi.android.traffic.eventfirst.EventFirstEngine
import nomi.android.traffic.eventfirst.NaverA11yGuidanceEnd
import nomi.android.traffic.scope.CurrentGuidanceScope
import nomi.android.traffic.scope.CurrentGuidanceScopeDebug

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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != NaverMapNotification.PACKAGE) return
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
            if (startClick) {
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
            val windowId = root.windowId.toLong()
            val hasStartButton = flattenBlobs(tree).any { NaverTripStartParser.isStartButton(it) }
            NaverTripStartDiag.logTree(diagSeq, event, now, root, live, hasStartButton)
            if (root.packageName?.toString() != NaverMapNotification.PACKAGE) return
            if (!live) {
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
                if (NaverTripStartSession.noteNotLive(now, windowId)) {
                    NaverTripStartDiag.logSessionEndTrigger(diagSeq, event, now, root, live)
                    NavigationEventVoice.onNaverGuidanceEnded()
                    Log.i(NaverMapsTransit.TAG, "[NAVER_TRIP] session ended")
                }
            } else {
                NaverTripStartDiag.onLiveSeen()
                NaverTripStartSession.noteLiveWindow(windowId)
            }
            if (live != wasLive) {
                NaverTripStartDiag.logLiveState(diagSeq, wasLive, live, event, now, root, live)
            }
            if (live && !wasLive) {
                if (NaverTripStartSession.noteLive(now, windowId)) {
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
                    // 안내 중 vanished for a few frames inside the same guidance.
                    // A flicker is not a new trip — keep the subway cues already spoken.
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

            if (NaverTripStartSession.due(now)) {
                val liveDecision = NaverTripStartParser.decision(
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
                if (tripEvent != null) {
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

            val screenBlobs = flattenBlobs(tree)
            try {
                CurrentGuidanceScope.onScreen(screenBlobs)
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
            if (boardDirection != null && !boardDirectionSpokenThisLive) {
                Log.i(
                    NaverMapsTransit.TAG,
                    NaverTransferAccessibilityParser.formatLog(boardDirection),
                )
                NavigationEventVoice.offer(this, boardDirection)
                boardDirectionSpokenThisLive = true
            }

            val parsed = NaverSubwayAccessibilityParser.parse(
                packageName = pkg,
                root = tree,
                timestampMillis = event.eventTime,
            )
            for (item in parsed) {
                if (!dedup.accept(item)) continue
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
            RouteCardAccess.naverIntake(this).onScreen(
                destinationLabel = NaverTransitDestinationParser.destination(tree),
                liveGuidance = live,
                atMillis = now,
            )
        } finally {
            root.recycle()
        }
    }

    private fun resetTransferState() {
        awaitTransferWalk = false
        transferWalkReadyAtMs = 0L
        transferWalkSpokenThisLive = false
        boardDirectionSpokenThisLive = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        NaverRideObservationLog.attach(filesDir)
        NavigationEventVoice.prepare(this)
        Log.i(NaverMapsTransit.TAG, "service connected")
        NaverTripStartDebug.note("service_connected ${NaverTripStartDebug.buildMark()}")
        CurrentGuidanceScopeDebug.emit("feed_attached a11y")
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
        private const val AFTER_NAVER_ALIGHT_MS = 3_500L
    }
}
