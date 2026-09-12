package nomi.traffic

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

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

    fun openSettings(activity: Activity, service: Class<*>): Boolean {
        val component = ComponentName(activity, service)
        val flattened = component.flattenToString()
        return openDetails(activity, flattened) || openList(activity)
    }

    private fun openDetails(activity: Activity, flattened: String): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
        details.putExtra(Intent.EXTRA_COMPONENT_NAME, flattened)
        return startSafely(activity, details)
    }

    private fun startSafely(activity: Activity, intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.i(TAG, "open failed ${intent.action}: ${e.message}")
        false
    }
}
