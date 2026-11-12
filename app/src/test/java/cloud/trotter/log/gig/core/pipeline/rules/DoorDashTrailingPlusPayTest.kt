package cloud.trotter.log.gig.core.pipeline.rules

import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.model.offer.OfferQuoteBasis
import cloud.trotter.log.gig.domain.model.offer.ParsedOffer
import cloud.trotter.log.gig.domain.model.order.OrderType
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.test.util.TestResourceLoader
import cloud.trotter.log.gig.test.util.TestRulesetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * #1604: on DoorDash 8.106.9 a Compose total card can show its pay as one node with a trailing
 * plus, `$27.35+`, followed by `incl. tips` ("this much or more"). offer_popup's total branch
 * anchored on an exact `$N.NN`, so the whole card fell to UNKNOWN. The fixture is a committed
 * one-store Compose total card (it already carries the badge `TextView` before the pay and the
 * `Shop & deliver` chip after the route summary) rewritten to the census shape. The leading-plus
 * form `+$8.00` stays the add-on card's delta and never parses as a total.
 */
class DoorDashTrailingPlusPayTest {

    @Test
    fun `a trailing-plus pay parses the same offer as the plain figure`() {
        val baseline = parse(card())
        assertEquals(14.75, baseline.payAmount!!, 0.0)
        assertEquals(baseline, parse(card(pay = "$14.75+")))
    }

    @Test
    fun `the census card shape is recognized as a total offer`() {
        val offer = parse(censusCard(pay = "$27.35+"))
        assertEquals(OfferQuoteBasis.TOTAL, offer.quoteBasis)
        assertEquals(27.35, offer.payAmount!!, 0.0)
        assertEquals(7.9, offer.distanceMiles!!, 0.0)
        assertEquals(91.0, offer.timeToCompleteMinutes!!.toDouble(), 0.0)
        val order = offer.orders.single()
        assertEquals("H-E-B", order.storeName)
        assertEquals(OrderType.SHOP_FOR_ITEMS, order.orderType)
        assertEquals(18, order.itemCount)
        assertEquals(1, offer.dropoffCount)
    }

    @Test
    fun `the census card without the plus still parses`() {
        val plus = parse(censusCard(pay = "$27.35+"))
        val plain = parse(censusCard(pay = "$27.35"))
        assertEquals(27.35, plain.payAmount!!, 0.0)
        assertEquals(plain, plus)
    }

    @Test
    fun `a leading-plus pay never parses as a total`() {
        val match = TestRulesetFactory.screenRuleset.matchFirst(censusCard(pay = "+$8.00"), "doordash")
        val offer = match?.takeIf { it.ruleId == OFFER_RULE }
            ?.let { (ParsedFieldsFactory.create(it.shape, it.fields) as? ParsedFields.OfferFields)?.parsedOffer }
        assertNotEquals(OfferQuoteBasis.TOTAL, offer?.quoteBasis)
    }

    @Test
    fun `the side-nav drawer does not claim the card`() {
        val withDrawer = censusCard(pay = "$27.35+").withDrawerMenu()
        assertEquals(OFFER_RULE, TestRulesetFactory.screenRuleset.matchFirst(withDrawer, "doordash")?.ruleId)
    }

    @Test
    fun `the drawer gives up on a partial card whose only signature is a trailing-plus pay`() {
        // No route summary and no Accept yet: only the pay is left for the drawer's give-up to see,
        // so the frame must fall to UNKNOWN for triage instead of being filed as chrome.
        val partial = censusCard(pay = "$27.35+")
            .withRewrittenText(CENSUS_ROUTE, "")
            .withRewrittenText("Accept", "")
            .withDrawerMenu()
        assertNull(TestRulesetFactory.screenRuleset.matchFirst(partial, "doordash")?.ruleId)
    }

    private fun card(pay: String = PAY): UiNode {
        val file = File("src/test/resources/$FIXTURE")
        require(file.isFile) { "Missing snapshot: ${file.absolutePath}" }
        val original = TestResourceLoader.loadNode(file)
        for (text in listOf(PAY, BADGE, ROUTE, SHOP_ROW, "Shop & deliver", "incl. tips")) {
            require(original.findNode { it.text == text } != null) { "fixture lost its '$text' node" }
        }
        return original.withRewrittenText(PAY, pay).restoreParents()
    }

    /** The census sample's node shape: Gold badge, `$27.35+`, a 1 hr 31 min route, an 18-item shop row. */
    private fun censusCard(pay: String): UiNode = card(pay)
        .withRewrittenText(BADGE, "[icon] High pay for Gold")
        .withRewrittenText(ROUTE, CENSUS_ROUTE)
        .withRewrittenText(SHOP_ROW, "Shop for 18 items (24 units, ~19 min)")
        .restoreParents()

    /** Adds the side-nav menu texts the drawer rule requires beside its content container. */
    private fun UiNode.withDrawerMenu(): UiNode {
        val menu = listOf("Ratings", "Promos", "Preferences").map {
            UiNode(text = it, className = "android.widget.TextView", isEnabled = true)
        }
        return copy(children = children + menu).restoreParents()
    }

    private fun parse(tree: UiNode): ParsedOffer {
        val match = requireNotNull(TestRulesetFactory.screenRuleset.matchFirst(tree, "doordash")) { "card fell to UNKNOWN" }
        assertEquals(OFFER_RULE, match.ruleId)
        return (ParsedFieldsFactory.create(match.shape, match.fields) as ParsedFields.OfferFields).parsedOffer
    }

    private fun UiNode.withRewrittenText(from: String, to: String): UiNode = copy(
        text = if (text == from) to else text,
        children = children.map { it.withRewrittenText(from, to) },
    )

    private companion object {
        const val OFFER_RULE = "doordash.screen.offer_popup"
        const val FIXTURE =
            "snapshots/offer_popup/2026-09-19_11-42-11-094__doordash__accessibility.window__offer_popup__b7abab.json"
        const val PAY = "$14.75"
        const val BADGE = "[icon] High pay for Pro Shoppers"
        const val ROUTE = "2 stops (8.5 mi) • 45 min"
        const val CENSUS_ROUTE = "2 stops (7.9 mi) • 1 hr 31 min"
        const val SHOP_ROW = "Shop for 6 items (~7 min)"
    }
}
