package cloud.trotter.dashbuddy.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #975 / brief §7.5 — the estimate-vs-reality population policy, in the one pure place that owns it.
 *
 * The card's honesty rests entirely on which offers it lets in. Each rule below is an exclusion that,
 * if dropped, would silently *flatter or distort* the comparison rather than fail loudly:
 *
 *  - a **null frozen estimate** (#936's fail-closed evaluation) counted as `0` would drag the
 *    estimate bar down and make realized look better than it was;
 *  - a **stacked job** (two accepted offers, one set of receipts) counted per-offer would attribute
 *    the same dollars twice and roughly double the realized bar;
 *  - an **unmeasured job** (no recorded net or no recorded time) counted as `0` would drag the
 *    realized bar toward zero for work that simply wasn't recorded.
 *
 * And in every case the *denominator* survives, because the surface has to state what it left out.
 */
class EstimateVsRealityTest {

    private fun sample(
        seq: Long,
        estPerHour: Double? = 20.0,
        linkedJobId: String? = "job-$seq",
        realizedNet: Double? = 30.0,
        realizedCashTip: Double = 0.0,
        realizedMinutes: Double? = 60.0,
    ) = AcceptedOfferOutcomeSample(
        offerEventSequenceId = seq,
        estPerHour = estPerHour,
        linkedJobId = linkedJobId,
        realizedNet = realizedNet,
        realizedCashTip = realizedCashTip,
        realizedMinutes = realizedMinutes,
    )

    @Test
    fun `no samples is the empty comparison, not a zero-rate one`() {
        val comparison = EstimateVsReality.of(emptyList())

        assertEquals(EstimateVsReality.EMPTY, comparison)
        assertNull(comparison.estPerHour)
        assertNull(comparison.realizedPerHour)
        assertNull(comparison.ratio)
        assertFalse(comparison.hasComparison)
    }

    @Test
    fun `both bars are the MEAN of per-offer rates and the ratio divides them`() {
        val comparison = EstimateVsReality.of(
            listOf(
                // 30.0 net over 60 min ⇒ $30.00/hr realized against a $20.00/hr estimate
                sample(1, estPerHour = 20.0, realizedNet = 30.0, realizedMinutes = 60.0),
                // 15.0 net over 30 min ⇒ $30.00/hr realized against a $40.00/hr estimate
                sample(2, estPerHour = 40.0, realizedNet = 15.0, realizedMinutes = 30.0),
            ),
        )

        assertEquals(2, comparison.offers)
        assertEquals(2, comparison.acceptedOffers)
        assertEquals(30.0, comparison.estPerHour!!, 1e-9)
        assertEquals(30.0, comparison.realizedPerHour!!, 1e-9)
        assertEquals(1.0, comparison.ratio!!, 1e-9)
        assertTrue(comparison.hasComparison)
    }

    @Test
    fun `cash tips are additive on the realized side (#688)`() {
        val comparison = EstimateVsReality.of(
            listOf(sample(1, realizedNet = 20.0, realizedCashTip = 10.0, realizedMinutes = 60.0)),
        )

        assertEquals(30.0, comparison.realizedPerHour!!, 1e-9)
    }

    @Test
    fun `a null frozen estimate is excluded from the sample but kept in the denominator (#936)`() {
        val comparison = EstimateVsReality.of(
            listOf(
                sample(1, estPerHour = 20.0, realizedNet = 20.0, realizedMinutes = 60.0),
                sample(2, estPerHour = null),
            ),
        )

        assertEquals(1, comparison.offers)
        assertEquals(2, comparison.acceptedOffers)
        // The excluded row contributed NOTHING to either mean — a `0` estimate would have halved it.
        assertEquals(20.0, comparison.estPerHour!!, 1e-9)
        assertEquals(20.0, comparison.realizedPerHour!!, 1e-9)
        assertTrue(comparison.hasUnmatchedOffers)
    }

    @Test
    fun `an offer never linked to a job has no realized side and is excluded`() {
        val comparison = EstimateVsReality.of(
            listOf(
                sample(1),
                sample(2, linkedJobId = null, realizedNet = null, realizedMinutes = null),
            ),
        )

        assertEquals(1, comparison.offers)
        assertEquals(2, comparison.acceptedOffers)
    }

    @Test
    fun `a stacked job drops BOTH of its accepted offers rather than double-counting its net`() {
        val comparison = EstimateVsReality.of(
            listOf(
                // Two accepted offers on ONE job: the join hands each of them the WHOLE job's net.
                sample(1, linkedJobId = "job-stack", realizedNet = 40.0, realizedMinutes = 60.0),
                sample(2, linkedJobId = "job-stack", realizedNet = 40.0, realizedMinutes = 60.0),
                sample(3, linkedJobId = "job-solo", estPerHour = 20.0, realizedNet = 20.0, realizedMinutes = 60.0),
            ),
        )

        assertEquals(1, comparison.offers)
        assertEquals(3, comparison.acceptedOffers)
        // Only the solo job's rates survive — the stack's $40/hr never touches the mean.
        assertEquals(20.0, comparison.estPerHour!!, 1e-9)
        assertEquals(20.0, comparison.realizedPerHour!!, 1e-9)
    }

    @Test
    fun `a job with no recorded net or no measured time is excluded, never counted as zero`() {
        val comparison = EstimateVsReality.of(
            listOf(
                sample(1, realizedNet = 20.0, realizedMinutes = 60.0),
                sample(2, realizedNet = null),                 // nothing recorded
                sample(3, realizedMinutes = null),             // nothing measured
                sample(4, realizedMinutes = 0.0),              // zero denominator is not a rate
            ),
        )

        assertEquals(1, comparison.offers)
        assertEquals(4, comparison.acceptedOffers)
        assertEquals(20.0, comparison.realizedPerHour!!, 1e-9)
    }

    @Test
    fun `every offer excluded leaves the denominator standing and no comparison`() {
        val comparison = EstimateVsReality.of(listOf(sample(1, estPerHour = null), sample(2, estPerHour = null)))

        assertEquals(0, comparison.offers)
        assertEquals(2, comparison.acceptedOffers)
        assertFalse(comparison.hasComparison)
        assertNull(comparison.ratio)
        assertTrue(comparison.hasUnmatchedOffers)
    }

    @Test
    fun `a non-positive estimate mean yields no ratio rather than a division artefact`() {
        val comparison = EstimateVsReality.of(
            listOf(sample(1, estPerHour = 0.0, realizedNet = 20.0, realizedMinutes = 60.0)),
        )

        assertEquals(1, comparison.offers)
        assertEquals(20.0, comparison.realizedPerHour!!, 1e-9)
        assertNull(comparison.ratio)
        // The bars still render — the driver's own data is never hidden, only the ratio is withheld.
        assertTrue(comparison.hasComparison)
    }

    @Test
    fun `a sample under the confidence floor is flagged thin, at or above it is not`() {
        val thin = EstimateVsReality.of((1L until EstimateVsReality.MIN_CONFIDENT_OFFERS).map { sample(it) })
        val enough = EstimateVsReality.of((1L..EstimateVsReality.MIN_CONFIDENT_OFFERS.toLong()).map { sample(it) })

        assertTrue(thin.isThinData)
        assertFalse(enough.isThinData)
        // An EMPTY comparison is not "thin" — there is nothing to qualify.
        assertFalse(EstimateVsReality.EMPTY.isThinData)
    }
}
