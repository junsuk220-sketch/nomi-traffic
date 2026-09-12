package nomi.android.traffic

import nomi.product.nav.NavigationEventSource
import nomi.product.nav.RouteCard
import nomi.product.nav.RouteCardMode
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object RouteCardCopy {

    private const val MINUTE_MS = 60_000L
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun providerLabel(source: NavigationEventSource): String = when (source) {
        NavigationEventSource.NAVER -> "Naver"
        NavigationEventSource.GOOGLE -> "Google"
    }

    fun modeLabel(mode: RouteCardMode): String = when (mode) {
        RouteCardMode.TRANSIT -> "대중교통"
    }

    fun detailLine(card: RouteCard): String =
        "${providerLabel(card.provider)} · ${modeLabel(card.mode)}"

    fun lastUsedLabel(
        atMillis: Long,
        nowMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        val elapsed = nowMillis - atMillis
        if (elapsed < MINUTE_MS) return "방금 전"
        val todayStart = startOfDay(nowMillis, timeZone)
        if (atMillis >= todayStart) {
            val format = SimpleDateFormat("HH:mm", Locale.KOREA)
            format.timeZone = timeZone
            return "오늘 ${format.format(Date(atMillis))}"
        }
        val yesterdayStart = todayStart - DAY_MS
        if (atMillis >= yesterdayStart) return "어제"
        val days = ((todayStart - startOfDay(atMillis, timeZone)) / DAY_MS).toInt()
        return "${days}일 전"
    }

    private fun startOfDay(millis: Long, timeZone: TimeZone): Long {
        val calendar = Calendar.getInstance(timeZone)
        calendar.timeInMillis = millis
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
}
