package nomi.android.traffic.setupnudge

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import nomi.android.traffic.NaverNotificationAccess
import nomi.android.traffic.NavigationEventVoice
import nomi.android.traffic.api.google.read.GoogleCellLog
import nomi.traffic.A11yConsent
import nomi.traffic.A11yItem
import nomi.traffic.A11yNeeds
import nomi.traffic.MainActivity
import nomi.traffic.R

/**
 * A map app is guiding but a switch this app needs is off: speak once and post
 * a notice that opens the app, where the matching setup guide is shown.
 */
object SetupNudgeShadow {

    private const val CHANNEL = "setup_nudge"
    private const val NOTICE_ID = 42

    private val nudge = SetupNudge()
    private val log = GoogleCellLog("SETUP_NUDGE", "nomi-setup-nudge.log")

    /** Google Maps transit guidance notification arrived (listener is on). Silent after 동의하지 않습니다. */
    fun onGoogleGuidance(context: Context) {
        if (A11yConsent.isDeclined(context)) return
        check(context, SetupGap.GOOGLE_A11Y, !A11yNeeds.isOn(context, A11yItem.GOOGLE))
    }

    /** Naver Map transit guidance notification arrived (listener is on). Silent after 동의하지 않습니다. */
    fun onNaverGuidance(context: Context) {
        if (A11yConsent.isDeclined(context)) return
        check(context, SetupGap.NAVER_A11Y, !A11yNeeds.isOn(context, A11yItem.NAVER))
    }

    /** A map app shows live guidance on screen (that app's accessibility is on). */
    fun onLiveScreen(context: Context) =
        check(context, SetupGap.LISTENER, !NaverNotificationAccess.isEnabled(context))

    fun cancelNotice(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(NOTICE_ID)
        } catch (_: Throwable) {
        }
    }

    private fun check(context: Context, gap: SetupGap, off: Boolean) {
        val speak = synchronized(nudge) {
            nudge.shouldSpeak(gap, off, System.currentTimeMillis())
        }
        if (!speak) return
        log.attach(context.applicationContext.filesDir)
        val posted = postNotice(context.applicationContext, gap)
        log.emit("speak gap=$gap notice=$posted")
        NavigationEventVoice.speakNotice(context, SetupNudge.speech(gap, posted))
    }

    private fun postNotice(context: Context, gap: SetupGap): Boolean = try {
        if (!canPost(context)) {
            false
        } else {
            ensureChannel(context)
            val open = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val tap = PendingIntent.getActivity(
                context,
                0,
                open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notice = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify_setup)
                .setContentTitle(context.getString(R.string.setup_nudge_title))
                .setContentText(context.getString(textOf(gap)))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            NotificationManagerCompat.from(context).notify(NOTICE_ID, notice)
            true
        }
    } catch (_: Throwable) {
        false
    }

    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.setup_nudge_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    private fun textOf(gap: SetupGap): Int = when (gap) {
        SetupGap.GOOGLE_A11Y -> R.string.setup_nudge_google
        SetupGap.NAVER_A11Y -> R.string.setup_nudge_naver
        SetupGap.LISTENER -> R.string.setup_nudge_listener
    }
}
