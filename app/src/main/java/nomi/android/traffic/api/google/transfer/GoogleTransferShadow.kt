package nomi.android.traffic.api.google.transfer

import nomi.android.traffic.NavigationEventVoice
import nomi.android.traffic.api.google.read.GoogleCellLog
import nomi.android.traffic.api.google.read.GoogleStep
import java.io.File

/** Host for GoogleTransfer. */
object GoogleTransferShadow {

    private val log = GoogleCellLog("GOOGLE_TRANSFER", "nomi-google-transfer.log")

    fun attach(filesDir: File) = log.attach(filesDir)

    fun onScreen(blobs: List<String>, live: Boolean, nowMillis: Long) {
        GoogleTransfer.onScreen(blobs, live, nowMillis)
    }

    fun tick(step: GoogleStep) {
        val text = GoogleTransfer.onStep(step, System.currentTimeMillis()) ?: return
        log.emit("speak text=$text src=${GoogleTransfer.lastSource}")
        NavigationEventVoice.playGoogle(text)
    }

    fun end() {
        GoogleTransfer.reset()
        log.emit("reset trip_end")
    }
}
