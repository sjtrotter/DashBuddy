package cloud.trotter.log.gig.res

import cloud.trotter.log.gig.guard.RepoRoot
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * #1457 — the test build's disclosure overrides are a pinned set of FULL replacement texts.
 *
 * A debug value replaces its main value whole, so each override must say everything the main text
 * says that is still true in a test build, plus what the test build adds. The set is pinned so a new
 * override (or a dropped one) is a decision, and the debug Spanish file holds no disclosure key: a
 * Spanish value there beats the English debug override and would show a Spanish speaker the
 * release text.
 */
class DebugDisclosureOverrideTest {

    private val root = RepoRoot.locate()

    private fun values(relPath: String, tag: String = "string"): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val nodes = factory.newDocumentBuilder().parse(File(root, relPath)).getElementsByTagName(tag)
        return (0 until nodes.length).associate { i ->
            val node = nodes.item(i) as Element
            node.getAttribute("name") to node.textContent
        }
    }

    private val appMain by lazy { values("app/src/main/res/values/strings.xml") }
    private val appDebug by lazy { values("app/src/debug/res/values/strings.xml") }

    @Test
    fun `the app's debug overrides are the accessibility and notification-access disclosures`() {
        assertEquals(
            setOf("app_name", "accessibility_service_description", "perm_a11y_where", "perm_body_listener"),
            appDebug.keys,
        )
        for (key in appDebug.keys - "app_name") {
            assertTrue("$key overrides a main value", key in appMain)
            assertNotEquals("$key differs from the main text", appMain[key], appDebug[key])
        }
    }

    @Test
    fun `the debug accessibility texts replace the sends-nothing claim and keep to screens`() {
        for (key in listOf("accessibility_service_description", "perm_a11y_where")) {
            val main = appMain.getValue(key)
            val debug = appDebug.getValue(key)
            assertTrue("$key (main) states it sends nothing itself", "sends nothing it reads off your phone" in main)
            assertFalse("$key (debug) must not claim that: census upload sends", "sends nothing" in debug)
            for (text in listOf(main, debug)) {
                assertTrue("$key names the speech engine", "text-to-speech engine, which may use the network" in text)
            }
            assertTrue("$key (debug) names the install ID", "with this install\\'s ID" in debug)
            assertTrue(
                "$key (debug) says rule masking covers only what the rules know",
                "its rules replace the customer details they know about" in debug,
            )
            assertFalse("$key (debug) is about screens; notifications belong to their own card", "notification" in debug.lowercase())
        }
        assertTrue(
            "the debug service description is the main text with its sharing sentences replaced",
            appDebug.getValue("accessibility_service_description").startsWith(
                appMain.getValue("accessibility_service_description").substringBefore(" gig.log itself sends nothing"),
            ),
        )
    }

    @Test
    fun `the debug notification-access card keeps the main text and adds the test build's saving`() {
        val main = appMain.getValue("perm_body_listener")
        val debug = appDebug.getValue("perm_body_listener")
        assertTrue(debug.startsWith(main))
        assertTrue("This test build also saves notification text" in debug)
    }

    @Test
    fun `the debug Spanish file holds no disclosure key`() {
        assertEquals(setOf("app_name"), values("app/src/debug/res/values-es/strings.xml").keys)
    }

    @Test
    fun `the settings debug overrides are the requirement note and the test-build switches`() {
        val main = values("feature/settings/src/main/res/values/strings.xml")
        val debug = values("feature/settings/src/debug/res/values/strings.xml")
        assertEquals(
            setOf("event_receipt_requirement_note", "platform_settings_description", "evidence_settings_master_subtitle"),
            debug.keys,
        )
        debug.keys.forEach { assertNotEquals(it, main[it], debug[it]) }

        val mainBools = values("feature/settings/src/main/res/values/bools.xml", tag = "bool")
        val debugBools = values("feature/settings/src/debug/res/values/bools.xml", tag = "bool")
        val switches = setOf("privacy_show_test_builds", "event_receipt_off_confirm")
        assertEquals(switches, mainBools.keys)
        assertEquals(switches, debugBools.keys)
        switches.forEach {
            assertEquals("$it is off in release", "false", mainBools[it])
            assertEquals("$it is on in the test build", "true", debugBools[it])
        }
    }
}
