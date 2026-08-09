package cloud.trotter.dashbuddy.domain.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #691 — the pure eligibility + share policy extracted out of EffectMap (FIX 5), plus the two money
 * fixes layered on it: the #996 eligible-owed filter (a proven-complete job stops diluting its split
 * with a never-activated placeholder) and the #997 per-offer partition (a job that absorbed N
 * separately-accepted offers splits each offer's OWN quote across its OWN drops).
 */
class OfferPayFallbackTest {

    private fun drop(id: String, completedAt: Long?) = Task(
        taskId = id, jobId = "J", phase = TaskPhase.DROPOFF,
        customerNameHash = "c-$id", startedAt = 100L, completedAt = completedAt,
    )

    /** A never-activated customer-TBD dropoff placeholder — the #996 consolidation artifact. */
    private fun placeholder(id: String, offerHash: String? = null) = Task(
        taskId = id, jobId = "J", phase = TaskPhase.DROPOFF,
        customerNameHash = null, customerAddressHash = null, startedAt = 100L, completedAt = null,
        mintedByOfferHash = offerHash,
    )

    private fun job(offerPay: Double?, tasks: List<Task>) = Job(
        jobId = "J", offerStoreHint = emptyList(), parentOfferHash = null,
        acceptedOffers = listOf(AcceptedOfferEconomics(offerHash = "h", payAmount = offerPay, acceptedAt = 50L)),
        tasks = tasks, startedAt = 50L,
    )

    /** A job that absorbed several accepts — one [AcceptedOfferEconomics] per `hash to pay`. */
    private fun multiOfferJob(offers: List<Pair<String, Double?>>, tasks: List<Task>) = Job(
        jobId = "J", offerStoreHint = emptyList(), parentOfferHash = offers.firstOrNull()?.first,
        acceptedOffers = offers.mapIndexed { i, (hash, pay) ->
            AcceptedOfferEconomics(offerHash = hash, payAmount = pay, acceptedAt = 50L + i)
        },
        tasks = tasks, startedAt = 50L,
    )

    private fun shareOf(
        job: Job,
        taskId: String,
        recentTasks: List<Task> = emptyList(),
        provenComplete: Boolean = false,
        requireFinalShape: Boolean = false,
    ) = OfferPayFallback.shareFor(
        job, recentTasks, taskId,
        suppressedByReceipt = false,
        requireFinalShape = requireFinalShape,
        jobProvenComplete = provenComplete,
    )

    private fun cents(v: Double): Long = Math.round(v * 100.0)

    // ---- #691 baseline (unchanged behaviour) ----

    @Test
    fun `a receipt suppresses the estimate entirely`() {
        val j = job(12.95, listOf(drop("d1", 400L)))
        val r = OfferPayFallback.shareFor(
            j, emptyList(), "d1",
            suppressedByReceipt = true, requireFinalShape = false, jobProvenComplete = true,
        )
        assertNull(r.share)
        assertFalse("suppressed is not an unsplit-miss", r.eligibleButUnsplit)
    }

    @Test
    fun `final-shape gate blocks a mid-stack drop and stamps the last open drop`() {
        val j = job(12.95, listOf(drop("d1", null), drop("d2", null)))
        // d1 minting while d2 still owed → not final shape → no stamp.
        val mid = shareOf(j, "d1", requireFinalShape = true)
        assertNull("mid-stack → no stamp", mid.share)
        assertFalse(mid.eligibleButUnsplit)

        // d2 is the last open owed drop once d1 completed.
        val j2 = job(12.95, listOf(drop("d1", 380L), drop("d2", null)))
        val last = shareOf(j2, "d2", requireFinalShape = true)
        assertEquals(6.47, last.share!!, 1e-9)
    }

    @Test
    fun `close-out (no final-shape requirement) stamps every owed drop's equal share`() {
        val j = job(12.95, listOf(drop("d1", 400L), drop("d2", 410L)))
        val a = shareOf(j, "d1")
        val b = shareOf(j, "d2")
        assertEquals(6.48, a.share!!, 1e-9)
        assertEquals(6.47, b.share!!, 1e-9)
    }

    @Test
    fun `a pay-less offer is eligible-but-unsplit (the WARN signal)`() {
        val j = job(offerPay = null, tasks = listOf(drop("d1", 400L)))
        val r = shareOf(j, "d1")
        assertNull(r.share)
        assertTrue("eligible but the split yielded nothing", r.eligibleButUnsplit)
    }

    @Test
    fun `a minting task outside the owed set is eligible-but-unsplit`() {
        val j = job(12.95, listOf(drop("d1", 400L)))
        val r = shareOf(j, "ghost")
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
        // And the split reads that union: the survivor's share is total/2, not the full quote.
        val r = shareOf(j, "d1", recentTasks = listOf(survivor, unassigned, foreign))
        assertEquals(6.48, r.share!!, 1e-9)
    }

    // ---- #996: the eligible-owed filter ----

    @Test
    fun `#996 — a proven-complete same-customer job stamps the FULL quote on its one physical drop`() {
        // The 08-07 field shape (job …-313, seqs 1496→1506): ONE offer quoting $13.10 over 2 orders
        // (Mama Margies + Raising Cane's) for ONE customer. Both pickups confirmed; only one physical
        // drop ever activates, so the second placeholder stays customer-TBD forever. Pre-#996 the
        // denominator counted it and the drop folded 13.10/2 = $6.55 — and the dash's reconciliation
        // read reported $52.85 − attributed $46.30 = exactly the missing $6.55.
        val delivered = drop("d1", 400L).copy(mintedByOfferHash = "h")
        val tbd = placeholder("d2", offerHash = "h")
        val j = job(13.10, listOf(delivered, tbd))

        val r = shareOf(j, "d1", recentTasks = listOf(delivered), provenComplete = true)
        assertEquals("the consolidation artifact leaves the denominator", 13.10, r.share!!, 1e-9)
        assertEquals(2, r.quotedOwed)
        assertEquals(1, r.eligibleOwed)
    }

    @Test
    fun `#996 conservatism pin — completion NOT proven reproduces today's diluted split bit-exactly`() {
        // Same shape, unproven completion (abandon / endSession bail / mid-flight): the filter is OFF
        // and the pre-#996 number stands to the cent. This is the pin that stops the fix leaking into
        // a job that might still owe a drop.
        val delivered = drop("d1", 400L).copy(mintedByOfferHash = "h")
        val tbd = placeholder("d2", offerHash = "h")
        val j = job(13.10, listOf(delivered, tbd))

        val r = shareOf(j, "d1", recentTasks = listOf(delivered), provenComplete = false)
        assertEquals("today's behaviour, unchanged", 6.55, r.share!!, 1e-9)
        assertEquals(2, r.quotedOwed)
        assertEquals("no filter applied", 2, r.eligibleOwed)
    }

    @Test
    fun `#996 doctrine pin — an UNASSIGNED order keeps diluting even at a proven-complete close`() {
        // The owedDropoffs doctrine: an unassigned order was QUOTED but may not be paid, so its share
        // must never be re-attributed to a sibling (that would over-count). The filter targets only
        // never-activated TBD placeholders, and an unassigned drop is excluded from it by construction
        // — whether it carries the inline-abandon shape (completedAt null) or the #752 retro shape.
        val survivor = drop("d1", 400L)
        val unassignedInline = drop("d2", null).copy(customerNameHash = null, unassignedAt = 900L)
        val j = job(12.95, tasks = listOf(survivor))

        val r = shareOf(
            j, "d1",
            recentTasks = listOf(survivor, unassignedInline),
            provenComplete = true,
        )
        assertEquals("the unassigned order still divides the quote", 6.48, r.share!!, 1e-9)
        assertEquals(2, r.eligibleOwed)
    }

    // ---- #997: the per-offer partition ----

    @Test
    fun `#997 — a job absorbing three accepts stamps each drop its OWN offer's pay`() {
        // The 08-06 field shape (job …-299, seqs 1442 / 1445 / 1455): three separately-accepted offers
        // merged into one job — Target $10.45, Target $16.55, H-E-B $20.20. Pre-#997 all three folded
        // the pooled 47.20/3 → $15.73 / $15.73 / $15.74 (per-drop error up to $5.28, 50 % relative),
        // invisible at session level because Σ is identical either way.
        val d1 = drop("d1", 400L).copy(mintedByOfferHash = "o1")
        val d2 = drop("d2", 410L).copy(mintedByOfferHash = "o2")
        val d3 = drop("d3", 420L).copy(mintedByOfferHash = "o3")
        val j = multiOfferJob(
            offers = listOf("o1" to 10.45, "o2" to 16.55, "o3" to 20.20),
            tasks = listOf(d1, d2, d3),
        )
        val recent = listOf(d1, d2, d3)

        assertEquals(10.45, shareOf(j, "d1", recent, provenComplete = true).share!!, 1e-9)
        assertEquals(16.55, shareOf(j, "d2", recent, provenComplete = true).share!!, 1e-9)
        assertEquals(20.20, shareOf(j, "d3", recent, provenComplete = true).share!!, 1e-9)
        assertTrue(shareOf(j, "d1", recent, provenComplete = true).perOffer)

        // Session Σ invariant: per-offer only REDISTRIBUTES within the job — the job total is
        // unchanged, so period gross / unattributedPay cannot move.
        val sum = listOf("d1", "d2", "d3").sumOf { cents(shareOf(j, it, recent, provenComplete = true).share!!) }
        assertEquals("Σ = the accepted quotes, to the cent", cents(47.20), sum)
    }

    @Test
    fun `#997 — the per-offer split is cents-exact WITHIN each offer's own partition`() {
        // Two accepts, each quoting an amount that does not divide evenly across its own two drops.
        // The remainder-to-last reconciliation runs per partition, so each offer's Σ is exact and the
        // job's Σ is the sum of the quotes — never a cent more.
        val a1 = drop("a1", 400L).copy(mintedByOfferHash = "o1")
        val a2 = drop("a2", 401L).copy(mintedByOfferHash = "o1")
        val b1 = drop("b1", 402L).copy(mintedByOfferHash = "o2")
        val b2 = drop("b2", 403L).copy(mintedByOfferHash = "o2")
        val j = multiOfferJob(listOf("o1" to 10.01, "o2" to 7.33), listOf(a1, a2, b1, b2))
        val recent = listOf(a1, a2, b1, b2)

        val shares = listOf("a1", "a2", "b1", "b2").associateWith { shareOf(j, it, recent, provenComplete = true).share!! }
        assertEquals("offer o1 splits exactly", cents(10.01), cents(shares.getValue("a1")) + cents(shares.getValue("a2")))
        assertEquals("offer o2 splits exactly", cents(7.33), cents(shares.getValue("b1")) + cents(shares.getValue("b2")))
        assertEquals(cents(17.34), shares.values.sumOf { cents(it) })
    }

    @Test
    fun `#997 — a pay-less offer's partition gets nothing and no longer dilutes its siblings`() {
        // Pre-#997 the pooled arm spread the PAID offer's quote across the pay-less offer's drops too
        // (both drops folded $5.00 of a $10.00 quote). Per-offer, the paid drop keeps its whole quote
        // and the pay-less one is an eligible-but-unsplit WARN — the honest "we don't know" answer.
        val paid = drop("d1", 400L).copy(mintedByOfferHash = "o1")
        val payless = drop("d2", 410L).copy(mintedByOfferHash = "o2")
        val j = multiOfferJob(listOf("o1" to 10.00, "o2" to null), listOf(paid, payless))
        val recent = listOf(paid, payless)

        assertEquals(10.00, shareOf(j, "d1", recent, provenComplete = true).share!!, 1e-9)
        val other = shareOf(j, "d2", recent, provenComplete = true)
        assertNull("a pay-less offer's drop stamps nothing", other.share)
        assertTrue("…and says so (the FIX-6 WARN signal)", other.eligibleButUnsplit)
    }

    @Test
    fun `#997 degrade pin — ANY unresolvable lineage falls back to the pooled split, bit-exact`() {
        // A recovered / pre-#997 snapshot decodes every drop with a null mintedByOfferHash, and a
        // partially-stamped job (one drop's hash names no accepted offer) is just as unmappable. Both
        // degrade WHOLESALE to the pre-#997 job-wide equal split — never a mixed basis, never null
        // (that would un-attribute money we attribute today).
        val nullLineage = listOf(drop("d1", 400L), drop("d2", 410L), drop("d3", 420L))
        val jNull = multiOfferJob(listOf("o1" to 10.45, "o2" to 16.55, "o3" to 20.20), nullLineage)
        val rNull = shareOf(jNull, "d1", nullLineage, provenComplete = true)
        assertEquals("pooled 47.20/3, remainder to last", 15.73, rNull.share!!, 1e-9)
        assertFalse(rNull.perOffer)
        assertEquals(15.74, shareOf(jNull, "d3", nullLineage, provenComplete = true).share!!, 1e-9)

        // One drop stamped with a hash that resolves to no accepted offer (lineage loss).
        val stray = listOf(
            drop("d1", 400L).copy(mintedByOfferHash = "o1"),
            drop("d2", 410L).copy(mintedByOfferHash = "gone"),
            drop("d3", 420L).copy(mintedByOfferHash = "o3"),
        )
        val jStray = multiOfferJob(listOf("o1" to 10.45, "o2" to 16.55, "o3" to 20.20), stray)
        val rStray = shareOf(jStray, "d1", stray, provenComplete = true)
        assertFalse("an unresolvable drop degrades the WHOLE split", rStray.perOffer)
        assertEquals(15.73, rStray.share!!, 1e-9)
    }

    @Test
    fun `#996 and #997 compose — a proven-complete multi-offer job with a consolidated placeholder`() {
        // Offer o1 quoted two orders (one delivered drop + one TBD placeholder that consolidated onto
        // it); offer o2 was a single-order add-on. At a proven-complete close o1's placeholder leaves
        // the denominator, so o1's whole quote lands on its one physical drop while o2 is untouched.
        val d1 = drop("d1", 400L).copy(mintedByOfferHash = "o1")
        val tbd = placeholder("d2", offerHash = "o1")
        val d3 = drop("d3", 420L).copy(mintedByOfferHash = "o2")
        val j = multiOfferJob(listOf("o1" to 13.10, "o2" to 9.00), listOf(d1, tbd, d3))
        val recent = listOf(d1, d3)

        assertEquals(13.10, shareOf(j, "d1", recent, provenComplete = true).share!!, 1e-9)
        assertEquals(9.00, shareOf(j, "d3", recent, provenComplete = true).share!!, 1e-9)
        assertEquals(
            "Σ = the accepted quotes, to the cent",
            cents(22.10),
            listOf("d1", "d3").sumOf { cents(shareOf(j, it, recent, provenComplete = true).share!!) },
        )

        // Unproven: o1 still dilutes across its placeholder (the conservative arm), o2 unaffected.
        assertEquals(6.55, shareOf(j, "d1", recent, provenComplete = false).share!!, 1e-9)
        assertEquals(9.00, shareOf(j, "d3", recent, provenComplete = false).share!!, 1e-9)
    }

    @Test
    fun `eligibleOwedDropoffs is identity when completion is unproven and drops only TBD placeholders`() {
        val delivered = drop("d1", 400L)
        val tbd = placeholder("d2")
        val unassigned = drop("d3", null).copy(customerNameHash = null, unassignedAt = 900L)
        val owed = listOf(delivered, tbd, unassigned)

        assertEquals(owed, OfferPayFallback.eligibleOwedDropoffs(owed, jobProvenComplete = false))
        assertEquals(
            listOf("d1", "d3"),
            OfferPayFallback.eligibleOwedDropoffs(owed, jobProvenComplete = true).map { it.taskId },
        )
    }
}
