package cloud.trotter.dashbuddy.guard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CensusVariantBindingTest {
    @Test fun `only debug binds the HTTP sink and release is structurally inert`() {
        val root = RepoRoot.locate()
        val path = "java/cloud/trotter/dashbuddy/core/data/di/CensusSinkModule.kt"
        val debug = File(root, "core/data/src/debug/$path").readText()
        val release = File(root, "core/data/src/release/$path").readText()
        assertTrue(debug.contains("bindCensusSink(impl: HttpCensusSink): CensusSink"))
        assertTrue(release.contains("bindCensusSink(impl: NoOpCensusSink): CensusSink"))
        assertFalse(release.contains("impl: HttpCensusSink"))
        assertFalse(File(root, "core/data/src/main/$path").exists())
        val application = File(root, "app/src/main/java/cloud/trotter/dashbuddy/DashBuddyApplication.kt").readText()
        assertTrue(application.contains("if (BuildConfig.DEBUG) CensusUploadWorker.schedule(this)"))
    }

    @Test fun `credentials spool and health ledger excluded from every backup channel`() {
        val root = RepoRoot.locate()
        val paths = listOf("datastore/census_credentials.preferences_pb", "census/", "datastore/census_health.preferences_pb")
        val extraction = File(root, "app/src/main/res/xml/data_extraction_rules.xml").readText()
        val backup = File(root, "app/src/main/res/xml/backup_rules.xml").readText()
        for (path in paths) {
            val exclusion = "<exclude domain=\"file\" path=\"$path\""
            assertTrue(backup.contains(exclusion))
            assertTrue(extraction.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>").contains(exclusion))
            assertTrue(extraction.substringAfter("<device-transfer>").substringBefore("</device-transfer>").contains(exclusion))
        }
    }
}
