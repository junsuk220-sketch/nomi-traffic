package nomi.android.traffic.eventfirst

/**
 * What [EventFirstJudge] decided about one event, and why. Every silence has a
 * named [reason] so a quiet ride can be read back off the log.
 */
internal data class EventFirstDecision(
    val speak: Boolean,
    val reason: Reason,
    val speechType: SpeechType? = null,
) {

    enum class Reason {
        SPEAK_BRIEF,
        SPEAK_STAGE_10,
        SPEAK_STAGE_5,
        SPEAK_STAGE_2,
        SPEAK_SOON,
        SPEAK_BOARD,
        SPEAK_ALIGHT_SOON,
        SPEAK_ALIGHT_NOW,
        SPEAK_TRANSFER,

        /** `길안내를 시작합니다.` is not repeated back; the next wait event briefs. */
        SILENCE_GUIDANCE_START,

        /** `길안내를 종료합니다.` drops the whole Event-First state. */
        SILENCE_GUIDANCE_END,

        /** Aboard. Wait cues for this scope are over. */
        SILENCE_RIDING,

        /** This rung of the ladder was already spoken in this scope. */
        SILENCE_ALREADY_SPOKEN,

        /** Same vehicle, inside the 120s window. 곧 never lands here. */
        SILENCE_COOLDOWN,

        /** The jump is not explainable by the clock; hold until it repeats. */
        SILENCE_PENDING,

        /** ETA is not 10 / 5 / 2 / 곧 — or is a clock time we do not convert. */
        SILENCE_NOT_A_STAGE,

        /** Two 방면 on one board. Guessing a direction is not allowed. */
        SILENCE_AMBIGUOUS_DIRECTION,

        /** `다음 역에서 하차` names no station, so there is nothing to announce. */
        SILENCE_NO_STATION,

        /** Board with no readable row. */
        SILENCE_NO_ARRIVALS,

        /** Same (title | text) again within the dedup window. */
        SILENCE_DUPLICATE,

        /** No pattern matched — the event is not one of the nine kinds. */
        SILENCE_UNPARSED,
    }

    enum class SpeechType {
        BUS_BRIEF,
        BUS_STAGE,
        TRAIN_BRIEF,
        TRAIN_STAGE,
        BOARD_TRAIN,
        ALIGHT_SOON,
        ALIGHT_NOW,
        /** 하차 was already announced for this station; say only the transfer. */
        TRANSFER_ONLY,
        /** 하차 + 환승 in one go, so a single station never gets two cues. */
        TRANSFER_WITH_ALIGHT,
    }

    companion object {
        fun silence(reason: Reason) = EventFirstDecision(speak = false, reason = reason)

        fun speak(reason: Reason, speechType: SpeechType) =
            EventFirstDecision(speak = true, reason = reason, speechType = speechType)
    }
}
