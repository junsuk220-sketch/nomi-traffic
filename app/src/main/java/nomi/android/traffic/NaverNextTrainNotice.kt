package nomi.android.traffic

import nomi.product.nav.NavigationEvent

/**
 * One "다음 열차는 N분 후 도착입니다." per guidance.
 * The 302 board often lists the next train a moment after the briefing
 * starts — remember it, then speak once the briefing is out.
 */
internal object NaverNextTrainNotice {

    @Volatile
    private var spoken = false
    @Volatile
    private var pending: NavigationEvent? = null

    fun remember(event: NavigationEvent) {
        if (spoken) return
        if (NavigationEventSpeech.naverNextTrainLine(event) == null) return
        pending = event
    }

    fun takePending(): NavigationEvent? {
        if (spoken) return null
        val event = pending ?: return null
        pending = null
        return event
    }

    fun request(): Boolean {
        if (spoken) return false
        spoken = true
        pending = null
        return true
    }

    fun reset() {
        spoken = false
        pending = null
    }
}
