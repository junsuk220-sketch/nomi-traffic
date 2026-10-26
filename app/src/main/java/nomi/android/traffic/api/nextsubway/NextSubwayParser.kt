package nomi.android.traffic.api.nextsubway

/**
 * Reads a started guidance screen for the subway after this leg.
 * Ignores alight stations ("삼송역 하차") and route-list cards.
 */
internal object NextSubwayParser {

    data class Parsed(
        val line: String,
        val station: String?,
        val stationExplicit: Boolean,
        val lineExplicit: Boolean,
        val direction: String?,
        val cars: String?,
        val service: String? = null,
    )

    fun parse(cues: List<String>): Parsed? {
        var line = ""
        var lineExplicit = false
        var station: String? = null
        var stationExplicit = false
        var direction: String? = null
        var cars: String? = null
        var service: String? = null
        for (blob in cues) {
            val text = blob.trim()
            if (text.isEmpty()) continue
            lineStation.find(text)?.let { match ->
                line = match.groupValues[1].trim()
                station = match.groupValues[3].trim()
                service = serviceName(match.groupValues[2]) ?: service
                lineExplicit = true
                stationExplicit = true
            }
            stationLine.find(text)?.let { match ->
                station = match.groupValues[1].trim()
                line = match.groupValues[2].trim()
                lineExplicit = true
                stationExplicit = true
            }
            directionToken.find(text)?.groupValues?.get(1)?.trim()?.let { direction = it }
            carsToken.find(text)?.groupValues?.get(1)?.trim()?.let { cars = it }
            if (!lineExplicit && text.contains("환승")) {
                lineToken.find(text)?.groupValues?.get(1)?.trim()?.let { line = it }
            }
        }
        if (!stationExplicit) {
            station = entranceStation(cues) ?: station
        }
        if (line.isEmpty() && station == null && direction == null && cars == null) return null
        return Parsed(
            line = line,
            station = station,
            stationExplicit = stationExplicit,
            lineExplicit = lineExplicit,
            direction = direction,
            cars = cars,
            service = service,
        )
    }

    private fun entranceStation(cues: List<String>): String? {
        var found: String? = null
        for (i in cues.indices) {
            val name = bareStation.matchEntire(cues[i].trim())?.groupValues?.get(1) ?: continue
            val window = cues.subList(i + 1, minOf(cues.size, i + 4))
            if (window.any { exitInstruction.containsMatchIn(it.trim()) }) found = name
        }
        return found
    }

    private val lineToken = Regex("""(\d+호선|[가-힣A-Za-z0-9]+선)""")
    private val lineStation =
        Regex(
            """((?:\d+호선)|(?:[가-힣A-Za-z0-9]+선))(?:\((급행|급)\))?\s+""" +
                """([가-힣0-9]+역)\s*승차""",
        )

    private fun serviceName(raw: String): String? =
        when (raw.trim()) {
            "급", "급행" -> "급행"
            else -> null
        }
    /** Needs the board phrase; a bare `선릉역 2호선` is the destination's name (2026-10-13). */
    private val stationLine =
        Regex(
            """([가-힣0-9]+역)\s+((?:\d+호선)|(?:[가-힣A-Za-z0-9]+선))""" +
                """\s*(?:까지\s*걷기|열차\s*승차|도보(?:\s*후)?)""",
        )
    private val bareStation = Regex("""^([가-힣0-9]+역)$""")
    /** `번 출구까지` / `2번 출구`. A bus stop named `학동역6번출구` is not an exit. */
    private val exitInstruction = Regex("""^(?:\d+\s*)?번\s*출구""")
    private val directionToken = Regex("""([가-힣0-9]+역)\s*방면""")
    private val carsToken =
        Regex("""빠른\s*(?:하차|환승)\s*[:：]?\s*([0-9]+(?:-[0-9]+)?)""")
}
