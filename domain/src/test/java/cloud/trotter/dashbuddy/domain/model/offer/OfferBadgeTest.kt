package cloud.trotter.dashbuddy.domain.model.offer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recognition is data, not code (CLAUDE.md): the screen text-matching machinery
 * (`findAllBadgesInScreen` + the `exactMatchText`/`containsText`/`regexPattern` columns) was
 * superseded by the JSON rule engine and deleted, so its tests are gone too. What remains is the
 * enum's role as a stable set of serialized/UI keys with a human-readable [OfferBadge.displayName].
 */
class OfferBadgeTest {

    @Test
    fun `every badge exposes a non-blank displayName`() {
        OfferBadge.entries.forEach { badge ->
            assertTrue(
                "displayName for $badge should be non-blank",
                badge.displayName.isNotBlank()
            )
        }
    }

    @Test
    fun `valueOf round-trips the serialized name for every badge`() {
        // DataTypeConverters persists/restores badges by enum name — guard that contract.
        OfferBadge.entries.forEach { badge ->
            assertEquals(badge, OfferBadge.valueOf(badge.name))
        }
    }
}
