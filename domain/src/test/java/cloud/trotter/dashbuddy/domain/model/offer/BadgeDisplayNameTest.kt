package cloud.trotter.dashbuddy.domain.model.offer

import cloud.trotter.dashbuddy.domain.model.order.OrderBadge
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #942: `displayName` is now the ONE badge-label owner — the bubble HUD's pill row reads it instead
 * of a second, drifted map that silently missed ≥8 branches and rendered them as a lowercased enum
 * name. That only holds if every constant actually carries a human label, so a badge added later
 * cannot reintroduce the mangle.
 */
class BadgeDisplayNameTest {

    @Test
    fun `every OfferBadge carries a human displayName`() {
        OfferBadge.entries.forEach { badge ->
            assertTrue("${badge.name} has a blank displayName", badge.displayName.isNotBlank())
            assertTrue(
                "${badge.name} renders its raw enum name — that IS the mangle #942 removed",
                badge.displayName != badge.name,
            )
        }
    }

    @Test
    fun `every OrderBadge carries a human displayName`() {
        OrderBadge.entries.forEach { badge ->
            assertTrue("${badge.name} has a blank displayName", badge.displayName.isNotBlank())
            assertTrue(
                "${badge.name} renders its raw enum name — that IS the mangle #942 removed",
                badge.displayName != badge.name,
            )
        }
    }

    /**
     * The renderer resolves a wire name by scanning both enums, so a name shared between them would
     * make the lookup order (offer first) silently decide the color.
     */
    @Test
    fun `the two badge enums share no constant name`() {
        val offerNames = OfferBadge.entries.map { it.name }.toSet()
        val collisions = OrderBadge.entries.map { it.name }.filter { it in offerNames }
        assertTrue("OfferBadge/OrderBadge name collision: $collisions", collisions.isEmpty())
    }
}
