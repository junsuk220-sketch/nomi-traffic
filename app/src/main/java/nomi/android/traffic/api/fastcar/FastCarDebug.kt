package nomi.android.traffic.api.fastcar

import android.util.Log
import nomi.android.traffic.FieldLogs
import java.io.File

/** Field-only logs for FastCar. Does not change decisions. */
internal object FastCarDebug {
    const val TAG = "FAST_CAR"
    private const val BUILD = "vc125-fast-car-twice"
    private const val FILE_NAME = "nomi-fastcar.log"

    @Volatile
    private var dir: File? = null

    fun attach(filesDir: File) {
        if (!FieldLogs.enabled) return
        dir = filesDir
    }

    fun emit(message: String) {
        val line = "[FAST_CAR] $message build=$BUILD"
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
