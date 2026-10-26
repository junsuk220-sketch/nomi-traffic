package nomi.android.traffic

import nomi.product.nav.NavigationEvent

/**
 * Remembers the last preview trip-start briefing so we can speak it
 * after Naver says "길안내를 시작합니다", even if the live sheet
 * no longer shows walk minutes.
 * A preview older than [MAX_AGE_MS] is not this trip: its arrival minutes are stale
 * (2026-10-11 21:09 spoke the 272번 2분 seen at 19:49).
 */
internal object NaverTripStartCache {

    const val MAX_AGE_MS = 3 * 60_000L

    @Volatile
    private var pending: NavigationEvent? = null

    @Volatile
    private var rememberedAtMs = 0L

    fun remember(event: NavigationEvent, nowMs: Long = System.currentTimeMillis()) {
        pending = event
        rememberedAtMs = nowMs
    }

    fun peek(nowMs: Long = System.currentTimeMillis()): NavigationEvent? {
        val value = pending ?: return null
        if (nowMs - rememberedAtMs > MAX_AGE_MS) return null
        return value
    }

    fun take(nowMs: Long = System.currentTimeMillis()): NavigationEvent? {
        val value = peek(nowMs)
        pending = null
        return value
    }

    fun clear() {
        pending = null
    }
}
