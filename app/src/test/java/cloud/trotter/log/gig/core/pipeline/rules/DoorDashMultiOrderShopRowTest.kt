package cloud.trotter.log.gig.core.pipeline.rules

import cloud.trotter.log.gig.domain.evaluation.EvaluationConfig
import cloud.trotter.log.gig.domain.evaluation.OfferEvaluator
import cloud.trotter.log.gig.domain.evaluation.UserEconomy
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.model.offer.ParsedOffer
import cloud.trotter.log.gig.domain.model.order.CountUnit
import cloud.trotter.log.gig.domain.model.order.OrderType
import cloud.trotter.log.gig.domain.model.vehicle.VehicleClass
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.test.util.TestResourceLoader
import cloud.trotter.log.gig.test.util.TestRulesetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1528: DoorDash renders a two-order shop row as `Shop for 2 orders (29 items, ~35 min)`. The Compose
 * offer rules read it as a shop with its item count, so the evaluator prices shopping time instead of a
 * quick pickup. The fixture is a committed one-order Compose card with its shop row rewritten.
 */
class DoorDashMultiOrderShopRowTest {

    @Test
    fun `a two-order shop row parses as a shop with its item count`() {
        val offer = parse(card(shopRow = "Shop for 2 orders (29 items, ~35 min)", dropRow = "Multiple dropoffs (2 stops)"))
        val order = offer.orders.single()
        assertEquals(OrderType.SHOP_FOR_ITEMS, order.orderType)
        assertEquals(29, order.itemCount)
        assertFalse(order.isItemCountEstimated)
        assertEquals(CountUnit.ITEMS, order.countUnit)
        assertEquals(29, offer.itemCount)
        assertTrue(offer.isShop)
    }

    @Test
    fun `a one-order row in the orders wording parses as a shop`() {
        val offer = parse(card(shopRow = "Shop for 1 order (12 items, ~15 min)"))
        assertEquals(OrderType.SHOP_FOR_ITEMS, offer.orders.single().orderType)
        assertEquals(12, offer.orders.single().itemCount)
        assertEquals(12, offer.itemCount)
        assertTrue(offer.isShop)
    }

    @Test
    fun `the unmodified card parses as before`() {
        val offer = parse(card())
        assertEquals(OrderType.SHOP_FOR_ITEMS, offer.orders.single().orderType)
        assertEquals(6, offer.orders.single().itemCount)
        assertEquals(6, offer.itemCount)
        assertTrue(offer.isShop)
    }

    @Test
    fun `the two-order shop estimate includes shopping time`() {
        val config = EvaluationConfig(userEconomy = UserEconomy(vehicleClass = VehicleClass.E_BIKE))
        val evaluator = OfferEvaluator()
        val shop = parse(card(shopRow = "Shop for 2 orders (29 items, ~35 min)", dropRow = "Multiple dropoffs (2 stops)"))
        val pickup = parse(card(shopRow = "", dropRow = "Multiple dropoffs (2 stops)"))
        assertFalse(pickup.isShop)
        val shopMinutes = evaluator.evaluate(shop, config).estimatedTimeMinutes
        val pickupMinutes = evaluator.evaluate(pickup, config).estimatedTimeMinutes
        assertTrue("shop $shopMinutes min must exceed pickup $pickupMinutes min", shopMinutes > pickupMinutes)
    }

    private fun card(shopRow: String = SHOP_ROW, dropRow: String = DROP_ROW): UiNode {
        val file = File("src/test/resources/$FIXTURE")
        require(file.isFile) { "Missing snapshot: ${file.absolutePath}" }
        val original = TestResourceLoader.loadNode(file)
        require(original.findNode { it.text == SHOP_ROW } != null) { "fixture lost its shop row" }
        require(original.findNode { it.text == DROP_ROW } != null) { "fixture lost its drop row" }
        return original.withRewrittenText(SHOP_ROW, shopRow).withRewrittenText(DROP_ROW, dropRow).restoreParents()
    }

    private fun parse(tree: UiNode): ParsedOffer {
        val match = requireNotNull(TestRulesetFactory.screenRuleset.matchFirst(tree)) { "card fell to UNKNOWN" }
        assertEquals("doordash.screen.offer_popup", match.ruleId)
        return (ParsedFieldsFactory.create(match.shape, match.fields) as ParsedFields.OfferFields).parsedOffer
    }

    private fun UiNode.withRewrittenText(from: String, to: String): UiNode = copy(
        text = if (text == from) to else text,
        children = children.map { it.withRewrittenText(from, to) },
    )

    private companion object {
        const val FIXTURE =
            "snapshots/offer_popup/2026-09-19_11-42-11-094__doordash__accessibility.window__offer_popup__b7abab.json"
        const val SHOP_ROW = "Shop for 6 items (~7 min)"
        const val DROP_ROW = "Customer dropoff"
    }
}
