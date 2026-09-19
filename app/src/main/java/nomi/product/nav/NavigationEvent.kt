package nomi.product.nav

/**
 * Provider-free snapshot of an external navigation update.
 * Does not drive [NavigationSession] or TTS.
 */
enum class NavigationEventSource {
    NAVER,
    GOOGLE,
}

enum class NavigationEventType {
    WALK,
    TRANSIT,
}

data class NavigationBusArrival(
    val line: String,
    val eta: String,
    val occupancy: String? = null,
    val stopsRemaining: Int? = null,
)

data class NavigationBusInfo(
    val raw: String,
    val arrivals: List<NavigationBusArrival> = emptyList(),
    /** Naver subway 302 clocks, in board order. Empty when the board is relative-only. */
    val clocks: List<String> = emptyList(),
    /** True when the 302 wait row is `(도착)`, not `(곧 도착)`. */
    val arrived: Boolean = false,
)

data class NavigationEvent(
    val source: NavigationEventSource,
    val type: NavigationEventType,
    val notificationId: Int,
    val channel: String,
    val title: String,
    val action: String,
    val distanceMeters: Int?,
    val rawText: String,
    val busInfo: NavigationBusInfo?,
    val timestampMillis: Long,
    /**
     * Place name taken from a verified notification title pattern
     * (`{landmark} 방면으로 {action}`). Null when that pattern is absent.
     * Not a separate extras key.
     */
    val landmark: String? = null,
)
