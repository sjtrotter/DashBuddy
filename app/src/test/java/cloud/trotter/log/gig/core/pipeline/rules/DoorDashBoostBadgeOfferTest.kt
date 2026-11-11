package cloud.trotter.log.gig.core.pipeline.rules

import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.model.offer.OfferQuoteBasis
import cloud.trotter.log.gig.domain.model.offer.ParsedOffer
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.test.util.TestResourceLoader
import cloud.trotter.log.gig.test.util.TestRulesetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1343: a "Very busy" boost badge renders a standalone `+$N.NN` (`View` → `TextView "Very busy"`,
 * `TextView "+$1.50"`), the same shape as an add-on's delta pay. offer_popup's TOTAL branch rejected
 * every frame holding that shape (#1121), so a boosted card fell to UNKNOWN and its offer was never
 * recorded. The `+$` arm now skips a node whose immediately preceding sibling is the "Very busy" label.
 */
class DoorDashBoostBadgeOfferTest {

    private companion object {
        const val OFFER_RULE = "doordash.screen.offer_popup"
        const val BOOST_LABEL = "Very busy"
        const val BADGE = "+$1.50"

        /** A TOTAL card that carries the "Very busy" label but no `+$` badge node. */
        const val TOTAL_CARD = "87e2e3"

        /** The committed Compose add-on card; its `+$10.50` is its parent's first child. */
        const val ADDON_CARD = "7f17fd"
    }

    private val ruleset get() = TestRulesetFactory.screenRuleset

    private fun fixture(name: String): UiNode {
        val dir = File("src/test/resources/snapshots/offer_popup")
        val file = dir.listFiles()?.sortedBy { it.name }?.firstOrNull { it.name.contains(name) }
        assertNotNull("fixture '$name' must stay committed under snapshots/offer_popup", file)
        return TestResourceLoader.loadNode(file!!).restoreParents()
    }

    private fun textNode(value: String) =
        UiNode(text = value, className = "android.widget.TextView", isEnabled = true)

    /**
     * Rebuilds [this] tree with [sibling] placed next to the first node whose text is [anchor], in
     * that node's parent: after it when [after], before it otherwise. Fails when the anchor is absent.
     */
    private fun UiNode.withSiblingBeside(anchor: String, sibling: UiNode, after: Boolean): UiNode {
        var placed = false
        fun rebuild(node: UiNode): UiNode {
            if (placed) return node
            val idx = node.children.indexOfFirst { it.text == anchor }
            if (idx >= 0) {
                placed = true
                val at = if (after) idx + 1 else idx
                return node.copy(children = node.children.toMutableList().apply { add(at, sibling) })
            }
            return node.copy(children = node.children.map { rebuild(it) })
        }
        val rebuilt = rebuild(this)
        assertTrue("anchor '$anchor' must exist in the fixture", placed)
        return rebuilt.restoreParents()
    }

    private fun offerOrNull(node: UiNode): ParsedOffer? {
        val match = ruleset.matchFirst(node, "doordash") ?: return null
        if (match.ruleId != OFFER_RULE) return null
        val fields = ParsedFieldsFactory.create(match.shape, match.fields)
        return (fields as? ParsedFields.OfferFields)?.parsedOffer
    }

    private fun boostedCard(): UiNode =
        fixture(TOTAL_CARD).withSiblingBeside(BOOST_LABEL, textNode(BADGE), after = true)

    @Test
    fun `a boosted TOTAL card is recognized and the badge is never read as pay`() {
        val baseline = offerOrNull(fixture(TOTAL_CARD))
        assertNotNull("the un-boosted fixture must parse as an offer", baseline)
        assertEquals(OfferQuoteBasis.TOTAL, baseline!!.quoteBasis)

        val boosted = boostedCard()
        assertEquals(OFFER_RULE, ruleset.matchFirst(boosted, "doordash")?.ruleId)
        val offer = offerOrNull(boosted)!!
        assertEquals("the boosted card parses on the TOTAL branch", OfferQuoteBasis.TOTAL, offer.quoteBasis)
        assertEquals(baseline.payAmount!!, offer.payAmount!!, 0.0)
        assertNotEquals(1.50, offer.payAmount!!, 0.0)
    }

    @Test
    fun `the committed add-on card still parses as the add-on, never as TOTAL`() {
        val offer = offerOrNull(fixture(ADDON_CARD))
        assertNotNull("the add-on fixture is recognized as offer_popup", offer)
        assertEquals(OfferQuoteBasis.INCREMENTAL, offer!!.quoteBasis)
        assertEquals(10.50, offer.payAmount!!, 0.0)
    }

    @Test
    fun `an add-on delta after some other sibling text still rejects TOTAL`() {
        val labelled = fixture(ADDON_CARD).withSiblingBeside("+$10.50", textNode("Add-on"), after = false)
        val offer = offerOrNull(labelled)
        assertNotEquals("an add-on never parses as TOTAL", OfferQuoteBasis.TOTAL, offer?.quoteBasis)

        // The discriminating cases: a TOTAL card whose `+$` follows a label other than "Very busy", or
        // has no preceding sibling at all, is still rejected by the `+$` arm alone.
        val otherLabel = fixture(TOTAL_CARD).withSiblingBeside(BOOST_LABEL, textNode("Add-on"), after = true)
            .withSiblingBeside("Add-on", textNode(BADGE), after = true)
        assertNotEquals(OfferQuoteBasis.TOTAL, offerOrNull(otherLabel)?.quoteBasis)
        val firstChild = fixture(TOTAL_CARD).withSiblingBeside(BOOST_LABEL, textNode(BADGE), after = false)
        assertNotEquals(OfferQuoteBasis.TOTAL, offerOrNull(firstChild)?.quoteBasis)
    }

    @Test
    fun `a boosted card with an add-on route line still rejects TOTAL`() {
        val tree = boostedCard().withSiblingBeside("incl. tips", textNode("+1 stop (1.0 mi) • +5 min"), after = true)
        assertNotEquals(OfferQuoteBasis.TOTAL, offerOrNull(tree)?.quoteBasis)
    }
}
