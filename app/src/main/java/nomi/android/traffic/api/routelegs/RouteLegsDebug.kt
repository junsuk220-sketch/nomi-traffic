package nomi.android.traffic.api.routelegs

import android.util.Log
import nomi.android.traffic.FieldLogs
import java.io.File

/** Field-only logs for RouteLegs. Does not change decisions. */
internal object RouteLegsDebug {
    const val TAG = "ROUTE_LEGS"
    private const val BUILD = "vc156-route-hold-note"
    private const val FILE_NAME = "nomi-routelegs.log"

    @Volatile
    private var dir: File? = null

    @Volatile
    private var last: String? = null

    fun attach(filesDir: File) {
        if (!FieldLogs.enabled) return
        dir = filesDir
    }

    fun emit(message: String) {
        if (message == last) return
        last = message
        val line = "[ROUTE_LEGS] $message build=$BUILD"
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
