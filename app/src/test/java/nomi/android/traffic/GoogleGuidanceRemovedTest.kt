package nomi.android.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GoogleGuidanceRemovedTest {

    @Before
    fun setUp() = GoogleGuidanceRemoved.resetForTest()

    @Test
    fun `card back inside the grace keeps the trip`() {
        // 2026-11-01: remove then same ride count 4 ms later; immediate end wiped the ride.
        var ended = 0
        GoogleGuidanceRemoved.hold(1_000L) { ended++ }
        assertTrue(GoogleGuidanceRemoved.onPosted())
        GoogleGuidanceRemoved.expire(1_000L + GoogleGuidanceRemoved.REPOST_GRACE_MS + 1)
        assertEquals(0, ended)
        assertFalse(GoogleGuidanceRemoved.isHolding())
    }

    @Test
    fun `no card back within the grace ends the trip`() {
        var ended = 0
        GoogleGuidanceRemoved.hold(2_000L) { ended++ }
        GoogleGuidanceRemoved.expire(2_000L + GoogleGuidanceRemoved.REPOST_GRACE_MS - 1)
        assertEquals(0, ended)
        GoogleGuidanceRemoved.expire(2_000L + GoogleGuidanceRemoved.REPOST_GRACE_MS)
        assertEquals(1, ended)
        GoogleGuidanceRemoved.expire(2_000L + 10 * GoogleGuidanceRemoved.REPOST_GRACE_MS)
        assertEquals(1, ended)
    }

    @Test
    fun `flush runs a held end once`() {
        var ended = 0
        GoogleGuidanceRemoved.hold(3_000L) { ended++ }
        GoogleGuidanceRemoved.flush()
        assertEquals(1, ended)
        GoogleGuidanceRemoved.flush()
        assertEquals(1, ended)
    }
}
