package nomi.android.traffic.api.seoulcountdown

import android.os.Handler
import android.os.Looper
import nomi.android.traffic.NavigationEventVoice
import nomi.android.traffic.api.nexttrain.NextTrainShadow
import nomi.android.traffic.api.seoul.SeoulRealtimeArrival
import nomi.android.traffic.api.transfer.Transfer
import nomi.android.traffic.api.transfer.TransferBoard
import nomi.android.traffic.api.transfer.TransferShadowDebug
import nomi.android.traffic.observe.TransitRow
import nomi.traffic.BuildConfig
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Android host for the Seoul-backed transfer-subway countdown cell. */
object SeoulSubwayCountdownShadow {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val fetching = AtomicBoolean(false)
    private var generation = 0
    private var board: TransferBoard? = null
    private var lastSuccessAtMs = 0L

    @Synchronized
    fun arm(board: TransferBoard, rows: List<TransitRow>) {
        val changed = this.board?.let {
            it.stationApiName != board.stationApiName || it.line != board.line ||
                it.direction != board.direction
        } != false
        this.board = board
        lastSuccessAtMs = System.currentTimeMillis()
        SeoulSubwayCountdown.arm(board.line, rows)
        if (changed) {
            generation += 1
            NextTrainShadow.reset()
        }
        if (NextTrainShadow.onPoll(rows).departed) SeoulSubwayCountdown.reopen()
        schedule(generation)
    }

    @Synchronized
    fun syncLifecycle() {
        if (Transfer.mayTrackSeoulCountdown()) return
        resetLocked()
    }

    @Synchronized
    fun owns(line: String?, nowMs: Long = System.currentTimeMillis()): Boolean {
        val want = line?.trim().orEmpty()
        return want.isNotEmpty() && board?.line == want &&
            Transfer.mayTrackSeoulCountdown() &&
            nowMs - lastSuccessAtMs in 0..FRESH_MS
    }

    /** Guidance ended. Drops the 20s poll. Home with the guidance notice still up does not call this. */
    @Synchronized
    fun stop() {
        TransferShadowDebug.emit("seoul_countdown stop reason=guidance_ended")
        resetLocked()
    }

    @Synchronized
    fun resetForTest() {
        resetLocked()
    }

    private fun schedule(gen: Int) {
        main.removeCallbacksAndMessages(TOKEN)
        main.postAtTime({ poll(gen) }, TOKEN, android.os.SystemClock.uptimeMillis() + POLL_MS)
    }

    private fun poll(gen: Int) {
        val current = synchronized(this) {
            if (gen != generation || !Transfer.mayTrackSeoulCountdown()) return
            board
        } ?: return
        val key = BuildConfig.SEOUL_SUBWAY_API_KEY.trim()
        if (key.isEmpty()) {
            TransferShadowDebug.emit("seoul_countdown stop reason=no_key")
            return
        }
        if (!fetching.compareAndSet(false, true)) {
            schedule(gen)
            return
        }
        io.execute {
            val rows = try {
                SeoulRealtimeArrival.rowsFor(
                    arrivals = SeoulRealtimeArrival.fetch(current.stationApiName, key),
                    line = current.line,
                    direction = current.direction,
                )
            } catch (_: Throwable) {
                emptyList()
            }
            main.post {
                fetching.set(false)
                synchronized(this) {
                    if (gen != generation || !Transfer.mayTrackSeoulCountdown()) return@post
                    if (rows.isNotEmpty()) lastSuccessAtMs = System.currentTimeMillis()
                }
                if (NextTrainShadow.onPoll(rows).departed) SeoulSubwayCountdown.reopen()
                if (rows.isNotEmpty()) {
                    val outcome = SeoulSubwayCountdown.consider(rows)
                    TransferShadowDebug.emit(
                        "seoul_countdown line=${current.line} rows=${rows.size} " +
                            "band=${outcome.band ?: "-"} speak=${outcome.text ?: "-"}",
                    )
                    outcome.text?.trim()?.takeIf(String::isNotEmpty)
                        ?.let(NavigationEventVoice::playTransferBriefing)
                } else {
                    TransferShadowDebug.emit(
                        "seoul_countdown empty station=${current.stationApiName} line=${current.line}",
                    )
                }
                schedule(gen)
            }
        }
    }

    private fun resetLocked() {
        generation += 1
        board = null
        lastSuccessAtMs = 0L
        fetching.set(false)
        SeoulSubwayCountdown.reset()
        NextTrainShadow.reset()
        main.removeCallbacksAndMessages(TOKEN)
    }

    private val TOKEN = Any()
    private const val POLL_MS = 20_000L
    private const val FRESH_MS = 90_000L
}
