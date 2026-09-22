package nomi.android.traffic.api.eta

import nomi.android.traffic.speech.SpeechLedger
import nomi.android.traffic.track.VehicleKey
import nomi.android.traffic.track.VehicleSnapshot

/** 5 < minutes ≤ 10. Own spoken set. Does not speak in shadow. */
object Eta10 {
    private val spoken = mutableSetOf<VehicleKey>()

    fun consider(
        vehicle: VehicleSnapshot?,
        ledger: SpeechLedger,
        nowMs: Long,
    ): EtaOutcome = etaConsider(
        band = EtaBand.TEN,
        cooldown = true,
        spoken = spoken,
        vehicle = vehicle,
        ledger = ledger,
        nowMs = nowMs,
    )

    internal fun resetForTest() {
        spoken.clear()
    }
}
