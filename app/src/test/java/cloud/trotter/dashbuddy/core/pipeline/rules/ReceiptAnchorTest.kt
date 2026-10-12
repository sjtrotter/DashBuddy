package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #935 — a receipt must not complete a task while its live drop-off sheet is still open. */
class ReceiptAnchorTest {

    private val rules = TestRulesetFactory.screenRuleset
    private val receiptRule = "doordash.screen.delivery_summary_collapsed"

    @Test
    fun `every committed collapsed receipt still matches`() {
        val receipts = TestResourceLoader.loadSnapshots("snapshots/delivery_summary_collapsed")
        assertTrue("collapsed receipt corpus must not be empty", receipts.isNotEmpty())
        assertTrue("keep the 2026-01-28 id-bearing receipts", receipts.any { (name, node, _) ->
            name.startsWith("20260128") && node.findDescendantById("final_value") != null
        })
        assertTrue("keep the id-less receipts", receipts.any { (_, node, _) ->
            node.findDescendantById("final_value") == null
        })
        receipts.forEach { (name, node, _) ->
            assertEquals(name, receiptRule, rules.matchFirst(node, "doordash")?.ruleId)
        }
    }

    @Test
    fun `live dropoff sheet with this offer is not a receipt`() {
        val sheet = UiNode(
            viewIdResourceName = "com.doordash.driverapp:id/drop_off_bottom_sheet_container",
            children = listOf(
                UiNode(viewIdResourceName = "user_name_label", text = "Deliver to"),
                UiNode(text = "This offer $8.25"),
                UiNode(text = "this dash"),
                UiNode(viewIdResourceName = "complete_delivery_steps_button", text = "Complete delivery"),
            ),
        ).restoreParents()
        assertNotReceipt("live sheet", sheet)
    }

    @Test
    fun `this offer alone is not a receipt`() {
        assertNotReceipt("missing dash-total section and legacy id", receipt(includeDashTotal = false))
    }

    @Test
    fun `both text sections on an id-less receipt parse post_task`() {
        val result = rules.matchFirst(receipt(), "doordash")
        assertEquals(receiptRule, result?.ruleId)
        assertEquals("post_task", result?.shape)
        assertEquals(Flow.PostTask, result?.flow)
        assertEquals(8.25, result?.fields?.get("totalPay"))
        assertEquals(40.57, result?.fields?.get("sessionEarnings"))
        assertEquals(false, result?.fields?.get("isExpanded"))
        assertEquals(null, result?.fields?.get("expandButtonId"))
    }

    @Test
    fun `legacy final_value keeps a rollback receipt recognizable`() {
        val legacy = receipt(includeDashTotal = false).copy(children = listOf(
            UiNode(text = "This offer"),
            UiNode(viewIdResourceName = "final_value", text = "$8.25"),
        )).restoreParents()
        assertEquals(receiptRule, rules.matchFirst(legacy, "doordash")?.ruleId)
    }

    @Test
    fun `each live-sheet id independently rejects even with both receipt texts`() {
        listOf("drop_off_bottom_sheet_container", "complete_delivery_steps_button").forEach { id ->
            val receipt = receipt()
            val combined = receipt.copy(children = receipt.children + UiNode(viewIdResourceName = id))
                .restoreParents()
            assertNotReceipt("receipt text with $id", combined)
        }
    }

    @Test
    fun `the two July 28 incident sheets belong to dropoff_pre_arrival`() {
        listOf(
            "2026-07-28_20-16-24-940__doordash__accessibility.window__dropoff_pre_arrival__22a4ac.json",
            "2026-07-28_20-35-50-483__doordash__accessibility.window__dropoff_pre_arrival__196177.json",
        ).forEach { name ->
            val node = TestResourceLoader.loadNode(File("src/test/resources/snapshots/dropoff_pre_arrival/$name"))
            val winner = rules.matchFirst(node, "doordash")?.ruleId
            println("#935 $name winner=$winner")
            assertEquals(name, "doordash.screen.dropoff_pre_arrival", winner)
        }
    }

    private fun assertNotReceipt(label: String, node: UiNode) {
        val winner = rules.matchFirst(node, "doordash")?.ruleId
        println("#935 $label winner=$winner")
        assertNotEquals("$label winner=$winner", receiptRule, winner)
    }

    /** Id-less money siblings and the dash-total label's parent mirror the post_task golden. */
    private fun receipt(includeDashTotal: Boolean = true): UiNode = UiNode(
        children = listOf(
            UiNode(text = "This offer"),
            UiNode(contentDescription = "Expand"),
            UiNode(text = "$8.25"),
        ) + if (includeDashTotal) listOf(
            UiNode(children = listOf(UiNode(text = "This dash so far"), UiNode(text = "$40.57"))),
        ) else emptyList(),
    ).restoreParents()
}
