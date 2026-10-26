package nomi.android.traffic.api.nexttrain

import nomi.android.traffic.TransitSoonEta
import nomi.android.traffic.observe.TransitRow

data class NextTrainOutcome(
    val text: String? = null,
    /** The 곧 train has left and a later train has been soonest twice. */
    val departed: Boolean = false,
    val note: String,
)

/**
 * After the 곧 train leaves the Seoul list, one sentence when the next
 * train is still more than 10 minutes away. Inside 10 minutes it stays
 * quiet and only reports [NextTrainOutcome.departed], so the 10/5/2/곧
 * ladder can speak that train. It does not clear that ladder itself.
 * A later train counts only after it is the soonest on two polls in a row.
 */
object NextTrain {

    private var sawSoon = false
    private var laterStreak = 0
    private var spoken = false

    @Synchronized
    fun onPoll(rows: List<TransitRow>): NextTrainOutcome {
        val minutes = soonestMinutes(rows)
        if (minutes == null) {
            laterStreak = 0
            return NextTrainOutcome(note = "empty")
        }
        if (minutes <= 1) {
            sawSoon = true
            laterStreak = 0
            spoken = false
            return NextTrainOutcome(note = "soon")
        }
        if (!sawSoon) return NextTrainOutcome(note = "no_soon minutes=$minutes")
        if (spoken) return NextTrainOutcome(note = "held minutes=$minutes")
        laterStreak += 1
        if (laterStreak < 2) {
            return NextTrainOutcome(note = "wait minutes=$minutes streak=$laterStreak")
        }
        spoken = true
        laterStreak = 0
        val say = if (minutes > 10) text(minutes) else null
        return NextTrainOutcome(
            text = say,
            departed = true,
            note = if (say == null) "departed minutes=$minutes" else "speak minutes=$minutes",
        )
    }

    @Synchronized
    fun reset() {
        sawSoon = false
        laterStreak = 0
        spoken = false
    }

    /** `다음 열차는 20분, 20분 후 도착입니다.` */
    internal fun text(minutes: Int): String =
        "다음 열차는 ${minutes}분, ${minutes}분 후 도착입니다."

    private fun soonestMinutes(rows: List<TransitRow>): Int? =
        rows.mapNotNull { TransitSoonEta.minutes(it.eta) }.minOrNull()
}
