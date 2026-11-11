package cloud.trotter.log.gig.core.pipeline.rules

import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.model.offer.ParsedOffer
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.test.util.TestResourceLoader
import cloud.trotter.log.gig.test.util.TestRulesetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * #1598: the Compose TOTAL offer card's dropoff holder reads `Customer dropoff` (one drop stop) or
 * `Multiple dropoffs (N stops)`, parsed into [ParsedOffer.dropoffCount]. The add-on card and the legacy View card
 * carry no count. Committed fixtures only.
 */
class DoorDashDropoffCountParseTest {

    @Test
    fun `a one-drop Compose card counts one, including a one-customer two-store card`() {
        assertEquals(1, parse(OFFER_DIR + "2026-09-19_09-45-38-229__doordash__accessibility.window__offer_popup__822a68.json").dropoffCount)
        assertEquals(1, parse(OFFER_DIR + "2026-09-19_20-38-23-394__doordash__accessibility.window__offer_popup__073fcb.json").dropoffCount)
        assertEquals(1, parse(SESSION_DIR + "gift_drop_open_job_2026_10_11/02_win_offer_popup.json").dropoffCount)
    }

    @Test
    fun `a two-drop Compose card counts two`() {
        assertEquals(2, parse(OFFER_DIR + "2026-09-18_18-15-06-664__doordash__accessibility.window__offer_popup__b9a745.json").dropoffCount)
        assertEquals(2, parse(OFFER_DIR + "2026-09-19_20-39-03-514__doordash__accessibility.window__offer_popup__858fcf.json").dropoffCount)
        assertEquals(2, parse(SESSION_DIR + "unassign_two_order_job_2026_10_09/01_win_offer_popup.json").dropoffCount)
        assertEquals(2, parse(SESSION_DIR + "gift_drop_open_job_2026_10_11/48_win_offer_popup.json").dropoffCount)
    }

    @Test
    fun `the add-on card and the legacy View card carry no count`() {
        assertNull(parse(OFFER_DIR + "2026-09-20_15-51-37-652__doordash__accessibility.window__offer_popup__7f17fd.json").dropoffCount)
        assertNull(parse(SESSION_DIR + "single_delivery_2026_06_16/01_offer_popup.json").dropoffCount)
        assertNull(parse(SESSION_DIR + "two_pickup_stack_2026_07_05/01_offer_popup.json").dropoffCount)
    }

    @Test
    fun `a zero-stop holder is no count`() {
        val tree = load(OFFER_DIR + "2026-09-18_18-15-06-664__doordash__accessibility.window__offer_popup__b9a745.json")
        require(tree.findNode { it.text == "Multiple dropoffs (2 stops)" } != null) { "fixture lost its drop holder" }
        assertNull(parse(tree.withRewrittenText("Multiple dropoffs (2 stops)", "Multiple dropoffs (0 stops)").restoreParents()).dropoffCount)
    }

    private fun load(path: String): UiNode {
        val file = File("src/test/resources/$path")
        require(file.isFile) { "Missing snapshot: ${file.absolutePath}" }
        return TestResourceLoader.loadNode(file)
    }

    private fun parse(path: String): ParsedOffer = parse(load(path))

    private fun parse(tree: UiNode): ParsedOffer {
        val match = requireNotNull(TestRulesetFactory.screenRuleset.matchFirst(tree)) { "card fell to UNKNOWN" }
        return (ParsedFieldsFactory.create(match.shape, match.fields) as ParsedFields.OfferFields).parsedOffer
    }

    private fun UiNode.withRewrittenText(from: String, to: String): UiNode = copy(
        text = if (text == from) to else text,
        children = children.map { it.withRewrittenText(from, to) },
    )

    private companion object {
        const val OFFER_DIR = "snapshots/offer_popup/"
        const val SESSION_DIR = "snapshots/sessions/"
    }
}
