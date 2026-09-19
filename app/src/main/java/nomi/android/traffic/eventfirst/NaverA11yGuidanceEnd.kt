package nomi.android.traffic.eventfirst

/**
 * Naver's on-screen end toast, as captured on the a11y tree. Exact blob match
 * only — the live-sheet button `안내 종료` is not an end, and neither is a
 * longer sentence that merely contains the phrase.
 *
 * Pure: no Android, no Journey, no state.
 */
internal object NaverA11yGuidanceEnd {

    private const val PHRASE = "길안내를 종료합니다."
    private const val PHRASE_NO_PERIOD = "길안내를 종료합니다"

    fun read(blobs: List<String>): Boolean = blobs.any(::matches)

    fun matches(blob: String): Boolean {
        val text = blob.trim()
        return text == PHRASE || text == PHRASE_NO_PERIOD
    }
}
