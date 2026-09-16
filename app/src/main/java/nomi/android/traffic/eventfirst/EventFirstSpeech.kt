package nomi.android.traffic.eventfirst

import nomi.android.traffic.NaverMapNotification
import nomi.android.traffic.NaverMapsTransit
import nomi.android.traffic.NavigationEventSpeech
import nomi.product.nav.NavigationBusArrival
import nomi.product.nav.NavigationBusInfo
import nomi.product.nav.NavigationEvent
import nomi.product.nav.NavigationEventSource
import nomi.product.nav.NavigationEventType

/**
 * Turns a SPEAK [EventFirstDecision] into the Korean line. Sentences are not
 * rewritten here: each case builds the [NavigationEvent] the existing
 * [NavigationEventSpeech] already knows how to phrase.
 *
 * Two cases have no Naver sentence in [NavigationEventSpeech] today — `이번
 * 역에서 하차` and `하차 후 환승` only exist for Google — so those two lines are
 * written here rather than added to the shared speech file.
 */
internal object EventFirstSpeech {

    fun line(event: NaverTransitEvent, decision: EventFirstDecision): String? {
        val type = decision.speechType ?: return null
        return when (type) {
            EventFirstDecision.SpeechType.BUS_BRIEF ->
                (event as? NaverTransitEvent.WaitBus)?.let { busLine(it, brief = true) }

            EventFirstDecision.SpeechType.BUS_STAGE ->
                (event as? NaverTransitEvent.WaitBus)?.let { busLine(it, brief = false) }

            EventFirstDecision.SpeechType.TRAIN_BRIEF ->
                (event as? NaverTransitEvent.WaitTrain)?.let { trainLine(it, brief = true) }

            EventFirstDecision.SpeechType.TRAIN_STAGE ->
                (event as? NaverTransitEvent.WaitTrain)?.let { trainLine(it, brief = false) }

            EventFirstDecision.SpeechType.BOARD_TRAIN ->
                (event as? NaverTransitEvent.BoardTrain)?.let { boardLine(it) }

            EventFirstDecision.SpeechType.ALIGHT_SOON ->
                (event as? NaverTransitEvent.AlightSoon)?.let { prepareAlightLine(it) }

            EventFirstDecision.SpeechType.ALIGHT_NOW ->
                (event as? NaverTransitEvent.AlightNow)?.let { alightNowLine(it) }

            EventFirstDecision.SpeechType.TRANSFER_ONLY ->
                (event as? NaverTransitEvent.AlightTransfer)?.let { transferLine(it, withAlight = false) }

            EventFirstDecision.SpeechType.TRANSFER_WITH_ALIGHT ->
                (event as? NaverTransitEvent.AlightTransfer)?.let { transferLine(it, withAlight = true) }
        }
    }

    private fun busLine(event: NaverTransitEvent.WaitBus, brief: Boolean): String? =
        NavigationEventSpeech.line(
            waitEvent(
                arrivals = event.arrivals,
                kind = NaverMapsTransit.KIND_BUS,
                brief = brief,
                atMs = event.atMs,
            ),
        )

    private fun trainLine(event: NaverTransitEvent.WaitTrain, brief: Boolean): String? =
        NavigationEventSpeech.line(
            waitEvent(
                arrivals = event.arrivals,
                kind = NaverMapsTransit.KIND_SUBWAY,
                brief = brief,
                atMs = event.atMs,
            ),
        )

    /**
     * The tracked vehicle has to lead the list: [NavigationEventSpeech] speaks
     * `arrivals.first()` and names the rest through NaverNextVehicle.
     */
    private fun waitEvent(
        arrivals: List<NavigationBusArrival>,
        kind: String,
        brief: Boolean,
        atMs: Long,
    ): NavigationEvent {
        val target = EventFirstPolicy.soonest(arrivals)
        val ordered = if (target == null) arrivals else listOf(target) + (arrivals - target)
        return naverEvent(
            action = if (brief) NaverMapsTransit.TRIP_START_ACTION else "",
            rawText = kind,
            busInfo = NavigationBusInfo(raw = "", arrivals = ordered),
            atMs = atMs,
        )
    }

    /** `예술회관역 방면입니다. 빠른 환승은 8-4입니다.` — existing 빠른 환승 sentence. */
    private fun boardLine(event: NaverTransitEvent.BoardTrain): String? {
        val cars = event.fastTransfer?.trim().orEmpty()
        if (cars.isEmpty()) return null
        return NavigationEventSpeech.line(
            naverEvent(
                action = NaverMapsTransit.QUICK_TRANSFER,
                rawText = cars,
                landmark = "${event.direction} 방면",
                atMs = event.atMs,
            ),
        )
    }

    private fun prepareAlightLine(event: NaverTransitEvent.AlightSoon): String? {
        val station = event.station?.trim().orEmpty()
        if (station.isEmpty()) return null
        return NavigationEventSpeech.line(
            naverEvent(
                action = NaverMapsTransit.PREPARE_ALIGHT_ACTION,
                rawText = kindOf(event.unit),
                landmark = station,
                atMs = event.atMs,
            ),
        )
    }

    private fun alightNowLine(event: NaverTransitEvent.AlightNow): String? {
        val station = event.station?.trim().orEmpty()
        if (station.isEmpty()) return null
        val place = placeOf(event.unit, station)
        return "이번 ${place}은 ${station}입니다. 이번 ${place}에서 하차하세요."
    }

    private fun transferLine(event: NaverTransitEvent.AlightTransfer, withAlight: Boolean): String? {
        val line = event.transferLine?.trim().orEmpty()
        val door = event.doorSide?.trim().orEmpty()
        val tail = when {
            line.isNotEmpty() && door.isNotEmpty() -> "내리는 문은 ${door}입니다. ${line}으로 환승입니다."
            line.isNotEmpty() -> "${line}으로 환승입니다."
            door.isNotEmpty() -> "내리는 문은 ${door}입니다."
            else -> return null
        }
        if (!withAlight) return tail
        val station = event.station.trim()
        if (station.isEmpty()) return tail
        return "이번 역은 ${station}입니다. $tail"
    }

    private fun kindOf(unit: RemainingUnit): String =
        if (unit == RemainingUnit.STOP) NaverMapsTransit.KIND_BUS else NaverMapsTransit.KIND_SUBWAY

    private fun placeOf(unit: RemainingUnit, station: String): String =
        if (unit == RemainingUnit.STOP || !station.endsWith("역")) "정류장" else "역"

    private fun naverEvent(
        action: String,
        rawText: String,
        atMs: Long,
        busInfo: NavigationBusInfo? = null,
        landmark: String? = null,
    ): NavigationEvent = NavigationEvent(
        source = NavigationEventSource.NAVER,
        type = NavigationEventType.TRANSIT,
        notificationId = 0,
        channel = NaverMapNotification.CHANNEL_TRANSIT,
        title = "",
        action = action,
        distanceMeters = null,
        rawText = rawText,
        busInfo = busInfo,
        timestampMillis = atMs,
        landmark = landmark,
    )
}
