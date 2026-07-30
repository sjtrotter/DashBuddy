package cloud.trotter.dashbuddy.core.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.core.pipeline.rules.Ruleset
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.test.util.SnapshotSecurityScanner
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

/**
 * The **negative** half of the corpus regression (#941, from the 2026-07-30 ground-up
 * adversarial review §4.4 item 4).
 *
 * Over-match is the dangerous failure direction. Under-match only drops a frame to UNKNOWN
 * (the pipeline's own fail-safe); over-match *forges state* — the #857/#874/#875 class claimed
 * `modeHint: offline` from ABSENCE on partially-rendered Uber home frames, splitting live
 * dashes into phantom `session_records`, and #858's expiry overlay minted an offer named
 * "This request is no longer available". Yet the must-stay-UNKNOWN discipline was, until this
 * test, a handful of frames hard-wired into three rule-specific evidence tests. This is the
 * *general* mechanism: a committed folder of frames that must classify UNKNOWN, checked
 * against the live production rulesets on every run.
 *
 * ### Where the corpus lives, and why there
 *
 * `snapshots/UNKNOWN/negative/`. The parent `snapshots/UNKNOWN/` is gitignored *staging* (raw,
 * unsorted device captures a triage pass drops there — never committed, since they are
 * un-swept for PII), so the committed corpus lives one level down behind an explicit
 * `.gitignore` negation. That subfolder placement is also load-bearing, not cosmetic: the
 * corpus-MUTATING triage tools ([InboxProcessorTest], [UnknownScreenAnalysisTest]) enumerate
 * `.json` *files* in a flat directory listing, so they cannot see — and therefore cannot
 * graduate, move, or prune — anything under `negative/`.
 *
 * ### Adding a frame
 *
 * Drop a sanitized capture in and bump nothing: the floor below is a *minimum*, not an equality
 * pin, so the corpus only grows. A frame must (a) be PII-swept — it is committed, so it is held
 * to the same bar as the golden corpus — and (b) carry the device capture naming convention's
 * `__<platformWire>__` token, so this test can scope classification to that platform's rule
 * partition exactly as production does.
 *
 * ### Relationship to the rule-specific evidence tests
 *
 * [cloud.trotter.dashbuddy.core.pipeline.rules.UberIdleMapOfflineEvidenceTest],
 * [cloud.trotter.dashbuddy.core.pipeline.rules.UberHomeDashboardOfflineEvidenceTest] and
 * [cloud.trotter.dashbuddy.core.pipeline.rules.UberOfferExpiryOverlayTest] stay exactly as they
 * are — their fixtures were COPIED here, not moved. They assert something this test cannot:
 * that each frame is still a *specimen of its specific bug* (it carries
 * `home_screen_overlay_container` / `glide_bottom_nav_home` / `image_message_text`). This test
 * is the platform for frames that have no such dedicated home.
 */
@RunWith(Parameterized::class)
class NegativeCorpusStaysUnknownTest(
    private val filename: String,
    private val node: UiNode,
) {

    companion object {
        /** Committed must-stay-UNKNOWN corpus. See the class KDoc for why it lives here. */
        const val FOLDER = "snapshots/UNKNOWN/negative"

        /**
         * Floor, not an equality pin — the corpus may only GROW. Its job is to make an
         * empty/vanished folder fail: before #941 the negative half green-passed on an empty
         * set, so deleting the whole corpus was indistinguishable from a clean run.
         *
         * Seeded at 10 = the frames the three rule-specific evidence tests already owned
         * (4 × `uber_idle_map_partial` + 4 × `uber_home_dashboard_partial` +
         * 2 × `uber_offer_expiry_overlay`).
         */
        const val MINIMUM_FRAMES = 10

        /** `2026-07-23_19-35-44-857__uber__accessibility.window__idle_map__4571c6.json` → `uber`. */
        private val PLATFORM_TOKEN = Regex("""__([a-z0-9_]+)__""")

        private val screenRuleset: Ruleset<UiNode> by lazy { TestRulesetFactory.screenRuleset }

        /**
         * The wire prefix this frame's platform partition is keyed by, or null when the
         * filename carries no resolvable token (then the assertion runs unscoped — a strictly
         * BROADER bar, so it can never silently weaken).
         */
        fun platformWireOf(filename: String): String? = PLATFORM_TOKEN
            .findAll(filename)
            .map { it.groupValues[1] }
            .firstOrNull { Platform.fromWire(it) != null }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> {
            val loaded = TestResourceLoader.loadSnapshots(FOLDER)
            assertTrue(
                "$FOLDER holds ${loaded.size} frames, below the ${MINIMUM_FRAMES}-frame floor. " +
                    "The negative corpus is the only guard against the over-match direction " +
                    "(#857/#874/#875/#858) — do not shrink it to make a rule change pass; fix " +
                    "the rule. If a frame legitimately stopped being a negative specimen, " +
                    "replace it and lower the floor deliberately, in review.",
                loaded.size >= MINIMUM_FRAMES,
            )
            return loaded.map { (filename, node, _) -> arrayOf(filename, node) }
        }
    }

    /**
     * The whole point: the production rulesets must not claim this frame.
     *
     * Scoped to the frame's own platform partition because that is what production does —
     * `ObservationClassifier` partitions by wire at all three call sites, so an unscoped
     * assertion would fail on cross-platform `allText` folding that can never happen at
     * runtime (#900 / #898 review F5).
     */
    @Test
    fun `committed negative frame still classifies UNKNOWN`() {
        val wire = platformWireOf(filename)
        val intent = screenRuleset.matchFirst(node, platformWire = wire)?.intent ?: "UNKNOWN"
        assertEquals(
            "$filename classified '$intent' — a rule now OVER-matches a frame committed as " +
                "must-stay-UNKNOWN. Over-match forges state (a false mode edge, a phantom " +
                "offer); UNKNOWN is the safe direction. Tighten the rule's `require` to demand " +
                "POSITIVE evidence rather than absence.",
            "UNKNOWN",
            intent,
        )
    }

    /**
     * Naming discipline: without a resolvable platform token the assertion above silently
     * widens to the unscoped partition, so pin the convention rather than let it drift.
     */
    @Test
    fun `negative frame names its platform`() {
        assertTrue(
            "$filename carries no `__<platformWire>__` token, so its classification cannot be " +
                "scoped to a platform partition the way production scopes it. Commit negative " +
                "frames under the device capture naming convention.",
            platformWireOf(filename) != null,
        )
    }

    /** Committed corpus — held to the same PII bar as every golden folder. */
    @Test
    fun `negative frame carries no dasher-sensitive content`() {
        assertFalse(
            "$filename tripped the security scanner — redact or drop it",
            SnapshotSecurityScanner.scan(node).isToxic,
        )
    }

    /**
     * Structural guard on the corpus itself: the flat staging loaders must never be able to
     * reach these files. A stray `.json` dropped directly into `snapshots/UNKNOWN/` is
     * gitignored staging and is fine; a `negative/` frame promoted UP into the staging folder
     * would become graduate-able by [UnknownScreenAnalysisTest] and silently leave the corpus.
     */
    @Test
    fun `negative corpus is not reachable from the flat staging listing`() {
        val staged = TestResourceLoader.loadSnapshots("snapshots/UNKNOWN").map { it.first }
        assertFalse(
            "$filename is ALSO present directly in snapshots/UNKNOWN/, where the mutating " +
                "triage tools can graduate or delete it. Keep negative frames under negative/.",
            filename in staged,
        )
        assertTrue(
            "$FOLDER must be a real directory",
            File("src/test/resources/$FOLDER").isDirectory,
        )
    }
}
