package nomi.android.traffic

import android.util.Log
import java.io.File

/**
 * Field instrumentation for trip-start tracing, also written to [FILE_NAME]:
 * logcat alone was gone by the time the 2026-10-11 briefings were looked at.
 * Log only — does not change speak / pin / session decisions.
 */
internal object NaverTripStartDebug {

    private const val TAG = "TRIP_DEBUG"
    private const val BUILD = "vc182-trip-confirm"
    const val FILE_NAME = "nomi-tripstart.log"
    const val MAX_FILE_BYTES = 1024L * 1024L

    @Volatile
    private var dir: File? = null

    fun attach(filesDir: File) {
        if (!FieldLogs.enabled) return
        dir = filesDir
    }

    @Volatile
    private var lastStep2Key: String? = null

    @Volatile
    private var lastStep3Key: String? = null

    fun buildMark() = BUILD

    fun step1(
        how: String,
        armed: Boolean,
        session: String,
        due: Boolean,
    ) {
        emit("[TRIP_DEBUG] STEP1 session how=$how armed=$armed $session due=$due build=$BUILD")
    }

    fun step2(
        requireLive: Boolean,
        live: Boolean,
        blobCount: Int,
        hasStart: Boolean,
        hasEnd: Boolean,
        sliceMode: String,
        scopedCount: Int,
        head: String,
        tail: String,
        bus: String,
        subway: String,
        decision: String,
        drop: String? = null,
    ) {
        if (!requireLive) return
        val key = listOf(
            live, blobCount, hasStart, hasEnd, sliceMode, scopedCount, bus, subway, decision, drop,
        ).joinToString("|")
        if (key == lastStep2Key) return
        lastStep2Key = key
        emit(
            "[TRIP_DEBUG] STEP2 decision requireLive=$requireLive live=$live " +
                "blobs=$blobCount hasStart=$hasStart hasEnd=$hasEnd " +
                "sliceMode=$sliceMode scoped=$scopedCount " +
                "head=[$head] tail=[$tail] bus=$bus subway=$subway " +
                "result=$decision drop=${drop.orEmpty()} build=$BUILD",
        )
    }

    fun step3(
        due: Boolean,
        decision: String,
        fromSpeak: Boolean,
        cacheHit: Boolean,
        tripNull: Boolean,
        action: String,
        raw: String,
        spokenAlready: Boolean,
        giveUp: Boolean,
    ) {
        val key = listOf(
            due, decision, fromSpeak, cacheHit, tripNull, action, spokenAlready, giveUp,
        ).joinToString("|")
        if (key == lastStep3Key) return
        lastStep3Key = key
        emit(
            "[TRIP_DEBUG] STEP3 tripEvent due=$due decision=$decision " +
                "fromSpeak=$fromSpeak cacheHit=$cacheHit tripNull=$tripNull " +
                "action=$action raw=$raw spokenAlready=$spokenAlready giveUp=$giveUp " +
                "build=$BUILD",
        )
    }

    fun step4(action: String, raw: String, isTripStart: Boolean) {
        emit(
            "[TRIP_DEBUG] STEP4 voiceOffer action=$action raw=$raw " +
                "isTripStart=$isTripStart build=$BUILD",
        )
    }

    fun step5(
        action: String,
        promoted: Boolean,
        lineNull: Boolean,
        line: String,
        accepted: Boolean,
        drop: String?,
    ) {
        emit(
            "[TRIP_DEBUG] STEP5 speaker action=$action promoted=$promoted " +
                "lineNull=$lineNull line=[$line] accepted=$accepted " +
                "drop=${drop.orEmpty()} build=$BUILD",
        )
    }

    fun step6(text: String, path: String) {
        emit("[TRIP_DEBUG] STEP6 speak path=$path text=[$text] build=$BUILD")
    }

    fun note(msg: String) {
        emit("[TRIP_DEBUG] $msg build=$BUILD")
    }

    private fun emit(message: String) {
        try {
            Log.i(TAG, message)
        } catch (_: Throwable) {
        }
        val folder = dir ?: return
        try {
            val file = File(folder, FILE_NAME)
            if (file.length() > MAX_FILE_BYTES) file.delete()
            file.appendText("${System.currentTimeMillis()} $message\n")
        } catch (_: Throwable) {
        }
    }

    fun clipBlobs(blobs: List<String>, take: Int = 4): Pair<String, String> {
        if (blobs.isEmpty()) return "" to ""
        val head = blobs.take(take).joinToString(" | ")
        val tail = blobs.takeLast(minOf(take, blobs.size)).joinToString(" | ")
        return head to tail
    }
}
