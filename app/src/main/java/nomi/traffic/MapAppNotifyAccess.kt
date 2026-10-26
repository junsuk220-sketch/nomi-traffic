package nomi.traffic

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * The map app's own notification switch (Google Maps, Naver Map).
 * Android 13+ exposes that switch as a permission; older Android does not, so
 * there the user confirms it in onboarding.
 */
object MapAppNotifyAccess {

    const val GOOGLE_MAPS = "com.google.android.apps.maps"
    const val NAVER_MAP = "com.nhn.android.nmap"

    private const val PREFS = "setup"

    fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Android 13+: the app's 알림 허용 switch is its POST_NOTIFICATIONS grant, which
     * any app may read. Older Android cannot tell, so null.
     */
    fun isAllowed(context: Context, pkg: String): Boolean? {
        if (Build.VERSION.SDK_INT < 33) return null
        return try {
            context.packageManager.checkPermission(Manifest.permission.POST_NOTIFICATIONS, pkg) ==
                PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            null
        }
    }

    fun isConfirmed(context: Context, pkg: String): Boolean =
        prefs(context).getBoolean(key(pkg), false)

    fun markConfirmed(context: Context, pkg: String) {
        prefs(context).edit().putBoolean(key(pkg), true).apply()
    }

    /** Opens that app's notification page, where the 알림 허용 switch is. */
    fun openSettings(activity: Activity, pkg: String): Boolean {
        val notify = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
            .putExtra("app_package", pkg)
        if (startSafely(activity, notify)) return true
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        return startSafely(activity, details)
    }

    private fun key(pkg: String) = "map_notify_confirmed_$pkg"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun startSafely(activity: Activity, intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }
}
