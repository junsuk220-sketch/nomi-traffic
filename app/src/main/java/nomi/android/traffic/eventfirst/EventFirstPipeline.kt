package nomi.android.traffic.eventfirst

/**
 * dedup → parse → judge → sentence, with no Android and no I/O. One instance
 * per running trip; [EventFirstEngine] owns the app's instance and adds
 * logging plus the actual speaker call.
 *
 * Keeping this separate is what lets a whole day of 302 notifications be
 * replayed through the real judge in a unit test.
 */
internal class EventFirstPipeline(
    private val dedup: EventFirstDedup = EventFirstDedup(),
) {

    var state: EventFirstState = EventFirstState()
        private set

    data class Step(
        /** Null when the notification was a duplicate or matched no pattern. */
        val event: NaverTransitEvent?,
        val decision: EventFirstDecision,
        val state: EventFirstState,
        /** The line to speak, or null for silence (and for SPEAK with no sentence). */
        val sentence: String?,
    )

    fun accept(title: String?, text: String?, nowMs: Long): Step {
        if (dedup.isDuplicate(title, text, nowMs)) {
            return Step(null, EventFirstDecision.silence(Reason.SILENCE_DUPLICATE), state, null)
        }
        val event = NaverEventParser.parse(title, text, nowMs)
            ?: return Step(null, EventFirstDecision.silence(Reason.SILENCE_UNPARSED), state, null)
        val judged = EventFirstJudge.judge(event, state, nowMs)
        state = judged.state
        val sentence = if (judged.decision.speak) {
            EventFirstSpeech.line(event, judged.decision)
        } else {
            null
        }
        return Step(event, judged.decision, judged.state, sentence)
    }

    fun reset() {
        state = EventFirstState()
        dedup.reset()
    }
}

private typealias Reason = EventFirstDecision.Reason
