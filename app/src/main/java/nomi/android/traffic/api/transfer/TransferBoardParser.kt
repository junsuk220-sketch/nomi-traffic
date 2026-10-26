package nomi.android.traffic.api.transfer

/**
 * Next subway board remembered from Naver cues before alight.
 * Station is printed without the trailing 역 for Seoul API.
 */
data class TransferBoard(
    val station: String,
    val line: String,
    val direction: String? = null,
    val cars: String? = null,
    /** `급행` when the board was `9호선(급)`. */
    val service: String? = null,
) {
    val stationApiName: String
        get() = station.removeSuffix("역").trim()
}

internal object TransferBoardParser {

    fun parse(cues: List<String>): TransferBoard? {
        var station: String? = null
        var line: String? = null
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
            }
            stationLine.find(text)?.let { match ->
                station = match.groupValues[1].trim()
                line = match.groupValues[2].trim()
            }
            directionToken.find(text)?.groupValues?.get(1)?.trim()?.let { direction = it }
            carsToken.find(text)?.groupValues?.get(1)?.trim()?.let { cars = it }
        }
        val s = station?.takeIf { it.isNotEmpty() } ?: return null
        val l = line?.takeIf { it.isNotEmpty() } ?: return null
        return TransferBoard(
            station = s,
            line = l,
            direction = direction,
            cars = cars,
            service = service,
        )
    }

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
    /**
     * Needs the board phrase. A bare `선릉역 2호선` / `…까지 이동` is the destination's
     * name, not a subway to ride (2026-10-13: 146번 bus trip spoke 2호선 for 20 minutes).
     */
    private val stationLine =
        Regex(
            """([가-힣0-9]+역)\s+((?:\d+호선)|(?:[가-힣A-Za-z0-9]+선))""" +
                """\s*(?:까지\s*걷기|열차\s*승차|도보(?:\s*후)?)""",
        )
    private val directionToken = Regex("""([가-힣0-9]+역)\s*방면""")
    private val carsToken =
        Regex("""빠른\s*(?:하차|환승)\s*[:：]?\s*([0-9]+(?:-[0-9]+)?)""")
}
