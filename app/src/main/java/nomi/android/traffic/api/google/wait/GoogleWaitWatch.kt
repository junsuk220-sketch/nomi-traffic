package nomi.android.traffic.api.google.wait

import nomi.android.traffic.api.google.read.GoogleCellLog
import nomi.android.traffic.api.google.read.GoogleStep
import java.io.File

/**
 * Record-only. 10/5/2/곧 waits until Google is seen giving wait minutes; this
 * logs every change of the departure clock so the field can show whether it moves.
 * Never speaks.
 */
object GoogleWaitWatch {

    private val log = GoogleCellLog("GOOGLE_WAIT", "nomi-google-wait.log")
    private var last: String? = null

    fun attach(filesDir: File) = log.attach(filesDir)

    @Synchronized
    fun tick(step: GoogleStep, rawTitle: String?, rawText: String?) {
        val key = when (step) {
            is GoogleStep.Wait -> "wait line=${step.line} stop=${step.stop} depart=${step.depart}"
            is GoogleStep.Board -> "board line=${step.line} stop=${step.stop} depart=${step.depart}"
            is GoogleStep.Walk -> "walk min=${step.minutes} depart=${step.depart}"
            else -> null
        } ?: return
        if (key == last) return
        last = key
        log.emit("$key title=${rawTitle.orEmpty()} text=${rawText.orEmpty()}")
    }

    @Synchronized
    fun end() {
        last = null
        log.emit("reset trip_end")
    }
}
