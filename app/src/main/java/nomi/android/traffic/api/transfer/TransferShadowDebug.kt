package nomi.android.traffic.api.transfer

import android.util.Log
import nomi.android.traffic.FieldLogs
import nomi.android.traffic.observe.TransitReading
import java.io.File

/**
 * Field-only logs for the transfer API. Does not change decisions.
 * Never logs the Seoul API key.
 */
internal object TransferShadowDebug {
    const val TAG = "TRANSFER_SHADOW"
    private const val BUILD = "vc123-seoul-owns-alight"
    private const val FILE_NAME = "nomi-transfer.log"

    @Volatile
    private var lastKey: String? = null

    @Volatile
    private var dir: File? = null

    fun attach(filesDir: File) {
        if (!FieldLogs.enabled) return
        dir = filesDir
    }

    fun emit(message: String) {
        val line = "[TRANSFER_SHADOW] $message build=$BUILD"
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

    fun emit(
        outcome: TransferOutcome,
        reading: TransitReading?,
        board: TransferBoard?,
    ) {
        val request = outcome.request
        val reason = if (request != null) "SPEAK" else outcome.silence?.name ?: "SILENCE"
        if (request == null &&
            (outcome.silence == TransferSilence.INACTIVE ||
                outcome.silence == TransferSilence.ALREADY)
        ) {
            val key = reason
            if (key == lastKey) return
            lastKey = key
            return
        }
        val rows = reading?.rows.orEmpty().joinToString(",") { "${it.line}:${it.eta}" }
        val boardText = board?.let {
            "${it.stationApiName}/${it.line}/${it.direction ?: "-"}"
        } ?: "-"
        val key = listOf(reason, rows, boardText, request?.text).joinToString("|")
        if (key == lastKey) return
        lastKey = key
        emit(
            "decision=$reason kind=${reading?.kind ?: "-"} " +
                "board=$boardText rows=${rows.ifEmpty { "-" }} " +
                "text=${request?.text ?: "-"}",
        )
    }

    fun resetForTest() {
        lastKey = null
    }
}
