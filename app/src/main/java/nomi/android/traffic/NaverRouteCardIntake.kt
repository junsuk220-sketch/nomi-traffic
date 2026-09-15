package nomi.android.traffic

import nomi.product.nav.NavigationEventSource
import nomi.product.nav.RouteCard
import nomi.product.nav.RouteCardMode
import nomi.product.nav.RouteCardRepository

/**
 * Records a Naver transit card after live guidance (`안내 중`) with a destination,
 * or a 302 start notification once a destination is known.
 * Search/preview without those signals does not record.
 */
class NaverRouteCardIntake(
    private val repository: RouteCardRepository,
) {
    private val recordedIds = mutableSetOf<Int>()
    private var pendingId: Int? = null
    private var pendingAtMillis: Long = 0L
    private var snapshot: String? = null
    private var recordedLiveKey: String? = null

    fun onScreen(
        destinationLabel: String?,
        liveGuidance: Boolean,
        atMillis: Long,
    ): RouteCard? {
        val dest = destinationLabel?.trim()?.ifEmpty { null }
        if (dest != null) {
            snapshot = dest
        } else if (!liveGuidance) {
            snapshot = null
        }
        if (liveGuidance) return recordLive(atMillis)
        recordedLiveKey = null
        val waiting = pendingId ?: return null
        if (snapshot == null) return null
        return recordFromNotification(waiting, pendingAtMillis.takeIf { it > 0L } ?: atMillis)
    }

    fun onNotificationPosted(
        notificationId: Int,
        atMillis: Long,
        destinationBlobs: List<String?> = emptyList(),
    ): RouteCard? {
        val dest = NaverTransitDestinationParser.destination(
            destinationBlobs.mapNotNull { it?.trim()?.ifEmpty { null } },
        )
        if (dest != null) {
            onScreen(destinationLabel = dest, liveGuidance = false, atMillis = atMillis)
        }
        if (notificationId in recordedIds) return null
        if (snapshot == null) {
            pendingId = notificationId
            pendingAtMillis = atMillis
            return null
        }
        return recordFromNotification(notificationId, atMillis)
    }

    fun onNotificationRemoved(notificationId: Int) {
        recordedIds.remove(notificationId)
        if (pendingId == notificationId) {
            pendingId = null
            pendingAtMillis = 0L
        }
    }

    private fun recordFromNotification(notificationId: Int, atMillis: Long): RouteCard? {
        recordedIds.add(notificationId)
        pendingId = null
        pendingAtMillis = 0L
        return recordLive(atMillis)
    }

    private fun recordLive(atMillis: Long): RouteCard? {
        val dest = snapshot ?: return null
        val key = RouteCard.keyOf(NavigationEventSource.NAVER, dest, RouteCardMode.TRANSIT)
        if (key == recordedLiveKey) return null
        recordedLiveKey = key
        return repository.record(
            provider = NavigationEventSource.NAVER,
            destinationName = dest,
            mode = RouteCardMode.TRANSIT,
            atMillis = atMillis,
        )
    }
}
