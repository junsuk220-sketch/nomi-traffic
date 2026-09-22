package nomi.android.traffic.api.eta

import nomi.android.traffic.buswait.BusWaitCore
import nomi.android.traffic.observe.TransitKind
import nomi.android.traffic.speech.SpeechLedger
import nomi.android.traffic.track.VehicleKey
import nomi.android.traffic.track.VehicleSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class Eta10Test {

    private val ledger = SpeechLedger()

    @Before
    fun setUp() {
        Eta10.resetForTest()
        EtaShadowDebug.resetForTest()
        ledger.resetForTest()
    }

    @After
    fun tearDown() {
        Eta10.resetForTest()
        ledger.resetForTest()
    }

    @Test
    fun `9 minutes speaks once`() {
        val vehicle = bus("88B", 9, "9분", track = 1)
        val first = Eta10.consider(vehicle, ledger, 1_000L)
        assertEquals(
            "88B번, 88B번 버스가 9분 후 도착해요.",
            first.request!!.text,
        )
        assertNull(first.silence)
        val again = Eta10.consider(vehicle, ledger, 2_000L)
        assertNull(again.request)
        assertEquals(EtaSilence.ALREADY, again.silence)
    }

    @Test
    fun `4 minutes is out of band`() {
        val out = Eta10.consider(bus("88B", 4, "4분"), ledger, 1_000L)
        assertNull(out.request)
        assertEquals(EtaSilence.OUT_OF_BAND, out.silence)
    }

    @Test
    fun `different vehicle key can speak again`() {
        Eta10.consider(bus("88B", 9, "9분", track = 1), ledger, 1_000L)
        val next = Eta10.consider(bus("88B", 8, "8분", track = 2), ledger, 2_000L)
        assertEquals(
            "88B번, 88B번 버스가 8분 후 도착해요.",
            next.request!!.text,
        )
    }

    @Test
    fun `cooldown blocks 10 after recent speech`() {
        ledger.record("이미 말함", 1_000L)
        val blocked = Eta10.consider(bus("88B", 9, "9분"), ledger, 2_000L)
        assertEquals(EtaSilence.COOLDOWN, blocked.silence)
        val open = Eta10.consider(
            bus("88B", 9, "9분"),
            ledger,
            1_000L + BusWaitCore.STAGE_COOLDOWN_MS,
        )
        assertEquals(
            "88B번, 88B번 버스가 9분 후 도착해요.",
            open.request!!.text,
        )
    }

    @Test
    fun `unsettled vehicle is silent`() {
        val out = Eta10.consider(
            bus("88B", 9, "9분", settled = false),
            ledger,
            1_000L,
        )
        assertEquals(EtaSilence.UNSETTLED, out.silence)
        assertNull(out.request)
    }
}

internal fun bus(
    line: String,
    minutes: Int,
    eta: String,
    track: Int = 1,
    settled: Boolean = true,
    kind: TransitKind = TransitKind.BUS,
) = VehicleSnapshot(
    vehicleKey = VehicleKey(
        generation = 1,
        kind = kind,
        line = line,
        track = track,
    ),
    line = line,
    kind = kind,
    eta = eta,
    etaMinutes = minutes,
    nextVehicle = track > 1,
    settled = settled,
    stop = null,
)
