package nomi.android.traffic.buswait

import nomi.product.nav.NavigationBusArrival
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Field Trace: persist observe() input + Tick as-is. No new selection.
 */
class BusWaitFieldTraceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun attach() {
        BusWaitFieldTrace.resetForTest(tmp.root)
    }

    @Test
    fun `arrivals stay in the given order`() {
        val arrivals = listOf(
            NavigationBusArrival("15", "2분"),
            NavigationBusArrival("15", "16분"),
            NavigationBusArrival("66", "20분"),
        )
        BusWaitFieldTrace.record(
            nowMs = 1_000L,
            source = "sheet",
            stop = null,
            pinned = setOf("15"),
            seeded = setOf("15"),
            prevLine = null,
            prevEta = null,
            arrivals = arrivals,
            targetLine = "15",
            targetEta = "2분",
            switched = false,
            speakStage = 2,
            silence = null,
            path = BusWaitTracePath.INITIAL,
        )
        val line = BusWaitFieldTrace.file().readText()
        val seenAt = line.indexOf("\"seen\":")
        val i15a = line.indexOf("\"15\",\"eta\":\"2분\"", seenAt)
        val i15b = line.indexOf("\"15\",\"eta\":\"16분\"", seenAt)
        val i66 = line.indexOf("\"66\",\"eta\":\"20분\"", seenAt)
        assertTrue(i15a in 0 until i15b)
        assertTrue(i15b in i15b until i66 + 1)
        assertTrue(i15b < i66)
    }

    @Test
    fun `same keep is folded until heartbeat`() {
        val row = NavigationBusArrival("15", "5분")
        fun write(at: Long) {
            BusWaitFieldTrace.record(
                nowMs = at,
                source = "sheet",
                stop = null,
                pinned = setOf("15"),
                seeded = setOf("15"),
                prevLine = "15",
                prevEta = "5분",
                arrivals = listOf(row),
                targetLine = "15",
                targetEta = "5분",
                switched = false,
                speakStage = null,
                silence = BusWaitSilence.STAGE_ALREADY,
                path = BusWaitTracePath.KEEP_SAME,
            )
        }
        write(1_000L)
        write(2_000L)
        assertEquals(1, BusWaitFieldTrace.file().readLines().size)
        write(1_000L + BusWaitFieldTrace.HEARTBEAT_MS)
        assertEquals(2, BusWaitFieldTrace.file().readLines().size)
    }

    @Test
    fun `switch is never folded`() {
        val rows = listOf(NavigationBusArrival("15", "16분"), NavigationBusArrival("66", "20분"))
        fun write(at: Long) {
            BusWaitFieldTrace.record(
                nowMs = at,
                source = "notification",
                stop = "정류장",
                pinned = setOf("15", "66"),
                seeded = setOf("15"),
                prevLine = "15",
                prevEta = "2분",
                arrivals = rows,
                targetLine = "15",
                targetEta = "16분",
                switched = true,
                speakStage = 10,
                silence = null,
                path = BusWaitTracePath.NO_SAME_VEHICLE,
            )
        }
        write(1_000L)
        write(1_001L)
        assertEquals(2, BusWaitFieldTrace.file().readLines().size)
    }

    @Test
    fun `stages closed without speech are written every time`() {
        fun write(at: Long) {
            BusWaitFieldTrace.record(
                nowMs = at,
                source = "sheet",
                stop = null,
                pinned = setOf("15"),
                seeded = setOf("15"),
                prevLine = null,
                prevEta = null,
                arrivals = listOf(NavigationBusArrival("15", "2분")),
                targetLine = "15",
                targetEta = "2분",
                switched = false,
                speakStage = 2,
                silence = null,
                path = BusWaitTracePath.INITIAL,
                skippedStages = listOf(10, 5),
            )
        }
        write(1_000L)
        write(1_001L)
        val lines = BusWaitFieldTrace.file().readLines()
        assertEquals(2, lines.size)
        assertTrue(lines.first().contains("\"skip\":[10,5]"))
    }

    @Test
    fun `lines older than 24 hours are dropped`() {
        val now = 2_000_000_000_000L
        BusWaitFieldTrace.record(
            nowMs = now - BusWaitFieldTrace.MAX_AGE_MS - 1,
            source = "sheet",
            stop = null,
            pinned = setOf("15"),
            seeded = setOf("15"),
            prevLine = null,
            prevEta = null,
            arrivals = listOf(NavigationBusArrival("15", "10분")),
            targetLine = "15",
            targetEta = "10분",
            switched = true,
            speakStage = 10,
            silence = null,
            path = BusWaitTracePath.INITIAL,
        )
        BusWaitFieldTrace.record(
            nowMs = now,
            source = "sheet",
            stop = null,
            pinned = setOf("15"),
            seeded = setOf("15"),
            prevLine = null,
            prevEta = null,
            arrivals = listOf(NavigationBusArrival("15", "5분")),
            targetLine = "15",
            targetEta = "5분",
            switched = true,
            speakStage = 5,
            silence = null,
            path = BusWaitTracePath.INITIAL,
        )
        val text = BusWaitFieldTrace.file().readText()
        assertFalse(text.contains("\"10분\""))
        assertTrue(text.contains("\"5분\""))
    }
}
