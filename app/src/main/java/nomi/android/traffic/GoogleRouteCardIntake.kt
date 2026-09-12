package nomi.android.traffic

import nomi.product.nav.NavigationEventSource
import nomi.product.nav.RouteCard
import nomi.product.nav.RouteCardMode
import nomi.product.nav.RouteCardRepository

/**
 * Records a Google transit card only after live guidance (`한눈에 보기 종료`)
 * and a previously seen `목적지, X` snapshot.
 */
class GoogleRouteCardIntake(
    private val repository: RouteCardRepository,
) {
    private var snapshot: String? = null
    private var recordedLiveKey: String? = null

    fun onScreen(
        destinationLabel: String?,
        liveGuidance: Boolean,
        atMillis: Long,
    ): RouteCard? {
        val dest = destinationLabel?.trim()?.ifEmpty { null }
        if (!liveGuidance) {
            if (dest != null) snapshot = dest
            recordedLiveKey = null
            return null
        }
        val name = snapshot ?: dest ?: return null
        val key = RouteCard.keyOf(NavigationEventSource.GOOGLE, name, RouteCardMode.TRANSIT)
        if (key == recordedLiveKey) return null
        recordedLiveKey = key
        return repository.record(
            provider = NavigationEventSource.GOOGLE,
            destinationName = name,
            mode = RouteCardMode.TRANSIT,
            atMillis = atMillis,
        )
    }
}
