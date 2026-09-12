package nomi.android.traffic

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/**
 * Google/Naver accessibility is off. The OS clears those services on app upgrade;
 * a third-party app cannot keep them on.
 */
object AccessibilityOffAlert {

    private const val LEFTOVER_NOTICE_ID = 41

    fun isOff(context: Context): Boolean =
        !isEnabled(context, GoogleTransitAccessibilityService::class.java) ||
            !isEnabled(context, NaverSubwayAccessibilityService::class.java)

    fun cancelLeftoverNotice(context: Context) {
        val manager = context.applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(LEFTOVER_NOTICE_ID)
    }

    private fun isEnabled(context: Context, service: Class<*>): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val me = ComponentName(context, service)
        return enabled.contains(me.flattenToString()) ||
            enabled.contains(me.flattenToShortString())
    }
}
