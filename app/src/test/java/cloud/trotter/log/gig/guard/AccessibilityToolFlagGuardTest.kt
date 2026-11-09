package cloud.trotter.log.gig.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * #1414 — the three builds and their accessibility-tool flag. Google Play allows
 * `android:isAccessibilityTool="true"` only for disability tools, so the Play build (`release`) declares false;
 * the sideload and debug builds declare true, because Android 14+ delivers accessibility-data-sensitive events
 * (Uber's offer pop-up) only to a service that does.
 *
 * Pins, by reading the repo files:
 * - main's `accessibility_service_config.xml` says false (a build left out of the wiring fails toward Play's
 *   posture), the `accessibilityTool` overlay says true, and the two agree on every other attribute;
 * - the build table in `app/build.gradle.kts` holds the three rows, the sideload build is a release build
 *   served the libraries' `release` variants (the no-op capture and census sinks), and the debug build keeps
 *   its application ID suffix and versionCode, so the developer's install updates in place.
 */
class AccessibilityToolFlagGuardTest {

    private val root = RepoRoot.locate()

    @Test
    fun `main declares false, the overlay declares true, and nothing else differs`() {
        val main = attributes("app/src/main/res/xml/accessibility_service_config.xml")
        val overlay = attributes("app/src/accessibilityTool/res/xml/accessibility_service_config.xml")

        assertEquals("the Play build's copy", "false", main[FLAG])
        assertEquals("the debug and sideload copy", "true", overlay[FLAG])
        assertEquals("every other attribute and value is identical", main - FLAG, overlay - FLAG)
    }

    @Test
    fun `the build table holds the three builds with their flags`() {
        val expected = mapOf(
            "debug" to BuildTable.Row(accessibilityTool = true, wideEventReceipt = true),
            "release" to BuildTable.Row(accessibilityTool = false, wideEventReceipt = false),
            "sideload" to BuildTable.Row(accessibilityTool = true, wideEventReceipt = true),
        )
        assertEquals("(accessibilityTool, wideEventReceipt) per build type", expected, BuildTable.rows(root))
    }

    /** #170 — the screen reading card reads both build facts from BuildConfig, derived from the table row. */
    @Test
    fun `every build type's BuildConfig carries both table facts`() {
        val configure = block(buildScript().substringAfter("buildTypes {"), Regex("""configureEach\s*\{"""))
        for ((field, fact) in listOf("WIDE_EVENT_RECEIPT_OFFERED" to "wideEventReceipt", "ACCESSIBILITY_TOOL" to "accessibilityTool")) {
            assertTrue(
                "$field from facts.$fact",
                Regex("""buildConfigField\(\s*"boolean"\s*,\s*"$field"\s*,\s*facts\.$fact\.toString\(\)\s*\)""").containsMatchIn(configure),
            )
        }
    }

    @Test
    fun `the sideload build is a release build served the libraries' release variants`() {
        val sideload = block(buildScript().substringAfter("buildTypes {"), Regex("""create\(\s*"sideload"\s*\)\s*\{"""))
        assertTrue("initWith(release)", Regex("""initWith\(\s*getByName\(\s*"release"\s*\)\s*\)""").containsMatchIn(sideload))
        assertTrue("falls back to release", Regex("""matchingFallbacks\s*\+=\s*listOf\(\s*"release"\s*\)""").containsMatchIn(sideload))
        assertTrue("its own suffix", Regex("""applicationIdSuffix\s*=\s*"\.sideload"""").containsMatchIn(sideload))
    }

    @Test
    fun `the debug build keeps its application ID suffix and versionCode`() {
        val script = buildScript()
        val debug = block(script.substringAfter("buildTypes {"), Regex("""\bdebug\s*\{"""))
        assertTrue("applicationIdSuffix = \".debug\"", Regex("""applicationIdSuffix\s*=\s*"\.debug"""").containsMatchIn(debug))
        assertFalse("no versionCode in the debug block", debug.contains("versionCode"))
        val codes = Regex("""\bversionCode\s*=\s*(\S+)""").findAll(script).map { it.groupValues[1] }.toList()
        assertEquals("one versionCode assignment, the defaultConfig's 1", listOf("1"), codes)
    }

    private fun buildScript(): String = File(root, "app/build.gradle.kts").readText()

    private fun attributes(path: String): Map<String, String> {
        val file = File(root, path)
        assertTrue("missing $path", file.isFile)
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val element: Element = factory.newDocumentBuilder().parse(file).documentElement
        val attrs = element.attributes
        return (0 until attrs.length).map { attrs.item(it) }
            .filter { it.namespaceURI == ANDROID_NS }
            .associate { it.localName to it.nodeValue }
    }

    /** The brace-balanced body after the first match of [opener] in [text]. */
    private fun block(text: String, opener: Regex): String {
        val start = opener.find(text)?.range?.last ?: error("no block matching $opener")
        var depth = 1
        var i = start + 1
        while (depth > 0) {
            when (text[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        return text.substring(start + 1, i - 1)
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val FLAG = "isAccessibilityTool"
    }
}
