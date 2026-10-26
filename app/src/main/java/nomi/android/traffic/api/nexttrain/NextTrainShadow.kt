package nomi.android.traffic.api.nexttrain

import nomi.android.traffic.NavigationEventVoice
import nomi.android.traffic.observe.TransitRow

/** Host for [NextTrain]. Speaks only that one sentence. */
object NextTrainShadow {

    fun onPoll(rows: List<TransitRow>): NextTrainOutcome {
        val outcome = NextTrain.onPoll(rows)
        NextTrainDebug.emit(outcome.note)
        val text = outcome.text?.trim().orEmpty()
        if (text.isNotEmpty()) {
            NavigationEventVoice.playNextTrain(text)
            NextTrainDebug.emit("speak_played")
        }
        return outcome
    }

    fun reset() {
        NextTrain.reset()
    }
}
