package nomi.android.traffic

import nomi.product.nav.NavigationBusArrival
import nomi.product.nav.NavigationBusInfo
import nomi.product.nav.NavigationEvent
import nomi.product.nav.NavigationEventSource
import nomi.product.nav.NavigationEventType

/**
 * Maps already-extracted Naver Map notification fields to [NavigationEvent].
 * Package and channel ids stay here. No TTS, no Session.
 */
object NaverNotificationParser {

    data class Snapshot(
        val packageName: String,
        val notificationId: Int,
        val channel: String?,
        val title: String? = null,
        val text: String? = null,
        val action: String? = null,
        val chip: String? = null,
        val secondary: String? = null,
        val nowbarSecondary: String? = null,
        val bigText: String? = null,
        val timestampMillis: Long = 0L,
    )

    private val distancePattern = Regex("""(\d+)\s*m""", RegexOption.IGNORE_CASE)
    private val busArrivalPattern =
        Regex("""([A-Za-z]?\d{1,4}[A-Za-z]?)\s*\(([^)]+)\)""")
    private val boardingStopPattern =
        Regex("""^(.+?)\s*(?:까지\s*걷기|도보\s*후\s*(?:버스|지하철)\s*승차|에서\s*승차)$""")

    /**
     * Boarding stop the 302 board belongs to, e.g.
     * `일산동부경찰서(중) 도보 후 버스 승차` → `일산동부경찰서(중)`.
     * A board from another stop is a leftover trip, not this one.
     */
    fun boardingStop(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return boardingStopPattern.matchEntire(text)?.groupValues?.get(1)?.trim()?.ifEmpty { null }
    }

    fun parse(snapshot: Snapshot): NavigationEvent? {
        if (snapshot.packageName != NaverMapNotification.PACKAGE) return null
        val channel = snapshot.channel ?: return null
        return when (channel) {
            NaverMapNotification.CHANNEL_WALK -> walk(snapshot, channel)
            NaverMapNotification.CHANNEL_TRANSIT -> transit(snapshot, channel)
            else -> null
        }
    }

    private fun walk(snapshot: Snapshot, channel: String): NavigationEvent {
        val title = snapshot.title.orEmpty()
        val text = snapshot.text.orEmpty()
        val parts = walkParts(title, snapshot.action)
        return NavigationEvent(
            source = NavigationEventSource.NAVER,
            type = NavigationEventType.WALK,
            notificationId = snapshot.notificationId,
            channel = channel,
            title = title,
            action = parts.action,
            distanceMeters = parseDistanceMeters(text) ?: parseDistanceMeters(snapshot.chip),
            rawText = text,
            busInfo = null,
            timestampMillis = snapshot.timestampMillis,
            landmark = parts.landmark,
        )
    }

    private fun transit(snapshot: Snapshot, channel: String): NavigationEvent {
        val title = snapshot.title.orEmpty()
        val text = snapshot.text.orEmpty()
        val now = snapshot.timestampMillis.takeIf { it >= 1_600_000_000_000L }
            ?: System.currentTimeMillis()
        val busRaw = listOf(
            snapshot.text,
            snapshot.secondary,
            snapshot.nowbarSecondary,
            snapshot.bigText,
            snapshot.chip,
        ).firstOrNull { !it.isNullOrBlank() && busArrivalPattern.containsMatchIn(it) }
        val subwayInfo = if (busRaw == null) {
            listOf(
                snapshot.text,
                snapshot.secondary,
                snapshot.nowbarSecondary,
                snapshot.bigText,
            ).firstNotNullOfOrNull { field ->
                if (field.isNullOrBlank()) null
                else NaverSubwayNotificationEta.parse(
                    title = title,
                    text = field,
                    nowMillis = now,
                )
            }
        } else {
            null
        }
        val busInfo = busRaw?.let { parseBusInfo(it) } ?: subwayInfo
        val raw = when {
            subwayInfo != null -> subwayInfo.raw
            busRaw != null -> busRaw
            else -> text
        }
        return NavigationEvent(
            source = NavigationEventSource.NAVER,
            type = NavigationEventType.TRANSIT,
            notificationId = snapshot.notificationId,
            channel = channel,
            title = title,
            action = snapshot.action?.takeIf { it.isNotBlank() } ?: title,
            distanceMeters = parseDistanceMeters(text) ?: parseDistanceMeters(snapshot.chip),
            rawText = if (subwayInfo != null) NaverMapsTransit.KIND_SUBWAY else raw,
            busInfo = busInfo,
            timestampMillis = now,
        )
    }

    /**
     * Landmark is taken only from the on-device title pattern
     * `{landmark} 방면으로 {action}`. "X에서 오른쪽 방향" is not parsed until
     * that string appears in a walk notification extra.
     */
    internal fun walkParts(title: String, nowbarAction: String?): WalkParts {
        val marker = "방면으로 "
        val idx = title.indexOf(marker)
        val landmark = if (idx > 0) title.substring(0, idx).trim().ifBlank { null } else null
        val fromTitle = if (idx >= 0) title.substring(idx + marker.length).trim() else ""
        val given = nowbarAction?.trim().orEmpty()
        val action = given.ifBlank { fromTitle.ifBlank { title } }
        return WalkParts(landmark = landmark, action = action)
    }

    data class WalkParts(
        val landmark: String?,
        val action: String,
    )

    internal fun parseDistanceMeters(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        return distancePattern.find(raw)?.groupValues?.get(1)?.toIntOrNull()
    }

    internal fun parseBusInfo(raw: String?): NavigationBusInfo? {
        if (raw.isNullOrBlank()) return null
        val arrivals = busArrivalPattern.findAll(raw).map {
            NavigationBusArrival(line = it.groupValues[1], eta = it.groupValues[2])
        }.toList()
        return NavigationBusInfo(raw = raw, arrivals = arrivals)
    }
}
