package nomi.android.traffic.api.google.read

/**
 * One Google Maps guidance notification, read as-is. Clocks stay in Google's own
 * words ("오후 5:39"). Nothing here decides what to say.
 */
sealed interface GoogleStep {
    /** Trip arrival from subText, e.g. "오후 7:01". */
    val arrival: String?

    /** "도보 1분(0m)" / "라페스타.먹자골목 · 오후 5:39 출발". */
    data class Walk(
        val minutes: Int,
        val distance: String?,
        val place: String?,
        val depart: String?,
        override val arrival: String?,
    ) : GoogleStep

    /** "81 노선을 이용하세요." / "풍산역 · 오후 5:39 출발". */
    data class Wait(
        val line: String,
        val stop: String?,
        val depart: String?,
        override val arrival: String?,
    ) : GoogleStep

    /** "81 탑승" / "풍산역 · 오후 5:39 출발". */
    data class Board(
        val line: String,
        val stop: String?,
        val depart: String?,
        override val arrival: String?,
    ) : GoogleStep

    /** "저동중고교까지 이동" / "정류장 5개 · 4분". */
    data class Ride(
        val stop: String,
        val stopsLeft: Int?,
        val unit: String?,
        val minutes: Int?,
        override val arrival: String?,
    ) : GoogleStep

    /** "저동중고교에서 하차" / "정류장 4개 · 2분 · 999(으)로 환승 · 오후 5:56". */
    data class Alight(
        val stop: String,
        val stopsLeft: Int?,
        val unit: String?,
        val minutes: Int?,
        val nextLine: String?,
        val nextClock: String?,
        override val arrival: String?,
        /** Google wrote "다음 경유지". This is the alight stop itself. */
        val here: Boolean = false,
    ) : GoogleStep
}

object GoogleStepReader {

    private val walkTitle = Regex("""^도보\s*(\d+)분\s*(?:\(([^)]*)\))?$""")
    private val waitTitle = Regex("""^(.+?)\s*노선을 이용하세요\.?$""")
    private val boardTitle = Regex("""^(.+?)\s*탑승$""")
    private val rideTitle = Regex("""^(.+?)까지 이동$""")
    private val alightTitle = Regex("""^(.+?)에서 하차$""")
    private val placeDepart = Regex("""^(.+?)\s*·\s*((?:오전|오후)\s*\d{1,2}:\d{2})\s*출발$""")
    private val arrivalText = Regex("""^((?:오전|오후)\s*\d{1,2}:\d{2})\s*도착$""")
    private val clock = Regex("""^(?:오전|오후)\s*\d{1,2}:\d{2}$""")
    private val count = Regex("""^(정류장|역)\s*(\d+)개$""")
    private val minutes = Regex("""^(\d+)분$""")
    private val transfer = Regex("""^(.+?)\(으\)로 환승$""")

    fun read(title: String?, text: String?, subText: String?): GoogleStep? {
        val head = title?.trim().orEmpty()
        if (head.isEmpty()) return null
        val body = text?.trim().orEmpty()
        val arrival = subText?.trim()?.let { arrivalText.matchEntire(it)?.groupValues?.get(1) }

        walkTitle.matchEntire(head)?.let { m ->
            val pd = placeDepart.matchEntire(body)
            return GoogleStep.Walk(
                minutes = m.groupValues[1].toInt(),
                distance = m.groupValues[2].ifBlank { null },
                place = pd?.groupValues?.get(1),
                depart = pd?.groupValues?.get(2),
                arrival = arrival,
            )
        }
        waitTitle.matchEntire(head)?.let { m ->
            val pd = placeDepart.matchEntire(body)
            return GoogleStep.Wait(m.groupValues[1], pd?.groupValues?.get(1), pd?.groupValues?.get(2), arrival)
        }
        alightTitle.matchEntire(head)?.let { m ->
            val parts = segments(body)
            val next = parts.firstNotNullOfOrNull { transfer.matchEntire(it)?.groupValues?.get(1) }
            val nextIndex = parts.indexOfFirst { transfer.matches(it) }
            val nextClock = parts.getOrNull(nextIndex + 1)?.takeIf { nextIndex >= 0 && clock.matches(it) }
            val c = parts.firstNotNullOfOrNull { count.matchEntire(it) }
            return GoogleStep.Alight(
                stop = m.groupValues[1],
                stopsLeft = c?.groupValues?.get(2)?.toInt(),
                unit = c?.groupValues?.get(1),
                minutes = parts.firstNotNullOfOrNull { minutes.matchEntire(it)?.groupValues?.get(1)?.toInt() },
                nextLine = next,
                nextClock = nextClock,
                arrival = arrival,
                here = parts.any { it == "다음 경유지" },
            )
        }
        rideTitle.matchEntire(head)?.let { m ->
            val parts = segments(body)
            val c = parts.firstNotNullOfOrNull { count.matchEntire(it) }
            return GoogleStep.Ride(
                stop = m.groupValues[1],
                stopsLeft = c?.groupValues?.get(2)?.toInt(),
                unit = c?.groupValues?.get(1),
                minutes = parts.firstNotNullOfOrNull { minutes.matchEntire(it)?.groupValues?.get(1)?.toInt() },
                arrival = arrival,
            )
        }
        boardTitle.matchEntire(head)?.let { m ->
            val pd = placeDepart.matchEntire(body)
            return GoogleStep.Board(m.groupValues[1], pd?.groupValues?.get(1), pd?.groupValues?.get(2), arrival)
        }
        return null
    }

    private fun segments(body: String): List<String> =
        body.split("·").map { it.trim() }.filter { it.isNotEmpty() }
}
