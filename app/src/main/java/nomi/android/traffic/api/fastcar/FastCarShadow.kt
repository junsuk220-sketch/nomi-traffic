package nomi.android.traffic.api.fastcar

import android.os.Handler
import android.os.Looper
import nomi.android.traffic.NavigationEventVoice

/** Host for FastCar. Speaks after Naver's near-board line. */
object FastCarShadow {

    private val main = Handler(Looper.getMainLooper())

    fun tick(title: String?, texts: List<String>) {
        val outcome = FastCar.onNotification(title, texts) ?: return
        FastCarDebug.emit("decision ${outcome.note} text=${outcome.text ?: "-"}")
        val text = outcome.text?.trim().orEmpty()
        if (text.isEmpty()) return
        FastCarDebug.emit("speak_scheduled delayMs=$AFTER_NAVER_MS")
        main.postDelayed({
            NavigationEventVoice.playTransferBriefing(text)
            FastCarDebug.emit("speak_played")
        }, AFTER_NAVER_MS)
    }

    private const val AFTER_NAVER_MS = 5_000L
}
