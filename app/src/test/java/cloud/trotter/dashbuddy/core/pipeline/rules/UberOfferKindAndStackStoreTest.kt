package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.offer.OfferKind
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #882 (the multi-order card's type chip won the store read) + #881 (`offerKind` from the CTA),
 * against the fielded corpus.
 *
 * **The paired decision these tests pin.** On an Uber `Delivery (N)` card the platform renders the
 * type chip above a SINGLE visible store line, and `orders[]` extracts exactly one order. Before
 * this, the chip won BOTH the top-level `storeName` and `orders[].storeName`, so the dasher heard
 * "Delivery (2)" and `offer_records.merchantName` recorded it (fielded 2026-07-26 20:40, seq 1231).
 *
 * The fix negates the chip on the TOP-LEVEL (display) list ONLY. `orders[].storeName` — the input
 * to `presentationKey = sha256(storeNames|orders.size|orderTypes)` — deliberately keeps reading the
 * chip, because whether Uber CYCLES which store a stack card shows across re-renders is unconfirmed
 * (a single frame exists). Chip-anchoring makes the identity structurally immune to that cycling;
 * mirroring the negation would have traded a display bug for a #830 replace-storm. That asymmetry
 * is the interesting thing here, so it is pinned from both sides:
 * `sameStackDifferentVisibleStore ⇒ same presentationKey`, and `displayStoreText ⇒ the visible store`.
 */
class UberOfferKindAndStackStoreTest {

    private val ruleset get() = TestRulesetFactory.screenRuleset

    private data class Parsed(val raw: Map<String, Any?>, val offer: ParsedOffer)

    private fun parse(node: UiNode): Parsed? {
        val match = ruleset.matchFirst(node) ?: return null
        val fields = ParsedFieldsFactory.create(match.shape, match.fields) as? ParsedFields.OfferFields
            ?: return null
        return Parsed(match.fields, fields.parsedOffer)
    }

    private fun corpus(platformToken: String) = TestResourceLoader.loadSnapshots("snapshots/offer")
        .filter { (name, _, _) -> name.contains(platformToken) }

    /** True when the tree carries a node whose text is EXACTLY [label] (the CTA discriminator). */
    private fun hasExactText(node: UiNode, label: String): Boolean =
        node.text == label || node.children.any { hasExactText(it, label) }

    /** Rebuild the tree with every occurrence of [from]'s text replaced by [to]. */
    private fun UiNode.withText(from: String, to: String): UiNode = copy(
        text = if (text == from) to else text,
        children = children.map { it.withText(from, to) },
    )

    // =============================================================================================
    // #881 — offerKind from the CTA
    // =============================================================================================

    @Test
    fun `every uber offer in the corpus records the kind its own CTA advertises`() {
        val uber = corpus("__uber__")
        assertTrue("no uber offer corpus — this guard would be vacuous", uber.isNotEmpty())
        var matches = 0
        var directs = 0
        uber.forEach { (filename, node, _) ->
            val parsed = parse(node)
            assertNotNull("$filename no longer parses as an offer", parsed)
            val expected = when {
                hasExactText(node, "Match") -> OfferKind.MATCH
                hasExactText(node, "Accept") -> OfferKind.DIRECT
                else -> error("$filename carries neither CTA — it cannot match uber.screen.offer")
            }
            assertEquals("$filename recorded the wrong offer kind", expected, parsed!!.offer.offerKind)
            if (expected == OfferKind.MATCH) matches++ else directs++
        }
        // Both arms of the conditionalEnum must actually be exercised by the corpus, or a broken
        // arm could ship green.
        assertTrue("corpus must cover a Match card", matches > 0)
        assertTrue("corpus must cover an Accept card", directs > 0)
    }

    @Test
    fun `the Match CTA is matched EXACTLY, so 'Matching may take longer' cannot be read as the CTA`() {
        // The 20-39-29 card carries both "Match" (the button) and "Matching may take longer" (a
        // wait-time note). A `contains` arm would read either; the note must not decide the kind,
        // and it must not win the store read.
        val (filename, node, _) = corpus("__uber__")
            .single { (name, _, _) -> name.contains("2026-07-26_20-39-29") }
        val parsed = parse(node)!!
        assertEquals(OfferKind.MATCH, parsed.offer.offerKind)
        assertEquals("$filename: the wait-time note stole the store read", "Angkor Bistro", parsed.offer.displayStoreText)
    }

    @Test
    fun `the wait-time note loses the store read by NEGATION, not by document order (review F2)`() {
        // On the fielded card the note sits below the store, so it would lose first-match anyway —
        // a positional accident. Prove the negation arm is what's holding by hoisting the note's
        // text ONTO the node that renders above the store (the ETA banner): without the arm the
        // card's merchant would become "Matching may take longer" (the #858 class of lie).
        val hoisted = corpus("__uber__")
            .single { (name, _, _) -> name.contains("2026-07-26_20-39-29") }
            .second
            .withText("Arrive around 8:39 PM • 1 min away", "Matching may take longer")
            .restoreParents()
        val parsed = parse(hoisted)!!
        assertEquals("the note above the store still loses", "Angkor Bistro", parsed.offer.displayStoreText)
        assertEquals("and still isn't read as the CTA", OfferKind.MATCH, parsed.offer.offerKind)
    }

    @Test
    fun `a platform whose ruleset has no offerKind concept parses null (P8 - no Platform branch)`() {
        val doordash = TestResourceLoader.loadSnapshots("snapshots/offer_popup")
            .ifEmpty { corpus("OFFER_POPUP") }
        assertTrue("no DoorDash offer corpus found — the null-kind guard would be vacuous", doordash.isNotEmpty())
        doordash.forEach { (filename, node, _) ->
            val parsed = parse(node) ?: return@forEach
            assertNull("$filename must carry no offerKind — DoorDash has no such concept", parsed.offer.offerKind)
            assertNull("$filename must carry no card-level store either", parsed.offer.displayStoreName)
        }
    }

    // =============================================================================================
    // #882 — the stack card's display store, and the identity that must NOT move with it
    // =============================================================================================

    private fun stackCard(): UiNode = corpus("__uber__")
        .single { (name, _, _) -> name.contains("2026-07-26_20-40-24") }
        .second

    @Test
    fun `the stack card displays its visible store while its identity stays chip-anchored`() {
        val parsed = parse(stackCard())!!

        assertEquals(
            "the DISPLAY store must be the visible store line (#882)",
            "Sonic (3035 Tpc Pkwy)",
            parsed.offer.displayStoreName,
        )
        assertEquals(
            "…and that is what merchantName/TTS read",
            "Sonic (3035 Tpc Pkwy)",
            parsed.offer.displayStoreText,
        )
        assertEquals(
            "the screenshot prefix field resolves against the raw parse map too",
            "Sonic (3035 Tpc Pkwy)",
            parsed.raw["storeName"],
        )
        assertEquals(
            "IDENTITY stays on the type chip — deliberately NOT mirrored (see the class kdoc)",
            "Delivery (2)",
            parsed.offer.orders.single().storeName,
        )
        assertNotNull("the offer still derives a presentation identity", parsed.offer.presentationKey)
    }

    @Test
    fun `the same stack re-rendered with a DIFFERENT visible store keeps the SAME presentationKey`() {
        // The cycling-proof pin. If Uber rotates which store a stack card shows, the display must
        // follow it and the identity must not — otherwise every rotation reads as a new offer and
        // the #830 replace-storm (OFFER_TIMEOUT "Replaced by new offer", re-speak, dropped click
        // latches) returns for stacks specifically.
        val original = stackCard()
        val rotated = original.withText("Sonic (3035 Tpc Pkwy)", "Whataburger (1204)").restoreParents()

        val a = parse(original)!!
        val b = parse(rotated)!!

        assertEquals("the display follows the visible store", "Whataburger (1204)", b.offer.displayStoreText)
        assertNotEquals("…so the two frames DO differ where a human looks", a.offer.displayStoreText, b.offer.displayStoreText)
        assertEquals(
            "…but the presentation identity does NOT move (chip-anchored)",
            a.offer.presentationKey,
            b.offer.presentationKey,
        )
        assertEquals("the per-order store is still the chip on both", "Delivery (2)", b.offer.orders.single().storeName)
    }

    @Test
    fun `a single-store card is untouched - display and identity agree, as before`() {
        val single = corpus("__uber__")
            .single { (name, _, _) -> name.contains("2026-07-21_18-40-26") }
            .second
        val parsed = parse(single)!!
        assertEquals("BJ's Restaurant & Brewhouse (San Antonio #480)", parsed.offer.displayStoreText)
        assertEquals(
            "no divergence on a card without the stack chip",
            parsed.offer.orders.single().storeName,
            parsed.offer.displayStoreText,
        )
    }

    @Test
    fun `the chip negation is anchored - it cannot swallow a store whose name contains 'Delivery'`() {
        // The negation arm is ^Delivery \(\d+\)$ against a containsMatchIn finder, so a real store
        // line must survive it. Rewrite the fielded card's store to a Delivery-ish name and prove
        // it still reads (an unanchored arm would blank the store and fail this).
        val rewritten = stackCard()
            .withText("Sonic (3035 Tpc Pkwy)", "Delivery Doughnuts (2 Mile Rd)")
            .restoreParents()
        assertEquals("Delivery Doughnuts (2 Mile Rd)", parse(rewritten)!!.offer.displayStoreText)
    }
}
