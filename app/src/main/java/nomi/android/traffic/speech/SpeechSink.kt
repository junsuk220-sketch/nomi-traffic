package nomi.android.traffic.speech

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
import nomi.android.traffic.NaverMapNotification
import java.util.Locale

/**
 * TTS only. Does not choose a sentence, grade ETA, or change other APIs.
 */
class SpeechSink(
    context: Context,
    val ledger: SpeechLedger = SpeechLedger(),
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
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

    fun speak(request: SpeechRequest) {
        val line = request.text.trim()
        if (line.isEmpty()) return
        speak(line)
    }

    fun speak(text: String) {
        val line = text.trim()
        if (line.isEmpty()) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { speak(line) }
            return
        }
        if (engineReady && voiceWarmed && tts != null) {
            speakNow(line)
            return
        }
        pending = line
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
        ledger.record(text, System.currentTimeMillis())
        Log.i(NaverMapNotification.TAG, "[NAV_SPEECH] $text speakMs=$speakMs")
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
