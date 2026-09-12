package nomi.android.traffic

import nomi.product.nav.NavigationEvent

/**
 * Holds 빠른 하차 / 빠른 환승 until Naver itself says we arrived at the
 * boarding stop (`승차역 부근에 도착했습니다`).
 * Broad scraps like `역 부근` or `도보 후 열차 승차` fire during the walk.
 */
internal object NaverNearBoardNotice {

    @Volatile
    private var armed = false
    @Volatile
    private var pendingCar: NavigationEvent? = null

    fun isArmed(): Boolean = armed

    fun note(raw: String?): Boolean {
        if (armed) return false
        if (!isNearBoard(raw)) return false
        armed = true
        return true
    }

    fun holdCar(event: NavigationEvent) {
        if (armed) return
        pendingCar = event
    }

    fun takePendingCar(): NavigationEvent? {
        if (!armed) return null
        val event = pendingCar ?: return null
        pendingCar = null
        return event
    }

    fun reset() {
        armed = false
        pendingCar = null
    }

    internal fun isNearBoard(raw: String?): Boolean {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return false
        if (text.contains("승차정류장 부근")) return true
        if (text.contains("승차역 부근")) return true
        if (text.contains("승착역 부근")) return true
        return false
    }
}

