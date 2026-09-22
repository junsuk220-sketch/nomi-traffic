package nomi.android.traffic.api.eta

import nomi.android.traffic.observe.TransitKind
import nomi.android.traffic.track.VehicleSnapshot

/**
 * Wait sentence only. Same letters as the current bus/subway wait lines.
 * Does not append occupancy or the next vehicle.
 */
internal object EtaLine {

    fun text(vehicle: VehicleSnapshot): String? {
        val minutes = vehicle.etaMinutes ?: return null
        val line = vehicle.line.trim()
        if (line.isEmpty()) return null
        return if (vehicle.kind == TransitKind.SUBWAY) {
            if (minutes <= 1) "$line, 곧 도착합니다."
            else "${subwaySubject(line)} ${minutes}분 후 출발해요."
        } else {
            if (minutes <= 1) "${line}번, ${line}번 버스, 곧 도착합니다."
            else "${line}번, ${line}번 버스가 ${minutes}분 후 도착해요."
        }
    }

    private fun subwaySubject(line: String): String =
        if (line.endsWith("선") || line.endsWith("호선")) "${line}이" else "${line}가"
}
