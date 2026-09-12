package nomi.android.traffic

/**
 * Naver Map notification fields we already verified on device.
 * Host-only. Not Navigation Core.
 */
internal object NaverMapNotification {
    const val PACKAGE = "com.nhn.android.nmap"
    const val CHANNEL_WALK = "350_WALK_NAVIGATION"
    const val CHANNEL_TRANSIT = "302_PUBTRANS_POPUP"
    const val TAG = "NAVER_NAV"

    const val EXTRA_NOWBAR_PRIMARY = "android.ongoingActivityNoti.nowbarPrimaryInfo"
    const val EXTRA_CHIP = "android.ongoingActivityNoti.chipExpandedText"
    const val EXTRA_PRIMARY = "android.ongoingActivityNoti.primaryInfo"
    const val EXTRA_SECONDARY = "android.ongoingActivityNoti.secondaryInfo"
    const val EXTRA_NOWBAR_SECONDARY = "android.ongoingActivityNoti.nowbarSecondaryInfo"

    fun isWatchedChannel(channelId: String?): Boolean =
        channelId == CHANNEL_TRANSIT || channelId == CHANNEL_WALK

    fun walkLog(
        id: Int,
        channel: String,
        title: String?,
        text: String?,
        nowbarPrimary: String?,
        chip: String?,
    ): String = buildString {
        appendLine("[NAVER_WALK]")
        appendLine("id=$id")
        appendLine("channel=$channel")
        appendLine("title=${title.orEmpty()}")
        appendLine("action=${nowbarPrimary.orEmpty()}")
        appendLine("distance=${text.orEmpty()}")
        append("chip=${chip.orEmpty()}")
    }

    fun extrasDump(entries: List<Pair<String, String>>): String =
        entries.joinToString(" | ") { (key, value) -> "$key=$value" }

    fun transitLog(
        id: Int,
        channel: String,
        title: String?,
        text: String?,
        nowbarPrimary: String?,
        chip: String?,
        extrasNote: String?,
    ): String = buildString {
        appendLine("[NAVER_TRANSIT]")
        appendLine("id=$id")
        appendLine("channel=$channel")
        appendLine("title=${title.orEmpty()}")
        appendLine("text=${text.orEmpty()}")
        appendLine("nowbarPrimaryInfo=${nowbarPrimary.orEmpty()}")
        appendLine("chipExpandedText=${chip.orEmpty()}")
        append("extras=${extrasNote.orEmpty()}")
    }
}
