package cloud.trotter.dashbuddy.domain.model.pay

import org.junit.Assert.assertEquals
import org.junit.Test

class ParsedPayTest {
    private fun item(type: String, amount: Double) = ParsedPayItem(type, amount)

    @Test fun `pay arithmetic shapes`() {
        data class Case(
            val name: String, val app: List<ParsedPayItem>, val tips: List<ParsedPayItem>,
            val base: Double, val tip: Double, val total: Double,
        )
        listOf(
            Case("app components", listOf(item("Base Pay", 5.00), item("Peak Pay", 2.50)), emptyList(), 7.50, 0.0, 7.50),
            Case("customer tips", emptyList(), listOf(item("Taco Bell", 3.00), item("Pizza Hut", 1.50)), 0.0, 4.50, 4.50),
            Case("base and tips", listOf(item("Base Pay", 4.25)), listOf(item("Taco Bell", 1.75)), 4.25, 1.75, 6.00),
            Case("empty pay", emptyList(), emptyList(), 0.0, 0.0, 0.0),
            Case("empty components", emptyList(), listOf(item("Store", 5.0)), 0.0, 5.0, 5.0),
            Case("empty tips", listOf(item("Base Pay", 5.0)), emptyList(), 5.0, 0.0, 5.0),
        ).forEach { (name, app, tips, base, tip, total) ->
            val pay = ParsedPay(appPayComponents = app, customerTips = tips)
            assertEquals("$name base", base, pay.totalBasePay, 0.0001)
            assertEquals("$name tips", tip, pay.totalTip, 0.0001)
            assertEquals("$name total", total, pay.total, 0.0001)
        }
    }
}
