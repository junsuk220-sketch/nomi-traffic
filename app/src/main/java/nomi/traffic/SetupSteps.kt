package nomi.traffic

import android.content.Context
import nomi.android.traffic.AccessibilityOffAlert
import nomi.android.traffic.NaverNotificationAccess

enum class SetupStep {
    NAVER_NOTIFY,
    A11Y,
}

object SetupSteps {

    val all = listOf(SetupStep.NAVER_NOTIFY, SetupStep.A11Y)

    fun firstPending(context: Context): SetupStep? =
        SetupGate.firstPending(all.associateWith { isGranted(context, it) })

    fun index(step: SetupStep): Int = all.indexOf(step)

    fun isGranted(context: Context, step: SetupStep): Boolean = when (step) {
        SetupStep.NAVER_NOTIFY -> NaverNotificationAccess.isEnabled(context)
        SetupStep.A11Y -> !AccessibilityOffAlert.isOff(context)
    }
}
