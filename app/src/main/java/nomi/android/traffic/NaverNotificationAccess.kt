package nomi.android.traffic

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * Opens system "notification access" settings. Does not grant anything itself.
 */
object NaverNotificationAccess {

    fun isEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ).orEmpty()
        val me = ComponentName(context, NaverNavigationNotificationListenerService::class.java)
        return enabled.contains(me.flattenToString()) ||
            enabled.contains(me.flattenToShortString())
    }

    fun openSettings(activity: Activity): Boolean {
        val opened = startSafely(activity, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        if (opened && Build.VERSION.SDK_INT >= 24) {
            NotificationListenerService.requestRebind(
                ComponentName(activity, NaverNavigationNotificationListenerService::class.java),
            )
        }
        return opened
    }

    private fun startSafely(activity: Activity, intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }
}
