package nomi.android.traffic

import nomi.traffic.BuildConfig
import java.io.File

/**
 * Field observation and debug files. Nothing reads them back for speech.
 * Debug builds write them; release builds never create them and remove the ones
 * an older version left behind. Route cards and setup state are app data and must
 * never be listed here. Keep `backup_rules.xml` and `data_extraction_rules.xml`
 * excluding the same names.
 */
object FieldLogs {

    val enabled: Boolean get() = BuildConfig.DEBUG

    internal val FILES = listOf(
        "google-ride-observe.jsonl",
        "google-ride-observe.1.jsonl",
        "naver-ride-observe.jsonl",
        "naver-ride-observe.1.jsonl",
        "nomi-speech.log",
        "nomi-transfer.log",
        "nomi-fastcar.log",
        "nomi-nexttrain.log",
        "nomi-bustransfer.log",
        "nomi-tripstart.log",
        "nomi-nextbus.log",
        "nomi-routelegs.log",
        "nomi-buswait-trace.jsonl",
        "nomi-eventfirst.jsonl",
        "nomi-google-feed.log",
        "nomi-google-brief.log",
        "nomi-google-walk.log",
        "nomi-google-wait.log",
        "nomi-google-board.log",
        "nomi-google-ride.log",
        "nomi-google-prepare.log",
        "nomi-google-alight.log",
        "nomi-google-transfer.log",
        "nomi-google-countdown.log",
        "nomi-setup-nudge.log",
        "nomi-beacon.log",
        "nomi-google-beacon.log",
    )

    /** Release start: delete field files an older build wrote. Debug keeps them. */
    fun purgeIfRelease(filesDir: File) {
        if (enabled) return
        purge(filesDir)
    }

    /** Deletes only [FILES]. Returns how many were removed. */
    internal fun purge(filesDir: File): Int {
        var removed = 0
        for (name in FILES) {
            val file = File(filesDir, name)
            if (file.isFile && file.delete()) removed += 1
        }
        return removed
    }
}
