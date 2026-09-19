package nomi.android.traffic

import nomi.product.nav.NavigationBusArrival
import nomi.product.nav.NavigationBusInfo
import java.util.Calendar

/**
 * Naver 302 subway wait: title like `정발산역 3호선까지 걷기`,
 * text like `오금행 (16:29), 오금행 (16:34)`.
 * Converts the soonest clock into minutes / 곧 for 10/5/2 stages.
 */
internal object NaverSubwayNotificationEta {

    private val lineInTitle = Regex("""(\d+호선|[가-힣A-Za-z0-9]+선|GTX-[A-Za-z])""")
    private val clockInParens = Regex("""\((\d{1,2}):(\d{2})\)""")
    private val clockOnly = Regex("""(\d{1,2}):(\d{2})""")
    private val relativeMinutesInParens = Regex("""\(\s*(\d+)\s*분\s*\)""")
    private val soonInParens = Regex("""\(\s*곧\s*도착\s*\)""")
    /** Bare `(도착)`. Does not match `(곧 도착)`. */
    private val arrivedInParens = Regex("""\(\s*도착\s*\)""")

    /** Beyond this a departure is timetable reading, not a wait cue. */
    const val WAIT_HORIZON_MINUTES = 120

    fun parse(
        title: String?,
        text: String?,
        nowMillis: Long,
    ): NavigationBusInfo? {
        val head = title?.trim().orEmpty()
        val body = text?.trim().orEmpty()
        if (head.isEmpty() || body.isEmpty()) return null
        if (!looksLikeSubway(head, body)) return null
        val line = lineInTitle.find(head)?.groupValues?.get(1)?.trim() ?: return null
        val clocks = extractClocks(body)
        val arrived = arrivedInParens.containsMatchIn(body)
        // Naver lists up to three departures; keep this train and the next one.
        val etas = etasAhead(body, nowMillis).take(2)
        if (etas.isEmpty()) {
            if (!arrived) return null
            return NavigationBusInfo(
                raw = "$line 도착 | $body",
                arrivals = listOf(NavigationBusArrival(line = line, eta = "도착")),
                clocks = emptyList(),
                arrived = true,
            )
        }
        return NavigationBusInfo(
            raw = "$line ${etas.first()} | $body",
            arrivals = etas.map { NavigationBusArrival(line = line, eta = it) },
            clocks = clocks,
            arrived = arrived,
        )
    }

    private fun looksLikeSubway(title: String, text: String): Boolean {
        if (lineInTitle.containsMatchIn(title)) return true
        if (title.contains("열차") || title.contains("지하철")) return true
        if (text.contains("행 (") &&
            (clockInParens.containsMatchIn(text) ||
                relativeMinutesInParens.containsMatchIn(text) ||
                soonInParens.containsMatchIn(text))
        ) {
            return true
        }
        return false
    }

    private fun extractClocks(text: String): List<String> {
        val out = mutableListOf<String>()
        for (match in clockInParens.findAll(text)) {
            val clock = "${match.groupValues[1]}:${match.groupValues[2]}"
            if (clock !in out) out.add(clock)
        }
        return out
    }

    private fun etasAhead(text: String, nowMillis: Long): List<String> {
        val now = if (nowMillis >= 1_600_000_000_000L) nowMillis else System.currentTimeMillis()
        val minutes = sortedSetOf<Int>()
        for (match in clockInParens.findAll(text)) {
            val hour = match.groupValues[1].toIntOrNull() ?: continue
            val minute = match.groupValues[2].toIntOrNull() ?: continue
            if (hour > 23) continue
            val delta = minutesUntil(now, hour, minute) ?: continue
            if (delta > WAIT_HORIZON_MINUTES) continue
            minutes.add(delta)
        }
        // Clocks win when Naver still prints HH:mm. Relative (N분)/(곧 도착)
        // only fills the wait after that clock line is gone.
        if (minutes.isNotEmpty()) {
            return minutes.map { etaText(it) }.distinct()
        }
        for (match in relativeMinutesInParens.findAll(text)) {
            val delta = match.groupValues[1].toIntOrNull() ?: continue
            if (delta > WAIT_HORIZON_MINUTES) continue
            minutes.add(delta)
        }
        if (soonInParens.containsMatchIn(text)) {
            minutes.add(1)
        }
        return minutes.map { etaText(it) }.distinct()
    }

    /**
     * A single `HH:mm` departure against the moment it was seen: `17:16` read at
     * 17:08:44 is 8. Null when [raw] is not a clock, when the train already left,
     * or when it is further out than [WAIT_HORIZON_MINUTES].
     *
     * Same rules as [etasAhead] — ceiling minutes, a 30s grace before a clock
     * counts as yesterday's, and the 180분 cap in [minutesUntil]. Unlike
     * [etasAhead] this never falls back to the wall clock, so callers that must
     * stay pure can use it.
     */
    internal fun clockMinutesUntil(raw: String, nowMillis: Long): Int? {
        val match = clockOnly.matchEntire(raw.trim()) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour > 23 || minute > 59) return null
        val delta = minutesUntil(nowMillis, hour, minute) ?: return null
        if (delta > WAIT_HORIZON_MINUTES) return null
        return delta
    }

    /** 1분 or less is 곧, which is how the 10 / 5 / 2 / 곧 ladder grades it. */
    internal fun etaText(minutes: Int): String =
        if (minutes <= 1) "곧" else "${minutes}분"

    private fun minutesUntil(nowMillis: Long, hour24: Int, minute: Int): Int? {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val target = cal.clone() as Calendar
        target.set(Calendar.HOUR_OF_DAY, hour24)
        target.set(Calendar.MINUTE, minute)
        target.set(Calendar.SECOND, 0)
        target.set(Calendar.MILLISECOND, 0)
        if (target.timeInMillis + 30_000L < nowMillis) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        val deltaMin = ((target.timeInMillis - nowMillis + 59_999L) / 60_000L).toInt()
        if (deltaMin < 0 || deltaMin > 180) return null
        return deltaMin
    }
}
