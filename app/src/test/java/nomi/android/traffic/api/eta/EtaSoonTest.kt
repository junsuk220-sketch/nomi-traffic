package nomi.android.traffic.api.eta

import nomi.android.traffic.observe.TransitKind
import nomi.android.traffic.speech.SpeechLedger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class EtaSoonTest {

    private val ledger = SpeechLedger()

    @Before
    fun setUp() {
        EtaSoon.resetForTest()
        ledger.resetForTest()
    }

    @After
    fun tearDown() {
        EtaSoon.resetForTest()
        ledger.resetForTest()
    }

    @Test
    fun `soon speaks and cooldown does not block`() {
        ledger.record("방금 말함", 1_000L)
        val speak = EtaSoon.consider(bus("88B", 0, "곧 도착"), ledger, 2_000L)
        assertEquals("88B번, 88B번 버스, 곧 도착합니다.", speak.request!!.text)
        assertNull(speak.silence)
    }

    @Test
    fun `1 minute is soon`() {
        val speak = EtaSoon.consider(bus("88B", 1, "1분"), ledger, 1_000L)
        assertEquals("88B번, 88B번 버스, 곧 도착합니다.", speak.request!!.text)
    }

    @Test
    fun `2 minutes is out of band`() {
        val out = EtaSoon.consider(bus("88B", 2, "2분"), ledger, 1_000L)
        assertEquals(EtaSilence.OUT_OF_BAND, out.silence)
    }

    @Test
    fun `subway soon uses the subway line`() {
        val speak = EtaSoon.consider(
            bus("3호선", 0, "곧 도착", kind = TransitKind.SUBWAY),
            ledger,
            1_000L,
        )
        assertEquals("3호선, 곧 도착합니다.", speak.request!!.text)
    }
}
