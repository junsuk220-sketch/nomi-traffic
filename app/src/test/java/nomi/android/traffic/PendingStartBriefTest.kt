package nomi.android.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingStartBriefTest {

    @Test
    fun `an event before the due time does not take it`() {
        val brief = PendingStartBrief()
        brief.schedule("81번 5분", bus = true, dueAtMs = 7_000L)
        assertNull(brief.takeDue(6_999L))
    }

    @Test
    fun `an event after the due time takes it once`() {
        val brief = PendingStartBrief()
        brief.schedule("81번 5분", bus = true, dueAtMs = 7_000L)
        assertEquals(PendingStartBrief.Due("81번 5분", true), brief.takeDue(7_000L))
        assertNull(brief.takeDue(7_200L))
    }

    @Test
    fun `its own timer takes it even a little early`() {
        val brief = PendingStartBrief()
        val id = brief.schedule("3호선 4분", bus = false, dueAtMs = 7_000L)
        assertEquals(PendingStartBrief.Due("3호선 4분", false), brief.takeDue(6_990L, timerId = id))
    }

    @Test
    fun `the timer after an event already spoke finds nothing`() {
        val brief = PendingStartBrief()
        val id = brief.schedule("81번 5분", bus = true, dueAtMs = 7_000L)
        brief.takeDue(7_100L)
        assertNull(brief.takeDue(7_200L, timerId = id))
    }

    @Test
    fun `an old timer does not take a newer briefing early`() {
        val brief = PendingStartBrief()
        val old = brief.schedule("81번 5분", bus = true, dueAtMs = 7_000L)
        brief.schedule("81번 4분", bus = true, dueAtMs = 20_000L)
        assertNull(brief.takeDue(7_000L, timerId = old))
        assertEquals(PendingStartBrief.Due("81번 4분", true), brief.takeDue(20_000L))
    }

    @Test
    fun `cancel drops the waiting briefing`() {
        val brief = PendingStartBrief()
        val id = brief.schedule("81번 5분", bus = true, dueAtMs = 7_000L)
        assertTrue(brief.cancel())
        assertNull(brief.takeDue(8_000L, timerId = id))
        assertFalse(brief.cancel())
    }
}
