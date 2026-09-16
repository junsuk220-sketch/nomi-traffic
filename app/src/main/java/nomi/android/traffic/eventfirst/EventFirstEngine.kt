package nomi.android.traffic.eventfirst

import android.content.Context
import android.util.Log
import nomi.android.traffic.NaverMapNotification
import nomi.android.traffic.NavigationEventVoice
import java.io.File

/**
 * The Event-First path, end to end:
 *
 * 302 notification → [NaverEventParser] → [NaverTransitEvent] → [EventFirstJudge]
 * → [EventFirstDecision] → existing NavigationEventSpeech → existing
 * NavigationEventSpeaker.
 *
 * This object is the Android edge: it owns the app's [EventFirstPipeline],
 * writes the decision log, and hands a SPEAK line to the existing speaker. The
 * judgment itself stays pure inside the pipeline.
 *
 * Speech is gated by [EventFirstFeature]. While it is off this runs as a shadow:
 * it decides and logs, and the existing Journey path remains the single voice.
 */
internal object EventFirstEngine {

    private val lock = Any()
    private val pipeline = EventFirstPipeline()

    fun attach(filesDir: File) {
        EventFirstLog.attach(filesDir)
        EventFirstFeature.attach(filesDir)
    }

    /**
     * @return true when Event-First owns speech for this notification, so the
     * caller must not let the legacy path judge it too. False while
     * [EventFirstFeature.speechEnabled] is off — then this only decides and logs.
     */
    fun onTransitNotification(
        context: Context,
        title: String?,
        text: String?,
        nowMs: Long,
    ): Boolean {
        val owns = EventFirstFeature.speechEnabled
        val step = synchronized(lock) { pipeline.accept(title, text, nowMs) }
        val line = EventFirstLog.encode(
            nowMs = nowMs,
            title = title,
            text = text,
            event = step.event,
            state = step.state,
            decision = step.decision,
            spoken = step.sentence,
        )
        EventFirstLog.append(line, nowMs)
        Log.i(NaverMapNotification.TAG, "[EVENT_FIRST] $line")
        val sentence = step.sentence
        if (sentence != null && owns) {
            NavigationEventVoice.speakNotice(context, sentence)
        }
        return owns
    }

    fun resetForTest() {
        synchronized(lock) { pipeline.reset() }
    }
}
