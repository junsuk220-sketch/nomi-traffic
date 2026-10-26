package nomi.android.traffic.api.google.board

import nomi.android.traffic.NavigationEventVoice
import nomi.android.traffic.api.google.read.GoogleCellLog
import nomi.android.traffic.api.google.read.GoogleStep
import java.io.File

/** Host for GoogleBoard. */
object GoogleBoardShadow {

    private val log = GoogleCellLog("GOOGLE_BOARD", "nomi-google-board.log")

    fun attach(filesDir: File) = log.attach(filesDir)

    fun tick(step: GoogleStep) {
        val text = GoogleBoard.onStep(step) ?: return
        log.emit("speak text=$text")
        NavigationEventVoice.playGoogle(text)
    }

    fun end() {
        GoogleBoard.reset()
        log.emit("reset trip_end")
    }
}
