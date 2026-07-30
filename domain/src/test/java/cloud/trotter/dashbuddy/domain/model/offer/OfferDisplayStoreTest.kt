package cloud.trotter.dashbuddy.domain.model.offer

import cloud.trotter.dashbuddy.domain.evaluation.EvaluationConfig
import cloud.trotter.dashbuddy.domain.evaluation.MetricType
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluator
import cloud.trotter.dashbuddy.domain.evaluation.ScoringRule
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.domain.model.order.ParsedOrder
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #882 — [ParsedOffer.displayStoreText], the single "which store does a human see" definition, and
 * #881 — [OfferKind.fromWire].
 *
 * The display store had to become its own concept because the identity-bearing
 * `orders[].storeName` on an Uber multi-order card is the TYPE CHIP ("Delivery (2)"), deliberately
 * kept there so a stack's `presentationKey` cannot churn if the card cycles which of its stores it
 * shows. Every human-facing read (TTS, the bubble offer card, `offer_records.merchantName`, store
 * resolution) routes through the evaluator's `merchantName`, which is why the precedence lives in
 * ONE place rather than at each call site.
 */
class OfferDisplayStoreTest {

    private fun offer(
        displayStoreName: String? = null,
        vararg orderStores: String,
    ) = ParsedOffer(
        offerHash = "h",
        payAmount = 7.0,
        distanceMiles = 3.0,
        displayStoreName = displayStoreName,
        orders = orderStores.mapIndexed { i, s ->
            ParsedOrder(
                orderIndex = i, orderType = OrderType.PICKUP, storeName = s,
                itemCount = 1, isItemCountEstimated = true, badges = emptySet(),
            )
        },
    )

    // ---------------------------------------------------------------------------------------
    // displayStoreText precedence
    // ---------------------------------------------------------------------------------------

    @Test
    fun `no card store parsed falls back to the joined orders (the pre-882 DoorDash path)`() {
        assertEquals("Taco Bell", offer(null, "Taco Bell").displayStoreText)
        assertEquals(
            "Tarka Indian Kitchen & Torchy's Tacos",
            offer(null, "Tarka Indian Kitchen", "Torchy's Tacos").displayStoreText,
        )
    }

    @Test
    fun `the card's own store wins when the orders yield a single store (the Uber stack fix)`() {
        // The fielded 2026-07-26 card: chip held in orders[] for identity, visible store displayed.
        assertEquals(
            "Sonic (3035 Tpc Pkwy)",
            offer("Sonic (3035 Tpc Pkwy)", "Delivery (2)").displayStoreText,
        )
    }

    @Test
    fun `two or more real order stores always beat the card headline`() {
        // A genuine multi-store parse is strictly richer than one headline line — the card-level
        // text may never shadow it (this is what keeps a DoorDash stack reading both stores even
        // if its rule ever starts declaring a top-level storeName).
        assertEquals(
            "Tarka Indian Kitchen & Torchy's Tacos",
            offer("Tarka Indian Kitchen", "Tarka Indian Kitchen", "Torchy's Tacos").displayStoreText,
        )
    }

    @Test
    fun `a blank card store is ignored`() {
        assertEquals("Taco Bell", offer("   ", "Taco Bell").displayStoreText)
    }

    @Test
    fun `an order-less offer with a card store still displays it`() {
        assertEquals("Angkor Bistro", offer("Angkor Bistro").displayStoreText)
    }

    @Test
    fun `nothing parsed at all is blank, never a fabricated name`() {
        assertEquals("", offer(null).displayStoreText)
    }

    /**
     * #942: the two surfaces that hold only the derived list (the HUD offer card and the heads-up
     * notification) join through this, not through their own separator — they used to disagree
     * (" & " vs " + ") about the same offer.
     */
    @Test
    fun `joinDisplayStores is the separator SSOT displayStoreText itself uses`() {
        assertEquals("A & B", listOf("A", "B").joinDisplayStores())
        assertEquals("A", listOf("A").joinDisplayStores())
        assertEquals("", emptyList<String>().joinDisplayStores())
        val stacked = offer(null, "Tarka Indian Kitchen", "Torchy's Tacos")
        assertEquals(stacked.displayStoreText, stacked.displayStores.joinDisplayStores())
    }

    // ---------------------------------------------------------------------------------------
    // The evaluator (the SSOT's one consumer) — merchantName is what TTS speaks
    // ---------------------------------------------------------------------------------------

    private fun merchantNameOf(o: ParsedOffer): String = OfferEvaluator().evaluate(
        o,
        EvaluationConfig(
            rules = listOf(
                ScoringRule.MetricRule(id = "payout", metricType = MetricType.PAYOUT, targetValue = 7.0f),
            ),
            userEconomy = UserEconomy(vehicleClass = VehicleClass.E_BIKE),
        ),
    ).merchantName

    @Test
    fun `the evaluator speaks the visible store of a stack card, not its type chip`() {
        assertEquals(
            "Sonic (3035 Tpc Pkwy)",
            merchantNameOf(offer("Sonic (3035 Tpc Pkwy)", "Delivery (2)")),
        )
    }

    @Test
    fun `the evaluator is unchanged for an offer with no card store`() {
        assertEquals("Taco Bell", merchantNameOf(offer(null, "Taco Bell")))
        assertEquals("Unknown Store", merchantNameOf(offer(null)))
    }

    // ---------------------------------------------------------------------------------------
    // OfferKind wire resolution (#881)
    // ---------------------------------------------------------------------------------------

    @Test
    fun `offerKind resolves its wire vocabulary and fails NULL on anything else`() {
        assertEquals(OfferKind.MATCH, OfferKind.fromWire("match"))
        assertEquals(OfferKind.DIRECT, OfferKind.fromWire("direct"))
        assertEquals("case/space tolerant", OfferKind.DIRECT, OfferKind.fromWire(" Direct "))
        assertNull("a platform without the concept", OfferKind.fromWire(null))
        assertNull("an unknown vocabulary word is NOT defaulted to a kind", OfferKind.fromWire("radar"))
        assertNull(OfferKind.fromWire(""))
    }
}
