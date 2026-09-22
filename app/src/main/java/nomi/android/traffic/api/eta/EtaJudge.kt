package nomi.android.traffic.api.eta

import nomi.android.traffic.speech.SpeechLedger
import nomi.android.traffic.speech.SpeechRequest
import nomi.android.traffic.track.VehicleKey
import nomi.android.traffic.track.VehicleSnapshot

internal fun etaConsider(
    band: EtaBand,
    cooldown: Boolean,
    spoken: MutableSet<VehicleKey>,
    vehicle: VehicleSnapshot?,
    ledger: SpeechLedger,
    nowMs: Long,
): EtaOutcome {
    if (vehicle == null) return EtaOutcome(silence = EtaSilence.NO_VEHICLE)
    if (!vehicle.settled) return EtaOutcome(silence = EtaSilence.UNSETTLED)
    val minutes = vehicle.etaMinutes ?: return EtaOutcome(silence = EtaSilence.NOT_A_BAND)
    val actual = EtaBand.ofMinutes(minutes) ?: return EtaOutcome(silence = EtaSilence.NOT_A_BAND)
    if (actual != band) return EtaOutcome(silence = EtaSilence.OUT_OF_BAND)
    if (vehicle.vehicleKey in spoken) return EtaOutcome(silence = EtaSilence.ALREADY)
    if (cooldown) {
        val last = ledger.lastSpokenAtMs()
        if (last != null && nowMs - last < EtaBand.COOLDOWN_MS) {
            return EtaOutcome(silence = EtaSilence.COOLDOWN)
        }
    }
    val text = EtaLine.text(vehicle) ?: return EtaOutcome(silence = EtaSilence.NO_TEXT)
    spoken.add(vehicle.vehicleKey)
    return EtaOutcome(request = SpeechRequest(text))
}
