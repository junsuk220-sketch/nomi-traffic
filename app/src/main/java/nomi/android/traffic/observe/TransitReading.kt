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
    /** Printed walk minutes, if the tick actually showed them. Not guessed. */
    val walkMinutes: Int? = null,
)

data class TransitRow(
    val line: String,
    val eta: String,
    val occupancy: String? = null,
    val stopsRemaining: Int? = null,
    /** `급행` or `일반` when the source said so. Null leaves the sentence unchanged. */
    val service: String? = null,
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
