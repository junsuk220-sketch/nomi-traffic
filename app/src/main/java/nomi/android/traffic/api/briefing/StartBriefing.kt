package nomi.android.traffic.api.briefing

import nomi.android.traffic.TransitSoonEta
import nomi.android.traffic.observe.TransitReading
import nomi.android.traffic.observe.TransitRow
import nomi.android.traffic.scope.GuidanceKind
import nomi.android.traffic.scope.GuidanceSnapshot
import nomi.android.traffic.speech.SpeechRequest

/**
 * One start briefing per Scope generation.
 * Reads Scope and Observation only. Does not hold wait, arm, or speak in shadow.
 */
object StartBriefing {

    private var spokenGeneration: Int? = null

    fun consider(
        scope: GuidanceSnapshot,
        reading: TransitReading?,
    ): StartBriefingOutcome {
        if (!scope.active) return StartBriefingOutcome(silence = StartBriefingSilence.INACTIVE)
        val line = scope.line?.trim().orEmpty()
        if (line.isEmpty()) {
            return StartBriefingOutcome(silence = StartBriefingSilence.NO_LINE)
        }
        if (scope.kind == GuidanceKind.NONE) {
            return StartBriefingOutcome(silence = StartBriefingSilence.NO_KIND)
        }
        if (spokenGeneration == scope.generation) {
            return StartBriefingOutcome(silence = StartBriefingSilence.ALREADY)
        }
        if (reading == null) {
            return StartBriefingOutcome(silence = StartBriefingSilence.NO_READING)
        }
        val own = reading.rows.filter { it.line == line }
        if (own.isEmpty()) {
            return StartBriefingOutcome(silence = StartBriefingSilence.NO_SCOPE_ROW)
        }
        val first = pickSoonest(own) ?: return StartBriefingOutcome(
            silence = StartBriefingSilence.NO_SCOPE_ROW,
        )
        val next = pickSoonest(own.filterNot { it.line == first.line && it.eta == first.eta })
        val text = StartBriefingLine.text(
            kind = scope.kind,
            first = first,
            next = next,
            walkMinutes = reading.walkMinutes,
        )
            ?: return StartBriefingOutcome(silence = StartBriefingSilence.NO_TEXT)
        spokenGeneration = scope.generation
        return StartBriefingOutcome(request = SpeechRequest(text))
    }

    internal fun resetForTest() {
        spokenGeneration = null
    }

    private fun pickSoonest(rows: List<TransitRow>): TransitRow? =
        rows.minWithOrNull(
            compareBy { TransitSoonEta.minutes(it.eta) ?: Int.MAX_VALUE },
        )
}
