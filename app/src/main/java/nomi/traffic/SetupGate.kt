package nomi.traffic

enum class SetupView {
    HOME,
    ONBOARDING,
    A11Y_DIALOG,
    A11Y_CONSENT,
}

object SetupGate {

    /** After DECLINED the accessibility step is skipped; it comes back only through consent. */
    fun firstPending(
        granted: Map<SetupStep, Boolean>,
        consent: A11yConsentState = A11yConsentState.AGREED,
    ): SetupStep? =
        SetupSteps.all.firstOrNull { step ->
            granted[step] != true && (step != SetupStep.A11Y || consent == A11yConsentState.AGREED)
        }

    fun view(
        granted: Map<SetupStep, Boolean>,
        onboardingDone: Boolean,
        consent: A11yConsentState = A11yConsentState.AGREED,
    ): SetupView {
        if (consent == A11yConsentState.UNSET) return SetupView.A11Y_CONSENT
        val pending = firstPending(granted, consent)
        if (pending == null) return SetupView.HOME
        if (pending == SetupStep.A11Y && onboardingDone) return SetupView.A11Y_DIALOG
        return SetupView.ONBOARDING
    }
}
