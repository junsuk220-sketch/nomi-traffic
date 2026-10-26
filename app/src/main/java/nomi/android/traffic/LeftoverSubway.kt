package nomi.android.traffic

import nomi.android.traffic.scope.CurrentGuidanceScope
import nomi.android.traffic.scope.GuidanceKind

/**
 * Leftover subway from a previous trip. Reads Scope only.
 * Does not speak, wait, or change Scope.
 */
internal object LeftoverSubway {

    fun againstBusScope(kind: String?): Boolean {
        if (kind != NaverMapsTransit.KIND_SUBWAY) return false
        val scope = CurrentGuidanceScope.snapshot()
        return scope.active && scope.kind == GuidanceKind.BUS
    }

    fun dropPinIfBusScope(): Boolean {
        val scope = CurrentGuidanceScope.snapshot()
        if (!scope.active || scope.kind != GuidanceKind.BUS) return false
        if (NaverSubwayPin.pinned() == null) return false
        NaverSubwayPin.reset()
        return true
    }
}
