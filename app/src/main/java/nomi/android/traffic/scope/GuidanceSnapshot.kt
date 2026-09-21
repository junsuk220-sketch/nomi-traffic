package nomi.android.traffic.scope

/**
 * Read-only view of the route the user actually started.
 * No ETA, no speech, no pin. Callers must not mutate it — copy if they need a
 * local variant; [CurrentGuidanceScope] never hands out a live reference.
 */
data class GuidanceSnapshot(
    val active: Boolean,
    /** Increments each time guidance starts. 0 if none has started yet. */
    val generation: Int,
    val kind: GuidanceKind,
    /** Primary vehicle on the selected route, e.g. `3호선` or `81`. */
    val line: String?,
)

enum class GuidanceKind {
    NONE,
    SUBWAY,
    BUS,
}
