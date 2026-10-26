package nomi.android.traffic.api.seoul

/**
 * Seoul TOPIS subwayId → line letter used by Transfer.
 * Only IDs used for matching; unknown IDs are dropped.
 */
internal object SeoulSubwayIds {

    fun lineOf(subwayId: String): String? = when (subwayId.trim()) {
        "1001" -> "1호선"
        "1002" -> "2호선"
        "1003" -> "3호선"
        "1004" -> "4호선"
        "1005" -> "5호선"
        "1006" -> "6호선"
        "1007" -> "7호선"
        "1008" -> "8호선"
        "1009" -> "9호선"
        "1063" -> "경의중앙선"
        "1065" -> "공항철도"
        "1067" -> "경춘선"
        "1075" -> "수인분당선"
        "1077" -> "신분당선"
        "1092" -> "우이신설선"
        "1093" -> "서해선"
        "1032" -> "GTX-A"
        else -> null
    }
}
