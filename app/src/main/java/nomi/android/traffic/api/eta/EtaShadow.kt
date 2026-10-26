package nomi.android.traffic.api.eta

import nomi.android.traffic.speech.SpeechLedger
import nomi.android.traffic.track.VehicleSnapshot

/**
 * Host for the four ETA APIs. Logs each decision. Does not speak —
 * legacy bus wait is the single voice for 10/5/2/soon.
 */
object EtaShadow {

    private val lock = Any()

    fun tick(
        vehicle: VehicleSnapshot?,
        ledger: SpeechLedger,
        nowMs: Long,
    ) {
        synchronized(lock) {
            EtaShadowDebug.emit("Eta10", Eta10.consider(vehicle, ledger, nowMs), vehicle)
            EtaShadowDebug.emit("Eta5", Eta5.consider(vehicle, ledger, nowMs), vehicle)
            EtaShadowDebug.emit("Eta2", Eta2.consider(vehicle, ledger, nowMs), vehicle)
            EtaShadowDebug.emit("EtaSoon", EtaSoon.consider(vehicle, ledger, nowMs), vehicle)
        }
    }
}
