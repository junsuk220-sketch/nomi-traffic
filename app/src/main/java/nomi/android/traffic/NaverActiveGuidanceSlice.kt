package nomi.android.traffic

/**
 * Live Naver route sheet: the selected itinerary is the inclusive DFS
 * span from `안내 중` through `안내 종료`. Alternative cards sit outside
 * that span. Incomplete snapshots return empty rather than the full
 * flatten, so an unselected 99 cannot leak into wait / pin.
 */
internal object NaverActiveGuidanceSlice {

    const val START = "안내 중"
    const val START_COMPACT = "안내중"
    const val END = "안내 종료"
    const val END_COMPACT = "안내종료"

    fun from(blobs: List<String>): List<String> {
        val start = blobs.indexOfFirst { isStart(it) }
        if (start < 0) return emptyList()
        val end = blobs.indices.firstOrNull { it >= start && isEnd(blobs[it]) }
            ?: return emptyList()
        return blobs.subList(start, end + 1)
    }

    fun isStart(blob: String): Boolean = blob == START || blob == START_COMPACT

    fun isEnd(blob: String): Boolean = blob == END || blob == END_COMPACT
}
