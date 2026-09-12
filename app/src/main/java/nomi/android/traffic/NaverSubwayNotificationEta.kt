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
    private val busMinutesInParens = Regex("""\(\s*\d+\s*분""")

    fun parse(
        title: String?,
        text: String?,
        nowMillis: Long,
    ): NavigationBusInfo? {
        val head = title?.trim().orEmpty()
        val body = text?.trim().orEmpty()
        if (head.isEmpty() || body.isEmpty()) return null
        if (busMinutesInParens.containsMatchIn(body)) return null
        if (!looksLikeSubway(head, body)) return null
        val line = lineInTitle.find(head)?.groupValues?.get(1)?.trim() ?: return null
        // Naver lists up to three departures; keep this train and the next one.
        val etas = etasAhead(body, nowMillis).take(2)
        if (etas.isEmpty()) return null
        return NavigationBusInfo(
            raw = "$line ${etas.first()} | $body",
            arrivals = etas.map { NavigationBusArrival(line = line, eta = it) },
        )
    }

    private fun looksLikeSubway(title: String, text: String): Boolean {
        if (lineInTitle.containsMatchIn(title)) return true
        if (title.contains("열차") || title.contains("지하철")) return true
        if (text.contains("행 (") && clockInParens.containsMatchIn(text)) return true
        return false
    }

    private fun etasAhead(text: String, nowMillis: Long): List<String> {
        val now = if (nowMillis >= 1_600_000_000_000L) nowMillis else System.currentTimeMillis()
        val minutes = sortedSetOf<Int>()
        for (match in clockInParens.findAll(text)) {
            val hour = match.groupValues[1].toIntOrNull() ?: continue
            val minute = match.groupValues[2].toIntOrNull() ?: continue
            if (hour > 23) continue
            val delta = minutesUntil(now, hour, minute) ?: continue
            if (delta > 120) continue
            minutes.add(delta)
        }
        return minutes.map { if (it <= 1) "곧" else "${it}분" }.distinct()
    }

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
