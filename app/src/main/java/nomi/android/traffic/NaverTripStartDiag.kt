package nomi.android.traffic

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Read-only field diagnostics for trip-start session A/B/C.
 * Must not change live judgment, session state, speech, or parsers.
 */
internal object NaverTripStartDiag {

    private const val TAG = "NAVER_DIAG"
    private const val MAX = 280

    private val lock = Any()
    private var seq = 0
    private var notLiveGap = false

    fun nextSeq(): Int = synchronized(lock) { ++seq }

    fun onSessionClockReset() {
        synchronized(lock) { notLiveGap = false }
    }

    fun onLiveSeen() {
        synchronized(lock) { notLiveGap = false }
    }

    fun consumeNotLiveBegin(): Boolean = synchronized(lock) {
        if (notLiveGap) return false
        notLiveGap = true
        true
    }

    fun logClick(
        seq: Int,
        event: AccessibilityEvent,
        textsJoined: String,
        desc: String,
        startMatch: Boolean,
    ) {
        safe {
            val src = event.source
            try {
                val items = event.text?.joinToString(" | ") { it?.toString().orEmpty() }.orEmpty()
                line(
                    seq,
                    "CLICK",
                    "eventType=${typeName(event.eventType)}",
                    "packageName=${event.packageName}",
                    "className=${event.className}",
                    "viewIdResourceName=${src?.viewIdResourceName}",
                    "event.text=[$items]",
                    "event.textJoined=${clip(textsJoined)}",
                    "event.contentDescription=${clip(desc)}",
                    "source.className=${src?.className}",
                    "source.viewIdResourceName=${src?.viewIdResourceName}",
                    "source.text=${clip(src?.text?.toString())}",
                    "source.contentDescription=${clip(src?.contentDescription?.toString())}",
                    "startMatch=$startMatch",
                )
                if (startMatch) {
                    line(
                        seq,
                        "START_CLICK_MATCH",
                        "eventType=${typeName(event.eventType)}",
                        "packageName=${event.packageName}",
                        "className=${event.className}",
                        "viewIdResourceName=${src?.viewIdResourceName}",
                        "event.text=[$items]",
                        "event.textJoined=${clip(textsJoined)}",
                        "event.contentDescription=${clip(desc)}",
                        "source.className=${src?.className}",
                        "source.viewIdResourceName=${src?.viewIdResourceName}",
                        "source.text=${clip(src?.text?.toString())}",
                        "source.contentDescription=${clip(src?.contentDescription?.toString())}",
                    )
                }
            } finally {
                src?.recycle()
            }
        }
    }

    fun logNoRoot(seq: Int, event: AccessibilityEvent, ts: Long) {
        safe {
            line(
                seq,
                "TREE",
                "eventType=${typeName(event.eventType)}",
                "timestamp=$ts",
                "root=null",
            )
        }
    }

    fun logTree(
        seq: Int,
        event: AccessibilityEvent,
        ts: Long,
        root: AccessibilityNodeInfo,
        live: Boolean,
        hasStartButton: Boolean,
    ) {
        safe {
            line(
                seq,
                "TREE",
                "eventType=${typeName(event.eventType)}",
                "timestamp=$ts",
                "rootClass=${root.className}",
                "rootPackage=${root.packageName}",
                "rootWindowId=${root.windowId}",
                "liveLabel=$live",
                "hasStartButton=$hasStartButton",
            )
        }
    }

    fun logWindows(seq: Int, service: AccessibilityService) {
        safe {
            val windows = service.windows ?: emptyList()
            val parts = ArrayList<String>(windows.size)
            for (window in windows) {
                var wRoot: AccessibilityNodeInfo? = null
                try {
                    wRoot = window.root
                    val liveLabel = wRoot != null &&
                        NaverTransitDestinationParser.isLiveGuidance(collect(wRoot))
                    parts.add(
                        "id=${window.id}" +
                            " type=${windowTypeName(window.type)}" +
                            " pkg=${wRoot?.packageName}" +
                            " class=${wRoot?.className}" +
                            " active=${window.isActive}" +
                            " focused=${window.isFocused}" +
                            " liveLabel=$liveLabel",
                    )
                } catch (_: Exception) {
                    parts.add("id=${window.id} type=${windowTypeName(window.type)} error=1")
                } finally {
                    wRoot?.recycle()
                }
            }
            line(seq, "WINDOWS", "n=${windows.size}", "windows=[${parts.joinToString(" ; ")}]")
        }
    }

    fun logLiveState(
        seq: Int,
        from: Boolean,
        to: Boolean,
        event: AccessibilityEvent,
        ts: Long,
        root: AccessibilityNodeInfo,
        live: Boolean,
    ) {
        safe {
            line(
                seq,
                "LIVE_STATE",
                "from=$from",
                "to=$to",
                "eventType=${typeName(event.eventType)}",
                "timestamp=$ts",
                "rootClass=${root.className}",
                "rootPackage=${root.packageName}",
                "rootWindowId=${root.windowId}",
                "live=$live",
            )
        }
    }

    fun logNotLiveBegin(
        seq: Int,
        event: AccessibilityEvent,
        ts: Long,
        root: AccessibilityNodeInfo,
        live: Boolean,
    ) {
        safe {
            line(
                seq,
                "NOT_LIVE_BEGIN",
                "timestamp=$ts",
                "eventType=${typeName(event.eventType)}",
                "rootClass=${root.className}",
                "rootPackage=${root.packageName}",
                "rootWindowId=${root.windowId}",
                "live=$live",
            )
        }
    }

    fun logSessionEndTrigger(
        seq: Int,
        event: AccessibilityEvent,
        ts: Long,
        root: AccessibilityNodeInfo,
        live: Boolean,
    ) {
        safe {
            line(
                seq,
                "SESSION_END_TRIGGER",
                "timestamp=$ts",
                "eventType=${typeName(event.eventType)}",
                "rootClass=${root.className}",
                "rootPackage=${root.packageName}",
                "rootWindowId=${root.windowId}",
                "live=$live",
            )
        }
    }

    private fun line(seq: Int, event: String, vararg fields: String) {
        Log.i(TAG, buildString {
            append("[NAVER_DIAG] seq=").append(seq)
            append(" event=").append(event)
            fields.forEach { append(' ').append(it) }
        })
    }

    private fun safe(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
        }
    }

    private fun clip(raw: String?): String {
        val text = raw.orEmpty()
        if (text.length <= MAX) return text
        return text.substring(0, MAX) + "…"
    }

    private fun typeName(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "TYPE_VIEW_CLICKED"
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "TYPE_WINDOW_STATE_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "TYPE_WINDOW_CONTENT_CHANGED"
        else -> "TYPE_$type"
    }

    private fun windowTypeName(type: Int): String = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "APPLICATION"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "INPUT_METHOD"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "SYSTEM"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "ACCESSIBILITY_OVERLAY"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "SPLIT_SCREEN_DIVIDER"
        else -> "TYPE_$type"
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
}
