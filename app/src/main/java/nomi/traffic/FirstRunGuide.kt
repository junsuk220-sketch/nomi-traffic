package nomi.traffic

/**
 * First home after onboarding: play the welcome voice once.
 * Does not decide TTS, permissions, or route cards.
 */
object FirstRunGuide {

    fun shouldPlay(onboardingWasAlreadyDone: Boolean, voiceAlreadyPlayed: Boolean): Boolean =
        !onboardingWasAlreadyDone && !voiceAlreadyPlayed
}
