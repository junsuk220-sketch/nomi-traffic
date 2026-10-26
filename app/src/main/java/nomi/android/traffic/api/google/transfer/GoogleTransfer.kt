package nomi.android.traffic.api.google.transfer

import nomi.android.traffic.api.google.read.GoogleSay
import nomi.android.traffic.api.google.read.GoogleStep
import java.util.Calendar

/**
 * Speaks once when Google writes "다음 경유지" and names the next line.
 * The number is said once. Minutes come from the guidance screen's
 * "오후 H:MM에 출발" under 환승 for that line. The notification clock is used
 * only when the screen gave none; it can stay on a bus that already left.
 */
object GoogleTransfer {

    private val spoken = HashSet<String>()
    private val screenClocks = HashMap<String, Seen>()
    private val departClock = Regex("""^(오전|오후)\s*\d{1,2}:\d{2}에 출발$""")
    private val lineLabel = Regex("""^\u200b+([^\u200b\s]+)\u200b*$""")

    private data class Seen(val clock: String, val atMillis: Long)

    /** "screen", "notice" or "none" for the last spoken line. Log only. */
    @Volatile
    var lastSource: String = ""
        private set

    @Synchronized
    fun onScreen(blobs: List<String>, live: Boolean, nowMillis: Long) {
        if (!live) return
        val marks = blobs.indices.filter { blobs[it].trim() == "환승" }
        marks.forEachIndexed { n, start ->
            val end = marks.getOrNull(n + 1) ?: blobs.size
            val lineAt = (start + 1 until end).firstOrNull {
                lineLabel.matches(blobs[it].trim('\n', ' '))
            } ?: return@forEachIndexed
            val line = lineLabel.matchEntire(blobs[lineAt].trim('\n', ' '))!!.groupValues[1]
            val clock = (lineAt + 1 until end).firstNotNullOfOrNull { i ->
                blobs[i].trim().takeIf { departClock.matches(it) }
            } ?: return@forEachIndexed
            screenClocks[line] = Seen(clock, nowMillis)
        }
    }

    @Synchronized
    fun onStep(step: GoogleStep, nowMillis: Long): String? {
        if (step !is GoogleStep.Alight || !step.here) return null
        val next = step.nextLine ?: return null
        if (!spoken.add("${step.stop}|$next")) return null
        val screenMinutes = screenClocks[next]
            ?.takeIf { nowMillis - it.atMillis in 0..SCREEN_FRESH_MILLIS }
            ?.let { minutesUntil(it.clock, nowMillis) }
            ?.takeIf { it >= 1 }
        val noticeMinutes = step.nextClock?.let { minutesUntil(it, nowMillis) }?.takeIf { it >= 1 }
        val whenPart = when {
            screenMinutes != null -> " ${screenMinutes}분 후 도착합니다.".also { lastSource = "screen" }
            noticeMinutes != null -> " ${noticeMinutes}분 후 도착합니다.".also { lastSource = "notice" }
            else -> " 도착 시간은 휴대폰 화면에서 확인해 주세요.".also { lastSource = "none" }
        }
        val vehicle = if (next.any { it.isDigit() }) "버스" else "열차"
        return "다음 ${vehicle}는 ${GoogleSay.line(next)}입니다.$whenPart"
    }

    /** Google's clock minus now, rounded up to the next minute. */
    private fun minutesUntil(raw: String, nowMillis: Long): Int? {
        val match = Regex("""(오전|오후)\s*(\d{1,2}):(\d{2})""").find(raw) ?: return null
        val hour24 = match.groupValues[2].toInt() % 12 + if (match.groupValues[1] == "오후") 12 else 0
        val target = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, hour24)
            set(Calendar.MINUTE, match.groupValues[3].toInt())
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis < nowMillis - 12 * 3_600_000L) target.add(Calendar.DAY_OF_YEAR, 1)
        val delta = target.timeInMillis - nowMillis
        return if (delta >= 0) ((delta + 59_999L) / 60_000L).toInt() else -((-delta) / 60_000L).toInt()
    }

    @Synchronized
    fun reset() {
        spoken.clear()
        screenClocks.clear()
        lastSource = ""
    }

    internal fun resetForTest() = reset()

    /** A screen clock older than this is not used. */
    private const val SCREEN_FRESH_MILLIS = 5 * 60_000L
}
