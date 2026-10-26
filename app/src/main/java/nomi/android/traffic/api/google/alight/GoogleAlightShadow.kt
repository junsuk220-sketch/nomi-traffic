package nomi.android.traffic.api.google.alight

import nomi.android.traffic.NavigationEventVoice
import nomi.android.traffic.api.google.read.GoogleCellLog
import nomi.android.traffic.api.google.read.GoogleStep
import java.io.File

/** Host for GoogleAlight. */
object GoogleAlightShadow {

    private val log = GoogleCellLog("GOOGLE_ALIGHT", "nomi-google-alight.log")

    fun attach(filesDir: File) = log.attach(filesDir)

    fun tick(step: GoogleStep) {
        val text = GoogleAlight.onStep(step) ?: return
        log.emit("speak text=$text")
        NavigationEventVoice.playGoogle(text)
    }

    fun end() {
        GoogleAlight.reset()
        log.emit("reset trip_end")
    }
}
