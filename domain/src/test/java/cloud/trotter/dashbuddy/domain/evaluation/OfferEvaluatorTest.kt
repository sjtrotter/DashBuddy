package cloud.trotter.dashbuddy.domain.evaluation

import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.domain.model.order.ParsedOrder
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OfferEvaluatorTest {

    private lateinit var evaluator: OfferEvaluator

    /**
     * Zero-cost economy used by all metric scoring tests so they can assert exact values
     * without fuel cost skewing the numbers. Fuel cost behavior has its own test section.
     */
    private val noCostEconomy = UserEconomy(vehicleClass = VehicleClass.E_BIKE)

    // Default config: single PAYOUT rule, target $7.00, no fuel cost
    private val defaultConfig = EvaluationConfig(
        rules = listOf(
            ScoringRule.MetricRule(
                id = "payout",
                metricType = MetricType.PAYOUT,
                targetValue = 7.0f
            )
        ),
        userEconomy = noCostEconomy,
    )

    @Before
    fun setup() {
        evaluator = OfferEvaluator()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun offer(
        pay: Double? = 7.0,
        dist: Double? = 3.0,
        itemCount: Int = 1,
        storeName: String = "Test Store",
        orderType: OrderType = OrderType.PICKUP,
    ) = ParsedOffer(
        offerHash = "test-hash",
        payAmount = pay,
        distanceMiles = dist,
        itemCount = itemCount,
        orders = listOf(
            ParsedOrder(
                orderIndex = 0,
                orderType = orderType,
                storeName = storeName,
                itemCount = itemCount,
                isItemCountEstimated = false,
                badges = emptySet()
            )
        )
    )

    private fun metricRule(metric: MetricType, target: Float, enabled: Boolean = true) =
        ScoringRule.MetricRule(id = metric.name, metricType = metric, targetValue = target, isEnabled = enabled)

    private fun blockRule(storeName: String) =
        ScoringRule.MerchantRule(id = "block_$storeName", storeName = storeName, action = MerchantAction.BLOCK)

    private fun reviewRule(storeName: String) =
        ScoringRule.MerchantRule(id = "review_$storeName", storeName = storeName, action = MerchantAction.MANUAL_REVIEW)

    private fun modifierRule(storeName: String, modifier: Float) =
        ScoringRule.MerchantRule(id = "mod_$storeName", storeName = storeName, action = MerchantAction.SCORE_MODIFIER, scoreModifier = modifier)

    private fun config(vararg rules: ScoringRule) =
        EvaluationConfig(rules = rules.toList(), userEconomy = noCostEconomy)

    /**
     * Builds a [UserEconomy] where only fuel cost is non-zero. Used by fuel-cost
     * tests to isolate fuel behavior from the rest of the operating-cost model.
     */
    private fun fuelOnlyEconomy(vehicleMpg: Double, gasPricePerGallon: Double) = UserEconomy(
        vehicleClass = VehicleClass.SEDAN,
        vehicleMpg = vehicleMpg,
        gasPricePerGallon = gasPricePerGallon,
        tireSetCost = 0.0,
        oilCost = 0.0,
        brakesCost = 0.0,
        fluidsCost = 0.0,
        miscYearly = 0.0,
        includeDepreciation = false,
        insuranceDeltaPerMonth = 0.0,
        registrationDeltaPerYear = 0.0,
        phonePlanTotal = 0.0,
    )

    // -------------------------------------------------------------------------
    // Protect Stats Mode
    // -------------------------------------------------------------------------

    @Test
    fun `protectStatsMode - always returns ACCEPT with score 100`() {
        val cfg = EvaluationConfig(protectStatsMode = true, rules = emptyList(), userEconomy = noCostEconomy)
        val result = evaluator.evaluate(offer(pay = 1.0), cfg)
        assertEquals(OfferAction.ACCEPT, result.action)
        assertEquals(100.0, result.score, 0.0001)
        assertEquals("Protected!", result.qualityLevel)
    }

    @Test
    fun `protectStatsMode - overrides even a very bad offer`() {
        val cfg = EvaluationConfig(protectStatsMode = true, rules = emptyList(), userEconomy = noCostEconomy)
        val result = evaluator.evaluate(offer(pay = 0.01, dist = 100.0), cfg)
        assertEquals(OfferAction.ACCEPT, result.action)
    }

    // -------------------------------------------------------------------------
    // Empty / Disabled Rules
    // -------------------------------------------------------------------------

    @Test
    fun `no active rules - returns NOTHING with score 0`() {
        val cfg = EvaluationConfig(rules = emptyList(), userEconomy = noCostEconomy)
        val result = evaluator.evaluate(offer(), cfg)
        assertEquals(OfferAction.NOTHING, result.action)
        assertEquals(0.0, result.score, 0.0001)
    }

    @Test
    fun `all rules disabled - returns NOTHING with score 0`() {
        val cfg = config(metricRule(MetricType.PAYOUT, 7.0f, enabled = false))
        val result = evaluator.evaluate(offer(), cfg)
        assertEquals(OfferAction.NOTHING, result.action)
        assertEquals(0.0, result.score, 0.0001)
    }

    // -------------------------------------------------------------------------
    // PAYOUT metric
    // -------------------------------------------------------------------------

    @Test
    fun `payout at target - scores full weight, returns ACCEPT`() {
        val cfg = config(metricRule(MetricType.PAYOUT, 7.0f))
        val result = evaluator.evaluate(offer(pay = 7.0), cfg)
        assertEquals(OfferAction.ACCEPT, result.action)
        assertEquals(100.0, result.score, 0.0001)
    }

    @Test
    fun `payout below target - partial score`() {
        val cfg = config(metricRule(MetricType.PAYOUT, 10.0f))
        val result = evaluator.evaluate(offer(pay = 5.0), cfg)
        // score = (5/10) * 100 = 50.0 → NOTHING
        assertEquals(OfferAction.NOTHING, result.action)
        assertEquals(50.0, result.score, 0.0001)
    }

    @Test
    fun `payout zero - scores 0, returns DECLINE`() {
        val cfg = config(metricRule(MetricType.PAYOUT, 7.0f))
        val result = evaluator.evaluate(offer(pay = 0.0), cfg)
        assertEquals(OfferAction.DECLINE, result.action)
        assertEquals(0.0, result.score, 0.0001)
    }

    @Test
    fun `payout null defaults to 0`() {
        val cfg = config(metricRule(MetricType.PAYOUT, 7.0f))
        val result = evaluator.evaluate(offer(pay = null), cfg)
        assertEquals(OfferAction.DECLINE, result.action)
    }

    @Test
    fun `payout above target - clamps to 100`() {
        val cfg = config(metricRule(MetricType.PAYOUT, 7.0f))
        val result = evaluator.evaluate(offer(pay = 100.0), cfg)
        assertEquals(100.0, result.score, 0.0001)
        assertEquals(OfferAction.ACCEPT, result.action)
    }

    // -------------------------------------------------------------------------
    // DOLLAR_PER_MILE metric
    // -------------------------------------------------------------------------

    @Test
    fun `dollar per mile at target - full score`() {
        val cfg = config(metricRule(MetricType.DOLLAR_PER_MILE, 2.0f))
        val result = evaluator.evaluate(offer(pay = 6.0, dist = 3.0), cfg) // $2/mi net (no fuel cost)
        assertEquals(100.0, result.score, 0.0001)
        assertEquals(OfferAction.ACCEPT, result.action)
    }

    @Test
    fun `dollar per mile below target - partial score`() {
        val cfg = config(metricRule(MetricType.DOLLAR_PER_MILE, 2.0f))
        val result = evaluator.evaluate(offer(pay = 3.0, dist = 3.0), cfg) // $1/mi net
        assertEquals(50.0, result.score, 0.0001)
        assertEquals(OfferAction.NOTHING, result.action)
    }

    // -------------------------------------------------------------------------
    // MAX_DISTANCE metric (lower is better)
    // -------------------------------------------------------------------------

    @Test
    fun `max distance - within limit scores above 0`() {
        val cfg = config(metricRule(MetricType.MAX_DISTANCE, 10.0f))
        // dist=5, target=10 → score = (1 - 5/10) * 100 = 50 → NOTHING
        val result = evaluator.evaluate(offer(dist = 5.0), cfg)
        assertTrue(result.score > 0.0)
        assertEquals(OfferAction.NOTHING, result.action)
    }

    @Test
    fun `max distance - well within limit scores ACCEPT`() {
        val cfg = config(metricRule(MetricType.MAX_DISTANCE, 10.0f))
        // dist=2, target=10 → score = (1 - 2/10) * 100 = 80 → ACCEPT
        val result = evaluator.evaluate(offer(dist = 2.0), cfg)
        assertEquals(80.0, result.score, 0.0001)
        assertEquals(OfferAction.ACCEPT, result.action)
    }

    @Test
    fun `max distance - exceeds limit scores 0`() {
        val cfg = config(metricRule(MetricType.MAX_DISTANCE, 5.0f))
        val result = evaluator.evaluate(offer(dist = 10.0), cfg)
        assertEquals(0.0, result.score, 0.0001)
        assertEquals(OfferAction.DECLINE, result.action)
    }

    @Test
    fun `max distance - exactly at limit scores 0`() {
        // dist == target → 1.0 - (dist/target) = 0.0
        val cfg = config(metricRule(MetricType.MAX_DISTANCE, 5.0f))
        val result = evaluator.evaluate(offer(dist = 5.0), cfg)
        assertEquals(0.0, result.score, 0.0001)
    }

    // -------------------------------------------------------------------------
    // ITEM_COUNT metric (lower is better)
    // -------------------------------------------------------------------------

    @Test
    fun `item count - within limit scores above 0`() {
        val cfg = config(metricRule(MetricType.ITEM_COUNT, 10.0f))
        val result = evaluator.evaluate(offer(itemCount = 3), cfg)
        assertTrue(result.score > 0.0)
    }

    @Test
    fun `item count - exceeds limit scores 0, returns DECLINE`() {
        val cfg = config(metricRule(MetricType.ITEM_COUNT, 5.0f))
        val result = evaluator.evaluate(offer(itemCount = 10), cfg)
        assertEquals(0.0, result.score, 0.0001)
        assertEquals(OfferAction.DECLINE, result.action)
    }

    // -------------------------------------------------------------------------
    // ACTIVE_HOURLY metric
    // -------------------------------------------------------------------------

    @Test
    fun `active hourly at target - full score`() {
        // 3 miles * 2.5 min/mi + 7 = 14.5 min = 14.5/60 hrs (default constants)
        // No fuel cost (E_BIKE) → netPay = pay
        val estTimeHours = ((3.0 * UserEconomy.DEFAULT_MINUTES_PER_MILE) + UserEconomy.DEFAULT_BASE_PICKUP_MINUTES) / 60.0
        val targetHourly = 20.0
        val pay = targetHourly * estTimeHours
        val cfg = config(metricRule(MetricType.ACTIVE_HOURLY, targetHourly.toFloat()))
        val result = evaluator.evaluate(offer(pay = pay, dist = 3.0), cfg)
        assertEquals(100.0, result.score, 0.5)
        assertEquals(OfferAction.ACCEPT, result.action)
    }

    // -------------------------------------------------------------------------
    // Multi-rule weighted scoring
    // -------------------------------------------------------------------------

    @Test
    fun `multi-rule - higher ranked rule has more weight`() {
        // Rule 1 (rank 1, highest weight): PAYOUT target $10 → offer pays $10 → score 1.0
        // Rule 2 (rank 2, lower weight):   MAX_DISTANCE target 5mi → offer is 10mi → score 0.0
        // With 2 rules: weights are 2/3 and 1/3
        // Expected final = (1.0 * 2/3 + 0.0 * 1/3) * 100 = 66.67 → NOTHING (not ACCEPT)
        val cfg = config(
            metricRule(MetricType.PAYOUT, 10.0f),
            metricRule(MetricType.MAX_DISTANCE, 5.0f),
        )
        val result = evaluator.evaluate(offer(pay = 10.0, dist = 10.0), cfg)
        assertEquals(66.67, result.score, 0.5)
        assertEquals(OfferAction.NOTHING, result.action)
    }

    @Test
    fun `multi-rule - all rules pass returns ACCEPT`() {
        val cfg = config(
            metricRule(MetricType.PAYOUT, 7.0f),
            metricRule(MetricType.DOLLAR_PER_MILE, 1.5f),
        )
        // $10 / 3mi = $3.33/mi — well above both targets
        val result = evaluator.evaluate(offer(pay = 10.0, dist = 3.0), cfg)
        assertEquals(OfferAction.ACCEPT, result.action)
        assertTrue(result.score >= 70.0)
    }

    // -------------------------------------------------------------------------
    // Output fields
    // -------------------------------------------------------------------------

    @Test
    fun `evaluation output - computed fields are correct`() {
        val cfg = config(metricRule(MetricType.PAYOUT, 7.0f))
        val result = evaluator.evaluate(offer(pay = 7.0, dist = 3.0, storeName = "Taco Bell"), cfg)
        assertEquals(7.0, result.payAmount, 0.0001)
        assertEquals(0.0, result.fuelCostEstimate, 0.0001) // E_BIKE
        assertEquals(7.0, result.netPayAmount, 0.0001)
        assertEquals(3.0, result.distanceMiles, 0.0001)
        assertEquals(7.0 / 3.0, result.dollarsPerMile, 0.01)
        assertEquals("Taco Bell", result.merchantName)
        // 3 miles * 2.5 min/mi + 7 base = 14.5 min
        assertEquals(14.5, result.estimatedTimeMinutes, 0.0001)
    }

    @Test
    fun `evaluation output - estimatedTimeMinutes uses UserEconomy constants`() {
        val economy = UserEconomy(vehicleClass = VehicleClass.E_BIKE, avgMinutesPerMile = 4.0, basePickupMinutes = 10.0)
        val cfg = EvaluationConfig(rules = listOf(metricRule(MetricType.PAYOUT, 7.0f)), userEconomy = economy)
        // 5 miles * 4 min/mi + 10 = 30 min
        val result = evaluator.evaluate(offer(pay = 7.0, dist = 5.0), cfg)
        assertEquals(30.0, result.estimatedTimeMinutes, 0.0001)
    }

    @Test
    fun `evaluation output - multi-store offer joins names`() {
        val offer = ParsedOffer(
            offerHash = "multi",
            payAmount = 10.0,
            distanceMiles = 4.0,
            orders = listOf(
                ParsedOrder(0, OrderType.PICKUP, "Taco Bell", 1, false, emptySet()),
                ParsedOrder(1, OrderType.PICKUP, "Pizza Hut", 1, false, emptySet()),
            )
        )
        val result = evaluator.evaluate(offer, defaultConfig)
        assertEquals("Taco Bell & Pizza Hut", result.merchantName)
    }

    @Test
    fun `evaluation output - duplicate store names deduplicated`() {
        val offer = ParsedOffer(
            offerHash = "same-store",
            payAmount = 10.0,
            distanceMiles = 4.0,
            orders = listOf(
                ParsedOrder(0, OrderType.PICKUP, "McDonald's", 1, false, emptySet()),
                ParsedOrder(1, OrderType.PICKUP, "McDonald's", 1, false, emptySet()),
            )
        )
        val result = evaluator.evaluate(offer, defaultConfig)
        assertEquals("McDonald's", result.merchantName)
    }

    @Test
    fun `evaluation output - no orders shows Unknown Store`() {
        val offer = ParsedOffer(offerHash = "no-orders", payAmount = 7.0, distanceMiles = 3.0)
        val result = evaluator.evaluate(offer, defaultConfig)
        assertEquals("Unknown Store", result.merchantName)
    }

    // -------------------------------------------------------------------------
    // Action thresholds
    // -------------------------------------------------------------------------

    @Test
    fun `action threshold - score exactly 70 returns ACCEPT`() {
        // With 1 PAYOUT rule target=$10, pay=$7 → score = (7/10)*100 = 70.0
        val cfg = config(metricRule(MetricType.PAYOUT, 10.0f))
        val result = evaluator.evaluate(offer(pay = 7.0), cfg)
        assertEquals(70.0, result.score, 0.0001)
        assertEquals(OfferAction.ACCEPT, result.action)
    }

    @Test
    fun `action threshold - score exactly 30 returns DECLINE`() {
        // With 1 PAYOUT rule target=$10, pay=$3 → score = (3/10)*100 = 30.0
        val cfg = config(metricRule(MetricType.PAYOUT, 10.0f))
        val result = evaluator.evaluate(offer(pay = 3.0), cfg)
        assertEquals(30.0, result.score, 0.0001)
        assertEquals(OfferAction.DECLINE, result.action)
    }

    @Test
    fun `action threshold - score between 30 and 70 returns NOTHING`() {
        // pay=$5, target=$10 → score = 50
        val cfg = config(metricRule(MetricType.PAYOUT, 10.0f))
        val result = evaluator.evaluate(offer(pay = 5.0), cfg)
        assertEquals(50.0, result.score, 0.0001)
        assertEquals(OfferAction.NOTHING, result.action)
    }

    // -------------------------------------------------------------------------
    // Target value edge cases
    // -------------------------------------------------------------------------

    @Test
    fun `target value zero - returns full score to prevent divide-by-zero`() {
        val cfg = EvaluationConfig(
            rules = listOf(ScoringRule.MetricRule(id = "zero", metricType = MetricType.PAYOUT, targetValue = 0.0f)),
            userEconomy = noCostEconomy,
        )
        val result = evaluator.evaluate(offer(pay = 5.0), cfg)
        assertEquals(100.0, result.score, 0.0001)
    }

    @Test
    fun `distance null defaults to 1 - prevents divide-by-zero in dpm`() {
        val cfg = config(metricRule(MetricType.DOLLAR_PER_MILE, 2.0f))
        val result = evaluator.evaluate(offer(pay = 2.0, dist = null), cfg)
        // dist defaults to 1.0 → dpm = 2.0/1.0 = 2.0 → score 100
        assertEquals(100.0, result.score, 0.0001)
    }

    // -------------------------------------------------------------------------
    // MerchantRule - BLOCK
    // -------------------------------------------------------------------------

    @Test
    fun `BLOCK rule - matching store short-circuits to DECLINE with score 0`() {
        val cfg = config(metricRule(MetricType.PAYOUT, 7.0f), blockRule("Taco Bell"))
        val result = evaluator.evaluate(offer(pay = 20.0, storeName = "Taco Bell"), cfg)
        assertEquals(OfferAction.DECLINE, result.action)
        assertEquals(0.0, result.score, 0.0001)
        assertEquals("Blocked", result.qualityLevel)
    }

    @Test
    fun `BLOCK rule - matching is case-insensitive`() {
        val cfg = config(blockRule("taco bell"))
        val result = evaluator.evaluate(offer(storeName = "Taco Bell"), cfg)
        assertEquals(OfferAction.DECLINE, result.action)
    }

    @Test
    fun `BLOCK rule - non-matching store passes through to metric scoring`() {
        val cfg = config(metricRule(MetricType.PAYOUT, 7.0f), blockRule("Taco Bell"))
        val result = evaluator.evaluate(offer(pay = 10.0, storeName = "Pizza Hut"), cfg)
        assertEquals(OfferAction.ACCEPT, result.action)
    }

    @Test
    fun `BLOCK rule - overrides protectStatsMode is not applicable (protectStats checked first)`() {
        // protectStatsMode returns ACCEPT before merchant rules are evaluated
        val cfg = EvaluationConfig(protectStatsMode = true, rules = listOf(blockRule("Taco Bell")), userEconomy = noCostEconomy)
        val result = evaluator.evaluate(offer(storeName = "Taco Bell"), cfg)
        assertEquals(OfferAction.ACCEPT, result.action)
    }

    // -------------------------------------------------------------------------
    // MerchantRule - MANUAL_REVIEW
    // -------------------------------------------------------------------------

    @Test
    fun `MANUAL_REVIEW rule - matching store overrides action regardless of score`() {
        val cfg = config(metricRule(MetricType.PAYOUT, 7.0f), reviewRule("Chipotle"))
        // pay=10 → score=100, but MANUAL_REVIEW overrides to MANUAL_REVIEW action
        val result = evaluator.evaluate(offer(pay = 10.0, storeName = "Chipotle"), cfg)
        assertEquals(OfferAction.MANUAL_REVIEW, result.action)
        assertEquals("Recommended: REVIEW", result.recommendationText)
    }

    @Test
    fun `MANUAL_REVIEW rule - non-matching store does not override action`() {
        val cfg = config(metricRule(MetricType.PAYOUT, 7.0f), reviewRule("Chipotle"))
        val result = evaluator.evaluate(offer(pay = 10.0, storeName = "Pizza Hut"), cfg)
        assertEquals(OfferAction.ACCEPT, result.action)
    }

    @Test
    fun `MANUAL_REVIEW rule - score is still computed normally`() {
        val cfg = config(metricRule(MetricType.PAYOUT, 10.0f), reviewRule("Chipotle"))
        // pay=5, target=10 → score=50
        val result = evaluator.evaluate(offer(pay = 5.0, storeName = "Chipotle"), cfg)
        assertEquals(OfferAction.MANUAL_REVIEW, result.action)
        assertEquals(50.0, result.score, 0.0001)
    }

    // -------------------------------------------------------------------------
    // MerchantRule - SCORE_MODIFIER
    // -------------------------------------------------------------------------

    @Test
    fun `SCORE_MODIFIER - positive boost raises score`() {
        val cfg = config(metricRule(MetricType.PAYOUT, 10.0f), modifierRule("Chipotle", 1.5f))
        // pay=5, target=10 → raw score=50, * 1.5 = 75 → ACCEPT
        val result = evaluator.evaluate(offer(pay = 5.0, storeName = "Chipotle"), cfg)
        assertEquals(75.0, result.score, 0.0001)
        assertEquals(OfferAction.ACCEPT, result.action)
    }

    @Test
    fun `SCORE_MODIFIER - penalty reduces score`() {
        val cfg = config(metricRule(MetricType.PAYOUT, 7.0f), modifierRule("Chipotle", 0.5f))
        // pay=7, target=7 → raw score=100, * 0.5 = 50 → NOTHING
        val result = evaluator.evaluate(offer(pay = 7.0, storeName = "Chipotle"), cfg)
        assertEquals(50.0, result.score, 0.0001)
        assertEquals(OfferAction.NOTHING, result.action)
    }

    @Test
    fun `SCORE_MODIFIER - score clamped to 100 on extreme boost`() {
        val cfg = config(metricRule(MetricType.PAYOUT, 7.0f), modifierRule("Chipotle", 10.0f))
        val result = evaluator.evaluate(offer(pay = 7.0, storeName = "Chipotle"), cfg)
        assertEquals(100.0, result.score, 0.0001)
    }

    @Test
    fun `SCORE_MODIFIER - non-matching store modifier is not applied`() {
        val cfg = config(metricRule(MetricType.PAYOUT, 10.0f), modifierRule("Chipotle", 0.1f))
        // pay=7, target=10 → raw score=70; modifier does NOT apply (wrong store)
        val result = evaluator.evaluate(offer(pay = 7.0, storeName = "Pizza Hut"), cfg)
        assertEquals(70.0, result.score, 0.0001)
        assertEquals(OfferAction.ACCEPT, result.action)
    }

    @Test
    fun `SCORE_MODIFIER - multiple matching modifiers are multiplied together`() {
        val cfg = config(
            metricRule(MetricType.PAYOUT, 10.0f),
            modifierRule("Chipotle", 2.0f),
            modifierRule("Chipotle", 0.5f), // 2.0 * 0.5 = 1.0 → no net change
        )
        val result = evaluator.evaluate(offer(pay = 5.0, storeName = "Chipotle"), cfg)
        assertEquals(50.0, result.score, 0.0001)
    }

    // -------------------------------------------------------------------------
    // MerchantRule - Merchant rules do not contribute to rank-based weights
    // -------------------------------------------------------------------------

    @Test
    fun `merchant rules do not skew rank-based metric weights`() {
        // With 1 metric rule (PAYOUT, target=$10) and 1 BLOCK rule for a different store,
        // the metric weight should still be 1.0 (sole metric rule gets full weight).
        val cfg = config(metricRule(MetricType.PAYOUT, 10.0f), blockRule("Taco Bell"))
        // Pizza Hut offer: not blocked, pay=7 → score = (7/10)*100 = 70 → ACCEPT
        val result = evaluator.evaluate(offer(pay = 7.0, storeName = "Pizza Hut"), cfg)
        assertEquals(70.0, result.score, 0.0001)
        assertEquals(OfferAction.ACCEPT, result.action)
    }

    // -------------------------------------------------------------------------
    // Fuel cost (UserEconomy / #78)
    // -------------------------------------------------------------------------

    @Test
    fun `fuel cost - CAR economy deducts cost from net pay`() {
        // $3.50/gal, 35 mpg → $0.10/mi; 5 miles → $0.50 fuel cost
        val carEconomy = fuelOnlyEconomy(vehicleMpg = 35.0, gasPricePerGallon = 3.50)
        val cfg = EvaluationConfig(rules = listOf(metricRule(MetricType.PAYOUT, 10.0f)), userEconomy = carEconomy)
        val result = evaluator.evaluate(offer(pay = 10.0, dist = 5.0), cfg)
        assertEquals(0.50, result.fuelCostEstimate, 0.001)
        assertEquals(9.50, result.netPayAmount, 0.001)
        assertEquals(10.0, result.payAmount, 0.001)
    }

    @Test
    fun `fuel cost - E_BIKE has zero fuel cost`() {
        val cfg = EvaluationConfig(rules = listOf(metricRule(MetricType.PAYOUT, 7.0f)), userEconomy = noCostEconomy)
        val result = evaluator.evaluate(offer(pay = 7.0, dist = 10.0), cfg)
        assertEquals(0.0, result.fuelCostEstimate, 0.0001)
        assertEquals(7.0, result.netPayAmount, 0.0001)
    }

    @Test
    fun `fuel cost - metric scoring uses net pay not gross pay`() {
        // $4.00/gal, 20 mpg → $0.20/mi; 5 miles → $1.00 fuel cost
        // grossPay=$8, netPay=$7, target=$7 → PAYOUT score = 7/7 = 100
        val carEconomy = fuelOnlyEconomy(vehicleMpg = 20.0, gasPricePerGallon = 4.00)
        val cfg = EvaluationConfig(rules = listOf(metricRule(MetricType.PAYOUT, 7.0f)), userEconomy = carEconomy)
        val result = evaluator.evaluate(offer(pay = 8.0, dist = 5.0), cfg)
        assertEquals(1.0, result.fuelCostEstimate, 0.001)
        assertEquals(7.0, result.netPayAmount, 0.001)
        assertEquals(100.0, result.score, 0.001)
        assertEquals(OfferAction.ACCEPT, result.action)
    }

    @Test
    fun `fuel cost - high fuel cost can push net pay negative, scores 0`() {
        // $6.00/gal, 10 mpg → $0.60/mi; 10 miles → $6 fuel cost; grossPay=$5 → netPay=-$1
        val expensiveEconomy = fuelOnlyEconomy(vehicleMpg = 10.0, gasPricePerGallon = 6.00)
        val cfg = EvaluationConfig(rules = listOf(metricRule(MetricType.PAYOUT, 5.0f)), userEconomy = expensiveEconomy)
        val result = evaluator.evaluate(offer(pay = 5.0, dist = 10.0), cfg)
        assertEquals(0.0, result.score, 0.001)
        assertEquals(OfferAction.DECLINE, result.action)
    }

    // -------------------------------------------------------------------------
    // Time estimate constants (UserEconomy / #81)
    // -------------------------------------------------------------------------

    @Test
    fun `time estimate - custom minutesPerMile affects hourly calculation`() {
        // Rural market: slower pace, 5 min/mile instead of default 2.5
        val ruralEconomy = UserEconomy(
            vehicleClass = VehicleClass.E_BIKE, // no fuel cost so we isolate time
            avgMinutesPerMile = 5.0,
            basePickupMinutes = 7.0,
        )
        // 3 miles * 5 min/mi + 7 = 22 min = 22/60 hrs
        val estTimeHours = (3.0 * 5.0 + 7.0) / 60.0
        val targetHourly = 20.0
        val pay = targetHourly * estTimeHours // pay that exactly hits target at rural pace
        val cfg = EvaluationConfig(
            rules = listOf(metricRule(MetricType.ACTIVE_HOURLY, targetHourly.toFloat())),
            userEconomy = ruralEconomy,
        )
        val result = evaluator.evaluate(offer(pay = pay, dist = 3.0), cfg)
        assertEquals(100.0, result.score, 0.5)
    }

    @Test
    fun `time estimate - custom basePickupMinutes affects hourly calculation`() {
        // Long-wait market: 15 min base overhead
        val slowPickupEconomy = UserEconomy(
            vehicleClass = VehicleClass.E_BIKE,
            avgMinutesPerMile = UserEconomy.DEFAULT_MINUTES_PER_MILE,
            basePickupMinutes = 15.0,
        )
        val estTimeHours = (3.0 * UserEconomy.DEFAULT_MINUTES_PER_MILE + 15.0) / 60.0
        val targetHourly = 20.0
        val pay = targetHourly * estTimeHours
        val cfg = EvaluationConfig(
            rules = listOf(metricRule(MetricType.ACTIVE_HOURLY, targetHourly.toFloat())),
            userEconomy = slowPickupEconomy,
        )
        val result = evaluator.evaluate(offer(pay = pay, dist = 3.0), cfg)
        assertEquals(100.0, result.score, 0.5)
    }

    // -------------------------------------------------------------------------
    // Caveat warnings (#80)
    // -------------------------------------------------------------------------

    @Test
    fun `warnings - realistic targets produce no warnings`() {
        val cfg = config(
            metricRule(MetricType.DOLLAR_PER_MILE, 1.50f),
            metricRule(MetricType.ACTIVE_HOURLY, 20.0f),
            metricRule(MetricType.PAYOUT, 8.0f),
        )
        val result = evaluator.evaluate(offer(pay = 10.0, dist = 3.0), cfg)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `warnings - dollar per mile above threshold generates warning`() {
        val cfg = config(metricRule(MetricType.DOLLAR_PER_MILE, 2.50f)) // above $2.00 max
        val result = evaluator.evaluate(offer(pay = 10.0, dist = 3.0), cfg)
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings[0].contains("2.50"))
    }

    @Test
    fun `warnings - active hourly above threshold generates warning`() {
        val cfg = config(metricRule(MetricType.ACTIVE_HOURLY, 40.0f)) // above $35/hr max
        val result = evaluator.evaluate(offer(pay = 10.0, dist = 3.0), cfg)
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings[0].contains("40.00"))
    }

    @Test
    fun `warnings - payout above threshold generates warning`() {
        val cfg = config(metricRule(MetricType.PAYOUT, 20.0f)) // above $15 max
        val result = evaluator.evaluate(offer(pay = 10.0, dist = 3.0), cfg)
        assertEquals(1, result.warnings.size)
        assertTrue(result.warnings[0].contains("20.00"))
    }

    @Test
    fun `warnings - multiple unrealistic targets produce multiple warnings`() {
        val cfg = config(
            metricRule(MetricType.DOLLAR_PER_MILE, 3.00f),
            metricRule(MetricType.PAYOUT, 25.0f),
        )
        val result = evaluator.evaluate(offer(pay = 10.0, dist = 3.0), cfg)
        assertEquals(2, result.warnings.size)
    }

    @Test
    fun `warnings - MAX_DISTANCE and ITEM_COUNT never trigger warnings`() {
        val cfg = config(
            metricRule(MetricType.MAX_DISTANCE, 100.0f),
            metricRule(MetricType.ITEM_COUNT, 100.0f),
        )
        val result = evaluator.evaluate(offer(pay = 10.0, dist = 3.0), cfg)
        assertTrue(result.warnings.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Operating cost — full breakdown (#145)
    // -------------------------------------------------------------------------

    @Test
    fun `operating cost - subtracts all cost components from gross pay`() {
        // Construct a fully-populated economy and verify each per-mile component
        // contributes to net pay. Distance = 10 mi so per-mile values translate
        // directly to dollar amounts.
        val economy = UserEconomy(
            vehicleClass = VehicleClass.SEDAN,
            vehicleMpg = 35.0, gasPricePerGallon = 3.50,          // fuel: $0.10/mi → $1.00
            tireSetCost = 800.0, tireLifetimeMi = 40_000.0,       // tires: $0.02/mi → $0.20
            oilCost = 60.0, oilIntervalMi = 5_000.0,              // oil: $0.012/mi → $0.12
            brakesCost = 400.0, brakesIntervalMi = 40_000.0,      // brakes: $0.01/mi → $0.10
            fluidsCost = 150.0, fluidsIntervalMi = 30_000.0,      // fluids: $0.005/mi → $0.05
            miscYearly = 600.0, miscYearlyMi = 12_000.0,          // misc: $0.05/mi → $0.50
            includeDepreciation = true,
            purchasePrice = 28_000.0, totalLifetimeMi = 200_000.0, // depr: $0.14/mi → $1.40
            insuranceDeltaPerMonth = 30.0,
            expectedAnnualDashMiles = 10_000.0,                    // insurance: $0.036/mi → $0.36
            registrationDeltaPerYear = 100.0,                      // reg: $0.01/mi → $0.10
            phonePlanTotal = 80.0, phonePlanLines = 4, phoneDashPercent = 30.0, // phone: $0.0072/mi → $0.072
        )
        val cfg = EvaluationConfig(rules = listOf(metricRule(MetricType.PAYOUT, 10.0f)), userEconomy = economy)
        val result = evaluator.evaluate(offer(pay = 10.0, dist = 10.0), cfg)
        // Total expected: 1.00 + 0.20 + 0.12 + 0.10 + 0.05 + 0.50 + 1.40 + 0.36 + 0.10 + 0.072 = 3.902
        assertEquals(1.00, result.fuelCostEstimate, 0.001)
        assertEquals(2.902, result.nonFuelCostEstimate, 0.01)
        assertEquals(3.902, result.totalOperatingCost, 0.01)
        assertEquals(10.0 - 3.902, result.netPayAmount, 0.01)
    }

    @Test
    fun `operating cost - depreciation toggle off excludes depreciation`() {
        val economy = UserEconomy(
            vehicleClass = VehicleClass.SEDAN,
            includeDepreciation = false,
            purchasePrice = 28_000.0, totalLifetimeMi = 200_000.0,
        )
        val cfg = EvaluationConfig(rules = listOf(metricRule(MetricType.PAYOUT, 10.0f)), userEconomy = economy)
        val result = evaluator.evaluate(offer(pay = 10.0, dist = 10.0), cfg)
        // Depreciation would be $0.14/mi × 10 = $1.40; with toggle off it's $0.
        assertEquals(0.0, result.nonFuelCostEstimate, 0.001)
    }

    @Test
    fun `operating cost - amortizes insurance delta correctly`() {
        // $30/mo × 12 / 10k dash mi = $0.036/mi; 5-mi offer → $0.18 insurance cost
        val economy = UserEconomy(
            vehicleClass = VehicleClass.SEDAN,
            insuranceDeltaPerMonth = 30.0,
            expectedAnnualDashMiles = 10_000.0,
        )
        val cfg = EvaluationConfig(rules = listOf(metricRule(MetricType.PAYOUT, 10.0f)), userEconomy = economy)
        val result = evaluator.evaluate(offer(pay = 10.0, dist = 5.0), cfg)
        assertEquals(0.18, result.nonFuelCostEstimate, 0.001)
    }

    @Test
    fun `operating cost - amortizes registration delta correctly`() {
        // $100/yr / 10k dash mi = $0.01/mi; 5-mi offer → $0.05 registration cost
        val economy = UserEconomy(
            vehicleClass = VehicleClass.SEDAN,
            registrationDeltaPerYear = 100.0,
            expectedAnnualDashMiles = 10_000.0,
        )
        val cfg = EvaluationConfig(rules = listOf(metricRule(MetricType.PAYOUT, 10.0f)), userEconomy = economy)
        val result = evaluator.evaluate(offer(pay = 10.0, dist = 5.0), cfg)
        assertEquals(0.05, result.nonFuelCostEstimate, 0.001)
    }

    @Test
    fun `operating cost - amortizes phone correctly`() {
        // $80/mo plan / 4 lines = $20/mo your share × 30% dashing = $6/mo
        // × 12 = $72/yr / 10k dash mi = $0.0072/mi; 10-mi offer → $0.072
        val economy = UserEconomy(
            vehicleClass = VehicleClass.SEDAN,
            phonePlanTotal = 80.0,
            phonePlanLines = 4,
            phoneDashPercent = 30.0,
            expectedAnnualDashMiles = 10_000.0,
        )
        val cfg = EvaluationConfig(rules = listOf(metricRule(MetricType.PAYOUT, 10.0f)), userEconomy = economy)
        val result = evaluator.evaluate(offer(pay = 10.0, dist = 10.0), cfg)
        assertEquals(0.072, result.nonFuelCostEstimate, 0.001)
    }

    @Test
    fun `operating cost - phone not affected by vehicle class change`() {
        // Same phone setup; only the vehicleClass differs. Phone cost should be identical.
        val sedanEco = UserEconomy(vehicleClass = VehicleClass.SEDAN, phonePlanTotal = 100.0, phoneDashPercent = 50.0)
        val truckEco = UserEconomy(vehicleClass = VehicleClass.TRUCK, phonePlanTotal = 100.0, phoneDashPercent = 50.0)
        assertEquals(sedanEco.phoneCostPerMile, truckEco.phoneCostPerMile, 0.0001)
    }

    @Test
    fun `operating cost - zero expected annual dash miles does not divide by zero`() {
        // expectedAnnualDashMiles = 0 should be coerced; result must be finite.
        val economy = UserEconomy(
            vehicleClass = VehicleClass.SEDAN,
            insuranceDeltaPerMonth = 30.0,
            registrationDeltaPerYear = 100.0,
            phonePlanTotal = 80.0,
            expectedAnnualDashMiles = 0.0,
        )
        val cfg = EvaluationConfig(rules = listOf(metricRule(MetricType.PAYOUT, 10.0f)), userEconomy = economy)
        val result = evaluator.evaluate(offer(pay = 10.0, dist = 5.0), cfg)
        // Should not be NaN/Infinity; finite even if large
        assertTrue(result.nonFuelCostEstimate.isFinite())
    }

    @Test
    fun `operating cost - e-bike preset yields zero operating cost`() {
        // E_BIKE has all preset cost constants at zero/sentinel. UserEconomy default
        // construction with E_BIKE class still has zero domain defaults for the
        // paired fields, so total operating cost should be zero.
        val economy = UserEconomy(vehicleClass = VehicleClass.E_BIKE)
        assertEquals(0.0, economy.fuelCostPerMile, 0.0001)
        assertEquals(0.0, economy.operatingCostPerMile, 0.0001)
    }

    @Test
    fun `operating cost - EV has non-fuel costs but zero fuel cost`() {
        // EV: no fuel, but real tires/brakes/depreciation.
        val economy = UserEconomy(
            vehicleClass = VehicleClass.EV,
            tireSetCost = 1_000.0, tireLifetimeMi = 35_000.0,
            includeDepreciation = true,
            purchasePrice = 40_000.0, totalLifetimeMi = 200_000.0,
        )
        assertEquals(0.0, economy.fuelCostPerMile, 0.0001)
        assertTrue("EV non-fuel cost should be positive", economy.nonFuelCostPerMile > 0.0)
    }

    @Test
    fun `operating cost - isUsingDefaults true when userSetFields empty`() {
        val economy = UserEconomy(vehicleClass = VehicleClass.SEDAN)
        assertTrue(economy.isUsingDefaults)
    }

    @Test
    fun `operating cost - isUsingDefaults false when every field is user-set`() {
        val allFields = EconomyField.entries.toSet()
        val economy = UserEconomy(vehicleClass = VehicleClass.SEDAN, userSetFields = allFields)
        assertEquals(false, economy.isUsingDefaults)
    }

    @Test
    fun `operating cost - breakdown sums to total`() {
        // Invariant: fuelCostEstimate + nonFuelCostEstimate == totalOperatingCost
        val economy = UserEconomy(
            vehicleClass = VehicleClass.SEDAN,
            vehicleMpg = 30.0, gasPricePerGallon = 3.50,
            tireSetCost = 800.0, tireLifetimeMi = 40_000.0,
            oilCost = 60.0, oilIntervalMi = 5_000.0,
            includeDepreciation = true,
            purchasePrice = 28_000.0, totalLifetimeMi = 200_000.0,
        )
        val cfg = EvaluationConfig(rules = listOf(metricRule(MetricType.PAYOUT, 10.0f)), userEconomy = economy)
        val result = evaluator.evaluate(offer(pay = 10.0, dist = 7.5), cfg)
        assertEquals(
            result.totalOperatingCost,
            result.fuelCostEstimate + result.nonFuelCostEstimate,
            0.0001,
        )
    }

    @Test
    fun `operating cost - evaluator carries isUsingDefaults through to result`() {
        val partial = UserEconomy(
            vehicleClass = VehicleClass.SEDAN,
            userSetFields = setOf(EconomyField.VEHICLE_CLASS, EconomyField.GAS_PRICE),
        )
        val cfg = EvaluationConfig(rules = listOf(metricRule(MetricType.PAYOUT, 10.0f)), userEconomy = partial)
        val result = evaluator.evaluate(offer(pay = 10.0, dist = 5.0), cfg)
        assertTrue(result.isUsingDefaults)
    }
}
