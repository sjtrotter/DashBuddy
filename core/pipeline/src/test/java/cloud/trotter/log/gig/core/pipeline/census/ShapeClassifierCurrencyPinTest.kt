package cloud.trotter.log.gig.core.pipeline.census

import cloud.trotter.census.contract.ShapeClassifier
import cloud.trotter.census.contract.ShapeTemplate
import cloud.trotter.log.gig.core.pipeline.rules.CurrencyShape
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ADR-0011 §1 / §13 (#1619): the census money shape is [CurrencyShape], not a second currency definition. The
 * contract cannot depend on `:core:pipeline`, so its figure is a copy PINNED here byte for byte (the
 * `CurrencyShapePinTest` precedent), and the two are checked to agree on a sweep of figures.
 */
class ShapeClassifierCurrencyPinTest {

    @Test
    fun `the census money figure is byte-identical to CurrencyShape's`() {
        assertEquals(CurrencyShape.FIGURE_CORE, ShapeClassifier.MONEY_FIGURE_CORE)
    }

    @Test
    fun `the money template and the rule-side currency pattern accept exactly the same values`() {
        val rule = Regex(CurrencyShape.RULE_PATTERN)
        val integers = listOf("0", "00", "01", "7", "16", "999", "9999", "99999", "1,234", "12,345", "1,2", ",123", "1234,567", "1,2,3")
        val fractions = listOf("", ".", ".0", ".00", ".50", ".505", ".5")
        val values = integers.flatMap { i -> fractions.map { f -> "\$$i$f" } } + listOf("\$", "\$.00", "\$,.00")
        values.forEach { value ->
            assertEquals(value, rule.matches(value), ShapeClassifier.classify(value) == ShapeTemplate.MONEY)
        }
    }
}
