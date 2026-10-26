package nomi.traffic

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * The service's own switch screen (ACCESSIBILITY_DETAILS_SETTINGS) needs
 * OPEN_ACCESSIBILITY_DETAILS_SETTINGS, which only the system holds: apps open the list.
 */
object AccessibilityAccess {

    private const val TAG = "GyolimA11y"

    fun isEnabled(context: Context, service: Class<*>): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val me = ComponentName(context, service)
        return enabled.contains(me.flattenToString()) ||
            enabled.contains(me.flattenToShortString())
    }

    fun openList(activity: Activity): Boolean =
        startSafely(activity, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    private fun startSafely(activity: Activity, intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.i(TAG, "open failed ${intent.action}: ${e.message}")
        false
    }
}
