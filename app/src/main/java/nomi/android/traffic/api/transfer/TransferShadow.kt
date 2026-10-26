package nomi.android.traffic.api.transfer

import android.os.Handler
import android.os.Looper
import nomi.android.traffic.NavigationEventVoice
import nomi.android.traffic.api.nextsubway.NextSubway
import nomi.android.traffic.api.seoul.SeoulRealtimeArrival
import nomi.android.traffic.api.seoulcountdown.SeoulSubwayCountdownShadow
import nomi.android.traffic.observe.TransitReading
import nomi.android.traffic.observe.TransitRow
import nomi.traffic.BuildConfig
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Host for Transfer.
 * Alight briefing: Seoul minutes only, after Naver's alight line.
 * Near-board briefing: Naver sheet minutes only, after Naver's near-board line.
 */
object TransferShadow {

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val fetchStarted = AtomicBoolean(false)
    private var speakGeneration = 0
    private var lastArmGeneration = 0

    fun tick(
        cues: List<String>,
        reading: TransitReading?,
        liveScreen: Boolean = true,
    ) {
        if (!liveScreen) {
            NextSubway.consider(cues, learn = false)
            return
        }
        val next = NextSubway.consider(cues)
        val outcome = Transfer.consider(
            cues = cues,
            reading = reading,
            next = next,
            nowMs = System.currentTimeMillis(),
            chain = NextSubway.chain(),
            startLeg = NextSubway.startLeg(),
            takeTarget = { NextSubway.takeTransferTarget(it) },
        )
        SeoulSubwayCountdownShadow.syncLifecycle()
        val gen = Transfer.armGeneration()
        if (gen != lastArmGeneration) {
            lastArmGeneration = gen
            fetchStarted.set(false)
            TransferShadowDebug.emit(
                "armed_transfer gen=$gen chain=${NextSubway.cardNote() ?: "-"}",
            )
        }
        TransferShadowDebug.emit(outcome, reading, Transfer.board())
        val text = outcome.request?.text?.trim().orEmpty()
        if (text.isNotEmpty()) {
            scheduleTransferSpeech(text)
            return
        }
        if (outcome.silence == TransferSilence.RESET) {
            fetchStarted.set(false)
            speakGeneration += 1
            return
        }
        if (outcome.silence == TransferSilence.ARMED ||
            outcome.silence == TransferSilence.WAIT_ETA
        ) {
            maybeFetchSeoul()
        }
    }

    private fun maybeFetchSeoul() {
        if (!Transfer.needsSeoulFetch()) return
        val board = Transfer.board() ?: return
        val key = BuildConfig.SEOUL_SUBWAY_API_KEY.trim()
        if (key.isEmpty()) {
            TransferShadowDebug.emit("seoul_skip reason=no_key")
            return
        }
        if (!fetchStarted.compareAndSet(false, true)) return
        TransferShadowDebug.emit(
            "seoul_fetch station=${board.stationApiName} line=${board.line} " +
                "dir=${board.direction ?: "-"}",
        )
        io.execute {
            var rows = emptyList<TransitRow>()
            for (attempt in 1..4) {
                if (!Transfer.needsSeoulFetch()) break
                val arrivals = try {
                    SeoulRealtimeArrival.fetch(board.stationApiName, key)
                } catch (_: Throwable) {
                    emptyList()
                }
                rows = SeoulRealtimeArrival.rowsFor(
                    arrivals = arrivals,
                    line = board.line,
                    direction = board.direction,
                )
                TransferShadowDebug.emit(
                    "seoul_try attempt=$attempt station=${board.stationApiName} " +
                        "line=${board.line} rows=${rows.size}",
                )
                if (rows.isNotEmpty()) break
                try {
                    Thread.sleep(800)
                } catch (_: InterruptedException) {
                    break
                }
            }
            val ready = rows
            main.post {
                if (!Transfer.needsSeoulFetch()) {
                    fetchStarted.set(false)
                    TransferShadowDebug.emit(
                        "seoul_drop station=${board.stationApiName} line=${board.line}",
                    )
                    return@post
                }
                if (ready.isEmpty()) {
                    fetchStarted.set(false)
                    TransferShadowDebug.emit(
                        "seoul_empty station=${board.stationApiName} line=${board.line}",
                    )
                    return@post
                }
                val outcome = Transfer.considerSeoul(ready)
                TransferShadowDebug.emit(outcome, reading = null, board = Transfer.board())
                val text = outcome.request?.text?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    Transfer.board()?.let { SeoulSubwayCountdownShadow.arm(it, ready) }
                    scheduleTransferSpeech(text)
                } else {
                    fetchStarted.set(false)
                }
            }
        }
    }

    private fun scheduleTransferSpeech(text: String) {
        val gen = ++speakGeneration
        TransferShadowDebug.emit("speak_scheduled delayMs=$AFTER_NAVER_MS")
        main.postDelayed({
            if (gen != speakGeneration) return@postDelayed
            if (!Transfer.mayPlayBriefing()) return@postDelayed
            NavigationEventVoice.playTransferBriefing(spokenTwice(text))
            TransferShadowDebug.emit("speak_played")
        }, AFTER_NAVER_MS)
    }

    internal fun resetForTest() {
        fetchStarted.set(false)
        speakGeneration += 1
        SeoulSubwayCountdownShadow.resetForTest()
    }

    private const val AFTER_NAVER_MS = 7_000L
}

/**
 * One utterance: the whole briefing, then its first sentence (the first train) once more.
 * Two separate lines would be swallowed as a repeat.
 */
internal fun spokenTwice(text: String): String {
    val line = text.trim()
    if (line.isEmpty()) return line
    val first = Regex("""^.*?[.!?](?=\s|$)""").find(line)?.value ?: line
    return "$line $first"
}
