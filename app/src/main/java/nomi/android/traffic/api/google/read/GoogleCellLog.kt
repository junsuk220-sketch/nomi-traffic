package nomi.android.traffic.api.google.read

import android.util.Log
import nomi.android.traffic.FieldLogs
import java.io.File

/** Field-only log for one Google cell. Each cell owns its own file. */
class GoogleCellLog(private val tag: String, private val fileName: String) {

    @Volatile
    private var dir: File? = null

    fun attach(filesDir: File) {
        if (!FieldLogs.enabled) return
        dir = filesDir
    }

    fun emit(message: String) {
        val line = "[$tag] $message build=$BUILD"
        try {
            Log.i(tag, line)
        } catch (_: Throwable) {
        }
        val folder = dir ?: return
        try {
            File(folder, fileName).appendText("${System.currentTimeMillis()} $line\n")
        } catch (_: Throwable) {
        }
    }

    companion object {
        const val BUILD = "vc155-speech-thread"
    }
}
