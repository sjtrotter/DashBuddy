package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [ParsedFieldsFactory] order-type resolution (#762 D9).
 *
 * The order type is rule-source vocabulary resolved via [OrderType.valueOf]. Three cases matter:
 * a KNOWN string resolves to its constant; an ABSENT field takes the neutral [OrderType.PICKUP]
 * default (a bare delivery); a PRESENT-but-unrecognized string degrades to [OrderType.UNKNOWN]
 * (a logged gap between the ruleset and the enum) without crashing.
 */
class ParsedFieldsFactoryTest {

    private fun offerWithOrderType(orderType: String?): OrderType {
        val order = buildMap<String, Any?> {
            if (orderType != null) put("orderType", orderType)
            put("storeName", "Test Store")
        }
        val fields = mapOf<String, Any?>("orders" to listOf(order))
        val result = ParsedFieldsFactory.create("offer", fields)
        val offerFields = result as ParsedFields.OfferFields
        return offerFields.parsedOffer.orders.single().orderType
    }

    @Test
    fun `known PICKUP resolves to PICKUP`() {
        assertEquals(OrderType.PICKUP, offerWithOrderType("PICKUP"))
    }

    @Test
    fun `known SHOP_FOR_ITEMS resolves to SHOP_FOR_ITEMS`() {
        assertEquals(OrderType.SHOP_FOR_ITEMS, offerWithOrderType("SHOP_FOR_ITEMS"))
    }

    @Test
    fun `absent orderType takes the neutral PICKUP default`() {
        assertEquals(OrderType.PICKUP, offerWithOrderType(null))
    }

    @Test
    fun `present-but-unrecognized orderType degrades to UNKNOWN without crashing`() {
        // A retired constant name is exactly the historical gap this guards against.
        assertEquals(OrderType.UNKNOWN, offerWithOrderType("RESTAURANT_PICKUP"))
        assertEquals(OrderType.UNKNOWN, offerWithOrderType("totally-bogus"))
    }
}
