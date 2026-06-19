package cloud.trotter.dashbuddy.domain.model.order

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Recognition is data, not code (CLAUDE.md): the text-matching helpers
 * (`fromTypeName`/`orderTypeCount`/`allTypeNames`) were superseded by the JSON rule engine and
 * deleted, so their tests are gone too. `ParsedFieldsFactory` now resolves the order type from
 * parse output via the intrinsic [OrderType.valueOf]. What remains is the [OrderType.isShoppingOrder]
 * flag and the [OrderType.typeName] label.
 */
class OrderTypeTest {

    // -------------------------------------------------------------------------
    // isShoppingOrder flag
    // -------------------------------------------------------------------------

    @Test
    fun `isShoppingOrder - SHOP_FOR_ITEMS is a shopping order`() {
        assert(OrderType.SHOP_FOR_ITEMS.isShoppingOrder)
    }

    @Test
    fun `isShoppingOrder - PICKUP is not a shopping order`() {
        assert(!OrderType.PICKUP.isShoppingOrder)
    }

    @Test
    fun `isShoppingOrder - RESTAURANT_PICKUP is not a shopping order`() {
        assert(!OrderType.RESTAURANT_PICKUP.isShoppingOrder)
    }

    @Test
    fun `isShoppingOrder - RETAIL_PICKUP is not a shopping order`() {
        assert(!OrderType.RETAIL_PICKUP.isShoppingOrder)
    }

    // -------------------------------------------------------------------------
    // Serialized-key contract (ParsedFieldsFactory resolves via valueOf)
    // -------------------------------------------------------------------------

    @Test
    fun `valueOf round-trips the serialized name for every type`() {
        OrderType.entries.forEach { type ->
            assertEquals(type, OrderType.valueOf(type.name))
        }
    }

    @Test
    fun `every type exposes a non-blank typeName`() {
        OrderType.entries.forEach { type ->
            assert(type.typeName.isNotBlank()) { "typeName for $type should be non-blank" }
        }
    }
}
