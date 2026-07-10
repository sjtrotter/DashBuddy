package cloud.trotter.dashbuddy.domain.evaluation

import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.domain.model.order.ParsedOrder
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass
import cloud.trotter.dashbuddy.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #556 — the Shop & Deliver time model. The old flat 7-min handling estimated a 25-item grocery run
 * at ~15 min → a ~$116/hr reading and an inflated score. Handling for a shop is now its item count
 * at the effective shopping pace (items/min, default 0.8 from the 2026-06 corpus), so a real
 * grocery run reads a realistic time and rate. Non-shop pickups are unchanged.
 */
class ShopTimeModelTest {

    private val evaluator = OfferEvaluator()
    // E_BIKE → zero operating cost, so netPay == grossPay and we can assert the rate exactly.
    private val economy = UserEconomy(vehicleClass = VehicleClass.E_BIKE)
    private val config = EvaluationConfig(rules = emptyList(), userEconomy = economy)

    private fun offer(pay: Double, dist: Double, itemCount: Int, type: OrderType) = ParsedOffer(
        offerHash = "h", payAmount = pay, distanceMiles = dist, itemCount = itemCount,
        orders = listOf(ParsedOrder(0, type, "Store", itemCount, false, emptySet())),
    )

    @Test
    fun `a 25-item shop is estimated at items-over-pace plus drive, not the flat base`() {
        // The real H-E-B specimen: $30.03, 3.2 mi, 25 items. drive 3.2*2.5=8; shop 25/0.8=31.25.
        val r = evaluator.evaluate(offer(30.03, 3.2, 25, OrderType.SHOP_FOR_ITEMS), config)
        assertEquals(39.25, r.estimatedTimeMinutes, 0.01)
        // The bug read ~$116/hr (30.03 / ((8+7)/60)); now it's a realistic ~$46/hr.
        assertEquals(30.03 / (39.25 / 60.0), r.dollarsPerHour, 0.1)
        assertTrue("shop \$/hr is no longer inflated", r.dollarsPerHour < 60.0)
    }

    @Test
    fun `a non-shop pickup keeps the flat base handling (unchanged)`() {
        // PICKUP: drive 3*2.5=7.5 + base 7 = 14.5 — exactly the old model.
        val r = evaluator.evaluate(offer(7.0, 3.0, 1, OrderType.PICKUP), config)
        assertEquals(14.5, r.estimatedTimeMinutes, 0.01)
    }

    @Test
    fun `a tiny or unparsed shop is floored at the base handling, never under-counted`() {
        // 1 item / 0.8 = 1.25 min would be absurd; floor at base (7) + drive.
        val r = evaluator.evaluate(offer(8.0, 2.0, 1, OrderType.SHOP_FOR_ITEMS), config)
        assertEquals(2.0 * 2.5 + 7.0, r.estimatedTimeMinutes, 0.01)
    }

    @Test
    fun `the learned pace overrides the seed once enough shops are measured`() {
        val warm = economy.copy(learnedShopItemsPerMinute = 1.0, shopRateSampleCount = UserEconomy.MIN_SHOP_SAMPLES)
        assertEquals(1.0, warm.effectiveShopItemsPerMinute, 0.0)
        val cold = economy.copy(learnedShopItemsPerMinute = 1.0, shopRateSampleCount = 2) // below floor
        assertEquals(UserEconomy.DEFAULT_SHOP_ITEMS_PER_MIN, cold.effectiveShopItemsPerMinute, 0.0)
        assertEquals(UserEconomy.DEFAULT_SHOP_ITEMS_PER_MIN, economy.effectiveShopItemsPerMinute, 0.0)
    }

    @Test
    fun `forPlatform resolves a platform with no samples to its seed, never another platform's learned rate (#588)`() {
        // DoorDash is well past the trust gate at a fast learned pace; Instacart has no samples.
        val cfg = config.copy(
            shopRates = mapOf(
                Platform.DoorDash to LearnedShopRate(itemsPerMin = 2.0, sampleCount = UserEconomy.MIN_SHOP_SAMPLES + 3),
            ),
        )

        // DoorDash resolves to its own learned pace.
        assertEquals(2.0, cfg.forPlatform(Platform.DoorDash).userEconomy.effectiveShopItemsPerMinute, 0.0)

        // Instacart — absent from the map — resolves to the SEED, never DoorDash's 2.0/min.
        val ic = cfg.forPlatform(Platform.Instacart).userEconomy
        assertEquals(ShopRateSeeds.seedFor(Platform.Instacart), ic.effectiveShopItemsPerMinute, 0.0)
        assertEquals(0, ic.shopRateSampleCount)
    }

    @Test
    fun `forPlatform selects each platform's own learned mean independently (#588)`() {
        val cfg = config.copy(
            shopRates = mapOf(
                Platform.DoorDash to LearnedShopRate(0.8, UserEconomy.MIN_SHOP_SAMPLES),
                Platform.Instacart to LearnedShopRate(1.5, UserEconomy.MIN_SHOP_SAMPLES),
            ),
        )
        assertEquals(0.8, cfg.forPlatform(Platform.DoorDash).userEconomy.effectiveShopItemsPerMinute, 0.0)
        assertEquals(1.5, cfg.forPlatform(Platform.Instacart).userEconomy.effectiveShopItemsPerMinute, 0.0)
    }

    @Test
    fun `ShopRate fold is an order-independent running mean that ignores noise`() {
        var avg: Double? = null; var n = 0
        // two real shops: 0.8/min and 1.0/min → mean 0.9 over n=2
        ShopRate.fold(avg, n, 24, 30.0).let { avg = it.first; n = it.second } // 0.8
        ShopRate.fold(avg, n, 20, 20.0).let { avg = it.first; n = it.second } // 1.0
        assertEquals(2, n); assertEquals(0.9, avg!!, 1e-9)
        // a sub-floor 2-item / 0.5-min blip is ignored (no poisoning)
        val (avg2, n2) = ShopRate.fold(avg, n, 2, 0.5)
        assertEquals(2, n2); assertEquals(0.9, avg2!!, 1e-9)
    }
}
