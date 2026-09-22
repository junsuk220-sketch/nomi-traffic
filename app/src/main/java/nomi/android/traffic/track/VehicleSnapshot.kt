package nomi.android.traffic.track

import nomi.android.traffic.observe.TransitKind

/**
 * One tracked vehicle. Immutable. No stage, no speech.
 * [vehicleKey] changes only when the tracked vehicle is missed or replaced.
 */
data class VehicleSnapshot(
    val vehicleKey: VehicleKey,
    val line: String,
    val kind: TransitKind,
    val eta: String,
    val etaMinutes: Int?,
    val nextVehicle: Boolean,
    val settled: Boolean,
    val stop: String?,
)

data class VehicleKey(
    val generation: Int,
    val kind: TransitKind,
    val line: String,
    val track: Int,
)
