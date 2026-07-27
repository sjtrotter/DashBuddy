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
 * Negative guard for `uber.screen.home_dashboard` (#874) — the sibling of
 * [UberIdleMapOfflineEvidenceTest], one rule priority down.
 *
 * The rule's third branch claimed `modeHint: offline` from ABSENCE: `glide_bottom_nav_home`
 * exists (the bottom nav renders in EVERY mode, and even under `earnings_activity`) plus
 * `notExists` of the online label. Every fixture in `fixtures/uber_home_dashboard_partial/`
 * is a real capture that satisfied that vacuously — three 68–69-node frames where only the
 * nav bar had inflated (zero content), and one where a *Trip Details* screen is stacked over
 * the dashboard, which says nothing about online/offline at all. A false offline is
 * destructive (it arms `SESSION_END`, and a multi-apping dasher sends no Uber frame to
 * contradict it, so a live dash is permanently split into two `session_records`).
 *
 * The branch now requires POSITIVE evidence — the offline card's own id-anchored headline —
 * so these must classify UNKNOWN: no observation reaches the state machine and the platform
 * region keeps its last known mode until a settled frame or the already-recognized
 * `uber.click.go_offline` arrives.
 *
 * The positive half lives in the golden corpus (the `uber` captures under
 * `snapshots/home_dashboard`, guarded by
 * [cloud.trotter.dashbuddy.core.pipeline.recognition.matchers.GoldenSnapshotRegressionTest]),
 * including the three #875 captures of the offline card rendered WITHOUT the bottom nav.
 */
@RunWith(Parameterized::class)
class UberHomeDashboardOfflineEvidenceTest(
    private val filename: String,
    private val node: UiNode,
) {

    companion object {
        private const val FIXTURES = "fixtures/uber_home_dashboard_partial"

        private val screenRuleset: Ruleset<UiNode> by lazy { TestRulesetFactory.screenRuleset }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> {
            val loaded = TestResourceLoader.loadSnapshots(FIXTURES)
            assertTrue("$FIXTURES must not be empty", loaded.isNotEmpty())
            return loaded.map { (filename, node, _) -> arrayOf(filename, node) }
        }

        private fun hasNavHome(node: UiNode): Boolean =
            node.viewIdResourceName?.endsWith("glide_bottom_nav_home", ignoreCase = true) == true ||
                node.children.any(::hasNavHome)
    }

    /**
     * Pins that each fixture really is an instance of the bug's precondition — it carries the
     * bottom-nav id the absence-claim anchored on. Without this the UNKNOWN assertion below
     * could pass on any unrelated tree and the regression would be untested.
     */
    @Test
    fun `fixture carries the bottom-nav id the absence-claim anchored on`() {
        assertTrue(
            "$filename no longer carries glide_bottom_nav_home — it is not a #874 specimen " +
                "any more; replace it with a real partial-render capture",
            hasNavHome(node),
        )
    }

    /**
     * Scoped to the `uber` platform wire because that is what production does — the classifier
     * always matches against `evalOrderByPlatform[platformWire]` (`ObservationClassifier`), and
     * the frame's wire is resolved from the window's real package. Un-scoped, the Trip Details
     * specimen is claimed by `doordash.screen.notifications_view`, a cross-platform match the
     * partition makes unreachable on a real Uber frame; PR #872 recorded that same drift on
     * these captures and it is out of scope here.
     */
    @Test
    fun `uber home without offline evidence makes no offline claim`() {
        val intent = screenRuleset.matchFirst(node, platformWire = "uber")?.intent ?: "UNKNOWN"
        assertEquals(
            "$filename classified '$intent' — an Uber home frame carrying neither the offline " +
                "card nor the Go Online button is not evidence of being offline, and claiming it " +
                "forges an Online→Offline edge (#874)",
            "UNKNOWN",
            intent,
        )
    }

    /** These are chrome frames; they are committed corpus, so hold them to the same bar. */
    @Test
    fun `fixture carries no dasher-sensitive content`() {
        assertFalse(
            "$filename tripped the security scanner — redact or drop it",
            SnapshotSecurityScanner.scan(node).isToxic,
        )
    }
}
