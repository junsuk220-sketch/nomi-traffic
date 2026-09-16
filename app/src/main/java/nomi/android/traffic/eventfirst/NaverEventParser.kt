package nomi.android.traffic.eventfirst

import nomi.android.traffic.NaverSubwayNotificationEta
import nomi.product.nav.NavigationBusArrival

/**
 * Naver 302 title/text → one [NaverTransitEvent]. Pure: no Journey, no pin, no
 * BusWaitCore, no Accessibility, no speech. The same input always yields the
 * same event, so nothing here may look at what came before.
 *
 * Every pattern below was taken from 302 notifications captured on device.
 */
internal object NaverEventParser {

    fun parse(title: String?, text: String?, atMs: Long): NaverTransitEvent? {
        val head = title?.trim().orEmpty()
        val body = text?.trim().orEmpty()
        if (head.isEmpty()) return null

        guidance(head, body, atMs)?.let { return it }
        alight(head, body, atMs)?.let { return it }
        riding(head, body, atMs)?.let { return it }
        board(head, body, atMs)?.let { return it }
        wait(head, body, atMs)?.let { return it }
        return null
    }

    private fun guidance(head: String, body: String, atMs: Long): NaverTransitEvent? {
        if (GUIDANCE_START.matches(head)) {
            val destination = DESTINATION.find(body)?.groupValues?.get(1)?.trim()
            return NaverTransitEvent.GuidanceStart(destination?.ifEmpty { null }, atMs)
        }
        if (GUIDANCE_END.matches(head)) {
            return NaverTransitEvent.GuidanceEnd(body.ifEmpty { null }, atMs)
        }
        return null
    }

    private fun alight(head: String, body: String, atMs: Long): NaverTransitEvent? {
        ALIGHT_TRANSFER_TITLE.find(head)?.let { match ->
            return NaverTransitEvent.AlightTransfer(
                station = match.groupValues[2].trim(),
                transferLine = TRANSFER_LINE.find(body)?.groupValues?.get(1)?.trim(),
                doorSide = DOOR_SIDE.find(body)?.groupValues?.get(1)?.trim(),
                atMs = atMs,
            )
        }
        ALIGHT_NOW_NAMED.find(head)?.let { match ->
            return NaverTransitEvent.AlightNow(
                station = match.groupValues[2].trim().ifEmpty { null },
                unit = unitOf(match.groupValues[1]),
                atMs = atMs,
            )
        }
        ALIGHT_NOW_TITLE.find(head)?.let { match ->
            return NaverTransitEvent.AlightNow(
                station = stationFromText(body),
                unit = unitOf(match.groupValues[1]),
                atMs = atMs,
            )
        }
        ALIGHT_SOON_TITLE.find(head)?.let { match ->
            // Only the last stop before alighting is a cue. `하차까지 3개 역`
            // arrives as riding text, never as a title.
            if (match.groupValues[1].toIntOrNull() != 1) return null
            return NaverTransitEvent.AlightSoon(
                station = stationFromText(body),
                unit = unitOf(match.groupValues[2]),
                atMs = atMs,
            )
        }
        return null
    }

    /** `부평구청역에서 하차` → 부평구청역. `다음 역에서 하차` names nothing. */
    private fun stationFromText(body: String): String? {
        val named = ALIGHT_TEXT.find(body)?.groupValues?.get(1)?.trim()
        if (named != null && named.isNotEmpty() && !UNNAMED_STATION.matches(named)) return named
        if (body.isNotEmpty() && !body.contains(' ') && !body.contains(',')) return body
        return null
    }

    private fun riding(head: String, body: String, atMs: Long): NaverTransitEvent? {
        val stop = RIDING_MOVING.find(head)?.groupValues?.get(1)?.trim()
            ?: RIDING_STOPPED.find(head)?.groupValues?.get(1)?.trim()
            ?: return null
        if (stop.isEmpty()) return null
        val remaining = REMAINING.find(body)
        return NaverTransitEvent.Riding(
            stop = stop,
            remaining = remaining?.groupValues?.get(1)?.toIntOrNull(),
            unit = unitOf(remaining?.groupValues?.get(2)),
            atMs = atMs,
        )
    }

    private fun board(head: String, body: String, atMs: Long): NaverTransitEvent? {
        val match = TRAIN_BOARD.find(head) ?: return null
        val direction = BOARD_DIRECTION.find(body)?.groupValues?.get(1)?.trim().orEmpty()
        if (direction.isEmpty()) return null
        return NaverTransitEvent.BoardTrain(
            station = match.groupValues[1].trim(),
            line = match.groupValues[2].trim(),
            direction = direction,
            fastTransfer = FAST_TRANSFER.find(body)?.groupValues?.get(1)?.trim(),
            atMs = atMs,
        )
    }

    /**
     * Bus and train waits share the `까지 걷기` title, so the text decides:
     * numbered rows (`4 (10분)`) are a bus board, `{방면}행 (…)` rows are a train
     * board. A train board also needs the line, which only the title carries.
     */
    private fun wait(head: String, body: String, atMs: Long): NaverTransitEvent? {
        val rows = departureRows(body)
        if (rows.isNotEmpty()) {
            val match = TRAIN_WALK.find(head) ?: return null
            return NaverTransitEvent.WaitTrain(
                station = match.groupValues[1].trim(),
                line = match.groupValues[2].trim(),
                departures = rows.mapNotNull { resolve(it, atMs) },
                atMs = atMs,
            )
        }
        val arrivals = busArrivals(body)
        if (arrivals.isEmpty()) return null
        val stop = BUS_BOARD_TITLE.find(head)?.groupValues?.get(1)?.trim()
            ?: WALK_TO.find(head)?.groupValues?.get(1)?.trim()
            ?: return null
        if (stop.isEmpty()) return null
        return NaverTransitEvent.WaitBus(stop = stop, arrivals = arrivals, atMs = atMs)
    }

    private fun busArrivals(body: String): List<NavigationBusArrival> =
        BUS_ROW.findAll(body)
            .map { NavigationBusArrival(line = it.groupValues[1].trim(), eta = it.groupValues[2].trim()) }
            .filter { it.line.isNotEmpty() && it.eta.isNotEmpty() }
            .toList()

    /**
     * Rows exactly as printed, before any clock is resolved. Whether the board
     * is a train board must not depend on how many of its trains are still
     * catchable, or a board of departed trains would stop being an event.
     */
    private fun departureRows(body: String): List<Pair<String, String>> =
        TRAIN_ROW.findAll(body)
            .map { it.groupValues[1].trim() to it.groupValues[2].trim() }
            .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
            .toList()

    /**
     * An absolute clock becomes a candidate only once it converts against this
     * event's own timestamp. A train that already left, or one further out than
     * the wait horizon, is dropped rather than announced.
     *
     * Reading the clock off the event that carries it keeps this pure — no wall
     * clock, no previous event.
     */
    private fun resolve(row: Pair<String, String>, atMs: Long): Departure? {
        val (direction, value) = row
        if (!CLOCK.matches(value)) return Departure(direction, value)
        val minutes = NaverSubwayNotificationEta.clockMinutesUntil(value, atMs) ?: return null
        return Departure(direction, value, minutes)
    }

    private fun unitOf(raw: String?): RemainingUnit =
        if (raw?.contains("정류장") == true) RemainingUnit.STOP else RemainingUnit.STATION

    private const val LINE = """(?:\d+호선|GTX-[A-Z]|[가-힣A-Za-z0-9]+선)"""

    private val GUIDANCE_START = Regex("""^길안내를 시작합니다\.?$""")
    private val GUIDANCE_END = Regex("""^길안내를 종료합니다\.?$""")
    private val DESTINATION = Regex("""^(.+?)까지 이동$""")

    private val WALK_TO = Regex("""^(.+?)까지 걷기$""")
    private val BUS_BOARD_TITLE = Regex("""^(.+?)(?:\s*도보 후)?\s*버스 승차$""")
    private val TRAIN_WALK = Regex("""^(.+?)\s+($LINE)까지 걷기$""")
    private val TRAIN_BOARD = Regex("""^(.+?)\s+($LINE)\s+열차 승차$""")

    private val BOARD_DIRECTION = Regex("""^(.+?)\s*방면""")
    private val FAST_TRANSFER = Regex("""빠른\s*환승\s*:\s*(\S+)""")

    private val RIDING_MOVING = Regex("""^(.+?)(?:\(으\)로|으로|로) 이동 중$""")
    private val RIDING_STOPPED = Regex("""^(.+?)\s+정차$""")
    private val REMAINING = Regex("""하차까지\s*(\d+)\s*개\s*(정류장|역)""")

    private val ALIGHT_SOON_TITLE = Regex("""^하차까지\s*(\d+)\s*개\s*(정류장|역)$""")
    private val ALIGHT_TEXT = Regex("""^(.+?)에서 하차$""")
    private val UNNAMED_STATION = Regex("""^(?:다음|이번)\s*(?:역|정류장)$""")
    private val ALIGHT_NOW_TITLE = Regex("""^이번 (역|정류장)에서 하차$""")
    private val ALIGHT_NOW_NAMED = Regex("""^이번 (역|정류장)\((.+?)\)에서 하차$""")
    private val ALIGHT_TRANSFER_TITLE = Regex("""^이번 (역|정류장)\((.+?)\)에서 하차 후 환승$""")
    private val TRANSFER_LINE = Regex("""($LINE)(?:으로|로) 환승""")
    private val DOOR_SIDE = Regex("""내리는 문\s*(왼쪽|오른쪽)""")

    private val BUS_ROW = Regex("""([A-Za-z]?\d{1,5}(?:-\d+)?[A-Za-z]?)\s*\(([^)]+)\)""")
    private val TRAIN_ROW = Regex("""([^,()]+?행)\s*\(([^)]+)\)""")
    private val CLOCK = Regex("""\d{1,2}:\d{2}""")
}
