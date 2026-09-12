package nomi.android.traffic

import nomi.product.nav.NavigationBusArrival
import org.junit.Assert.assertEquals
import org.junit.Test

class NaverNextVehicleTest {

    @Test
    fun `same stop cluster prefers other line not the next 98`() {
        val next = NaverNextVehicle.afterSoonest(
            listOf(
                bus("98", "곧 도착", stops = 1),
                bus("2000", "3분", stops = 1),
                bus("89", "2분", stops = 2),
                bus("98", "5분", stops = 3),
            ),
        )!!
        assertEquals("2000", next.line)
        assertEquals("3분", next.eta)
    }

    @Test
    fun `without stops uses soonest other line`() {
        val next = NaverNextVehicle.afterSoonest(
            listOf(
                bus("150", "3분"),
                bus("67", "5분"),
                bus("150", "18분"),
            ),
        )!!
        assertEquals("67", next.line)
    }

    @Test
    fun `same line later is the next bus when no other line`() {
        val next = NaverNextVehicle.afterSoonest(
            listOf(
                bus("88B", "5분", stops = 4),
                bus("88B", "11분", stops = 6),
            ),
        )!!
        assertEquals("88B", next.line)
        assertEquals("11분", next.eta)
    }

    @Test
    fun `subway keeps the later same line`() {
        val next = NaverNextVehicle.afterSoonest(
            listOf(
                bus("3호선", "3분"),
                bus("3호선", "21분"),
            ),
            sameLine = true,
        )!!
        assertEquals("21분", next.eta)
    }

    private fun bus(line: String, eta: String, stops: Int? = null) =
        NavigationBusArrival(line, eta, occupancy = "여유", stopsRemaining = stops)
}
