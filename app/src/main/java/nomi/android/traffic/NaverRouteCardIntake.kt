package nomi.android.traffic

import nomi.android.traffic.api.nextsubway.NextSubwayFact
import nomi.android.traffic.api.routelegs.RouteLegs
import nomi.product.nav.NavigationEventSource
import nomi.product.nav.RouteCard
import nomi.product.nav.RouteCardMode
import nomi.product.nav.RouteCardRepository

/**
 * Records a Naver transit card after live guidance (`안내 중`) with a destination,
 * or a 302 start notification once a destination is known.
 * Search/preview without those signals does not record.
 * [transferNote] on the card lists every boarding after the first, bus and
 * subway, from [RouteLegs] (pre-release check without a computer).
 * Naver's `길안내를 시작합니다 | {place}까지 이동` names the destination of that
 * guidance until it ends; a ride's stop list (`아라역3번출구 / 까지 이동`) does not.
 */
class NaverRouteCardIntake(
    private val repository: RouteCardRepository,
) {
    private val recordedIds = mutableSetOf<Int>()
    private var pendingId: Int? = null
    private var pendingAtMillis: Long = 0L
    private var snapshot: String? = null
    private var recordedLiveKey: String? = null
    private var lastTransferNote: String? = null
    private var boundDestination: String? = null
    private var heldNote: String? = null
    private var guidanceDest: String? = null
    private var clearNoteOnRecord = false

    fun onScreen(
        destinationLabel: String?,
        liveGuidance: Boolean,
        atMillis: Long,
    ): RouteCard? {
        val bound = guidanceDest
        val dest = bound ?: destinationLabel?.trim()?.ifEmpty { null }
        if (dest != null) {
            snapshot = dest
        } else if (!liveGuidance) {
            snapshot = null
        }
        val note = snapshot?.let { applicableNote(it) }
        val card = if (liveGuidance) {
            recordLive(atMillis, note)
        } else {
            if (bound == null) {
                recordedLiveKey = null
                lastTransferNote = null
            }
            val waiting = pendingId
            if (waiting == null || snapshot == null) {
                null
            } else {
                recordFromNotification(waiting, pendingAtMillis.takeIf { it > 0L } ?: atMillis, note)
            }
        }
        return noteTransfer(note, atMillis) ?: card
    }

    fun onNotificationPosted(
        notificationId: Int,
        atMillis: Long,
        destinationBlobs: List<String?> = emptyList(),
    ): RouteCard? {
        val blobs = destinationBlobs.mapNotNull { it?.trim()?.ifEmpty { null } }
        if (blobs.any { endNotice.containsMatchIn(it) }) onGuidanceEnded()
        val dest = NaverTransitDestinationParser.destination(blobs)
        if (dest != null && blobs.any { startNotice.containsMatchIn(it) }) bind(dest)
        if (dest != null) {
            onScreen(destinationLabel = dest, liveGuidance = false, atMillis = atMillis)
        }
        if (notificationId in recordedIds) {
            return noteTransfer(heldNote, atMillis)
        }
        if (snapshot == null) {
            pendingId = notificationId
            pendingAtMillis = atMillis
            return null
        }
        return recordFromNotification(notificationId, atMillis, heldNote)
    }

    fun onNotificationRemoved(notificationId: Int) {
        recordedIds.remove(notificationId)
        if (pendingId == notificationId) {
            pendingId = null
            pendingAtMillis = 0L
        }
    }

    /** Naver ended the guidance. The next destination comes from its own start. */
    fun onGuidanceEnded() {
        guidanceDest = null
        clearNoteOnRecord = false
    }

    /** A new guidance to another place starts clean; a restart to the same place keeps its note. */
    private fun bind(dest: String) {
        if (guidanceDest == dest) return
        guidanceDest = dest
        clearNoteOnRecord = true
        recordedLiveKey = null
        lastTransferNote = null
    }

    /**
     * Writes the remembered transfer subway onto the current destination card.
     * Wrong station (e.g. 대화역) shows as-is so the rider can catch it.
     */
    fun noteTransferSubway(fact: NextSubwayFact?, atMillis: Long): RouteCard? =
        noteTransfer(transferLabel(fact), atMillis)

    /** Same as [noteTransferSubway] with ready text (e.g. `서해선 대곡역 · 1호선 소사역`). */
    fun noteTransfer(text: String?, atMillis: Long): RouteCard? {
        val dest = snapshot ?: return null
        val note = text?.trim()?.ifEmpty { null } ?: return null
        if (note == lastTransferNote) {
            val existing = repository.recent().firstOrNull {
                it.provider == NavigationEventSource.NAVER &&
                    it.destinationName == dest &&
                    it.mode == RouteCardMode.TRANSIT
            }
            if (existing?.transferNote == note) return existing
        }
        lastTransferNote = note
        clearNoteOnRecord = false
        return repository.record(
            provider = NavigationEventSource.NAVER,
            destinationName = dest,
            mode = RouteCardMode.TRANSIT,
            atMillis = atMillis,
            transferNote = note,
        )
    }

    /**
     * A note chosen for another destination stays on that card.
     * This destination gets a note only after its own route is read.
     */
    private fun applicableNote(dest: String): String? {
        val fresh = RouteLegs.takeFresh()
        val changed = boundDestination != dest
        if (changed) boundDestination = dest
        val note = if (changed && !fresh) null else RouteLegs.cardNote()
        heldNote = note
        return note
    }

    private fun recordFromNotification(notificationId: Int, atMillis: Long, note: String?): RouteCard? {
        recordedIds.add(notificationId)
        pendingId = null
        pendingAtMillis = 0L
        return recordLive(atMillis, note)
    }

    private fun recordLive(atMillis: Long, note: String?): RouteCard? {
        val dest = snapshot ?: return null
        val key = RouteCard.keyOf(NavigationEventSource.NAVER, dest, RouteCardMode.TRANSIT)
        if (key == recordedLiveKey && note == lastTransferNote) return null
        recordedLiveKey = key
        if (note != null) lastTransferNote = note
        val keepNote = !clearNoteOnRecord
        clearNoteOnRecord = false
        return repository.record(
            provider = NavigationEventSource.NAVER,
            destinationName = dest,
            mode = RouteCardMode.TRANSIT,
            atMillis = atMillis,
            transferNote = note,
            keepNote = keepNote,
        )
    }

    companion object {
        private val startNotice = Regex("""길\s*안내를\s*시작""")
        private val endNotice = Regex("""길\s*안내를\s*종료""")

        fun transferLabel(fact: NextSubwayFact?): String? {
            if (fact == null) return null
            val line = fact.line.trim()
            val station = fact.station?.trim().orEmpty()
            if (station.isEmpty()) return null
            return if (line.isEmpty()) station else "$line $station"
        }
    }
}
