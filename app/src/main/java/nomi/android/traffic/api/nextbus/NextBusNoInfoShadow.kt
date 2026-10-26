package nomi.android.traffic.api.nextbus

import android.os.Handler
import android.os.Looper
import nomi.android.traffic.NavigationEventVoice

/**
 * Host for NextBusNoInfo. Speaks after Naver's `하차 후 환승` line.
 */
object NextBusNoInfoShadow {

    private val main = Handler(Looper.getMainLooper())
    private var speakGeneration = 0

    @Volatile
    private var lastPlayedAtMs = 0L

    /** Read by the bus transfer cell so the same "no info" is not said twice. */
    fun playedSince(atMs: Long): Boolean = atMs > 0L && lastPlayedAtMs >= atMs

    fun tickScreen(blobs: List<String>, nowMs: Long) {
        NextBusNoInfo.observeScreen(blobs, nowMs)
        tickCues(blobs, nowMs)
    }

    fun tickCues(cues: List<String>, nowMs: Long) {
        val outcome = NextBusNoInfo.onCues(cues, nowMs) ?: return
        NextBusNoInfoDebug.emit("decision ${outcome.note} text=${outcome.text ?: "-"}")
        val text = outcome.text?.trim().orEmpty()
        val stopNo = outcome.stopNo ?: return
        if (text.isEmpty()) return
        val gen = ++speakGeneration
        NextBusNoInfoDebug.emit("speak_scheduled delayMs=$AFTER_NAVER_MS")
        main.postDelayed({
            if (gen != speakGeneration) return@postDelayed
            if (!NextBusNoInfo.stillNoInfo(stopNo)) {
                NextBusNoInfoDebug.emit("speak_dropped stop=$stopNo info_arrived")
                return@postDelayed
            }
            NavigationEventVoice.playTransferBriefing(text)
            lastPlayedAtMs = System.currentTimeMillis()
            NextBusNoInfoDebug.emit("speak_played")
        }, AFTER_NAVER_MS)
    }

    private const val AFTER_NAVER_MS = 5_000L
}
