package nomi.android.traffic

/**
 * Observation tier: records that Naver said we reached the boarding stop
 * (`승차역 부근에 도착했습니다`). Zero occurrences in the 2026-09-15~18 field
 * capture, so it gates nothing and triggers nothing — there is deliberately no
 * way to read this state. Promoting it needs field evidence and a protection
 * test first (constitution 8-1 · 8-2 · 제22원칙).
 * Broad scraps like `역 부근` or `도보 후 열차 승차` fire during the walk.
 */
internal object NaverNearBoardNotice {

    /** Log-once latch: the scrap repeats on every accessibility sweep. */
    @Volatile
    private var recorded = false

    /** @return true the first time this journey sees the real boarding-stop wording. */
    fun note(raw: String?): Boolean {
        if (recorded) return false
        if (!isNearBoard(raw)) return false
        recorded = true
        return true
    }

    fun reset() {
        recorded = false
    }

    internal fun isNearBoard(raw: String?): Boolean {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return false
        if (text.contains("승차정류장 부근")) return true
        if (text.contains("승차역 부근")) return true
        if (text.contains("승착역 부근")) return true
        return false
    }
}

