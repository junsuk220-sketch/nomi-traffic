package nomi.android.traffic.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechLedgerTest {

    @Test
    fun `empty ledger has no spoken fact`() {
        val ledger = SpeechLedger()
        assertNull(ledger.lastSpokenAtMs())
        assertNull(ledger.lastText())
    }

    @Test
    fun `record keeps last text and time`() {
        val ledger = SpeechLedger()
        ledger.record("81번, 81번 버스가 10분 후 도착해요.", 1_000L)
        assertEquals(1_000L, ledger.lastSpokenAtMs())
        assertEquals("81번, 81번 버스가 10분 후 도착해요.", ledger.lastText())
        ledger.record("81번, 81번 버스가 5분 후 도착해요.", 2_000L)
        assertEquals(2_000L, ledger.lastSpokenAtMs())
        assertEquals("81번, 81번 버스가 5분 후 도착해요.", ledger.lastText())
    }

    @Test
    fun `request is text only`() {
        val request = SpeechRequest(text = "3호선이 곧 도착합니다.")
        assertEquals("3호선이 곧 도착합니다.", request.text)
    }

    @Test
    fun `ledger reset clears facts`() {
        val ledger = SpeechLedger()
        ledger.record("x", 1L)
        ledger.resetForTest()
        assertNull(ledger.lastSpokenAtMs())
        assertNull(ledger.lastText())
    }
}
