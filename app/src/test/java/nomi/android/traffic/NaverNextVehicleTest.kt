package nomi.android.traffic

import nomi.product.nav.NavigationBusArrival
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NaverNextVehicleTest {

    @Test
    fun `remaining soonest after 98 soon is 89 not 2000`() {
        val current = bus("98", "곧 도착", stops = 1)
        val next = NaverNextVehicle.afterSoonest(
            listOf(
                current,
                bus("2000", "3분", stops = 1),
                bus("89", "2분", stops = 2),
                bus("98", "5분", stops = 3),
            ),
            current,
        )!!
        assertEquals("89", next.line)
        assertEquals("2분", next.eta)
    }

    @Test
    fun `remaining soonest after current is not chosen by line`() {
        val current = bus("150", "3분")
        val next = NaverNextVehicle.afterSoonest(
            listOf(
                current,
                bus("67", "5분"),
                bus("150", "18분"),
            ),
            current,
        )!!
        assertEquals("67", next.line)
    }

    @Test
    fun `later same line is next when it is the remaining soonest`() {
        val current = bus("88B", "5분", stops = 4)
        val next = NaverNextVehicle.afterSoonest(
            listOf(
                current,
                bus("88B", "11분", stops = 6),
            ),
            current,
        )!!
        assertEquals("88B", next.line)
        assertEquals("11분", next.eta)
    }

    @Test
    fun `subway later train is remaining soonest after current`() {
        val current = bus("3호선", "3분")
        val next = NaverNextVehicle.afterSoonest(
            listOf(
                current,
                bus("3호선", "21분"),
            ),
            current,
        )!!
        assertEquals("21분", next.eta)
    }

    @Test
    fun `A remaining soonest after 81 soon is later 81 not 99`() {
        val current = bus("81", "곧 도착")
        val next = NaverNextVehicle.afterSoonest(
            listOf(
                current,
                bus("81", "12분"),
                bus("99", "13분"),
            ),
            current,
        )!!
        assertEquals("81", next.line)
        assertEquals("12분", next.eta)
    }

    @Test
    fun `B remaining soonest after 81 soon is faster 99`() {
        val current = bus("81", "곧 도착")
        val next = NaverNextVehicle.afterSoonest(
            listOf(
                current,
                bus("99", "12분"),
                bus("81", "13분"),
            ),
            current,
        )!!
        assertEquals("99", next.line)
        assertEquals("12분", next.eta)
    }

    @Test
    fun `C remaining soonest after 81 at 5 is later 81`() {
        val current = bus("81", "5분")
        val next = NaverNextVehicle.afterSoonest(
            listOf(
                current,
                bus("81", "12분"),
                bus("99", "13분"),
            ),
            current,
        )!!
        assertEquals("81", next.line)
        assertEquals("12분", next.eta)
    }

    @Test
    fun `D no remaining candidate after current is null`() {
        val current = bus("81", "곧 도착")
        assertNull(NaverNextVehicle.afterSoonest(listOf(current), current))
        assertNull(NaverNextVehicle.afterSoonest(emptyList(), current))
    }

    @Test
    fun `E remaining soonest can be faster than current`() {
        val current = bus("81", "5분")
        val next = NaverNextVehicle.afterSoonest(
            listOf(
                bus("99", "1분"),
                current,
                bus("81", "7분"),
            ),
            current,
        )!!
        assertEquals("99", next.line)
        assertEquals("1분", next.eta)
    }

    private fun bus(line: String, eta: String, stops: Int? = null) =
        NavigationBusArrival(line, eta, occupancy = "여유", stopsRemaining = stops)
}
