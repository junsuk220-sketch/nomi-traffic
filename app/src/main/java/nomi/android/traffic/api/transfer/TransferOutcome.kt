package nomi.android.traffic.api.transfer

import nomi.android.traffic.speech.SpeechRequest

data class TransferOutcome(
    val request: SpeechRequest? = null,
    val silence: TransferSilence? = null,
)

enum class TransferSilence {
    INACTIVE,
    ARMED,
    WAIT_ETA,
    NEAR_ARMED,
    NEAR_WAIT,
    NEAR_ALREADY,
    NOT_SUBWAY,
    ALREADY,
    NO_TEXT,
    RESET,
}
