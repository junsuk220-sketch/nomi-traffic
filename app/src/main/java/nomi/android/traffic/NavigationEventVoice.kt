package nomi.android.traffic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.util.Log
import nomi.product.nav.NavigationEvent
import nomi.product.nav.NavigationEventSource

/**
 * Shared TTS entry. Naver wait-sheet state stays Naver-only.
 * Google events never touch [NaverBusWaitTracker].
 *
 * While the screen is off, Naver a11y sheets are ignored so 302 notifications
 * can drive 10/5/2/곧. Opening the map must not be required to hear ETA.
 */
object NavigationEventVoice {

    private val lock = Any()
    private val gate = NavigationEventSpeechGate()
    private val naverBusWait = NaverBusWaitTracker()
    private var speaker: NavigationEventSpeaker? = null
    private var appContext: Context? = null
    private var screenWatchRegistered = false
    @Volatile
    private var screenOff = false

    fun prepare(context: Context) {
        appContext = context.applicationContext
        speaker(context)
        ensureScreenWatch(context.applicationContext)
        syncScreenState(context.applicationContext)
    }

    fun offer(context: Context, event: NavigationEvent) {
        when (event.source) {
            NavigationEventSource.NAVER,
            NavigationEventSource.GOOGLE,
            -> speaker(context).offer(event)
        }
    }

    fun speakNotice(context: Context, text: String) {
        try {
            speaker(context).speakNotice(text)
        } catch (_: Exception) {
        }
    }

    fun onNaverWaitSheetEmpty() {
        synchronized(lock) { naverBusWait.onSheet(emptyList()) }
    }

    /** Sheet left the screen. Keep pin — 302 still drives 10/5/2/곧. */
    fun releaseNaverWaitSheet() {
        synchronized(lock) { naverBusWait.releaseSheetOwnership() }
    }

    /** Guidance ended. Clear pin so the next trip must seed again. */
    fun leaveNaverWaitSheet() {
        synchronized(lock) { naverBusWait.leave() }
        NaverNextTrainNotice.reset()
        NaverNearBoardNotice.reset()
        resetNaverTripStart()
        resetNaverPrepareAlight()
        Log.i(NaverMapNotification.TAG, "[NAVER_PIN] cleared — guidance ended")
    }

    /** Pin itinerary line from preview/live trip-start before bus rows can speak. */
    fun pinNaverFromTripEvent(event: NavigationEvent) {
        val line = event.busInfo?.arrivals?.firstOrNull()?.line?.trim().orEmpty()
        if (line.isEmpty()) return
        synchronized(lock) {
            when (event.rawText) {
                NaverMapsTransit.KIND_SUBWAY -> {
                    naverBusWait.pinSubwayLine(line)
                    Log.i(NaverMapNotification.TAG, "[NAVER_PIN] subway=$line")
                }
                else -> {
                    naverBusWait.pinBusLine(line)
                    Log.i(NaverMapNotification.TAG, "[NAVER_PIN] bus seed=$line")
                }
            }
        }
    }

    fun pinNaverFromTripCache() {
        val pending = NaverTripStartCache.peek() ?: return
        pinNaverFromTripEvent(pending)
    }

    /** Accessibility bus rows must not own the tracker while the display is dark. */
    fun ignoreNaverSheetBus(context: Context): Boolean {
        syncScreenState(context)
        return screenOff
    }

    fun onScreenOff() {
        screenOff = true
        synchronized(lock) {
            naverBusWait.releaseSheetOwnership()
        }
        Log.i(NaverMapNotification.TAG, "[NAV_SCREEN] off -> naver sheet ownership released")
    }

    fun onScreenOn() {
        screenOff = false
        Log.i(NaverMapNotification.TAG, "[NAV_SCREEN] on -> naver sheet bus allowed")
    }

    fun resetGoogleTripStart() {
        synchronized(lock) {
            gate.resetGoogleTripStart()
        }
    }

    fun resetNaverTripStart() {
        synchronized(lock) {
            gate.resetNaverTripStart()
        }
    }

    fun resetNaverTransferCues() {
        synchronized(lock) {
            gate.resetNaverTransferCues()
        }
        NaverNearBoardNotice.reset()
    }

    fun resetNaverPrepareAlight() {
        synchronized(lock) {
            gate.resetNaverPrepareAlight()
        }
        NaverPrepareAlight.reset()
    }

    fun noteNaverAlightStop(raw: String?) {
        NaverPrepareAlight.noteStop(raw)
        flushPrepareAlight()
    }

    fun noteNaverAlightStops(blobs: List<String>) {
        NaverPrepareAlight.noteStops(blobs)
        flushPrepareAlight()
    }

    fun noteNaverPrepareAlight(raw: String?) {
        NaverPrepareAlight.noteCue(raw)
        flushPrepareAlight()
    }

    private fun flushPrepareAlight() {
        val event = NaverPrepareAlight.readyEvent(System.currentTimeMillis()) ?: return
        val context = appContext ?: return
        Log.i(
            NaverMapNotification.TAG,
            "[NAVER_PREPARE_ALIGHT] armed stop=${event.landmark.orEmpty()}",
        )
        speaker(context).offer(event)
    }

    fun noteNaverNearBoard(raw: String?) {
        if (!NaverNearBoardNotice.note(raw)) return
        Log.i(NaverMapNotification.TAG, "[NAVER_NEAR_BOARD] armed raw=${raw?.trim().orEmpty()}")
        val pending = NaverNearBoardNotice.takePendingCar() ?: return
        offerPendingCar(pending)
    }

    private fun offerPendingCar(event: NavigationEvent) {
        val context = appContext ?: return
        speaker(context).offer(event)
    }

    fun resetNaverSubwayStages() {
        synchronized(lock) {
            gate.resetNaverSubwayStages()
        }
        NaverNextTrainNotice.reset()
    }

    fun armNaverTripStart() {
        NaverTripStartSession.request(System.currentTimeMillis())
    }

    private fun syncScreenState(context: Context) {
        val pm = context.applicationContext.getSystemService(PowerManager::class.java) ?: return
        val off = !pm.isInteractive
        if (off && !screenOff) {
            onScreenOff()
        } else if (!off && screenOff) {
            onScreenOn()
        } else {
            screenOff = off
        }
    }

    private fun ensureScreenWatch(app: Context) {
        synchronized(lock) {
            if (screenWatchRegistered) return
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_OFF -> onScreenOff()
                        Intent.ACTION_SCREEN_ON -> onScreenOn()
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(receiver, filter)
            }
            screenWatchRegistered = true
            Log.i(NaverMapNotification.TAG, "[NAV_SCREEN] watch registered")
        }
    }

    private fun speaker(context: Context): NavigationEventSpeaker {
        synchronized(lock) {
            speaker?.let { return it }
            val created = NavigationEventSpeaker(
                context.applicationContext,
                gate,
                naverBusWait,
            )
            speaker = created
            return created
        }
    }
}
