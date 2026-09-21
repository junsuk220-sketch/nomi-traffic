package nomi.android.traffic.speech

/**
 * Facts about what [SpeechSink] already played. Read-only to APIs.
 * Not a judgment store — no ETA, no pin, no gate.
 */
class SpeechLedger {

    data class Entry(
        val text: String,
        val atMs: Long,
    )

    @Volatile
    private var last: Entry? = null

    fun lastSpokenAtMs(): Long? = last?.atMs

    fun lastText(): String? = last?.text

    internal fun record(text: String, atMs: Long) {
        last = Entry(text = text, atMs = atMs)
    }

    internal fun resetForTest() {
        last = null
    }
}
