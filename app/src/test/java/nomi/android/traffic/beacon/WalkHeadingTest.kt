package nomi.android.traffic.beacon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WalkHeadingTest {

    private val start = GeoPoint(37.5665, 126.9780)

    @Test
    fun `uses gps course while moving`() {
        val h = WalkHeading()
        assertEquals(45.0, h.update(0, start, 5f, 1.2f, 45f)!!, 1e-9)
        assertEquals("gps", h.source)
    }

    @Test
    fun `ignores gps course when too slow`() {
        val h = WalkHeading()
        assertNull(h.update(0, start, 5f, 0.2f, 45f))
    }

    @Test
    fun `falls back to line between fixes`() {
        val h = WalkHeading()
        h.update(0, start, 5f, null, null)
        val tenMetersNorth = GeoPoint(start.lat + 0.00009, start.lng)
        assertEquals(0.0, h.update(5_000, tenMetersNorth, 5f, null, null)!!, 1.0)
        assertEquals("move", h.source)
    }

    @Test
    fun `small drift within accuracy is not a heading`() {
        val h = WalkHeading()
        h.update(0, start, 12f, null, null)
        val eightMeters = GeoPoint(start.lat + 0.000072, start.lng)
        assertNull(h.update(3_000, eightMeters, 12f, null, null))
    }

    @Test
    fun `heading goes stale after standing`() {
        val h = WalkHeading()
        h.update(0, start, 5f, 1.2f, 90f)
        assertNull(h.update(9_000, start, 5f, 0f, null))
    }
}
