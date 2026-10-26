package nomi.android.traffic

import nomi.android.traffic.buswait.BusWaitCore
import nomi.product.nav.NavigationBusArrival
import nomi.product.nav.NavigationBusInfo
import nomi.product.nav.NavigationEvent
import nomi.product.nav.NavigationEventSource
import nomi.product.nav.NavigationEventType

/**
 * Speech dedupe for cues **other than** Naver bus 10/5/2/곧.
 * Naver bus wait stages live only in [nomi.android.traffic.buswait.BusWaitCore].
 */
class NavigationEventSpeechGate {

    private val naverSubwayStages = mutableSetOf<Int>()
    private val spokenNaverSubwayWalkBriefs = mutableSetOf<String>()
    private val googleTransitStages = mutableSetOf<Int>()
    private val spokenPassages = mutableSetOf<String>()
    private val spokenNaverSubwayCars = mutableSetOf<String>()
    private val spokenGoogleTripStarts = mutableSetOf<String>()
    private var spokenNaverTripStart = false
    private val spokenGoogleBoardDirections = mutableSetOf<String>()
    private val spokenNaverTransferWalks = mutableSetOf<String>()
    private val spokenNaverBoardDirections = mutableSetOf<String>()
    private var spokenAlight = false
    private val spokenAlightStops = mutableSetOf<String>()
    /** Last subway 302 clock head, e.g. `10:01`. */
    private var lastSubwayClockHead: String? = null
    /** The tracked head has been seen as `(도착)`. Reset only after this. */
    private var subwayClockDeparted = false
    /** Next HH:mm observed on a 302 clock list. Cleared on `(도착)` and journey reset. */
    private var lastSubwayNextClock: String? = null
    /** Line that accompanied [lastSubwayNextClock]. Empty line never remembers. */
    private var lastSubwayNextLine: String? = null

    fun accept(event: NavigationEvent): Boolean {
        return when (event.type) {
            NavigationEventType.WALK -> false
            NavigationEventType.TRANSIT -> acceptTransit(event)
        }
    }

    fun acceptNaverSubwayMinutes(minutes: Int): Boolean {
        return acceptTransitStage(naverSubwayStages, minutes)
    }

    /**
     * A new Naver guidance armed. Each cue below clears its own state — this is
     * the only place the set is listed, so a new cue is added here once instead
     * of at every live edge.
     */
    fun resetNaverJourneyCues() {
        resetNaverSubwayStages()
        resetNaverSubwayClockTrack()
        resetNaverSubwayBoardBriefs()
        resetNaverTransferCues()
        resetNaverPrepareAlight()
    }

    private fun resetNaverSubwayClockTrack() {
        lastSubwayClockHead = null
        subwayClockDeparted = false
        lastSubwayNextClock = null
        lastSubwayNextLine = null
    }

    /** The subway ladder only. Clock-head tracking stays — a new train may reuse it. */
    fun resetNaverSubwayStages() {
        naverSubwayStages.clear()
    }

    /** The one-briefing-per-board slot only. */
    fun resetNaverSubwayBoardBriefs() {
        spokenNaverSubwayWalkBriefs.clear()
    }

    /**
     * One walk-to-subway briefing per station+line. Not a 10/5/2/soon stage.
     * A later wait on the same board still uses the subway stage ladder.
     */
    fun acceptNaverSubwayWalkBrief(event: NavigationEvent): Boolean {
        noteNaverSubwayClocks(event)
        return takeFirstSubwayBoardBrief(event)
    }

    /**
     * The single first-briefing slot for one station+line board.
     */
    private fun takeFirstSubwayBoardBrief(event: NavigationEvent): Boolean {
        val key = naverSubwayWalkBriefKey(event) ?: return false
        if (!spokenNaverSubwayWalkBriefs.add(key)) return false
        etaMinutes(event)?.let { acceptNaverSubwayMinutes(it) }
        return true
    }

    private fun acceptTransit(event: NavigationEvent): Boolean {
        if (event.source == NavigationEventSource.GOOGLE &&
            event.action == GoogleMapsTransit.TRIP_START_ACTION
        ) {
            return acceptGoogleTripStart(event)
        }
        if (event.source == NavigationEventSource.NAVER &&
            event.action == NaverMapsTransit.TRIP_START_ACTION
        ) {
            return acceptNaverTripStart(event)
        }
        if (event.source == NavigationEventSource.NAVER &&
            event.action == NaverMapsTransit.TRANSFER_WALK_ACTION
        ) {
            val key = event.rawText.trim().ifEmpty {
                "${event.title}|${event.distanceMeters}"
            }
            return spokenNaverTransferWalks.add(key)
        }
        if (event.source == NavigationEventSource.NAVER &&
            event.action == NaverMapsTransit.PREPARE_ALIGHT_ACTION
        ) {
            val stop = event.landmark?.trim().orEmpty()
            if (stop.isEmpty()) return false
            return spokenAlightStops.add("${event.action}|$stop")
        }
        if (event.source == NavigationEventSource.NAVER &&
            event.action == NaverMapsTransit.BOARD_DIRECTION_ACTION
        ) {
            val key = "${event.title.trim()}|${event.landmark.orEmpty()}"
            if (key == "|") return false
            return spokenNaverBoardDirections.add(key)
        }
        if (event.source == NavigationEventSource.GOOGLE &&
            event.action == GoogleMapsTransit.BOARD_DIRECTION_ACTION
        ) {
            val key = "${event.title.trim()}|${event.landmark.orEmpty()}"
            if (key == "|") return false
            return spokenGoogleBoardDirections.add(key)
        }
        if (event.source == NavigationEventSource.GOOGLE &&
            (event.action == GoogleMapsTransit.ALIGHT_ACTION ||
                event.action == GoogleMapsTransit.PREPARE_ALIGHT_ACTION)
        ) {
            return acceptAlight(event)
        }
        if (isNaverSubwayCar(event)) return spokenNaverSubwayCars.add(naverSubwayCarKey(event))
        val minutes = etaMinutes(event) ?: return acceptPassage(event)
        // Naver bus ETA must not use this gate — BusWaitCore only.
        if (event.source == NavigationEventSource.NAVER &&
            event.rawText != NaverMapsTransit.KIND_SUBWAY
        ) {
            return false
        }
        if (event.source == NavigationEventSource.NAVER &&
            event.rawText == NaverMapsTransit.KIND_SUBWAY
        ) {
            noteNaverSubwayClocks(event)
        }
        val stages = when {
            event.source == NavigationEventSource.NAVER &&
                event.rawText == NaverMapsTransit.KIND_SUBWAY -> naverSubwayStages
            else -> googleTransitStages
        }
        return acceptTransitStage(stages, minutes)
    }

    fun resetNaverTransferCues() {
        spokenNaverTransferWalks.clear()
        spokenNaverBoardDirections.clear()
    }

    fun resetNaverPrepareAlight() {
        spokenAlightStops.removeAll { it.startsWith("${NaverMapsTransit.PREPARE_ALIGHT_ACTION}|") }
    }

    private fun acceptNaverTripStart(event: NavigationEvent): Boolean {
        if (event.busInfo?.arrivals?.firstOrNull() == null) return false
        if (spokenNaverTripStart) return false
        spokenNaverTripStart = true
        return true
    }

    fun resetNaverTripStart() {
        spokenNaverTripStart = false
    }

    private fun acceptGoogleTripStart(event: NavigationEvent): Boolean {
        val arrival = event.busInfo?.arrivals?.firstOrNull() ?: return false
        val key = "${event.rawText}|${arrival.line}|${arrival.eta}|${event.distanceMeters}"
        if (!spokenGoogleTripStarts.add(key)) return false
        return true
    }

    fun resetGoogleTripStart() {
        spokenGoogleTripStarts.clear()
    }

    private fun isNaverSubwayCar(event: NavigationEvent): Boolean {
        if (event.source != NavigationEventSource.NAVER) return false
        if (event.action != NaverMapsTransit.QUICK_EXIT &&
            event.action != NaverMapsTransit.QUICK_TRANSFER
        ) {
            return false
        }
        val direction = event.landmark?.trim().orEmpty()
        val cars = event.rawText.trim()
        return direction.endsWith("방면") && cars.isNotEmpty()
    }

    private fun naverSubwayCarKey(event: NavigationEvent): String =
        "${event.action}|${event.landmark.orEmpty()}|${event.rawText.trim()}"

    private fun acceptAlight(event: NavigationEvent): Boolean {
        val stop = event.landmark?.trim().orEmpty()
        if (stop.isNotEmpty()) {
            return spokenAlightStops.add("${event.action}|$stop")
        }
        if (event.action == GoogleMapsTransit.PREPARE_ALIGHT_ACTION) return false
        if (spokenAlight) return false
        spokenAlight = true
        return true
    }

    private fun acceptTransitStage(stages: MutableSet<Int>, minutes: Int): Boolean {
        val stage = BusWaitCore.stageForMinutes(minutes) ?: return false
        if (!stages.add(stage)) return false
        stages.addAll(BusWaitCore.coarserStages(stage))
        return true
    }

    private fun acceptPassage(event: NavigationEvent): Boolean {
        if (event.source != NavigationEventSource.GOOGLE) return false
        val key = PASSAGE.find(event.action.trim())?.value ?: return false
        if (!spokenPassages.add(key)) return false
        return true
    }

    /**
     * Observe 302 clocks before Speech so a later 2분/곧 event can reuse the
     * next HH:mm. Same owner as the departed/later-head reset.
     */
    fun rememberNaverSubwayClocks(event: NavigationEvent) {
        noteNaverSubwayClocks(event)
    }

    /**
     * If this wait has only the current ETA, attach a second arrival computed
     * from a previously observed next HH:mm. Speech still reads the event only.
     */
    fun withRememberedSubwayNext(event: NavigationEvent): NavigationEvent {
        if (event.source != NavigationEventSource.NAVER) return event
        if (event.rawText != NaverMapsTransit.KIND_SUBWAY) return event
        val info = event.busInfo ?: return event
        if (info.arrived) return event
        if (info.clocks.isNotEmpty()) return event
        if (info.arrivals.size != 1) return event
        if (subwayClockDeparted) return event
        val nextClock = lastSubwayNextClock ?: return event
        val nextLine = lastSubwayNextLine ?: return event
        val current = info.arrivals.first()
        if (current.line != nextLine) return event
        val now = event.timestampMillis
        if (now < 1_600_000_000_000L) return event
        val minutes = NaverSubwayNotificationEta.clockMinutesUntil(nextClock, now) ?: return event
        val nextEta = NaverSubwayNotificationEta.etaText(minutes)
        if (nextEta == current.eta) return event
        return event.copy(
            busInfo = info.copy(
                arrivals = listOf(
                    current,
                    NavigationBusArrival(line = nextLine, eta = nextEta),
                ),
            ),
        )
    }

    /**
     * Subway next-train: a later clock head is the next train. After `(도착)`, the
     * first clock head or a relative `2분` or more is the next train too.
     * A clock slide or `곧` right after `(도착)` is the same train and does not reset.
     */
    private fun noteNaverSubwayClocks(event: NavigationEvent) {
        if (event.source != NavigationEventSource.NAVER) return
        if (event.rawText != NaverMapsTransit.KIND_SUBWAY) return
        val info = event.busInfo ?: return
        if (info.arrived) {
            subwayClockDeparted = true
            lastSubwayNextClock = null
            lastSubwayNextLine = null
        }
        if (isArrivedOnly(event)) return
        val head = info.clocks.firstOrNull()
        if (head == null) {
            val minutes = TransitSoonEta.minutes(info.arrivals.firstOrNull()?.eta.orEmpty())
            if (subwayClockDeparted && minutes != null && minutes >= 2) {
                resetNaverSubwayStages()
                subwayClockDeparted = false
            }
            return
        }
        val previous = lastSubwayClockHead
        if (previous == null) {
            lastSubwayClockHead = head
            if (subwayClockDeparted) {
                resetNaverSubwayStages()
                subwayClockDeparted = false
            }
            if (!info.arrived) rememberSubwayNext(info)
            return
        }
        if (isClockLater(head, previous)) {
            // A later head clock is the next train, whether or not the
            // previous one was seen as (도착). Stages start over for it.
            resetNaverSubwayStages()
            lastSubwayClockHead = head
            subwayClockDeparted = false
            rememberSubwayNext(info)
            return
        }
        if (!subwayClockDeparted) {
            rememberSubwayNext(info)
        }
    }

    /** `(도착)` with no other train: the train at the platform, not a wait rung. */
    fun isArrivedOnly(event: NavigationEvent): Boolean {
        val info = event.busInfo ?: return false
        if (!info.arrived) return false
        return info.arrivals.isNotEmpty() && info.arrivals.all { it.eta.trim() == "도착" }
    }

    private fun rememberSubwayNext(info: NavigationBusInfo) {
        val next = info.clocks.getOrNull(1)
        val line = info.arrivals.firstOrNull()?.line?.trim().orEmpty()
        if (next == null || line.isEmpty()) {
            lastSubwayNextClock = null
            lastSubwayNextLine = null
            return
        }
        lastSubwayNextClock = next
        lastSubwayNextLine = line
    }

    private fun isClockLater(next: String, previous: String): Boolean {
        val nextMin = clockMinutes(next) ?: return false
        val previousMin = clockMinutes(previous) ?: return false
        return nextMin > previousMin
    }

    private fun clockMinutes(clock: String): Int? {
        val parts = clock.split(':')
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour > 23 || minute > 59) return null
        return hour * 60 + minute
    }

    private fun naverSubwayWalkBriefKey(event: NavigationEvent): String? {
        if (event.source != NavigationEventSource.NAVER) return null
        if (event.rawText != NaverMapsTransit.KIND_SUBWAY) return null
        val line = event.busInfo?.arrivals?.firstOrNull()?.line?.trim().orEmpty()
        if (line.isEmpty()) return null
        val station = subwayBoardStation(event.title, line) ?: return null
        return "$station|$line"
    }

    private fun subwayBoardStation(title: String, line: String): String? {
        val head = title.trim()
        val stripped = when {
            head.endsWith("열차 승차") -> head.removeSuffix("열차 승차").trim()
            head.endsWith("까지 걷기") -> head.removeSuffix("까지 걷기").trim()
            else -> return null
        }
        // 302 rewrites `까지 걷기` → `도보 후 열차 승차` on the same board.
        val board = stripped.removeSuffix("도보 후").trim()
        val station = if (board.endsWith(line)) {
            board.removeSuffix(line).trim()
        } else {
            board
        }
        return station.takeIf { it.isNotEmpty() }
    }

    private fun etaMinutes(event: NavigationEvent): Int? {
        val eta = event.busInfo?.arrivals?.firstOrNull()?.eta ?: return null
        if (TransitSoonEta.matches(eta)) return 1
        val minutes = ETA_MINUTES.find(eta)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        // Google keeps numeric 1분 silent (곧/지금 only). Naver subway 1분 ≈ soon.
        if (minutes <= 1) {
            if (event.source == NavigationEventSource.GOOGLE) return null
            return 1
        }
        return minutes
    }

    companion object {
        private val ETA_MINUTES = Regex("""(\d+)\s*분""")
        private val PASSAGE = Regex("""(\d+)\s*통해\s*(들어가기|나가기)""")
    }
}
