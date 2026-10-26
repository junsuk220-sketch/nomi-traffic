package nomi.android.traffic.api.eta

import nomi.android.traffic.speech.SpeechLedger
import nomi.android.traffic.track.VehicleKey
import nomi.android.traffic.track.VehicleSnapshot

/** 2 < minutes ≤ 5. Own spoken set. Host logs only. */
object Eta5 {
    private val spoken = mutableSetOf<VehicleKey>()

    fun consider(
        vehicle: VehicleSnapshot?,
        ledger: SpeechLedger,
        nowMs: Long,
    ): EtaOutcome = etaConsider(
        band = EtaBand.FIVE,
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
