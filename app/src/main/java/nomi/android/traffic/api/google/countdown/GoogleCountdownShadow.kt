package nomi.android.traffic.api.google.countdown

import android.os.Handler
import android.os.Looper
import nomi.android.traffic.NavigationEventVoice
import nomi.android.traffic.api.google.read.GoogleCellLog
import nomi.android.traffic.api.google.read.GoogleStep
import java.io.File

/** Host for GoogleCountdown. Checks the clock every 15 s while a wait is open. */
object GoogleCountdownShadow {

    private const val TICK_MS = 15_000L

    private val log = GoogleCellLog("GOOGLE_COUNTDOWN", "nomi-google-countdown.log")
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val ticker = object : Runnable {
        override fun run() {
            try {
                speak(GoogleCountdown.onTick(System.currentTimeMillis()))
            } catch (_: Throwable) {
            }
            if (GoogleCountdown.active) handler.postDelayed(this, TICK_MS)
        }
    }

    fun attach(filesDir: File) = log.attach(filesDir)

    fun tick(step: GoogleStep) {
        speak(GoogleCountdown.onStep(step, System.currentTimeMillis()))
        if (step is GoogleStep.Wait) log.emit("watch line=${step.line} depart=${step.depart}")
        handler.removeCallbacks(ticker)
        if (GoogleCountdown.active) handler.postDelayed(ticker, TICK_MS)
    }

    fun end() {
        handler.removeCallbacks(ticker)
        GoogleCountdown.reset()
        log.emit("reset trip_end")
    }

    private fun speak(text: String?) {
        if (text == null) return
        log.emit("speak text=$text")
        NavigationEventVoice.playGoogle(text)
    }
}
