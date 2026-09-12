package nomi.android.traffic

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import nomi.product.nav.NavigationEvent
import nomi.product.nav.NavigationEventSource
import nomi.product.nav.NavigationEventType
import java.util.Locale

/**
 * Host voice layer: [NavigationEvent] → phrase → Android TTS.
 * Naver and Google keep separate bus trackers / stage gates upstream.
 * Walk events are ignored. Samsung SMT warmup runs on first Korean speak.
 */
class NavigationEventSpeaker(
    context: Context,
    private val gate: NavigationEventSpeechGate = NavigationEventSpeechGate(),
    private val naverBusWait: NaverBusWaitTracker = NaverBusWaitTracker(),
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var tts: TextToSpeech? = null
    private var engineReady = false
    private var voiceWarmed = false
    private var recovering = false
    private var pending: String? = null
    private var seq = 0
    private var speakWakeLock: PowerManager.WakeLock? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    init {
        ensureTts()
    }

    fun offer(event: NavigationEvent) {
        when (event.type) {
            NavigationEventType.WALK -> Unit
            NavigationEventType.TRANSIT -> when (event.source) {
                NavigationEventSource.NAVER -> offerNaverTransit(event)
                NavigationEventSource.GOOGLE -> offerGoogleTransit(event)
            }
        }
    }

    fun speakNotice(text: String) {
        val line = text.trim()
        if (line.isEmpty()) return
        speak(line)
    }

    private fun offerNaverTransit(event: NavigationEvent) {
        val incoming = promoteArmedTripStart(event) ?: event
        if (incoming.action == NaverMapsTransit.TRIP_START_ACTION) {
            pinFromTripStart(incoming)
            val line = NavigationEventSpeech.line(incoming) ?: return
            val accepted = synchronized(lock) { gate.accept(incoming) }
            if (!accepted) return
            speak(line)
            NaverTripStartSession.markSpoken(System.currentTimeMillis())
            if (incoming.rawText == NaverMapsTransit.KIND_BUS) {
                naverBusWait.noteSpoken()
            }
            if (incoming.rawText == NaverMapsTransit.KIND_BUS &&
                incoming.busInfo?.arrivals.orEmpty().size >= 2
            ) {
                NaverNextTrainNotice.request()
            }
            flushNextTrain()
            return
        }
        if (event.action == NaverMapsTransit.TRANSFER_WALK_ACTION ||
            event.action == NaverMapsTransit.BOARD_DIRECTION_ACTION
        ) {
            if (event.action == NaverMapsTransit.BOARD_DIRECTION_ACTION) {
                pinFromBoardDirection(event)
            }
            val line = NavigationEventSpeech.line(event) ?: return
            val accepted = synchronized(lock) { gate.accept(event) }
            if (!accepted) return
            speak(line)
            return
        }
        if (NaverTripStartSession.holdBusWait() &&
            event.action != NaverMapsTransit.TRIP_START_ACTION &&
            event.action != NaverMapsTransit.TRANSFER_WALK_ACTION &&
            event.action != NaverMapsTransit.BOARD_DIRECTION_ACTION &&
            !isNaverSubwayCar(event) &&
            event.busInfo?.arrivals?.isNotEmpty() == true
        ) {
            if (event.rawText == NaverMapsTransit.KIND_SUBWAY ||
                event.busInfo?.arrivals.orEmpty().size >= 2
            ) {
                speakNextTrainOnce(event)
            }
            Log.i(NaverMapNotification.TAG, "[NAVER_TRIP] hold bus until briefing")
            return
        }
        if (event.action == NaverMapsTransit.PREPARE_ALIGHT_ACTION) {
            val line = NavigationEventSpeech.line(event) ?: return
            val accepted = synchronized(lock) { gate.accept(event) }
            if (!accepted) return
            Log.i(NaverMapNotification.TAG, "[NAVER_PREPARE_ALIGHT] speak $line")
            speak(line)
            return
        }
        if (isNaverSubwayCar(event)) {
            if (!NaverNearBoardNotice.isArmed()) {
                NaverNearBoardNotice.holdCar(event)
                Log.i(
                    NaverMapNotification.TAG,
                    "[NAVER_SUBWAY_CAR] hold until near board " +
                        "dir=${event.landmark.orEmpty()} cars=${event.rawText}",
                )
                return
            }
            val line = NavigationEventSpeech.line(event) ?: return
            val accepted = synchronized(lock) { gate.accept(event) }
            if (!accepted) return
            speak(line)
            return
        }
        // Subway 10/5/2/곧 from 302 clocks — bypass bus wait tracker.
        if (event.rawText == NaverMapsTransit.KIND_SUBWAY &&
            event.busInfo?.arrivals?.isNotEmpty() == true
        ) {
            val arrival = event.busInfo?.arrivals?.firstOrNull()
            val subwayLine = arrival?.line.orEmpty()
            if (!naverBusWait.allowsSubwayLine(subwayLine)) {
                Log.i(
                    NaverMapNotification.TAG,
                    "[NAVER_SUBWAY_STAGE] skip unpinned line=$subwayLine " +
                        "pin=${naverBusWait.pinnedSubwayLine().orEmpty()} " +
                        "eta=${arrival?.eta.orEmpty()}",
                )
                return
            }
            speakNextTrainOnce(event)
            val line = NavigationEventSpeech.line(event) ?: return
            val accepted = synchronized(lock) { gate.accept(event) }
            if (!accepted) {
                Log.i(
                    NaverMapNotification.TAG,
                    "[NAVER_SUBWAY_STAGE] skip line=$subwayLine " +
                        "eta=${arrival?.eta.orEmpty()}",
                )
                return
            }
            Log.i(NaverMapNotification.TAG, "[NAVER_SUBWAY_STAGE] speak $line")
            speak(line)
            return
        }
        val focused = synchronized(lock) {
            NaverBusWaitSpeech.focusedEvent(
                tracker = naverBusWait,
                event = event,
                ensureSeed = {
                    if (naverBusWait.pinnedBusLines().isEmpty()) {
                        NaverTripStartCache.peek()?.let { pinFromTripStart(it) }
                    }
                },
            )
        } ?: return
        val line = NavigationEventSpeech.line(focused) ?: return
        Log.i(
            NaverMapNotification.TAG,
            "[NAVER_BUS_STAGE] speak $line",
        )
        speak(line)
    }

    private fun offerGoogleTransit(event: NavigationEvent) {
        val line = NavigationEventSpeech.line(event) ?: return
        val accepted = synchronized(lock) { gate.accept(event) }
        if (!accepted) return
        speak(line)
    }

    private fun speakNextTrainOnce(@Suppress("UNUSED_PARAMETER") event: NavigationEvent) {
        // Next train is on the wait line until 승차역 부근. Do not speak it alone.
    }

    private fun flushNextTrain() {
        val pending = NaverNextTrainNotice.takePending() ?: return
        speakNextTrainOnce(pending)
    }

    /** 302 clocks arrived while the sheet briefing is still pending. */
    private fun promoteArmedTripStart(event: NavigationEvent): NavigationEvent? {
        if (!NaverTripStartSession.due(System.currentTimeMillis())) return null
        return NaverTripStartParser.asTripStart(event)
    }

    private fun pinFromTripStart(event: NavigationEvent) {
        val line = event.busInfo?.arrivals?.firstOrNull()?.line?.trim().orEmpty()
        if (line.isEmpty()) return
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

    private fun pinFromBoardDirection(event: NavigationEvent) {
        val line = event.busInfo?.arrivals?.firstOrNull()?.line?.trim().orEmpty()
        if (line.isEmpty()) return
        naverBusWait.pinBusLine(line)
        Log.i(NaverMapNotification.TAG, "[NAVER_PIN] bus board=$line")
    }

    private fun isNaverSubwayCar(event: NavigationEvent): Boolean {
        if (event.action != NaverMapsTransit.QUICK_EXIT &&
            event.action != NaverMapsTransit.QUICK_TRANSFER
        ) {
            return false
        }
        val direction = event.landmark?.trim().orEmpty()
        return direction.endsWith("방면") && event.rawText.trim().isNotEmpty()
    }

    private fun speak(text: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { speak(text) }
            return
        }
        if (engineReady && voiceWarmed && tts != null) {
            speakNow(text)
            return
        }
        pending = text
        ensureTts()
        Log.i(NaverMapNotification.TAG, "[NAV_TTS] queued until warmed")
    }

    private fun ensureTts() {
        if (tts != null) return
        val started = SystemClock.elapsedRealtime()
        Log.i(NaverMapNotification.TAG, "[NAV_TTS] create")
        try {
            val holder = arrayOfNulls<TextToSpeech>(1)
            holder[0] = TextToSpeech(appContext) { status ->
                val engine = holder[0]
                if (status != TextToSpeech.SUCCESS || engine == null) {
                    Log.w(NaverMapNotification.TAG, "[NAV_TTS] init failed status=$status")
                    engineReady = false
                    pending = null
                    return@TextToSpeech
                }
                val lang = try {
                    engine.setLanguage(Locale.KOREAN)
                } catch (e: Exception) {
                    Log.w(NaverMapNotification.TAG, "[NAV_TTS] language failed: ${e.message}")
                    engineReady = false
                    pending = null
                    return@TextToSpeech
                }
                if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(NaverMapNotification.TAG, "[NAV_TTS] Korean unsupported result=$lang")
                    engineReady = false
                    pending = null
                    return@TextToSpeech
                }
                try {
                    engine.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                } catch (e: Exception) {
                    Log.w(NaverMapNotification.TAG, "[NAV_TTS] audio attrs failed: ${e.message}")
                }
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.i(NaverMapNotification.TAG, "[NAV_TTS] audio start id=$utteranceId")
                    }

                    override fun onDone(utteranceId: String?) {
                        Log.i(NaverMapNotification.TAG, "[NAV_TTS] audio done id=$utteranceId")
                        if (utteranceId == WARMUP_ID) {
                            onWarmed()
                        } else {
                            endSpeakHold()
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        Log.w(NaverMapNotification.TAG, "[NAV_TTS] audio error id=$utteranceId")
                        if (utteranceId == WARMUP_ID) {
                            onWarmed()
                        } else {
                            endSpeakHold()
                        }
                    }
                })
                engineReady = true
                tts = engine
                Log.i(
                    NaverMapNotification.TAG,
                    "[NAV_TTS] engine ready initMs=${SystemClock.elapsedRealtime() - started}",
                )
                warmupKoreanVoice(engine)
            }
            tts = holder[0]
        } catch (e: Exception) {
            Log.w(NaverMapNotification.TAG, "[NAV_TTS] create failed: ${e.message}")
            tts = null
            engineReady = false
            pending = null
        }
    }

    private fun onWarmed() {
        if (voiceWarmed) return
        voiceWarmed = true
        Log.i(NaverMapNotification.TAG, "[NAV_TTS] korean voice warmed")
        val queued = pending
        pending = null
        if (!queued.isNullOrBlank()) speakNow(queued)
    }

    private fun warmupKoreanVoice(engine: TextToSpeech) {
        try {
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0f)
            val code = engine.speak("음", TextToSpeech.QUEUE_FLUSH, params, WARMUP_ID)
            Log.i(NaverMapNotification.TAG, "[NAV_TTS] warmup speak code=$code")
            if (code != TextToSpeech.SUCCESS) onWarmed()
        } catch (e: Exception) {
            Log.w(NaverMapNotification.TAG, "[NAV_TTS] warmup failed: ${e.message}")
            onWarmed()
        }
    }

    private fun speakNow(text: String) {
        val engine = tts ?: return
        seq += 1
        val utteranceId = "nav-event-$seq"
        beginSpeakHold()
        val started = SystemClock.elapsedRealtime()
        val params = Bundle()
        val code = engine.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
        val speakMs = SystemClock.elapsedRealtime() - started
        if (code != TextToSpeech.SUCCESS) {
            Log.w(NaverMapNotification.TAG, "[NAV_TTS] speak failed code=$code speakMs=$speakMs")
            endSpeakHold()
            recoverTts(text)
            return
        }
        recovering = false
        Log.i(NaverMapNotification.TAG, "[NAV_SPEECH] $text speakMs=$speakMs")
    }

    /** Samsung SMT dies after a long background stretch and then speak() returns -1. */
    private fun recoverTts(text: String) {
        if (recovering) {
            pending = text
            Log.w(NaverMapNotification.TAG, "[NAV_TTS] recover already running")
            return
        }
        recovering = true
        pending = text
        engineReady = false
        voiceWarmed = false
        Log.i(NaverMapNotification.TAG, "[NAV_TTS] recreate after speak failure")
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        ensureTts()
    }

    private fun beginSpeakHold() {
        try {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { }
                .build()
            am.requestAudioFocus(request)
            audioFocusRequest = request
        } catch (e: Exception) {
            Log.w(NaverMapNotification.TAG, "[NAV_TTS] audio focus failed: ${e.message}")
        }
        try {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nomi.traffic:nav-tts")
            wl.setReferenceCounted(false)
            wl.acquire(30_000L)
            speakWakeLock = wl
        } catch (e: Exception) {
            Log.w(NaverMapNotification.TAG, "[NAV_TTS] wake lock failed: ${e.message}")
        }
    }

    private fun endSpeakHold() {
        try {
            speakWakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        speakWakeLock = null
        try {
            val request = audioFocusRequest ?: return
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.abandonAudioFocusRequest(request)
        } catch (_: Exception) {
        }
        audioFocusRequest = null
    }

    fun shutdown() {
        pending = null
        engineReady = false
        voiceWarmed = false
        recovering = false
        endSpeakHold()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
    }

    companion object {
        private const val WARMUP_ID = "nav-warmup"
    }
}
