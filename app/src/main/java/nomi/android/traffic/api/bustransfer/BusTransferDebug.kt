package nomi.android.traffic.api.bustransfer

import android.util.Log
import nomi.android.traffic.FieldLogs
import java.io.File

/** Field-only logs for the next-bus transfer sentence. Does not change decisions. */
internal object BusTransferDebug {
    const val TAG = "BUS_TRANSFER"
    private const val BUILD = "vc181-bus-transfer"
    private const val FILE_NAME = "nomi-bustransfer.log"

    @Volatile
    private var dir: File? = null

    fun attach(filesDir: File) {
        if (!FieldLogs.enabled) return
        dir = filesDir
    }

    fun emit(message: String) {
        val line = "[BUS_TRANSFER] $message build=$BUILD"
        try {
            Log.i(TAG, line)
        } catch (_: Throwable) {
        }
        val folder = dir ?: return
        try {
            File(folder, FILE_NAME).appendText("${System.currentTimeMillis()} $line\n")
        } catch (_: Throwable) {
        }
    }
}
