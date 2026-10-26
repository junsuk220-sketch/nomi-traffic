package nomi.android.traffic.api.seoulcountdown

import nomi.android.traffic.TransitSoonEta
import nomi.android.traffic.api.eta.EtaBand
import nomi.android.traffic.api.transfer.TransferLine
import nomi.android.traffic.observe.TransitRow

data class SeoulSubwayCountdownOutcome(
    val text: String? = null,
    val band: EtaBand? = null,
)

/**
 * Seoul-backed 10/5/2/soon ladder for the subway immediately after a transfer.
 * It owns only its bands. Fetching, timing and speech stay in the host.
 */
object SeoulSubwayCountdown {
    private var line: String? = null
    private val spoken = LinkedHashSet<EtaBand>()

    @Synchronized
    fun arm(line: String, rows: List<TransitRow>) {
        val clean = line.trim()
        if (clean.isEmpty()) return
        if (this.line != clean) {
            this.line = clean
            spoken.clear()
        }
        rows.firstFor(clean)?.eta?.let(::bandOf)?.let(::markThrough)
    }

    @Synchronized
    fun consider(rows: List<TransitRow>): SeoulSubwayCountdownOutcome {
        val want = line ?: return SeoulSubwayCountdownOutcome()
        val filtered = rows.filter { it.line == want && it.eta.isNotBlank() }
        val first = filtered.firstFor(want) ?: return SeoulSubwayCountdownOutcome()
        val band = bandOf(first.eta) ?: return SeoulSubwayCountdownOutcome()
        if (!spoken.add(band)) return SeoulSubwayCountdownOutcome(band = band)
        markCoarser(band)
        val next = filtered.firstOrNull {
            it !== first && (it.eta != first.eta || it.service != first.service)
        }
        return SeoulSubwayCountdownOutcome(
            text = TransferLine.text(first = first, next = next),
            band = band,
        )
    }

    /**
     * The 곧 train has left. The next train starts 10/5/2/곧 from whatever
     * minute Seoul shows. The line stays. Same-train jitter does not call this.
     */
    @Synchronized
    fun reopen() {
        spoken.clear()
    }

    @Synchronized
    fun reset() {
        line = null
        spoken.clear()
    }

    @Synchronized
    internal fun spokenBands(): Set<EtaBand> = spoken.toSet()

    private fun List<TransitRow>.firstFor(line: String): TransitRow? =
        filter { it.line == line && it.eta.isNotBlank() }
            .minByOrNull { TransitSoonEta.minutes(it.eta) ?: Int.MAX_VALUE }

    private fun bandOf(eta: String): EtaBand? {
        val minutes = TransitSoonEta.minutes(eta) ?: return null
        return EtaBand.ofMinutes(minutes)
    }

    private fun markThrough(band: EtaBand) {
        spoken += band
        markCoarser(band)
    }

    private fun markCoarser(band: EtaBand) {
        when (band) {
            EtaBand.TEN -> Unit
            EtaBand.FIVE -> spoken += EtaBand.TEN
            EtaBand.TWO -> spoken += listOf(EtaBand.TEN, EtaBand.FIVE)
            EtaBand.SOON -> spoken += listOf(EtaBand.TEN, EtaBand.FIVE, EtaBand.TWO)
        }
    }
}
