package nomi.android.traffic.api.nexttrain

import android.util.Log
import nomi.android.traffic.FieldLogs
import java.io.File

/** Field-only logs for the departed-train next ETA. Does not change decisions. */
internal object NextTrainDebug {
    const val TAG = "NEXT_TRAIN"
    private const val BUILD = "vc181-next-train-twice"
    private const val FILE_NAME = "nomi-nexttrain.log"

    @Volatile
    private var dir: File? = null

    fun attach(filesDir: File) {
        if (!FieldLogs.enabled) return
        dir = filesDir
    }

    fun emit(message: String) {
        val line = "[NEXT_TRAIN] $message build=$BUILD"
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
