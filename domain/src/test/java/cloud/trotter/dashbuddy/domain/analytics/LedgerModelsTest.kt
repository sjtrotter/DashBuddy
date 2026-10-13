package cloud.trotter.dashbuddy.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LedgerModelsTest {
    @Test
    fun `price per gallon is derived only for positive gallons`() {
        val fuel = FuelRecord(1, null, 100, 30.0, null, null, null)
        assertNull(fuel.pricePerGallon)
        assertNull(fuel.copy(gallons = 0.0).pricePerGallon)
        assertEquals(3.0, fuel.copy(gallons = 10.0).pricePerGallon!!, 0.0)
    }

    @Test
    fun `kept optionally subtracts real expenses`() {
        val net = SessionNet("S", 20.0, 3.0, 2.0, expenses = 3.0, fuel = 5.0)
        assertEquals(net.expenses + net.fuel, net.realExpenses, 0.0)
        assertEquals(25.0, net.kept(countExpenses = false), 0.0)
        assertEquals(17.0, net.kept(countExpenses = true), 0.0)
    }

    @Test
    fun `expense categories fall back to OTHER for missing and unknown names`() {
        assertEquals(ExpenseCategory.OTHER, ExpenseCategory.fromStorage("bogus"))
        assertEquals(ExpenseCategory.OTHER, ExpenseCategory.fromStorage(null))
        assertEquals(ExpenseCategory.FOOD, ExpenseCategory.fromStorage("FOOD"))
    }
}
