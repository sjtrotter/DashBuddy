package cloud.trotter.dashbuddy.test.suites

import cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.view.clicked.ClickClassifierTest
import cloud.trotter.dashbuddy.core.pipeline.notification.NotificationClassifierTest
import cloud.trotter.dashbuddy.core.pipeline.recognition.matchers.GoldenSnapshotRegressionTest
import cloud.trotter.dashbuddy.core.pipeline.rules.ClickRulesetTest
import cloud.trotter.dashbuddy.core.pipeline.rules.DefaultRulesIntegrationTest
import cloud.trotter.dashbuddy.core.pipeline.rules.NotificationRulesetTest
import cloud.trotter.dashbuddy.core.pipeline.rules.ParseOutputGoldenTest
import cloud.trotter.dashbuddy.core.pipeline.rules.ScreenRulesetTest
import cloud.trotter.dashbuddy.core.pipeline.rules.TriageRulesTest
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
 * - [DefaultRulesIntegrationTest] — end-to-end rule compilation/wiring.
 * - [NotificationClassifierTest] / [ClickClassifierTest] — classifier behavior.
 */
@RunWith(Suite::class)
@Suite.SuiteClasses(
    GoldenSnapshotRegressionTest::class,
    ParseOutputGoldenTest::class,
    ScreenRulesetTest::class,
    ClickRulesetTest::class,
    NotificationRulesetTest::class,
    TriageRulesTest::class,
    DefaultRulesIntegrationTest::class,
    NotificationClassifierTest::class,
    ClickClassifierTest::class,
)
class AllMatchersSuite
