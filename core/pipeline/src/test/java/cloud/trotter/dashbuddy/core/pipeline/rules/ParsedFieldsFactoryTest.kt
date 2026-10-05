package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

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
        val fields = mapOf<String, Any?>("orders" to orders, "presentationIdentity" to "store")
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

    // #1069 — exact assignment identity wins; only declared store identity may merge re-quotes.
    private fun identityOffer(
        assignmentId: String? = null,
        identity: String? = null,
        pay: Double = 23.20,
        distance: Double = 3.2,
        stores: List<String> = listOf("H-E-B"),
    ): ParsedOffer {
        val fields = buildMap<String, Any?> {
            put("payAmount", pay)
            put("distance", distance)
            put("orders", stores.map { mapOf("storeName" to it, "orderType" to "SHOP_FOR_ITEMS") })
            if (assignmentId != null) put("assignmentId", assignmentId)
            if (identity != null) put("presentationIdentity", identity)
        }
        return (ParsedFieldsFactory.create("offer", fields) as ParsedFields.OfferFields).parsedOffer
    }

    private fun digest(input: String): String = MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    @Test
    fun `assignment identity is trimmed hashed and exact regardless of stores or fallback`() {
        for (stores in listOf(emptyList(), listOf(""), listOf("H-E-B"), listOf("Sonic", "H-E-B"))) {
            for (identity in listOf(null, "store", "economics")) {
                val offer = identityOffer("  assignment-1  ", identity, stores = stores)
                assertEquals(digest("assignment|assignment-1"), offer.assignmentIdHash)
                assertEquals(offer.assignmentIdHash, offer.presentationKey)
            }
        }
    }

    @Test
    fun `different assignments with identical economics have different keys but identical offer hashes`() {
        val first = identityOffer("assignment-1")
        val second = identityOffer("assignment-2")
        assertNotEquals(first.presentationKey, second.presentationKey)
        assertEquals("assignment is never an offerHash input", first.offerHash, second.offerHash)
    }

    @Test
    fun `same assignment with different economics keeps the exact presentation key`() {
        val first = identityOffer("assignment-1")
        val second = identityOffer("assignment-1", pay = 17.20, distance = 10.4)
        assertEquals(first.presentationKey, second.presentationKey)
        assertNotEquals(first.offerHash, second.offerHash)
    }

    @Test
    fun `store fallback is byte equal to the pre-1069 stable subset across re-quotes`() {
        val first = identityOffer(identity = "store", stores = listOf("H-E-B", "Sonic"))
        val second = identityOffer(identity = "store", pay = 17.20, distance = 10.4, stores = listOf("H-E-B", "Sonic"))
        assertNull(first.assignmentIdHash)
        assertEquals(digest("H-E-B,Sonic|2|SHOP_FOR_ITEMS,SHOP_FOR_ITEMS"), first.presentationKey)
        assertEquals(first.presentationKey, second.presentationKey)
        assertNotEquals(first.offerHash, second.offerHash)
    }

    @Test
    fun `undeclared and explicit economics fallback produce no presentation key`() {
        for (identity in listOf(null, "economics")) {
            val offer = identityOffer(identity = identity)
            assertNull(offer.assignmentIdHash)
            assertNull(offer.presentationKey)
        }
    }

    @Test
    fun `empty and whitespace assignment ids are absent and use the declared fallback`() {
        for (id in listOf("", " \t\n")) {
            for (identity in listOf(null, "store", "economics")) {
                val offer = identityOffer(id, identity)
                assertNull(offer.assignmentIdHash)
                assertEquals(identityOffer(identity = identity).presentationKey, offer.presentationKey)
            }
        }
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
    fun `the synthesized app-pay label is platform-neutral AND survives the pay partition`() {
        // #1052 (principle 8): `:core:pipeline` is the shared recognition spine and must not name
        // a gig platform — and the old "DoorDash pay" literal would have read as a genuine
        // DoorDash label on any other platform's receipt. The replacement is constrained: an
        // itemized receipt is split app-pay/tips on `type.contains("pay")`, so a synthesized
        // component failing that token would be sorted as a customer tip.
        assertFalse(
            "no platform literal in the recognition spine",
            ParsedFieldsFactory.SYNTHESIZED_APP_PAY_LABEL.contains("doordash", ignoreCase = true),
        )
        assertTrue(
            "the partition heuristic must still claim it as app pay",
            ParsedFieldsFactory.SYNTHESIZED_APP_PAY_LABEL.contains("pay", ignoreCase = true),
        )
        val parsed = postTask(totalPay = 16.70, customerTips = 7.00).parsedPay
        assertEquals(
            ParsedFieldsFactory.SYNTHESIZED_APP_PAY_LABEL,
            parsed!!.appPayComponents.single().type,
        )
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
