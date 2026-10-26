package nomi.android.traffic.api.google.prepare

import nomi.android.traffic.NavigationEventVoice
import nomi.android.traffic.api.google.read.GoogleCellLog
import nomi.android.traffic.api.google.read.GoogleStep
import java.io.File

/** Host for GooglePrepare. */
object GooglePrepareShadow {

    private val log = GoogleCellLog("GOOGLE_PREPARE", "nomi-google-prepare.log")

    fun attach(filesDir: File) = log.attach(filesDir)

    fun tick(step: GoogleStep) {
        val text = GooglePrepare.onStep(step) ?: return
        log.emit("speak text=$text")
        NavigationEventVoice.playGoogle(text)
    }

    fun end() {
        GooglePrepare.reset()
        log.emit("reset trip_end")
    }
}
