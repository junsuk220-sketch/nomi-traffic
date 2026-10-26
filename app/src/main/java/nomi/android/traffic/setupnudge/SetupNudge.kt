package nomi.android.traffic.setupnudge

/** A setting this app needs that is observed off while a map app is guiding. */
enum class SetupGap { GOOGLE_A11Y, NAVER_A11Y, LISTENER }

/**
 * Decides when to tell the user a needed setting is off. One gap speaks at most
 * once per [quietMillis]; turning it on clears it so a later off speaks again.
 * Touches no guidance state.
 */
class SetupNudge(private val quietMillis: Long = QUIET_MILLIS) {

    private val lastSpoken = HashMap<SetupGap, Long>()

    fun shouldSpeak(gap: SetupGap, off: Boolean, nowMillis: Long): Boolean {
        if (!off) {
            lastSpoken.remove(gap)
            return false
        }
        val last = lastSpoken[gap]
        if (last != null && nowMillis - last < quietMillis) return false
        lastSpoken[gap] = nowMillis
        return true
    }

    fun reset() {
        lastSpoken.clear()
    }

    companion object {
        const val QUIET_MILLIS = 3 * 60 * 60 * 1000L

        fun speech(gap: SetupGap, canTapNotice: Boolean): String {
            val what = when (gap) {
                SetupGap.GOOGLE_A11Y ->
                    "구글 지도 안내를 들으려면 교통음성알림에서 구글 교통을 켜 주세요."
                SetupGap.NAVER_A11Y ->
                    "네이버 지도 안내를 들으려면 교통음성알림에서 네이버 교통을 켜 주세요."
                SetupGap.LISTENER ->
                    "교통음성알림의 알림 접근이 꺼져 있어 안내를 들을 수 없습니다."
            }
            val how = if (canTapNotice) {
                " 알림을 누르면 켜는 방법이 나옵니다."
            } else {
                " 교통음성알림 앱을 열어 주세요."
            }
            return what + how
        }
    }
}
