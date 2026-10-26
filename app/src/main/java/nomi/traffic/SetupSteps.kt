package nomi.traffic

import android.content.Context
import nomi.android.traffic.NaverNotificationAccess

enum class SetupStep {
    A11Y,
    NAVER_NOTIFY,
    GOOGLE_MAPS_NOTIFY,
    NAVER_MAP_NOTIFY,
}

object SetupSteps {

    /** Hardest first: attention is highest at the start. */
    val all = listOf(
        SetupStep.A11Y,
        SetupStep.NAVER_NOTIFY,
        SetupStep.GOOGLE_MAPS_NOTIFY,
        SetupStep.NAVER_MAP_NOTIFY,
    )

    fun firstPending(context: Context): SetupStep? =
        SetupGate.firstPending(all.associateWith { isGranted(context, it) }, A11yConsent.state(context))

    /**
     * Steps shown to this user. A map-app step that is not needed (not installed, or on) is not counted,
     * nor the accessibility step without consent.
     */
    fun visible(context: Context): List<SetupStep> = all.filter { step ->
        when {
            step == SetupStep.A11Y -> A11yConsent.state(context) == A11yConsentState.AGREED
            mapPackage(step) != null -> !isGranted(context, step)
            else -> true
        }
    }

    fun mapPackage(step: SetupStep): String? = when (step) {
        SetupStep.GOOGLE_MAPS_NOTIFY -> MapAppNotifyAccess.GOOGLE_MAPS
        SetupStep.NAVER_MAP_NOTIFY -> MapAppNotifyAccess.NAVER_MAP
        else -> null
    }

    fun isGranted(context: Context, step: SetupStep): Boolean = when (step) {
        SetupStep.NAVER_NOTIFY -> NaverNotificationAccess.isEnabled(context)
        SetupStep.A11Y -> A11yNeeds.allOn(context)
        SetupStep.GOOGLE_MAPS_NOTIFY, SetupStep.NAVER_MAP_NOTIFY -> {
            val pkg = mapPackage(step)!!
            mapNotifyDone(
                installed = MapAppNotifyAccess.isInstalled(context, pkg),
                allowed = MapAppNotifyAccess.isAllowed(context, pkg),
                confirmed = MapAppNotifyAccess.isConfirmed(context, pkg),
                onboardingDone = SetupMemory.isOnboardingDone(context),
            )
        }
    }

    /**
     * [allowed] is the map app's real switch when Android can read it (13+).
     * Otherwise the step is asked only during first onboarding; [confirmed] is set
     * by the user's 켰어요 or by a notification from that app arriving.
     */
    fun mapNotifyDone(
        installed: Boolean,
        allowed: Boolean?,
        confirmed: Boolean,
        onboardingDone: Boolean,
    ): Boolean = when {
        !installed -> true
        allowed != null -> allowed
        else -> confirmed || onboardingDone
    }
}
