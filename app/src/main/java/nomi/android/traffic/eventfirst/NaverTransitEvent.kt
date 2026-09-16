package nomi.android.traffic.eventfirst

import nomi.android.traffic.NaverSubwayNotificationEta
import nomi.product.nav.NavigationBusArrival

/**
 * What one Naver 302 notification says about right now. Exactly nine kinds —
 * the event itself carries the situation, so nothing here is derived from an
 * earlier event, a Journey, a pin, or a live session.
 *
 * @see docs/BUS_WAIT_CONSTITUTION.md for the wait policy these feed.
 */
internal sealed interface NaverTransitEvent {

    val atMs: Long

    /** Scope the [EventFirstJudge] dedups within. Built from this event only. */
    val scopeKey: String

    data class GuidanceStart(
        val destination: String?,
        override val atMs: Long,
    ) : NaverTransitEvent {
        override val scopeKey: String get() = "guidance"
    }

    data class GuidanceEnd(
        val reason: String?,
        override val atMs: Long,
    ) : NaverTransitEvent {
        override val scopeKey: String get() = "guidance"
    }

    /** `{정류장}까지 걷기` / `{정류장} 버스 승차` + `4 (10분), 4 (20분)`. */
    data class WaitBus(
        val stop: String,
        val arrivals: List<NavigationBusArrival>,
        override val atMs: Long,
    ) : NaverTransitEvent {
        /** Soonest row on this board. The bus the wait cues are about. */
        val target: NavigationBusArrival? get() = EventFirstPolicy.soonest(arrivals)
        val route: String? get() = target?.line
        val eta: String? get() = target?.eta
        val nextEta: String? get() = EventFirstPolicy.nextVehicle(arrivals)?.eta

        override val scopeKey: String get() = "bus|$stop|${route.orEmpty()}"
    }

    /** `{역} {노선}까지 걷기` + `검단호수공원행 (17:16), ...`. */
    data class WaitTrain(
        val station: String,
        val line: String,
        val departures: List<Departure>,
        override val atMs: Long,
    ) : NaverTransitEvent {
        /** Distinct 방면 on the board. Two or more means the direction is unknown. */
        val directions: List<String> get() = departures.map { it.direction }.distinct()
        val direction: String? get() = directions.singleOrNull()
        val arrivals: List<NavigationBusArrival>
            get() = departures.map { NavigationBusArrival(line = line, eta = it.eta) }

        override val scopeKey: String get() = "train|$station|$line"
    }

    /** `{역} {노선} 열차 승차` + `{방면} 방면 빠른 환승: 8-4`. */
    data class BoardTrain(
        val station: String,
        val line: String,
        val direction: String,
        val fastTransfer: String?,
        override val atMs: Long,
    ) : NaverTransitEvent {
        override val scopeKey: String get() = "board|$station|$line|$direction"
    }

    /** `{정류장}으로 이동 중` / `{정류장} 정차` + `하차까지 35개 정류장`. Already aboard. */
    data class Riding(
        val stop: String,
        val remaining: Int?,
        val unit: RemainingUnit,
        override val atMs: Long,
    ) : NaverTransitEvent {
        override val scopeKey: String get() = "ride|$stop"
    }

    /** `하차까지 1개 역` + `{역}에서 하차`. [station] is null on the unnamed variant. */
    data class AlightSoon(
        val station: String?,
        val unit: RemainingUnit,
        override val atMs: Long,
    ) : NaverTransitEvent {
        override val scopeKey: String get() = "alight|${station.orEmpty()}"
    }

    /** `이번 역에서 하차` + `{역}`. */
    data class AlightNow(
        val station: String?,
        val unit: RemainingUnit,
        override val atMs: Long,
    ) : NaverTransitEvent {
        override val scopeKey: String get() = "alight|${station.orEmpty()}"
    }

    /** `이번 역({역})에서 하차 후 환승` + `내리는 문 오른쪽, ... 7호선으로 환승`. */
    data class AlightTransfer(
        val station: String,
        val transferLine: String?,
        val doorSide: String?,
        override val atMs: Long,
    ) : NaverTransitEvent {
        override val scopeKey: String get() = "alight|$station"
    }
}

/** `하차까지 N개 정류장` vs `하차까지 N개 역`. Chooses 정류장/역 wording. */
internal enum class RemainingUnit { STOP, STATION }

/**
 * One `{방면}행 ({값})` row on a train board.
 *
 * @param departureTime verbatim from the 302: a relative `5분` / `도착`, or an
 * absolute clock like `17:16`.
 * @param etaMinutes an absolute clock resolved against the timestamp of the very
 * event it arrived on. Null for the relative forms, which need no conversion.
 */
internal data class Departure(
    val direction: String,
    val departureTime: String,
    val etaMinutes: Int? = null,
) {
    /** What the 10 / 5 / 2 / 곧 ladder grades. */
    val eta: String
        get() = etaMinutes?.let { NaverSubwayNotificationEta.etaText(it) } ?: departureTime
}
