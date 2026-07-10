package cloud.trotter.dashbuddy.domain.state

import cloud.trotter.dashbuddy.domain.model.pay.ParsedPay
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPayItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #159 / #526 Step 3 — the store-chain projection links offer ↔ pickup ↔ dropoff ↔ payout for each
 * store of a job, correlating the differing surface forms by brand tokens. Modelled on the real
 * 2026-06-19 Target + Maple Street stack.
 */
class StoreChainProjectorTest {

    private fun pickup(id: String, store: String) = Task(
        taskId = id, jobId = "job-1", phase = TaskPhase.PICKUP, storeName = store, startedAt = 100L, completedAt = 200L,
    )

    private fun dropoff(id: String, store: String, cust: String) = Task(
        taskId = id, jobId = "job-1", phase = TaskPhase.DROPOFF, storeName = store,
        customerNameHash = cust, startedAt = 300L, completedAt = 400L,
    )

    private fun job(tasks: List<Task>, offerHints: List<String>) = Job(
        jobId = "job-1", offerStoreHint = offerHints, parentOfferHash = null, startedAt = 50L, tasks = tasks,
    )

    @Test
    fun `multi-store stack links each store across all four surfaces`() {
        val j = job(
            tasks = listOf(
                pickup("p-target", "Target"),
                pickup("p-maple", "Maple Street Biscuit Company"),
                dropoff("d-target", "Target", "cust-A"),
                dropoff("d-maple", "Maple Street Biscuit Company", "cust-B"),
            ),
            offerHints = listOf("Target", "Maple Street Biscuit Company"),
        )
        val payout = ParsedPay(
            appPayComponents = listOf(ParsedPayItem("Base Pay", 6.40)),
            customerTips = listOf(
                ParsedPayItem("Maple Street Biscuit - Alamo Ranch", 6.50),
                ParsedPayItem("Target (02426)", 2.25),
            ),
        )

        val chain = StoreChainProjector.project(j, payout)
        assertEquals("job-1", chain.jobId)
        assertEquals(2, chain.links.size)

        val target = chain.links.single { it.canonicalStore == "Target" }
        assertEquals("Target", target.offerName)
        assertEquals("Target", target.dropoffName)
        assertEquals("the payout running-key form links by brand token", "Target (02426)", target.payoutName)
        assertEquals("02426", target.runningKey)
        assertEquals(listOf("cust-A"), target.customerHashes)
        assertEquals(2.25, target.realizedTip!!, 0.001)

        val maple = chain.links.single { it.canonicalStore == "Maple Street Biscuit Company" }
        assertEquals("Maple Street Biscuit Company", maple.offerName)
        assertEquals(
            "the dash-suffix payout form links to the brand despite the core mismatch",
            "Maple Street Biscuit - Alamo Ranch", maple.payoutName,
        )
        assertEquals("Alamo Ranch", maple.runningKey)
        assertEquals(listOf("cust-B"), maple.customerHashes)
        assertEquals(6.50, maple.realizedTip!!, 0.001)
    }

    @Test
    fun `single-store job links the one store and its payout`() {
        val j = job(
            tasks = listOf(pickup("p1", "H-E-B"), dropoff("d1", "H-E-B", "cust-1")),
            offerHints = listOf("H-E-B"),
        )
        val payout = ParsedPay(
            appPayComponents = listOf(ParsedPayItem("Base Pay", 7.0)),
            customerTips = listOf(ParsedPayItem("H-E-B (799)", 5.5)),
        )
        val chain = StoreChainProjector.project(j, payout)
        assertEquals(1, chain.links.size)
        val link = chain.links.single()
        assertEquals("H-E-B", link.canonicalStore)
        assertEquals("799", link.runningKey)
        assertEquals(5.5, link.realizedTip!!, 0.001)
        assertEquals(listOf("cust-1"), link.customerHashes)
    }

    @Test
    fun `a payout line matching no pickup is not attributed`() {
        val j = job(
            tasks = listOf(pickup("p1", "Target"), dropoff("d1", "Target", "cust-1")),
            offerHints = listOf("Target"),
        )
        // A stray payout line for a store with no pickup in this job → dropped, not mis-attributed.
        val payout = ParsedPay(
            appPayComponents = emptyList(),
            customerTips = listOf(ParsedPayItem("Whataburger (123)", 3.0)),
        )
        val chain = StoreChainProjector.project(j, payout)
        assertEquals(1, chain.links.size)
        assertNull("Target gets no payout line (Whataburger doesn't match)", chain.links.single().payoutName)
    }

    @Test
    fun `M5 — the Job adapter and the row adapter produce identical resolutions over the same surfaces`() {
        // The pure StoreResolver core is reached two ways: the Job adapter (StoreChainProjector, the
        // shadow logger's path) and the row adapter (StoreResolutionRunner, over DB rows). Given the SAME
        // surfaces, both MUST resolve identically — that parity is what makes the shadow log a valid field
        // oracle for the persisted path. This test pins it.
        val tasks = listOf(
            pickup("p-target", "Target"),
            pickup("p-maple", "Maple Street Biscuit Company"),
            dropoff("d-target", "Target", "cust-A"),
            dropoff("d-maple", "Maple Street Biscuit Company", "cust-B"),
        )
        val offerHints = listOf("Target", "Maple Street Biscuit Company")
        val payout = ParsedPay(
            appPayComponents = emptyList(),
            customerTips = listOf(
                ParsedPayItem("Maple Street Biscuit - Alamo Ranch", 6.50),
                ParsedPayItem("Target (02426)", 2.25),
            ),
        )
        // Job adapter.
        val jobResolved = StoreChainProjector.project(job(tasks, offerHints), payout).links
            .associate { it.canonicalStore to Triple(it.offerName, it.payoutName, it.runningKey) }
        // Row adapter: the SAME surfaces flattened to neutral lists (what StoreResolutionRunner passes).
        val rowResolved = StoreResolver.resolveAnchors(
            anchors = listOf("Target", "Maple Street Biscuit Company"),
            offerForms = offerHints,
            dropoffForms = listOf("Target", "Maple Street Biscuit Company"),
            payoutForms = listOf(
                StoreResolver.PayoutForm("Maple Street Biscuit - Alamo Ranch", 6.50),
                StoreResolver.PayoutForm("Target (02426)", 2.25),
            ),
        ).associate { it.canonical to Triple(it.offerForm, it.payoutForm, it.runningKey) }

        assertEquals("row adapter ≡ Job adapter resolutions (M5 parity)", jobResolved, rowResolved)
    }

    @Test
    fun `null payout still yields the pickup-anchored links`() {
        val j = job(
            tasks = listOf(pickup("p1", "Chipotle"), dropoff("d1", "Chipotle", "cust-1")),
            offerHints = listOf("Chipotle"),
        )
        val chain = StoreChainProjector.project(j, payout = null)
        assertEquals(1, chain.links.size)
        assertNull(chain.links.single().payoutName)
        assertNull(chain.links.single().realizedTip)
        assertEquals("Chipotle", chain.links.single().canonicalStore)
    }
}
