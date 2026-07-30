package cloud.trotter.dashbuddy.test.suites

import cloud.trotter.dashbuddy.core.pipeline.CaptureBackstopCorpusTest
import cloud.trotter.dashbuddy.core.pipeline.RuleIdLogSafetyTest
import cloud.trotter.dashbuddy.core.pipeline.SensitiveMarkerAssetCoverageTest
import cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.view.clicked.ClickClassifierTest
import cloud.trotter.dashbuddy.core.pipeline.notification.NotificationClassifierTest
import cloud.trotter.dashbuddy.core.pipeline.recognition.matchers.GoldenSnapshotRegressionTest
import cloud.trotter.dashbuddy.core.pipeline.recognition.matchers.OfferPipelineTest
import cloud.trotter.dashbuddy.core.pipeline.recognition.matchers.PickupNoCustomerIdentityTest
import cloud.trotter.dashbuddy.core.pipeline.rules.CaptureRedactionCorpusTest
import cloud.trotter.dashbuddy.core.pipeline.rules.ClickRulesetTest
import cloud.trotter.dashbuddy.core.pipeline.rules.DefaultRulesIntegrationTest
import cloud.trotter.dashbuddy.core.pipeline.rules.GoPuffRecognitionTest
import cloud.trotter.dashbuddy.core.pipeline.rules.NotificationRulesetTest
import cloud.trotter.dashbuddy.core.pipeline.rules.ParseOutputGoldenTest
import cloud.trotter.dashbuddy.core.pipeline.rules.ScreenRulesetTest
import cloud.trotter.dashbuddy.core.pipeline.rules.TriageRulesTest
import cloud.trotter.dashbuddy.core.pipeline.rules.UberDeclineClickRuleTest
import cloud.trotter.dashbuddy.core.pipeline.rules.UberHomeDashboardOfflineEvidenceTest
import cloud.trotter.dashbuddy.core.pipeline.rules.UberIdleMapOfflineEvidenceTest
import cloud.trotter.dashbuddy.core.pipeline.rules.UberOfferExpiryOverlayTest
import cloud.trotter.dashbuddy.core.pipeline.rules.UberOfferKindAndStackStoreTest
import org.junit.runner.RunWith
import org.junit.runners.Suite

/**
 * Recognition / ruleset regression suite.
 *
 * Runs everything that validates the JSON rulesets against the snapshot corpus
 * and synthetic fixtures, WITHOUT touching the rest of the unit suite (state
 * machine, DB, network, UI). Use it as the fast pre-PR check:
 *
 *     ./gradlew :app:testDebugUnitTest --tests "*AllMatchersSuite*"
 *
 * Side-effect-free by design: this is pure verification. The corpus *intake*
 * tools — [cloud.trotter.dashbuddy.core.pipeline.recognition.matchers.InboxProcessorTest]
 * (sorts INBOX) and
 * [cloud.trotter.dashbuddy.core.pipeline.recognition.matchers.UnknownScreenAnalysisTest]
 * (re-triages UNKNOWN, graduating newly-recognized screens) — mutate the corpus
 * and are deliberately NOT members; run those when adding/triaging captures.
 *
 * - [GoldenSnapshotRegressionTest] — positive guard: every snapshot in an intent
 *   folder still classifies as that folder (SENSITIVE: sensitive rule or scanner).
 * - [ParseOutputGoldenTest] — parse-OUTPUT guard (#433): every snapshot's typed
 *   fields match `snapshots/approved-parse-output.json` (regen deliberately with
 *   `-DupdateParseGolden=true` and review the diff); plus the corpus-coverage
 *   ratchet and the dead-dedupeKey-template lint.
 * - [ScreenRulesetTest] / [ClickRulesetTest] / [NotificationRulesetTest] — the
 *   ruleset compiles and the core predicates behave.
 * - [TriageRulesTest] — synthetic fixtures for rules added from capture triage.
 * - [UberDeclineClickRuleTest] — #786: real device click envelopes pin `uber.click.decline_offer`
 *   onto the offer card's anonymous childless dismiss Button, and pin BOTH the accept CardView tap
 *   (subtree-labelled, non-leaf) and the fielded permission-nag "Settings"/"Not now" buttons
 *   (labelled, but otherwise predicate-identical to the X) as NOT matching it — the separability
 *   the rule rests on. Plus per-predicate mutation coverage, so no predicate can be dropped
 *   silently.
 * - [DefaultRulesIntegrationTest] — end-to-end rule compilation/wiring.
 * - [NotificationClassifierTest] / [ClickClassifierTest] — classifier behavior.
 * - [CaptureRedactionCorpusTest] — the #598/#620 redact predicates mask injected
 *   PII across the corpus + notification redact blocks mask name/body, keep store.
 * - [CaptureBackstopCorpusTest] — the #624 recognized-frame customer-marker
 *   backstop finds ZERO leaks over the redacted corpus (false-positive pin).
 * - [SensitiveMarkerAssetCoverageTest] — #762 D10: every `parse.as == "sensitive"` rule's text
 *   anchor across ALL platforms' generated assets is independently caught by the rules-INDEPENDENT
 *   [cloud.trotter.dashbuddy.core.pipeline.SensitiveTextMarkers] backstop (a documented,
 *   shrink-only ledger excuses pre-existing DoorDash debt out of D10's Uber scope).
 * - [UberIdleMapOfflineEvidenceTest] — #857 negative guard: real partially-rendered Uber
 *   home captures (`fixtures/uber_idle_map_partial/`) must classify UNKNOWN, so
 *   `uber.screen.idle_map` can never claim `modeHint: offline` from absence again.
 * - [UberHomeDashboardOfflineEvidenceTest] — #874, the same guard one rule down: real
 *   Uber home captures carrying only the bottom nav (or a Trip Details screen stacked over
 *   the dashboard) — `fixtures/uber_home_dashboard_partial/` — must classify UNKNOWN, so
 *   `uber.screen.home_dashboard` branch 3 can no longer claim offline from absence either.
 * - [UberOfferExpiryOverlayTest] — #858: real captures of Uber's expiry overlay
 *   ("This request is no longer available", rendered OVER the dimmed card inside
 *   `primary_touch_area`) must classify UNKNOWN, so a dead request can never mint an
 *   offer or poison `storeName`/`presentationKey`; plus the paired over-rejection
 *   guard that a clean uber offer still classifies `offer` with a real store.
 * - [OfferPipelineTest] — Layer 2 (#105): a real `offer_popup/` snapshot through the SAME
 *   production ruleset, feeding the recognized `ParsedOffer` into `OfferEvaluator` and pinning the
 *   resulting `OfferEvaluation` — proves the recognition→parse→evaluate wiring, not just the
 *   evaluator math (Layer 1, `:domain`'s `OfferEvaluatorTest`). Pure/side-effect-free like the rest
 *   of this suite (no state machine, DB, or UI).
 * - [PickupNoCustomerIdentityTest] — #548: source-scans every generated rule for the structural
 *   invariant that a PICKUP surface never parses a customer identity (the #526 D6a
 *   `customerNameHash` exception is node-pinned and separately asserted).
 * - [RuleIdLogSafetyTest] — #862 review LOW-2: every reachable (non-sensitive) rule id is
 *   [cloud.trotter.dashbuddy.core.pipeline.SensitiveTextMarkers]-clean, so a rule id can never
 *   self-scrub the recognized-frame backstop WARN that interpolates it.
 * - [GoPuffRecognitionTest] — #501: the GoPuff (DoorDash Drive) warehouse batch-pickup branches
 *   (bin-scan arrival anchor, wait-survey/barcode-failure/multi-order-confirm recognize-only,
 *   the hand-built zone-arrival CTA fixtures) against the real production ruleset.
 * - [UberOfferKindAndStackStoreTest] — #881/#882: the Uber stacked-card `Delivery (N)` chip wins
 *   the top-level display store but NOT the `orders[]`/`presentationKey` read (chip-anchored
 *   identity, immune to the card cycling which store it shows), plus `offerKind: match|direct`
 *   parsing from the CTA — against the fielded corpus.
 *
 * ### Deliberately excluded (#947 membership reconciliation)
 *
 * These also compile the production ruleset via `TestRulesetFactory` but are NOT suite members:
 *  - [cloud.trotter.dashbuddy.core.pipeline.recognition.matchers.InboxProcessorTest] /
 *    [cloud.trotter.dashbuddy.core.pipeline.recognition.matchers.UnknownScreenAnalysisTest] —
 *    corpus-*mutating* intake tools (see above); this suite is pure verification.
 *  - [cloud.trotter.dashbuddy.core.pipeline.notification.NotificationPipelineSensitiveOrderingTest]
 *    — a `RobolectricTestRunner` pipeline-ORDERING integration test (the sensitive gate must run
 *    before `CaptureWriter`), not a ruleset/corpus regression; every other suite member is a plain
 *    JVM test, and pulling in Robolectric's Android shadow runtime here would cost every pre-PR run
 *    the startup it exists to avoid.
 *  - [cloud.trotter.dashbuddy.state.effects.ActuationBindingResolutionTest] — validates the
 *    EFFECTS-layer actuation target-binding resolution (`ClickCandidateRanker` /
 *    `UiInteractionHandler` mirror, #734), which happens to replay a rule-bound target but is
 *    testing the app's click-actuation logic, not screen/intent recognition; out of this suite's
 *    stated scope ("WITHOUT touching the rest of the unit suite").
 *  - [cloud.trotter.dashbuddy.replay.ClassifyGateCaptureFuzzTest] — a `PropSeeds`/`checkAll`
 *    kotest PROPERTY test (200 adversarial-tree samples) in the #590/#878 security-fuzz family,
 *    which is its own documented command (`-Ddashbuddy.propExplore=true`, see the root CLAUDE.md)
 *    with its own seed-exploration doctrine — deliberately kept separate from this suite's
 *    deterministic single-pass corpus/fixture checks.
 */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    GoldenSnapshotRegressionTest::class,
    ParseOutputGoldenTest::class,
    OfferPipelineTest::class,
    ScreenRulesetTest::class,
    ClickRulesetTest::class,
    UberDeclineClickRuleTest::class,
    NotificationRulesetTest::class,
    TriageRulesTest::class,
    UberIdleMapOfflineEvidenceTest::class,
    UberHomeDashboardOfflineEvidenceTest::class,
    UberOfferExpiryOverlayTest::class,
    DefaultRulesIntegrationTest::class,
    NotificationClassifierTest::class,
    ClickClassifierTest::class,
    CaptureRedactionCorpusTest::class,
    CaptureBackstopCorpusTest::class,
    SensitiveMarkerAssetCoverageTest::class,
    PickupNoCustomerIdentityTest::class,
    RuleIdLogSafetyTest::class,
    GoPuffRecognitionTest::class,
    UberOfferKindAndStackStoreTest::class,
)
class AllMatchersSuite
