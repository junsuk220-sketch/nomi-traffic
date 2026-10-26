package nomi.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The app is not a disability tool: Play needs the disclosure path, not the tool flag. */
class A11yDeclarationTest {

    @Test
    fun `both accessibility services declare isAccessibilityTool false`() {
        listOf(
            "src/main/res/xml/google_transit_accessibility_service.xml",
            "src/main/res/xml/naver_subway_accessibility_service.xml",
        ).forEach { path ->
            val xml = projectFile(path).readText()
            val values = Regex("""android:isAccessibilityTool="(\w+)"""").findAll(xml).map { it.groupValues[1] }.toList()
            assertEquals(path, listOf("false"), values)
        }
    }

    @Test
    fun `settings open only from screens behind consent`() {
        val main = projectFile("src/main/java/nomi/traffic/MainActivity.kt").readText()
        val opens = Regex("""AccessibilityAccess\.open\w*\(""").findAll(main).count()
        val consentGate = projectFile("src/main/java/nomi/traffic/SetupGate.kt").readText()
        assertTrue(consentGate.contains("consent == A11yConsentState.UNSET) return SetupView.A11Y_CONSENT"))
        assertEquals("guide dialog and guide step (one opener), turn-off hint", 2, opens)
    }

    private fun projectFile(path: String): File =
        listOf(File(path), File("app", path)).firstOrNull { it.exists() }
            ?: error("missing $path")
}
