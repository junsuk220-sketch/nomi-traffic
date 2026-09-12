package nomi.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetupGateTest {

    @Test
    fun `first launch shows the first missing step`() {
        val granted = mapOf(
            SetupStep.NAVER_NOTIFY to false,
            SetupStep.A11Y to false,
        )
        assertEquals(SetupStep.NAVER_NOTIFY, SetupGate.firstPending(granted))
        assertEquals(SetupView.ONBOARDING, SetupGate.view(granted, onboardingDone = false))
    }

    @Test
    fun `first setup still uses the accessibility onboarding screen`() {
        val granted = mapOf(
            SetupStep.NAVER_NOTIFY to true,
            SetupStep.A11Y to false,
        )
        assertEquals(SetupStep.A11Y, SetupGate.firstPending(granted))
        assertEquals(SetupView.ONBOARDING, SetupGate.view(granted, onboardingDone = false))
    }

    @Test
    fun `later visit with accessibility off shows a dialog on home`() {
        val granted = mapOf(
            SetupStep.NAVER_NOTIFY to true,
            SetupStep.A11Y to false,
        )
        assertEquals(SetupView.A11Y_DIALOG, SetupGate.view(granted, onboardingDone = true))
    }

    @Test
    fun `all granted skips onboarding`() {
        val granted = mapOf(
            SetupStep.NAVER_NOTIFY to true,
            SetupStep.A11Y to true,
        )
        assertNull(SetupGate.firstPending(granted))
        assertEquals(SetupView.HOME, SetupGate.view(granted, onboardingDone = true))
    }
}
