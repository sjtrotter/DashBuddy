package cloud.trotter.log.gig.guard

import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.ui.main.setup.permissions.joinAppNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * #170 — the accessibility service description Android shows beside the switch, as each build merges it: the
 * build type's source set (`src/<build>/res/values`) over main's. Main's copy is the Play build's.
 *
 * - Every build's copy names exactly the bound platforms (`Platform.boundPlatforms`), joined the way the
 *   disclosure card joins them: the description is referenced from the service config XML and cannot be
 *   formatted, so it is the one static copy of the names, and this test is what keeps it derived.
 * - It carries the Screen events sentence iff the build table says the build offers Screen events.
 * - The sideload copy is main's word for word plus that sentence; the debug copy is main's up to its sharing
 *   sentences (the test build's own additions follow, pinned by `DebugDisclosureOverrideTest`).
 */
class AccessibilityDescriptionGuardTest {

    private val root = RepoRoot.locate()

    private fun strings(relPath: String): Map<String, String> {
        val file = File(root, relPath)
        if (!file.isFile) return emptyMap()
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val nodes = factory.newDocumentBuilder().parse(file).getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val node = nodes.item(i) as Element
            node.getAttribute("name") to node.textContent.replace("\\'", "'")
        }
    }

    private val main by lazy { strings("app/src/main/res/values/strings.xml") }

    /** The copy a build ends up with: its build-type source set's value, else main's. */
    private fun effective(buildType: String): String =
        strings("app/src/$buildType/res/values/strings.xml")[KEY] ?: main.getValue(KEY)

    private val builds by lazy { BuildTable.rows(root) }

    @Test
    fun `the build table holds the three builds`() {
        assertEquals(setOf("debug", "release", "sideload"), builds.keys)
    }

    @Test
    fun `every build's description names exactly the bound platforms`() {
        val names = joinAppNames(
            Platform.boundPlatforms.map { it.displayName },
            main.getValue("perm_a11y_names_pair"),
            Locale.ROOT,
        )
        val others = Platform.entries.filter { it !in Platform.boundPlatforms && it.packageName != null }
        for (build in builds.keys) {
            val text = effective(build)
            assertTrue("$build names $names", "read the screens of $names, when you turn them on in gig.log." in text)
            others.forEach { assertFalse("$build must not name ${it.displayName}", it.displayName in text) }
        }
    }

    @Test
    fun `the Screen events sentence appears only where the build offers Screen events`() {
        for ((build, row) in builds) {
            assertEquals(
                "$build: Screen events sentence iff wideEventReceipt",
                row.wideEventReceipt,
                SCREEN_EVENTS in effective(build),
            )
        }
    }

    @Test
    fun `the sideload and debug copies are main's plus their own sentences`() {
        val play = effective("release")
        assertEquals("the Play build uses main's copy", main.getValue(KEY), play)
        assertEquals("sideload is main's plus the Screen events sentence", play, effective("sideload").without(SCREEN_EVENTS))
        val debug = effective("debug").without(SCREEN_EVENTS)
        assertTrue(
            "debug is main's up to the sharing sentences",
            debug.startsWith(play.substringBefore(" gig.log itself sends nothing")),
        )
    }

    @Test
    fun `the card's Screen events paragraph is the description's sentence`() {
        assertEquals(SCREEN_EVENTS, main.getValue("perm_a11y_screen_events"))
    }

    @Test
    fun `the description and the card say where screenshots go`() {
        for (build in builds.keys) {
            assertTrue("$build description names gallery sync", "gallery sync or sharing can copy it off your phone" in effective(build))
        }
        assertTrue("gallery sync or sharing can copy it off your phone" in main.getValue("perm_a11y_screenshots"))
    }

    private fun String.without(sentence: String): String = replace(" $sentence", "")

    private companion object {
        const val KEY = "accessibility_service_description"
        const val SCREEN_EVENTS = "With Screen events on, it also sees which other app is open."
    }
}
