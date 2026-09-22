package nomi.android.traffic.api.eta

import nomi.android.traffic.speech.SpeechRequest

data class EtaOutcome(
    val request: SpeechRequest? = null,
    val silence: EtaSilence? = null,
)

enum class EtaSilence {
    NO_VEHICLE,
    UNSETTLED,
    NOT_A_BAND,
    OUT_OF_BAND,
    ALREADY,
    COOLDOWN,
    NO_TEXT,
}
