package nomi.android.traffic.beacon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BeaconMathTest {

    private val here = GeoPoint(37.5665, 126.9780)
    private val north = GeoPoint(37.5700, 126.9780)
    private val east = GeoPoint(37.5665, 126.9830)

    @Test
    fun `bearing north and east`() {
        assertEquals(0.0, BeaconMath.bearingDeg(here, north), 0.5)
        assertEquals(90.0, BeaconMath.bearingDeg(here, east), 0.5)
    }

    @Test
    fun `relative wraps to minus 180 plus 180`() {
        assertEquals(20.0, BeaconMath.relativeDeg(10.0, 350.0), 1e-9)
        assertEquals(-20.0, BeaconMath.relativeDeg(350.0, 10.0), 1e-9)
        assertEquals(-180.0, BeaconMath.relativeDeg(180.0, 0.0), 1e-9)
    }

    @Test
    fun `pan is centered ahead and one ear at 90`() {
        val (l0, r0) = BeaconMath.pan(0.0)
        assertEquals(l0, r0, 1e-6f)
        val (l90, r90) = BeaconMath.pan(90.0)
        assertEquals(0f, l90, 1e-6f)
        assertEquals(1f, r90, 1e-6f)
        val (l30, r30) = BeaconMath.pan(-30.0)
        assertTrue(l30 > r30)
        assertTrue(r30 > 0f)
    }

    @Test
    fun `target north while walking east is on the left`() {
        val cue = BeaconMath.cue(here, north, heading = 90.0)!!
        assertTrue(cue.left > 0.99f)
        assertFalse(cue.behind)
    }

    @Test
    fun `target north while walking south is behind`() {
        val cue = BeaconMath.cue(here, north, heading = 180.0)!!
        assertTrue(cue.behind)
    }

    @Test
    fun `silent when facing unknown`() {
        assertNull(BeaconMath.cue(here, north, heading = null))
    }

    @Test
    fun `arrived near target even when standing`() {
        val near = GeoPoint(37.56655, 126.9780)
        assertTrue(BeaconMath.cue(here, near, heading = null)!!.arrived)
    }

    @Test
    fun `beeps faster when closer`() {
        assertTrue(BeaconMath.intervalMillis(20.0) < BeaconMath.intervalMillis(500.0))
    }
}
