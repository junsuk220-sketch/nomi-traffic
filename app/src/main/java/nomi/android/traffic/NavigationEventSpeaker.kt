package nomi.android.traffic

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import nomi.android.traffic.api.transfer.Transfer
import nomi.android.traffic.scope.CurrentGuidanceScope
import nomi.android.traffic.speech.SpeechLedger
import nomi.android.traffic.speech.SpeechMouth
import nomi.android.traffic.speech.SpeechSink
import nomi.android.traffic.api.seoulcountdown.SeoulSubwayCountdownShadow
import nomi.product.nav.NavigationEvent
import nomi.product.nav.NavigationEventSource
import nomi.product.nav.NavigationEventType

/**
 * Host voice layer: [NavigationEvent] → phrase → [SpeechSink].
 * Naver and Google keep separate bus trackers / stage gates upstream.
 * Walk events are ignored. TTS lives in [SpeechSink].
 */
class NavigationEventSpeaker(
    context: Context,
    private val gate: NavigationEventSpeechGate = NavigationEventSpeechGate(),
    private val naverBusWait: NaverBusWaitTracker = NaverBusWaitTracker(),
    private val sink: SpeechSink = SpeechSink(context),
) {
    private val lock = Any()

    fun offer(event: NavigationEvent) {
        when (event.type) {
            NavigationEventType.WALK -> Unit
            NavigationEventType.TRANSIT -> when (event.source) {
                NavigationEventSource.NAVER -> offerNaverTransit(event)
                NavigationEventSource.GOOGLE -> offerGoogleTransit(event)
            }
        }
    }

    fun ledger(): SpeechLedger = sink.ledger

    fun speakNotice(text: String) {
        val line = text.trim()
        if (line.isEmpty()) return
        speak(line)
    }

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val startBrief = PendingStartBrief()

    /**
     * The trip-start briefing, said twice, [NaverTripStartSession.BRIEF_AFTER_NAVER_MS]
     * after Naver's own start line. Marked spoken now so the other path stays quiet.
     * The timer and the next Naver event after the due time both fire it; first one wins.
     */
    fun briefStart(text: String, bus: Boolean) {
        val line = text.trim()
        if (line.isEmpty()) return
        if (!firstLegAllows(line, "brief")) return
        val now = System.currentTimeMillis()
        NaverTripStartSession.markSpoken(now)
        val key = briefRouteKey(line)
        if (NaverTripStartSession.isLateClickRepeat(key)) {
            Log.i(NaverMapNotification.TAG, "[NAVER_TRIP] briefing skip late_click_same_route=$key")
            return
        }
        val delayMs = NaverTripStartSession.msUntilBriefing(now)
        val id = synchronized(lock) { startBrief.schedule(line, bus, now + delayMs) }
        Log.i(NaverMapNotification.TAG, "[NAVER_TRIP] briefing scheduled delayMs=$delayMs")
        NaverTripStartDebug.note("brief_scheduled delayMs=$delayMs line=[$line] ${NaverTripStartSession.debugSnapshot(now)}")
        main.postDelayed({ fireStartBrief("timer", timerId = id) }, delayMs)
    }

    /** Any Naver event. Speaks a briefing whose wait is over. */
    fun tickStartBrief() {
        fireStartBrief("event", timerId = null)
    }

    fun cancelStartBrief() {
        val dropped = synchronized(lock) { startBrief.cancel() }
        if (dropped) Log.i(NaverMapNotification.TAG, "[NAVER_TRIP] briefing cancelled")
    }

    private fun fireStartBrief(how: String, timerId: Int?) {
        val now = System.currentTimeMillis()
        val due = synchronized(lock) { startBrief.takeDue(now, timerId) } ?: return
        if (!NaverTripStartSession.hasSpokenBriefing()) {
            Log.i(NaverMapNotification.TAG, "[NAVER_TRIP] briefing skip how=$how session_not_spoken")
            return
        }
        if (!firstLegAllows(due.line, "fire")) {
            NaverTripStartSession.reopenBriefing()
            synchronized(lock) { gate.resetNaverTripStart() }
            return
        }
        Log.i(NaverMapNotification.TAG, "[NAVER_TRIP] briefing fire how=$how")
        NaverTripStartDebug.note("brief_fire how=$how line=[${due.line}] ${NaverTripStartSession.debugSnapshot(now)}")
        speak(startBriefTwice(due.line))
        val spokenAt = System.currentTimeMillis()
        NaverTripStartSession.markSpoken(spokenAt)
        NaverTripStartSession.noteBriefed(briefRouteKey(due.line), spokenAt)
        if (due.bus) naverBusWait.noteSpoken()
    }

    private fun offerNaverTransit(event: NavigationEvent) {
        val promoted = promoteArmedTripStart(event)
        val incoming = promoted ?: event
        if (promoted != null) {
            NaverTripStartDebug.note(
                "STEP3 promoteArmedTripStart action=${promoted.action} " +
                    "raw=${promoted.busInfo?.raw.orEmpty()} " +
                    "fromAction=${event.action}",
            )
        }
        if (incoming.action == NaverMapsTransit.TRIP_START_ACTION) {
            if (LeftoverSubway.againstBusScope(incoming.rawText)) {
                pinFromTripStart(incoming)
                Log.i(
                    NaverMapNotification.TAG,
                    "[NAVER_TRIP] skip leftover subway " +
                        "scope=BUS line=${CurrentGuidanceScope.snapshot().line.orEmpty()}",
                )
                return
            }
            if (!firstLegAllows(NavigationEventSpeech.line(incoming), "offer")) return
            if (NaverTripStartSession.hasSpokenBriefing()) {
                pinFromTripStart(incoming)
                Log.i(NaverMapNotification.TAG, "[NAVER_TRIP] skip start, briefing already spoken")
                return
            }
            pinFromTripStart(incoming)
            val line = NavigationEventSpeech.line(incoming)
            if (line == null) {
                NaverTripStartDebug.step5(
                    action = incoming.action,
                    promoted = promoted != null,
                    lineNull = true,
                    line = "",
                    accepted = false,
                    drop = "tripStartLine_null",
                )
                return
            }
            val accepted = synchronized(lock) { gate.accept(incoming) }
            if (!accepted) {
                NaverTripStartDebug.step5(
                    action = incoming.action,
                    promoted = promoted != null,
                    lineNull = false,
                    line = line,
                    accepted = false,
                    drop = "duplicate_or_gate_reject",
                )
                return
            }
            NaverTripStartDebug.step5(
                action = incoming.action,
                promoted = promoted != null,
                lineNull = false,
                line = line,
                accepted = true,
                drop = null,
            )
            briefStart(line, bus = incoming.rawText == NaverMapsTransit.KIND_BUS)
            if (incoming.rawText == NaverMapsTransit.KIND_BUS &&
                incoming.busInfo?.arrivals.orEmpty().size >= 2
            ) {
                NaverNextTrainNotice.request()
            }
            flushNextTrain()
            return
        }
        if (event.action == NaverMapsTransit.TRANSFER_WALK_ACTION ||
            event.action == NaverMapsTransit.BOARD_DIRECTION_ACTION
        ) {
            if (event.action == NaverMapsTransit.BOARD_DIRECTION_ACTION) {
                pinFromBoardDirection(event)
            }
            val line = NavigationEventSpeech.line(event) ?: return
            val accepted = synchronized(lock) { gate.accept(event) }
            if (!accepted) return
            speak(line)
            return
        }
        if (NaverTripStartSession.holdBusWait() &&
            event.action != NaverMapsTransit.TRIP_START_ACTION &&
            event.action != NaverMapsTransit.TRANSFER_WALK_ACTION &&
            event.action != NaverMapsTransit.BOARD_DIRECTION_ACTION &&
            !isNaverSubwayCar(event) &&
            event.busInfo?.arrivals?.isNotEmpty() == true
        ) {
            if (event.rawText == NaverMapsTransit.KIND_SUBWAY ||
                event.busInfo?.arrivals.orEmpty().size >= 2
            ) {
                speakNextTrainOnce(event)
            }
            Log.i(NaverMapNotification.TAG, "[NAVER_TRIP] hold bus until briefing")
            return
        }
        // Naver itself says the stop before alighting; saying it again doubles the voice.
        if (event.action == NaverMapsTransit.PREPARE_ALIGHT_ACTION) {
            val line = NavigationEventSpeech.line(event) ?: return
            val accepted = synchronized(lock) { gate.accept(event) }
            if (!accepted) return
            Log.i(NaverMapNotification.TAG, "[NAVER_PREPARE_ALIGHT] naver speaks, silent $line")
            return
        }
        // Observation tier: the car numbers already ride on the transfer wait line, and
        // this separate cue has never spoken on a device. Log it, do not speak it.
        // Promotion needs field evidence + a protection test (constitution 8-2).
        if (isNaverSubwayCar(event)) {
            Log.i(
                NaverMapNotification.TAG,
                "[NAVER_SUBWAY_CAR] observed only " +
                    "dir=${event.landmark.orEmpty()} cars=${event.rawText}",
            )
            return
        }
        // Subway 10/5/2/곧 from 302 clocks — bypass bus wait tracker.
        if (event.rawText == NaverMapsTransit.KIND_SUBWAY &&
            event.busInfo?.arrivals?.isNotEmpty() == true
        ) {
            if (LeftoverSubway.againstBusScope(event.rawText)) {
                Log.i(
                    NaverMapNotification.TAG,
                    "[NAVER_SUBWAY_STAGE] skip leftover subway " +
                        "scope=BUS line=${CurrentGuidanceScope.snapshot().line.orEmpty()}",
                )
                return
            }
            val arrival = event.busInfo?.arrivals?.firstOrNull()
            val subwayLine = arrival?.line.orEmpty()
            if (SeoulSubwayCountdownShadow.owns(subwayLine)) {
                Log.i(
                    NaverMapNotification.TAG,
                    "[NAVER_SUBWAY_STAGE] skip seoul countdown owns line=$subwayLine",
                )
                return
            }
            if (!NaverSubwayPin.allows(subwayLine)) {
                Log.i(
                    NaverMapNotification.TAG,
                    "[NAVER_SUBWAY_STAGE] skip unpinned line=$subwayLine " +
                        "pin=${NaverSubwayPin.pinned().orEmpty()} " +
                        "eta=${arrival?.eta.orEmpty()}",
                )
                return
            }
            val waitEvent = synchronized(lock) {
                gate.rememberNaverSubwayClocks(event)
                gate.withRememberedSubwayNext(event)
            }
            if (gate.isArrivedOnly(waitEvent)) {
                Log.i(NaverMapNotification.TAG, "[NAVER_SUBWAY_STAGE] skip arrived line=$subwayLine")
                return
            }
            speakNextTrainOnce(waitEvent)
            val line = NavigationEventSpeech.line(waitEvent) ?: return
            val brief = synchronized(lock) { gate.acceptNaverSubwayWalkBrief(waitEvent) }
            if (brief) {
                if (Transfer.seoulOwnsAlight(subwayLine)) {
                    Log.i(
                        NaverMapNotification.TAG,
                        "[NAVER_SUBWAY_BRIEF] skip seoul owns line=$subwayLine",
                    )
                    return
                }
                Log.i(NaverMapNotification.TAG, "[NAVER_SUBWAY_BRIEF] speak $line")
                speak(line)
                return
            }
            val accepted = synchronized(lock) { gate.accept(waitEvent) }
            if (!accepted) {
                Log.i(
                    NaverMapNotification.TAG,
                    "[NAVER_SUBWAY_STAGE] skip line=$subwayLine " +
                        "eta=${arrival?.eta.orEmpty()}",
                )
                return
            }
            Log.i(NaverMapNotification.TAG, "[NAVER_SUBWAY_STAGE] speak $line")
            speak(line)
            return
        }
        val focused = synchronized(lock) {
            NaverBusWaitSpeech.focusedEvent(
                tracker = naverBusWait,
                event = event,
                ensureSeed = {
                    if (naverBusWait.pinnedBusLines().isEmpty()) {
                        NaverTripStartCache.peek()?.let { pinFromTripStart(it) }
                    }
                },
            )
        } ?: return
        val line = NavigationEventSpeech.line(focused) ?: return
        Log.i(
            NaverMapNotification.TAG,
            "[NAVER_BUS_STAGE] speak $line",
        )
        speak(line)
    }

    private fun offerGoogleTransit(event: NavigationEvent) {
        val line = NavigationEventSpeech.line(event) ?: return
        val accepted = synchronized(lock) { gate.accept(event) }
        if (!accepted) return
        speak(line)
    }

    private fun speakNextTrainOnce(@Suppress("UNUSED_PARAMETER") event: NavigationEvent) {
        // The next train rides on the wait line itself. There is no separate cue.
    }

    private fun flushNextTrain() {
        val pending = NaverNextTrainNotice.takePending() ?: return
        speakNextTrainOnce(pending)
    }

    /** 302 clocks arrived while the sheet briefing is still pending. */
    private fun promoteArmedTripStart(event: NavigationEvent): NavigationEvent? {
        if (!NaverTripStartSession.due(System.currentTimeMillis())) return null
        return NaverTripStartParser.asTripStart(event)
    }

    /** Every trip-start path passes here: a briefing that names another leg than the start screen's first one stays quiet. */
    private fun firstLegAllows(vehicle: String?, where: String): Boolean {
        val leg = NaverTripStartSession.firstLeg()
        if (NaverFirstLeg.allows(vehicle, leg)) return true
        Log.i(
            NaverMapNotification.TAG,
            "[NAVER_TRIP] briefing skip not_first_leg at=$where vehicle=${vehicle.orEmpty()} leg=${leg?.label()}",
        )
        return false
    }

    private fun pinFromTripStart(event: NavigationEvent) {
        val pinned = NaverTripPin.apply(event, naverBusWait) ?: return
        Log.i(NaverMapNotification.TAG, "[NAVER_PIN] $pinned")
    }

    private fun pinFromBoardDirection(event: NavigationEvent) {
        val line = event.busInfo?.arrivals?.firstOrNull()?.line?.trim().orEmpty()
        if (line.isEmpty()) return
        naverBusWait.pinBusLine(line)
        Log.i(NaverMapNotification.TAG, "[NAVER_PIN] bus board=$line")
    }

    private fun isNaverSubwayCar(event: NavigationEvent): Boolean {
        if (event.action != NaverMapsTransit.QUICK_EXIT &&
            event.action != NaverMapsTransit.QUICK_TRANSFER
        ) {
            return false
        }
        val direction = event.landmark?.trim().orEmpty()
        return direction.endsWith("방면") && event.rawText.trim().isNotEmpty()
    }

    private var lastSpoken: SpeechMouth.Spoken? = null

    private fun speak(text: String) {
        val admitted = SpeechMouth.admit(text, System.currentTimeMillis(), lastSpoken)
        if (admitted == null) {
            NaverTripStartDebug.step6(text = text, path = "mouth_swallowed")
            return
        }
        lastSpoken = admitted.second
        NaverTripStartDebug.step6(text = admitted.first, path = "sink")
        sink.speak(admitted.first)
    }

    fun shutdown() {
        sink.shutdown()
    }
}

/** The line a briefing opens with: "99번, …" → 99번, "3호선이 …" → 3호선. */
internal fun briefRouteKey(line: String): String? {
    val head = line.trim().split(' ', ',').firstOrNull { it.isNotBlank() } ?: return null
    return head.removeSuffix("이").ifBlank { null }
}

/**
 * One utterance: the whole briefing, then its first sentence (the first vehicle) once more.
 * Two separate lines would be swallowed as a repeat.
 */
internal fun startBriefTwice(line: String): String {
    val text = line.trim()
    if (text.isEmpty()) return text
    val first = Regex("""^.*?[.!?](?=\s|$)""").find(text)?.value ?: text
    return "$text $first"
}
