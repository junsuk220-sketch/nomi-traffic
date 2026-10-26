package nomi.traffic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapNotifyDoneTest {

    @Test
    fun `readable switch on skips the step`() {
        assertTrue(SetupSteps.mapNotifyDone(installed = true, allowed = true, confirmed = false, onboardingDone = false))
    }

    @Test
    fun `readable switch off shows the step even after onboarding`() {
        assertFalse(SetupSteps.mapNotifyDone(installed = true, allowed = false, confirmed = true, onboardingDone = true))
    }

    @Test
    fun `unreadable first onboarding asks until confirmed`() {
        assertFalse(SetupSteps.mapNotifyDone(installed = true, allowed = null, confirmed = false, onboardingDone = false))
        assertTrue(SetupSteps.mapNotifyDone(installed = true, allowed = null, confirmed = true, onboardingDone = false))
    }

    @Test
    fun `unreadable after onboarding is never forced again`() {
        assertTrue(SetupSteps.mapNotifyDone(installed = true, allowed = null, confirmed = false, onboardingDone = true))
    }

    @Test
    fun `not installed has no step`() {
        assertTrue(SetupSteps.mapNotifyDone(installed = false, allowed = false, confirmed = false, onboardingDone = false))
    }
}
