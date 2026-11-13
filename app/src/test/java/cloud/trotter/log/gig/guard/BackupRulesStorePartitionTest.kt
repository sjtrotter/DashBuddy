package cloud.trotter.log.gig.guard

import cloud.trotter.log.gig.core.datastore.DataStoreFiles
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element

/**
 * #1615: every DataStore store is either carried by a backup ([DataStoreFiles.BACKED_UP]) or excluded from Android
 * backup and transfer, never both and never neither. The full backup file and Android backup then carry the same
 * stores, and a new store has to pick a side.
 */
class BackupRulesStorePartitionTest {

    @Test
    fun `each store is backed up or excluded in every backup section, never both`() {
        val sections = buildList {
            add("backup_rules.xml" to readRules("backup_rules.xml"))
            val extraction = readRules("data_extraction_rules.xml")
            for (name in listOf("cloud-backup", "device-transfer")) {
                add("data_extraction_rules.xml <$name>" to extraction.getElementsByTagName(name).item(0) as Element)
            }
        }
        val problems = mutableListOf<String>()
        for ((label, section) in sections) {
            val excluded = excludedFilePaths(section)
            for (name in DataStoreFiles.ALL) {
                val isExcluded = "datastore/$name.preferences_pb" in excluded
                val isBackedUp = name in DataStoreFiles.BACKED_UP
                if (isExcluded == isBackedUp) {
                    problems += "$label: '$name' is " + if (isBackedUp) "backed up AND excluded" else "neither backed up nor excluded"
                }
            }
        }
        assertEquals("store partition", emptyList<String>(), problems)
    }

    @Test
    fun `DataStoreModule names every store through DataStoreFiles`() {
        val module = File(
            RepoRoot.locate(),
            "core/datastore/src/main/java/cloud/trotter/log/gig/core/datastore/di/DataStoreModule.kt",
        ).readText()
        assertFalse(
            "a store name literal bypasses DataStoreFiles",
            Regex("""preferencesDataStoreFile\(\s*"""").containsMatchIn(module),
        )
    }

    private fun excludedFilePaths(section: Element): Set<String> {
        val nodes = section.getElementsByTagName("exclude")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .filter { it.getAttribute("domain") == "file" }
            .map { it.getAttribute("path") }
            .toSet()
    }

    private fun readRules(name: String): Element = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(File(RepoRoot.locate(), "app/src/main/res/xml/$name"))
        .documentElement
}
