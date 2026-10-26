package nomi.android.traffic.api.google.walk

import nomi.android.traffic.api.google.read.GoogleStep

/**
 * Walk minutes as Google shows them. Speaks when a walk leg first shows,
 * then once when it drops to 5 and to 1. Zero is never spoken.
 */
object GoogleWalk {

    private val marks = listOf(5, 1)
    private var lastSpoken: Int? = null

    @Synchronized
    fun onStep(step: GoogleStep): String? {
        if (step !is GoogleStep.Walk) {
            lastSpoken = null
            return null
        }
        val minutes = step.minutes
        if (minutes < 1) return null
        val last = lastSpoken
        val speak = last == null || marks.any { minutes <= it && last > it }
        if (!speak) return null
        lastSpoken = minutes
        return "걸어서 약 ${minutes}분입니다."
    }

    @Synchronized
    fun reset() {
        lastSpoken = null
    }

    internal fun resetForTest() = reset()
}
