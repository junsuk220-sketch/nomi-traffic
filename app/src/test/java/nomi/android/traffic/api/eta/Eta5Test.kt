package nomi.android.traffic.api.eta

import nomi.android.traffic.speech.SpeechLedger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class Eta5Test {

    private val ledger = SpeechLedger()

    @Before
    fun setUp() {
        Eta5.resetForTest()
        ledger.resetForTest()
    }

    @After
    fun tearDown() {
        Eta5.resetForTest()
        ledger.resetForTest()
    }

    @Test
    fun `4 minutes speaks and 9 minutes stays out`() {
        val speak = Eta5.consider(bus("81", 4, "4분"), ledger, 1_000L)
        assertEquals("81번, 81번 버스가 4분 후 도착해요.", speak.request!!.text)
        val nine = Eta5.consider(bus("81", 9, "9분"), ledger, 2_000L)
        assertNull(nine.request)
        assertEquals(EtaSilence.OUT_OF_BAND, nine.silence)
    }

    @Test
    fun `2 minutes is not this band`() {
        val out = Eta5.consider(bus("81", 2, "2분"), ledger, 1_000L)
        assertEquals(EtaSilence.OUT_OF_BAND, out.silence)
    }
}
