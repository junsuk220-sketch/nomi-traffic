package nomi.android.traffic

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reads only the current Google Maps transit screen and hands events to [NavigationEventVoice].
 * Does not call TTS itself or click.
 */
class GoogleTransitAccessibilityService : AccessibilityService() {

    private val dedup = GoogleTransitAccessibilityDedup()
    private var awaitTripStart = false
    private var wasLive = false
    private var tripStartSpokenThisLive = false
    private var quietParseUntilMs = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != GoogleMapsTransit.PACKAGE) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val desc = event.contentDescription
            val texts = event.text
            val textJoin = texts?.joinToString("") ?: ""
            if (GoogleTransitAccessibilityParser.isGlanceStartButton(desc, texts) ||
                textJoin.trim() == "시작"
            ) {
                armTripStart(force = true)
                Log.i(GoogleMapsTransit.TAG, "[GOOGLE_TRIP] start button clicked")
            }
        }
        val root = rootInActiveWindow ?: return
        try {
            if (root.packageName?.toString() != GoogleMapsTransit.PACKAGE) return
            val tree = collect(root)
            val now = System.currentTimeMillis()
            val live = GoogleTransitAccessibilityParser.isLiveGuidance(tree)
            if (live && !wasLive) {
                if (!tripStartSpokenThisLive) {
                    armTripStart(force = false)
                    Log.i(GoogleMapsTransit.TAG, "[GOOGLE_TRIP] live guidance began")
                }
            }
            if (!live && wasLive) {
                awaitTripStart = false
                quietParseUntilMs = 0L
                tripStartSpokenThisLive = false
                dedup.resetTripStart()
            }
            wasLive = live
            if (awaitTripStart) {
                when (
                    val decision = GoogleTransitAccessibilityParser.tripStartDecision(
                        packageName = pkg,
                        root = tree,
                        timestampMillis = now,
                    )
                ) {
                    is GoogleTransitAccessibilityParser.TripStartDecision.Speak -> {
                        if (dedup.accept(decision.event)) {
                            Log.i(
                                GoogleMapsTransit.TAG,
                                GoogleTransitAccessibilityParser.formatLog(decision.event),
                            )
                            NavigationEventVoice.offer(this, decision.event)
                            awaitTripStart = false
                            tripStartSpokenThisLive = true
                            quietParseUntilMs = now + TRIP_START_QUIET_MS
                        }
                    }
                    GoogleTransitAccessibilityParser.TripStartDecision.Pending -> Unit
                }
            }
            if (live && now >= quietParseUntilMs) {
                val parsed = GoogleTransitAccessibilityParser.parse(
                    packageName = pkg,
                    root = tree,
                    timestampMillis = now,
                )
                for (item in parsed) {
                    if (!dedup.accept(item)) continue
                    Log.i(GoogleMapsTransit.TAG, GoogleTransitAccessibilityParser.formatLog(item))
                    NavigationEventVoice.offer(this, item)
                }
            }
            RouteCardAccess.googleIntake(this).onScreen(
                destinationLabel = GoogleTransitAccessibilityParser.destinationLabel(tree),
                liveGuidance = live,
                atMillis = now,
            )
        } finally {
            root.recycle()
        }
    }

    private fun armTripStart(force: Boolean) {
        if (force) {
            tripStartSpokenThisLive = false
            NavigationEventVoice.resetGoogleTripStart()
            dedup.resetTripStart()
        }
        awaitTripStart = true
        quietParseUntilMs = 0L
        if (!force) {
            dedup.resetTripStart()
            NavigationEventVoice.resetGoogleTripStart()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        NavigationEventVoice.prepare(this)
        Log.i(GoogleMapsTransit.TAG, "service connected")
    }

    override fun onInterrupt() = Unit

    private fun collect(node: AccessibilityNodeInfo): GoogleTransitAccessibilityParser.Node {
        val children = ArrayList<GoogleTransitAccessibilityParser.Node>(node.childCount)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                children.add(collect(child))
            } finally {
                child.recycle()
            }
        }
        return GoogleTransitAccessibilityParser.Node(
            text = node.text?.toString(),
            contentDesc = node.contentDescription?.toString(),
            children = children,
        )
    }

    companion object {
        private const val TRIP_START_QUIET_MS = 12_000L
    }
}
