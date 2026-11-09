package cloud.trotter.log.gig.ui.main.setup.permissions

import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.guard.BuildTable
import cloud.trotter.log.gig.guard.RepoRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * #170 (Sol F2) — the screen reading card each build shows, read the way that build merges its strings (its
 * build-type `values/strings.xml` over main's) with that build's own facts from the build table. Screenshots render
 * the debug build's resources only, so this is what pins the Play card: it names DoorDash and Uber Driver and says
 * which offer pop-up it can't read, and it carries no Screen events sentence and no test-build or census text.
 */
class AccessibilityDisclosureCompositionTest {

    private val root = RepoRoot.locate()
    private val builds = BuildTable.rows(root)

    /** Resource id -> name, so a paragraph's id finds its text in a build's XML. */
    private val names: Map<Int, String> = R.string::class.java.fields.associate { it.getInt(null) to it.name }

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

    /** The card's paragraphs as [build] shows them. */
    private fun card(build: String): List<String> {
        val row = builds.getValue(build)
        val merged = strings("app/src/main/res/values/strings.xml") + strings("app/src/$build/res/values/strings.xml")
        val facts = AccessibilityDisclosureFacts.of(row.accessibilityTool, row.wideEventReceipt, row.readsUnboundPlatforms)
        return facts.paragraphs().map { paragraph ->
            val text = merged.getValue(names.getValue(paragraph.res))
            val appNames = paragraph.names
            if (appNames == null) {
                text
            } else {
                String.format(Locale.ROOT, text, joinAppNames(appNames, merged.getValue("perm_a11y_names_pair"), Locale.ROOT))
            }
        }
    }

    @Test
    fun `the Play card names the two bound apps, states the Uber pop-up limit and nothing test-build`() {
        val play = card("release")
        val text = play.joinToString("\n")
        assertTrue(text, play.first().contains("read the screens of DoorDash and Uber Driver, when you turn them on in gig.log."))
        assertTrue(text, play.any { it == "This version of gig.log can't read the offer pop-up in Uber Driver. It reads the other screens in Uber Driver." })
        assertFalse(text, text.contains("Screen events"))
        for (forbidden in listOf("test build", "census", "Instacart", "Walmart")) {
            assertFalse("Play card must not say \"$forbidden\"", text.lowercase().contains(forbidden.lowercase()))
        }
    }

    @Test
    fun `the sideload card names the two bound apps, offers Screen events and has no pop-up limit`() {
        val text = card("sideload").joinToString("\n")
        assertTrue(text, text.contains("read the screens of DoorDash and Uber Driver, when you turn them on in gig.log."))
        assertTrue(text, text.contains("With Screen events on, it also sees which other app is open."))
        assertFalse(text, text.contains("offer pop-up"))
        for (forbidden in listOf("test build", "census")) assertFalse(text, text.lowercase().contains(forbidden))
    }

    @Test
    fun `the debug card names all four apps and offers Screen events`() {
        val text = card("debug").joinToString("\n")
        assertTrue(
            text,
            text.contains("read the screens of DoorDash, Uber Driver, Instacart Shopper and Walmart Spark, when you turn them on in gig.log."),
        )
        assertTrue(text, text.contains("With Screen events on, it also sees which other app is open."))
        assertFalse(text, text.contains("offer pop-up"))
    }

    @Test
    fun `every build's card has the same paragraphs in the same order apart from the per-build ones`() {
        val core = listOf(
            R.string.perm_a11y_use, R.string.perm_a11y_data, R.string.perm_a11y_screenshots,
            R.string.perm_a11y_where, R.string.perm_a11y_taps,
        )
        for ((build, row) in builds) {
            val ids = AccessibilityDisclosureFacts.of(row.accessibilityTool, row.wideEventReceipt, row.readsUnboundPlatforms)
                .paragraphs().map { it.res }
            assertEquals(build, core, ids - setOf(R.string.perm_a11y_overlay_limit, R.string.perm_a11y_screen_events))
        }
    }
}
