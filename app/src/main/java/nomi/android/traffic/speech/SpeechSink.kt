package nomi.android.traffic.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import nomi.android.traffic.FieldLogs
import nomi.android.traffic.NaverMapNotification
import java.io.File
import java.util.Locale
import kotlin.math.min

/**
 * TTS only. Does not choose a sentence, grade ETA, or change other APIs.
 */
class SpeechSink(
    context: Context,
    val ledger: SpeechLedger = SpeechLedger(),
) {
    private val appContext = context.applicationContext

    /**
     * Speech owns this thread. On the main thread it queued behind accessibility
     * screen reads: a line due in 120 ms was spoken 17 s later in the field.
     */
    private val thread = HandlerThread("nomi-speech").apply { start() }
    private val voice = Handler(thread.looper)
    private var tts: TextToSpeech? = null
    private var engineReady = false
    private var voiceWarmed = false
    private var recovering = false
    private var pending: String? = null
    private var seq = 0
    private var speakGeneration = 0
    private var delayedSpeak: Runnable? = null
    private var delayedText: String? = null
    private var awaitingPreSpeak = false
    private var speakWakeLock: PowerManager.WakeLock? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var volumeBoostStream: Int? = null
    private var volumeBeforeBoost: Int? = null

    init {
        ensureTts()
    }

    fun speak(request: SpeechRequest) {
        val line = request.text.trim()
        if (line.isEmpty()) return
        speak(line)
    }

    fun speak(text: String) {
        val line = text.trim()
        if (line.isEmpty()) return
        if (Looper.myLooper() != thread.looper) {
            voice.post { speak(line) }
            return
        }
        if (engineReady && voiceWarmed && tts != null) {
            speakNow(line)
            return
        }
        pending?.let { trace("pending replaced old=$it") }
        pending = line
        ensureTts()
        Log.i(NaverMapNotification.TAG, "[NAV_TTS] queued until warmed")
        trace("queued_until_warmed ready=$engineReady warmed=$voiceWarmed text=$line")
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
                        trace("audio_start id=$utteranceId")
                    }

                    override fun onDone(utteranceId: String?) {
                        Log.i(NaverMapNotification.TAG, "[NAV_TTS] audio done id=$utteranceId")
                        trace("audio_done id=$utteranceId")
                        if (utteranceId == WARMUP_ID) {
                            onWarmed()
                        } else {
                            endSpeakHold()
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        Log.w(NaverMapNotification.TAG, "[NAV_TTS] audio error id=$utteranceId")
                        trace("audio_error id=$utteranceId")
                        if (utteranceId == WARMUP_ID) {
                            onWarmed()
                        } else {
                            endSpeakHold()
                        }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        onError(utteranceId)
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

    /**
     * Grab focus first, wait briefly so the audio path opens, then speak.
     * Avoids clipping the first syllable while focus/route is still switching.
     */
    private fun speakNow(text: String) {
        if (tts == null) return
        val carried = if (awaitingPreSpeak) delayedText else null
        if (carried != null) {
            // A line still waiting for its focus delay must not be dropped by the next one.
            delayedText = null
            delayedSpeak?.let { voice.removeCallbacks(it) }
            delayedSpeak = null
            awaitingPreSpeak = false
            speakGeneration += 1
            trace("carry text=$carried next=$text")
            dispatchSpeak(carried)
            dispatchSpeak(text)
            return
        }
        cancelDelayedSpeak()
        beginSpeakHold()
        val gen = ++speakGeneration
        awaitingPreSpeak = true
        delayedText = text
        val postedAt = SystemClock.elapsedRealtime()
        val runnable = Runnable {
            if (gen != speakGeneration) {
                trace("stale gen=$gen now=$speakGeneration text=$text")
                return@Runnable
            }
            awaitingPreSpeak = false
            delayedText = null
            trace("hold_ran waitMs=${SystemClock.elapsedRealtime() - postedAt}")
            dispatchSpeak(text)
        }
        delayedSpeak = runnable
        val posted = voice.postDelayed(runnable, PRE_SPEAK_FOCUS_MS)
        trace("hold posted=$posted gen=$gen text=$text")
        Log.i(
            NaverMapNotification.TAG,
            "[NAV_TTS] focus held, speak in ${PRE_SPEAK_FOCUS_MS}ms",
        )
    }

    private fun dispatchSpeak(text: String) {
        val engine = tts
        if (engine == null) {
            endSpeakHold()
            return
        }
        seq += 1
        val utteranceId = "nav-event-$seq"
        val started = SystemClock.elapsedRealtime()
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, SPEAK_VOLUME)
        val code = engine.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
        val speakMs = SystemClock.elapsedRealtime() - started
        trace("dispatch id=$utteranceId code=$code text=$text")
        if (code != TextToSpeech.SUCCESS) {
            Log.w(NaverMapNotification.TAG, "[NAV_TTS] speak failed code=$code speakMs=$speakMs")
            endSpeakHold()
            recoverTts(text)
            return
        }
        recovering = false
        ledger.record(text, System.currentTimeMillis())
        Log.i(NaverMapNotification.TAG, "[NAV_SPEECH] $text speakMs=$speakMs vol=$SPEAK_VOLUME")
    }

    private fun cancelDelayedSpeak() {
        delayedSpeak?.let { voice.removeCallbacks(it) }
        delayedSpeak = null
        delayedText = null
        if (awaitingPreSpeak) {
            awaitingPreSpeak = false
            speakGeneration += 1
            endSpeakHold()
        }
    }

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
            val granted = am.requestAudioFocus(request)
            trace("focus result=$granted")
            audioFocusRequest = request
            boostVolumeSlightly(am)
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

    /** One media-stream step only while speaking; restored in [endSpeakHold]. */
    private fun boostVolumeSlightly(am: AudioManager) {
        restoreVolumeBoost(am)
        val stream = AudioManager.STREAM_MUSIC
        val current = am.getStreamVolume(stream)
        val max = am.getStreamMaxVolume(stream)
        if (current >= max || max <= 0) return
        val next = min(current + 1, max)
        am.setStreamVolume(stream, next, 0)
        volumeBoostStream = stream
        volumeBeforeBoost = current
        Log.i(
            NaverMapNotification.TAG,
            "[NAV_TTS] volume boost stream=$stream $current->$next (max=$max)",
        )
    }

    private fun restoreVolumeBoost(am: AudioManager? = null) {
        val stream = volumeBoostStream ?: return
        val prev = volumeBeforeBoost
        volumeBoostStream = null
        volumeBeforeBoost = null
        if (prev == null) return
        try {
            val manager = am
                ?: appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            manager.setStreamVolume(stream, prev, 0)
            Log.i(NaverMapNotification.TAG, "[NAV_TTS] volume restored stream=$stream to=$prev")
        } catch (e: Exception) {
            Log.w(NaverMapNotification.TAG, "[NAV_TTS] volume restore failed: ${e.message}")
        }
    }

    private fun endSpeakHold() {
        try {
            speakWakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        speakWakeLock = null
        restoreVolumeBoost()
        try {
            val request = audioFocusRequest ?: return
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.abandonAudioFocusRequest(request)
        } catch (_: Exception) {
        }
        audioFocusRequest = null
    }

    /** Field-only file log of each line's path to the speaker. */
    private fun trace(message: String) {
        if (!FieldLogs.enabled) return
        try {
            File(appContext.filesDir, TRACE_FILE)
                .appendText("${System.currentTimeMillis()} [SPEECH] $message\n")
        } catch (_: Throwable) {
        }
    }

    fun shutdown() {
        trace("shutdown")
        cancelDelayedSpeak()
        pending = null
        engineReady = false
        voiceWarmed = false
        recovering = false
        awaitingPreSpeak = false
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
        private const val TRACE_FILE = "nomi-speech.log"
        /** Wait after focus so the first syllable is not clipped. */
        private const val PRE_SPEAK_FOCUS_MS = 120L
        /** TTS relative volume (0..1). Engine max. */
        private const val SPEAK_VOLUME = 1.0f
    }
}
