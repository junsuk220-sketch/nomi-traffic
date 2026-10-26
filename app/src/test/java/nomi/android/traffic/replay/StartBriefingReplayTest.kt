package nomi.android.traffic.replay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import nomi.android.traffic.LeftoverSubway
import nomi.android.traffic.NaverFirstLeg
import nomi.android.traffic.NaverGuidanceRemoved
import nomi.android.traffic.NaverMapNotification
import nomi.android.traffic.NaverMapsTransit
import nomi.android.traffic.NaverNotificationParser
import nomi.android.traffic.NaverSubwayAccessibilityParser
import nomi.android.traffic.NaverTransitDestinationParser
import nomi.android.traffic.NaverTripStartCache
import nomi.android.traffic.NaverTripStartParser
import nomi.android.traffic.NaverTripStartSession
import nomi.android.traffic.NavigationEventSpeech
import nomi.android.traffic.NavigationEventSpeechGate
import nomi.android.traffic.PendingStartBrief
import nomi.android.traffic.api.briefing.StartBriefing
import nomi.android.traffic.api.transfer.Transfer
import nomi.android.traffic.briefRouteKey
import nomi.android.traffic.observe.TransitObservation
import nomi.android.traffic.observe.TransitReading
import nomi.android.traffic.scope.CurrentGuidanceScope
import nomi.android.traffic.scope.GuidanceKind
import nomi.product.nav.NavigationEvent
import nomi.product.nav.NavigationEventType
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Replays pulled Naver field captures (naver-ride-observe JSONL under pull dirs) through the
 * real start-briefing objects and reports, per departure, what would be briefed against
 * the first vehicle of the route summary printed right after `안내 중`.
 *
 * Runs only when NOMI_REPLAY points at the project root. Field data stays local;
 * the report goes to app/build/reports.
 *
 * Capture limits: blobs are filtered and capped (80 blobs, 120 chars), identical
 * trees within 20 s are not re-logged, and window ids are not recorded.
 */
class StartBriefingReplayTest {

    @Test
    fun replayFieldTrips() {
        val root = System.getenv("NOMI_REPLAY")?.let(::File)
        Assume.assumeTrue(root != null && root.isDirectory)
        val load = loadRecords(root!!)
        Assume.assumeTrue(load.records.isNotEmpty())
        val speech = loadSpeech(root)
        val result = Replay(load.records).run()
        val report = render(result, speech, load.skippedFiles)
        val out = File("build/reports/start-briefing-replay.txt")
        out.parentFile.mkdirs()
        out.writeText(report)
    }

    private data class Rec(val ts: Long, val src: String, val obj: JsonObject)

    private class Load(val records: List<Rec>, val skippedFiles: List<String>)

    private fun loadRecords(root: File): Load {
        val seen = HashSet<String>()
        val out = ArrayList<Rec>()
        val skipped = ArrayList<String>()
        root.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("pull") }
            .flatMap { dir ->
                dir.listFiles().orEmpty().filter { it.name.startsWith("naver-ride-observe") && it.name.endsWith(".jsonl") }
            }
            .forEach { file ->
                val head = file.inputStream().use { s -> ByteArray(2).also { s.read(it) } }
                if (head[0] != '{'.code.toByte()) {
                    skipped += "${file.parentFile.name}/${file.name}"
                    return@forEach
                }
                file.forEachLine(Charsets.UTF_8) { line ->
                    if (line.isBlank() || !seen.add(line)) return@forEachLine
                    val obj = try {
                        Json.parseToJsonElement(line).jsonObject
                    } catch (_: Exception) {
                        return@forEachLine
                    }
                    val ts = obj["ts"]?.jsonPrimitive?.longOrNull ?: return@forEachLine
                    val src = obj["src"]?.jsonPrimitive?.content ?: return@forEachLine
                    out.add(Rec(ts, src, obj))
                }
            }
        return Load(out.sortedBy { it.ts }, skipped)
    }

    private data class Spoken(val ts: Long, val text: String)

    private fun loadSpeech(root: File): List<Spoken> {
        val seen = HashSet<String>()
        val out = ArrayList<Spoken>()
        val pattern = Regex("""^(\d{13}) \[SPEECH] dispatch id=\S+ code=\S+ text=(.*)$""")
        root.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("pull") }
            .mapNotNull { File(it, "nomi-speech.log").takeIf(File::isFile) }
            .forEach { file ->
                file.forEachLine(Charsets.UTF_8) { line ->
                    if (!seen.add(line)) return@forEachLine
                    val m = pattern.matchEntire(line) ?: return@forEachLine
                    out.add(Spoken(m.groupValues[1].toLong(), m.groupValues[2]))
                }
            }
        return out.sortedBy { it.ts }
    }

    private data class Arm(val ts: Long, val how: String)
    private data class Leg(val ts: Long, val firstLeg: String, val summary: String)
    private data class Brief(
        val scheduledAt: Long,
        val producer: String,
        val text: String,
        var outcome: String = "scheduled",
        var outcomeAt: Long? = null,
    )
    private data class Candidate(val ts: Long, val producer: String, val text: String)

    private class Result(
        val arms: List<Arm>,
        val legs: List<Leg>,
        val briefs: List<Brief>,
        val candidates: List<Candidate>,
        val skips: List<Candidate>,
        val ends: List<Pair<Long, String>>,
        val locks: List<Pair<Long, NaverFirstLeg.Leg>>,
    )

    private class Replay(private val records: List<Rec>) {
        private val pkg = NaverMapNotification.PACKAGE
        private val gate = NavigationEventSpeechGate()
        private val pending = PendingStartBrief()
        private var pendingBrief: Brief? = null
        private var pendingDueAt: Long? = null
        private var pendingId = 0
        private var wasLive = false
        private var now = 0L
        private var lastTree: List<String>? = null
        private val lastTitleById = HashMap<Int, String?>()
        private val arms = ArrayList<Arm>()
        private val legs = ArrayList<Leg>()
        private val briefs = ArrayList<Brief>()
        private val candidates = ArrayList<Candidate>()
        private val skips = ArrayList<Candidate>()
        private val ends = ArrayList<Pair<Long, String>>()
        private val locks = ArrayList<Pair<Long, NaverFirstLeg.Leg>>()

        fun run(): Result {
            NaverTripStartSession.reset()
            NaverTripStartCache.clear()
            CurrentGuidanceScope.resetForTest()
            CurrentGuidanceScope.clockMs = { now }
            StartBriefing.resetForTest()
            Transfer.resetForTest()
            NaverGuidanceRemoved.resetForTest()
            try {
                for ((i, rec) in records.withIndex()) {
                    fireTimer(rec.ts)
                    now = rec.ts
                    NaverGuidanceRemoved.expire(now)
                    when (rec.src) {
                        "a11y_event" -> onEvent(rec, records.getOrNull(i + 1))
                        "a11y_tree" -> onTree(rec)
                        "notif" -> onNotif(rec)
                        "notif_removed" -> onRemoved(rec)
                    }
                }
                fireTimer(Long.MAX_VALUE)
            } finally {
                NaverGuidanceRemoved.resetForTest()
                CurrentGuidanceScope.resetForTest()
                StartBriefing.resetForTest()
                NaverTripStartSession.reset()
                NaverTripStartCache.clear()
            }
            return Result(arms, legs, briefs, candidates, skips, ends, locks)
        }

        private fun ended(why: String) {
            ends += now to why
            if (pending.cancel()) {
                pendingBrief?.let {
                    it.outcome = "cancelled:$why"
                    it.outcomeAt = now
                }
                pendingBrief = null
                pendingDueAt = null
            }
            gate.resetNaverTripStart()
        }

        private fun fireTimer(until: Long) {
            val due = pendingDueAt ?: return
            if (due > until) return
            pendingDueAt = null
            val brief = pendingBrief
            pendingBrief = null
            val taken = pending.takeDue(due, pendingId) ?: return
            if (!NaverTripStartSession.hasSpokenBriefing()) {
                brief?.outcome = "skip_session_not_spoken"
                brief?.outcomeAt = due
                return
            }
            if (!NaverFirstLeg.allows(taken.line, NaverTripStartSession.firstLeg())) {
                NaverTripStartSession.reopenBriefing()
                gate.resetNaverTripStart()
                brief?.outcome = "not_first_leg_at_fire"
                brief?.outcomeAt = due
                return
            }
            NaverTripStartSession.markSpoken(due)
            NaverTripStartSession.noteBriefed(briefRouteKey(taken.line), due)
            brief?.outcome = "spoken"
            brief?.outcomeAt = due
        }

        private fun onEvent(rec: Rec, next: Rec?) {
            if (rec.obj.str("a11yType") == "TYPE_VIEW_CLICKED") {
                val texts = rec.obj.str("text").orEmpty()
                val desc = rec.obj.str("contentDescription").orEmpty()
                val startClick = NaverTripStartParser.isStartButton(texts) ||
                    NaverTripStartParser.isStartButton(desc) ||
                    texts.contains("안내시작") ||
                    desc.contains("안내시작")
                if (startClick || NaverTripStartParser.isEndButton(texts) || NaverTripStartParser.isEndButton(desc)) {
                    NaverGuidanceRemoved.flush()
                }
                if (startClick) {
                    if (NaverTripStartSession.restartFromClick(now)) arms += Arm(now, "click")
                    gate.resetNaverTripStart()
                }
                if (NaverTripStartParser.isEndButton(texts) || NaverTripStartParser.isEndButton(desc)) {
                    NaverTripStartSession.reset()
                    NaverTripStartCache.clear()
                    ended("end_click")
                    wasLive = false
                }
            }
            val freshTreeFollows = next != null && next.src == "a11y_tree" && next.ts - rec.ts <= 300L
            if (!freshTreeFollows) lastTree?.let { tree(it) }
        }

        private fun onTree(rec: Rec) {
            val blobs = (rec.obj["blobs"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
            lastTree = blobs
            noteChosenSummary(blobs)
            tree(blobs)
        }

        /** The chosen route's summary sits right after `안내 중`, before the card end or an alt card. */
        private fun noteChosenSummary(blobs: List<String>) {
            var inCard = false
            for (b in blobs) {
                val text = b.trim()
                when {
                    text == "안내 중" || text == "안내중" -> inCard = true
                    text == "안내 종료" || text == "안내종료" || text in ALT -> inCard = false
                    inCard && SUMMARY.containsMatchIn(text) -> {
                        firstLeg(text)?.let { legs += Leg(now, it, text) }
                        inCard = false
                    }
                }
            }
        }

        private fun tree(blobs: List<String>) {
            val node = NaverSubwayAccessibilityParser.Node(children = blobs.map { NaverSubwayAccessibilityParser.Node(text = it) })
            val live = NaverTransitDestinationParser.isLiveGuidance(node)
            CurrentGuidanceScope.onScreen(blobs)
            if (!live) {
                if (NaverTripStartParser.isRouteList(blobs)) NaverTripStartCache.clear()
                val preview = NaverTripStartParser.decision(pkg, node, now, requireLive = false)
                if (preview is NaverTripStartParser.Decision.Speak) NaverTripStartCache.remember(preview.event)
            }
            if (live && !wasLive) {
                if (NaverTripStartSession.noteLive(now)) {
                    arms += Arm(now, "live_edge")
                    gate.resetNaverJourneyCues()
                }
            }
            wasLive = live
            if (live) NaverFirstLeg.read(blobs)?.let { if (NaverTripStartSession.noteFirstLeg(it, now)) locks += now to it }
            if (NaverTripStartSession.dropUnconfirmed(now)) NaverTripStartCache.clear()
            if (NaverTripStartSession.due(now)) {
                val legBlock = NaverTripStartSession.firstLeg()?.block.orEmpty()
                val fromLeg = if (legBlock.isEmpty()) {
                    null
                } else {
                    NaverTripStartParser.decision(pkg, NaverFirstLeg.node(legBlock), now, requireLive = true)
                        as? NaverTripStartParser.Decision.Speak
                }
                val decision = fromLeg ?: NaverTripStartParser.decision(pkg, node, now, requireLive = true)
                val speak = decision as? NaverTripStartParser.Decision.Speak
                val event = speak?.event ?: NaverTripStartCache.take()
                val eventLine = event?.let { NavigationEventSpeech.line(it) }
                if (event != null && !NaverFirstLeg.allows(eventLine, NaverTripStartSession.firstLeg())) {
                    skips += Candidate(now, "legacy", "not_first_leg")
                } else if (event != null) {
                    val producer = when {
                        fromLeg != null -> "legacy_first_leg"
                        speak != null -> "legacy_screen"
                        else -> "legacy_cache"
                    }
                    offerTripStart(event, producer)
                    NaverTripStartCache.clear()
                } else {
                    NaverTripStartSession.giveUpIfStale(now)
                }
            }
            shadow(TransitObservation.fromScreen(pkg, node, now), "new_screen")
        }

        private fun onNotif(rec: Rec) {
            val o = rec.obj
            val channel = o.str("channel")
            val id = o["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            lastTitleById[id] = o.str("title")
            NaverGuidanceRemoved.onPosted(channel, o.str("title"))
            if (channel == NaverMapNotification.CHANNEL_WALK) return
            NaverTripStartSession.confirm(now)
            val title = o.str("title")
            val text = o.str("text")
            val snapshot = NaverNotificationParser.Snapshot(
                packageName = pkg,
                notificationId = id,
                channel = channel,
                title = title,
                text = text,
                action = o.str("nowbarPrimary"),
                chip = o.str("chip"),
                secondary = o.str("secondary"),
                nowbarSecondary = o.str("nowbarSecondary"),
                bigText = o.str("bigText"),
                timestampMillis = now,
            )
            CurrentGuidanceScope.onNotification(title, text)
            shadow(TransitObservation.fromNotification(snapshot), "new_302")
            val event = NaverNotificationParser.parse(snapshot) ?: return
            if (event.type != NavigationEventType.TRANSIT) return
            val startHint = listOf(title, text, o.str("nowbarPrimary"), o.str("chip"))
                .any { NaverTripStartParser.isStartPhrase(it) }
            if (startHint && NaverTripStartSession.request(now)) arms += Arm(now, "302_start_phrase")
            NaverFirstLeg.fromWalkTitle(title)?.let { if (NaverTripStartSession.noteFirstLeg(it, now)) locks += now to it }
            offerTransit(event)
        }

        private fun onRemoved(rec: Rec) {
            val id = rec.obj["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: return
            if (NaverGuidanceRemoved.endsGuidance(rec.obj.str("channel"), lastTitleById[id])) {
                NaverGuidanceRemoved.hold(now) {
                    NaverTripStartSession.reset()
                    NaverTripStartCache.clear()
                    ended("card_removed")
                }
            }
        }

        private fun shadow(reading: TransitReading?, producer: String) {
            val scope = CurrentGuidanceScope.snapshot()
            val outcome = StartBriefing.consider(scope, reading)
            val text = outcome.request?.text?.trim().orEmpty()
            if (text.isEmpty()) return
            val tagged = "$producer:${scope.kind}"
            candidates += Candidate(now, tagged, text)
            if (Transfer.seoulOwnsAlight(scope.line)) return
            if (NaverTripStartSession.hasSpokenBriefing()) return
            briefStart(text, scope.kind == GuidanceKind.BUS, tagged)
        }

        private fun offerTransit(event: NavigationEvent) {
            if (event.action == NaverMapsTransit.TRIP_START_ACTION) {
                offerTripStart(event, "302_trip_start")
                return
            }
            if (!NaverTripStartSession.due(now)) return
            val promoted = NaverTripStartParser.asTripStart(event) ?: return
            offerTripStart(promoted, "302_promote")
        }

        private fun offerTripStart(incoming: NavigationEvent, producer: String) {
            val line = NavigationEventSpeech.line(incoming)
            if (line != null) candidates += Candidate(now, producer, line)
            if (LeftoverSubway.againstBusScope(incoming.rawText)) {
                skips += Candidate(now, producer, "leftover_subway")
                return
            }
            if (!NaverFirstLeg.allows(line, NaverTripStartSession.firstLeg())) {
                skips += Candidate(now, producer, "not_first_leg")
                return
            }
            if (NaverTripStartSession.hasSpokenBriefing()) return
            if (line == null) {
                skips += Candidate(now, producer, "line_null")
                return
            }
            if (!gate.accept(incoming)) {
                skips += Candidate(now, producer, "gate")
                return
            }
            briefStart(line, incoming.rawText == NaverMapsTransit.KIND_BUS, producer)
        }

        private fun briefStart(text: String, bus: Boolean, producer: String) {
            val line = text.trim()
            if (line.isEmpty()) return
            if (!NaverFirstLeg.allows(line, NaverTripStartSession.firstLeg())) {
                skips += Candidate(now, producer, "not_first_leg")
                return
            }
            NaverTripStartSession.markSpoken(now)
            if (NaverTripStartSession.isLateClickRepeat(briefRouteKey(line))) {
                skips += Candidate(now, producer, "late_click_same_route")
                return
            }
            val delay = NaverTripStartSession.msUntilBriefing(now)
            pendingBrief?.let {
                it.outcome = "replaced"
                it.outcomeAt = now
            }
            pendingId = pending.schedule(line, bus, now + delay)
            pendingDueAt = now + delay
            pendingBrief = Brief(now, producer, line).also(briefs::add)
        }

        private fun JsonObject.str(key: String): String? =
            this[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun render(result: Result, speech: List<Spoken>, skippedFiles: List<String>): String {
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA)
        val starts = ArrayList<Long>()
        for (arm in result.arms) {
            if (starts.isEmpty() || arm.ts - (result.arms.lastOrNull { it.ts < arm.ts }?.ts ?: 0L) > JOURNEY_GAP_MS) {
                starts += arm.ts
            }
        }
        val counts = LinkedHashMap<String, Int>()
        val byProducer = LinkedHashMap<String, Int>()
        val lines = ArrayList<String>()
        for ((i, start) in starts.withIndex()) {
            val next = starts.getOrNull(i + 1) ?: Long.MAX_VALUE
            val until = minOf(next, start + TRUTH_WINDOW_MS)
            val arms = result.arms.filter { it.ts in start until next }
            val legs = result.legs.filter { it.ts in start until until }
            val firstLegs = legs.map { it.firstLeg }.distinct()
            val spoken = result.briefs.filter { it.outcome == "spoken" && (it.outcomeAt ?: 0L) in start until next }
            val other = result.briefs.filter { it.outcome != "spoken" && it.scheduledAt in start until next }
            val first = spoken.firstOrNull()
            val said = first?.let { briefRouteKey(it.text)?.removeSuffix("번") }
            val truth = when (firstLegs.size) {
                0 -> null
                1 -> firstLegs.first()
                else -> "AMBIGUOUS(${firstLegs.joinToString("/")})"
            }
            val lock = result.locks.firstOrNull { it.first in start until next }?.second
            val sameBoard = lock != null && !lock.subway && said != null && said in lock.lines &&
                truth != null && truth in lock.lines
            val verdict = when {
                truth == null -> "NO_TRUTH"
                truth.startsWith("AMBIGUOUS") -> "AMBIGUOUS"
                first == null -> "SILENT"
                said == truth -> if (spoken.size > 1) "CORRECT_BUT_REPEATED" else "CORRECT"
                sameBoard -> "SAME_BOARD"
                else -> "WRONG"
            }
            counts[verdict] = (counts[verdict] ?: 0) + 1
            if (verdict != "NO_TRUTH" && first != null) {
                val k = "${first.producer.substringBefore(':')}=${if (said == truth) "ok" else "bad"}"
                byProducer[k] = (byProducer[k] ?: 0) + 1
            }
            val windowEnd = minOf(next, start + 60_000L)
            val field = speech.filter { it.ts in start until windowEnd }.take(2)
            val cands = result.candidates.filter { it.ts in start until windowEnd }
                .distinctBy { it.producer.substringBefore(':') }
            lines += buildString {
                append(fmt.format(Date(start))).append(" | ").append(verdict)
                append(" | truth=").append(truth ?: "-")
                append(" | said=").append(said ?: "-")
                append(" | by=").append(first?.producer ?: "-")
                first?.let { append(" | at +").append(((it.outcomeAt ?: start) - start) / 100 / 10.0).append("s") }
                append("\n    arms=").append(arms.joinToString(",") { "${it.how}+${(it.ts - start) / 1000}s" })
                append("\n    first_leg=").append(lock?.label() ?: "-")
                spoken.forEachIndexed { n, b ->
                    append("\n    spoken#").append(n + 1).append(" +").append(((b.outcomeAt ?: start) - start) / 1000).append("s ")
                        .append(b.producer).append(" : ").append(b.text)
                }
                other.forEach { b ->
                    append("\n    not_spoken ").append(b.outcome).append(" ").append(b.producer).append(" : ").append(b.text.take(60))
                }
                cands.forEach { c ->
                    append("\n    first_from ").append(c.producer).append(" +").append((c.ts - start) / 1000).append("s : ").append(c.text.take(70))
                }
                result.skips.filter { it.ts in start until windowEnd }.distinct().forEach {
                    append("\n    skip ").append(it.producer).append(" ").append(it.text)
                }
                field.forEach { append("\n    field ").append(fmt.format(Date(it.ts))).append(" ").append(it.text.take(90)) }
                legs.firstOrNull()?.let { append("\n    summary=").append(it.summary.take(90)) }
            }
        }
        val head = buildString {
            append("records replayed; departures=").append(starts.size)
            counts.forEach { (k, v) -> append(' ').append(k).append('=').append(v) }
            append("\nfirst briefing source (judged departures): ")
            byProducer.forEach { (k, v) -> append(k).append('=').append(v).append(' ') }
            if (skippedFiles.isNotEmpty()) append("\nskipped unreadable: ").append(skippedFiles.joinToString(", "))
            append('\n')
        }
        return head + lines.joinToString("\n") + "\n"
    }

    private companion object {
        const val JOURNEY_GAP_MS = 40_000L
        const val TRUTH_WINDOW_MS = 5 * 60_000L
        val SUMMARY = Regex("""^도보 \d+분, """)
        val ALT = setOf("바로 안내시작", "최소시간", "최소환승")
        val BUS_LEG = Regex("""^([A-Za-z]?\d{1,4}[A-Za-z-]?\d*)번""")

        fun firstLeg(summary: String): String? {
            val leg = summary.split(",").map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("도보") }
                ?: return null
            BUS_LEG.find(leg)?.let { return it.groupValues[1] }
            return leg.replace(Regex("""\s*\d+분$"""), "").trim().split(" ").firstOrNull()
        }
    }
}
