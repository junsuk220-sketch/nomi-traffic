package nomi.android.traffic.api.briefing

import nomi.android.traffic.TransitSoonEta
import nomi.android.traffic.observe.TransitRow
import nomi.android.traffic.scope.GuidanceKind

/**
 * Start-briefing letters only. Same wording as the current trip-start lines.
 * Does not invent ETA or walk minutes.
 */
internal object StartBriefingLine {

    fun text(
        kind: GuidanceKind,
        first: TransitRow,
        next: TransitRow?,
        walkMinutes: Int? = null,
    ): String? {
        val line = first.line.trim()
        if (line.isEmpty()) return null
        val vehicle = vehicleClause(kind, line, first.eta, first.service) ?: return null
        val nextTail = next?.let { nextClause(kind, it) }
        val walk = walkMinutes?.takeIf { it > 0 }
        if (walk == null) {
            return if (nextTail == null) vehicle else "$vehicle $nextTail"
        }
        val walkTail = if (kind == GuidanceKind.SUBWAY) {
            "역까지는 걸어서 약 ${walk}분입니다."
        } else {
            "정류장까지는 걸어서 약 ${walk}분입니다."
        }
        return if (nextTail == null) "$vehicle $walkTail" else "$vehicle $walkTail $nextTail"
    }

    private fun vehicleClause(
        kind: GuidanceKind,
        line: String,
        eta: String,
        service: String?,
    ): String? {
        val subway = kind == GuidanceKind.SUBWAY
        val named = namedSubway(line, service)
        if (eta.isBlank()) {
            return if (subway) {
                if (named == line) "$line 열차 도착 예정 정보가 없습니다."
                else "$named 열차 도착 예정 정보가 없습니다."
            } else {
                "${line}번 버스 도착 예정 정보가 없습니다."
            }
        }
        if (TransitSoonEta.matches(eta)) {
            return if (subway) {
                "$named, 곧 도착합니다."
            } else {
                "${line}번, ${line}번 버스, 곧 도착합니다."
            }
        }
        val minutes = TransitSoonEta.minutes(eta) ?: return null
        if (minutes < 1) return null
        return if (subway) {
            if (named == line) "${subwaySubject(line)} ${minutes}분 후 출발합니다."
            else "${named}이 ${minutes}분 후 출발합니다."
        } else {
            "${line}번, ${line}번 버스가 ${minutes}분 후 도착합니다."
        }
    }

    private fun nextClause(kind: GuidanceKind, next: TransitRow): String? {
        val subway = kind == GuidanceKind.SUBWAY
        val service = next.service?.trim().orEmpty()
        if (TransitSoonEta.matches(next.eta)) {
            return if (subway) {
                if (service.isEmpty()) "다음 열차는 곧 도착합니다."
                else "다음 열차는 $service, 곧 도착합니다."
            } else "다음은 ${next.line}번, 곧 도착합니다."
        }
        val minutes = TransitSoonEta.minutes(next.eta) ?: return null
        if (minutes < 1) return null
        return if (subway) {
            if (service.isEmpty()) "다음 열차는 ${minutes}분 후 도착입니다."
            else "다음 열차는 $service, ${minutes}분 후 도착입니다."
        } else {
            "다음은 ${next.line}번, ${minutes}분 후 도착입니다."
        }
    }

    /** Service is spoken only when the source named it. Otherwise the line alone, as before. */
    private fun namedSubway(line: String, service: String?): String {
        val kind = service?.trim().orEmpty()
        return if (kind.isEmpty()) line else "$line $kind"
    }

    private fun subwaySubject(line: String): String =
        if (line.endsWith("선") || line.endsWith("호선")) "${line}이" else "${line}가"
}
