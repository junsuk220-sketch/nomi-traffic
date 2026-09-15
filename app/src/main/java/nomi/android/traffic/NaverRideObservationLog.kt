package nomi.android.traffic

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Dev-only Naver ride observer. Writes Accessibility / notification snapshots
 * to filesDir JSONL. Does not parse, pin, speak, or change wait stages.
 *
 * Default [enabled] is false. For a field capture set it true, or place an
 * empty flag file `naver-ride-observe.enabled` next to the log before the
 * services connect.
 */
internal object NaverRideObservationLog {

    const val FILE_NAME = "naver-ride-observe.jsonl"
    const val ROTATE_NAME = "naver-ride-observe.1.jsonl"
    const val FLAG_NAME = "naver-ride-observe.enabled"
    const val MAX_BYTES = 2L * 1024L * 1024L
    const val MAX_AGE_MS = 24L * 60L * 60L * 1000L
    const val TREE_HEARTBEAT_MS = 20_000L
    const val EVENT_HEARTBEAT_MS = 10_000L
    const val NOTIF_HEARTBEAT_MS = 10_000L
    const val MAX_BLOBS = 80
    const val MAX_BLOB_CHARS = 120

    @Volatile
    var enabled: Boolean = true

    private val lock = Any()
    private var dir: File? = null
    private var openedAtMs = 0L
    private var lastTreeKey: String? = null
    private var lastTreeAtMs = 0L
    private var lastEventKey: String? = null
    private var lastEventAtMs = 0L
    private var lastNotifKey: String? = null
    private var lastNotifAtMs = 0L

    @Volatile
    internal var lastWriteThreadName: String? = null

    @Volatile
    internal var writeInline: Boolean = false

    private val executor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "naver-ride-observe").apply { isDaemon = true }
    }

    fun attach(filesDir: File) {
        synchronized(lock) {
            dir = filesDir
            if (!enabled && File(filesDir, FLAG_NAME).exists()) {
                enabled = true
            }
            if (openedAtMs == 0L) {
                val existing = File(filesDir, FILE_NAME)
                openedAtMs = if (existing.exists()) existing.lastModified().takeIf { it > 0L }
                    ?: System.currentTimeMillis()
                else {
                    0L
                }
            }
        }
    }

    fun recordA11yEvent(
        ts: Long,
        pkg: String,
        interactive: Boolean,
        a11yType: String,
        className: String?,
        text: String?,
        contentDescription: String?,
        viewId: String?,
        skipDedup: Boolean = false,
    ) {
        if (!enabled) return
        val clippedText = clip(text)
        val clippedDesc = clip(contentDescription)
        val key = "$a11yType|$interactive|$className|$clippedText|$clippedDesc|$viewId"
        if (!skipDedup && !acceptRepeat(key, ts, event = true)) return
        submit(
            encode(
                ts = ts,
                src = "a11y_event",
                pkg = pkg,
                interactive = interactive,
                a11yType = a11yType,
                className = className,
                text = clippedText,
                contentDescription = clippedDesc,
                viewId = viewId,
            ),
        )
    }

    fun recordA11yTree(
        ts: Long,
        pkg: String,
        live: Boolean,
        blobs: List<String>,
    ) {
        if (!enabled) return
        val kept = selectBlobs(blobs)
        val key = "$live|${kept.joinToString("\u0001")}"
        if (!acceptRepeat(key, ts, tree = true)) return
        submit(
            encode(
                ts = ts,
                src = "a11y_tree",
                pkg = pkg,
                interactive = true,
                live = live,
                blobs = kept,
            ),
        )
    }

    fun recordNotification(
        ts: Long,
        pkg: String,
        interactive: Boolean,
        id: Int,
        channel: String?,
        title: String?,
        text: String?,
        bigText: String?,
        nowbarPrimary: String?,
        nowbarSecondary: String?,
        chip: String?,
        primary: String?,
        secondary: String?,
        progress: Int?,
        chipExpandedText: String?,
    ) {
        if (!enabled) return
        val key = listOf(
            id.toString(),
            channel,
            title,
            text,
            bigText,
            nowbarPrimary,
            nowbarSecondary,
            chip,
            primary,
            secondary,
            progress?.toString(),
            chipExpandedText,
        ).joinToString("|")
        if (!acceptRepeat(key, ts, notif = true)) return
        submit(
            encode(
                ts = ts,
                src = "notif",
                pkg = pkg,
                interactive = interactive,
                id = id,
                channel = channel,
                title = clip(title),
                text = clip(text),
                bigText = clip(bigText),
                nowbarPrimary = clip(nowbarPrimary),
                nowbarSecondary = clip(nowbarSecondary),
                chip = clip(chip),
                primary = clip(primary),
                secondary = clip(secondary),
                progress = progress,
                chipExpandedText = clip(chipExpandedText),
            ),
        )
    }

    fun recordNotificationRemoved(
        ts: Long,
        pkg: String,
        interactive: Boolean,
        id: Int,
        channel: String?,
    ) {
        if (!enabled) return
        submit(
            encode(
                ts = ts,
                src = "notif_removed",
                pkg = pkg,
                interactive = interactive,
                id = id,
                channel = channel,
            ),
        )
    }

    fun typeName(type: Int): String = when (type) {
        1 -> "TYPE_VIEW_CLICKED"
        32 -> "TYPE_WINDOW_STATE_CHANGED"
        2048 -> "TYPE_WINDOW_CONTENT_CHANGED"
        else -> "TYPE_$type"
    }

    internal fun selectBlobs(blobs: List<String>): List<String> {
        val out = ArrayList<String>(MAX_BLOBS)
        for (raw in blobs) {
            val text = clip(raw.trim()) ?: continue
            if (!keepBlob(text)) continue
            out.add(text)
            if (out.size >= MAX_BLOBS) break
        }
        return out
    }

    internal fun keepBlob(text: String): Boolean {
        if (NOISE.containsMatchIn(text)) return false
        if (ADDRESS.containsMatchIn(text)) return false
        if (KEEP.containsMatchIn(text)) return true
        if (BUS_LINE.matches(text)) return true
        return PLACE.matches(text)
    }

    internal fun resetForTest(filesDir: File, inline: Boolean = true) {
        synchronized(lock) {
            enabled = true
            writeInline = inline
            dir = filesDir
            openedAtMs = 0L
            lastTreeKey = null
            lastTreeAtMs = 0L
            lastEventKey = null
            lastEventAtMs = 0L
            lastNotifKey = null
            lastNotifAtMs = 0L
            lastWriteThreadName = null
            File(filesDir, FILE_NAME).delete()
            File(filesDir, ROTATE_NAME).delete()
        }
    }

    internal fun disableForTest() {
        synchronized(lock) {
            enabled = false
            writeInline = false
            dir = null
        }
    }

    internal fun flushForTest() {
        if (writeInline) return
        val done = CountDownLatch(1)
        executor.execute { done.countDown() }
        check(done.await(3, TimeUnit.SECONDS)) { "naver-ride-observe writer did not flush" }
    }

    internal fun file(): File = File(dir ?: error("NaverRideObservationLog not attached"), FILE_NAME)

    internal fun rotateFile(): File =
        File(dir ?: error("NaverRideObservationLog not attached"), ROTATE_NAME)

    private fun acceptRepeat(
        key: String,
        ts: Long,
        event: Boolean = false,
        tree: Boolean = false,
        notif: Boolean = false,
    ): Boolean = synchronized(lock) {
        val lastKey: String?
        val lastAt: Long
        val heartbeat: Long
        when {
            tree -> {
                lastKey = lastTreeKey
                lastAt = lastTreeAtMs
                heartbeat = TREE_HEARTBEAT_MS
            }
            notif -> {
                lastKey = lastNotifKey
                lastAt = lastNotifAtMs
                heartbeat = NOTIF_HEARTBEAT_MS
            }
            else -> {
                lastKey = lastEventKey
                lastAt = lastEventAtMs
                heartbeat = EVENT_HEARTBEAT_MS
            }
        }
        if (key == lastKey && ts - lastAt < heartbeat) return false
        when {
            tree -> {
                lastTreeKey = key
                lastTreeAtMs = ts
            }
            notif -> {
                lastNotifKey = key
                lastNotifAtMs = ts
            }
            event -> {
                lastEventKey = key
                lastEventAtMs = ts
            }
        }
        true
    }

    private fun submit(line: String) {
        if (writeInline) {
            writeLine(line)
            return
        }
        executor.execute { writeLine(line) }
    }

    private fun writeLine(line: String) {
        lastWriteThreadName = Thread.currentThread().name
        synchronized(lock) {
            val folder = dir ?: return
            val out = File(folder, FILE_NAME)
            rotateIfNeeded(out, System.currentTimeMillis())
            if (openedAtMs == 0L) openedAtMs = System.currentTimeMillis()
            out.appendText(line + "\n")
        }
    }

    internal fun rotateIfNeeded(out: File, nowMs: Long) {
        val folder = out.parentFile ?: return
        if (!out.exists()) {
            openedAtMs = nowMs
            return
        }
        val ageStart = if (openedAtMs > 0L) openedAtMs else out.lastModified().takeIf { it > 0L } ?: nowMs
        val tooBig = out.length() >= MAX_BYTES
        val tooOld = nowMs - ageStart >= MAX_AGE_MS
        if (!tooBig && !tooOld) return
        val rotated = File(folder, ROTATE_NAME)
        rotated.delete()
        if (!out.renameTo(rotated)) return
        openedAtMs = nowMs
    }

    internal fun encode(
        ts: Long,
        src: String,
        pkg: String,
        interactive: Boolean,
        a11yType: String? = null,
        className: String? = null,
        text: String? = null,
        contentDescription: String? = null,
        viewId: String? = null,
        live: Boolean? = null,
        blobs: List<String>? = null,
        id: Int? = null,
        channel: String? = null,
        title: String? = null,
        bigText: String? = null,
        nowbarPrimary: String? = null,
        nowbarSecondary: String? = null,
        chip: String? = null,
        primary: String? = null,
        secondary: String? = null,
        progress: Int? = null,
        chipExpandedText: String? = null,
    ): String = buildString {
        append('{')
        append("\"ts\":").append(ts)
        append(",\"src\":").append(q(src))
        append(",\"pkg\":").append(q(pkg))
        append(",\"interactive\":").append(interactive)
        if (a11yType != null) append(",\"a11yType\":").append(q(a11yType))
        if (className != null) append(",\"className\":").append(q(className))
        if (text != null) append(",\"text\":").append(q(text))
        if (contentDescription != null) append(",\"contentDescription\":").append(q(contentDescription))
        if (viewId != null) append(",\"viewId\":").append(q(viewId))
        if (live != null) append(",\"live\":").append(live)
        if (blobs != null) {
            append(",\"blobs\":[")
            blobs.forEachIndexed { i, blob ->
                if (i > 0) append(',')
                append(q(blob))
            }
            append(']')
        }
        if (id != null) append(",\"id\":").append(id)
        if (channel != null) append(",\"channel\":").append(q(channel))
        if (title != null) append(",\"title\":").append(q(title))
        if (bigText != null) append(",\"bigText\":").append(q(bigText))
        if (nowbarPrimary != null) append(",\"nowbarPrimary\":").append(q(nowbarPrimary))
        if (nowbarSecondary != null) append(",\"nowbarSecondary\":").append(q(nowbarSecondary))
        if (chip != null) append(",\"chip\":").append(q(chip))
        if (primary != null) append(",\"primary\":").append(q(primary))
        if (secondary != null) append(",\"secondary\":").append(q(secondary))
        if (progress != null) append(",\"progress\":").append(progress)
        if (chipExpandedText != null) append(",\"chipExpandedText\":").append(q(chipExpandedText))
        append('}')
    }

    private fun clip(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (text.length <= MAX_BLOB_CHARS) return text
        return text.substring(0, MAX_BLOB_CHARS)
    }

    private fun q(raw: String): String {
        val escaped = raw
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        return "\"$escaped\""
    }

    private val ADDRESS = Regex(
        """^(경기|서울|인천|부산|대구|대전|광주|울산|세종|강원|충북|충남|전북|전남|경북|경남|제주).{0,80}(로|길)\s*\d+""",
    )
    private val KEEP = Regex(
        """안내|승차|하차|정류장|버스|분|곧|여유|보통|혼잡|도착|경로|도보|환승|전역|방면|호선|이동|걷기|출발|종료|현재|다음|까지|출구|실시간|시간표""",
    )
    private val NOISE = Regex(
        """^(지도|뒤로|메뉴|새로고침|닫기|더보기|광고)|레이어|내 위치 보기|전체경로""",
    )
    private val BUS_LINE = Regex("""^[A-Za-z]?\d{1,4}[A-Za-z]?$""")
    private val PLACE = Regex("""^[가-힣A-Za-z0-9.·() ]{2,40}$""")
}
