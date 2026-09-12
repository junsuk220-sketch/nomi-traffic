package nomi.traffic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunGuideTest {

    @Test
    fun `plays only on the first home after onboarding`() {
        assertTrue(FirstRunGuide.shouldPlay(onboardingWasAlreadyDone = false, voiceAlreadyPlayed = false))
    }

    @Test
    fun `does not play again after the voice was stored`() {
        assertFalse(FirstRunGuide.shouldPlay(onboardingWasAlreadyDone = true, voiceAlreadyPlayed = true))
        assertFalse(FirstRunGuide.shouldPlay(onboardingWasAlreadyDone = false, voiceAlreadyPlayed = true))
    }

    @Test
    fun `does not play on a later home visit`() {
        assertFalse(FirstRunGuide.shouldPlay(onboardingWasAlreadyDone = true, voiceAlreadyPlayed = false))
    }
}
