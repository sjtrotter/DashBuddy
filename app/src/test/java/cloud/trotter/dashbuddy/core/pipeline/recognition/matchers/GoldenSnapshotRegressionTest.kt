package cloud.trotter.dashbuddy.core.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.core.pipeline.rules.Ruleset
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.test.util.SnapshotSecurityScanner
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

/**
 * Positive regression for the recognition ruleset.
 *
 * Each category folder under `snapshots/` is named after the screen intent its
 * snapshots are expected to classify as (the sorted output of [InboxProcessorTest]).
 * This test re-runs every golden snapshot through the live [TestRulesetFactory]
 * ruleset and asserts it still classifies as its folder's intent — the "positive"
 * half of the regression loop ([UnknownScreenAnalysisTest] is the negative half:
 * `UNKNOWN/` must stay unrecognized).
 *
 * To refresh the corpus after rule changes: drop captures into `snapshots/INBOX/`,
 * run [InboxProcessorTest] (sorts + prunes into intent folders), then this guards
 * them going forward.
 */
@RunWith(Parameterized::class)
class GoldenSnapshotRegressionTest(
    private val folder: String,
    private val filename: String,
    private val node: UiNode,
) {

    companion object {
        // Folders that are not intent-named goldens.
        private val SKIP = setOf("INBOX", "UNKNOWN")

        private val screenRuleset: Ruleset<UiNode> by lazy { TestRulesetFactory.screenRuleset }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}/{1}")
        fun data(): Collection<Array<Any>> {
            val base = File("src/test/resources/snapshots")
            val dirs = base.listFiles { f -> f.isDirectory && f.name !in SKIP }
                ?.sortedBy { it.name } ?: return emptyList()
            val out = mutableListOf<Array<Any>>()
            for (dir in dirs) {
                for ((fn, node, _) in TestResourceLoader.loadSnapshots("snapshots/${dir.name}")) {
                    out.add(arrayOf(dir.name, fn, node))
                }
            }
            return out
        }
    }

    @Test
    fun `golden snapshot still classifies as its folder intent`() {
        val intent = screenRuleset.matchFirst(node)?.intent ?: "UNKNOWN"
        if (folder == "SENSITIVE") {
            // Sensitive screens must be caught either by a sensitive rule or the
            // edge security scanner (the in-app safety net) — never leak as a
            // normal screen unnoticed.
            assertTrue(
                "SENSITIVE/$filename leaked: classified '$intent' and not flagged by the security scanner",
                intent.startsWith("sensitive") || SnapshotSecurityScanner.scan(node).isToxic,
            )
        } else {
            assertEquals(
                "snapshots/$folder/$filename regressed — no longer classifies as '$folder'",
                folder,
                intent,
            )
        }
    }
}
