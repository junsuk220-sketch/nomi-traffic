package nomi.android.traffic.track

import nomi.android.traffic.NaverSubwayPin
import nomi.android.traffic.NaverTripStartSession
import nomi.android.traffic.buswait.BusWaitCore
import nomi.android.traffic.observe.ObservationSource
import nomi.android.traffic.observe.TransitKind
import nomi.android.traffic.observe.TransitReading
import nomi.android.traffic.observe.TransitRow
import nomi.android.traffic.scope.CurrentGuidanceScope
import nomi.android.traffic.scope.GuidanceKind
import nomi.android.traffic.scope.GuidanceSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VehicleTrackingTest {

    @Before
    fun setUp() {
        VehicleTracking.resetForTest()
        CurrentGuidanceScope.resetForTest()
        NaverSubwayPin.reset()
        NaverTripStartSession.reset()
    }

    @After
    fun tearDown() {
        VehicleTracking.resetForTest()
        CurrentGuidanceScope.resetForTest()
        NaverSubwayPin.reset()
        NaverTripStartSession.reset()
    }

    @Test
    fun `inactive scope tracks nothing`() {
        val snap = VehicleTracking.observe(
            GuidanceSnapshot(false, 0, GuidanceKind.NONE, null),
            reading(1_000L, row("88B", "4분")),
        )
        assertNull(snap)
        assertNull(VehicleTracking.snapshot())
    }

    @Test
    fun `scope 88B keeps 88B and ignores closer 88A`() {
        val snap = VehicleTracking.observe(
            scope("88B"),
            reading(
                1_000L,
                row("88A", "곧 도착"),
                row("88B", "4분"),
                row("88B", "7분"),
            ),
        )!!
        assertEquals("88B", snap.line)
        assertEquals("4분", snap.eta)
        assertEquals(4, snap.etaMinutes)
        assertEquals(TransitKind.BUS, snap.kind)
        assertTrue(snap.settled)
        assertFalse(snap.nextVehicle)
        assertEquals(1, snap.vehicleKey.track)
    }

    @Test
    fun `soonest among the pinned line is the first 88B`() {
        val snap = VehicleTracking.observe(
            scope("88B"),
            reading(1_000L, row("88B", "7분"), row("88B", "3분")),
        )!!
        assertEquals("3분", snap.eta)
        assertEquals(3, snap.etaMinutes)
    }

    @Test
    fun `partial empty board holds 20 seconds then drops`() {
        VehicleTracking.observe(scope("88B"), reading(1_000L, row("88B", "4분")))
        val held = VehicleTracking.observe(
            scope("88B"),
            emptyReading(1_000L + 5_000L),
        )!!
        assertEquals("88B", held.line)
        assertEquals("4분", held.eta)
        assertFalse(held.nextVehicle)
        assertEquals(1, held.vehicleKey.track)
        assertNull(
            VehicleTracking.observe(
                scope("88B"),
                emptyReading(1_000L + BusWaitCore.ABSENT_HOLD_MS),
            ),
        )
    }

    @Test
    fun `soon then 2 minutes stays unsettled on the same key`() {
        val first = VehicleTracking.observe(
            scope("88B"),
            reading(1_000L, row("88B", "곧 도착")),
        )!!
        assertEquals("곧 도착", first.eta)
        assertEquals(0, first.etaMinutes)
        val lag = VehicleTracking.observe(
            scope("88B"),
            reading(6_000L, row("88B", "2분"), row("88B", "8분")),
        )!!
        assertFalse(lag.settled)
        assertFalse(lag.nextVehicle)
        assertEquals("곧 도착", lag.eta)
        assertEquals(first.vehicleKey, lag.vehicleKey)
    }

    @Test
    fun `vehicleKey changes only after a confirmed miss`() {
        val first = VehicleTracking.observe(
            scope("88B"),
            reading(1_000L, row("88B", "곧 도착")),
        )!!
        val pending = VehicleTracking.observe(
            scope("88B"),
            reading(2_000L, row("88B", "3분")),
        )!!
        assertEquals(first.vehicleKey, pending.vehicleKey)
        assertEquals("곧 도착", pending.eta)
        val next = VehicleTracking.observe(
            scope("88B"),
            reading(2_000L + BusWaitCore.CONFIRM_MS, row("88B", "3분")),
        )!!
        assertTrue(next.nextVehicle)
        assertEquals("3분", next.eta)
        assertEquals(first.vehicleKey.generation, next.vehicleKey.generation)
        assertEquals(first.vehicleKey.line, next.vehicleKey.line)
        assertNotEquals(first.vehicleKey.track, next.vehicleKey.track)
    }

    @Test
    fun `countdown keeps the same vehicleKey`() {
        val first = VehicleTracking.observe(
            scope("88B"),
            reading(1_000L, row("88B", "4분")),
        )!!
        val next = VehicleTracking.observe(
            scope("88B"),
            reading(2_000L, row("88B", "3분")),
        )!!
        assertEquals(first.vehicleKey, next.vehicleKey)
        assertEquals("3분", next.eta)
        assertFalse(next.nextVehicle)
        assertTrue(next.settled)
    }

    @Test
    fun `other stop board does not steal the track`() {
        VehicleTracking.observe(
            scope("88B"),
            reading(1_000L, row("88B", "4분"), stop = "대우.삼성오피스텔"),
        )
        val leftover = VehicleTracking.observe(
            scope("88B"),
            reading(2_000L, row("88B", "곧 도착"), stop = "홀플러스"),
        )!!
        assertEquals("4분", leftover.eta)
        assertEquals("대우.삼성오피스텔", leftover.stop)
        val sameStop = VehicleTracking.observe(
            scope("88B"),
            reading(3_000L, row("88B", "3분"), stop = "대우.삼성오피스텔"),
        )!!
        assertEquals("3분", sameStop.eta)
        assertEquals(leftover.vehicleKey, sameStop.vehicleKey)
    }

    @Test
    fun `tracking does not change scope pin session or bus core`() {
        CurrentGuidanceScope.onScreen(listOf("안내 중", "3호선", "안내 종료"))
        NaverSubwayPin.pin("6호선")
        val core = BusWaitCore()
        core.seed("81")

        VehicleTracking.observe(
            scope("88B"),
            reading(1_000L, row("88A", "곧 도착"), row("88B", "4분")),
        )

        assertEquals("3호선", CurrentGuidanceScope.snapshot().line)
        assertEquals("6호선", NaverSubwayPin.pinned())
        assertEquals(setOf("81"), core.pinnedLines())
        assertFalse(NaverTripStartSession.hasSpokenBriefing())
    }

    @Test
    fun `snapshot is a detached copy`() {
        VehicleTracking.observe(scope("88B"), reading(1_000L, row("88B", "4분")))
        val first = VehicleTracking.snapshot()!!
        val second = VehicleTracking.snapshot()!!
        assertEquals(first, second)
        assertNotSame(first, second)
        VehicleTracking.observe(
            GuidanceSnapshot(false, 1, GuidanceKind.NONE, null),
            null,
        )
        assertEquals("88B", first.line)
        assertEquals(1, first.vehicleKey.track)
        assertNull(VehicleTracking.snapshot())
    }

    @Test
    fun `new scope generation starts a new key`() {
        val first = VehicleTracking.observe(
            scope("88B", generation = 1),
            reading(1_000L, row("88B", "4분")),
        )!!
        val next = VehicleTracking.observe(
            scope("88B", generation = 2),
            reading(2_000L, row("88B", "3분")),
        )!!
        assertEquals(2, next.vehicleKey.generation)
        assertNotEquals(first.vehicleKey, next.vehicleKey)
        assertFalse(next.nextVehicle)
    }

    private fun scope(
        line: String,
        generation: Int = 1,
        kind: GuidanceKind = GuidanceKind.BUS,
    ) = GuidanceSnapshot(
        active = true,
        generation = generation,
        kind = kind,
        line = line,
    )

    private fun row(line: String, eta: String) = TransitRow(line = line, eta = eta)

    private fun reading(
        atMs: Long,
        vararg rows: TransitRow,
        stop: String? = null,
    ) = TransitReading(
        source = ObservationSource.NOTIFICATION,
        atMs = atMs,
        kind = TransitKind.BUS,
        stop = stop,
        rows = rows.toList(),
        raw = rows.joinToString(", ") { "${it.line} (${it.eta})" },
    )

    private fun emptyReading(atMs: Long) = reading(atMs)
}
