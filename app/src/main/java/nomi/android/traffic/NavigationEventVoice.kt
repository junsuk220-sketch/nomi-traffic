package nomi.android.traffic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.util.Log
import nomi.android.traffic.api.bustransfer.BusTransferShadow
import nomi.android.traffic.api.seoulcountdown.SeoulSubwayCountdownShadow
import nomi.android.traffic.buswait.BusWaitFieldTrace
import nomi.android.traffic.speech.SpeechLedger
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

    /** Read-only facts about what SpeechSink already played. */
    fun speechLedger(): SpeechLedger? = synchronized(lock) { speaker?.ledger() }

    fun prepare(context: Context) {
        appContext = context.applicationContext
        BusWaitFieldTrace.attach(context.applicationContext.filesDir)
        speaker(context)
        ensureScreenWatch(context.applicationContext)
        syncScreenState(context.applicationContext)
    }

    fun offer(context: Context, event: NavigationEvent) {
        if (event.source == NavigationEventSource.NAVER &&
            event.action == NaverMapsTransit.TRIP_START_ACTION
        ) {
            NaverTripStartDebug.step4(
                action = event.action,
                raw = event.busInfo?.raw.orEmpty(),
                isTripStart = true,
            )
        }
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

    /** Eta host only. Same sink as other Naver speech. */
    fun speakEta(text: String) {
        val context = appContext ?: return
        speakNotice(context, text)
    }

    /** NextTrain host only. One sentence, no trip-start or ladder state. */
    fun playNextTrain(text: String) {
        val context = appContext ?: return
        val line = text.trim()
        if (line.isEmpty()) return
        speakNotice(context, line)
    }

    /** BusTransfer host only. One sentence, no trip-start or bus wait state. */
    fun playBusTransfer(text: String) {
        val context = appContext ?: return
        val line = text.trim()
        if (line.isEmpty()) return
        speakNotice(context, line)
    }

    /** Transfer host only. Does not mark trip-start or bus wait as spoken. */
    fun playTransferBriefing(text: String) {
        val context = appContext ?: return
        val line = text.trim()
        if (line.isEmpty()) return
        speakNotice(context, line)
    }

    /** Google cells only. Touches no Naver state. */
    fun playGoogle(text: String) {
        val context = appContext ?: return
        val line = text.trim()
        if (line.isEmpty()) return
        speakNotice(context, line)
    }

    /** StartBriefing host only. Marks the legacy trip-start as spoken so it does not repeat. */
    fun playStartBriefing(text: String, bus: Boolean) {
        val context = appContext ?: return
        val line = text.trim()
        if (line.isEmpty()) return
        try {
            speaker(context).briefStart(line, bus)
        } catch (_: Exception) {
        }
    }

    /** Every Naver a11y event or notification. A due start briefing speaks here if its timer did not. */
    fun tickStartBrief() {
        try {
            speaker?.tickStartBrief()
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

    /**
     * A new Naver guidance began. Every owner that a new trip invalidates clears
     * itself here. Callers announce the transition; they do not list the owners —
     * adding a cue means editing this one function, not hunting reset calls.
     * The bus pin survives: the itinerary is re-seeded, not rebuilt.
     */
    fun onNaverGuidanceStarted() {
        synchronized(lock) {
            gate.resetNaverJourneyCues()
        }
        NaverNearBoardNotice.reset()
        NaverNextTrainNotice.reset()
        NaverPrepareAlight.reset()
    }

    /** Guidance ended. Clear pin so the next trip must seed again. */
    fun onNaverGuidanceEnded() {
        speaker?.cancelStartBrief()
        synchronized(lock) {
            naverBusWait.leave()
            gate.resetNaverTripStart()
            gate.resetNaverPrepareAlight()
        }
        NaverNextTrainNotice.reset()
        NaverNearBoardNotice.reset()
        NaverPrepareAlight.reset()
        NaverSubwayPin.reset()
        SeoulSubwayCountdownShadow.stop()
        BusTransferShadow.reset()
        Log.i(NaverMapNotification.TAG, "[NAVER_PIN] cleared — guidance ended")
    }

    /**
     * The 안내 중 label left the screen without ending. Pin stays — 302 keeps
     * driving 10/5/2/곧, so nothing here may touch the bus stage set.
     */
    fun onNaverGuidanceOffScreen() {
        synchronized(lock) {
            gate.resetNaverTransferCues()
            naverBusWait.releaseSheetOwnership()
        }
        NaverNearBoardNotice.reset()
    }

    /** Pin itinerary line from preview/live trip-start before bus rows can speak. */
    fun pinNaverFromTripEvent(event: NavigationEvent) {
        val pinned = synchronized(lock) { NaverTripPin.apply(event, naverBusWait) } ?: return
        Log.i(NaverMapNotification.TAG, "[NAVER_PIN] $pinned")
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

    /** The start button was pressed: the briefing may speak again. One owner. */
    fun resetNaverTripStart() {
        synchronized(lock) {
            gate.resetNaverTripStart()
        }
    }

    /** 302 `이번 정류장에서 하차`: the bus wait may re-seed on the transfer bus board. */
    fun noteNaverBusAlight(title: String?) {
        if (title?.trim()?.startsWith("이번 정류장에서 하차") != true) return
        synchronized(lock) { naverBusWait.noteBusAlighted() }
        Log.i(NaverMapNotification.TAG, "[NAVER_PIN] bus alighted, next wait board may re-seed")
    }

    /**
     * 302 `…으로 이동 중` / `… 정차`: already aboard.
     * Sheet wait boards stay silent until alight / next wait board / guidance end.
     */
    fun noteNaverBusRiding(title: String?) {
        val head = title?.trim().orEmpty()
        if (head.isEmpty()) return
        val stop = RIDING_MOVING.find(head)?.groupValues?.get(1)?.trim()
            ?: RIDING_STOPPED.find(head)?.groupValues?.get(1)?.trim()
            ?: return
        if (stop.isEmpty()) return
        synchronized(lock) { naverBusWait.noteRiding() }
        Log.i(NaverMapNotification.TAG, "[NAVER_PIN] riding, sheet bus wait silent")
    }

    /** A subway alight-transfer opens the closed bus ladder only when a bus board follows. */
    fun noteNaverSubwayTransfer(title: String?) {
        val cue = title?.trim().orEmpty()
        if (!cue.contains("이번 역") || !cue.contains("하차 후 환승")) return
        synchronized(lock) { naverBusWait.noteSubwayTransfer() }
        Log.i(NaverMapNotification.TAG, "[NAVER_PIN] subway alighted, next bus board may open")
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

    /** Observation tier: the signal is recorded and nothing else happens (8-1). */
    fun noteNaverNearBoard(raw: String?) {
        if (!NaverNearBoardNotice.note(raw)) return
        Log.i(NaverMapNotification.TAG, "[NAVER_NEAR_BOARD] observed raw=${raw?.trim().orEmpty()}")
    }

    fun armNaverTripStart() {
        val armed = NaverTripStartSession.request(System.currentTimeMillis())
        NaverTripStartDebug.step1(
            how = "302_start_phrase",
            armed = armed,
            session = NaverTripStartSession.debugSnapshot(),
            due = NaverTripStartSession.due(System.currentTimeMillis()),
        )
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

    /** Same title shapes as [nomi.android.traffic.eventfirst.NaverEventParser] Riding. */
    private val RIDING_MOVING = Regex("""^(.+?)(?:\(으\)로|으로|로) 이동 중$""")
    private val RIDING_STOPPED = Regex("""^(.+?)\s+정차$""")
}
