package nomi.android.traffic.api.google.brief

import nomi.android.traffic.GoogleTransitAccessibilityParser
import nomi.android.traffic.NavigationEventVoice
import nomi.android.traffic.api.google.read.GoogleCellLog
import nomi.android.traffic.api.google.read.GoogleStep
import java.io.File

/** Host for GoogleStartBrief. */
object GoogleStartBriefShadow {

    private val log = GoogleCellLog("GOOGLE_BRIEF", "nomi-google-brief.log")

    fun attach(filesDir: File) = log.attach(filesDir)

    fun onStartClick() {
        GoogleStartBrief.onStartClick()
        log.emit("armed start_click")
    }

    fun onScreen(root: GoogleTransitAccessibilityParser.Node, live: Boolean, nowMillis: Long) {
        val text = GoogleStartBrief.onScreen(root, live, nowMillis) ?: return
        log.emit("speak text=$text")
        NavigationEventVoice.playGoogle(text)
    }

    fun tick(step: GoogleStep) {
        val text = GoogleStartBrief.onStep(step) ?: return
        log.emit("speak text=$text")
        NavigationEventVoice.playGoogle(text)
    }

    fun end() {
        GoogleStartBrief.reset()
        log.emit("reset trip_end")
    }
}
