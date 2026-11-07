package nomi.android.traffic.speech

/**
 * The one mouth every spoken line passes through. A cell still decides its own
 * sentence; this only refuses a line that repeats what was just said.
 *
 * A line carries facts: a walk time, a bus arrival, a train arrival. It is a
 * repeat only when EVERY fact in it was already in the line spoken just before
 * (same kind, same subject) and the two were spoken close together. A new bus
 * or train in the line makes it new, even if both lines mention a walk.
 * Field 2026-11-02: "3호선 … 걸어서 10분" swallowed "707번 … 걸어서 6분".
 */
object SpeechMouth {

    /** Walk times drift ("3분" then "1분"); arrivals name a line. */
    private val WALK = Regex("""(?:걸어서|도보) 약? ?(\d+)분""")
    private val BUS = Regex("""([A-Za-z]?\d{1,5}(?:-\d+)?[A-Za-z가-힣]?)번""")
    private val TRAIN = Regex("""(\d+호선|[가-힣A-Za-z]+선)""")

    /**
     * @return the line to speak, or null when it only repeats the recent one.
     */
    fun admit(line: String, nowMillis: Long, last: Spoken?): Pair<String, Spoken>? {
        val text = line.trim()
        if (text.isEmpty()) return null
        val facts = factsOf(text)
        if (last != null) {
            val age = nowMillis - last.atMillis
            if (facts.isNotEmpty()) {
                if (age <= WINDOW_MILLIS && last.facts.containsAll(facts)) return null
            } else if (age <= SAME_TEXT_WINDOW_MILLIS && last.facts.contains(Fact(Kind.OTHER, text))) {
                // No fact to compare: the identical sentence again is a repeat.
                return null
            }
        }
        return text to Spoken(facts.ifEmpty { setOf(Fact(Kind.OTHER, text)) }, nowMillis)
    }

    private fun factsOf(text: String): Set<Fact> {
        val out = LinkedHashSet<Fact>()
        if (WALK.containsMatchIn(text)) out.add(Fact(Kind.WALK, "walk"))
        BUS.findAll(text).forEach { out.add(Fact(Kind.BUS, it.groupValues[1])) }
        // Train only when the line names no bus: a bus stop called "…선" is not a train.
        if (out.none { it.kind == Kind.BUS }) {
            TRAIN.find(text)?.let {
                val line = it.groupValues[1]
                val rung = trainRung(text)
                out.add(Fact(Kind.TRAIN, if (rung == null) line else "$line|$rung"))
            }
        }
        return out
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

    data class Spoken(val facts: Set<Fact>, val atMillis: Long) {
        /** The most specific fact of the line, for logs and tests. */
        val fact: Fact
            get() = facts.firstOrNull { it.kind != Kind.WALK } ?: facts.first()
    }

    const val WINDOW_MILLIS = 30_000L

    /** An identical sentence with no fact in it ("…까지 정류장 24개, 46분 남았습니다."). */
    const val SAME_TEXT_WINDOW_MILLIS = 120_000L
}
