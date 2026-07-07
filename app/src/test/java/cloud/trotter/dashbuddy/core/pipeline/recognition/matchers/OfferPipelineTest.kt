package cloud.trotter.dashbuddy.core.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.core.pipeline.ObservationClassifier
import cloud.trotter.dashbuddy.core.pipeline.PipelineEvent
import cloud.trotter.dashbuddy.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.dashbuddy.core.pipeline.rules.JsonRuleInterpreter
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadataProvider
import cloud.trotter.dashbuddy.domain.evaluation.EvaluationConfig
import cloud.trotter.dashbuddy.domain.evaluation.MerchantAction
import cloud.trotter.dashbuddy.domain.evaluation.MetricType
import cloud.trotter.dashbuddy.domain.evaluation.OfferAction
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluator
import cloud.trotter.dashbuddy.domain.evaluation.OfferQuality
import cloud.trotter.dashbuddy.domain.evaluation.ScoringRule
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.io.File

/**
 * Layer 2 (#105): JSON snapshot → recognition → [OfferEvaluator] → [OfferEvaluation].
 *
 * Layer 1 ([cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluatorTest], #75) verifies the
 * evaluator in isolation against hand-constructed `ParsedOffer` inputs — it proves the scoring
 * MATH is correct. This closes the gap one layer up: it feeds a REAL `offer_popup` snapshot from
 * the golden corpus through the production screen ruleset ([TestRulesetFactory], the same one
 * [GoldenSnapshotRegressionTest] guards) to get the `ParsedOffer` the recognition layer actually
 * extracts, then plugs THAT into [OfferEvaluator] — proving the recognition→parse→evaluate WIRING
 * (field names, units, store-name casing/punctuation a `MerchantRule` has to match) is correct, not
 * just the arithmetic.
 *
 * [PipelineTestCase] is the data shape the issue's acceptance criterion asks for: adding a case is
 * adding an entry to [CASES], no code change. Each case pairs a real `offer_popup/` snapshot
 * filename with an [EvaluationConfig] and a fully pinned [PipelineTestCase.Expected] — every
 * expected number below was computed once from the snapshot's already-approved parse output
 * (`snapshots/approved-parse-output.json`, the #433 golden `ParseOutputGoldenTest` guards) and the
 * config's own formulas, hand-verified (see the arithmetic comments on each case), then pinned as a
 * regression guard. This test does NOT re-derive the golden parse values — [ParseOutputGoldenTest]
 * already owns that regression; this test only asserts the DOWNSTREAM evaluation is consistent
 * with them.
 */
@RunWith(Parameterized::class)
class OfferPipelineTest(private val case: PipelineTestCase) {

    companion object {
        private val evaluator = OfferEvaluator()

        /** A classifier wired to the production screen ruleset — no Android Context, mirrors
         *  [cloud.trotter.dashbuddy.test.util.SessionReplay]'s screenClassifier() wiring. */
        private val classifier: ObservationClassifier by lazy {
            ObservationClassifier(
                mock<JsonRuleInterpreter> { on { screenRuleset } doReturn TestRulesetFactory.screenRuleset },
                mock<ReplayMetadataProvider> { on { current() } doReturn ReplayMetadata.EMPTY },
            )
        }

        /** Zero-cost economy — isolates the recognition/parse wiring from fuel-cost arithmetic
         *  (already exhaustively covered by Layer 1's fuel-cost test section). */
        private val zeroCostEconomy = UserEconomy(vehicleClass = VehicleClass.E_BIKE)

        /**
         * A SEDAN economy with ONLY fuel cost non-zero (35 mpg @ $3.50/gal → $0.10/mi; every other
         * maintenance/depreciation/fixed-cost field defaults to 0.0 per [UserEconomy]'s own
         * domain-construction defaults — see its KDoc: "production paths populate these from the
         * VehicleClass preset via the repository", so a bare `UserEconomy(vehicleClass = SEDAN, ...)`
         * stays cost-free except what's set here). Used by cases that want to prove the pipeline
         * carries real per-mile cost through to `netPayAmount`/`dollarsPerMile`.
         */
        private val fuelOnlyEconomy = UserEconomy(
            vehicleClass = VehicleClass.SEDAN,
            vehicleMpg = 35.0,
            gasPricePerGallon = 3.50,
        )

        /**
         * Case 1 — plain single PICKUP delivery (Peter Piper Pizza). Golden parse output:
         * payAmount=9.78, distanceMiles=9.6, itemCount=0, orders=[PICKUP "Peter Piper Pizza"].
         *
         * Hand-check (zero-cost economy, single PAYOUT rule @ $6.00):
         *   netPay = grossPay - dist * 0 = 9.78 (E_BIKE burns no fuel)
         *   dpm = 9.78 / 9.6 = 1.01875
         *   handling = basePickupMinutes = 7.0 (not a shop leg → flat base)
         *   estMinutes = dist*2.5 + 7.0 = 24.0 + 7.0 = 31.0 → estHours = 31/60
         *   hourly = 9.78 / (31/60) = 18.929...
         *   PAYOUT score = min(netPay/target, 1.0) = min(9.78/6.00, 1.0) = 1.0 → 100.0 (clamped)
         *   score >= ACCEPT_THRESHOLD(70) → ACCEPT; 100 >= AWESOME(70) → AWESOME
         */
        private val case1 = PipelineTestCase(
            snapshotFile = "20260128_173308_715_OFFER_POPUP.json",
            config = EvaluationConfig(
                rules = listOf(ScoringRule.MetricRule(id = "payout", metricType = MetricType.PAYOUT, targetValue = 6.00f)),
                userEconomy = zeroCostEconomy,
            ),
            expected = PipelineTestCase.Expected(
                action = OfferAction.ACCEPT,
                qualityLevel = OfferQuality.AWESOME,
                score = 100.0,
                merchantName = "Peter Piper Pizza",
                payAmount = 9.78,
                distanceMiles = 9.6,
                itemCount = 0.0,
                fuelCostEstimate = 0.0,
                netPayAmount = 9.78,
                dollarsPerMile = 1.01875,
                dollarsPerHour = 18.929,
                dollarsPerHourDelta = 0.01,
            ),
        )

        /**
         * Case 2 — shop-only offer (CVS, 4 items). Golden parse output: payAmount=8.15,
         * distanceMiles=5.8, itemCount=4, orders=[SHOP_FOR_ITEMS "CVS"].
         *
         * Hand-check ([fuelOnlyEconomy]: $0.10/mi fuel, single DOLLAR_PER_MILE rule @ $2.00):
         *   fuelCost = 5.8 * 0.10 = 0.58 → netPay = 8.15 - 0.58 = 7.57
         *   dpm = 7.57 / 5.8 = 1.3051724...
         *   handling = max(items / DEFAULT_SHOP_ITEMS_PER_MIN, basePickupMinutes)
         *            = max(4 / 0.8, 7.0) = max(5.0, 7.0) = 7.0 (base overhead wins — a 4-item shop
         *              is faster than the flat per-offer overhead, #556's floor)
         *   estMinutes = 5.8*2.5 + 7.0 = 14.5 + 7.0 = 21.5 → estHours = 21.5/60
         *   hourly = 7.57 / (21.5/60) = 21.1256...
         *   DOLLAR_PER_MILE score = min(dpm/2.00, 1.0) = min(1.3051724/2.00, 1.0) = 0.6525862 → 65.2586
         *   30 < 65.26 < 70 → NOTHING; 60 <= 65.26 < 70 → GREAT
         */
        private val case2 = PipelineTestCase(
            snapshotFile = "20260128_163448_533_OFFER_POPUP.json",
            config = EvaluationConfig(
                rules = listOf(ScoringRule.MetricRule(id = "dpm", metricType = MetricType.DOLLAR_PER_MILE, targetValue = 2.00f)),
                userEconomy = fuelOnlyEconomy,
            ),
            expected = PipelineTestCase.Expected(
                action = OfferAction.NOTHING,
                qualityLevel = OfferQuality.GREAT,
                score = 65.2586,
                merchantName = "CVS",
                payAmount = 8.15,
                distanceMiles = 5.8,
                itemCount = 4.0,
                fuelCostEstimate = 0.58,
                netPayAmount = 7.57,
                dollarsPerMile = 1.30517,
                dollarsPerHour = 21.1256,
                dollarsPerHourDelta = 0.01,
            ),
        )

        /**
         * Case 3 — mixed stacked offer: a SHOP_FOR_ITEMS leg (CVS) + a PICKUP leg (Bill Miller
         * BBQ). Golden parse output: payAmount=6.85, distanceMiles=6.4, itemCount=1 (the shop-only
         * sum, #556), orders=[SHOP_FOR_ITEMS "CVS", PICKUP "Bill Miller BBQ"].
         *
         * This case pins a `MerchantRule.BLOCK` against `"bill miller bbq"` (lowercase) — proving
         * the store name the RECOGNITION layer actually extracted ("Bill Miller BBQ", verbatim off
         * the real offer screen) matches a rule author's case-insensitive input. That's a pipeline-
         * specific integration risk Layer 1 can't catch (its store names are hand-typed to already
         * match).
         *
         * Hand-check ([fuelOnlyEconomy]; PAYOUT rule @ $6.00 is irrelevant — BLOCK short-circuits
         * before any metric scoring, but `netPayAmount`/`dollarsPerMile`/`dollarsPerHour` are still
         * computed unconditionally earlier in `OfferEvaluator.evaluate` and returned even on the
         * BLOCK path):
         *   fuelCost = 6.4 * 0.10 = 0.64 → netPay = 6.85 - 0.64 = 6.21
         *   dpm = 6.21 / 6.4 = 0.9703125
         *   handling = max(1 / 0.8, 7.0) + 1 nonShopLeg * 7.0 = max(1.25, 7.0) + 7.0 = 14.0
         *   estMinutes = 6.4*2.5 + 14.0 = 16.0 + 14.0 = 30.0 → estHours = 0.5
         *   hourly = 6.21 / 0.5 = 12.42
         *   BLOCK("bill miller bbq") matches "Bill Miller BBQ" case-insensitively → DECLINE, score
         *   0.0, qualityLevel=BLOCKED (explicit, not derived from score).
         */
        private val case3 = PipelineTestCase(
            snapshotFile = "20260128_182617_556_OFFER_POPUP.json",
            config = EvaluationConfig(
                rules = listOf(
                    ScoringRule.MetricRule(id = "payout", metricType = MetricType.PAYOUT, targetValue = 6.00f),
                    ScoringRule.MerchantRule(id = "block_bmb", storeName = "bill miller bbq", action = MerchantAction.BLOCK),
                ),
                userEconomy = fuelOnlyEconomy,
            ),
            expected = PipelineTestCase.Expected(
                action = OfferAction.DECLINE,
                qualityLevel = OfferQuality.BLOCKED,
                score = 0.0,
                merchantName = "CVS & Bill Miller BBQ",
                payAmount = 6.85,
                distanceMiles = 6.4,
                itemCount = 1.0,
                fuelCostEstimate = 0.64,
                netPayAmount = 6.21,
                dollarsPerMile = 0.970313,
                dollarsPerHour = 12.42,
                dollarsPerHourDelta = 0.01,
            ),
        )

        /**
         * Case 4 — stacked double-PICKUP offer (Tarka Indian Kitchen + Torchy's Tacos, note the
         * apostrophe — verified straight `'` U+0027, not a curly quote, in the raw snapshot text).
         * Golden parse output: payAmount=12.15, distanceMiles=10.7, itemCount=0 (no shop leg),
         * orders=[PICKUP "Tarka Indian Kitchen", PICKUP "Torchy's Tacos"].
         *
         * This case pins a `MerchantRule.MANUAL_REVIEW` against `"torchy's tacos"` — proving an
         * apostrophe in a real recognized store name round-trips through rule matching intact.
         *
         * Hand-check (zero-cost economy; PAYOUT rule @ $8.00):
         *   netPay = grossPay - 0 = 12.15
         *   dpm = 12.15 / 10.7 = 1.1355140...
         *   handling = basePickupMinutes = 7.0 (neither leg is a shop leg → flat base regardless of
         *     leg count)
         *   estMinutes = 10.7*2.5 + 7.0 = 26.75 + 7.0 = 33.75 → estHours = 33.75/60 = 0.5625
         *   hourly = 12.15 / 0.5625 = 21.6
         *   PAYOUT score = min(12.15/8.00, 1.0) = 1.0 → 100.0 — score is computed normally even
         *     though MANUAL_REVIEW overrides the final action (Layer 1: "score is still computed
         *     normally").
         *   MANUAL_REVIEW("torchy's tacos") matches → action=MANUAL_REVIEW (NOT ACCEPT despite the
         *     100.0 score); qualityLevel is still derived from score (100 → AWESOME), independent
         *     of the action override.
         */
        private val case4 = PipelineTestCase(
            snapshotFile = "20260128_193825_010_OFFER_POPUP.json",
            config = EvaluationConfig(
                rules = listOf(
                    ScoringRule.MetricRule(id = "payout", metricType = MetricType.PAYOUT, targetValue = 8.00f),
                    ScoringRule.MerchantRule(id = "review_torchys", storeName = "torchy's tacos", action = MerchantAction.MANUAL_REVIEW),
                ),
                userEconomy = zeroCostEconomy,
            ),
            expected = PipelineTestCase.Expected(
                action = OfferAction.MANUAL_REVIEW,
                qualityLevel = OfferQuality.AWESOME,
                score = 100.0,
                merchantName = "Tarka Indian Kitchen & Torchy's Tacos",
                payAmount = 12.15,
                distanceMiles = 10.7,
                itemCount = 0.0,
                fuelCostEstimate = 0.0,
                netPayAmount = 12.15,
                dollarsPerMile = 1.135514,
                dollarsPerHour = 21.6,
                dollarsPerHourDelta = 0.01,
            ),
        )

        /**
         * All seeded cases. Add a new offer_popup snapshot here — no test-method or harness change
         * needed (the issue's acceptance criterion).
         */
        private val CASES: List<PipelineTestCase> = listOf(case1, case2, case3, case4)

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<PipelineTestCase> = CASES
    }

    @Test
    fun `offer snapshot recognizes and evaluates as expected`() {
        val file = File("src/test/resources/snapshots/offer_popup/${case.snapshotFile}")
        require(file.isFile) { "Missing snapshot: ${file.absolutePath}" }
        val node = TestResourceLoader.loadNode(file)

        val event = PipelineEvent.Screen(
            timestamp = 0L,
            tree = node,
            snapshot = TreeSnapshot(node, packageName = Platform.DoorDash.packageName),
            packageName = Platform.DoorDash.packageName,
        )
        val observation = classifier.classify(event)
        val offerFields = observation.parsed as? ParsedFields.OfferFields
            ?: error(
                "${case.snapshotFile} did not classify/parse as an offer — recognized as: " +
                    "${observation.target}, parsed as: ${observation.parsed}"
            )

        val evaluation = evaluator.evaluate(offerFields.parsedOffer, case.config)
        case.expected.assertAgainst(evaluation, case.snapshotFile)
    }
}

/**
 * One Layer-2 pipeline case: a real `offer_popup/` snapshot filename + the [EvaluationConfig] to
 * score it with + the pinned [Expected] result. Adding a case is adding one of these — no code
 * change to the test method.
 */
data class PipelineTestCase(
    val snapshotFile: String,
    val config: EvaluationConfig,
    val expected: Expected,
) {
    /**
     * The pinned, hand-checked-then-frozen [OfferEvaluation] fields this case asserts on.
     * Money/rate fields use a small delta (float/double division is not bit-exact);
     * [dollarsPerHourDelta] is separately tunable since the hourly rate compounds the
     * distance/time-model division the most.
     */
    data class Expected(
        val action: OfferAction,
        val qualityLevel: OfferQuality,
        val score: Double,
        val merchantName: String,
        val payAmount: Double,
        val distanceMiles: Double,
        val itemCount: Double,
        val fuelCostEstimate: Double,
        val netPayAmount: Double,
        val dollarsPerMile: Double,
        val dollarsPerHour: Double,
        val dollarsPerHourDelta: Double = 0.01,
    ) {
        fun assertAgainst(evaluation: OfferEvaluation, label: String) {
            assertEquals("$label: action", action, evaluation.action)
            assertEquals("$label: qualityLevel", qualityLevel, evaluation.qualityLevel)
            assertEquals("$label: score", score, evaluation.score, 0.01)
            assertEquals("$label: merchantName", merchantName, evaluation.merchantName)
            assertEquals("$label: payAmount", payAmount, evaluation.payAmount, 0.001)
            assertEquals("$label: distanceMiles", distanceMiles, evaluation.distanceMiles, 0.001)
            assertEquals("$label: itemCount", itemCount, evaluation.itemCount, 0.001)
            assertEquals("$label: fuelCostEstimate", fuelCostEstimate, evaluation.fuelCostEstimate, 0.001)
            assertEquals("$label: netPayAmount", netPayAmount, evaluation.netPayAmount, 0.001)
            assertEquals("$label: dollarsPerMile", dollarsPerMile, evaluation.dollarsPerMile, 0.001)
            assertEquals("$label: dollarsPerHour", dollarsPerHour, evaluation.dollarsPerHour, dollarsPerHourDelta)
        }
    }

    override fun toString(): String = snapshotFile
}
