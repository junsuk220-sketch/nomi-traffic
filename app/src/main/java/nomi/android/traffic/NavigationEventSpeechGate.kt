package nomi.android.traffic

import nomi.product.nav.NavigationEvent
import nomi.product.nav.NavigationEventSource
import nomi.product.nav.NavigationEventType

/**
 * Speech dedupe for cues **other than** Naver bus 10/5/2/곧.
 * Naver bus wait stages live only in [nomi.android.traffic.buswait.BusWaitCore].
 */
class NavigationEventSpeechGate {

    private val naverSubwayStages = mutableSetOf<Int>()
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

    fun accept(event: NavigationEvent): Boolean {
        return when (event.type) {
            NavigationEventType.WALK -> false
            NavigationEventType.TRANSIT -> acceptTransit(event)
        }
    }

    fun acceptNaverSubwayMinutes(minutes: Int): Boolean {
        return acceptTransitStage(naverSubwayStages, minutes)
    }

    fun resetNaverSubwayStages() {
        naverSubwayStages.clear()
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
        val stage = when {
            minutes <= 1 -> 1
            minutes <= 2 -> 2
            minutes <= 5 -> 5
            minutes <= 10 -> 10
            else -> return false
        }
        if (!stages.add(stage)) return false
        for (coarser in listOf(10, 5, 2)) {
            if (coarser > stage) stages.add(coarser)
        }
        return true
    }

    private fun acceptPassage(event: NavigationEvent): Boolean {
        if (event.source != NavigationEventSource.GOOGLE) return false
        val key = PASSAGE.find(event.action.trim())?.value ?: return false
        if (!spokenPassages.add(key)) return false
        return true
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
