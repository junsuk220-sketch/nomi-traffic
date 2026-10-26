package nomi.android.traffic.speech

/**
 * The one mouth every spoken line passes through. A cell still decides its own
 * sentence; this only refuses a line that repeats what was just said.
 *
 * Two lines repeat each other when they share the same kind of fact (a walk
 * time, a bus arrival, a train arrival) about the same subject and were spoken
 * close together. Different subjects never collide: a train line and the bus
 * after it may both speak.
 */
object SpeechMouth {

    /** Walk times drift ("3분" then "1분"); arrivals name a line. */
    private val WALK = Regex("""(?:걸어서|도보) 약? ?(\d+)분""")
    private val BUS = Regex("""(\d+[A-Za-z가-힣]?)번""")
    private val TRAIN = Regex("""(\d+호선|[가-힣A-Za-z]+선)""")

    /**
     * @return the line to speak, or null when it only repeats the recent one.
     */
    fun admit(line: String, nowMillis: Long, last: Spoken?): Pair<String, Spoken>? {
        val text = line.trim()
        if (text.isEmpty()) return null
        val fact = factOf(text)
        if (last != null && fact != null && fact.kind == last.fact.kind &&
            fact.subject == last.fact.subject && nowMillis - last.atMillis <= WINDOW_MILLIS
        ) {
            return null
        }
        return text to Spoken(fact ?: Fact(Kind.OTHER, text), nowMillis)
    }

    private fun factOf(text: String): Fact? {
        WALK.find(text)?.let { return Fact(Kind.WALK, "walk") }
        BUS.find(text)?.let { return Fact(Kind.BUS, it.groupValues[1]) }
        TRAIN.find(text)?.let {
            val line = it.groupValues[1]
            val rung = trainRung(text)
            return Fact(Kind.TRAIN, if (rung == null) line else "$line|$rung")
        }
        return null
    }

    /**
     * The first minute in the line is this train. 3~5 share "5", 6~10 share "10",
     * so the next rung is not the same fact. A later "다음 열차" minute is ignored.
     */
    private fun trainRung(text: String): String? {
        val head = text.substringBefore("다음")
        if (SOON.containsMatchIn(head)) return "곧"
        val minutes = TRAIN_MINUTE.find(head)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return when {
            minutes <= 1 -> "곧"
            minutes <= 2 -> "2"
            minutes <= 5 -> "5"
            minutes <= 10 -> "10"
            else -> minutes.toString()
        }
    }

    private val SOON = Regex("""곧""")
    private val TRAIN_MINUTE = Regex("""(\d+)\s*분""")

    enum class Kind { WALK, BUS, TRAIN, OTHER }

    data class Fact(val kind: Kind, val subject: String)

    data class Spoken(val fact: Fact, val atMillis: Long)

    const val WINDOW_MILLIS = 30_000L
}
