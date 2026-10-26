package nomi.android.traffic.beacon

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import nomi.android.traffic.FieldLogs
import nomi.traffic.R
import java.io.File
import java.util.Locale

/**
 * Test cell: beeps toward one fixed point while the phone stays in a pocket.
 * Foreground location service started from the test screen.
 */
class BeaconService : Service() {

    private var tone: BeaconTone? = null
    private val heading = WalkHeading()
    private var locations: LocationManager? = null

    @Volatile
    private var cue: BeaconCue? = null

    private val listener = LocationListener { onFix(it) }

    /** The point to guide toward was not known at start: take the next fix. */
    private var waitingForTarget = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val lat = intent?.getDoubleExtra(EXTRA_LAT, Double.NaN) ?: Double.NaN
        val lng = intent?.getDoubleExtra(EXTRA_LNG, Double.NaN) ?: Double.NaN
        try {
            goForeground()
        } catch (t: Throwable) {
            emit(this, "foreground_error ${t.javaClass.simpleName} ${t.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (tone == null) {
            tone = BeaconTone { cue }.also { it.start() }
        }
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            emit(this, "no_permission")
            status = "위치 권한이 없습니다"
            stopSelf()
            return START_NOT_STICKY
        }
        if (lat.isNaN() || lng.isNaN()) {
            waitingForTarget = true
            status = "위치 찾는 중…"
            emit(this, "start waiting_for_target")
            startFirstFix()
        } else {
            waitingForTarget = false
            target = GeoPoint(lat, lng)
            status = "시작: 걸으면 소리가 납니다"
            emit(this, "start target=${fmt(lat)},${fmt(lng)}")
            startLocation()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try {
            locations?.removeUpdates(listener)
        } catch (_: Throwable) {
        }
        tone?.stop()
        tone = null
        cue = null
        running = false
        status = "정지"
        emit(this, "stop")
        super.onDestroy()
    }

    private fun goForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager?.getNotificationChannel(CHANNEL) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL, "띠띠 방향 안내", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, nomi.traffic.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, BeaconService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notice = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify_setup)
            .setContentTitle("띠띠 방향 안내 중")
            .setContentText("출발한 곳 쪽에서 소리가 납니다")
            .setContentIntent(open)
            .addAction(0, "정지", stop)
            .setOngoing(true)
            .build()
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            ServiceCompat.startForeground(this, NOTICE_ID, notice, type)
            running = true
        }

    /** Takes a recent GPS fix at once if there is one; otherwise the first GPS fix sets the point. */
    @SuppressLint("MissingPermission")
    private fun startFirstFix() {
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val last = runCatching { manager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
        if (last != null && System.currentTimeMillis() - last.time <= RECENT_FIX_MILLIS) {
            setTarget(last, "last_gps")
        }
        startLocation()
    }

    private fun setTarget(location: Location, from: String) {
        waitingForTarget = false
        target = GeoPoint(location.latitude, location.longitude)
        status = "시작: 걸으면 소리가 납니다"
        emit(
            this,
            "target ${fmt(location.latitude)},${fmt(location.longitude)} acc=${location.accuracy} " +
                "provider=${location.provider} from=$from",
        )
    }

    private fun providers(manager: LocationManager): List<String> {
        val all = runCatching { manager.getProviders(true) }.getOrDefault(emptyList())
        return listOf(LocationManager.GPS_PROVIDER).filter { it in all }
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (locations != null) return
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locations = manager
        val used = providers(manager)
        if (used.isEmpty()) {
            emit(this, "location_error no_provider")
            status = "위치(GPS)가 꺼져 있습니다"
            return
        }
        for (provider in used) {
            try {
                manager.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
            } catch (t: Throwable) {
                emit(this, "location_error $provider ${t.javaClass.simpleName}")
            }
        }
        emit(this, "listening providers=$used")
    }

    private fun onFix(location: Location) {
        // Only a satellite fix may set or move the point. A coarse fix landing
        // first would slide the target along with the walker.
        if (location.provider != LocationManager.GPS_PROVIDER) return
        if (waitingForTarget) {
            setTarget(location, "live_gps")
            return
        }
        val goal = target ?: return
        val here = GeoPoint(location.latitude, location.longitude)
        val accuracy = if (location.hasAccuracy()) location.accuracy else null
        val speed = if (location.hasSpeed()) location.speed else null
        val bearing = if (location.hasBearing()) location.bearing else null
        val facing = heading.update(System.currentTimeMillis(), here, accuracy, speed, bearing)
        val next = BeaconMath.cue(here, goal, facing)
        cue = next
        val distance = BeaconMath.distanceMeters(here, goal)
        val toTarget = BeaconMath.bearingDeg(here, goal)
        val relative = facing?.let { BeaconMath.relativeDeg(toTarget, it) }
        status = when {
            next?.arrived == true -> "도착 (${distance.toInt()}m)"
            relative == null -> "멈춤: 걸어야 방향을 압니다 (${distance.toInt()}m)"
            else -> "${sideOf(relative)} ${relative.toInt()}° · ${distance.toInt()}m"
        }
        emit(
            this,
            "fix lat=${fmt(here.lat)} lng=${fmt(here.lng)} acc=$accuracy speed=$speed gpsBearing=$bearing " +
                "heading=${facing?.toInt()} src=${heading.source} toTarget=${toTarget.toInt()} " +
                "rel=${relative?.toInt()} dist=${distance.toInt()} " +
                "cue=${next?.let { "L=%.2f R=%.2f behind=${it.behind} arrived=${it.arrived} every=${it.intervalMillis}".format(Locale.US, it.left, it.right) } ?: "silent"}",
        )
    }

    companion object {
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val ACTION_STOP = "nomi.beacon.STOP"
        private const val CHANNEL = "beacon_test"
        private const val NOTICE_ID = 43
        private const val LOG_FILE = "nomi-beacon.log"
        private const val BUILD = "vc164-beacon-gps-only"
        private const val RECENT_FIX_MILLIS = 120_000L

        @Volatile
        var status: String = "정지"
            private set

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        private var target: GeoPoint? = null

        fun sideOf(relative: Double): String = when {
            BeaconMath.isBehind(relative) -> "뒤"
            relative > 20 -> "오른쪽"
            relative < -20 -> "왼쪽"
            else -> "앞"
        }

        private fun fmt(v: Double) = "%.6f".format(Locale.US, v)

        private fun emit(context: Context, message: String) {
            val line = "[BEACON] $message build=$BUILD"
            try {
                Log.i("BEACON", line)
            } catch (_: Throwable) {
            }
            if (!FieldLogs.enabled) return
            try {
                File(context.filesDir, LOG_FILE).appendText("${System.currentTimeMillis()} $line\n")
            } catch (_: Throwable) {
            }
        }
    }
}
