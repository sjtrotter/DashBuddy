package cloud.trotter.log.gig.core.pipeline.recognition.matchers

import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.test.util.TestResourceLoader
import cloud.trotter.log.gig.test.util.TestRulesetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1396 — the going-to-store sheet renders two contact cards, each `user_name_label` + `user_name`: the
 * merchant's (`mx_contact_view`, label "Pickup from") and the customer's (`customer_contact_view`, label
 * "Delivery for"). A fielded mid-render frame put the customer card FIRST with an empty label, and the old
 * first-`user_name` read stored the customer's name as `storeName`. The read now takes only the
 * `user_name` whose immediately preceding sibling is the "Pickup from" label.
 *
 * Every tree here is a committed fixture rearranged in memory; "Jane D." and "Sample Pizza Co" are
 * invented values.
 */
class PickupPreArrivalStoreNameTest {

    private val rules = TestRulesetFactory.screenRuleset

    private val fixture: UiNode = TestResourceLoader.loadNode(
        File("src/test/resources/snapshots/pickup_pre_arrival/20260128_173614_959_PICKUP_DETAILS_PRE_ARRIVAL.json"),
    )

    private fun UiNode.hasId(suffix: String) = viewIdResourceName?.endsWith(suffix) == true

    /** Rewrite every node; [edit] sees a node whose children were already rewritten. */
    private fun UiNode.rewrite(edit: (UiNode) -> UiNode): UiNode = edit(copy(children = children.map { it.rewrite(edit) }))

    /** The mid-render shape: the customer card moved ahead of the merchant card, its label blank. */
    private fun customerCardFirst(tree: UiNode, customerName: String): UiNode = tree.rewrite { n ->
        val mx = n.children.indexOfFirst { it.hasId("mx_contact_view") }
        val cx = n.children.indexOfFirst { it.hasId("customer_contact_view") }
        val card = if (cx >= 0) n.children[cx].rewrite { c ->
            when {
                c.hasId("user_name_label") -> c.copy(text = "")
                c.hasId("user_name") -> c.copy(text = customerName)
                else -> c
            }
        } else null
        if (mx >= 0 && card != null) {
            val rest = n.children.filterIndexed { i, _ -> i != cx }.toMutableList()
            rest.add(rest.indexOfFirst { it.hasId("mx_contact_view") }, card)
            n.copy(children = rest)
        } else {
            n
        }
    }.restoreParents()

    private fun withMerchantName(tree: UiNode, name: String): UiNode = tree.rewrite { n ->
        if (!n.hasId("mx_contact_view")) {
            n
        } else {
            n.rewrite { c -> if (c.hasId("user_name")) c.copy(text = name) else c }
        }
    }.restoreParents()

    private fun without(tree: UiNode, drop: (UiNode) -> Boolean): UiNode =
        tree.rewrite { n -> n.copy(children = n.children.filterNot(drop)) }.restoreParents()

    @Test
    fun `the fixture renders both cards, merchant first`() {
        val ids = mutableListOf<String>()
        fun walk(n: UiNode) {
            n.viewIdResourceName?.substringAfterLast('/')?.takeIf { it.endsWith("contact_view") }?.let(ids::add)
            n.children.forEach(::walk)
        }
        walk(fixture)
        assertEquals(listOf("mx_contact_view", "customer_contact_view"), ids)
    }

    @Test
    fun `a customer card rendered first never becomes the store name (#1396)`() {
        val baseline = rules.matchFirst(fixture)
        assertEquals("doordash.screen.pickup_pre_arrival", baseline?.ruleId)
        val merchant = baseline!!.fields["storeName"]
        assertNotNull("the committed fixture parses a store name", merchant)

        val r = rules.matchFirst(customerCardFirst(fixture, "Jane D."))
        assertEquals("doordash.screen.pickup_pre_arrival", r?.ruleId)
        assertEquals("pickup_pre_arrival", r?.intent)
        assertEquals("the store name still comes from the merchant row", merchant, r!!.fields["storeName"])
        assertFalse("no parsed field carries the customer's name: ${r.fields}", r.fields.toString().contains("Jane"))
    }

    @Test
    fun `with a real merchant value the reordered frame reads the merchant, not the customer (#1396)`() {
        val tree = customerCardFirst(withMerchantName(fixture, "Sample Pizza Co"), "Jane D.")
        val r = rules.matchFirst(tree)
        assertEquals("doordash.screen.pickup_pre_arrival", r?.ruleId)
        assertEquals("Sample Pizza Co", r!!.fields["storeName"])
        assertFalse(r.fields.toString().contains("Jane"))
    }

    /**
     * The spec's "merchant card removed" case. The rule REQUIRES a "Pickup from" label, so a frame with
     * the whole merchant card gone is not this rule's; the closest frame this rule still claims is the
     * merchant card with its label but no name yet. Either way the customer's name is never parsed.
     */
    @Test
    fun `a merchant row with no name yet parses a null store, never the customer (#1396)`() {
        val reordered = customerCardFirst(fixture, "Jane D.")
        val nameless = reordered.rewrite { n ->
            if (n.hasId("mx_contact_view")) n.copy(children = n.children.filterNot { it.hasId("user_name") }) else n
        }.restoreParents()
        val r = rules.matchFirst(nameless)
        assertEquals("doordash.screen.pickup_pre_arrival", r?.ruleId)
        assertNull("no merchant name rendered, so no store name", r!!.fields["storeName"])
        assertFalse(r.fields.toString().contains("Jane"))
    }

    @Test
    fun `with the merchant card gone, no rule parses the customer's name (#1396)`() {
        val noMerchant = without(customerCardFirst(fixture, "Jane D.")) { it.hasId("mx_contact_view") }
        val r = rules.matchFirst(noMerchant)
        assertTrue(
            "whichever rule claims the frame must not parse the customer's name: ${r?.ruleId} ${r?.fields}",
            r == null || !r.fields.toString().contains("Jane"),
        )
        assertFalse("without a 'Pickup from' label this is not the pre-arrival rule's frame", r?.ruleId == "doordash.screen.pickup_pre_arrival")
    }
}
