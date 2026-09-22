package nomi.android.traffic.api.eta

import nomi.android.traffic.buswait.BusWaitCore

/**
 * Single band map. Eta APIs call this. They do not import each other.
 */
enum class EtaBand {
    TEN,
    FIVE,
    TWO,
    SOON,
    ;

    companion object {
        const val COOLDOWN_MS = BusWaitCore.STAGE_COOLDOWN_MS

        fun ofMinutes(minutes: Int): EtaBand? = when (BusWaitCore.stageForMinutes(minutes)) {
            10 -> TEN
            5 -> FIVE
            2 -> TWO
            1 -> SOON
            else -> null
        }
    }
}
