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
        assertTrue(debug.contains("bindCensusEnvelopeSink(impl: PersistentCensusEnvelopeSink): CensusEnvelopeSink"))
        assertTrue(release.contains("fun provideCensusEnvelopeSink(): CensusEnvelopeSink = NoOpCensusEnvelopeSink"))
        assertFalse(release.contains("PersistentCensusEnvelopeSink"))
        val capture = File(root, "core/data/src/release/java/cloud/trotter/dashbuddy/core/data/di/CaptureBusModule.kt")
        assertTrue(capture.readText().contains("NoOpCaptureBus"))
        assertFalse(File(root, "core/data/src/main/$path").exists())
        val application = File(root, "app/src/main/java/cloud/trotter/dashbuddy/DashBuddyApplication.kt").readText()
        assertTrue(application.contains("if (BuildConfig.DEBUG) CensusUploadWorker.schedule(this)"))
    }

    @Test fun `capture bus uses the domain registry screen source and envelope provider has bounded storage`() {
        val root = RepoRoot.locate()
        val registry = File(root, "domain/src/main/java/cloud/trotter/dashbuddy/domain/pipeline/PipelineRegistry.kt").readText()
        val pipeline = File(root, "core/pipeline/src/main/java/cloud/trotter/dashbuddy/core/pipeline/accessibility/AccessibilityPipeline.kt").readText()
        val declaration = "const val SCREEN_PIPELINE_ID = \"accessibility.window\""
        assertTrue(registry.contains(declaration))
        assertTrue(pipeline.contains("SCREEN_PIPELINE_ID = PipelineRegistry.SCREEN_PIPELINE_ID"))
        val bus = File(root, "core/data/src/main/java/cloud/trotter/dashbuddy/core/data/capture/DiskCaptureBus.kt").readText()
        assertTrue(bus.contains("source == PipelineRegistry.SCREEN_PIPELINE_ID"))
        val provider = File(root, "core/data/src/main/java/cloud/trotter/dashbuddy/core/data/di/CensusCredentialsModule.kt").readText()
        assertTrue(provider.contains("@CensusEnvelopeSpool"))
        assertTrue(provider.contains("maxFiles = 200"))
        assertTrue(provider.contains("maxDiskBytes = 20L * 1024 * 1024"))
        assertTrue(provider.contains("census/envelopes"))
    }

    @Test fun `credentials spool and health ledger excluded from every backup channel`() {
        val root = RepoRoot.locate()
        // census/ also covers census/envelopes in every backup channel.
        val paths = listOf("datastore/census_credentials.preferences_pb", "census/", "datastore/census_health.preferences_pb")
        val extraction = File(root, "app/src/main/res/xml/data_extraction_rules.xml").readText()
        val backup = File(root, "app/src/main/res/xml/backup_rules.xml").readText()
        for (path in paths) {
            val exclusion = "<exclude domain=\"file\" path=\"$path\""
            assertTrue(backup.contains(exclusion))
            assertTrue(extraction.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>").contains(exclusion))
            assertTrue(extraction.substringAfter("<device-transfer>").substringBefore("</device-transfer>").contains(exclusion))
        }
        for (domain in listOf("external", "file")) {
            val exclusion = "<exclude domain=\"$domain\" path=\"captures/\""
            assertTrue(backup.contains(exclusion))
            assertTrue(extraction.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>").contains(exclusion))
            assertTrue(extraction.substringAfter("<device-transfer>").substringBefore("</device-transfer>").contains(exclusion))
        }
    }
}
