package nomi.android.traffic.api.transfer

import nomi.android.traffic.api.briefing.StartBriefingLine
import nomi.android.traffic.observe.TransitRow
import nomi.android.traffic.scope.GuidanceKind

/**
 * Transfer letters only. Minutes use the existing subway start-briefing
 * lines. Does not invent ETA or 빠른 환승.
 */
internal object TransferLine {

    fun text(
        first: TransitRow,
        next: TransitRow?,
    ): String? {
        val base = StartBriefingLine.text(
            kind = GuidanceKind.SUBWAY,
            first = first,
            next = next,
            walkMinutes = null,
        ) ?: return null
        return if (next == null) "$base 다음 열차 정보는 아직 없습니다." else base
    }
}
