package nomi.android.traffic

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
import nomi.product.nav.NavigationEventSource
import nomi.product.nav.RouteCard

/**
 * Launches Naver Maps or Google Maps for a recorded destination name.
 */
object RouteCardMapLaunch {

    private const val TAG = "GyolimMap"

    fun open(activity: Activity, card: RouteCard): Boolean {
        val link = RouteCardMapLinks.of(card) ?: return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.setPackage(
            when (card.provider) {
                NavigationEventSource.NAVER -> NaverMapNotification.PACKAGE
                NavigationEventSource.GOOGLE -> GoogleMapsTransit.PACKAGE
            },
        )
        return startSafely(activity, intent)
    }

    private fun startSafely(activity: Activity, intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.i(TAG, "open failed ${intent.data}: ${e.message}")
        false
    }
}
