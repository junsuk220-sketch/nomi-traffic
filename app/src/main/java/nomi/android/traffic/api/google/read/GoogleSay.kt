package nomi.android.traffic.api.google.read

/** Turns Google's words into spoken Korean. No facts are added. */
object GoogleSay {

    private val clockParts = Regex("""^(오전|오후)\s*(\d{1,2}):(\d{2})$""")
    private val numberedLine = Regex("""^[가-힣]*\d+[A-Za-z0-9-]*$""")

    /** "오후 5:39" -> "오후 5시 39분", "오후 6:00" -> "오후 6시". */
    fun clock(raw: String): String {
        val m = clockParts.matchEntire(raw.trim()) ?: return raw.trim()
        val hour = m.groupValues[2].toInt()
        val minute = m.groupValues[3].toInt()
        val head = "${m.groupValues[1]} ${hour}시"
        return if (minute == 0) head else "$head ${minute}분"
    }

    /** "81" -> "81번"; "3호선" stays. */
    fun line(raw: String): String {
        val t = raw.trim()
        return if (numberedLine.matches(t) && !t.endsWith("번")) "${t}번" else t
    }

    /** Picks 으로 / 로 for the word it follows. */
    fun ro(word: String): String {
        val last = word.trim().lastOrNull() ?: return "${word}로"
        if (last !in '가'..'힣') return "${word}로"
        val jong = (last - '가') % 28
        return if (jong == 0 || jong == 8) "${word}로" else "${word}으로"
    }
}
