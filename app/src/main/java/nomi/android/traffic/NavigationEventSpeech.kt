package nomi.android.traffic

import nomi.product.nav.NavigationEvent
import nomi.product.nav.NavigationEventSource
import nomi.product.nav.NavigationEventType

/**
 * Turns a [NavigationEvent] into a fixed Korean line. No TTS, no LLM, no network,
 * and no reads of tracker state: the same event always yields the same line.
 * Walk is out of product V1. Transit speaks a bus arrival, a Naver subway car cue,
 * a Google passage cue, a named Google bus/subway alight stop, a one-stop-before
 * prepare cue, or a one-stop alight warning.
 */
object NavigationEventSpeech {

    private val etaMinutesPattern = Regex("""(\d+)\s*분""")
    private val passagePattern = Regex("""(\d+)\s*통해\s*(들어가기|나가기)""")
    private val CAR_NUMBERS = Regex("""^\d+-\d+(?:\s*,\s*\d+-\d+)*$""")
    private val TRAIN_BOUND = Regex("""([^,\s()]+행)""")
    private val TRAIN_BOUND_OPEN = Regex("""([^,\s()]+행)\s*\(""")
    private val FAST_ALIGHT_CARS =
        Regex("""빠른\s*하차\s*:\s*(\d+-\d+(?:\s*,\s*\d+-\d+)*)""")

    fun line(event: NavigationEvent, includeFastAlight: Boolean = false): String? {
        return when (event.type) {
            NavigationEventType.WALK -> null
            NavigationEventType.TRANSIT -> transitLine(event, includeFastAlight)
        }
    }

    private fun transitLine(
        event: NavigationEvent,
        includeFastAlight: Boolean = false,
    ): String? {
        naverSubwayCarLine(event)?.let { return it }
        naverTransferWalkLine(event)?.let { return it }
        naverBoardDirectionLine(event)?.let { return it }
        tripStartLine(event)?.let { return it }
        googleBoardDirectionLine(event)?.let { return it }
        naverPrepareAlightLine(event)?.let { return it }
        googleAlightLine(event)?.let { return it }
        val first = event.busInfo?.arrivals?.firstOrNull()
        if (first != null && first.line.isNotBlank()) {
            return if (event.rawText == GoogleMapsTransit.KIND_SUBWAY ||
                event.rawText == NaverMapsTransit.KIND_SUBWAY
            ) {
                val head = subwayArrivalLine(
                    line = first.line,
                    eta = first.eta,
                    // Naver walk/brief: say the current minutes twice. Google keeps the old line.
                    repeatCurrentMinutes = event.source == NavigationEventSource.NAVER,
                    bound = if (event.source == NavigationEventSource.NAVER) {
                        currentTrainBound(event)
                    } else {
                        null
                    },
                ) ?: return null
                if (event.source != NavigationEventSource.NAVER) head
                else appendFastAlight(
                    event,
                    appendWalkNextVehicle(event, head),
                    includeFastAlight,
                )
            } else {
                val head = busArrivalLine(
                    first.line,
                    first.eta,
                    first.occupancy,
                    // Google: numeric 1분 stays silent; Naver 1분 ≈ 곧.
                    oneMinuteAsSoon = event.source != NavigationEventSource.GOOGLE,
                ) ?: return null
                appendWalkNextVehicle(event, head)
            }
        }
        return passageLine(event.action)
    }

    private fun naverTransferWalkLine(event: NavigationEvent): String? {
        if (event.source != NavigationEventSource.NAVER) return null
        if (event.action != NaverMapsTransit.TRANSFER_WALK_ACTION) return null
        val exit = event.title.trim().removeSuffix("번").trim()
        val minutes = event.distanceMeters ?: return null
        if (exit.isEmpty() || minutes <= 0) return null
        return "${exit}번 출구로 나가서, 버스 정류장까지 걸어서 약 ${minutes}분입니다."
    }

    private fun naverBoardDirectionLine(event: NavigationEvent): String? {
        if (event.source != NavigationEventSource.NAVER) return null
        if (event.action != NaverMapsTransit.BOARD_DIRECTION_ACTION) return null
        val line = event.title.trim()
        val direction = shortenDirection(event.landmark?.trim().orEmpty())
        if (line.isEmpty() || direction.isEmpty()) return null
        return "${line}번 버스입니다. ${direction}입니다."
    }

    /** `삼송역사거리.지축차량기지입구 방면` → `삼송역사거리 방면`. */
    private fun shortenDirection(raw: String): String {
        if (raw.isEmpty() || !raw.endsWith("방면")) return ""
        val body = raw.removeSuffix("방면").trim()
        val head = body.substringBefore('.').substringBefore('·').trim()
        if (head.isEmpty()) return raw
        return "$head 방면"
    }

    private fun tripStartLine(event: NavigationEvent): String? {
        if (event.action != GoogleMapsTransit.TRIP_START_ACTION &&
            event.action != NaverMapsTransit.TRIP_START_ACTION
        ) {
            return null
        }
        if (event.source != NavigationEventSource.GOOGLE &&
            event.source != NavigationEventSource.NAVER
        ) {
            return null
        }
        val first = event.busInfo?.arrivals?.firstOrNull() ?: return null
        if (first.line.isBlank()) return null
        val walk = event.distanceMeters
        val subway = event.rawText == GoogleMapsTransit.KIND_SUBWAY ||
            event.rawText == NaverMapsTransit.KIND_SUBWAY
        val vehicle = if (subway) {
            when {
                isSoonEta(first.eta) -> "${subwaySubject(first.line)}, 곧 출발합니다."
                else -> {
                    val minutes = etaMinutesPattern.find(first.eta)?.groupValues?.get(1)?.toIntOrNull()
                        ?: return null
                    if (minutes < 1) return null
                    "${subwaySubject(first.line)} ${minutes}분 후 출발합니다."
                }
            }
        } else {
            when {
                isSoonEta(first.eta) -> "${first.line}번, ${first.line}번 버스, 곧 도착합니다."
                else -> {
                    val minutes = etaMinutesPattern.find(first.eta)?.groupValues?.get(1)?.toIntOrNull()
                        ?: return null
                    if (minutes < 1) return null
                    "${first.line}번, ${first.line}번 버스가 ${minutes}분 후 도착합니다."
                }
            }
        }
        if (walk == null || walk <= 0) {
            val nextOnly = nextVehicleClause(event)
            return if (nextOnly == null) vehicle else "$vehicle $nextOnly"
        }
        val walkTail = if (subway) {
            "역까지는 걸어서 약 ${walk}분입니다."
        } else {
            "정류장까지는 걸어서 약 ${walk}분입니다."
        }
        val nextTail = nextVehicleClause(event)
        return if (nextTail == null) "$vehicle $walkTail" else "$vehicle $walkTail $nextTail"
    }

    private fun nextVehicleClause(event: NavigationEvent): String? {
        if (event.source != NavigationEventSource.NAVER) return null
        return naverNextVehicleLine(event, forBriefing = true)
    }

    /**
     * Every wait cue names the next vehicle — one phase, no arrival condition.
     * @see docs/BUS_WAIT_CONSTITUTION.md revision 2026-09-18
     */
    private fun appendWalkNextVehicle(event: NavigationEvent, head: String): String {
        if (event.source != NavigationEventSource.NAVER) return head
        val next = naverNextVehicleLine(event, forBriefing = true) ?: return head
        return "$head $next"
    }

    private fun busArrivalLine(
        line: String,
        eta: String,
        occupancy: String?,
        oneMinuteAsSoon: Boolean,
    ): String? {
        if (isOneMinute(eta) && !oneMinuteAsSoon) return null
        val head = if (isSoonEta(eta) || (oneMinuteAsSoon && isOneMinute(eta))) {
            "${line}번, ${line}번 버스, 곧 도착합니다."
        } else {
            val minutes = etaMinutesPattern.find(eta)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            if (minutes < 1) return null
            "${line}번, ${line}번 버스가 ${minutes}분 후 도착해요."
        }
        val status = occupancy?.trim().orEmpty()
        if (status.isEmpty()) return head
        return "$head 버스 좌석은 ${status}입니다."
    }

    private fun isSoonEta(eta: String): Boolean = TransitSoonEta.matches(eta)

    private fun isOneMinute(eta: String): Boolean =
        etaMinutesPattern.find(eta)?.groupValues?.get(1)?.toIntOrNull() == 1

    /** Second departure on the 302 / sheet — the vehicle after the one in the briefing. */
    fun naverNextTrainLine(event: NavigationEvent): String? =
        naverNextVehicleLine(event, forBriefing = false)

    internal fun naverNextVehicleLine(
        event: NavigationEvent,
        forBriefing: Boolean,
    ): String? {
        if (event.source != NavigationEventSource.NAVER) return null
        if (!forBriefing && event.action == NaverMapsTransit.TRIP_START_ACTION) return null
        val arrivals = event.busInfo?.arrivals.orEmpty()
        val current = arrivals.firstOrNull() ?: return null
        val subway = event.rawText == NaverMapsTransit.KIND_SUBWAY
        val next = NaverNextVehicle.afterSoonest(arrivals, current) ?: return null
        if (isSoonEta(next.eta)) {
            return if (subway) "다음 열차는 곧 도착합니다."
            else "다음은 ${next.line}번, 곧 도착합니다."
        }
        val minutes = etaMinutesPattern.find(next.eta)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        if (minutes < 1) return null
        return if (subway) {
            "다음 열차는 ${minutes}분 후 도착입니다."
        } else {
            "다음은 ${next.line}번, ${minutes}분 후 도착입니다."
        }
    }

    private fun subwayArrivalLine(
        line: String,
        eta: String,
        repeatCurrentMinutes: Boolean = false,
        bound: String? = null,
    ): String? {
        if (isSoonEta(eta) || isOneMinute(eta)) return "${line}, 곧 출발합니다."
        val minutes = etaMinutesPattern.find(eta)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (minutes < 1) return null
        val subject = subwayWaitSubject(line, bound)
        // Naver repeats the minutes, Google keeps its own line. Nothing else may flip this.
        if (repeatCurrentMinutes) {
            return "$subject ${minutes}분, ${minutes}분 후 도착합니다."
        }
        return "$subject ${minutes}분 후 출발해요."
    }

    /** First `오금행 (` in the 302 body is this train. No 행 → keep `3호선이`. */
    private fun currentTrainBound(event: NavigationEvent): String? =
        TRAIN_BOUND_OPEN.find(event.busInfo?.raw.orEmpty())?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun subwayWaitSubject(line: String, bound: String?): String {
        val headsign = bound?.trim().orEmpty()
        if (headsign.isEmpty()) return subwaySubject(line)
        return "$line $headsign 열차가"
    }

    private fun appendFastAlight(
        event: NavigationEvent,
        head: String,
        includeFastAlight: Boolean,
    ): String {
        if (!includeFastAlight) return head
        val cars = FAST_ALIGHT_CARS.find(event.busInfo?.raw.orEmpty())?.groupValues?.get(1)?.trim()
            .orEmpty()
        if (cars.isEmpty()) return head
        return "$head 빠른 하차는 ${cars}번입니다."
    }

    private fun googleBoardDirectionLine(event: NavigationEvent): String? {
        if (event.source != NavigationEventSource.GOOGLE) return null
        if (event.action != GoogleMapsTransit.BOARD_DIRECTION_ACTION) return null
        val line = event.title.trim()
        val direction = event.landmark?.trim().orEmpty()
        if (line.isEmpty() || !direction.endsWith("방면")) return null
        return "${line}입니다. ${direction}입니다."
    }

    private fun subwaySubject(line: String): String =
        if (line.endsWith("선") || line.endsWith("호선")) "${line}이" else "${line}가"

    private fun naverPrepareAlightLine(event: NavigationEvent): String? {
        if (event.source != NavigationEventSource.NAVER) return null
        if (event.action != NaverMapsTransit.PREPARE_ALIGHT_ACTION) return null
        val stop = event.landmark?.trim().orEmpty()
        if (stop.isEmpty()) return null
        val place = if (event.rawText == NaverMapsTransit.KIND_BUS || !stop.endsWith("역")) {
            "정류장"
        } else {
            "역"
        }
        return "다음 ${place}은 ${stop}입니다. 내릴 준비하세요."
    }

    private fun googleAlightLine(event: NavigationEvent): String? {
        if (event.source != NavigationEventSource.GOOGLE) return null
        val stop = event.landmark?.trim().orEmpty()
        return when (event.action) {
            GoogleMapsTransit.PREPARE_ALIGHT_ACTION -> {
                if (stop.isEmpty()) null
                else "다음은 ${stop}입니다. 하차 준비하세요."
            }
            GoogleMapsTransit.ALIGHT_ACTION -> when {
                stop.isNotEmpty() && event.rawText == GoogleMapsTransit.KIND_SUBWAY ->
                    "이번 역은 ${stop}입니다. 이번 역에서 하차하세요."
                stop.isNotEmpty() ->
                    "이번 정류장은 ${stop}입니다. 이번 정류장에서 하차하세요."
                else -> "이번 역에서 하차하세요."
            }
            else -> null
        }
    }

    private fun naverSubwayCarLine(event: NavigationEvent): String? {
        if (event.source != NavigationEventSource.NAVER) return null
        repeatedNearBoardExitLine(event)?.let { return it }
        val direction = event.landmark?.trim().orEmpty()
        if (direction.isEmpty() || !direction.endsWith("방면")) return null
        val cars = spokenCars(event.rawText.trim())
        if (cars.isEmpty()) return null
        val board = event.title.trim()
        val head = if (board.endsWith("승차")) "$board " else ""
        return when (event.action) {
            NaverMapsTransit.QUICK_EXIT -> "${head}${direction}입니다. 빠른 하차는 ${cars}입니다."
            NaverMapsTransit.QUICK_TRANSFER -> "${head}${direction}입니다. 빠른 환승은 ${cars}입니다."
            else -> null
        }
    }

    /**
     * Near-board 빠른 하차 with a numeric wait: say the minutes twice.
     * `곧` stays on the existing car / wait sentences.
     */
    private fun repeatedNearBoardExitLine(event: NavigationEvent): String? {
        if (event.action != NaverMapsTransit.QUICK_EXIT) return null
        val cars = event.rawText.trim()
        if (!CAR_NUMBERS.matches(cars)) return null
        val first = event.busInfo?.arrivals?.firstOrNull() ?: return null
        val line = first.line.trim()
        if (line.isEmpty()) return null
        if (isSoonEta(first.eta) || isOneMinute(first.eta)) return null
        val minutes = etaMinutesPattern.find(first.eta)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (minutes < 1) return null
        val bound = TRAIN_BOUND.find(event.busInfo?.raw.orEmpty())?.groupValues?.get(1)?.trim()
            ?: event.landmark?.trim()?.takeIf { it.endsWith("행") && !it.endsWith("방면") }
            ?: return null
        return "${line} ${bound} 열차가 ${minutes}분, ${minutes}분 후 도착합니다. 빠른 하차는 ${cars}번입니다."
    }

    private fun spokenCars(raw: String): String =
        raw.replace("-", "다시").replace(Regex("""\s*,\s*"""), ", ")

    private fun passageLine(action: String): String? {
        val match = passagePattern.find(action.trim()) ?: return null
        val number = match.groupValues[1]
        val verb = match.groupValues[2]
        return when (verb) {
            "들어가기" -> "${number}번 출구를 통해 들어가세요."
            "나가기" -> "${number}번 출구를 통해 나가세요."
            else -> null
        }
    }
}
