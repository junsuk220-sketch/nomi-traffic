package nomi.android.traffic

import nomi.product.nav.NavigationEvent
import nomi.product.nav.NavigationEventSource
import nomi.product.nav.NavigationEventType

/**
 * After Naver's nameless 전역 cue, speak the alight stop once.
 * Name comes from the itinerary `{정류장|역} 하차` row — not the destination.
 * One speak per stop, so a later bus leg can speak after a subway 전역.
 */
internal object NaverPrepareAlight {

    private val alightRow = Regex("""^(?!빠른 하차|빠른 환승)(.+?)\s*하차$""")

    @Volatile
    private var stop: String? = null
    @Volatile
    private var prepareSeen = false
    @Volatile
    private var spokenFor: String? = null

    fun noteStop(raw: String?) {
        val name = alightStop(raw) ?: return
        rememberStop(name)
    }

    fun noteStops(blobs: List<String>) {
        for (i in blobs.indices) {
            val oneLine = alightStop(blobs[i])
            if (oneLine != null) {
                rememberStop(oneLine)
                continue
            }
            if (blobs[i].trim() != "하차" || i == 0) continue
            val prev = blobs[i - 1].trim()
            if (prev.isEmpty() || prev.contains("빠른") || prev.endsWith("승차")) continue
            rememberStop(prev)
        }
    }

    fun noteCue(raw: String?) {
        if (!isPrepareCue(raw)) return
        prepareSeen = true
    }

    fun readyEvent(timestampMillis: Long = 0L): NavigationEvent? {
        if (!prepareSeen) return null
        val name = stop ?: return null
        if (spokenFor == name) return null
        spokenFor = name
        prepareSeen = false
        val subway = name.endsWith("역")
        return NavigationEvent(
            source = NavigationEventSource.NAVER,
            type = NavigationEventType.TRANSIT,
            notificationId = 0,
            channel = NaverMapsTransit.CHANNEL,
            title = name,
            action = NaverMapsTransit.PREPARE_ALIGHT_ACTION,
            distanceMeters = null,
            rawText = if (subway) NaverMapsTransit.KIND_SUBWAY else NaverMapsTransit.KIND_BUS,
            busInfo = null,
            timestampMillis = timestampMillis,
            landmark = name,
        )
    }

    fun reset() {
        stop = null
        prepareSeen = false
        spokenFor = null
    }

    internal fun alightStop(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val name = alightRow.matchEntire(text)?.groupValues?.get(1)?.trim().orEmpty()
        if (name.isEmpty()) return null
        if (name.contains("빠른")) return null
        return name
    }

    internal fun isPrepareCue(raw: String?): Boolean {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return false
        if (text == "전역" || text == "전역입니다" || text == "전역입니다.") return true
        return text.contains("전역입니다")
    }

    private fun rememberStop(name: String) {
        val current = stop
        if (current == null || current == spokenFor) {
            stop = name
        }
    }
}
