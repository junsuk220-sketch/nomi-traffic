package nomi.android.traffic.api.eta

import nomi.android.traffic.speech.SpeechLedger
import nomi.android.traffic.track.VehicleSnapshot

/**
 * Shadow host only. Calls each ETA API and logs. Does not send [SpeechRequest]
 * to SpeechSink.
 */
object EtaShadow {

    fun tick(
        vehicle: VehicleSnapshot?,
        ledger: SpeechLedger,
        nowMs: Long,
    ) {
        EtaShadowDebug.emit("Eta10", Eta10.consider(vehicle, ledger, nowMs), vehicle)
        EtaShadowDebug.emit("Eta5", Eta5.consider(vehicle, ledger, nowMs), vehicle)
        EtaShadowDebug.emit("Eta2", Eta2.consider(vehicle, ledger, nowMs), vehicle)
        EtaShadowDebug.emit("EtaSoon", EtaSoon.consider(vehicle, ledger, nowMs), vehicle)
    }
}
