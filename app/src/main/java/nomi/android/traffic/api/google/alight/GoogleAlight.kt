package nomi.android.traffic.api.google.alight

import nomi.android.traffic.api.google.read.GoogleStep

/**
 * This stop. Speaks once when Google writes "다음 경유지".
 * 2026-09-26 20:36. Does not say the transfer.
 */
object GoogleAlight {

    private val spoken = HashSet<String>()

    @Synchronized
    fun onStep(step: GoogleStep): String? {
        if (step !is GoogleStep.Alight || !step.here) return null
        if (!spoken.add(step.stop)) return null
        val noun = step.unit ?: "정류장"
        return "이번 $noun ${step.stop}에서 하차하세요."
    }

    @Synchronized
    fun reset() {
        spoken.clear()
    }

    internal fun resetForTest() = reset()
}
