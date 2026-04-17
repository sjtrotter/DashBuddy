package cloud.trotter.dashbuddy.domain.model.pay

import org.junit.Assert.assertEquals
import org.junit.Test

class ParsedPayTest {

    private fun item(type: String, amount: Double) = ParsedPayItem(type, amount)

    @Test
    fun `totalBasePay - sums all app pay components`() {
        val pay = ParsedPay(
            appPayComponents = listOf(item("Base Pay", 5.00), item("Peak Pay", 2.50)),
            customerTips = emptyList()
        )
        assertEquals(7.50, pay.totalBasePay, 0.0001)
    }

    @Test
    fun `totalTip - sums all customer tips`() {
        val pay = ParsedPay(
            appPayComponents = emptyList(),
            customerTips = listOf(item("Taco Bell", 3.00), item("Pizza Hut", 1.50))
        )
        assertEquals(4.50, pay.totalTip, 0.0001)
    }

    @Test
    fun `total - sums base pay and tips`() {
        val pay = ParsedPay(
            appPayComponents = listOf(item("Base Pay", 5.00)),
            customerTips = listOf(item("Taco Bell", 2.00))
        )
        assertEquals(7.00, pay.total, 0.0001)
    }

    @Test
    fun `total - empty pay is zero`() {
        val pay = ParsedPay(appPayComponents = emptyList(), customerTips = emptyList())
        assertEquals(0.0, pay.total, 0.0001)
    }

    @Test
    fun `totalBasePay - empty components is zero`() {
        val pay = ParsedPay(appPayComponents = emptyList(), customerTips = listOf(item("Store", 5.0)))
        assertEquals(0.0, pay.totalBasePay, 0.0001)
    }

    @Test
    fun `totalTip - empty tips is zero`() {
        val pay = ParsedPay(appPayComponents = listOf(item("Base Pay", 5.0)), customerTips = emptyList())
        assertEquals(0.0, pay.totalTip, 0.0001)
    }

    @Test
    fun `total - single component single tip`() {
        val pay = ParsedPay(
            appPayComponents = listOf(item("Base Pay", 4.25)),
            customerTips = listOf(item("Customer", 1.75))
        )
        assertEquals(6.00, pay.total, 0.0001)
    }
}
