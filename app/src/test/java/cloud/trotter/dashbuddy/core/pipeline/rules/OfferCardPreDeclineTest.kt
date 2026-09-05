package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The DoorDash offer card **before its Decline control inflates** (#1063).
 *
 * DoorDash renders the offer sheet in two beats: the collar animation lands the card — store leg,
 * pay, distance, deadline, the live `accept_button` and its countdown — and the decline button
 * inflates a beat later. `doordash.screen.offer_popup` required a literal `Decline` node from the
 * ruleset's very first commit (inherited from the pre-#89 Kotlin matcher, where "Decline +
 * Accept" was simply the card's two footer buttons), so that first beat classified UNKNOWN.
 *
 * Six real offers in the 2026-09-05 pull were lost to it. Three never produced a settled sibling
 * at all — one (09-05 07:46:53) was **accepted** off a card the app had never presented — so the
 * offer produced no `OFFER_PRESENTED`, no evaluation and no voice.
 *
 * The two fixtures below are the fielded proof that admitting the early beat is safe: both frames
 * carry DoorDash's own `assignment_id_text` `29d94bea-…`, i.e. they are literally the same offer
 * 120 ms apart, and they parse to the same `presentationKey` AND the same `offerHash`. Under #830
 * that makes the early frame a re-presentation of one offer, never a replace — no
 * `OFFER_TIMEOUT("Replaced by new offer")`, no discarded evaluation, one `SpeakOffer`.
 *
 * The synthetic controls pin the guards the relaxation deliberately did NOT touch: an offer-shaped
 * frame with no real store leg stays UNKNOWN (#595), whether or not it carries Decline.
 */
class OfferCardPreDeclineTest {

    private companion object {
        const val OFFER_RULE = "doordash.screen.offer_popup"

        /** The animation frame: full card, live accept button, no `Decline` node anywhere. */
        const val EARLY = "c38cf0"

        /** Its settled sibling 120 ms later — same `assignment_id_text`, Decline inflated. */
        const val SETTLED = "07b9cf"
    }

    private val ruleset get() = TestRulesetFactory.screenRuleset

    private fun fixture(name: String): UiNode {
        val dir = File("src/test/resources/snapshots/offer_popup")
        val file = dir.listFiles()?.sortedBy { it.name }?.firstOrNull { it.name.contains(name) }
        assertNotNull(
            "the 08-28 15:31:47 offer pair must stay committed under snapshots/offer_popup — " +
                "'$name' is the only fielded evidence of the pre-Decline render (#1063)",
            file,
        )
        return TestResourceLoader.loadNode(file!!).also { it.restoreParents() }
    }

    private fun offerOf(node: UiNode): ParsedOffer {
        val match = ruleset.matchFirst(node, "doordash")
        assertEquals("the frame must be recognized as the offer card", OFFER_RULE, match?.ruleId)
        val fields = ParsedFieldsFactory.create(match!!.shape, match.fields)
        assertTrue("the offer shape must build offer fields", fields is ParsedFields.OfferFields)
        return (fields as ParsedFields.OfferFields).parsedOffer
    }

    // =========================================================================
    //  The fielded pair
    // =========================================================================

    @Test
    fun `the card is recognized before its Decline control inflates`() {
        val node = fixture(EARLY)
        assertTrue(
            "the fixture must genuinely carry NO Decline node — otherwise it is not the " +
                "animation frame this rule change exists for",
            !node.hasNode { it.text == "Decline" },
        )
        assertTrue(
            "the fixture must carry the accept-button id that now anchors the arm",
            node.hasNode { it.viewIdResourceName?.endsWith("accept_button") == true },
        )
        assertEquals(OFFER_RULE, ruleset.matchFirst(node, "doordash")?.ruleId)
    }

    @Test
    fun `the early frame and its settled sibling are ONE offer, not a replace`() {
        val early = offerOf(fixture(EARLY))
        val settled = offerOf(fixture(SETTLED))

        // #830: a matching presentationKey is what makes the second frame an enrich-as-variant
        // rather than an OFFER_TIMEOUT("Replaced by new offer") + a discarded evaluation.
        assertNotNull("a real store leg must yield a presentation key", early.presentationKey)
        assertEquals(
            "the animation frame must present as the SAME offer as its settled sibling (#830)",
            settled.presentationKey,
            early.presentationKey,
        )
        // Same economics too, so it is not even a variant — it is the same content identity.
        assertEquals("same offer identity", settled.offerHash, early.offerHash)
        assertEquals(settled.payAmount, early.payAmount)
        assertEquals(settled.distanceMiles, early.distanceMiles)
        assertEquals(settled.dueByTimeText, early.dueByTimeText)
        assertEquals(
            settled.orders.map { it.storeName },
            early.orders.map { it.storeName },
        )
    }

    // =========================================================================
    //  The guards the relaxation did NOT touch (#595)
    // =========================================================================

    /**
     * A minimal offer-shaped card: the footer ids and texts the rule anchors on, plus whatever
     * store rows the caller supplies.
     */
    private fun card(declineNode: Boolean, vararg storeNames: String): UiNode {
        val rows = storeNames.map { name ->
            UiNode(
                viewIdResourceName = "com.dd.doordash:id/display_name_container",
                children = listOf(
                    UiNode(text = name, viewIdResourceName = "com.dd.doordash:id/display_name"),
                ),
            )
        }
        val footer = buildList {
            if (declineNode) {
                add(
                    UiNode(
                        viewIdResourceName = "com.dd.doordash:id/secondary_action_button_dash_plus",
                        children = listOf(
                            UiNode(text = "Decline", viewIdResourceName = "com.dd.doordash:id/textView_prism_button_title"),
                        ),
                    ),
                )
            }
            add(
                UiNode(
                    viewIdResourceName = "com.dd.doordash:id/accept_button",
                    children = listOf(
                        UiNode(text = "Accept", viewIdResourceName = "com.dd.doordash:id/textView_prism_button_title"),
                    ),
                ),
            )
        }
        return UiNode(
            viewIdResourceName = "com.dd.doordash:id/accept_decline_fragment_container",
            children = listOf(
                UiNode(text = "$9.50  Guaranteed (incl. tips)", viewIdResourceName = "com.dd.doordash:id/text_field"),
                UiNode(text = "3.1 mi", viewIdResourceName = "com.dd.doordash:id/text_field"),
                UiNode(text = "Deliver by 4:21 PM", viewIdResourceName = "com.dd.doordash:id/text_field"),
            ) + rows + UiNode(
                viewIdResourceName = "com.dd.doordash:id/accept_decline_footer_container",
                children = footer,
            ),
        ).also { it.restoreParents() }
    }

    @Test
    fun `a control card with a real store leg is recognized with or without Decline`() {
        assertEquals(OFFER_RULE, ruleset.matchFirst(card(declineNode = true, "Panda Express"), "doordash")?.ruleId)
        assertEquals(OFFER_RULE, ruleset.matchFirst(card(declineNode = false, "Panda Express"), "doordash")?.ruleId)
    }

    @Test
    fun `a Decline-bearing card whose store leg is blank stays UNKNOWN`() {
        // #595: the store-less half-render re-parsed as a NEW degenerate offer and REPLACED the
        // just-accepted one. Relaxing the Decline conjunct must not reopen that door.
        assertNull(
            "a blank display_name is not a store leg (#595)",
            ruleset.matchFirst(card(declineNode = true, ""), "doordash")?.ruleId,
        )
        assertNull(
            "'Customer dropoff' alone is not a store leg (#595)",
            ruleset.matchFirst(card(declineNode = true, "Customer dropoff"), "doordash")?.ruleId,
        )
        // …and the same holds on the pre-Decline shape the relaxation admits.
        assertNull(
            "the relaxed arm must not admit a store-leg-less animation frame either",
            ruleset.matchFirst(card(declineNode = false, "Customer dropoff"), "doordash")?.ruleId,
        )
    }
}
