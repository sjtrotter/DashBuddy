package cloud.trotter.dashbuddy.domain.evaluation

import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.domain.model.order.ParsedOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OfferEvaluatorTest {

    private lateinit var evaluator: OfferEvaluator

    // Default config: single PAYOUT rule, target $7.00
    private val defaultConfig = EvaluationConfig(
        rules = listOf(
            ScoringRule.MetricRule(
                id = "payout",
                metricType = MetricType.PAYOUT,
                targetValue = 7.0f
            )
        )
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

    // -------------------------------------------------------------------------
    // Protect Stats Mode
    // -------------------------------------------------------------------------

    @Test
    fun `protectStatsMode - always returns ACCEPT with score 100`() {
        val config = EvaluationConfig(protectStatsMode = true, rules = emptyList())
        val result = evaluator.evaluate(offer(pay = 1.0), config)
        assertEquals(OfferAction.ACCEPT, result.action)
        assertEquals(100.0, result.score, 0.0001)
        assertEquals("Protected!", result.qualityLevel)
    }

    @Test
    fun `protectStatsMode - overrides even a very bad offer`() {
        val config = EvaluationConfig(protectStatsMode = true, rules = emptyList())
        val result = evaluator.evaluate(offer(pay = 0.01, dist = 100.0), config)
        assertEquals(OfferAction.ACCEPT, result.action)
    }

    // -------------------------------------------------------------------------
    // Empty / Disabled Rules
    // -------------------------------------------------------------------------

    @Test
    fun `no active rules - returns NOTHING with score 0`() {
        val config = EvaluationConfig(rules = emptyList())
        val result = evaluator.evaluate(offer(), config)
        assertEquals(OfferAction.NOTHING, result.action)
        assertEquals(0.0, result.score, 0.0001)
    }

    @Test
    fun `all rules disabled - returns NOTHING with score 0`() {
        val config = EvaluationConfig(
            rules = listOf(metricRule(MetricType.PAYOUT, 7.0f, enabled = false))
        )
        val result = evaluator.evaluate(offer(), config)
        assertEquals(OfferAction.NOTHING, result.action)
        assertEquals(0.0, result.score, 0.0001)
    }

    // -------------------------------------------------------------------------
    // PAYOUT metric
    // -------------------------------------------------------------------------

    @Test
    fun `payout at target - scores full weight, returns ACCEPT`() {
        val config = EvaluationConfig(rules = listOf(metricRule(MetricType.PAYOUT, 7.0f)))
        val result = evaluator.evaluate(offer(pay = 7.0), config)
        assertEquals(OfferAction.ACCEPT, result.action)
        assertEquals(100.0, result.score, 0.0001)
    }

    @Test
    fun `payout below target - partial score`() {
        val config = EvaluationConfig(rules = listOf(metricRule(MetricType.PAYOUT, 10.0f)))
        val result = evaluator.evaluate(offer(pay = 5.0), config)
        // score = (5/10) * 100 = 50.0 → NOTHING
        assertEquals(OfferAction.NOTHING, result.action)
        assertEquals(50.0, result.score, 0.0001)
    }

    @Test
    fun `payout zero - scores 0, returns DECLINE`() {
        val config = EvaluationConfig(rules = listOf(metricRule(MetricType.PAYOUT, 7.0f)))
        val result = evaluator.evaluate(offer(pay = 0.0), config)
        assertEquals(OfferAction.DECLINE, result.action)
        assertEquals(0.0, result.score, 0.0001)
    }

    @Test
    fun `payout null defaults to 0`() {
        val config = EvaluationConfig(rules = listOf(metricRule(MetricType.PAYOUT, 7.0f)))
        val result = evaluator.evaluate(offer(pay = null), config)
        assertEquals(OfferAction.DECLINE, result.action)
    }

    @Test
    fun `payout above target - clamps to 100`() {
        val config = EvaluationConfig(rules = listOf(metricRule(MetricType.PAYOUT, 7.0f)))
        val result = evaluator.evaluate(offer(pay = 100.0), config)
        assertEquals(100.0, result.score, 0.0001)
        assertEquals(OfferAction.ACCEPT, result.action)
    }

    // -------------------------------------------------------------------------
    // DOLLAR_PER_MILE metric
    // -------------------------------------------------------------------------

    @Test
    fun `dollar per mile at target - full score`() {
        val config = EvaluationConfig(rules = listOf(metricRule(MetricType.DOLLAR_PER_MILE, 2.0f)))
        val result = evaluator.evaluate(offer(pay = 6.0, dist = 3.0), config) // $2/mi
        assertEquals(100.0, result.score, 0.0001)
        assertEquals(OfferAction.ACCEPT, result.action)
    }

    @Test
    fun `dollar per mile below target - partial score`() {
        val config = EvaluationConfig(rules = listOf(metricRule(MetricType.DOLLAR_PER_MILE, 2.0f)))
        val result = evaluator.evaluate(offer(pay = 3.0, dist = 3.0), config) // $1/mi
        assertEquals(50.0, result.score, 0.0001)
        assertEquals(OfferAction.NOTHING, result.action)
    }

    // -------------------------------------------------------------------------
    // MAX_DISTANCE metric (lower is better)
    // -------------------------------------------------------------------------

    @Test
    fun `max distance - within limit scores above 0`() {
        val config = EvaluationConfig(rules = listOf(metricRule(MetricType.MAX_DISTANCE, 10.0f)))
        // dist=5, target=10 → score = (1 - 5/10) * 100 = 50 → NOTHING
        val result = evaluator.evaluate(offer(dist = 5.0), config)
        assertTrue(result.score > 0.0)
        assertEquals(OfferAction.NOTHING, result.action)
    }

    @Test
    fun `max distance - well within limit scores ACCEPT`() {
        val config = EvaluationConfig(rules = listOf(metricRule(MetricType.MAX_DISTANCE, 10.0f)))
        // dist=2, target=10 → score = (1 - 2/10) * 100 = 80 → ACCEPT
        val result = evaluator.evaluate(offer(dist = 2.0), config)
        assertEquals(80.0, result.score, 0.0001)
        assertEquals(OfferAction.ACCEPT, result.action)
    }

    @Test
    fun `max distance - exceeds limit scores 0`() {
        val config = EvaluationConfig(rules = listOf(metricRule(MetricType.MAX_DISTANCE, 5.0f)))
        val result = evaluator.evaluate(offer(dist = 10.0), config)
        assertEquals(0.0, result.score, 0.0001)
        assertEquals(OfferAction.DECLINE, result.action)
    }

    @Test
    fun `max distance - exactly at limit scores 0`() {
        // dist == target → 1.0 - (dist/target) = 0.0
        val config = EvaluationConfig(rules = listOf(metricRule(MetricType.MAX_DISTANCE, 5.0f)))
        val result = evaluator.evaluate(offer(dist = 5.0), config)
        assertEquals(0.0, result.score, 0.0001)
    }

    // -------------------------------------------------------------------------
    // ITEM_COUNT metric (lower is better)
    // -------------------------------------------------------------------------

    @Test
    fun `item count - within limit scores above 0`() {
        val config = EvaluationConfig(rules = listOf(metricRule(MetricType.ITEM_COUNT, 10.0f)))
        val result = evaluator.evaluate(offer(itemCount = 3), config)
        assertTrue(result.score > 0.0)
    }

    @Test
    fun `item count - exceeds limit scores 0, returns DECLINE`() {
        val config = EvaluationConfig(rules = listOf(metricRule(MetricType.ITEM_COUNT, 5.0f)))
        val result = evaluator.evaluate(offer(itemCount = 10), config)
        assertEquals(0.0, result.score, 0.0001)
        assertEquals(OfferAction.DECLINE, result.action)
    }

    // -------------------------------------------------------------------------
    // ACTIVE_HOURLY metric
    // -------------------------------------------------------------------------

    @Test
    fun `active hourly at target - full score`() {
        // 3 miles * 2.5 min/mi + 7 = 14.5 min = 14.5/60 hrs
        // pay / hrs = target → pay = target * hrs
        val estTimeHours = ((3.0 * 2.5) + 7.0) / 60.0
        val targetHourly = 20.0
        val pay = targetHourly * estTimeHours
        val config = EvaluationConfig(rules = listOf(metricRule(MetricType.ACTIVE_HOURLY, targetHourly.toFloat())))
        val result = evaluator.evaluate(offer(pay = pay, dist = 3.0), config)
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
        val config = EvaluationConfig(
            rules = listOf(
                metricRule(MetricType.PAYOUT, 10.0f),
                metricRule(MetricType.MAX_DISTANCE, 5.0f),
            )
        )
        val result = evaluator.evaluate(offer(pay = 10.0, dist = 10.0), config)
        assertEquals(66.67, result.score, 0.5)
        assertEquals(OfferAction.NOTHING, result.action)
    }

    @Test
    fun `multi-rule - all rules pass returns ACCEPT`() {
        val config = EvaluationConfig(
            rules = listOf(
                metricRule(MetricType.PAYOUT, 7.0f),
                metricRule(MetricType.DOLLAR_PER_MILE, 1.5f),
            )
        )
        // $10 / 3mi = $3.33/mi — well above both targets
        val result = evaluator.evaluate(offer(pay = 10.0, dist = 3.0), config)
        assertEquals(OfferAction.ACCEPT, result.action)
        assertTrue(result.score >= 70.0)
    }

    // -------------------------------------------------------------------------
    // Output fields
    // -------------------------------------------------------------------------

    @Test
    fun `evaluation output - computed fields are correct`() {
        val config = EvaluationConfig(rules = listOf(metricRule(MetricType.PAYOUT, 7.0f)))
        val result = evaluator.evaluate(offer(pay = 7.0, dist = 3.0, storeName = "Taco Bell"), config)
        assertEquals(7.0, result.payAmount, 0.0001)
        assertEquals(3.0, result.distanceMiles, 0.0001)
        assertEquals(7.0 / 3.0, result.dollarsPerMile, 0.01)
        assertEquals("Taco Bell", result.merchantName)
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
        // Construct a scenario where we get exactly 70
        // With 1 PAYOUT rule target=$10, pay=$7 → score = (7/10)*100 = 70.0
        val config = EvaluationConfig(rules = listOf(metricRule(MetricType.PAYOUT, 10.0f)))
        val result = evaluator.evaluate(offer(pay = 7.0), config)
        assertEquals(70.0, result.score, 0.0001)
        assertEquals(OfferAction.ACCEPT, result.action)
    }

    @Test
    fun `action threshold - score exactly 30 returns DECLINE`() {
        // With 1 PAYOUT rule target=$10, pay=$3 → score = (3/10)*100 = 30.0
        val config = EvaluationConfig(rules = listOf(metricRule(MetricType.PAYOUT, 10.0f)))
        val result = evaluator.evaluate(offer(pay = 3.0), config)
        assertEquals(30.0, result.score, 0.0001)
        assertEquals(OfferAction.DECLINE, result.action)
    }

    @Test
    fun `action threshold - score between 30 and 70 returns NOTHING`() {
        // pay=$5, target=$10 → score = 50
        val config = EvaluationConfig(rules = listOf(metricRule(MetricType.PAYOUT, 10.0f)))
        val result = evaluator.evaluate(offer(pay = 5.0), config)
        assertEquals(50.0, result.score, 0.0001)
        assertEquals(OfferAction.NOTHING, result.action)
    }

    // -------------------------------------------------------------------------
    // Target value edge cases
    // -------------------------------------------------------------------------

    @Test
    fun `target value zero - returns full score to prevent divide-by-zero`() {
        val config = EvaluationConfig(
            rules = listOf(ScoringRule.MetricRule(id = "zero", metricType = MetricType.PAYOUT, targetValue = 0.0f))
        )
        val result = evaluator.evaluate(offer(pay = 5.0), config)
        assertEquals(100.0, result.score, 0.0001)
    }

    @Test
    fun `distance null defaults to 1 - prevents divide-by-zero in dpm`() {
        val config = EvaluationConfig(rules = listOf(metricRule(MetricType.DOLLAR_PER_MILE, 2.0f)))
        val result = evaluator.evaluate(offer(pay = 2.0, dist = null), config)
        // dist defaults to 1.0 → dpm = 2.0/1.0 = 2.0 → score 100
        assertEquals(100.0, result.score, 0.0001)
    }
}
