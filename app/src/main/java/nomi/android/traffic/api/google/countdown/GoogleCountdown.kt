package nomi.android.traffic.api.google.countdown

import nomi.android.traffic.api.google.read.GoogleSay
import nomi.android.traffic.api.google.read.GoogleStep
import java.util.Calendar

/**
 * 10 / 5 / 2 / 곧 while waiting. Minutes are Google's own departure clock
 * ("모모월드 · 오후 10:33 출발") minus now. Rungs already passed when the wait
 * is first seen stay silent, so the start briefing is not repeated.
 * Boarding or riding stops the count.
 */
object GoogleCountdown {

    private val rungs = listOf(10, 5, 2, 1)

    private var key: String? = null
    private var line = ""
    private var departAt = 0L
    private val passed = HashSet<Int>()

    val active: Boolean
        @Synchronized get() = key != null

    @Synchronized
    fun onStep(step: GoogleStep, nowMillis: Long): String? {
        when (step) {
            is GoogleStep.Wait -> {
                val clock = step.depart ?: return null
                val at = clockMillis(clock, nowMillis) ?: return null
                val next = "${step.line}|${step.stop.orEmpty()}"
                if (next != key) {
                    key = next
                    line = step.line
                    passed.clear()
                    departAt = at
                    minutesLeft(nowMillis)?.let { left -> rungs.filter { it >= left }.forEach { passed.add(it) } }
                    return null
                }
                departAt = at
                return onTick(nowMillis)
            }
            is GoogleStep.Board, is GoogleStep.Ride, is GoogleStep.Alight -> {
                stop()
                return null
            }
            is GoogleStep.Walk -> return null
        }
    }

    @Synchronized
    fun onTick(nowMillis: Long): String? {
        if (key == null) return null
        val left = minutesLeft(nowMillis) ?: return null
        if (left < -2) {
            stop()
            return null
        }
        val rung = rungs.lastOrNull { left <= it } ?: return null
        if (rung in passed) return null
        rungs.filter { it >= rung }.forEach { passed.add(it) }
        val named = GoogleSay.line(line)
        val minutes = maxOf(left, 1)
        if (named.endsWith("선")) {
            return if (rung == 1) "$named, 곧 도착합니다."
            else "${trainSubject(named)} ${minutes}분 후 도착합니다."
        }
        val bus = if (named.endsWith("번")) named else "${named}번"
        return if (rung == 1) "$bus, $bus 버스, 곧 도착합니다."
        else "$bus, $bus 버스가 ${minutes}분 후 도착해요."
    }

    @Synchronized
    fun reset() = stop()

    private fun stop() {
        key = null
        line = ""
        departAt = 0L
        passed.clear()
    }

    /** `3호선이` / `경의중앙선이`. Same particle as the Naver wait sentence. */
    private fun trainSubject(line: String): String =
        if (line.endsWith("선") || line.endsWith("호선")) "${line}이" else "${line}가"

    private fun minutesLeft(nowMillis: Long): Int? {
        if (departAt <= 0L) return null
        val delta = departAt - nowMillis
        return if (delta >= 0) ((delta + 59_999L) / 60_000L).toInt() else -((-delta) / 60_000L).toInt()
    }

    private fun clockMillis(raw: String, nowMillis: Long): Long? {
        val m = Regex("""(오전|오후)\s*(\d{1,2}):(\d{2})""").find(raw) ?: return null
        val hour24 = m.groupValues[2].toInt() % 12 + if (m.groupValues[1] == "오후") 12 else 0
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, hour24)
            set(Calendar.MINUTE, m.groupValues[3].toInt())
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis < nowMillis - 12 * 3_600_000L) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    internal fun resetForTest() = reset()
}
