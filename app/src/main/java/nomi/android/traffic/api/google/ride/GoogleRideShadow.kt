package nomi.android.traffic.api.google.ride

import nomi.android.traffic.NavigationEventVoice
import nomi.android.traffic.api.google.read.GoogleCellLog
import nomi.android.traffic.api.google.read.GoogleStep
import java.io.File

/** Host for GoogleRide. */
object GoogleRideShadow {

    private val log = GoogleCellLog("GOOGLE_RIDE", "nomi-google-ride.log")

    fun attach(filesDir: File) = log.attach(filesDir)

    fun tick(step: GoogleStep) {
        val text = GoogleRide.onStep(step) ?: return
        log.emit("speak text=$text")
        NavigationEventVoice.playGoogle(text)
    }

    fun end() {
        GoogleRide.reset()
        log.emit("reset trip_end")
    }
}
