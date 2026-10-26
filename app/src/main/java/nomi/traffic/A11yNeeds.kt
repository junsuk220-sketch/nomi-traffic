package nomi.traffic

import android.content.Context
import nomi.android.traffic.GoogleTransitAccessibilityService
import nomi.android.traffic.NaverSubwayAccessibilityService

enum class A11yItem { GOOGLE, NAVER }

/**
 * Which accessibility switches this user must turn on: only the ones for the
 * map apps on the phone. With neither map app installed, both are asked.
 */
object A11yNeeds {

    fun needed(googleInstalled: Boolean, naverInstalled: Boolean): List<A11yItem> = when {
        googleInstalled && !naverInstalled -> listOf(A11yItem.GOOGLE)
        naverInstalled && !googleInstalled -> listOf(A11yItem.NAVER)
        else -> listOf(A11yItem.GOOGLE, A11yItem.NAVER)
    }

    fun needed(context: Context): List<A11yItem> = needed(
        MapAppNotifyAccess.isInstalled(context, MapAppNotifyAccess.GOOGLE_MAPS),
        MapAppNotifyAccess.isInstalled(context, MapAppNotifyAccess.NAVER_MAP),
    )

    /** Needed switches that are still off. Switches already on are not shown again. */
    fun off(needed: List<A11yItem>, isOn: (A11yItem) -> Boolean): List<A11yItem> =
        needed.filterNot(isOn)

    fun off(context: Context): List<A11yItem> = off(needed(context)) { isOn(context, it) }

    fun allOn(context: Context): Boolean = off(context).isEmpty()

    fun isOn(context: Context, item: A11yItem): Boolean =
        AccessibilityAccess.isEnabled(context, serviceOf(item))

    private fun serviceOf(item: A11yItem): Class<*> = when (item) {
        A11yItem.GOOGLE -> GoogleTransitAccessibilityService::class.java
        A11yItem.NAVER -> NaverSubwayAccessibilityService::class.java
    }
}
