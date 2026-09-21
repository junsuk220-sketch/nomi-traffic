package nomi.android.traffic

import nomi.product.nav.NavigationBusArrival
import nomi.product.nav.NavigationBusInfo
import nomi.product.nav.NavigationEvent
import nomi.product.nav.NavigationEventSource
import nomi.product.nav.NavigationEventType
import java.util.Calendar

/**
 * Naver trip-start briefing after live guidance begins
 * (Naver itself says "길안내를 시작합니다").
 * Same spoken shape as Google: first vehicle ETA + walk minutes.
 *
 * Preview sheet (안내시작) and live sheet (안내 중) both work.
 * Live often splits walk as `도보 751m` + `11분`, and ETA as `17:08`.
 */
object NaverTripStartParser {

    sealed class Decision {
        data class Speak(val event: NavigationEvent) : Decision()
        data object Pending : Decision()
    }

    private val formatChars = Regex("""[\u200b\u200c\u200d\ufeff]""")
    private val walkAboutPattern = Regex("""도보\s*약\s*(\d+)\s*분""")
    private val walkMinutesPattern = Regex("""도보\s*(\d+)\s*분""")
    private val walkMetersMinutesPattern =
        Regex("""도보\s*\d+\s*m\s*[·•.\-]?\s*(\d+)\s*분""", RegexOption.IGNORE_CASE)
    private val walkMetersOnlyPattern = Regex("""도보\s*\d+\s*m""", RegexOption.IGNORE_CASE)
    private val bareMinutesPattern = Regex("""^(\d+)\s*분$""")
    private val waitDepartPattern = Regex("""출발\s*대기\s*(\d+)\s*분""")
    private val subwayBoardPattern =
        Regex("""(\d+호선|[가-힣A-Za-z0-9]+선|GTX-[A-Za-z]).*승차""")
    private val subwayUntilPattern =
        Regex("""(\d+호선|[가-힣A-Za-z0-9]+선|GTX-[A-Za-z]).*까지\s*(걷기|승차)""")
    private val subwayLineLabelPattern =
        Regex("""^(\d+호선|[가-힣A-Za-z0-9]+선|GTX-[A-Za-z])$""")
    private val clockAmPmPattern = Regex("""(?:오전|오후)\s*(\d{1,2}):(\d{2})""")
    private val clockInText = Regex("""(\d{1,2}):(\d{2})""")
    private val startPhrasePattern = Regex("""길\s*안내를\s*시작""")
    private val rideMinutesNearPattern = Regex("""\d+\s*개\s*역""")
    private val listedBusLine = Regex("""^[A-Za-z]?\d{1,4}[A-Za-z]?$""")
    private val trainBoundBlob = Regex("""^[^,\s()]+행$""")
    private const val NO_ARRIVAL_INFO = "도착 예정 정보 없음"

    fun isStartPhrase(raw: String?): Boolean {
        val text = clean(raw)
        return text.isNotEmpty() && startPhrasePattern.containsMatchIn(text)
    }

    fun isStartButton(raw: String?): Boolean = clean(raw) == "안내시작"

    fun isEndButton(raw: String?): Boolean = clean(raw).let {
        it == "안내종료" || it == "안내 종료"
    }

    /**
     * @param requireLive when true, needs `안내 중`. When false, needs preview `안내시작`.
     */
    fun decision(
        packageName: String?,
        root: NaverSubwayAccessibilityParser.Node,
        timestampMillis: Long = 0L,
        requireLive: Boolean = true,
    ): Decision {
        if (packageName != NaverMapNotification.PACKAGE) {
            return Decision.Pending.also {
                if (requireLive) {
                    NaverTripStartDebug.step2(
                        requireLive = true,
                        live = false,
                        blobCount = 0,
                        hasStart = false,
                        hasEnd = false,
                        sliceMode = "none",
                        scopedCount = 0,
                        head = "",
                        tail = "",
                        bus = "-",
                        subway = "-",
                        decision = "Pending",
                        drop = "wrong_package",
                    )
                }
            }
        }
        val blobs = flatten(root).map(::clean).filter { it.isNotEmpty() }
        val live = blobs.any { it == "안내 중" }
        val preview = blobs.any { it == "안내시작" }
        val hasStart = blobs.any { NaverActiveGuidanceSlice.isStart(it) }
        val hasEnd = blobs.any { NaverActiveGuidanceSlice.isEnd(it) }
        var sliceMode = "none"
        val scoped = if (requireLive) {
            if (!live) {
                NaverTripStartDebug.step2(
                    requireLive = true,
                    live = false,
                    blobCount = blobs.size,
                    hasStart = hasStart,
                    hasEnd = hasEnd,
                    sliceMode = "none",
                    scopedCount = 0,
                    head = "",
                    tail = "",
                    bus = "-",
                    subway = "-",
                    decision = "Pending",
                    drop = "not_live",
                )
                return Decision.Pending
            }
            // Prefer 안내 중..안내 종료. At guidance start the end marker is often
            // still missing; use 안내 중..EOF so trip-start can still brief without
            // falling back to the full flatten (alts before 안내 중 stay out).
            val slice = NaverActiveGuidanceSlice.from(blobs)
            if (slice.isNotEmpty()) {
                sliceMode = "active_slice"
                slice
            } else {
                val start = blobs.indexOfFirst { NaverActiveGuidanceSlice.isStart(it) }
                if (start < 0) {
                    NaverTripStartDebug.step2(
                        requireLive = true,
                        live = true,
                        blobCount = blobs.size,
                        hasStart = hasStart,
                        hasEnd = hasEnd,
                        sliceMode = "none",
                        scopedCount = 0,
                        head = "",
                        tail = "",
                        bus = "-",
                        subway = "-",
                        decision = "Pending",
                        drop = "no_start_marker",
                    )
                    return Decision.Pending
                }
                sliceMode = "start_to_eof"
                blobs.subList(start, blobs.size)
            }
        } else {
            if (!preview || live) {
                return Decision.Pending
            }
            sliceMode = "preview_full"
            blobs
        }
        val walkMinutes = walkMinutes(scoped)
        if (walkMinutes == null && !requireLive) return Decision.Pending
        val walk = walkMinutes ?: 0
        val bus = firstBus(scoped)
        val subway = firstSubway(scoped)
        val noEtaBusLine = firstBusLineWithoutEta(scoped)
        val busLabel = when {
            noEtaBusLine != null -> "$noEtaBusLine(noEta)"
            bus != null -> bus.joinToString(",") { "${it.line}:${it.eta}" }
            else -> "-"
        }
        val subwayLabel = subway ?: "-"
        fun finish(result: Decision, drop: String? = null): Decision {
            if (requireLive) {
                val (head, tail) = NaverTripStartDebug.clipBlobs(scoped)
                NaverTripStartDebug.step2(
                    requireLive = true,
                    live = live,
                    blobCount = blobs.size,
                    hasStart = hasStart,
                    hasEnd = hasEnd,
                    sliceMode = sliceMode,
                    scopedCount = scoped.size,
                    head = head,
                    tail = tail,
                    bus = busLabel,
                    subway = subwayLabel,
                    decision = when (result) {
                        is Decision.Speak -> "Speak:${result.event.rawText}"
                        Decision.Pending -> "Pending"
                    },
                    drop = drop,
                )
            }
            return result
        }
        val busFirst = bus?.minWithOrNull(
            compareBy { TransitSoonEta.minutes(it.eta) ?: Int.MAX_VALUE },
        )
        val busLineForOrder = noEtaBusLine ?: busFirst?.line
        val preferBus = when {
            busLineForOrder != null && subway != null ->
                indexOfBusSeed(scoped, busLineForOrder) <= indexOfSubwaySeed(scoped, subway)
            busLineForOrder != null -> true
            subway != null -> false
            else -> return finish(Decision.Pending, "no_bus_no_subway")
        }
        if (preferBus) {
            if (noEtaBusLine != null) {
                val own = bus.orEmpty().filter { it.line == noEtaBusLine }
                if (own.isEmpty()) {
                    return finish(
                        Decision.Speak(
                            tripStartEvent(
                                arrivals = listOf(
                                    NavigationBusArrival(line = noEtaBusLine, eta = ""),
                                ),
                                walkMinutes = walk,
                                kind = NaverMapsTransit.KIND_BUS,
                                timestampMillis = timestampMillis,
                            ),
                        ),
                    )
                }
            }
            if (bus != null) {
                return finish(
                    Decision.Speak(
                        tripStartEvent(
                            arrivals = bus,
                            walkMinutes = walk,
                            kind = NaverMapsTransit.KIND_BUS,
                            timestampMillis = timestampMillis,
                        ),
                    ),
                )
            }
        }
        if (subway != null) {
            val noEtaSubway = !preferBus && subwayHasNoEta(scoped, subway)
            val subwayArrivals = if (noEtaSubway) {
                emptyList()
            } else {
                subwayArrivals(
                    line = subway,
                    blobs = scoped,
                    timestampMillis = timestampMillis,
                    walkMinutes = walk,
                )
            }
            if (subwayArrivals.isNotEmpty()) {
                return finish(
                    Decision.Speak(
                        tripStartEvent(
                            arrivals = subwayArrivals,
                            walkMinutes = walk,
                            kind = NaverMapsTransit.KIND_SUBWAY,
                            timestampMillis = timestampMillis,
                        ),
                    ),
                )
            }
            if (!preferBus) {
                return finish(
                    Decision.Speak(
                        tripStartEvent(
                            arrivals = listOf(NavigationBusArrival(line = subway, eta = "")),
                            walkMinutes = walk,
                            kind = NaverMapsTransit.KIND_SUBWAY,
                            timestampMillis = timestampMillis,
                            bound = firstTrainBound(scoped),
                        ),
                    ),
                )
            }
        }
        if (bus != null) {
            return finish(
                Decision.Speak(
                    tripStartEvent(
                        arrivals = bus,
                        walkMinutes = walk,
                        kind = NaverMapsTransit.KIND_BUS,
                        timestampMillis = timestampMillis,
                    ),
                ),
            )
        }
        return finish(Decision.Pending, "fallthrough")
    }

    /** 302 wait board that arrived while trip-start is still pending. */
    fun asTripStart(event: NavigationEvent): NavigationEvent? {
        if (event.action == NaverMapsTransit.TRIP_START_ACTION) return null
        val arrivals = event.busInfo?.arrivals.orEmpty()
            .filter { it.line.isNotBlank() && it.eta.isNotBlank() }
        if (arrivals.isEmpty()) return null
        val kind = if (event.rawText == NaverMapsTransit.KIND_SUBWAY) {
            NaverMapsTransit.KIND_SUBWAY
        } else {
            NaverMapsTransit.KIND_BUS
        }
        return tripStartEvent(
            arrivals = arrivals,
            walkMinutes = 0,
            kind = kind,
            timestampMillis = event.timestampMillis,
        )
    }

    /** First blob index that looks like this bus line seed. */
    private fun indexOfBusSeed(blobs: List<String>, line: String): Int {
        val exact = blobs.indexOfFirst { it == line || it == "${line}번" }
        if (exact >= 0) return exact
        return blobs.indexOfFirst { it.startsWith(line) && (it.length == line.length || !it[line.length].isDigit()) }
            .takeIf { it >= 0 } ?: Int.MAX_VALUE
    }

    private fun indexOfSubwaySeed(blobs: List<String>, line: String): Int {
        blobs.forEachIndexed { index, blob ->
            if (subwayBoardPattern.find(blob)?.groupValues?.get(1)?.trim() == line) return index
            if (subwayUntilPattern.find(blob)?.groupValues?.get(1)?.trim() == line) return index
            if (subwayLineLabelPattern.matchEntire(blob.trim())?.groupValues?.get(1)?.trim() == line) {
                return index
            }
        }
        return Int.MAX_VALUE
    }

    private fun tripStartEvent(
        arrivals: List<NavigationBusArrival>,
        walkMinutes: Int,
        kind: String,
        timestampMillis: Long,
        bound: String? = null,
    ): NavigationEvent {
        val first = arrivals.minWithOrNull(
            compareBy<NavigationBusArrival> {
                TransitSoonEta.minutes(it.eta) ?: Int.MAX_VALUE
            }.thenBy { it.stopsRemaining ?: Int.MAX_VALUE },
        ) ?: arrivals.first()
        val packed = listOfNotNull(
            first,
            NaverNextVehicle.afterSoonest(arrivals, first),
        )
        val boundTail = bound?.trim().orEmpty().let { if (it.isEmpty()) "" else " $it" }
        return NavigationEvent(
            source = NavigationEventSource.NAVER,
            type = NavigationEventType.TRANSIT,
            notificationId = 0,
            channel = NaverMapsTransit.CHANNEL,
            title = walkMinutes.toString(),
            action = NaverMapsTransit.TRIP_START_ACTION,
            distanceMeters = walkMinutes,
            rawText = kind,
            busInfo = NavigationBusInfo(
                raw = packed.joinToString(" ") { "${it.line} ${it.eta}" } +
                    " walk=$walkMinutes$boundTail",
                arrivals = packed,
            ),
            timestampMillis = timestampMillis,
            landmark = null,
        )
    }

    private fun walkMinutes(blobs: List<String>): Int? {
        blobs.firstNotNullOfOrNull {
            walkAboutPattern.find(it)?.groupValues?.get(1)?.toIntOrNull()
        }?.let { return it }
        blobs.firstNotNullOfOrNull {
            walkMetersMinutesPattern.find(it)?.groupValues?.get(1)?.toIntOrNull()
        }?.let { return it }
        blobs.firstNotNullOfOrNull {
            walkMinutesPattern.find(it)?.groupValues?.get(1)?.toIntOrNull()
        }?.let { return it }
        for (i in blobs.indices) {
            if (!walkMetersOnlyPattern.containsMatchIn(blobs[i])) continue
            for (j in (i + 1)..minOf(i + 4, blobs.lastIndex)) {
                bareMinutesPattern.matchEntire(blobs[j])?.groupValues?.get(1)
                    ?.toIntOrNull()
                    ?.takeIf { it in 1..120 }
                    ?.let { return it }
            }
        }
        return null
    }

    /**
     * A listed bus row that Naver printed without minutes — not an ETA guess.
     * The first such line is the current wait when it appears before subway.
     */
    private fun firstTrainBound(blobs: List<String>): String? =
        blobs.firstOrNull { trainBoundBlob.matches(it) }

    /** `도착 예정 정보 없음` on this subway board, before a later bus row. */
    private fun subwayHasNoEta(blobs: List<String>, subway: String): Boolean {
        val from = indexOfSubwaySeed(blobs, subway)
        if (from == Int.MAX_VALUE) return false
        val until = blobs.indices.firstOrNull { i ->
            i > from && listedBusLine.matches(blobs[i])
        } ?: blobs.size
        return blobs.subList(from, until).any { it.contains(NO_ARRIVAL_INFO) }
    }

    private fun firstBusLineWithoutEta(blobs: List<String>): String? {
        for (i in blobs.indices) {
            if (!listedBusLine.matches(blobs[i])) continue
            val window = blobs.subList(i + 1, minOf(blobs.size, i + 8))
            if (window.any { it.contains(NO_ARRIVAL_INFO) }) return blobs[i]
        }
        return null
    }

    private fun firstBus(blobs: List<String>): List<NavigationBusArrival>? {
        val arrivals = NaverBusAccessibilityParser.arrivalsFromBlobs(blobs)
        if (arrivals.isEmpty()) return null
        return arrivals
    }

    private fun firstSubway(blobs: List<String>): String? {
        blobs.firstNotNullOfOrNull {
            subwayBoardPattern.find(it)?.groupValues?.get(1)?.trim()
        }?.let { return it }
        blobs.firstNotNullOfOrNull {
            subwayLineLabelPattern.matchEntire(it.trim())?.groupValues?.get(1)?.trim()
        }?.let { return it }
        return blobs.firstNotNullOfOrNull {
            subwayUntilPattern.find(it)?.groupValues?.get(1)?.trim()
        }
    }

    private fun subwayArrivals(
        line: String,
        blobs: List<String>,
        timestampMillis: Long,
        walkMinutes: Int,
    ): List<NavigationBusArrival> {
        val clockEtas = clockEtasMinutes(blobs, timestampMillis).map { etaLabel(it) }
        val labelEtas = subwayMinuteLabels(blobs, walkMinutes)
        val etas = when {
            clockEtas.size >= 2 -> clockEtas.take(2)
            clockEtas.size == 1 -> listOfNotNull(
                clockEtas.first(),
                labelEtas.firstOrNull { it != clockEtas.first() },
            )
            labelEtas.isNotEmpty() -> labelEtas.take(2)
            else -> listOfNotNull(vehicleEta(blobs, timestampMillis, walkMinutes, preferSubway = true))
        }
        return etas.map { NavigationBusArrival(line = line, eta = it) }
    }

    private fun etaLabel(minutes: Int): String = if (minutes <= 1) "곧" else "${minutes}분"

    private fun subwayMinuteLabels(blobs: List<String>, walkMinutes: Int): List<String> {
        val rideIdx = blobs.indexOfFirst { rideMinutesNearPattern.containsMatchIn(it) }
        val minutes = ArrayList<Int>()
        for ((index, blob) in blobs.withIndex()) {
            if (rideIdx >= 0 && kotlin.math.abs(index - rideIdx) <= 2) continue
            val value = bareMinutesPattern.matchEntire(blob)?.groupValues?.get(1)?.toIntOrNull()
                ?: continue
            if (value == walkMinutes) continue
            if (value in 1..120) minutes.add(value)
        }
        return minutes.distinct().map(::etaLabel)
    }

    private fun vehicleEta(
        blobs: List<String>,
        timestampMillis: Long,
        walkMinutes: Int,
        preferSubway: Boolean,
    ): String? {
        blobs.firstNotNullOfOrNull {
            waitDepartPattern.find(it)?.groupValues?.get(1)?.toIntOrNull()
        }?.let { return "${it}분" }
        clockEtasMinutes(blobs, timestampMillis).firstOrNull()?.let { return etaLabel(it) }
        if (preferSubway) {
            // Avoid ride-duration minutes next to "N개 역 이동".
            val rideIdx = blobs.indexOfFirst { rideMinutesNearPattern.containsMatchIn(it) }
            for ((index, blob) in blobs.withIndex()) {
                if (rideIdx >= 0 && kotlin.math.abs(index - rideIdx) <= 2) continue
                val minutes = bareMinutesPattern.matchEntire(blob)?.groupValues?.get(1)?.toIntOrNull()
                    ?: continue
                if (minutes == walkMinutes) continue
                if (minutes in 1..120) return "${minutes}분"
            }
            return null
        }
        blobs.firstOrNull { TransitSoonEta.matches(it) }?.let { return it }
        return null
    }

    private fun clockEtasMinutes(blobs: List<String>, nowMillis: Long): List<Int> {
        if (nowMillis <= 0L) return emptyList()
        val now = if (nowMillis >= 1_600_000_000_000L) nowMillis else System.currentTimeMillis()
        val minutes = sortedSetOf<Int>()
        for (blob in blobs) {
            if (isTimeRangeBlob(blob)) continue
            if (clockAmPmPattern.containsMatchIn(blob)) {
                for (match in clockAmPmPattern.findAll(blob)) {
                    val hour12 = match.groupValues[1].toIntOrNull() ?: continue
                    val minute = match.groupValues[2].toIntOrNull() ?: continue
                    val afternoon = blob.contains("오후")
                    minutesUntil(now, hour12 % 12 + if (afternoon) 12 else 0, minute)
                        ?.let { minutes.add(it) }
                }
                continue
            }
            for (match in clockInText.findAll(blob)) {
                val hour = match.groupValues[1].toIntOrNull() ?: continue
                val minute = match.groupValues[2].toIntOrNull() ?: continue
                if (hour > 23) continue
                minutesUntil(now, hour, minute)?.let { minutes.add(it) }
            }
        }
        return minutes.toList()
    }

    private fun isTimeRangeBlob(blob: String): Boolean {
        if (blob.contains('–') || blob.contains('—')) return true
        return blob.contains('-') &&
            clockAmPmPattern.containsMatchIn(blob) &&
            Regex("""(오전|오후).+(오전|오후)""").containsMatchIn(blob)
    }

    private fun minutesUntil(nowMillis: Long, hour24: Int, minute: Int): Int? {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val target = cal.clone() as Calendar
        target.set(Calendar.HOUR_OF_DAY, hour24)
        target.set(Calendar.MINUTE, minute)
        target.set(Calendar.SECOND, 0)
        target.set(Calendar.MILLISECOND, 0)
        if (target.timeInMillis + 30_000L < nowMillis) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        val deltaMin = ((target.timeInMillis - nowMillis + 59_999L) / 60_000L).toInt()
        if (deltaMin < 0 || deltaMin > 120) return null
        return deltaMin
    }

    private fun flatten(node: NaverSubwayAccessibilityParser.Node): List<String> {
        val out = ArrayList<String>()
        collect(node, out)
        return out
    }

    private fun collect(node: NaverSubwayAccessibilityParser.Node, out: MutableList<String>) {
        clean(node.text).takeIf { it.isNotEmpty() }?.let(out::add)
        clean(node.contentDesc).takeIf { it.isNotEmpty() }?.let(out::add)
        node.children.forEach { collect(it, out) }
    }

    private fun clean(raw: String?): String =
        raw?.replace(formatChars, "")?.trim().orEmpty()
}
