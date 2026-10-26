package nomi.android.traffic

import nomi.android.traffic.api.bustransfer.BusTransferDebug
import nomi.android.traffic.api.fastcar.FastCarDebug
import nomi.android.traffic.api.google.read.GoogleCellLog
import nomi.android.traffic.api.nextbus.NextBusNoInfoDebug
import nomi.android.traffic.api.nexttrain.NextTrainDebug
import nomi.android.traffic.api.routelegs.RouteLegsDebug
import nomi.android.traffic.api.transfer.TransferShadowDebug
import nomi.android.traffic.eventfirst.EventFirstLog
import nomi.traffic.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class FieldLogsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `field logs follow the build type`() {
        assertEquals(BuildConfig.DEBUG, FieldLogs.enabled)
    }

    @Test
    fun `purge removes every field file and keeps app data`() {
        val dir = tmp.root
        FieldLogs.FILES.forEach { File(dir, it).writeText("x") }
        val keep = listOf(
            RouteCardAccess.STORE_FILE,
            "event-first-speech.enabled",
            "naver-ride-observe.enabled",
            "profileInstalled",
        )
        keep.forEach { File(dir, it).writeText("keep") }

        assertEquals(FieldLogs.FILES.size, FieldLogs.purge(dir))

        FieldLogs.FILES.forEach { assertFalse(it, File(dir, it).exists()) }
        keep.forEach { assertEquals(it, "keep", File(dir, it).readText()) }
    }

    @Test
    fun `route cards are never a field file`() {
        assertFalse(RouteCardAccess.STORE_FILE in FieldLogs.FILES)
    }

    @Test
    fun `release start purges, debug start keeps`() {
        val dir = tmp.root
        File(dir, "google-ride-observe.jsonl").writeText("x")
        File(dir, RouteCardAccess.STORE_FILE).writeText("cards")

        FieldLogs.purgeIfRelease(dir)

        assertEquals(FieldLogs.enabled, File(dir, "google-ride-observe.jsonl").exists())
        assertEquals("cards", File(dir, RouteCardAccess.STORE_FILE).readText())
    }

    @Test
    fun `attached loggers write only in debug`() {
        val dir = tmp.newFolder("logs")
        TransferShadowDebug.attach(dir)
        TransferShadowDebug.emit("probe")
        FastCarDebug.attach(dir)
        FastCarDebug.emit("probe")
        NextTrainDebug.attach(dir)
        NextTrainDebug.emit("probe")
        BusTransferDebug.attach(dir)
        BusTransferDebug.emit("probe")
        NaverTripStartDebug.attach(dir)
        NaverTripStartDebug.note("probe")
        NextBusNoInfoDebug.attach(dir)
        NextBusNoInfoDebug.emit("probe")
        RouteLegsDebug.attach(dir)
        RouteLegsDebug.emit("probe-${System.nanoTime()}")
        val cell = GoogleCellLog("PROBE", "nomi-google-feed.log")
        cell.attach(dir)
        cell.emit("probe")
        EventFirstLog.attach(dir)
        EventFirstLog.append("{\"ts\":1}", 1L)

        listOf(
            "nomi-transfer.log",
            "nomi-fastcar.log",
            "nomi-nexttrain.log",
            "nomi-bustransfer.log",
            "nomi-tripstart.log",
            "nomi-nextbus.log",
            "nomi-routelegs.log",
            "nomi-google-feed.log",
            "nomi-eventfirst.jsonl",
        ).forEach { name ->
            assertEquals(name, FieldLogs.enabled, File(dir, name).exists())
        }
    }

    @Test
    fun `every log file written in main sources is a field file`() {
        val src = projectFile("src/main/java")
        val literal = Regex(""""([A-Za-z0-9.-]+\.(?:log|jsonl))"""")
        val written = src.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> literal.findAll(file.readText()).map { it.groupValues[1] } }
            .toSet()
        assertTrue(written.isNotEmpty())
        assertEquals(emptySet<String>(), written - FieldLogs.FILES.toSet())
    }

    @Test
    fun `backup and device transfer exclude every field file but not route cards`() {
        val full = excludes(projectFile("src/main/res/xml/backup_rules.xml"), "full-backup-content")
        val cloud = excludes(projectFile("src/main/res/xml/data_extraction_rules.xml"), "cloud-backup")
        val transfer = excludes(projectFile("src/main/res/xml/data_extraction_rules.xml"), "device-transfer")
        for ((name, rules) in listOf("full" to full, "cloud" to cloud, "transfer" to transfer)) {
            assertEquals(name, FieldLogs.FILES.toSet(), rules.getValue("file"))
            assertEquals(name, setOf("setup.xml"), rules.getValue("sharedpref"))
            assertFalse(name, RouteCardAccess.STORE_FILE in rules.getValue("file"))
        }
    }

    private fun excludes(xml: File, section: String): Map<String, Set<String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
        val root = doc.getElementsByTagName(section).item(0) as Element
        val nodes = root.getElementsByTagName("exclude")
        val out = HashMap<String, MutableSet<String>>()
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as Element
            out.getOrPut(e.getAttribute("domain")) { HashSet() }.add(e.getAttribute("path"))
        }
        return out
    }

    private fun projectFile(path: String): File =
        listOf(File(path), File("app", path)).firstOrNull { it.exists() }
            ?: error("not found: $path")
}
