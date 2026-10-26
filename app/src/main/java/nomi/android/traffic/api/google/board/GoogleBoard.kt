package nomi.android.traffic.api.google.board

import nomi.android.traffic.api.google.read.GoogleStep

/**
 * Google's "탑승" notification appears at the stop, before the rider is on the bus.
 * This cell stays wired and stays silent. Naver boarding speech is untouched.
 */
object GoogleBoard {

    @Synchronized
    fun onStep(step: GoogleStep): String? = null

    @Synchronized
    fun reset() = Unit

    internal fun resetForTest() = reset()
}
