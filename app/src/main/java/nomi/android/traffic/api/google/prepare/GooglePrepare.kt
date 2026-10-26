package nomi.android.traffic.api.google.prepare

import nomi.android.traffic.api.google.read.GoogleStep

/**
 * The stop before alighting. Speaks once when Google's own count is 2.
 * "정류장 2개" on 2026-09-26 20:35. Does not speak at 1, and does not
 * speak the transfer.
 */
object GooglePrepare {

    private val spoken = HashSet<String>()

    @Synchronized
    fun onStep(step: GoogleStep): String? {
        val stop: String
        val left: Int?
        val unit: String?
        when (step) {
            is GoogleStep.Ride -> {
                stop = step.stop; left = step.stopsLeft; unit = step.unit
            }
            is GoogleStep.Alight -> {
                stop = step.stop; left = step.stopsLeft; unit = step.unit
            }
            else -> return null
        }
        if (left != 2) return null
        if (!spoken.add(stop)) return null
        val noun = unit ?: "정류장"
        return "다음 $noun ${stop}에서 하차 준비하세요."
    }

    @Synchronized
    fun reset() {
        spoken.clear()
    }

    internal fun resetForTest() = reset()
}
