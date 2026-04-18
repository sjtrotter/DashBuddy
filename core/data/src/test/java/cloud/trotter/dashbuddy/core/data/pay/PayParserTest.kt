package cloud.trotter.dashbuddy.core.data.pay

import cloud.trotter.dashbuddy.domain.model.accessibility.BoundingBox
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PayParserTest {

    private lateinit var parser: PayParser

    @Before
    fun setUp() {
        parser = PayParser()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun node(
        text: String? = null,
        id: String? = null,
        children: List<UiNode> = emptyList()
    ) = UiNode(
        text = text,
        viewIdResourceName = id?.let { "com.doordash.driverapp:id/$it" },
        boundsInScreen = BoundingBox(0, 0, 0, 0),
        children = children.toMutableList()
    )

    /** Builds a row container with a title child and a value child. */
    private fun payRow(type: String, amount: String) = node(
        children = listOf(
            node(text = type, id = "pay_line_item_title"),
            node(text = amount, id = "pay_line_item_value")
        )
    )

    /** Wraps rows under a section header node. */
    private fun section(headerText: String, vararg rows: UiNode) = node(
        children = listOf(node(text = headerText)) + rows.toList()
    )

    // -------------------------------------------------------------------------
    // DoorDash pay section
    // -------------------------------------------------------------------------

    @Test
    fun `parses DoorDash pay items into appPayComponents`() {
        val root = section(
            "DoorDash pay",
            payRow("Base pay", "$3.50"),
            payRow("Peak pay", "$1.00")
        )

        val result = parser.parsePayFromTree(root)

        assertEquals(2, result.appPayComponents.size)
        assertEquals("Base pay", result.appPayComponents[0].type)
        assertEquals(3.50, result.appPayComponents[0].amount, 0.001)
        assertEquals("Peak pay", result.appPayComponents[1].type)
        assertEquals(1.00, result.appPayComponents[1].amount, 0.001)
        assertTrue(result.customerTips.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Customer tips section
    // -------------------------------------------------------------------------

    @Test
    fun `parses Customer tips items into customerTips`() {
        val root = section(
            "Customer tips",
            payRow("Tip", "$4.00")
        )

        val result = parser.parsePayFromTree(root)

        assertTrue(result.appPayComponents.isEmpty())
        assertEquals(1, result.customerTips.size)
        assertEquals("Tip", result.customerTips[0].type)
        assertEquals(4.00, result.customerTips[0].amount, 0.001)
    }

    // -------------------------------------------------------------------------
    // Both sections
    // -------------------------------------------------------------------------

    @Test
    fun `separates appPayComponents and customerTips correctly when both sections present`() {
        val root = node(
            children = listOf(
                section("DoorDash pay", payRow("Base pay", "$2.50")),
                section("Customer tips", payRow("Tip", "$5.00"))
            )
        )

        val result = parser.parsePayFromTree(root)

        assertEquals(1, result.appPayComponents.size)
        assertEquals(2.50, result.appPayComponents[0].amount, 0.001)
        assertEquals(1, result.customerTips.size)
        assertEquals(5.00, result.customerTips[0].amount, 0.001)
    }

    // -------------------------------------------------------------------------
    // Dollar sign stripping
    // -------------------------------------------------------------------------

    @Test
    fun `strips dollar sign from amount string`() {
        val root = section("DoorDash pay", payRow("Base pay", "$7.25"))
        val result = parser.parsePayFromTree(root)
        assertEquals(7.25, result.appPayComponents[0].amount, 0.001)
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Test
    fun `returns empty lists for tree with no pay rows`() {
        val root = node(text = "Nothing here")
        val result = parser.parsePayFromTree(root)
        assertTrue(result.appPayComponents.isEmpty())
        assertTrue(result.customerTips.isEmpty())
    }

    @Test
    fun `invalid amount string defaults to zero`() {
        val root = section("DoorDash pay", payRow("Base pay", "N/A"))
        val result = parser.parsePayFromTree(root)
        assertEquals(0.0, result.appPayComponents[0].amount, 0.001)
    }

    @Test
    fun `partial row with only title node does not crash`() {
        val partialRow = node(
            children = listOf(
                node(text = "Base pay", id = "pay_line_item_title")
                // no value node
            )
        )
        val root = section("DoorDash pay", partialRow)
        val result = parser.parsePayFromTree(root)
        assertTrue(result.appPayComponents.isEmpty())
    }

    @Test
    fun `section header resets mode from tips back to appPay`() {
        val root = node(
            children = listOf(
                section("Customer tips", payRow("Tip", "$3.00")),
                section("DoorDash pay", payRow("Base pay", "$2.00"))
            )
        )

        val result = parser.parsePayFromTree(root)

        assertEquals(1, result.appPayComponents.size)
        assertEquals(2.00, result.appPayComponents[0].amount, 0.001)
        assertEquals(1, result.customerTips.size)
        assertEquals(3.00, result.customerTips[0].amount, 0.001)
    }
}
