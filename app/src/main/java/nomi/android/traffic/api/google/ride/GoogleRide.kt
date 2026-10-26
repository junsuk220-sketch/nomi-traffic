package nomi.android.traffic.api.google.ride

import nomi.android.traffic.api.google.read.GoogleStep

/**
 * Riding toward one stop. Says the count once when the ride shows, then
 * "다음 정류장 X에서 내리세요" once when Google's count reaches 1.
 * Counts and minutes are Google's own.
 */
object GoogleRide {

    private var stop: String? = null
    private var startSpoken = false
    private var prepSpoken = false

    @Synchronized
    fun onStep(step: GoogleStep): String? {
        val target: String
        val left: Int?
        val unit: String?
        val minutes: Int?
        when (step) {
            is GoogleStep.Ride -> {
                target = step.stop; left = step.stopsLeft; unit = step.unit; minutes = step.minutes
            }
            is GoogleStep.Alight -> {
                target = step.stop; left = step.stopsLeft; unit = step.unit; minutes = step.minutes
            }
            else -> {
                stop = null
                return null
            }
        }
        if (stop != target) {
            stop = target
            startSpoken = false
            prepSpoken = false
        }
        left ?: return null
        val noun = unit ?: "정류장"
        if (left <= 1) {
            if (prepSpoken) return null
            prepSpoken = true
            startSpoken = true
            return "다음 $noun ${target}에서 내리세요."
        }
        if (startSpoken || step !is GoogleStep.Ride) return null
        startSpoken = true
        val time = minutes?.let { ", ${it}분" }.orEmpty()
        return "${target}까지 $noun ${left}개$time 남았습니다."
    }

    @Synchronized
    fun reset() {
        stop = null
        startSpoken = false
        prepSpoken = false
    }

    internal fun resetForTest() = reset()
}
