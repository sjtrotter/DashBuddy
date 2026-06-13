package cloud.trotter.dashbuddy.domain.evaluation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #469: the persisted-name aliasing that keeps a dasher's user-set markers
 *  alive across the de-dash rename. */
class EconomyFieldTest {

    @Test
    fun `current constant names resolve to themselves`() {
        EconomyField.entries.forEach { field ->
            assertEquals(field, EconomyField.fromPersistedName(field.name))
        }
    }

    @Test
    fun `legacy pre-469 names map forward to the renamed constants`() {
        // These two strings were persisted into USER_SET_ECONOMY_FIELDS by
        // builds before #469. They must still resolve, or the dasher's
        // "user-set" badge for those fields silently resets to default.
        assertEquals(EconomyField.EXPECTED_ANNUAL_MI, EconomyField.fromPersistedName("EXPECTED_ANNUAL_DASH_MI"))
        assertEquals(EconomyField.PHONE_BUSINESS_PERCENT, EconomyField.fromPersistedName("PHONE_DASH_PERCENT"))
    }

    @Test
    fun `unknown names resolve to null instead of throwing`() {
        assertNull(EconomyField.fromPersistedName("SOME_FUTURE_FIELD"))
        assertNull(EconomyField.fromPersistedName(""))
    }
}
