package nomi.android.traffic.api.fastcar

data class FastCarOutcome(
    val text: String? = null,
    val note: String,
)

/**
 * Naver's board sheet (`정발산역 3호선 열차 승차` + `마두역 방면 빠른 하차: 2-4`)
 * arrives when Naver says the boarding station is near. Says Naver's car
 * numbers twice, once per station and line. Own state only.
 */
object FastCar {

    private val spoken = HashSet<String>()

    @Synchronized
    fun onNotification(title: String?, texts: List<String>): FastCarOutcome? {
        val head = title?.trim().orEmpty()
        val all = (listOf(head) + texts).map { it.trim() }
        if (all.any { resetPhrase.containsMatchIn(it) }) {
            spoken.clear()
            return null
        }
        val board = boardTitle.matchEntire(head) ?: return null
        val station = board.groupValues[1].trim()
        val line = board.groupValues[2].trim()
        val cue = texts.firstNotNullOfOrNull { fastCue.find(it) }
            ?: return FastCarOutcome(note = "no_cars station=$station line=$line")
        val kind = cue.groupValues[1]
        val cars = cue.groupValues[2].split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val key = "$station|$line"
        if (!spoken.add(key)) return null
        return FastCarOutcome(
            text = text(kind, cars),
            note = "speak station=$station line=$line kind=$kind cars=${cars.joinToString(",")}",
        )
    }

    @Synchronized
    internal fun resetForTest() {
        spoken.clear()
    }

    /** `빠른 하차 2다시4, 2다시4입니다.` */
    internal fun text(kind: String, cars: List<String>): String {
        val said = cars.joinToString(" 또는 ") { it.replace("-", "다시") }
        return "빠른 $kind $said, ${said}입니다."
    }

    private val boardTitle =
        Regex("""(.+?역)\s*(\d+호선|[가-힣A-Za-z0-9]+선)\s*(?:도보\s*후\s*)?열차\s*승차""")
    private val fastCue =
        Regex("""빠른\s*(하차|환승)\s*[:：]\s*(\d+-\d+(?:\s*,\s*\d+-\d+)*)""")
    private val resetPhrase = Regex("""길\s*안내를\s*(?:시작|종료)""")
}
