package nomi.android.traffic.api.briefing

import nomi.android.traffic.speech.SpeechRequest

data class StartBriefingOutcome(
    val request: SpeechRequest? = null,
    val silence: StartBriefingSilence? = null,
)

enum class StartBriefingSilence {
    INACTIVE,
    NO_LINE,
    NO_KIND,
    ALREADY,
    NO_READING,
    NO_SCOPE_ROW,
    NO_TEXT,
}
