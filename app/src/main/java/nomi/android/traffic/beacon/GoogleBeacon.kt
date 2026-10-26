package nomi.android.traffic.beacon

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
import androidx.core.content.ContextCompat
import nomi.android.traffic.FieldLogs
import nomi.traffic.R
import java.io.File

/**
 * Google cell: pressing 시작 turns the Maps screen live; at that moment the
 * current location becomes the beacon point. One start per trip; stops when
 * Google's ongoing guidance notification goes away.
 */
object GoogleBeacon {

    private const val LOG_FILE = "nomi-google-beacon.log"
    private const val BUILD = "vc161-google-beacon-live-edge"
    private const val CHANNEL = "beacon_permission"
    private const val NOTICE_ID = 44
    private const val STALE_TRIP_MILLIS = 30 * 60_000L

    private var wasLive = false
    private var started = false
    private var lastLiveAt = 0L

    /** Called on every Google Maps screen read. */
    @Synchronized
    fun onScreen(context: Context, live: Boolean, nowMillis: Long) {
        if (started && nowMillis - lastLiveAt > STALE_TRIP_MILLIS) started = false
        val becameLive = live && !wasLive
        wasLive = live
        if (live) lastLiveAt = nowMillis
        if (!becameLive || started) return
        started = true
        start(context)
    }

    @Synchronized
    fun onTripEnded(context: Context) {
        wasLive = false
        if (!started) return
        started = false
        context.startService(Intent(context, BeaconService::class.java).setAction(BeaconService.ACTION_STOP))
        emit(context, "trip_end")
    }

    private fun start(context: Context) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            emit(context, "no_permission")
            askPermission(context)
            return
        }
        try {
            ContextCompat.startForegroundService(context, Intent(context, BeaconService::class.java))
            emit(context, "start live_edge")
        } catch (t: Throwable) {
            emit(context, "start_error ${t.javaClass.simpleName}")
        }
    }

    private fun askPermission(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && manager?.getNotificationChannel(CHANNEL) == null) {
                manager?.createNotificationChannel(
                    NotificationChannel(CHANNEL, "띠띠 위치 권한", NotificationManager.IMPORTANCE_HIGH),
                )
            }
            val tap = PendingIntent.getActivity(
                context,
                2,
                Intent(context, BeaconPermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notice = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify_setup)
                .setContentTitle("방향 안내를 들으려면 위치 권한이 필요합니다")
                .setContentText("알림을 눌러 허용해 주세요")
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            NotificationManagerCompat.from(context).notify(NOTICE_ID, notice)
            emit(context, "permission_notice")
        } catch (t: Throwable) {
            emit(context, "permission_notice_error ${t.javaClass.simpleName}")
        }
    }

    private fun emit(context: Context, message: String) {
        val line = "[GOOGLE_BEACON] $message build=$BUILD"
        try {
            android.util.Log.i("GOOGLE_BEACON", line)
        } catch (_: Throwable) {
        }
        if (!FieldLogs.enabled) return
        try {
            File(context.filesDir, LOG_FILE).appendText("${System.currentTimeMillis()} $line\n")
        } catch (_: Throwable) {
        }
    }
}
