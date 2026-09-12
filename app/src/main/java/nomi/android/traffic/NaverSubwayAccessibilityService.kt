package nomi.android.traffic

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val texts = event.text?.joinToString("") ?: ""
            val desc = event.contentDescription?.toString().orEmpty()
            if (NaverTripStartParser.isStartButton(texts) ||
                NaverTripStartParser.isStartButton(desc) ||
                texts.contains("안내시작") ||
                desc.contains("안내시작")
            ) {
                NaverTripStartSession.restartFromClick(System.currentTimeMillis())
                NavigationEventVoice.resetNaverTripStart()
                Log.i(NaverMapsTransit.TAG, "[NAVER_TRIP] start button armed")
                NavigationEventVoice.pinNaverFromTripCache()
            }
            if (NaverTripStartParser.isEndButton(texts) ||
                NaverTripStartParser.isEndButton(desc)
            ) {
                // Do not wait for the 안내 중 label to fade — the stop board that
                // replaces it must not speak one more bus.
                NaverTripStartSession.reset()
                NaverTripStartCache.clear()
                NavigationEventVoice.leaveNaverWaitSheet()
                wasLive = false
                resetTransferState()
                Log.i(NaverMapsTransit.TAG, "[NAVER_TRIP] end button, guidance ended")
            }
        }
        val root = rootInActiveWindow ?: return
        try {
            if (root.packageName?.toString() != NaverMapNotification.PACKAGE) return
            val tree = collect(root)
            val now = System.currentTimeMillis()
            val live = NaverTransitDestinationParser.isLiveGuidance(tree)
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
                if (NaverTripStartSession.noteNotLive(now)) {
                    NavigationEventVoice.resetNaverTripStart()
                    NavigationEventVoice.leaveNaverWaitSheet()
                    Log.i(NaverMapsTransit.TAG, "[NAVER_TRIP] session ended")
                }
            }
            if (live && !wasLive) {
                if (NaverTripStartSession.noteLive(now)) {
                    Log.i(NaverMapsTransit.TAG, "[NAVER_TRIP] live guidance armed")
                } else {
                    Log.i(NaverMapsTransit.TAG, "[NAVER_TRIP] live guidance began")
                }
                resetTransferState()
                NavigationEventVoice.resetNaverTransferCues()
                NavigationEventVoice.resetNaverSubwayStages()
                NavigationEventVoice.resetNaverPrepareAlight()
                NavigationEventVoice.pinNaverFromTripCache()
            }
            if (!live && wasLive) {
                resetTransferState()
                NavigationEventVoice.resetNaverTransferCues()
                NavigationEventVoice.releaseNaverWaitSheet()
            }
            wasLive = live

            if (NaverTripStartSession.due(now)) {
                val liveDecision = NaverTripStartParser.decision(
                    packageName = pkg,
                    root = tree,
                    timestampMillis = now,
                    requireLive = true,
                )
                val tripEvent = when (liveDecision) {
                    is NaverTripStartParser.Decision.Speak -> liveDecision.event
                    NaverTripStartParser.Decision.Pending -> NaverTripStartCache.take()
                }
                if (tripEvent != null) {
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
                } else if (NaverTripStartSession.giveUpIfStale(now)) {
                    Log.i(NaverMapsTransit.TAG, "[NAVER_TRIP] still waiting for briefing, hold lifted")
                }
            }

            val screenBlobs = flattenBlobs(tree)
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
        NavigationEventVoice.prepare(this)
        Log.i(NaverMapsTransit.TAG, "service connected")
    }

    override fun onInterrupt() = Unit

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
