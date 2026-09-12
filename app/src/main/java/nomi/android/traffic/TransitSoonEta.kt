package nomi.android.traffic

/**
 * Naver sheet/notification uses `곧 도착`; Google live glance uses `곧` / `지금`.
 * Exact `1분` is not soon. Shared only after each provider parser normalizes ETA.
 */
internal object TransitSoonEta {

    private val PATTERN = Regex("""^(곧|지금)(\s*도착)?$|^도착$""")

    fun matches(eta: String): Boolean = PATTERN.matches(eta.trim())

    private val MINUTES = Regex("""(\d+)\s*분""")

    /** Soon is 0. Unknown eta is null. Numeric 1분 stays 1. */
    fun minutes(eta: String): Int? {
        if (matches(eta)) return 0
        return MINUTES.find(eta)?.groupValues?.get(1)?.toIntOrNull()
    }
}
