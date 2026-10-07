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
    @Test fun `display labels preserve the raw type`() {
        listOf(
            Triple("618", 10.0, "Store #618"),
            Triple("99", 1.0, "Store #99"),
            Triple("123456", 1.0, "Store #123456"),
            Triple("618", 10.0, "Store #618"),
            Triple("Chipotle", 3.0, "Chipotle"),
            Triple("Sake Cafe", 3.0, "Sake Cafe"),
            Triple("Base Pay", 5.0, "Base Pay"),
            Triple("Peak Pay", 2.5, "Peak Pay"),
            Triple("7", 1.0, "7"),
            Triple("1234567", 1.0, "1234567"),
            Triple("Store 618", 1.0, "Store 618"),
            Triple("618A", 1.0, "618A"),
            Triple("", 0.0, ""),
        ).forEachIndexed { index, (type, amount, label) ->
            val name = "row $index type=<$type> amount=$amount"
            val item = ParsedPayItem(type = type, amount = amount)
            assertEquals(name, label, item.displayLabel)
            assertEquals("$name raw type", type, item.type)
        }
    }
}
