package nomi.android.traffic

/**
 * Naver took its transit guidance notification away. A 걷기 / 승차 / 정차 card
 * vanishing is the guidance ending, even when the 안내 종료 tap never reached us.
 * Start and end notices are removed while a new guidance opens; they end nothing.
 *
 * Naver also swaps a card by removing it and posting the next one (2026-10-13 18:14:22
 * 걷기 → 버스 승차 1.3 s later; the end wiped the 1500번 wait). So the end is [hold]-ed:
 * a 걷기 / 승차 / 정차 card back within [REPOST_GRACE_MS] cancels it. A start/end notice
 * or a 안내시작 / 안내 종료 tap in that window runs it first ([flush]), as before.
 */
internal object NaverGuidanceRemoved {

    /** Field: the only re-post came after 1.3 s; every other removal was followed by a 시작/종료 notice. */
    const val REPOST_GRACE_MS = 3_000L

    private val notice = Regex("""길\s*안내를\s*(?:시작|종료)""")

    private var pendingEnd: (() -> Unit)? = null
    private var removedAtMs = 0L

    fun endsGuidance(channel: String?, title: String?): Boolean {
        if (channel != NaverMapNotification.CHANNEL_TRANSIT) return false
        val text = title?.trim().orEmpty()
        if (text.isEmpty()) return false
        return !notice.containsMatchIn(text)
    }

    @Synchronized
    fun hold(nowMs: Long, end: () -> Unit) {
        pendingEnd = end
        removedAtMs = nowMs
    }

    /**
     * A Naver notification was posted. True when it was a guidance card that
     * cancelled a held end. A start/end notice runs the held end first.
     */
    fun onPosted(channel: String?, title: String?): Boolean {
        if (channel != NaverMapNotification.CHANNEL_TRANSIT) return false
        val held = synchronized(this) {
            val end = pendingEnd ?: return false
            pendingEnd = null
            end
        }
        if (notice.containsMatchIn(title.orEmpty())) {
            held()
            return false
        }
        return true
    }

    /** Runs a held end now, if any. */
    fun flush() {
        val held = synchronized(this) {
            val end = pendingEnd ?: return
            pendingEnd = null
            end
        }
        held()
    }

    /** Runs a held end once [REPOST_GRACE_MS] passed with no card back. */
    fun expire(nowMs: Long) {
        val due = synchronized(this) { pendingEnd != null && nowMs - removedAtMs >= REPOST_GRACE_MS }
        if (due) flush()
    }

    @Synchronized
    fun isHolding(): Boolean = pendingEnd != null

    @Synchronized
    internal fun resetForTest() {
        pendingEnd = null
        removedAtMs = 0L
    }
}
