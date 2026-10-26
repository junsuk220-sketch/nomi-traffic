package nomi.android.traffic.api.fastcar

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class FastCarTest {

    @Before
    fun setUp() {
        FastCar.resetForTest()
    }

    @After
    fun tearDown() {
        FastCar.resetForTest()
    }

    @Test
    fun `jeongbalsan board sheet says fast alight twice`() {
        val out = FastCar.onNotification(
            "정발산역 3호선 열차 승차",
            listOf("마두역 방면 빠른 하차: 2-4", "마두역 방면 빠른 하차: 2-4\n오금행 (도착)"),
        )

        assertEquals("빠른 하차 2다시4, 2다시4입니다.", out?.text)
    }

    @Test
    fun `same board repeats stay silent`() {
        FastCar.onNotification("당산역 2호선 열차 승차", listOf("영등포구청역 방면 빠른 하차: 5-3"))
        FastCar.onNotification("당산역 2호선까지 걷기", listOf("외선순환행 (1분)"))

        assertNull(
            FastCar.onNotification("당산역 2호선 열차 승차", listOf("영등포구청역 방면 빠른 하차: 5-3")),
        )
    }

    @Test
    fun `walk-then-board title with fast transfer speaks`() {
        val out = FastCar.onNotification(
            "정발산역 3호선 도보 후 열차 승차",
            listOf("마두역 방면 빠른 환승: 8-1"),
        )

        assertEquals("빠른 환승 8다시1, 8다시1입니다.", out?.text)
    }

    @Test
    fun `two car pairs are both named`() {
        val out = FastCar.onNotification(
            "정발산역 3호선 도보 후 열차 승차",
            listOf("주엽역 방면 빠른 하차: 4-3, 7-1"),
        )

        assertEquals("빠른 하차 4다시3 또는 7다시1, 4다시3 또는 7다시1입니다.", out?.text)
    }

    @Test
    fun `station glued to line still matches`() {
        val out = FastCar.onNotification(
            "서울대입구역 2호선 열차 승차",
            listOf("봉천역 방면 빠른 하차: 8-1"),
        )

        assertEquals("빠른 하차 8다시1, 8다시1입니다.", out?.text)
    }

    @Test
    fun `walk sheet and bus sheet are not triggers`() {
        assertNull(FastCar.onNotification("정발산역 3호선까지 걷기", listOf("오금행 (09:13)")))
        assertNull(FastCar.onNotification("라페스타.먹자골목 버스 승차", listOf("99 (5분)")))
    }

    @Test
    fun `board sheet without cars stays silent`() {
        val out = FastCar.onNotification("정발산역 3호선 열차 승차", listOf("오금행 (도착)"))

        assertNull(out?.text)
    }

    @Test
    fun `guidance start lets the same board speak again`() {
        FastCar.onNotification("정발산역 3호선 열차 승차", listOf("마두역 방면 빠른 하차: 2-4"))
        FastCar.onNotification("길안내를 시작합니다.", listOf("광닭발까지 이동"))

        val out = FastCar.onNotification("정발산역 3호선 열차 승차", listOf("마두역 방면 빠른 하차: 2-4"))

        assertEquals("빠른 하차 2다시4, 2다시4입니다.", out?.text)
    }
}
