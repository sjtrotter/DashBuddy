package cloud.trotter.dashbuddy.domain.model.pay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #607 — DoorDash renders some merchants' per-tip sub-label as a bare store number
 * (e.g. "618") with no fuller name anywhere in-frame. That number IS DoorDash's own
 * label, not junk — so [ParsedPayItem.displayLabel] normalizes it to "Store #618" for
 * a human reader while [ParsedPayItem.type] itself stays completely untouched (it's
 * still token-matched raw by [cloud.trotter.dashbuddy.domain.state.StoreChainProjector]).
 */
class ParsedPayItemTest {

    @Test
    fun `bare 2-6 digit type displays as Store number`() {
        assertEquals("Store #618", ParsedPayItem(type = "618", amount = 10.0).displayLabel)
        assertEquals("Store #99", ParsedPayItem(type = "99", amount = 1.0).displayLabel)
        assertEquals("Store #123456", ParsedPayItem(type = "123456", amount = 1.0).displayLabel)
    }

    @Test
    fun `bare digit type does not mutate the raw type field`() {
        val item = ParsedPayItem(type = "618", amount = 10.0)
        assertEquals("618", item.type)
        assertEquals("Store #618", item.displayLabel)
    }

    @Test
    fun `full merchant name passes through verbatim`() {
        assertEquals("Chipotle", ParsedPayItem(type = "Chipotle", amount = 3.0).displayLabel)
        assertEquals("Sake Cafe", ParsedPayItem(type = "Sake Cafe", amount = 3.0).displayLabel)
    }

    @Test
    fun `app pay labels like Base Pay pass through verbatim`() {
        assertEquals("Base Pay", ParsedPayItem(type = "Base Pay", amount = 5.0).displayLabel)
        assertEquals("Peak Pay", ParsedPayItem(type = "Peak Pay", amount = 2.5).displayLabel)
    }

    @Test
    fun `single digit is too short to be a store number - passes through verbatim`() {
        assertEquals("7", ParsedPayItem(type = "7", amount = 1.0).displayLabel)
    }

    @Test
    fun `seven-plus digit run is too long to be a store number - passes through verbatim`() {
        assertEquals("1234567", ParsedPayItem(type = "1234567", amount = 1.0).displayLabel)
    }

    @Test
    fun `digits mixed with other characters are not treated as a bare store number`() {
        assertEquals("Store 618", ParsedPayItem(type = "Store 618", amount = 1.0).displayLabel)
        assertEquals("618A", ParsedPayItem(type = "618A", amount = 1.0).displayLabel)
    }

    @Test
    fun `blank type passes through verbatim`() {
        assertEquals("", ParsedPayItem(type = "", amount = 0.0).displayLabel)
    }
}
