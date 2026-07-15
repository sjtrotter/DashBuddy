package cloud.trotter.dashbuddy.domain.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #159 — the pure resolver core ([StoreResolver.resolveAnchors]) shared by the read-model projector
 * and the shadow logger (M5). Covers the D4 hardening shapes and the realizedTip-sums-all-matched rule.
 */
class StoreResolverTest {

    private fun payout(vararg forms: Pair<String, Double>) =
        forms.map { StoreResolver.PayoutForm(it.first, it.second) }

    @Test
    fun `franchise-number and place-name payout forms resolve their running keys`() {
        val r = StoreResolver.resolveAnchors(
            anchors = listOf("Sprouts Farmers Market", "CAVA"),
            offerForms = listOf("Sprouts Farmers Market", "CAVA"),
            dropoffForms = emptyList(),
            payoutForms = payout(
                "SPROUTS FARMERS MARKET #161" to 4.0,
                "CAVA (Sonterra Village)" to 3.0,
            ),
        )
        assertEquals("161", r.single { it.canonical == "Sprouts Farmers Market" }.runningKey)
        assertEquals("Sonterra Village", r.single { it.canonical == "CAVA" }.runningKey)
    }

    @Test
    fun `realizedTip sums all payout lines matched to one store (D4)`() {
        val r = StoreResolver.resolveAnchors(
            anchors = listOf("Target"),
            offerForms = emptyList(),
            dropoffForms = emptyList(),
            payoutForms = payout("Target (02426)" to 2.25, "Target Tip Adjustment" to 1.00),
        )
        assertEquals(3.25, r.single().realizedTip!!, 0.001)
        assertEquals("02426", r.single().runningKey)
    }

    @Test
    fun `each store in a multi-store stack keys off its own line (B2)`() {
        val r = StoreResolver.resolveAnchors(
            anchors = listOf("Target", "Maple Street Biscuit Company"),
            offerForms = emptyList(),
            dropoffForms = emptyList(),
            payoutForms = payout(
                "Target (02426)" to 2.25,
                "Maple Street Biscuit - Alamo Ranch" to 6.50,
            ),
        )
        assertEquals("02426", r.single { it.canonical == "Target" }.runningKey)
        assertEquals("Alamo Ranch", r.single { it.canonical == "Maple Street Biscuit Company" }.runningKey)
    }

    @Test
    fun `a payout line matching no anchor is dropped`() {
        val r = StoreResolver.resolveAnchors(
            anchors = listOf("Target"),
            offerForms = emptyList(),
            dropoffForms = emptyList(),
            payoutForms = payout("Whataburger (123)" to 3.0),
        )
        assertNull(r.single().runningKey)
        assertNull(r.single().realizedTip)
    }

    @Test
    fun `no anchors yields no resolutions`() {
        val r = StoreResolver.resolveAnchors(emptyList(), emptyList(), emptyList(), emptyList())
        assertEquals(0, r.size)
    }

    // ── #773 address fallback ladder (resolvedRunningKey) ───────────────

    @Test
    fun `a receipt running key WINS over an address key (ladder — receipt first)`() {
        val r = StoreResolver.resolveAnchors(
            anchors = listOf("Target"),
            offerForms = emptyList(),
            dropoffForms = emptyList(),
            payoutForms = payout("Target (02426)" to 2.25),
            anchorAddresses = mapOf("Target" to "12125 Alamo Rnch Pkwy, San Antonio, TX"),
        ).single()
        assertEquals("02426", r.runningKey) // raw receipt token unchanged
        assertEquals("02426", r.resolvedRunningKey) // ladder picks the receipt tier
    }

    @Test
    fun `a chain-bare receipt falls back to the address key`() {
        val r = StoreResolver.resolveAnchors(
            anchors = listOf("H-E-B"),
            offerForms = emptyList(),
            dropoffForms = emptyList(),
            payoutForms = emptyList(), // grocery: chain-bare, no running key on the receipt
            anchorAddresses = mapOf("H-E-B" to "12125 Alamo Rnch Pkwy, San Antonio, TX 78240, USA"),
        ).single()
        assertNull(r.runningKey)
        assertEquals("@12125", r.addressKey)
        assertEquals("@12125", r.resolvedRunningKey)
    }

    @Test
    fun `neither a receipt key nor a usable address yields a null resolved key (chain-only)`() {
        val r = StoreResolver.resolveAnchors(
            anchors = listOf("H-E-B"),
            offerForms = emptyList(),
            dropoffForms = emptyList(),
            payoutForms = emptyList(),
            anchorAddresses = mapOf("H-E-B" to "The Rim Shopping Center"), // no leading street number
        ).single()
        assertNull(r.resolvedRunningKey)
    }
}
