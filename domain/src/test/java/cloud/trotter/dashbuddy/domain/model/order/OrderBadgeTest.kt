package cloud.trotter.dashbuddy.domain.model.order

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Recognition is data, not code (CLAUDE.md): the order-block text-matching machinery
 * (`findAllBadgesInOrderBlock` + the `badgeText` column) was superseded by the JSON rule engine
 * and deleted, so its tests are gone too. `ParsedFieldsFactory` now mints these constants from
 * parsed booleans; what remains is the enum's role as a stable set of serialized keys.
 */
class OrderBadgeTest {

    @Test
    fun `valueOf round-trips the serialized name for every badge`() {
        // DataTypeConverters persists/restores badges by enum name — guard that contract.
        OrderBadge.entries.forEach { badge ->
            assertEquals(badge, OrderBadge.valueOf(badge.name))
        }
    }
}
