package nomi.android.traffic.observe

/**
 * One tick of Naver traffic facts. Immutable. No pin, no stage, no speech.
 * Callers must not mutate [rows] — Observation always returns a new list.
 */
data class TransitReading(
    val source: ObservationSource,
    val atMs: Long,
    val kind: TransitKind,
    val stop: String?,
    val rows: List<TransitRow>,
    val raw: String,
)

data class TransitRow(
    val line: String,
    val eta: String,
    val occupancy: String? = null,
    val stopsRemaining: Int? = null,
)

enum class ObservationSource {
    NOTIFICATION,
    SCREEN,
}

enum class TransitKind {
    NONE,
    BUS,
    SUBWAY,
    WALK,
}
