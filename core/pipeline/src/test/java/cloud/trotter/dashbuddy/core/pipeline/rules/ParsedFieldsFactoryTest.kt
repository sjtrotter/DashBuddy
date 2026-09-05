package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ParsedFieldsFactory] order-type resolution (#762 D9).
 *
 * The order type is rule-source vocabulary resolved via [OrderType.valueOf]. Three cases matter:
 * a KNOWN string resolves to its constant; an ABSENT field takes the neutral [OrderType.PICKUP]
 * default (a bare delivery); a PRESENT-but-unrecognized string degrades to [OrderType.UNKNOWN]
 * (a logged gap between the ruleset and the enum) without crashing.
 */
class ParsedFieldsFactoryTest {

    private fun offerWithOrderType(orderType: String?): OrderType {
        val order = buildMap<String, Any?> {
            if (orderType != null) put("orderType", orderType)
            put("storeName", "Test Store")
        }
        val fields = mapOf<String, Any?>("orders" to listOf(order))
        val result = ParsedFieldsFactory.create("offer", fields)
        val offerFields = result as ParsedFields.OfferFields
        return offerFields.parsedOffer.orders.single().orderType
    }

    @Test
    fun `known PICKUP resolves to PICKUP`() {
        assertEquals(OrderType.PICKUP, offerWithOrderType("PICKUP"))
    }

    @Test
    fun `known SHOP_FOR_ITEMS resolves to SHOP_FOR_ITEMS`() {
        assertEquals(OrderType.SHOP_FOR_ITEMS, offerWithOrderType("SHOP_FOR_ITEMS"))
    }

    @Test
    fun `absent orderType takes the neutral PICKUP default`() {
        assertEquals(OrderType.PICKUP, offerWithOrderType(null))
    }

    @Test
    fun `present-but-unrecognized orderType degrades to UNKNOWN without crashing`() {
        // A retired constant name is exactly the historical gap this guards against.
        assertEquals(OrderType.UNKNOWN, offerWithOrderType("RESTAURANT_PICKUP"))
        assertEquals(OrderType.UNKNOWN, offerWithOrderType("totally-bogus"))
    }

    // -----------------------------------------------------------------------------------------
    // #830 presentationKey — fail-closed on a content-free stable subset (review F1)
    // -----------------------------------------------------------------------------------------

    private fun presentationKeyOf(orders: List<Map<String, Any?>>): String? {
        val fields = mapOf<String, Any?>("orders" to orders)
        val result = ParsedFieldsFactory.create("offer", fields) as ParsedFields.OfferFields
        return result.parsedOffer.presentationKey
    }

    @Test
    fun `an offer with real orders derives a non-null presentationKey`() {
        val key = presentationKeyOf(listOf(mapOf("orderType" to "PICKUP", "storeName" to "Sonic")))
        assertNotNull("a stable store subset yields a presentation identity", key)
    }

    @Test
    fun `an order-less offer yields a null presentationKey (fail-closed, never the constant key)`() {
        // A partially-rendered frame that extracted pay but no orders would otherwise hash the
        // CONSTANT "|0|" — every such offer on every platform sharing one key → false enrich-MERGE.
        assertNull("no orders → no stable subset → null (→ replace, never enrich)", presentationKeyOf(emptyList()))
    }

    @Test
    fun `an all-blank-store offer yields a null presentationKey (fail-closed)`() {
        // Same content-free class: every storeName parsed "" → the key input carries no identity.
        val blank = listOf(
            mapOf<String, Any?>("orderType" to "PICKUP", "storeName" to ""),
            mapOf<String, Any?>("orderType" to "PICKUP", "storeName" to "   "),
        )
        assertNull("all-blank stores → null presentation identity", presentationKeyOf(blank))
    }

    @Test
    fun `one real store among blanks still derives a presentationKey (not all-blank)`() {
        val mixed = listOf(
            mapOf<String, Any?>("orderType" to "PICKUP", "storeName" to ""),
            mapOf<String, Any?>("orderType" to "PICKUP", "storeName" to "Sonic"),
        )
        assertNotNull("a single real store carries identity", presentationKeyOf(mixed))
    }

    // -----------------------------------------------------------------------------------------
    // #1030 — a MISSED session-summary total is absent, never a fabricated $0
    // -----------------------------------------------------------------------------------------

    private fun sessionEndedTotal(fields: Map<String, Any?>): Double? =
        (ParsedFieldsFactory.create("session_ended", fields) as ParsedFields.SessionEndedFields)
            .totalEarnings

    @Test
    fun `an unparsed session-ended total stays null, never coerced to zero (#1030)`() {
        // The old `?: 0.0` here fabricated the exact value the fold's summary-screen carve-out
        // trusts as a real measurement — an anchor break would have looked like a $0 dash.
        assertNull(
            "a missed money parse is absent",
            sessionEndedTotal(mapOf("sessionDurationMillis" to 3_600_000L)),
        )
    }

    @Test
    fun `a genuinely parsed zero total is kept as zero (#1030)`() {
        assertEquals(0.0, sessionEndedTotal(mapOf("totalEarnings" to 0.0))!!, 1e-9)
    }

    @Test
    fun `a real parsed total rides through unchanged (#1030)`() {
        assertEquals(21.45, sessionEndedTotal(mapOf("totalEarnings" to 21.45))!!, 1e-9)
    }

    // =========================================================================
    // The id-less receipt still prices its drops (#1029 E4)
    // =========================================================================

    private fun postTask(
        totalPay: Double?,
        customerTips: Double?,
        lineItems: List<Map<String, Any?>> = emptyList(),
    ): ParsedFields.PostTaskFields {
        val fields = buildMap<String, Any?> {
            put("totalPay", totalPay)
            put("customerTips", customerTips)
            put("payLineItems", lineItems)
        }
        return ParsedFieldsFactory.create("post_task", fields) as ParsedFields.PostTaskFields
    }

    @Test
    fun `an 8_93_7 receipt with no itemization synthesizes its breakdown from the scalars`() {
        // DropPayApportioner.apportion(null, ...) returns an EMPTY map — for a SINGLE drop too —
        // so a null parsedPay means no drop gets dropRealizedPay, everything falls to an OFFER_PAY
        // estimate and payoutStoreForms never mints. `pay_line_item_title` is gone on 8.93.7, so
        // that was every fielded receipt; the receipt's own scalars are the honest bridge.
        val parsed = postTask(totalPay = 16.70, customerTips = 7.00).parsedPay
        assertNotNull(parsed)
        assertEquals(9.70, parsed!!.totalBasePay, 0.0001)
        assertEquals(7.00, parsed.customerTips.single().amount, 0.0001)
        assertEquals(16.70, parsed.total, 0.0001)
        assertEquals(
            "a BLANK tip type makes injectiveTipMatch decline, so a stack even-splits (#1051)",
            "", parsed.customerTips.single().type,
        )
    }

    @Test
    fun `a zero-tip receipt synthesizes an app-pay-only breakdown`() {
        val parsed = postTask(totalPay = 8.70, customerTips = 0.0).parsedPay
        assertNotNull(parsed)
        assertEquals(8.70, parsed!!.totalBasePay, 0.0001)
        assertTrue(parsed.customerTips.isEmpty())
    }

    @Test
    fun `a COLLAPSED receipt stays unpriced — the tips line is the breakdown-visible signal`() {
        // `sameTaskCollapsedDowngrade` in PlatformRegionStepper keys on parsedPay == null being
        // the collapsed signal, so synthesizing one without a tips line would break it.
        assertNull(postTask(totalPay = 16.70, customerTips = null).parsedPay)
    }

    @Test
    fun `a receipt with no total, a zero total, or tips above total stays unpriced`() {
        assertNull(postTask(totalPay = null, customerTips = 7.00).parsedPay)
        assertNull(postTask(totalPay = 0.0, customerTips = 0.0).parsedPay)
        assertNull(postTask(totalPay = 5.00, customerTips = 7.00).parsedPay)
    }

    @Test
    fun `an itemized receipt is untouched by the bridge`() {
        val parsed = postTask(
            totalPay = 16.70,
            customerTips = 7.00,
            lineItems = listOf(
                mapOf("type" to "Base pay", "amount" to 8.70),
                mapOf("type" to "Peak pay", "amount" to 1.00),
                mapOf("type" to "Some Store", "amount" to 7.00),
            ),
        ).parsedPay
        assertNotNull(parsed)
        assertEquals(9.70, parsed!!.totalBasePay, 0.0001)
        assertEquals("the real per-store tip type survives", "Some Store", parsed.customerTips.single().type)
    }
}
