package nomi.android.traffic

import nomi.product.nav.NavigationEvent

/**
 * Remembers the last preview trip-start briefing so we can speak it
 * after Naver says "길안내를 시작합니다", even if the live sheet
 * no longer shows walk minutes.
 */
internal object NaverTripStartCache {
    @Volatile
    private var pending: NavigationEvent? = null

    fun remember(event: NavigationEvent) {
        pending = event
    }

    fun peek(): NavigationEvent? = pending

    fun take(): NavigationEvent? {
        val value = pending
        pending = null
        return value
    }

    fun clear() {
        pending = null
    }
}
