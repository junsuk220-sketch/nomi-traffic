package nomi.android.traffic

import android.content.Context
import android.util.Log
import nomi.android.traffic.speech.SpeechSink
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

    fun speakNotice(text: String) {
        val line = text.trim()
        if (line.isEmpty()) return
        speak(line)
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
            speak(line)
            NaverTripStartSession.markSpoken(System.currentTimeMillis())
            if (incoming.rawText == NaverMapsTransit.KIND_BUS) {
                naverBusWait.noteSpoken()
            }
            if (incoming.rawText == NaverMapsTransit.KIND_BUS &&
                incoming.busInfo?.arrivals.orEmpty().size >= 2
            ) {
                NaverNextTrainNotice.request()
            }
            flushNextTrain()
            return
        }
        if (synchronized(lock) { gate.noteAlightThenTransfer(event.title, event.action) }) {
            Log.i(NaverMapNotification.TAG, "[NAVER_TRANSFER_SUBWAY] pending first subway ETA")
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
        if (event.action == NaverMapsTransit.PREPARE_ALIGHT_ACTION) {
            val line = NavigationEventSpeech.line(event) ?: return
            val accepted = synchronized(lock) { gate.accept(event) }
            if (!accepted) return
            Log.i(NaverMapNotification.TAG, "[NAVER_PREPARE_ALIGHT] speak $line")
            speak(line)
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
            val arrival = event.busInfo?.arrivals?.firstOrNull()
            val subwayLine = arrival?.line.orEmpty()
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
            speakNextTrainOnce(waitEvent)
            val line = NavigationEventSpeech.line(waitEvent) ?: return
            val transferBrief = synchronized(lock) { gate.acceptTransferSubwayBrief(waitEvent) }
            if (transferBrief) {
                val detailed = NavigationEventSpeech.line(waitEvent, includeFastAlight = true) ?: line
                Log.i(NaverMapNotification.TAG, "[NAVER_TRANSFER_SUBWAY] speak $detailed")
                speak(detailed)
                return
            }
            val brief = synchronized(lock) { gate.acceptNaverSubwayWalkBrief(waitEvent) }
            if (brief) {
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

    private fun speak(text: String) {
        NaverTripStartDebug.step6(text = text, path = "sink")
        sink.speak(text)
    }

    fun shutdown() {
        sink.shutdown()
    }
}
