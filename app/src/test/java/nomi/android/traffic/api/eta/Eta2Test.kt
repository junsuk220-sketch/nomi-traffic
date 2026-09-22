package nomi.android.traffic.api.eta

import nomi.android.traffic.speech.SpeechLedger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class Eta2Test {

    private val ledger = SpeechLedger()

    @Before
    fun setUp() {
        Eta2.resetForTest()
        ledger.resetForTest()
    }

    @After
    fun tearDown() {
        Eta2.resetForTest()
        ledger.resetForTest()
    }

    @Test
    fun `2 minutes speaks and 4 minutes stays out`() {
        val speak = Eta2.consider(bus("88B", 2, "2분"), ledger, 1_000L)
        assertEquals("88B번, 88B번 버스가 2분 후 도착해요.", speak.request!!.text)
        val four = Eta2.consider(bus("88B", 4, "4분", track = 2), ledger, 2_000L)
        assertNull(four.request)
        assertEquals(EtaSilence.OUT_OF_BAND, four.silence)
    }
}
