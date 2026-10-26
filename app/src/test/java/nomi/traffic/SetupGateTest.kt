package nomi.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetupGateTest {

    private fun granted(
        notify: Boolean = true,
        a11y: Boolean = true,
        google: Boolean = true,
        naver: Boolean = true,
    ) = mapOf(
        SetupStep.NAVER_NOTIFY to notify,
        SetupStep.A11Y to a11y,
        SetupStep.GOOGLE_MAPS_NOTIFY to google,
        SetupStep.NAVER_MAP_NOTIFY to naver,
    )

    @Test
    fun `first launch starts with accessibility, the hardest step`() {
        val g = granted(notify = false, a11y = false, google = false, naver = false)
        assertEquals(SetupStep.A11Y, SetupGate.firstPending(g))
        assertEquals(SetupView.ONBOARDING, SetupGate.view(g, onboardingDone = false))
    }

    @Test
    fun `notification access comes after accessibility`() {
        val g = granted(notify = false, google = false, naver = false)
        assertEquals(SetupStep.NAVER_NOTIFY, SetupGate.firstPending(g))
        assertEquals(SetupView.ONBOARDING, SetupGate.view(g, onboardingDone = false))
    }

    @Test
    fun `later visit with accessibility off shows a dialog on home`() {
        assertEquals(SetupView.A11Y_DIALOG, SetupGate.view(granted(a11y = false), onboardingDone = true))
    }

    @Test
    fun `google maps notify comes after notification access`() {
        val g = granted(google = false, naver = false)
        assertEquals(SetupStep.GOOGLE_MAPS_NOTIFY, SetupGate.firstPending(g))
        assertEquals(SetupView.ONBOARDING, SetupGate.view(g, onboardingDone = false))
    }

    @Test
    fun `naver map notify is the last step`() {
        val g = granted(naver = false)
        assertEquals(SetupStep.NAVER_MAP_NOTIFY, SetupGate.firstPending(g))
    }

    @Test
    fun `existing user sees only the new map steps`() {
        assertEquals(SetupView.ONBOARDING, SetupGate.view(granted(google = false), onboardingDone = true))
    }

    @Test
    fun `all granted skips onboarding`() {
        assertNull(SetupGate.firstPending(granted()))
        assertEquals(SetupView.HOME, SetupGate.view(granted(), onboardingDone = true))
    }

    @Test
    fun `first launch asks consent before the accessibility step`() {
        val g = granted(notify = false, a11y = false, google = false, naver = false)
        assertEquals(
            SetupView.A11Y_CONSENT,
            SetupGate.view(g, onboardingDone = false, consent = A11yConsentState.UNSET),
        )
    }

    @Test
    fun `existing user with accessibility on is asked consent once`() {
        assertEquals(
            SetupView.A11Y_CONSENT,
            SetupGate.view(granted(), onboardingDone = true, consent = A11yConsentState.UNSET),
        )
    }

    @Test
    fun `later visit with accessibility off and no answer asks consent, not settings`() {
        assertEquals(
            SetupView.A11Y_CONSENT,
            SetupGate.view(granted(a11y = false), onboardingDone = true, consent = A11yConsentState.UNSET),
        )
    }

    @Test
    fun `agreeing leads to the accessibility guide`() {
        val g = granted(notify = false, a11y = false, google = false, naver = false)
        assertEquals(SetupStep.A11Y, SetupGate.firstPending(g, A11yConsentState.AGREED))
        assertEquals(
            SetupView.ONBOARDING,
            SetupGate.view(g, onboardingDone = false, consent = A11yConsentState.AGREED),
        )
    }

    @Test
    fun `declining skips the accessibility step to notification access`() {
        val g = granted(notify = false, a11y = false, google = false, naver = false)
        assertEquals(SetupStep.NAVER_NOTIFY, SetupGate.firstPending(g, A11yConsentState.DECLINED))
        assertEquals(
            SetupView.ONBOARDING,
            SetupGate.view(g, onboardingDone = false, consent = A11yConsentState.DECLINED),
        )
    }

    @Test
    fun `declined user reaches home with accessibility off and never sees the dialog`() {
        val g = granted(a11y = false)
        assertNull(SetupGate.firstPending(g, A11yConsentState.DECLINED))
        assertEquals(
            SetupView.HOME,
            SetupGate.view(g, onboardingDone = true, consent = A11yConsentState.DECLINED),
        )
        assertEquals(
            SetupView.HOME,
            SetupGate.view(g, onboardingDone = false, consent = A11yConsentState.DECLINED),
        )
    }
}
