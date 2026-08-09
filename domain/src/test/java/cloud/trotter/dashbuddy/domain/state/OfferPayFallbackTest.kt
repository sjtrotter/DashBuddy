package cloud.trotter.dashbuddy.domain.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #691 — the pure eligibility + share policy extracted out of EffectMap (FIX 5), plus the two money
 * fixes layered on it: the #996 eligible-owed shrink (a proven-complete job stops diluting its split
 * with a placeholder that can never mint) and the #997 store-correspondence attribution ladder (a job
 * that absorbed N separately-accepted offers stops pooling their pay into one job-wide average).
 *
 * Two entry points, two policies — the #997-amendment split:
 * - [OfferPayFallback.shareFor] is the INLINE (PostTask-exit) mint and keeps the pre-#996/#997
 *   conservative pooled split, bit-exact;
 * - [OfferPayFallback.closeAttribution] is the TERMINAL close and runs the whole ladder.
 */
class OfferPayFallbackTest {

    private fun drop(id: String, completedAt: Long?, store: String? = null) = Task(
        taskId = id, jobId = "J", phase = TaskPhase.DROPOFF, storeName = store,
        customerNameHash = "c-$id", startedAt = 100L, completedAt = completedAt,
    )

    /** A never-activated customer-TBD dropoff placeholder — the #996 consolidation artifact. */
    private fun placeholder(id: String, offerHash: String? = null, completedAt: Long? = null) = Task(
        taskId = id, jobId = "J", phase = TaskPhase.DROPOFF,
        customerNameHash = null, customerAddressHash = null, startedAt = 100L, completedAt = completedAt,
        mintedByOfferHash = offerHash,
    )

    private fun pickup(id: String, store: String, customer: String?) = Task(
        taskId = id, jobId = "J", phase = TaskPhase.PICKUP, storeName = store,
        customerNameHash = customer, startedAt = 100L, completedAt = 300L,
    )

    private fun job(offerPay: Double?, tasks: List<Task>, stores: List<String> = emptyList()) = Job(
        jobId = "J", offerStoreHint = stores, parentOfferHash = null,
        acceptedOffers = listOf(
            AcceptedOfferEconomics(offerHash = "h", payAmount = offerPay, storeHints = stores, acceptedAt = 50L),
        ),
        tasks = tasks, startedAt = 50L,
    )

    /** A job that absorbed several accepts — one [AcceptedOfferEconomics] per `(hash, pay, stores)`. */
    private fun multiOfferJob(
        offers: List<Triple<String, Double?, List<String>>>,
        tasks: List<Task>,
    ) = Job(
        jobId = "J", offerStoreHint = offers.flatMap { it.third }, parentOfferHash = offers.firstOrNull()?.first,
        acceptedOffers = offers.mapIndexed { i, (hash, pay, stores) ->
            AcceptedOfferEconomics(offerHash = hash, payAmount = pay, storeHints = stores, acceptedAt = 50L + i)
        },
        tasks = tasks, startedAt = 50L,
    )

    // The INLINE (PostTask-exit) mint.
    private fun inlineShare(
        job: Job,
        taskId: String,
        recentTasks: List<Task> = emptyList(),
        requireFinalShape: Boolean = false,
    ) = OfferPayFallback.shareFor(
        job, recentTasks, taskId,
        suppressedByReceipt = false,
        requireFinalShape = requireFinalShape,
    )

    // The TERMINAL close's whole ladder.
    private fun closePlan(
        job: Job,
        recentTasks: List<Task> = emptyList(),
        provenComplete: Boolean = false,
        suppressed: Boolean = false,
    ) = OfferPayFallback.closeAttribution(job, recentTasks, suppressed, provenComplete)

    private fun closeShare(
        job: Job,
        taskId: String,
        recentTasks: List<Task> = emptyList(),
        provenComplete: Boolean = false,
    ) = closePlan(job, recentTasks, provenComplete).resultFor(taskId)

    private fun cents(v: Double): Long = Math.round(v * 100.0)

    // ---- #691 baseline: the INLINE mint, unchanged by both fixes ----

    @Test
    fun `a receipt suppresses the estimate entirely`() {
        val j = job(12.95, listOf(drop("d1", 400L)))
        val r = OfferPayFallback.shareFor(
            j, emptyList(), "d1", suppressedByReceipt = true, requireFinalShape = false,
        )
        assertNull(r.share)
        assertFalse("suppressed is not an unsplit-miss", r.eligibleButUnsplit)
        assertNull("nothing was measured → no fabricated 0-of-0 denominator", r.denominator)
    }

    @Test
    fun `final-shape gate blocks a mid-stack drop and stamps the last open drop`() {
        val j = job(12.95, listOf(drop("d1", null), drop("d2", null)))
        val mid = inlineShare(j, "d1", requireFinalShape = true)
        assertNull("mid-stack → no stamp", mid.share)
        assertFalse(mid.eligibleButUnsplit)
        assertNull("a blocked mint measured nothing", mid.denominator)

        val j2 = job(12.95, listOf(drop("d1", 380L), drop("d2", null)))
        assertEquals(6.47, inlineShare(j2, "d2", requireFinalShape = true).share!!, 1e-9)
    }

    @Test
    fun `close-out stamps every owed drop's equal share`() {
        val j = job(12.95, listOf(drop("d1", 400L), drop("d2", 410L)))
        assertEquals(6.48, closeShare(j, "d1").share!!, 1e-9)
        assertEquals(6.47, closeShare(j, "d2").share!!, 1e-9)
    }

    @Test
    fun `a pay-less offer is eligible-but-unsplit (the WARN signal)`() {
        val j = job(offerPay = null, tasks = listOf(drop("d1", 400L)))
        val r = closeShare(j, "d1")
        assertNull(r.share)
        assertTrue("eligible but the split yielded nothing", r.eligibleButUnsplit)
        assertEquals("and the WARN can say it was the drop's OWN quote that was missing", false, r.ownOfferPayPresent)
    }

    @Test
    fun `a minting task outside the owed set is eligible-but-unsplit`() {
        val j = job(12.95, listOf(drop("d1", 400L)))
        val r = closeShare(j, "ghost")
        assertNull(r.share)
        assertTrue(r.eligibleButUnsplit)
    }

    @Test
    fun `owedDropoffs unions job tasks with the job's recentTasks dropoffs, ANY marker state, deduped`() {
        // #752: job.tasks (the outstanding-placeholder mirror) no longer retains an unassigned drop,
        // so the quoted-order denominator must union it back in from the lifecycle record. The union
        // keeps ANY marker state (unassigned included — that order was still quoted), dedupes by
        // taskId (d1 appears in both sources), and ignores foreign-job tasks.
        val survivor = drop("d1", 400L)
        val unassigned = drop("d2", null).copy(unassignedAt = 1_000L)
        val foreign = drop("dX", 500L).copy(jobId = "OTHER")
        val j = job(12.95, tasks = listOf(survivor))
        val owed = OfferPayFallback.owedDropoffs(j, recentTasks = listOf(survivor, unassigned, foreign))
        assertEquals(setOf("d1", "d2"), owed.map { it.taskId }.toSet())
        assertEquals(6.48, closeShare(j, "d1", listOf(survivor, unassigned, foreign)).share!!, 1e-9)
    }

    // ---- #997 amendment B: the INLINE mint keeps the conservative denominator ----

    @Test
    fun `amendment B — the INLINE mint never shrinks and never attributes per-offer`() {
        // The shape #996/#997 would otherwise "fix" here: a proven-looking same-customer job with a
        // dead placeholder, minting inline. It must still pool over the QUOTED orders — an inline
        // full-quote stamp followed by a later activation of the filtered drop would put Σ stamped
        // above Σ quoted, the one thing this policy must never permit.
        val delivered = drop("d1", null, store = "Mama Margies").copy(mintedByOfferHash = "h")
        val tbd = placeholder("d2", offerHash = "h")
        val j = job(13.10, listOf(delivered, tbd), stores = listOf("Mama Margies", "Raising Cane's"))
        val r = inlineShare(j, "d1", requireFinalShape = true)
        assertEquals("pooled over the 2 quoted orders, exactly as pre-#996/#997", 6.55, r.share!!, 1e-9)
        assertEquals(OfferPayAttribution.INLINE_POOLED, r.arm)
        assertNull("a pooled share names no single offer", r.attributedOfferHash)
        assertEquals(OfferPayFallback.Denominator(2, 2), r.denominator)
    }

    // ---- #996: the eligible-owed shrink at the close ----

    @Test
    fun `#996 — a proven-complete same-customer job stamps the FULL quote on its one physical drop`() {
        // The 08-07 field shape (job …-313, seqs 1496→1506): ONE offer quoting $13.10 over 2 orders
        // (Mama Margies + Raising Cane's) for ONE customer. Only one physical drop ever activates, so
        // the second placeholder stays customer-TBD forever. Pre-#996 the denominator counted it and
        // the drop folded 13.10/2 = $6.55 — the dash reconciled reported $52.85 − attributed $46.30 =
        // exactly the missing $6.55.
        val delivered = drop("d1", 400L, store = "Mama Margies").copy(mintedByOfferHash = "h")
        val tbd = placeholder("d2", offerHash = "h")
        val j = job(13.10, listOf(delivered, tbd), stores = listOf("Mama Margies", "Raising Cane's"))

        val r = closeShare(j, "d1", listOf(delivered), provenComplete = true)
        assertEquals("the consolidation artifact leaves the denominator", 13.10, r.share!!, 1e-9)
        assertEquals(OfferPayFallback.Denominator(quotedOwed = 2, eligibleOwed = 1), r.denominator)
        assertEquals(OfferPayAttribution.PER_OFFER_STORE, r.arm)
        assertEquals("h", r.attributedOfferHash)
    }

    @Test
    fun `#996 conservatism pin — completion NOT proven reproduces today's diluted split bit-exactly`() {
        val delivered = drop("d1", 400L, store = "Mama Margies").copy(mintedByOfferHash = "h")
        val tbd = placeholder("d2", offerHash = "h")
        val j = job(13.10, listOf(delivered, tbd), stores = listOf("Mama Margies", "Raising Cane's"))

        val r = closeShare(j, "d1", listOf(delivered), provenComplete = false)
        assertEquals("today's behaviour, unchanged", 6.55, r.share!!, 1e-9)
        assertEquals(OfferPayFallback.Denominator(2, 2), r.denominator)
    }

    @Test
    fun `#996 — a FLICKER-activated identity-less placeholder is shrunk out too`() {
        // The amended criterion is "can never mint", not "was never touched": a #498-class placeholder
        // whose completedAt was stamped by a displacement still carries no identity, so the mint
        // firewall will never emit for it. Keying on completedAt (the pre-amendment hand-written
        // triple) would have left it diluting forever.
        val delivered = drop("d1", 400L, store = "Target").copy(mintedByOfferHash = "h")
        val flickered = placeholder("d2", offerHash = "h", completedAt = 405L)
        val j = job(10.00, listOf(delivered, flickered), stores = listOf("Target"))
        assertEquals(10.00, closeShare(j, "d1", listOf(delivered, flickered), provenComplete = true).share!!, 1e-9)
    }

    @Test
    fun `#996 doctrine pin — an UNASSIGNED order keeps diluting even at a proven-complete close`() {
        // An unassigned order was QUOTED but may not be paid, so its share must never be re-attributed
        // to a sibling (that would over-count). The shrink targets only never-activated placeholders,
        // and an unassigned drop is excluded from it by the predicate's own `unassignedAt` clause.
        val survivor = drop("d1", 400L, store = "Target")
        val unassignedInline = drop("d2", null).copy(customerNameHash = null, unassignedAt = 900L)
        val j = job(12.95, tasks = listOf(survivor), stores = listOf("Target"))

        val r = closeShare(j, "d1", listOf(survivor, unassignedInline), provenComplete = true)
        assertEquals("the unassigned order still divides the quote", 6.48, r.share!!, 1e-9)
        assertEquals(2, r.denominator!!.eligibleOwed)
    }

    // ---- #997 amendment A: the store-correspondence ladder ----

    @Test
    fun `#997 08-06 — same-store offers SUB-POOL while the unique-store drop takes its exact quote`() {
        // The 08-06 field shape (job …-299, seqs 1442 / 1445 / 1455): Target $10.45, Target $16.55,
        // H-E-B $20.20 absorbed into ONE job, all three folding the pooled 47.20/3 = $15.73.
        // Which Target quote belongs to which Target drop is platform-side unknowable, so the honest
        // answer sub-pools them — strictly tighter than job-wide pooling, and H-E-B takes its exact
        // $20.20 instead of $15.74.
        val d1 = drop("d1", 400L, store = "Target").copy(mintedByOfferHash = "o1")
        val d2 = drop("d2", 410L, store = "Target").copy(mintedByOfferHash = "o2")
        val d3 = drop("d3", 420L, store = "H-E-B").copy(mintedByOfferHash = "o3")
        val j = multiOfferJob(
            listOf(
                Triple("o1", 10.45, listOf("Target")),
                Triple("o2", 16.55, listOf("Target")),
                Triple("o3", 20.20, listOf("H-E-B")),
            ),
            listOf(d1, d2, d3),
        )
        val recent = listOf(d1, d2, d3)
        val plan = closePlan(j, recent, provenComplete = true)

        assertEquals(13.50, plan.resultFor("d1").share!!, 1e-9)
        assertEquals(13.50, plan.resultFor("d2").share!!, 1e-9)
        assertEquals(20.20, plan.resultFor("d3").share!!, 1e-9)
        assertEquals(OfferPayAttribution.SUB_POOLED_STORE, plan.resultFor("d1").arm)
        assertNull("a sub-pooled share names no single offer", plan.resultFor("d1").attributedOfferHash)
        assertEquals(OfferPayAttribution.PER_OFFER_STORE, plan.resultFor("d3").arm)
        assertEquals("o3", plan.resultFor("d3").attributedOfferHash)

        assertEquals(
            "Σ = the accepted quotes, to the cent (unchanged from the pooled split)",
            cents(47.20),
            listOf("d1", "d2", "d3").sumOf { cents(plan.resultFor(it).share!!) },
        )
        assertEquals("no quote went homeless", 0, plan.unattributedOffers)
        assertTrue(
            "the sub-pool degrade is stated",
            plan.degrades.any { it.arm == OfferPayAttribution.SUB_POOLED_STORE && it.offers == 2 && it.drops == 2 },
        )
    }

    @Test
    fun `#997 — a CROSS-STORE swap auto-corrects (store beats the mint stamp)`() {
        // Placeholders activate blind first-open, so when the platform routes the add-on's drop first
        // the slot stamps are crossed. Folding the stamp's exact quote would be confidently WRONG with
        // Σ unchanged — invisible to reconciliation. The store is what survives the reshuffle.
        val dA = drop("dA", 400L, store = "H-E-B").copy(mintedByOfferHash = "o1")
        val dB = drop("dB", 410L, store = "Target").copy(mintedByOfferHash = "o2")
        val j = multiOfferJob(
            listOf(Triple("o1", 10.00, listOf("Target")), Triple("o2", 20.00, listOf("H-E-B"))),
            listOf(dA, dB),
        )
        val plan = closePlan(j, listOf(dA, dB), provenComplete = true)
        assertEquals("the H-E-B drop takes the H-E-B quote, not its stamp's", 20.00, plan.resultFor("dA").share!!, 1e-9)
        assertEquals(10.00, plan.resultFor("dB").share!!, 1e-9)
        assertEquals("o2", plan.resultFor("dA").attributedOfferHash)
    }

    @Test
    fun `#997 — a NULL-store drop falls back to the offer that minted its slot`() {
        // The fielded D6 join-miss class: a drop whose store never resolved. With no store evidence
        // the mint stamp is the best evidence there is — rung 2.
        val dA = drop("dA", 400L, store = "Target").copy(mintedByOfferHash = "o1")
        val dB = drop("dB", 410L, store = null).copy(mintedByOfferHash = "o2")
        val j = multiOfferJob(
            listOf(Triple("o1", 10.00, listOf("Target")), Triple("o2", 20.00, listOf("H-E-B"))),
            listOf(dA, dB),
        )
        val plan = closePlan(j, listOf(dA, dB), provenComplete = true)
        assertEquals(10.00, plan.resultFor("dA").share!!, 1e-9)
        assertEquals(20.00, plan.resultFor("dB").share!!, 1e-9)
        assertEquals(OfferPayAttribution.STAMP_FALLBACK, plan.resultFor("dB").arm)
        assertTrue(plan.degrades.any { it.arm == OfferPayAttribution.STAMP_FALLBACK && it.drops == 1 })
    }

    @Test
    fun `#997 — the per-offer split is cents-exact WITHIN each component`() {
        val a1 = drop("a1", 400L, store = "Target").copy(mintedByOfferHash = "o1")
        val a2 = drop("a2", 401L, store = "Target").copy(mintedByOfferHash = "o1")
        val b1 = drop("b1", 402L, store = "H-E-B").copy(mintedByOfferHash = "o2")
        val b2 = drop("b2", 403L, store = "H-E-B").copy(mintedByOfferHash = "o2")
        val j = multiOfferJob(
            listOf(Triple("o1", 10.01, listOf("Target")), Triple("o2", 7.33, listOf("H-E-B"))),
            listOf(a1, a2, b1, b2),
        )
        val plan = closePlan(j, listOf(a1, a2, b1, b2), provenComplete = true)
        val shares = listOf("a1", "a2", "b1", "b2").associateWith { plan.resultFor(it).share!! }
        assertEquals(cents(10.01), cents(shares.getValue("a1")) + cents(shares.getValue("a2")))
        assertEquals(cents(7.33), cents(shares.getValue("b1")) + cents(shares.getValue("b2")))
        assertEquals(cents(17.34), shares.values.sumOf { cents(it) })
    }

    @Test
    fun `#997 — a pay-less offer's component gets nothing and no longer dilutes its siblings`() {
        val paid = drop("d1", 400L, store = "Target").copy(mintedByOfferHash = "o1")
        val payless = drop("d2", 410L, store = "H-E-B").copy(mintedByOfferHash = "o2")
        val j = multiOfferJob(
            listOf(Triple("o1", 10.00, listOf("Target")), Triple("o2", null, listOf("H-E-B"))),
            listOf(paid, payless),
        )
        val plan = closePlan(j, listOf(paid, payless), provenComplete = true)
        assertEquals(10.00, plan.resultFor("d1").share!!, 1e-9)
        val other = plan.resultFor("d2")
        assertNull("a pay-less quote stamps nothing", other.share)
        assertTrue("…and says so (the FIX-6 WARN signal)", other.eligibleButUnsplit)
        assertEquals(false, other.ownOfferPayPresent)
    }

    @Test
    fun `#997 degrade pin — no store evidence anywhere falls back to the pooled split, bit-exact`() {
        // A recovered / pre-#997 snapshot: no offer store hints, no drop stores, no lineage. The whole
        // ladder degrades to the pre-#997 job-wide equal split — never a mixed basis, never null.
        val drops = listOf(drop("d1", 400L), drop("d2", 410L), drop("d3", 420L))
        val j = multiOfferJob(
            listOf(Triple("o1", 10.45, emptyList()), Triple("o2", 16.55, emptyList()), Triple("o3", 20.20, emptyList())),
            drops,
        )
        val plan = closePlan(j, drops, provenComplete = true)
        assertEquals("pooled 47.20/3, remainder to last", 15.73, plan.resultFor("d1").share!!, 1e-9)
        assertEquals(15.73, plan.resultFor("d2").share!!, 1e-9)
        assertEquals(15.74, plan.resultFor("d3").share!!, 1e-9)
        assertEquals(OfferPayAttribution.JOB_POOLED, plan.resultFor("d1").arm)
        assertTrue(plan.degrades.any { it.arm == OfferPayAttribution.JOB_POOLED && it.offers == 3 && it.drops == 3 })
        assertEquals(0, plan.unattributedOffers)
    }

    // ---- the addendum: consolidation redistribution + empty-partition observability ----

    @Test
    fun `#996-997 — a CONSOLIDATED offer's quote follows its customer onto the surviving drop`() {
        // Offer o2's sole order collapsed onto o1's drop (same customer, #498 resume-collapse) and the
        // #996 shrink then retired o2's placeholder — leaving o2 with an empty partition. Pre-addendum
        // its $9.00 vanished, while the POOLED degrade on the identical shape paid it out: more money
        // for less information. The pickup side names the customer, exactly as #749's coverage arm.
        val d1 = drop("d1", 400L, store = "Mama Margies").copy(customerNameHash = "cust", mintedByOfferHash = "o1")
        val tbd = placeholder("d2", offerHash = "o2")
        val p1 = pickup("p1", "Mama Margies", "cust")
        val p2 = pickup("p2", "Raising Cane's", "cust")
        val j = multiOfferJob(
            listOf(Triple("o1", 13.10, listOf("Mama Margies")), Triple("o2", 9.00, listOf("Raising Cane's"))),
            listOf(d1, tbd, p1, p2),
        )
        val plan = closePlan(j, listOf(p1, p2, d1), provenComplete = true)
        val r = plan.resultFor("d1")
        assertEquals("the drop that carried BOTH orders gets both quotes", 22.10, r.share!!, 1e-9)
        assertEquals(OfferPayAttribution.CONSOLIDATED_CUSTOMER, r.arm)
        assertNull("two offers backed it → no single attributed hash", r.attributedOfferHash)
        assertEquals("nothing went homeless", 0, plan.unattributedOffers)
        assertTrue(plan.degrades.any { it.arm == OfferPayAttribution.CONSOLIDATED_CUSTOMER })
    }

    @Test
    fun `#997 — an UNMATCHABLE dropless offer stays unattributed and is COUNTED for the WARN`() {
        // Same shape, but the pickups never resolved a customer (a hash-less bin-scan pickup), so there
        // is no evidence to move o2's quote on. Fail-null beats fail-wrong — and the residual must be
        // observable, which the per-drop unsplit signal structurally cannot do (o2 has no minting task).
        val d1 = drop("d1", 400L, store = "Mama Margies").copy(customerNameHash = "cust", mintedByOfferHash = "o1")
        val tbd = placeholder("d2", offerHash = "o2")
        val p1 = pickup("p1", "Mama Margies", customer = null)
        val p2 = pickup("p2", "Raising Cane's", customer = null)
        val j = multiOfferJob(
            listOf(Triple("o1", 13.10, listOf("Mama Margies")), Triple("o2", 9.00, listOf("Raising Cane's"))),
            listOf(d1, tbd, p1, p2),
        )
        val plan = closePlan(j, listOf(p1, p2, d1), provenComplete = true)
        assertEquals(13.10, plan.resultFor("d1").share!!, 1e-9)
        assertEquals("one accepted quote matched no drop", 1, plan.unattributedOffers)
    }

    @Test
    fun `#997 — an AMBIGUOUS consolidated customer is never guessed onto one component`() {
        // o3's customer appears on drops in TWO different components — there is no single right answer,
        // so its quote stays unattributed rather than being guessed (#745).
        val d1 = drop("d1", 400L, store = "Target").copy(customerNameHash = "cust", mintedByOfferHash = "o1")
        val d2 = drop("d2", 410L, store = "H-E-B").copy(customerNameHash = "cust", mintedByOfferHash = "o2")
        val p1 = pickup("p1", "Sonic", "cust")
        val j = multiOfferJob(
            listOf(
                Triple("o1", 10.00, listOf("Target")),
                Triple("o2", 20.00, listOf("H-E-B")),
                Triple("o3", 5.00, listOf("Sonic")),
            ),
            listOf(d1, d2, p1),
        )
        val plan = closePlan(j, listOf(p1, d1, d2), provenComplete = true)
        assertEquals(10.00, plan.resultFor("d1").share!!, 1e-9)
        assertEquals(20.00, plan.resultFor("d2").share!!, 1e-9)
        assertEquals(1, plan.unattributedOffers)
    }

    @Test
    fun `#997 — redistribution is gated on the completeness proof`() {
        // Mid-flight, an offer with no drop yet is simply still being worked — never a consolidation.
        val d1 = drop("d1", 400L, store = "Mama Margies").copy(customerNameHash = "cust", mintedByOfferHash = "o1")
        val tbd = placeholder("d2", offerHash = "o2")
        val p1 = pickup("p1", "Mama Margies", "cust")
        val p2 = pickup("p2", "Raising Cane's", "cust")
        val j = multiOfferJob(
            listOf(Triple("o1", 13.10, listOf("Mama Margies")), Triple("o2", 9.00, listOf("Raising Cane's"))),
            listOf(d1, tbd, p1, p2),
        )
        // Unproven: the TBD placeholder is still in the denominator and o2 still owns it.
        val plan = closePlan(j, listOf(p1, p2, d1), provenComplete = false)
        assertEquals(13.10, plan.resultFor("d1").share!!, 1e-9)
        assertEquals(9.00, plan.resultFor("d2").share!!, 1e-9)
        assertEquals(0, plan.unattributedOffers)
    }

    @Test
    fun `a receipt-suppressed close measures nothing at all`() {
        val d1 = drop("d1", 400L, store = "Target")
        val j = job(12.95, listOf(d1), stores = listOf("Target"))
        val plan = closePlan(j, listOf(d1), provenComplete = true, suppressed = true)
        val r = plan.resultFor("d1")
        assertNull(r.share)
        assertFalse(r.eligibleButUnsplit)
        assertNull(r.denominator)
        assertNull(plan.denominator)
        assertEquals(0, plan.unattributedOffers)
        assertTrue(plan.degrades.isEmpty())
    }
}
