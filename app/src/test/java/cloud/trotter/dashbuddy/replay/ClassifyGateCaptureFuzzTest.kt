package cloud.trotter.dashbuddy.replay

import cloud.trotter.dashbuddy.core.pipeline.ObservationClassifier
import cloud.trotter.dashbuddy.core.pipeline.PipelineEvent
import cloud.trotter.dashbuddy.core.pipeline.PipelineStats
import cloud.trotter.dashbuddy.core.pipeline.SensitiveTextMarkers
import cloud.trotter.dashbuddy.core.pipeline.CaptureWriter
import cloud.trotter.dashbuddy.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.dashbuddy.core.pipeline.rules.CompiledRedact
import cloud.trotter.dashbuddy.core.pipeline.rules.JsonRuleInterpreter
import cloud.trotter.dashbuddy.core.pipeline.rules.ScreenRedactionSource
import cloud.trotter.dashbuddy.domain.capture.CaptureBus
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadataProvider
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.pipeline.UNKNOWN_TARGET
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/**
 * #590 fill-in — end-to-end `classify → gate → capture` on adversarial trees.
 *
 * Drives kotest-generated adversarial [UiNode] trees (bounded depth/width within the
 * tree-budget caps, with hostile text: sensitive markers, homoglyph-mutated markers,
 * SSN/PAN shapes, very long strings, unicode, blanks) through the REAL
 * [ObservationClassifier] wired to the PRODUCTION rules ([TestRulesetFactory]) and then
 * the real [CaptureWriter] fail-closed backstop. Asserts:
 *  - classify + capture never throw (no crash on hostile input);
 *  - each frame classifies + captures within a wall-clock budget (bounded time);
 *  - the fail-closed invariant: an UNKNOWN frame that reaches the capture bus NEVER
 *    carried a [SensitiveTextMarkers] hit — a toxic UNKNOWN is always dropped
 *    (post the #590 evasion hardening, homoglyph markers are caught too).
 *
 * Pipeline-service stages (the accessibility gate/dedup) need Android services and are
 * out of scope; this exercises the classify + capture-scrub path directly, mirroring
 * `CaptureScrubTest`'s wiring.
 *
 * A failing property prints its seed; pin it with `PropTestConfig(seed = ...)`.
 */
class ClassifyGateCaptureFuzzTest {

    private val pkg = "com.doordash.driverapp"

    /** No-op redaction source (UNKNOWN frames have no ruleId, so this is never consulted). */
    private val noRedaction = object : ScreenRedactionSource {
        override fun redactFor(ruleId: String): CompiledRedact? = null
    }

    private val classifier = ObservationClassifier(
        mock<JsonRuleInterpreter> {
            on { screenRuleset } doReturn TestRulesetFactory.screenRuleset
        },
        mock<ReplayMetadataProvider> { on { current() } doReturn ReplayMetadata.EMPTY },
    )

    /** A capture bus that records whether it was offered a write this frame. */
    private class RecordingBus : CaptureBus {
        var offered = false
        override fun offer(
            captureId: String,
            source: String,
            classification: String?,
            platform: String,
            envelopeJson: String,
            contentHash: Int?,
        ): String? {
            offered = true
            return captureId
        }
    }

    // --- Adversarial text pool ---------------------------------------------------

    private val toxicTexts = listOf(
        "Available Balance: \$152.10",
        "Routing Number 021000021",
        "123-45-6789",
        "4111 1111 1111 1111",
        "DasherDirect",
        "Available Balance", // NBSP homoglyph — must still be caught post-#590
        "R​outing Number",   // zero-width injected
        "Ａvailable Balance", // fullwidth 'A'
    )
    private val benignTexts = listOf(
        "Dash Now", "Accept", "Decline", "Pickup from Chipotle", "\$7.50", "3.2 mi",
        "Deliver by 7:45 PM", "", "   ", "\uD800 lone-surrogate",
    )

    private fun textArb(rs: RandomSource): String = when (rs.random.nextInt(10)) {
        0, 1 -> toxicTexts[rs.random.nextInt(toxicTexts.size)]
        2 -> "x".repeat(3_000 + rs.random.nextInt(2_000)) // very long
        else -> benignTexts[rs.random.nextInt(benignTexts.size)]
    }

    /** Build a bounded adversarial tree: depth <= [maxDepth], total nodes <= [maxNodes]. */
    private fun buildTree(rs: RandomSource, maxDepth: Int, budget: IntArray): UiNode {
        budget[0]--
        val withId = rs.random.nextInt(4) == 0
        val childCount = if (maxDepth <= 0 || budget[0] <= 0) 0 else rs.random.nextInt(4)
        val children = (0 until childCount).mapNotNull {
            if (budget[0] <= 0) null else buildTree(rs, maxDepth - 1, budget)
        }
        return UiNode(
            text = textArb(rs),
            viewIdResourceName = if (withId) "$pkg:id/n${rs.random.nextInt(50)}" else null,
            children = children,
        )
    }

    private val treeArb: Arb<UiNode> = arbitrary { rs ->
        buildTree(rs, maxDepth = 6, budget = intArrayOf(200))
    }

    private fun event(tree: UiNode) = PipelineEvent.Screen(
        timestamp = 1_000L,
        tree = tree,
        snapshot = TreeSnapshot(tree, packageName = pkg),
        packageName = pkg,
    )

    @Test
    fun `property - adversarial trees never crash classify or capture, stay time-bounded, and never leak an UNKNOWN sensitive frame`() = runTest {
        checkAll(200, treeArb) { tree ->
            val bus = RecordingBus()
            val writer = CaptureWriter(bus, PipelineStats(), noRedaction)

            val start = System.nanoTime()
            val obs = classifier.classify(event(tree)) // must not throw
            writer.captureScreen(obs, event(tree))      // must not throw
            val elapsedMs = (System.nanoTime() - start) / 1_000_000

            assertTrue("classify+capture took ${elapsedMs}ms — not time-bounded", elapsedMs < 2_000)

            // Fail-closed invariant: a toxic UNKNOWN frame is always dropped before the bus.
            if (obs.target == UNKNOWN_TARGET && bus.offered) {
                assertNull(
                    "an UNKNOWN frame with a sensitive marker reached the capture bus — backstop leaked",
                    SensitiveTextMarkers.findMarker(tree),
                )
            }
        }
    }
}
