package nomi.android.traffic

import nomi.product.nav.NavigationBusArrival
import nomi.product.nav.NavigationBusInfo
import nomi.product.nav.NavigationEvent
import nomi.product.nav.NavigationEventSource
import nomi.product.nav.NavigationEventType

/**
 * Maps already-read Google Maps transit nodes to [NavigationEvent].
 * Package and node strings stay here. No TTS, no Session, no tree walking of the live view.
 */
object GoogleTransitAccessibilityParser {

    data class Node(
        val text: String? = null,
        val contentDesc: String? = null,
        val children: List<Node> = emptyList(),
    )

    private val formatChars = Regex("""[\u200b\u200c\u200d\ufeff]""")
    private val busLinePattern = Regex("""버스[,:\s]+(\d+)""")
    private val singleBusLinePattern = Regex("""^버스[,:\s]+(\d+)(?:\s*\([^)]*\))?$""")
    private val bareBusLinePattern = Regex("""^(\d{2,4})(?:\s*\([^)]*\))?$""")
    private val etaPattern = Regex("""(\d+)\s*분\s*\(\s*실시간\s*\)\s*후""")
    private val etaSoonPattern = Regex("""(?:지금|곧)\s*\(\s*실시간\s*\)|^지금(\s*출발)?$|^곧$""")
    private val clockPattern = Regex("""(?:오전|오후)\s*(\d{1,2}):(\d{2})""")
    private val walkAboutPattern = Regex("""도보\s*약\s*(\d+)\s*분""")
    private val walkBeforeBusPattern = Regex("""도보\s*(\d+)\s*분후\s*버스""")
    private val walkBeforeSubwayPattern = Regex("""도보\s*(\d+)\s*분후\s*지하철""")
    private val firstVehiclePattern = Regex("""도보\s*\d+\s*분후\s*(버스|지하철|기차|열차)""")
    private val firstBusItineraryPattern = Regex("""도보\s*(\d+)\s*분후\s*버스[,:\s]+(\d+)""")
    private val firstSubwayItineraryPattern =
        Regex("""도보\s*(\d+)\s*분후\s*지하철[,:\s]+(.+?)(?:후|$)""")
    private val subwayLineLabelPattern = Regex("""^지하철[,:\s]+(.+)$""")
    private val subwayLineBarePattern = Regex("""^(\d+호선|[가-힣A-Za-z0-9]+선|GTX-[A-Za-z])$""")
    private val boardDirectionPattern = Regex("""^.+방면$""")
    private val etaSoonLoosePattern = Regex("""^지금(\s|$)|지금\s*출발|곧\s*출발""")
    private val busRideHudPattern = Regex("""\d{2,4},\s*.+까지 이동""")
    private val subwayRideHudPattern =
        Regex("""(?:\d+호선|[가-힣A-Za-z0-9]+선|GTX-[A-Za-z]),\s*.+까지 이동""")
    private val passagePattern = Regex("""(\d+)\s*통해\s*(들어가기|나가기)""")
    private val remainingCountPattern = Regex("""(?:정류장|역)\s*(\d+)개(?:\([^)]*\))?\s*이동""")
    private val liveEndedPattern = Regex("""한눈에 보기 종료""")
    private val glanceStartPattern = Regex("""한눈에 보기 시작""")
    private val destinationLabelPattern = Regex("""^목적지,\s*(.+)$""")
    private val hudStopPattern = Regex("""([^,]+),\s*(.+?)까지 이동""")
    private val busLineId = Regex("""^\d+$""")
    private val walkAboutMinutesPattern = Regex("""도보\s*약\s*(\d+)\s*분""")

    sealed class TripStartDecision {
        data class Speak(val event: NavigationEvent) : TripStartDecision()
        data object Pending : TripStartDecision()
    }

    fun parse(
        packageName: String?,
        root: Node,
        timestampMillis: Long = 0L,
    ): List<NavigationEvent> {
        if (packageName != GoogleMapsTransit.PACKAGE) return emptyList()
        // Preview / route options (before 한눈에 보기 종료) must not become arrivals.
        if (!isLiveGuidance(root)) return emptyList()
        val blobs = flatten(root)
        val events = ArrayList<NavigationEvent>()
        val allowBusCard = !subwayFirstWithoutBusRide(blobs)
        val subwayWait = extractSubwayWaitCard(blobs, timestampMillis)
        val busWait = if (allowBusCard && subwayWait == null) {
            busCards(root, timestampMillis).firstOrNull()
        } else {
            null
        }
        val wait = subwayWait ?: busWait
        wait?.let { events.add(waitEvent(it, timestampMillis)) }
        for (cue in currentPassages(root)) {
            events.add(passageEvent(cue, timestampMillis))
        }
        boardDirectionEvent(blobs, timestampMillis)?.let { events.add(it) }
        // Wait-sheet cards include the next leg's "정류장 N개 이동" preview.
        // Alight cues only when no boarding arrival is on screen.
        if (wait == null) {
            rideProgressEvent(root, timestampMillis)?.let { events.add(it) }
                ?: unnamedSubwayAlight(root, timestampMillis)?.let { events.add(it) }
        }
        return events
    }

    /** Later bus legs stay silent until the live HUD is actually on that bus. */
    private fun subwayFirstWithoutBusRide(blobs: List<String>): Boolean {
        val first = firstTransitVehicle(blobs) ?: return false
        if (first != "지하철" && first != "기차" && first != "열차") return false
        return !blobs.any { busRideHudPattern.containsMatchIn(it) }
    }

    fun destinationLabel(root: Node): String? {
        for (blob in flatten(root)) {
            val match = destinationLabelPattern.matchEntire(blob) ?: continue
            val name = match.groupValues[1].trim()
            if (name.isNotEmpty() && name != "출발지와 목적지 전환") return name
        }
        return null
    }

    fun isLiveGuidance(root: Node): Boolean =
        flatten(root).any { liveEndedPattern.containsMatchIn(it) }

    fun isGlanceStartOnly(root: Node): Boolean {
        val blobs = flatten(root)
        return blobs.any { glanceStartPattern.containsMatchIn(it) } &&
            blobs.none { liveEndedPattern.containsMatchIn(it) }
    }

    fun formatLog(event: NavigationEvent): String = buildString {
        appendLine("[GOOGLE_A11Y]")
        appendLine("action=${event.action}")
        val arrival = event.busInfo?.arrivals?.firstOrNull()
        appendLine("line=${arrival?.line.orEmpty()}")
        append("eta=${arrival?.eta.orEmpty()}")
    }

    internal fun clean(raw: String?): String =
        raw?.replace(formatChars, "")?.trim().orEmpty()

    private fun busCards(node: Node, nowMillis: Long): List<WaitCard> {
        val fromChildren = node.children.flatMap { busCards(it, nowMillis) }
        if (fromChildren.isNotEmpty()) return fromChildren
        val card = extractBusCard(flatten(node), nowMillis) ?: return emptyList()
        return listOf(card)
    }

    private fun extractBusCard(blobs: List<String>, nowMillis: Long): WaitCard? {
        val line = extractBusLine(blobs) ?: return null
        val minutes = blobs.firstNotNullOfOrNull { etaPattern.find(it)?.groupValues?.get(1) }
        val clockMinutes = clockEtaMinutes(blobs, nowMillis)
        val eta = when {
            minutes != null -> "${minutes}분"
            blobs.any { isSoonEtaBlob(it) } -> "곧"
            clockMinutes != null -> "${clockMinutes}분"
            else -> return null
        }
        return WaitCard(
            line = line,
            eta = eta,
            raw = blobs.joinToString(" | "),
            kind = GoogleMapsTransit.KIND_BUS,
        )
    }

    /** Subway departure wait at the platform — same 10/5/2/soon stages as bus. */
    private fun extractSubwayWaitCard(blobs: List<String>, nowMillis: Long): WaitCard? {
        if (!maySpeakSubwayWait(blobs)) return null
        val line = blobs.firstNotNullOfOrNull {
            subwayLineLabelPattern.matchEntire(it.trim())?.groupValues?.get(1)?.trim()
        } ?: blobs.firstOrNull { subwayLineBarePattern.matches(it.trim()) }?.trim()
            ?: return null
        val eta = tripStartEta(blobs, nowMillis, subwayFirst = true) ?: return null
        return WaitCard(
            line = line,
            eta = eta,
            raw = blobs.take(12).joinToString(" | "),
            kind = GoogleMapsTransit.KIND_SUBWAY,
        )
    }

    private fun maySpeakSubwayWait(blobs: List<String>): Boolean {
        if (blobs.any { subwayRideHudPattern.containsMatchIn(it) }) return false
        val walkMin = blobs.firstNotNullOfOrNull {
            walkAboutMinutesPattern.find(it)?.groupValues?.get(1)?.toIntOrNull()
        }
        if (walkMin != null && walkMin > 2) return false
        return blobs.any {
            boardDirectionPattern.matches(it.trim()) ||
                it.contains("플랫폼") ||
                it == "예정됨" ||
                it == "실시간" ||
                it.startsWith("실시간")
        }
    }

    /** Live glance uses bare `7727` / `1100(평일운행)`; list cards use `버스, 140`. Skip multi-line headers. */
    private fun extractBusLine(blobs: List<String>): String? {
        blobs.firstNotNullOfOrNull { singleBusLinePattern.matchEntire(it.trim())?.groupValues?.get(1) }
            ?.let { return it }
        val liveCard = blobs.any { it == "실시간" || it.startsWith("실시간") || isSoonEtaBlob(it) } ||
            blobs.any { isPrimaryDepartureClock(it) }
        if (liveCard) {
            blobs.firstNotNullOfOrNull { bareBusLinePattern.matchEntire(it)?.groupValues?.get(1) }
                ?.let { return it }
        }
        return blobs.firstNotNullOfOrNull { blob ->
            if (blob.contains("또는") || Regex("""버스[,:\s]+\d+\s*,""").containsMatchIn(blob)) {
                return@firstNotNullOfOrNull null
            }
            busLinePattern.find(blob)?.groupValues?.get(1)
        }
    }

    /**
     * Briefing after the user taps 시작 / glance becomes live.
     * Speaks the first vehicle leg (bus or subway) with walk minutes to board.
     */
    fun tripStartDecision(
        packageName: String?,
        root: Node,
        timestampMillis: Long = 0L,
    ): TripStartDecision {
        if (packageName != GoogleMapsTransit.PACKAGE) return TripStartDecision.Pending
        val blobs = flatten(root)
        val mode = firstTransitVehicle(blobs)
        val subwayFirst = mode == "지하철" || mode == "기차" || mode == "열차"
        val eta = tripStartEta(blobs, timestampMillis, subwayFirst = subwayFirst)
            ?: return TripStartDecision.Pending
        return when (mode) {
            "지하철", "기차", "열차" -> subwayTripStart(blobs, eta, timestampMillis)
                ?: TripStartDecision.Pending
            "버스", null -> busTripStart(blobs, eta, timestampMillis)
                ?: TripStartDecision.Pending
            else -> TripStartDecision.Pending
        }
    }

    private fun busTripStart(
        blobs: List<String>,
        eta: String,
        timestampMillis: Long,
    ): TripStartDecision.Speak? {
        val itinerary = blobs.firstNotNullOfOrNull { firstBusItineraryPattern.find(it) }
        val walkMinutes = itinerary?.groupValues?.get(1)?.toIntOrNull()
            ?: walkMinutes(blobs)
            ?: return null
        val line = itinerary?.groupValues?.get(2)
            ?: extractBusLine(blobs)
            ?: blobs.firstNotNullOfOrNull { bareBusLinePattern.matchEntire(it)?.groupValues?.get(1) }
            ?: return null
        return TripStartDecision.Speak(
            tripStartEvent(
                line = line,
                eta = eta,
                walkMinutes = walkMinutes,
                kind = GoogleMapsTransit.KIND_BUS,
                blobs = blobs,
                timestampMillis = timestampMillis,
            ),
        )
    }

    private fun subwayTripStart(
        blobs: List<String>,
        eta: String,
        timestampMillis: Long,
    ): TripStartDecision.Speak? {
        val itinerary = blobs.firstNotNullOfOrNull { firstSubwayItineraryPattern.find(it) }
        val walkMinutes = itinerary?.groupValues?.get(1)?.toIntOrNull()
            ?: blobs.firstNotNullOfOrNull {
                walkBeforeSubwayPattern.find(it)?.groupValues?.get(1)?.toIntOrNull()
            }
            ?: walkMinutes(blobs)
            ?: return null
        val line = itinerary?.groupValues?.get(2)?.trim()?.trimEnd(',', ' ')?.trim().orEmpty().ifBlank {
            blobs.firstNotNullOfOrNull {
                subwayLineLabelPattern.matchEntire(it.trim())?.groupValues?.get(1)?.trim()
            }.orEmpty()
        }
        if (line.isBlank()) return null
        return TripStartDecision.Speak(
            tripStartEvent(
                line = line,
                eta = eta,
                walkMinutes = walkMinutes,
                kind = GoogleMapsTransit.KIND_SUBWAY,
                blobs = blobs,
                timestampMillis = timestampMillis,
            ),
        )
    }

    private fun tripStartEvent(
        line: String,
        eta: String,
        walkMinutes: Int,
        kind: String,
        blobs: List<String>,
        timestampMillis: Long,
    ): NavigationEvent =
        NavigationEvent(
            source = NavigationEventSource.GOOGLE,
            type = NavigationEventType.TRANSIT,
            notificationId = 0,
            channel = GoogleMapsTransit.CHANNEL,
            title = walkMinutes.toString(),
            action = GoogleMapsTransit.TRIP_START_ACTION,
            distanceMeters = walkMinutes,
            rawText = kind,
            busInfo = NavigationBusInfo(
                raw = "$line $eta walk=$walkMinutes",
                arrivals = listOf(NavigationBusArrival(line = line, eta = eta)),
            ),
            timestampMillis = timestampMillis,
            landmark = null,
        )

    private fun tripStartEta(
        blobs: List<String>,
        timestampMillis: Long,
        subwayFirst: Boolean,
    ): String? {
        if (subwayFirst) {
            if (blobs.any { it.contains("지금") && it.contains("출발") }) return "곧"
            clockEtaMinutes(blobs, timestampMillis)?.let { return "${it}분" }
            if (blobs.any { isSoonEtaBlob(it) }) return "곧"
            blobs.firstNotNullOfOrNull { etaPattern.find(it)?.groupValues?.get(1) }
                ?.let { return "${it}분" }
            return null
        }
        blobs.firstNotNullOfOrNull { etaPattern.find(it)?.groupValues?.get(1) }
            ?.let { return "${it}분" }
        if (blobs.any { isSoonEtaBlob(it) }) return "곧"
        clockEtaMinutes(blobs, timestampMillis)?.let { return "${it}분" }
        return null
    }

    /** Test/helper: speak event only. Prefer [tripStartDecision] at the call site. */
    fun tripStartBriefing(
        packageName: String?,
        root: Node,
        timestampMillis: Long = 0L,
    ): NavigationEvent? = when (
        val decision = tripStartDecision(packageName, root, timestampMillis)
    ) {
        is TripStartDecision.Speak -> decision.event
        TripStartDecision.Pending -> null
    }

    private fun firstTransitVehicle(blobs: List<String>): String? =
        blobs.firstNotNullOfOrNull { firstVehiclePattern.find(it)?.groupValues?.get(1) }

    fun isGlanceStartButton(contentDescription: CharSequence?, texts: List<CharSequence>?): Boolean {
        val desc = contentDescription?.toString().orEmpty()
        if (glanceStartPattern.containsMatchIn(desc)) return true
        val joined = texts.orEmpty().joinToString("")
        return joined.contains("시작") && desc.contains("한눈에")
    }

    private fun walkMinutes(blobs: List<String>): Int? {
        blobs.firstNotNullOfOrNull { walkAboutPattern.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?.let { return it }
        blobs.firstNotNullOfOrNull {
            walkBeforeBusPattern.find(it)?.groupValues?.get(1)?.toIntOrNull()
        }?.let { return it }
        return blobs.firstNotNullOfOrNull {
            walkBeforeSubwayPattern.find(it)?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    private fun isSoonEtaBlob(blob: String): Boolean {
        val text = blob.trim()
        if (etaSoonPattern.containsMatchIn(text)) return true
        return etaSoonLoosePattern.containsMatchIn(text)
    }
    /** Primary card clock like `오후 8:46`. Ignore schedule labels, ranges, and `기타:` alternatives. */
    private fun clockEtaMinutes(blobs: List<String>, nowMillis: Long): Int? {
        if (nowMillis <= 0L) return null
        val blob = blobs.firstOrNull { isPrimaryDepartureClock(it) } ?: return null
        val match = clockPattern.find(blob) ?: return null
        val hour12 = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        val afternoon = blob.contains("오후")
        var hour24 = hour12 % 12
        if (afternoon) hour24 += 12
        val now = if (nowMillis >= 1_600_000_000_000L) nowMillis else System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val target = cal.clone() as java.util.Calendar
        target.set(java.util.Calendar.HOUR_OF_DAY, hour24)
        target.set(java.util.Calendar.MINUTE, minute)
        target.set(java.util.Calendar.SECOND, 0)
        target.set(java.util.Calendar.MILLISECOND, 0)
        if (target.timeInMillis + 30_000L < now) {
            target.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        val deltaMin = ((target.timeInMillis - now + 59_999L) / 60_000L).toInt()
        if (deltaMin < 0 || deltaMin > 120) return null
        return deltaMin
    }

    private fun isPrimaryDepartureClock(blob: String): Boolean {
        if (!clockPattern.containsMatchIn(blob)) return false
        if (blob.contains("기타") || blob.contains("예정")) return false
        if (blob.contains('–') || blob.contains('—')) return false
        // Trip window like "오전 9:38 – 오전 10:22" (ASCII hyphen variants too).
        if (Regex("""(오전|오후).+(오전|오후)""").containsMatchIn(blob)) return false
        return true
    }

    private fun passages(node: Node): List<String> {
        val found = linkedSetOf<String>()
        collectPassages(node, found)
        return found.toList()
    }

    /**
     * Passages from the full expanded sheet are not current steps.
     * Enter: only while walking to board (no mid-ride "정류장 N개" with N≥2).
     * Exit: only when one stop remains (arrived / about to alight).
     */
    private fun currentPassages(node: Node): List<String> {
        val blobs = flatten(node)
        val all = passages(node)
        if (all.isEmpty()) return emptyList()
        val rem = remainingCount(blobs)
        val enters = all.filter { it.endsWith("들어가기") }
        val exits = all.filter { it.endsWith("나가기") }
        if (enters.isNotEmpty() && maySpeakEnter(blobs, rem)) {
            return listOf(enters.first())
        }
        if (exits.isNotEmpty() && maySpeakExit(rem)) {
            return listOf(exits.first())
        }
        return emptyList()
    }

    private fun maySpeakEnter(blobs: List<String>, rem: Int?): Boolean {
        // Expanded itinerary shows upcoming ride length — not time to speak enter/exit.
        if (rem != null && rem >= 2) return false
        return blobs.any { walkAboutPattern.containsMatchIn(it) } ||
            blobs.any { passagePattern.containsMatchIn(it) && it.contains("들어가기") }
    }

    private fun maySpeakExit(rem: Int?): Boolean = rem == 1

    /**
     * At the end of the walk to the station: line + 방면 so the user can board
     * without looking at the phone.
     */
    private fun boardDirectionEvent(
        blobs: List<String>,
        timestampMillis: Long,
    ): NavigationEvent? {
        val rem = remainingCount(blobs)
        if (!maySpeakBoardDirection(blobs, rem)) return null
        val direction = blobs.firstOrNull { boardDirectionPattern.matches(it.trim()) }?.trim()
            ?: return null
        val line = blobs.firstNotNullOfOrNull {
            subwayLineLabelPattern.matchEntire(it.trim())?.groupValues?.get(1)?.trim()
        } ?: blobs.firstOrNull { subwayLineBarePattern.matches(it.trim()) }?.trim()
            ?: return null
        return NavigationEvent(
            source = NavigationEventSource.GOOGLE,
            type = NavigationEventType.TRANSIT,
            notificationId = 0,
            channel = GoogleMapsTransit.CHANNEL,
            title = line,
            action = GoogleMapsTransit.BOARD_DIRECTION_ACTION,
            distanceMeters = null,
            rawText = GoogleMapsTransit.KIND_SUBWAY,
            busInfo = null,
            timestampMillis = timestampMillis,
            landmark = direction,
        )
    }

    private fun maySpeakBoardDirection(blobs: List<String>, rem: Int?): Boolean {
        if (rem != null && rem >= 2) return false
        val walkMin = blobs.firstNotNullOfOrNull {
            walkAboutMinutesPattern.find(it)?.groupValues?.get(1)?.toIntOrNull()
        }
        // Still far from the station — keep silent.
        if (walkMin != null && walkMin > 2) return false
        val nearStation = walkMin != null ||
            blobs.any { passagePattern.containsMatchIn(it) && it.contains("들어가기") }
        return nearStation
    }

    private fun collectPassages(node: Node, out: MutableSet<String>) {
        for (blob in listOf(clean(node.text), clean(node.contentDesc))) {
            if (blob.isEmpty()) continue
            passagePattern.findAll(blob).forEach { match ->
                out.add("${match.groupValues[1]} 통해 ${match.groupValues[2]}")
            }
        }
        node.children.forEach { collectPassages(it, out) }
    }

    private fun flatten(node: Node): List<String> {
        val out = ArrayList<String>()
        collectBlobs(node, out)
        return out
    }

    private fun collectBlobs(node: Node, out: MutableList<String>) {
        clean(node.text).takeIf { it.isNotEmpty() }?.let(out::add)
        clean(node.contentDesc).takeIf { it.isNotEmpty() }?.let(out::add)
        node.children.forEach { collectBlobs(it, out) }
    }

    private fun waitEvent(card: WaitCard, timestampMillis: Long): NavigationEvent {
        val eta = card.eta
        return NavigationEvent(
            source = NavigationEventSource.GOOGLE,
            type = NavigationEventType.TRANSIT,
            notificationId = 0,
            channel = GoogleMapsTransit.CHANNEL,
            title = "${card.line} ${eta}",
            action = "${card.line} ${eta}",
            distanceMeters = null,
            rawText = card.kind,
            busInfo = NavigationBusInfo(
                raw = card.raw,
                arrivals = listOf(NavigationBusArrival(line = card.line, eta = eta)),
            ),
            timestampMillis = timestampMillis,
        )
    }

    private fun passageEvent(cue: String, timestampMillis: Long): NavigationEvent =
        NavigationEvent(
            source = NavigationEventSource.GOOGLE,
            type = NavigationEventType.TRANSIT,
            notificationId = 0,
            channel = GoogleMapsTransit.CHANNEL,
            title = cue,
            action = cue,
            distanceMeters = null,
            rawText = cue,
            busInfo = null,
            timestampMillis = timestampMillis,
        )

    private fun rideProgressEvent(node: Node, timestampMillis: Long): NavigationEvent? {
        val blobs = flatten(node)
        if (blobs.none { liveEndedPattern.containsMatchIn(it) }) return null
        val hud = rideHud(blobs) ?: return null
        return when (remainingCount(blobs)) {
            2 -> progressEvent(
                action = GoogleMapsTransit.PREPARE_ALIGHT_ACTION,
                stop = hud.stop,
                kind = hud.kind,
                timestampMillis = timestampMillis,
            )
            1 -> progressEvent(
                action = GoogleMapsTransit.ALIGHT_ACTION,
                stop = hud.stop,
                kind = hud.kind,
                timestampMillis = timestampMillis,
            )
            else -> null
        }
    }

    private fun unnamedSubwayAlight(node: Node, timestampMillis: Long): NavigationEvent? {
        val blobs = flatten(node)
        if (blobs.none { liveEndedPattern.containsMatchIn(it) }) return null
        if (rideHud(blobs) != null) return null
        if (remainingCount(blobs) != 1) return null
        if (blobs.none { it.contains("역 1개") }) return null
        return NavigationEvent(
            source = NavigationEventSource.GOOGLE,
            type = NavigationEventType.TRANSIT,
            notificationId = 0,
            channel = GoogleMapsTransit.CHANNEL,
            title = GoogleMapsTransit.ALIGHT_ACTION,
            action = GoogleMapsTransit.ALIGHT_ACTION,
            distanceMeters = null,
            rawText = GoogleMapsTransit.KIND_SUBWAY,
            busInfo = null,
            timestampMillis = timestampMillis,
            landmark = null,
        )
    }

    private fun rideHud(blobs: List<String>): RideHud? {
        for (blob in blobs) {
            val match = hudStopPattern.find(blob) ?: continue
            val line = match.groupValues[1].trim()
            val stop = match.groupValues[2].trim()
            if (line.isEmpty() || stop.isEmpty()) continue
            val kind = if (busLineId.matches(line)) {
                GoogleMapsTransit.KIND_BUS
            } else {
                GoogleMapsTransit.KIND_SUBWAY
            }
            return RideHud(kind = kind, stop = stop)
        }
        return null
    }

    private fun remainingCount(blobs: List<String>): Int? {
        var two: Int? = null
        for (blob in blobs) {
            val n = remainingCountPattern.find(blob)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (n == 1) return 1
            if (n == 2) two = 2
        }
        return two
    }

    private fun progressEvent(
        action: String,
        stop: String,
        kind: String,
        timestampMillis: Long,
    ): NavigationEvent =
        NavigationEvent(
            source = NavigationEventSource.GOOGLE,
            type = NavigationEventType.TRANSIT,
            notificationId = 0,
            channel = GoogleMapsTransit.CHANNEL,
            title = stop,
            action = action,
            distanceMeters = null,
            rawText = kind,
            busInfo = null,
            timestampMillis = timestampMillis,
            landmark = stop,
        )

    private data class RideHud(
        val kind: String,
        val stop: String,
    )

    private data class WaitCard(
        val line: String,
        val eta: String,
        val raw: String,
        val kind: String,
    )
}
