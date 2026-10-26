package nomi.android.traffic.api.google

import nomi.android.traffic.api.google.alight.GoogleAlightShadow
import nomi.android.traffic.api.google.board.GoogleBoardShadow
import nomi.android.traffic.api.google.brief.GoogleStartBriefShadow
import nomi.android.traffic.api.google.countdown.GoogleCountdownShadow
import nomi.android.traffic.api.google.prepare.GooglePrepareShadow
import nomi.android.traffic.api.google.read.GoogleCellLog
import nomi.android.traffic.api.google.read.GoogleStepReader
import nomi.android.traffic.api.google.ride.GoogleRideShadow
import nomi.android.traffic.api.google.transfer.GoogleTransferShadow
import nomi.android.traffic.api.google.wait.GoogleWaitWatch
import nomi.android.traffic.api.google.walk.GoogleWalkShadow
import java.io.File

/**
 * Hands one Google guidance notification to every cell. Each cell runs in its
 * own try so one failing never stops the others.
 */
object GoogleFeed {

    private val log = GoogleCellLog("GOOGLE_FEED", "nomi-google-feed.log")
    private var lastRaw: String? = null

    fun attach(filesDir: File) {
        log.attach(filesDir)
        try { GoogleStartBriefShadow.attach(filesDir) } catch (_: Throwable) {}
        try { GoogleWalkShadow.attach(filesDir) } catch (_: Throwable) {}
        try { GoogleWaitWatch.attach(filesDir) } catch (_: Throwable) {}
        try { GoogleBoardShadow.attach(filesDir) } catch (_: Throwable) {}
        try { GoogleRideShadow.attach(filesDir) } catch (_: Throwable) {}
        try { GooglePrepareShadow.attach(filesDir) } catch (_: Throwable) {}
        try { GoogleAlightShadow.attach(filesDir) } catch (_: Throwable) {}
        try { GoogleTransferShadow.attach(filesDir) } catch (_: Throwable) {}
        try { GoogleCountdownShadow.attach(filesDir) } catch (_: Throwable) {}
    }

    fun onPosted(title: String?, text: String?, subText: String?) {
        val step = try {
            GoogleStepReader.read(title, text, subText)
        } catch (_: Throwable) {
            null
        } ?: return
        val raw = "$title|$text|$subText"
        if (raw != lastRaw) {
            lastRaw = raw
            log.emit("step $step")
        }
        try { GoogleStartBriefShadow.tick(step) } catch (_: Throwable) {}
        try { GoogleWalkShadow.tick(step) } catch (_: Throwable) {}
        try { GoogleWaitWatch.tick(step, title, text) } catch (_: Throwable) {}
        try { GoogleBoardShadow.tick(step) } catch (_: Throwable) {}
        try { GoogleRideShadow.tick(step) } catch (_: Throwable) {}
        try { GooglePrepareShadow.tick(step) } catch (_: Throwable) {}
        try { GoogleAlightShadow.tick(step) } catch (_: Throwable) {}
        try { GoogleTransferShadow.tick(step) } catch (_: Throwable) {}
        try { GoogleCountdownShadow.tick(step) } catch (_: Throwable) {}
    }

    /** Google's ongoing guidance notification went away: the trip is over. */
    fun onEnded() {
        lastRaw = null
        log.emit("trip_end")
        try { GoogleStartBriefShadow.end() } catch (_: Throwable) {}
        try { GoogleWalkShadow.end() } catch (_: Throwable) {}
        try { GoogleWaitWatch.end() } catch (_: Throwable) {}
        try { GoogleBoardShadow.end() } catch (_: Throwable) {}
        try { GoogleRideShadow.end() } catch (_: Throwable) {}
        try { GooglePrepareShadow.end() } catch (_: Throwable) {}
        try { GoogleAlightShadow.end() } catch (_: Throwable) {}
        try { GoogleTransferShadow.end() } catch (_: Throwable) {}
        try { GoogleCountdownShadow.end() } catch (_: Throwable) {}
    }
}
