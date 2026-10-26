package nomi.android.traffic.api.nextbus

import android.util.Log
import nomi.android.traffic.FieldLogs
import java.io.File

/** Field-only logs for NextBusNoInfo. Does not change decisions. */
internal object NextBusNoInfoDebug {
    const val TAG = "NEXT_BUS_NO_INFO"
    private const val BUILD = "vc124-next-bus-no-info"
    private const val FILE_NAME = "nomi-nextbus.log"

    @Volatile
    private var dir: File? = null

    fun attach(filesDir: File) {
        if (!FieldLogs.enabled) return
        dir = filesDir
    }

    fun emit(message: String) {
        val line = "[NEXT_BUS_NO_INFO] $message build=$BUILD"
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
