package nomi.android.traffic.eventfirst

import nomi.android.traffic.eventfirst.EventFirstDecision.Reason
import nomi.android.traffic.eventfirst.EventFirstDecision.SpeechType
import nomi.product.nav.NavigationBusArrival

/**
 * Decides whether one [NaverTransitEvent] is worth saying out loud. Pure: same
 * event + same state + same clock always gives the same [Outcome], and the next
 * state is returned rather than written.
 *
 * The wait ladder is not redesigned here — [EventFirstPolicy] delegates to the
 * verified BusWaitCore functions, and this file only decides which scope the
 * ladder applies to and when a scope has to be forgotten.
 */
internal object EventFirstJudge {

    data class Outcome(
        val decision: EventFirstDecision,
        val state: EventFirstState,
    )

    fun judge(
        event: NaverTransitEvent,
        state: EventFirstState,
        nowMs: Long,
    ): Outcome = when (event) {
        // Naver announcing that it started is not news. The next wait event briefs.
        is NaverTransitEvent.GuidanceStart ->
            Outcome(silence(Reason.SILENCE_GUIDANCE_START), state)

        // Guidance is over: nothing we remember describes the present any more.
        is NaverTransitEvent.GuidanceEnd ->
            Outcome(silence(Reason.SILENCE_GUIDANCE_END), EventFirstState())

        is NaverTransitEvent.WaitBus -> wait(
            scopeKey = event.scopeKey,
            arrivals = event.arrivals,
            briefType = SpeechType.BUS_BRIEF,
            stageType = SpeechType.BUS_STAGE,
            state = state,
            nowMs = nowMs,
        )

        is NaverTransitEvent.WaitTrain -> waitTrain(event, state, nowMs)

        is NaverTransitEvent.BoardTrain -> board(event, state, nowMs)

        is NaverTransitEvent.Riding -> riding(state)

        is NaverTransitEvent.AlightSoon -> alight(
            station = event.station,
            scopeKey = event.scopeKey,
            mark = EventFirstState.Mark.ALIGHT_SOON,
            reason = Reason.SPEAK_ALIGHT_SOON,
            speechType = SpeechType.ALIGHT_SOON,
            state = state,
            nowMs = nowMs,
        )

        is NaverTransitEvent.AlightNow -> alight(
            station = event.station,
            scopeKey = event.scopeKey,
            mark = EventFirstState.Mark.ALIGHT_NOW,
            reason = Reason.SPEAK_ALIGHT_NOW,
            speechType = SpeechType.ALIGHT_NOW,
            state = state,
            nowMs = nowMs,
        )

        is NaverTransitEvent.AlightTransfer -> transfer(event, state, nowMs)
    }

    /**
     * The event itself says we are aboard, so the board we were waiting on is
     * dropped — 10 / 5 / 2 / 곧 must not keep counting down after boarding. No
     * Journey is consulted to confirm that this ride is "ours".
     */
    private fun riding(state: EventFirstState): Outcome {
        val waitScope = state.reading?.scopeKey
        val next = (if (waitScope == null) state else state.forgetScope(waitScope)).clearWait()
        return Outcome(silence(Reason.SILENCE_RIDING), next)
    }

    /**
     * 10 / 5 / 2 / 곧 for one board. [scopeKey] comes from the event (stop+route
     * or station+line), so a renamed stop or a different route is simply a
     * different scope — no Journey lookup decides that.
     */
    private fun wait(
        scopeKey: String,
        arrivals: List<NavigationBusArrival>,
        briefType: SpeechType,
        stageType: SpeechType,
        state: EventFirstState,
        nowMs: Long,
    ): Outcome {
        val target = EventFirstPolicy.soonest(arrivals)
            ?: return Outcome(silence(Reason.SILENCE_NO_ARRIVALS), state)
        val previous = state.reading

        // A jump the clock cannot explain waits for the same value to repeat.
        // Checked against the last reading even across scopes: one physical bus
        // can show up under two stop names while Naver re-routes.
        if (previous != null && EventFirstPolicy.isSuspectJump(previous, target.eta, nowMs)) {
            val held = state.pending?.takeIf { EventFirstPolicy.continuesPending(it, target.eta) }
            if (held == null || nowMs - held.sinceMs < EventFirstPolicy.CONFIRM_MS) {
                val pending = held ?: EventFirstState.Pending(target.eta, nowMs)
                return Outcome(
                    silence(Reason.SILENCE_PENDING),
                    state.copy(pending = pending),
                )
            }
        }

        val continued = previous != null &&
            previous.scopeKey == scopeKey &&
            EventFirstPolicy.isSameVehicle(
                NavigationBusArrival(line = previous.line, eta = previous.eta),
                target,
            )
        val base = if (continued) state else state.forgetScope(scopeKey)
        val next = base.copy(pending = null, reading = readingFor(previous, continued, scopeKey, target, nowMs))

        val stage = EventFirstPolicy.stageForEta(target.eta)
            ?: return Outcome(silence(Reason.SILENCE_NOT_A_STAGE), next)
        val marks = next.marks(scopeKey)
        val mark = EventFirstPolicy.markForStage(stage)
        if (mark in marks) return Outcome(silence(Reason.SILENCE_ALREADY_SPOKEN), next)

        val brief = EventFirstState.Mark.BRIEF !in marks
        var consumed = EventFirstPolicy.marksConsumedBy(stage)
        if (brief) consumed = consumed + EventFirstState.Mark.BRIEF

        // 곧 is cooldown-exempt, and so is the first cue for a board we have not
        // spoken about yet — the same exemptions BusWaitCore grants stage 1 and
        // a switched vehicle.
        val last = next.lastSpokenAtMs
        if (!brief && stage > 1 && last != null && nowMs - last < EventFirstPolicy.COOLDOWN_MS) {
            return Outcome(
                silence(Reason.SILENCE_COOLDOWN),
                next.withMarks(scopeKey, consumed),
            )
        }
        return Outcome(
            EventFirstDecision.speak(
                reason = if (brief) Reason.SPEAK_BRIEF else reasonForStage(stage),
                speechType = if (brief) briefType else stageType,
            ),
            next.withMarks(scopeKey, consumed).spokenAt(nowMs),
        )
    }

    /** Two 방면 on one board means the direction is unknown, and we do not guess. */
    private fun waitTrain(
        event: NaverTransitEvent.WaitTrain,
        state: EventFirstState,
        nowMs: Long,
    ): Outcome {
        if (event.departures.isEmpty()) {
            return Outcome(silence(Reason.SILENCE_NO_ARRIVALS), state)
        }
        if (event.directions.size > 1) {
            return Outcome(silence(Reason.SILENCE_AMBIGUOUS_DIRECTION), state)
        }
        return wait(
            scopeKey = event.scopeKey,
            arrivals = event.arrivals,
            briefType = SpeechType.TRAIN_BRIEF,
            stageType = SpeechType.TRAIN_STAGE,
            state = state,
            nowMs = nowMs,
        )
    }

    /**
     * Boarding replaces waiting: the wait scope for this station and line is
     * forgotten so 10 / 5 / 2 / 곧 cannot keep counting down behind us.
     */
    private fun board(
        event: NaverTransitEvent.BoardTrain,
        state: EventFirstState,
        nowMs: Long,
    ): Outcome {
        val waitScope = "train|${event.station}|${event.line}"
        val next = state.forgetScope(waitScope).clearWait()
        if (next.hasMark(event.scopeKey, EventFirstState.Mark.BOARD)) {
            return Outcome(silence(Reason.SILENCE_ALREADY_SPOKEN), next)
        }
        return Outcome(
            EventFirstDecision.speak(Reason.SPEAK_BOARD, SpeechType.BOARD_TRAIN),
            next.withMarks(event.scopeKey, setOf(EventFirstState.Mark.BOARD)).spokenAt(nowMs),
        )
    }

    private fun alight(
        station: String?,
        scopeKey: String,
        mark: EventFirstState.Mark,
        reason: Reason,
        speechType: SpeechType,
        state: EventFirstState,
        nowMs: Long,
    ): Outcome {
        if (station.isNullOrEmpty()) {
            return Outcome(silence(Reason.SILENCE_NO_STATION), state)
        }
        val next = state.clearWait()
        if (next.hasMark(scopeKey, mark)) {
            return Outcome(silence(Reason.SILENCE_ALREADY_SPOKEN), next)
        }
        return Outcome(
            EventFirstDecision.speak(reason, speechType),
            next.withMarks(scopeKey, setOf(mark)).spokenAt(nowMs),
        )
    }

    /**
     * 하차 and 환승 arrive on the same timestamp. Whichever lands first says the
     * station; the transfer cue then carries only the door and the next line, so
     * one station never gets the same sentence twice — and the order the two
     * arrive in does not matter.
     */
    private fun transfer(
        event: NaverTransitEvent.AlightTransfer,
        state: EventFirstState,
        nowMs: Long,
    ): Outcome {
        val next = state.clearWait()
        if (next.hasMark(event.scopeKey, EventFirstState.Mark.TRANSFER)) {
            return Outcome(silence(Reason.SILENCE_ALREADY_SPOKEN), next)
        }
        val alreadyAlighted = next.hasMark(event.scopeKey, EventFirstState.Mark.ALIGHT_NOW)
        return Outcome(
            EventFirstDecision.speak(
                reason = Reason.SPEAK_TRANSFER,
                speechType = if (alreadyAlighted) SpeechType.TRANSFER_ONLY
                else SpeechType.TRANSFER_WITH_ALIGHT,
            ),
            next.withMarks(
                event.scopeKey,
                setOf(EventFirstState.Mark.TRANSFER, EventFirstState.Mark.ALIGHT_NOW),
            ).spokenAt(nowMs),
        )
    }

    private fun readingFor(
        previous: EventFirstState.Reading?,
        continued: Boolean,
        scopeKey: String,
        target: NavigationBusArrival,
        nowMs: Long,
    ): EventFirstState.Reading {
        val sameEta = continued && previous != null && previous.eta == target.eta
        return EventFirstState.Reading(
            scopeKey = scopeKey,
            line = target.line,
            eta = target.eta,
            firstSeenAtMs = if (sameEta) previous.firstSeenAtMs else nowMs,
            soonAtMs = when {
                EventFirstPolicy.isSoon(target.eta) -> nowMs
                continued -> previous?.soonAtMs
                else -> null
            },
        )
    }

    private fun reasonForStage(stage: Int): Reason = when (stage) {
        1 -> Reason.SPEAK_SOON
        2 -> Reason.SPEAK_STAGE_2
        5 -> Reason.SPEAK_STAGE_5
        else -> Reason.SPEAK_STAGE_10
    }

    private fun silence(reason: Reason) = EventFirstDecision.silence(reason)
}
