package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.dashbuddy.core.pipeline.rules.CompiledRedact
import cloud.trotter.dashbuddy.core.pipeline.rules.JsonRuleInterpreter
import cloud.trotter.dashbuddy.core.pipeline.rules.ScreenRedactionSource
import cloud.trotter.dashbuddy.domain.capture.CaptureBus
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadataProvider
import cloud.trotter.dashbuddy.domain.capture.schema.UiNodeSchema
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.pipeline.UNKNOWN_TARGET
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.io.File

/**
 * #1116 — the two fielded renders of DashBuddy's own reported leak, end to end: the REAL classifier
 * over the production rules (the frames must still be UNKNOWN — this PR changes no recognition), then
 * the REAL [CaptureWriter], and the assertions run on the envelope JSON it offers to the bus.
 *
 * The fixtures are pseudonymized copies (decoy name, `1234 Sample Ridge …`, ZIP `78200`, placeholder
 * provenance) under `backstop-fixtures/1116/` — deliberately OUTSIDE `snapshots/`, so no corpus
 * classifier, golden or pruner treats them as an intent folder; only this test loads them, by explicit
 * path. Failure messages name the check, never the capture contents.
 */
class UnknownAddressBackstopFixtureTest {

    private val pkg = "com.doordash.driverapp"

    private val classifier = ObservationClassifier(
        mock<JsonRuleInterpreter> { on { screenRuleset } doReturn TestRulesetFactory.screenRuleset },
        mock<ReplayMetadataProvider> { on { current() } doReturn ReplayMetadata.EMPTY },
        PlatformAppVersions.NONE,
    )

    private val noRedaction = object : ScreenRedactionSource {
        override fun redactFor(ruleId: String): CompiledRedact? = null
    }

    private class RecordingBus : CaptureBus {
        var envelope: String? = null
        override fun offer(
            captureId: String,
            source: String,
            classification: String?,
            platform: String,
            envelopeJson: String,
            contentHash: Int?,
        ): String? {
            envelope = envelopeJson
            return captureId
        }
    }

    private fun event(tree: UiNode) = PipelineEvent.Screen(
        timestamp = 1_000L,
        tree = tree,
        snapshot = TreeSnapshot(tree, packageName = pkg),
        packageName = pkg,
    )

    private fun load(name: String): UiNode {
        val file = File("src/test/resources/backstop-fixtures/1116/$name")
        assertTrue("fixture $name must exist", file.isFile)
        return TestResourceLoader.loadNode(file)
    }

    private val code = Regex(""""text"\s*:\s*"000"""")

    @Test
    fun `both fielded renders stay UNKNOWN and persist with the address block masked`() {
        val stats = PipelineStats()
        for ((name, secrets) in FIXTURES) {
            val tree = load(name)
            val source = UiNodeSchema.serialize(tree)
            for (s in secrets + COMMON) assertTrue("$name: teeth — the fixture must carry each raw value", source.contains(s))

            val obs = classifier.classify(event(tree))
            assertEquals("$name must classify UNKNOWN under master's rules", UNKNOWN_TARGET, obs.target)

            val bus = RecordingBus()
            CaptureWriter(bus, stats, noRedaction).captureScreen(obs, event(tree))
            val json = bus.envelope
            assertNotNull("$name must still be captured for triage", json)
            json!!
            assertFalse("$name: street line persisted", json.contains("Sample Ridge"))
            assertFalse("$name: city/ST/ZIP persisted", json.contains("78200") || json.contains("San Antonio"))
            assertFalse("$name: customer name persisted", json.contains("Avery K"))
            for (s in secrets) assertFalse("$name: a block secret persisted", json.contains(s))
            for (chrome in CHROME) assertTrue("$name: chrome '$chrome' must survive", json.contains(chrome))
        }
        assertEquals("each capture counted once", 2L, stats.unknownCustomerScrubCount)
    }

    @Test
    fun `the bare code render masks the code itself`() {
        val tree = load(CODE_RENDER)
        assertTrue("teeth: the fixture carries the bare code", code.containsMatchIn(UiNodeSchema.serialize(tree)))
        val bus = RecordingBus()
        CaptureWriter(bus, PipelineStats(), noRedaction).captureScreen(classifier.classify(event(tree)), event(tree))
        assertFalse("the bare 3-digit code persisted", code.containsMatchIn(bus.envelope!!))
    }

    private companion object {
        const val NOTE_RENDER = "2026-09-23_15-49-33-774__doordash__accessibility.window__UNKNOWN__944039.json"
        const val CODE_RENDER = "2026-09-26_13-13-34-464__doordash__accessibility.window__UNKNOWN__15b164.json"
        val COMMON = listOf("Sample Ridge", "78200", "Avery K")
        val FIXTURES = mapOf(
            NOTE_RENDER to listOf("sample house"),
            CODE_RENDER to emptyList(),
        )
        val CHROME = listOf("Close sheet", "Leave it at my door")
    }
}
