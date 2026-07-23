package cloud.trotter.dashbuddy.domain.analytics

import cloud.trotter.dashbuddy.domain.analytics.JobAcceptMismatchResolver.AcceptedOffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #810 B2 Tier 1 — the pure store-evidence join ([JobAcceptMismatchResolver]). Proves the exact
 * predicate: resolve iff exactly one accepted offer is store-unaccounted while every other is
 * accounted; every ambiguous shape is INCONCLUSIVE (fail-null beats fail-wrong).
 */
class JobAcceptMismatchResolverTest {

    private fun resolve(offers: List<AcceptedOffer>, delivered: List<String>) =
        JobAcceptMismatchResolver.resolveOrphan(offers, delivered)

    @Test
    fun `cross-store orphan resolves to the single unaccounted offer`() {
        // Two accepts (HEB + Whataburger), only Whataburger delivered → HEB (seq 10) is the orphan.
        val orphan = resolve(
            offers = listOf(AcceptedOffer(10, "H-E-B"), AcceptedOffer(20, "Whataburger")),
            delivered = listOf("Whataburger (0456)"),
        )
        assertEquals("the undelivered store's offer is the orphan", 10L, orphan)
    }

    @Test
    fun `same-store tie falls through to INCONCLUSIVE (the fielded seq-114 shape)`() {
        // Two H-E-B accepts, one delivered H-E-B drop → both offers match the one HEB chain → no
        // single unaccounted offer → Tier 2.
        val orphan = resolve(
            offers = listOf(AcceptedOffer(10, "H-E-B"), AcceptedOffer(20, "H-E-B")),
            delivered = listOf("H-E-B (0123)"),
        )
        assertNull("a same-store tie cannot be auto-resolved", orphan)
    }

    @Test
    fun `multiple unaccounted offers fall through to INCONCLUSIVE`() {
        // Three accepts, only one store delivered → two unaccounted → ambiguous → Tier 2.
        val orphan = resolve(
            offers = listOf(
                AcceptedOffer(10, "H-E-B"),
                AcceptedOffer(20, "Whataburger"),
                AcceptedOffer(30, "Chipotle"),
            ),
            delivered = listOf("Chipotle Mexican Grill"),
        )
        assertNull("two unaccounted offers are ambiguous", orphan)
    }

    @Test
    fun `zero unaccounted offers is a no-op`() {
        // Both stores delivered → nothing unaccounted → no orphan to stamp.
        val orphan = resolve(
            offers = listOf(AcceptedOffer(10, "H-E-B"), AcceptedOffer(20, "Whataburger")),
            delivered = listOf("H-E-B (0123)", "Whataburger (0456)"),
        )
        assertNull("every accepted offer's store delivered", orphan)
    }

    @Test
    fun `no delivered evidence is INCONCLUSIVE (never resolves everything as orphan)`() {
        val orphan = resolve(
            offers = listOf(AcceptedOffer(10, "H-E-B"), AcceptedOffer(20, "Whataburger")),
            delivered = emptyList(),
        )
        assertNull("no store evidence to distinguish offers", orphan)
    }

    @Test
    fun `a null or blank offer store forces INCONCLUSIVE (unreliable evidence)`() {
        val orphan = resolve(
            offers = listOf(AcceptedOffer(10, null), AcceptedOffer(20, "Whataburger")),
            delivered = listOf("Whataburger (0456)"),
        )
        assertNull("a store-less offer can't be safely reasoned about", orphan)
    }

    @Test
    fun `a single accept never resolves (no mismatch to disambiguate)`() {
        val orphan = resolve(
            offers = listOf(AcceptedOffer(10, "H-E-B")),
            delivered = listOf("Whataburger (0456)"),
        )
        assertNull("a single-accept job is not this signal", orphan)
    }

    @Test
    fun `store forms match by normalized chain across payout qualifiers`() {
        // The delivered evidence carries a parenthetical location code; the offer store is bare. The
        // normalizedChain SSOT strips the qualifier, so the surviving offer is store-accounted and the
        // cross-store one is the orphan.
        val orphan = resolve(
            offers = listOf(AcceptedOffer(10, "Panda Express"), AcceptedOffer(20, "H-E-B")),
            delivered = listOf("H-E-B (Loop 410) - San Antonio"),
        )
        assertEquals("Panda Express is unaccounted", 10L, orphan)
    }
}
