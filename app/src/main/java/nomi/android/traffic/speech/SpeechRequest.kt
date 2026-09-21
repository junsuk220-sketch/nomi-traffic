package nomi.android.traffic.speech

/**
 * A line to speak. [SpeechSink] does not judge this — it only plays [text].
 */
data class SpeechRequest(
    val text: String,
)
