package nomi.android.traffic.api.google.walk

import nomi.android.traffic.NavigationEventVoice
import nomi.android.traffic.api.google.read.GoogleCellLog
import nomi.android.traffic.api.google.read.GoogleStep
import java.io.File

/** Host for GoogleWalk. */
object GoogleWalkShadow {

    private val log = GoogleCellLog("GOOGLE_WALK", "nomi-google-walk.log")

    fun attach(filesDir: File) = log.attach(filesDir)

    fun tick(step: GoogleStep) {
        val text = GoogleWalk.onStep(step) ?: return
        log.emit("speak text=$text")
        NavigationEventVoice.playGoogle(text)
    }

    fun end() {
        GoogleWalk.reset()
        log.emit("reset trip_end")
    }
}
