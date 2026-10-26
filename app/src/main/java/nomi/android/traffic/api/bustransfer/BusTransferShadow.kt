package nomi.android.traffic.api.bustransfer

import android.os.Handler
import android.os.Looper
import nomi.android.traffic.NavigationEventVoice
import nomi.android.traffic.api.nextbus.NextBusNoInfoShadow
import nomi.android.traffic.observe.TransitReading

/**
 * Host for [BusTransfer]. Naver notifications only. Speaks that one sentence
 * on the bus board, never before `하차 후 환승` + 7 s.
 */
object BusTransferShadow {

    private val main = Handler(Looper.getMainLooper())
    private var generation = 0

    fun tick(cues: List<String>, reading: TransitReading?) {
        val now = System.currentTimeMillis()
        val outcome = BusTransfer.consider(cues, reading, now)
        outcome.note?.let(BusTransferDebug::emit)
        val text = outcome.text?.trim().orEmpty()
        if (text.isEmpty()) return
        val gen = ++generation
        val delay = BusTransfer.delayMs(outcome.armedAtMs, now)
        BusTransferDebug.emit("speak_scheduled delayMs=$delay")
        main.postDelayed({
            if (gen != generation) return@postDelayed
            if (outcome.noInfo && NextBusNoInfoShadow.playedSince(outcome.armedAtMs)) {
                BusTransferDebug.emit("speak_dropped next_bus_no_info_said")
                return@postDelayed
            }
            NavigationEventVoice.playBusTransfer(text)
            BusTransferDebug.emit("speak_played")
        }, delay)
    }

    /** Guidance ended. Drops a pending sentence too. */
    fun reset() {
        generation += 1
        BusTransfer.reset()
    }
}
