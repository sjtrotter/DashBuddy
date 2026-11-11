package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.state.OpenReceiptEvidence
import cloud.trotter.log.gig.domain.state.ReceiptCoverage
import cloud.trotter.log.gig.domain.state.ReceiptProofGap
import cloud.trotter.log.gig.domain.state.ReceiptScope
import org.junit.Assert.assertEquals
import org.junit.Test

/** #1598 — the proof clause table of [receiptCoverageAt], one change per case on the proven fixture. */
class ReceiptProofTest {

    private fun proven(subject: String = "t1") =
        ReceiptCoverage(taskIds = setOf(subject), scope = ReceiptScope.PROVEN_SINGLE)

    private fun gap(gap: ReceiptProofGap) = ReceiptCoverage(scope = ReceiptScope.UNPROVEN, gap = gap)

    @Test
    fun `the proven fixture is proven`() {
        assertEquals(proven(), receiptCoverageAt(RS.live(), "t1"))
    }

    @Test
    fun `no subject`() {
        assertEquals(gap(ReceiptProofGap.NO_SUBJECT), receiptCoverageAt(RS.live(), null))
    }

    @Test
    fun `zero or two accepted offers`() {
        assertEquals(gap(ReceiptProofGap.OFFER_COUNT), receiptCoverageAt(RS.live(offers = emptyList()), "t1"))
        assertEquals(
            gap(ReceiptProofGap.OFFER_COUNT),
            receiptCoverageAt(RS.live(offers = listOf(RS.offer("o1"), RS.offer("o2"))), "t1"),
        )
    }

    @Test
    fun `the card count is missing or not one`() {
        assertEquals(
            gap(ReceiptProofGap.CARD_COUNT_MISSING),
            receiptCoverageAt(RS.live(offers = listOf(RS.offer(count = null))), "t1"),
        )
        assertEquals(
            gap(ReceiptProofGap.CARD_COUNT_NOT_ONE),
            receiptCoverageAt(RS.live(offers = listOf(RS.offer(count = 2))), "t1"),
        )
    }

    @Test
    fun `an identity-less subject is not accountable`() {
        assertEquals(
            gap(ReceiptProofGap.SUBJECT_NOT_ACCOUNTABLE),
            receiptCoverageAt(RS.live(active = RS.drop("t1", identity = false)), "t1"),
        )
    }

    @Test
    fun `a second accountable drop, completed or active, blocks proof`() {
        val completedSibling = RS.drop("t0", completedAt = 2_900L)
        assertEquals(
            gap(ReceiptProofGap.OTHER_DROPS),
            receiptCoverageAt(RS.live(recent = listOf(completedSibling)), "t1"),
        )
        // The subject is the completed drop and the ACTIVE drop is the other accountable one.
        val region = RS.live(active = RS.drop("t2"), recent = listOf(RS.drop("t1", completedAt = 2_900L)))
        assertEquals(gap(ReceiptProofGap.OTHER_DROPS), receiptCoverageAt(region, "t1"))
    }

    @Test
    fun `a never-activated placeholder sibling does not block proof (OQ2)`() {
        val placeholder = RS.drop("ph", store = null, arrivedAt = null, identity = false)
        val region = RS.live(jobTasks = listOf(RS.drop("t1"), placeholder))
        assertEquals(proven(), receiptCoverageAt(region, "t1"))
    }

    @Test
    fun `an unassigned sibling does not block proof`() {
        val unassigned = RS.drop("t0", completedAt = 2_900L).copy(unassignedAt = 2_950L)
        assertEquals(proven(), receiptCoverageAt(RS.live(recent = listOf(unassigned)), "t1"))
    }

    @Test
    fun `an unassigned copy anywhere makes the subject unaccountable`() {
        val region = RS.live().let {
            it.copy(activeJob = it.activeJob!!.copy(tasks = listOf(RS.drop("t1").copy(unassignedAt = 3_600L))))
        }
        assertEquals(gap(ReceiptProofGap.SUBJECT_NOT_ACCOUNTABLE), receiptCoverageAt(region, "t1"))
    }

    @Test
    fun `the subject store is missing or another chain`() {
        val noStore = RS.drop("t1", store = null)
        assertEquals(
            gap(ReceiptProofGap.STORE_MISSING),
            receiptCoverageAt(RS.live(active = noStore, jobTasks = listOf(noStore)), "t1"),
        )
        val otherStore = RS.drop("t1", store = "Store B")
        assertEquals(
            gap(ReceiptProofGap.STORE_MISMATCH),
            receiptCoverageAt(RS.live(active = otherStore, jobTasks = listOf(otherStore)), "t1"),
        )
    }

    @Test
    fun `the store reads from any copy of the subject, active first`() {
        // The job's copy has no store yet; the active copy reconciled it.
        val region = RS.live(jobTasks = listOf(RS.drop("t1", store = null)))
        assertEquals(proven(), receiptCoverageAt(region, "t1"))
    }

    @Test
    fun `a franchise form matches the quoted location form through the chain key`() {
        val region = RS.live(offers = listOf(RS.offer(hints = listOf("Store A (Loop 410)"))))
        assertEquals("Store A #55", region.activeTask!!.storeName)
        assertEquals(proven(), receiptCoverageAt(region, "t1"))
    }

    @Test
    fun `after the close only the same proven episode stays proven`() {
        val closed = RS.live().copy(activeJob = null, activeTask = null, recentTasks = listOf(RS.drop("t1", completedAt = 4_000L)))
        val episode = OpenReceiptEvidence(
            evidenceId = "receipt:t1:10000", subjectTaskId = "t1", jobId = "J1", firstSeenAt = 10_000L,
            revision = 1, lastTotalCents = 1_670L, lastScope = ReceiptScope.PROVEN_SINGLE, possibleWorkSeqAtOpen = 0L,
        )
        assertEquals(proven(), receiptCoverageAt(closed.copy(openReceiptEvidence = episode), "t1"))
        assertEquals(
            "a new episode after the close",
            gap(ReceiptProofGap.NO_LIVE_JOB), receiptCoverageAt(closed, "t1"),
        )
        assertEquals(
            "an episode that was unproven",
            gap(ReceiptProofGap.NO_LIVE_JOB),
            receiptCoverageAt(closed.copy(openReceiptEvidence = episode.copy(lastScope = ReceiptScope.UNPROVEN)), "t1"),
        )
        assertEquals(
            "another subject's episode",
            gap(ReceiptProofGap.NO_LIVE_JOB),
            receiptCoverageAt(closed.copy(openReceiptEvidence = episode.copy(subjectTaskId = "t9")), "t1"),
        )
    }
}
