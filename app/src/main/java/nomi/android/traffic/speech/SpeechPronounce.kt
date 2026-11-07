package nomi.android.traffic.speech

/**
 * TTS-only rewriting. Cells still write "2650번" / "70-3번"; the mouth turns
 * those into digit speech so the engine does not say "이천육백오십" or drop
 * the "70-" of a hyphenated route.
 *
 * Field 2026-10-30: Event-First logged `70-3번…` while the speaker said
 * `3번…` — the legacy parser had already truncated the line. Pronounce still
 * needs digit speech after that parse is fixed.
 */
object SpeechPronounce {

    /**
     * Bus (and exit) labels ending in 번. Matches Event-First [BUS_ROW] line
     * shape: optional letter, digits, optional `-digits`, optional letter.
     */
    private val ROUTE_BUN =
        Regex("""([A-Za-z]?\d{1,5}(?:-\d+)?[A-Za-z]?)번""")

    private val DIGIT = mapOf(
        '0' to "공",
        '1' to "일",
        '2' to "이",
        '3' to "삼",
        '4' to "사",
        '5' to "오",
        '6' to "육",
        '7' to "칠",
        '8' to "팔",
        '9' to "구",
    )

    /** Text the engine hears. Leaves non-route text alone. */
    fun forTts(text: String): String =
        ROUTE_BUN.replace(text) { match ->
            "${speakRoute(match.groupValues[1])}번"
        }

    /** `2650` → `이육오공`, `70-3` → `칠공다시삼`, `88B` → `팔팔B`. */
    fun speakRoute(route: String): String = buildString {
        for (ch in route) {
            when {
                ch in DIGIT -> append(DIGIT.getValue(ch))
                ch == '-' -> append("다시")
                else -> append(ch)
            }
        }
    }
}
