package cloud.trotter.dashbuddy.guard

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element

/** #1236: consent stays on the device; history and economy preferences follow the user. */
class BackupRulesGuardTest {
    private val expectedExcludes = setOf(
        "file" to "datastore/census_credentials.preferences_pb",
        "file" to "datastore/census_health.preferences_pb",
        "file" to "datastore/dev_settings.preferences_pb",
        "file" to "datastore/rule_capability_grants.preferences_pb",
        "file" to "datastore/consent_event_receipt.preferences_pb",
        "file" to "census/",
    ) + listOf("file", "external").flatMap { domain ->
        listOf("captures/", "app.log", "shareable.log", "shareable.log.1", "logs/")
            .map { domain to it }
    }

    @Test
    fun `full backup cloud backup and device transfer have identical exclusions`() {
        val full = readRules("backup_rules.xml")
        assertEquals("full-backup-content", full.tagName)
        checkSection(full)

        val extraction = readRules("data_extraction_rules.xml")
        assertEquals("data-extraction-rules", extraction.tagName)
        for (name in listOf("cloud-backup", "device-transfer")) {
            val sections = extraction.getElementsByTagName(name)
            assertEquals("Exactly one $name section must be guarded", 1, sections.length)
            checkSection(sections.item(0) as Element)
        }
    }

    @Test
    fun `neither rules file excludes the database or uses an include allowlist`() {
        for (file in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val root = readRules(file)
            // An include allowlist could silently drop history or app/economy preferences too.
            assertEquals("$file must keep Android's default inclusion", 0, root.getElementsByTagName("include").length)
            val excludes = root.getElementsByTagName("exclude")
            for (index in 0 until excludes.length) {
                val domain = (excludes.item(index) as Element).getAttribute("domain")
                assertFalse("$file: the database follows the user", domain == "database")
            }
        }
    }

    private fun checkSection(section: Element) {
        val nodes = section.getElementsByTagName("exclude")
        val actual = (0 until nodes.length).map { index ->
            val rule = nodes.item(index) as Element
            rule.getAttribute("domain") to rule.getAttribute("path")
        }
        assertEquals("${section.tagName}: exact exclusions, no duplicates", expectedExcludes.size, actual.size)
        assertEquals("${section.tagName}: consent/logs excluded, app preferences retained", expectedExcludes, actual.toSet())
    }

    private fun readRules(name: String): Element = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(File(RepoRoot.locate(), "app/src/main/res/xml/$name"))
        .documentElement
}
