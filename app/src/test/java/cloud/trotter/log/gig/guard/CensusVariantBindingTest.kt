package cloud.trotter.log.gig.guard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CensusVariantBindingTest {
    @Test fun `every build binds the census sink and only debug binds capture text`() {
        val root = RepoRoot.locate()
        val di = "java/cloud/trotter/log/gig/core/data/di"
        // #1620: one binding for every build (Play, sideload, debug); the consent decides whether it queues anything.
        val census = File(root, "core/data/src/main/$di/CensusSinkModule.kt").readText()
        assertTrue(census.contains("bindCensusSink(impl: HttpCensusSink): CensusSink"))
        for (variant in listOf("debug", "release")) {
            assertFalse("$variant binds no second census sink", File(root, "core/data/src/$variant/$di/CensusSinkModule.kt").exists())
        }
        assertFalse(File(root, "core/data/src/main/java/cloud/trotter/log/gig/core/data/capture/NoOpCensusSink.kt").exists())
        // No screen text leaves a Play or sideload phone: the release variant (which the sideload build also gets,
        // `matchingFallbacks`) binds the no-op capture bus and the no-op envelope sink.
        val debugEnvelope = File(root, "core/data/src/debug/$di/CensusEnvelopeSinkModule.kt").readText()
        val releaseEnvelope = File(root, "core/data/src/release/$di/CensusEnvelopeSinkModule.kt").readText()
        assertTrue(debugEnvelope.contains("bindCensusEnvelopeSink(impl: PersistentCensusEnvelopeSink): CensusEnvelopeSink"))
        assertTrue(releaseEnvelope.contains("fun provideCensusEnvelopeSink(): CensusEnvelopeSink = NoOpCensusEnvelopeSink"))
        assertFalse(releaseEnvelope.contains("PersistentCensusEnvelopeSink"))
        val releaseCapture = File(root, "core/data/src/release/$di/CaptureBusModule.kt").readText()
        assertTrue(releaseCapture.contains("bindCaptureBus(impl: NoOpCaptureBus): CaptureBus"))
        assertFalse(releaseCapture.contains("impl: DiskCaptureBus"))
        assertTrue(File(root, "app/build.gradle.kts").readText().contains("matchingFallbacks += listOf(\"release\")"))
        // The daily window is scheduled in every build; no BuildConfig.DEBUG gate is left on the census path.
        val application = File(root, "app/src/main/java/cloud/trotter/log/gig/GigLogApplication.kt").readText()
        assertTrue(application.contains("censusUploadPlanner.start(applicationScope)"))
        for (path in listOf("worker/CensusUploadWorker.kt", "worker/CensusWorkScheduler.kt")) {
            assertFalse(path, File(root, "app/src/main/java/cloud/trotter/log/gig/$path").readText().contains("BuildConfig.DEBUG"))
        }
    }

    @Test fun `capture bus uses the domain registry screen source and envelope provider has bounded storage`() {
        val root = RepoRoot.locate()
        val registry = File(root, "domain/src/main/java/cloud/trotter/log/gig/domain/pipeline/PipelineRegistry.kt").readText()
        val pipeline = File(root, "core/pipeline/src/main/java/cloud/trotter/log/gig/core/pipeline/accessibility/AccessibilityPipeline.kt").readText()
        val declaration = "const val SCREEN_PIPELINE_ID = \"accessibility.window\""
        assertTrue(registry.contains(declaration))
        assertTrue(pipeline.contains("SCREEN_PIPELINE_ID = PipelineRegistry.SCREEN_PIPELINE_ID"))
        val bus = File(root, "core/data/src/main/java/cloud/trotter/log/gig/core/data/capture/DiskCaptureBus.kt").readText()
        assertTrue(bus.contains("source == PipelineRegistry.SCREEN_PIPELINE_ID"))
        val provider = File(root, "core/data/src/main/java/cloud/trotter/log/gig/core/data/di/CensusCredentialsModule.kt").readText()
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
    @Test fun `notification publisher checks the sink first and never pairs trusted captures`() {
        val root = RepoRoot.locate()
        val publisher = File(root, "core/pipeline/src/main/java/cloud/trotter/log/gig/core/pipeline/census/SkeletonPublisher.kt").readText()
        val notification = publisher.substringAfter("fun publish(obs: Observation.Notification")
        assertTrue(notification.indexOf("if (!sink.isEnabled) return") < notification.indexOf("NotificationSkeletonBuilder.outcome"))
        assertFalse(notification.contains("envelopeSink"))
        val pipeline = File(root, "core/pipeline/src/main/java/cloud/trotter/log/gig/core/pipeline/notification/NotificationPipeline.kt").readText()
        assertTrue(pipeline.indexOf("frameGate.admit") < pipeline.indexOf("captureWriter.captureNotification"))
        assertTrue(pipeline.indexOf("captureWriter.captureNotification") < pipeline.indexOf("skeletonPublisher.publish"))
        assertTrue(pipeline.indexOf("skeletonPublisher.publish") < pipeline.indexOf("val isUnknown ="))
    }

}
