package nomi.traffic

enum class SetupView {
    HOME,
    ONBOARDING,
    A11Y_DIALOG,
}

object SetupGate {

    fun firstPending(granted: Map<SetupStep, Boolean>): SetupStep? =
        SetupSteps.all.firstOrNull { granted[it] != true }

    fun view(granted: Map<SetupStep, Boolean>, onboardingDone: Boolean): SetupView {
        val pending = firstPending(granted)
        if (pending == null) return SetupView.HOME
        if (pending == SetupStep.A11Y && onboardingDone) return SetupView.A11Y_DIALOG
        return SetupView.ONBOARDING
    }
}
