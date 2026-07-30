package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.test.util.SnapshotSecurityScanner
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Negative guard for `uber.screen.idle_map` (#857).
 *
 * The rule claims `modeHint: offline`, and a false offline claim is destructive: it arms
 * the `SESSION_END` grace, and with the dasher multi-apping in the other platform no Uber
 * frame arrives to contradict it, so the grace commits and a live dash is split into two
 * `session_records` — permanently, since the read model is a faithful refold of the log.
 * Measured over the 2026-07-25 pull: 20 of 27 Online→Offline edges fired on frames with no
 * offline evidence, 6 of which destroyed a real session (7 real toggles → 13 sessions).
 *
 * The cause was a claim from ABSENCE: `home_screen_overlay_container` exists (present online
 * AND offline) plus `notExists` of the online labels. Every fixture in
 * `fixtures/uber_idle_map_partial/` is a real capture of a **partially rendered** Uber home
 * that satisfied that shape vacuously — the status sheet not inflated, the label subtree
 * missing, the label mid-refresh reading "Loading…", and (worst) a 115-node tree with zero
 * text nodes at all. None of them is evidence of anything.
 *
 * The rule now requires POSITIVE offline evidence, so these must classify UNKNOWN — no
 * observation reaches the state machine and the platform region keeps its last known mode
 * until a settled frame or the (already recognized) `uber.click.go_offline` arrives. The
 * corresponding positive half lives in the golden corpus: the two `snapshots/idle_map/
 * *__uber__*` captures, guarded by [cloud.trotter.dashbuddy.core.pipeline.recognition.matchers.GoldenSnapshotRegressionTest].
 */
@RunWith(Parameterized::class)
class UberIdleMapOfflineEvidenceTest(
    private val filename: String,
    private val node: UiNode,
) {

    companion object {
        private const val FIXTURES = "fixtures/uber_idle_map_partial"

        private val screenRuleset: Ruleset<UiNode> by lazy { TestRulesetFactory.screenRuleset }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> {
            val loaded = TestResourceLoader.loadSnapshots(FIXTURES)
            assertTrue("fixtures/$FIXTURES must not be empty", loaded.isNotEmpty())
            return loaded.map { (filename, node, _) -> arrayOf(filename, node) }
        }

        private fun hasContainer(node: UiNode): Boolean =
            node.viewIdResourceName?.endsWith("home_screen_overlay_container", ignoreCase = true) == true ||
                node.children.any(::hasContainer)
    }

    /**
     * Pins that each fixture really is an instance of the bug's precondition — it carries the
     * container the old rule anchored on. Without this the UNKNOWN assertion below could pass
     * on any unrelated tree and the regression would be untested.
     */
    @Test
    fun `fixture carries the home overlay container the absence-claim anchored on`() {
        assertTrue(
            "$filename no longer carries home_screen_overlay_container — it is not a #857 " +
                "specimen any more; replace it with a real partial-render capture",
            hasContainer(node),
        )
    }

    @Test
    fun `partially rendered uber home makes no offline claim`() {
        // #900 (#898 review F5): scope to the uber partition like production does
        // (ObservationClassifier partitions by wire at all three call sites) — unscoped,
        // a future fixture carrying an "Inbox, N new notifications" desc would be claimed
        // cross-platform by doordash.screen.notifications_view via allText folding.
        val intent = screenRuleset.matchFirst(node, platformWire = "uber")?.intent ?: "UNKNOWN"
        assertEquals(
            "$filename classified '$intent' — a partially rendered Uber home carries no offline " +
                "evidence, so claiming one forges an Online→Offline edge (#857)",
            "UNKNOWN",
            intent,
        )
    }

    /** These are chrome/map frames; they are committed corpus, so hold them to the same bar. */
    @Test
    fun `fixture carries no dasher-sensitive content`() {
        assertFalse(
            "$filename tripped the security scanner — redact or drop it",
            SnapshotSecurityScanner.scan(node).isToxic,
        )
    }
}
