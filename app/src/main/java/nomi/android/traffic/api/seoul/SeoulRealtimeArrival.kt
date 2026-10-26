package nomi.android.traffic.api.seoul

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nomi.android.traffic.TransitSoonEta
import nomi.android.traffic.observe.TransitRow
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Seoul Open Data Plaza realtimeStationArrival.
 * Reads only. Does not speak. Key must never be logged.
 */
object SeoulRealtimeArrival {

    data class Arrival(
        val subwayId: String,
        val line: String,
        val trainLineNm: String,
        val eta: String,
        val service: String? = null,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun fetch(
        stationNm: String,
        apiKey: String,
        startIndex: Int = 0,
        endIndex: Int = 10,
    ): List<Arrival> {
        val key = apiKey.trim()
        val station = stationNm.trim().removeSuffix("역")
        if (key.isEmpty() || station.isEmpty()) return emptyList()
        val encoded = URLEncoder.encode(station, StandardCharsets.UTF_8.name())
        val url =
            "http://swopenAPI.seoul.go.kr/api/subway/$key/json/" +
                "realtimeStationArrival/$startIndex/$endIndex/$encoded"
        val body = httpGet(url) ?: return emptyList()
        return parseJson(body)
    }

    fun rowsFor(
        arrivals: List<Arrival>,
        line: String,
        direction: String? = null,
    ): List<TransitRow> {
        val want = line.trim()
        val pool = if (want.isEmpty()) {
            val lines = arrivals.map { it.line }.distinct()
            if (lines.size != 1) return emptyList()
            arrivals
        } else {
            arrivals.filter { it.line == want }
        }
        val dirStem = directionStem(direction)
        val preferred = if (dirStem == null) {
            pool
        } else {
            val hit = pool.filter { it.trainLineNm.contains(dirStem) }
            if (hit.isEmpty()) pool else hit
        }
        return preferred
            .sortedBy { TransitSoonEta.minutes(it.eta) ?: Int.MAX_VALUE }
            .map { TransitRow(line = it.line, eta = it.eta, service = it.service) }
            .distinctBy { "${it.line}|${it.eta}|${it.service.orEmpty()}" }
    }

    internal fun parseJson(body: String): List<Arrival> {
        val root = try {
            json.decodeFromString(SeoulArrivalResponse.serializer(), body)
        } catch (_: Throwable) {
            return emptyList()
        }
        val out = ArrayList<Arrival>()
        for (row in root.realtimeArrivalList) {
            val subwayId = row.subwayId.trim()
            val line = SeoulSubwayIds.lineOf(subwayId) ?: continue
            val eta = etaOf(arvlMsg2 = row.arvlMsg2, barvlDt = row.barvlDt) ?: continue
            val trainLineNm = row.trainLineNm.trim()
            out.add(
                Arrival(
                    subwayId = subwayId,
                    line = line,
                    trainLineNm = trainLineNm,
                    eta = eta,
                    service = serviceOf(row.btrainSttus, trainLineNm),
                ),
            )
        }
        return out
    }

    internal fun etaOf(arvlMsg2: String?, barvlDt: String?): String? {
        val sec = barvlDt?.trim()?.toIntOrNull()
        if (sec != null && sec > 0) {
            if (sec < 60) return "곧 도착"
            val minutes = (sec + 59) / 60
            return "${minutes}분"
        }
        val msg = arvlMsg2?.trim().orEmpty()
        if (msg.isEmpty()) return null
        if (msg.contains("곧") || msg.contains("진입") ||
            msg.endsWith("도착") || msg.contains(" 도착")
        ) {
            return "곧 도착"
        }
        TransitSoonEta.minutes(msg)?.let { minutes ->
            if (minutes < 1) return "곧 도착"
            return "${minutes}분"
        }
        // Seoul often leaves barvlDt at 0 and only says "N번째 전역".
        // That count is Seoul data; one stop ≈ 2 minutes for speech.
        val stations = nthStation.find(msg)?.groupValues?.get(1)?.toIntOrNull()
        if (stations != null && stations > 0) {
            val minutes = stations * 2
            return "${minutes}분"
        }
        return null
    }

    private val nthStation = Regex("""\[?\s*(\d+)\s*\]?\s*번째\s*전역""")

    /** Only values the API actually wrote. Missing status is not guessed as 일반. */
    internal fun serviceOf(btrainSttus: String?, trainLineNm: String): String? {
        val status = btrainSttus?.trim().orEmpty()
        val named = when {
            status.contains("특급") -> "특급"
            status.contains("급행") || status == "급" -> "급행"
            status.contains("일반") -> "일반"
            else -> null
        }
        if (named != null) return named
        return when {
            trainLineNm.contains("특급") -> "특급"
            trainLineNm.contains("급행") -> "급행"
            else -> null
        }
    }

    private fun directionStem(direction: String?): String? {
        val raw = direction?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return raw
            .removeSuffix("방면")
            .removeSuffix("행")
            .removeSuffix("역")
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4_000
                readTimeout = 4_000
                requestMethod = "GET"
            }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (_: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}

@Serializable
internal data class SeoulArrivalResponse(
    @SerialName("realtimeArrivalList")
    val realtimeArrivalList: List<SeoulArrivalItem> = emptyList(),
)

@Serializable
internal data class SeoulArrivalItem(
    val subwayId: String = "",
    val trainLineNm: String = "",
    val arvlMsg2: String = "",
    val barvlDt: String = "",
    val btrainSttus: String = "",
)
