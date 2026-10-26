package nomi.android.traffic

import android.accessibilityservice.AccessibilityService
import nomi.android.traffic.api.google.brief.GoogleStartBriefShadow
import nomi.android.traffic.api.google.transfer.GoogleTransferShadow
import nomi.android.traffic.setupnudge.SetupNudgeShadow
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import nomi.traffic.A11yDoneNotice
import nomi.traffic.A11yItem

/**
 * Reads the Google Maps transit screen for the route card and the field log.
 * The start-briefing cell may speak once after 시작 or when guidance goes live.
 * The transfer cell takes the transfer bus clock from the live screen.
 * Other Google speech comes from notifications.
 */
class GoogleTransitAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != GoogleMapsTransit.PACKAGE) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            try {
                GoogleRideObservationLog.recordClick(
                    System.currentTimeMillis(),
                    event.text?.joinToString("") ?: "",
                    event.contentDescription?.toString(),
                )
            } catch (_: Throwable) {
            }
            try {
                val texts = event.text
                if (GoogleTransitAccessibilityParser.isGlanceStartButton(event.contentDescription, texts) ||
                    texts?.joinToString("")?.trim() == "시작"
                ) {
                    GoogleStartBriefShadow.onStartClick()
                }
            } catch (_: Throwable) {
            }
        }
        val root = rootInActiveWindow ?: return
        try {
            if (root.packageName?.toString() != GoogleMapsTransit.PACKAGE) return
            val tree = collect(root)
            val now = System.currentTimeMillis()
            val live = GoogleTransitAccessibilityParser.isLiveGuidance(tree)
            val blobs = flatten(tree)
            try {
                GoogleRideObservationLog.recordTree(now, live, blobs)
            } catch (_: Throwable) {
            }
            try {
                GoogleStartBriefShadow.onScreen(tree, live, now)
            } catch (_: Throwable) {
            }
            try {
                GoogleTransferShadow.onScreen(blobs, live, now)
            } catch (_: Throwable) {
            }
            try {
                if (live) SetupNudgeShadow.onLiveScreen(this)
            } catch (_: Throwable) {
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        NavigationEventVoice.prepare(this)
        GoogleRideObservationLog.attach(filesDir)
        try {
            GoogleStartBriefShadow.attach(filesDir)
        } catch (_: Throwable) {
        }
        Log.i(GoogleMapsTransit.TAG, "service connected")
        try {
            A11yDoneNotice.showIfArmed(this, A11yItem.GOOGLE)
        } catch (_: Throwable) {
        }
    }

    override fun onInterrupt() = Unit

    private fun flatten(node: GoogleTransitAccessibilityParser.Node, out: MutableList<String> = ArrayList()): List<String> {
        node.text?.let(out::add)
        node.contentDesc?.let(out::add)
        node.children.forEach { flatten(it, out) }
        return out
    }

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
}
