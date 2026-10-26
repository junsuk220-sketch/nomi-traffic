package nomi.android.traffic.api.seoul

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeoulRealtimeArrivalTest {

    @Test
    fun `barvlDt seconds become minutes`() {
        assertEquals("2분", SeoulRealtimeArrival.etaOf("전역", "120"))
        assertEquals("곧 도착", SeoulRealtimeArrival.etaOf(null, "30"))
    }

    @Test
    fun `arvlMsg2 minutes are kept`() {
        assertEquals("5분", SeoulRealtimeArrival.etaOf("5분 후 (마두)", null))
        assertEquals("곧 도착", SeoulRealtimeArrival.etaOf("정발산 도착", null))
    }

    @Test
    fun `nth station away is kept when barvlDt is zero`() {
        assertEquals("4분", SeoulRealtimeArrival.etaOf("[2]번째 전역 (백석)", "0"))
        assertEquals("12분", SeoulRealtimeArrival.etaOf("[6]번째 전역 (원흥)", "0"))
        assertEquals("4분", SeoulRealtimeArrival.etaOf("2번째 전역", null))
    }

    @Test
    fun `explicit minutes beat nth station text`() {
        assertEquals("3분", SeoulRealtimeArrival.etaOf("3분 후 [2]번째 전역", "0"))
    }

    @Test
    fun `json list keeps line 3 rows`() {
        val body = """
            {"realtimeArrivalList":[
              {"subwayId":"1003","trainLineNm":"오금행 - 마두방면","arvlMsg2":"2분 후","barvlDt":"120"},
              {"subwayId":"1003","trainLineNm":"오금행 - 마두방면","arvlMsg2":"12분 후","barvlDt":"700"},
              {"subwayId":"1002","trainLineNm":"성수행","arvlMsg2":"1분 후","barvlDt":"60"}
            ]}
        """.trimIndent()
        val arrivals = SeoulRealtimeArrival.parseJson(body)
        assertEquals(3, arrivals.size)
        val rows = SeoulRealtimeArrival.rowsFor(arrivals, line = "3호선", direction = "마두역 방면")
        assertEquals(2, rows.size)
        assertEquals("2분", rows[0].eta)
        assertEquals("12분", rows[1].eta)
        assertNull(rows[0].service)
    }

    @Test
    fun `express and local stay separate when Seoul names them`() {
        val body = """
            {"realtimeArrivalList":[
              {"subwayId":"1009","trainLineNm":"중앙보훈병원행","btrainSttus":"급행","arvlMsg2":"2분 후","barvlDt":"120"},
              {"subwayId":"1009","trainLineNm":"중앙보훈병원행","btrainSttus":"일반","arvlMsg2":"9분 후","barvlDt":"540"}
            ]}
        """.trimIndent()
        val rows = SeoulRealtimeArrival.rowsFor(
            SeoulRealtimeArrival.parseJson(body),
            line = "9호선",
        )
        assertEquals(listOf("급행", "일반"), rows.map { it.service })
        assertEquals(listOf("2분", "9분"), rows.map { it.eta })
    }

    @Test
    fun `unknown subwayId is dropped`() {
        val body = """{"realtimeArrivalList":[{"subwayId":"9999","trainLineNm":"x","arvlMsg2":"1분","barvlDt":"60"}]}"""
        assertTrue(SeoulRealtimeArrival.parseJson(body).isEmpty())
        assertNull(SeoulSubwayIds.lineOf("9999"))
    }
}
