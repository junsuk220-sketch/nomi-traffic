package nomi.android.traffic.api.eta

import nomi.android.traffic.speech.SpeechLedger
import nomi.android.traffic.track.VehicleKey
import nomi.android.traffic.track.VehicleSnapshot

/** minutes ≤ 1. Own spoken set. Cooldown does not apply. Does not speak in shadow. */
object EtaSoon {
    private val spoken = mutableSetOf<VehicleKey>()

    fun consider(
        vehicle: VehicleSnapshot?,
        ledger: SpeechLedger,
        nowMs: Long,
    ): EtaOutcome = etaConsider(
        band = EtaBand.SOON,
        cooldown = false,
        spoken = spoken,
        vehicle = vehicle,
        ledger = ledger,
        nowMs = nowMs,
    )

    internal fun resetForTest() {
        spoken.clear()
    }
}
