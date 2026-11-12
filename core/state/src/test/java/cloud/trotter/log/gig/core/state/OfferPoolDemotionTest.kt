package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.DeclineEvidence
import cloud.trotter.log.gig.domain.state.PoolEntryVia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1119 council round 6 (addendum R6-4 tests 1–5 and 7): a confirm-decline sheet sighting DEMOTES a leaving offer —
 * kept and recorded, never a match candidate, a 60 s tie guard for same-store work, `OFFER_DECLINED` (inferred) at the
 * shift end — unless a card frame of the same presentation came back after the last sighting. No recency anywhere.
 *
 * Non-vacuity (shown against the old production behaviour, see the PR report): test 1 fails on the click-less
 * accept inference (the phantom B is linked, `OFFER_ACCEPTED` on B), and test 2 fails the same way (B is linked
 * through its leave straight to the pickup, so the work edge is no tie).
 */
class OfferPoolDemotionTest {

    private fun demotedOf(d: PoolDriver, hash: String) = d.region.offerPool.single { it.offer.offerHash == hash }.demoted

    // R6-4 test 1 — the 09-20 phantom, corrected truth.
    @Test fun `the 09-20 same-store phantom links nothing, and with no countdown nothing is demoted`() {
        // A: the older card at the store, with a sheet sighting (the 09-20 build parsed no countdown). B: the newest
        // card at the same store, no sighting — the phantom. The true accept's card was never seen.
        val d = PoolDriver().step(
            PF.card(1_000, "hA"), PF.sheet(3_000), PF.idle(5_000),
            PF.card(8_000, "hB"),
            PF.pickup(20_000),
        )
        assertTrue("never a link to the phantom", d.outcomes().none { it.type == AppEventType.OFFER_ACCEPTED })
        assertTrue(d.region.activeJob!!.acceptedOffers.isEmpty())
        assertFalse("no countdown parsed: the sighting cannot demote", demotedOf(d, "hA"))
        assertFalse(demotedOf(d, "hB"))
        val jobId = d.region.activeJob!!.jobId
        assertEquals(listOf(listOf(jobId), listOf(jobId)), d.region.offerPool.map { it.ambiguousJobIds })
    }

    private fun substitution(workDelayMs: Long): PoolDriver {
        // A: an older card at store X that expired by its timer, no sighting. B: the card the dasher really accepted,
        // whose decline sheet showed 6 s before it left; no card frame after the sheet, so B is demoted.
        val d = PoolDriver().step(
            PF.card(1_000, "hA", countdown = 30),
            PF.expiry(31_000, "hA", PF.instanceOf(1_000)),
            PF.card(40_000, "hB", countdown = 60),
            PF.sheet(44_000),
        )
        return if (workDelayMs == 0L) d.step(PF.pickup(50_000)) else d.step(PF.idle(50_000), PF.pickup(50_000 + workDelayMs))
    }

    // R6-4 test 2 — a false removal must not substitute the older same-store offer.
    @Test fun `work at the store of a demoted offer within 60 s is a tie over both, never a link to the older one`() {
        for (delay in listOf(0L, 60_000L)) {
            val d = substitution(delay)
            assertTrue("no link (delay $delay)", d.outcomes().none { it.type == AppEventType.OFFER_ACCEPTED })
            assertTrue("B demoted (delay $delay)", demotedOf(d, "hB"))
            assertEquals(PoolEntryVia.EXPIRY_TIMER, d.region.offerPool.single { it.offer.offerHash == "hA" }.via)
            val jobId = d.region.activeJob!!.jobId
            assertEquals(listOf(listOf(jobId), listOf(jobId)), d.region.offerPool.map { it.ambiguousJobIds })
        }
    }

    // R6-4 test 2, the documented residual: past the guard the older offer links.
    @Test fun `RESIDUAL - same-store work 61 s after a demoted leave links the older offer`() {
        val d = substitution(61_000L)
        assertEquals(listOf("hA"), d.region.activeJob!!.acceptedOffers.map { it.offerHash })
        assertTrue(demotedOf(d, "hB"))
    }

    // R6-4 test 3
    @Test fun `a card frame after the sheet is the sheet cancelled - not demoted, and it links`() {
        val d = PoolDriver().step(PF.card(1_000, "hA", countdown = 60), PF.sheet(5_000), PF.card(7_000, "hA", countdown = 54))
        d.step(PF.pickup(9_000))
        assertEquals(listOf("hA"), d.region.activeJob!!.acceptedOffers.map { it.offerHash })
        assertEquals(listOf(AppEventType.OFFER_ACCEPTED), d.outcomes().map { it.type })
    }

    // R6-4 test 4
    @Test fun `the last sighting governs repeated sightings`() {
        val last = PoolDriver().step(
            PF.card(1_000, "hA", countdown = 60), PF.sheet(3_000), PF.card(5_000, "hA", countdown = 56), PF.sheet(8_000),
            PF.idle(13_000),
        )
        assertTrue("a sighting after the card frame demotes", demotedOf(last, "hA"))
        val inverse = PoolDriver().step(
            PF.card(1_000, "hA", countdown = 60), PF.sheet(3_000), PF.sheet(6_000), PF.card(8_000, "hA", countdown = 53),
            PF.idle(11_000),
        )
        assertFalse("a card frame after the last sighting cancels it", demotedOf(inverse, "hA"))
    }

    // R6-4 test 5, split by #1610: the frame-leave half is unchanged.
    @Test fun `a frame leave with no countdown or the countdown nearly out never demotes`() {
        val noCountdown = PoolDriver().step(PF.card(1_000, "hA"), PF.sheet(3_000), PF.idle(5_000))
        assertFalse(demotedOf(noCountdown, "hA"))
        // 20 s countdown ends at 21 000; the leave at 18 500 has 2.5 s left (inside the 3 s slack).
        val nearlyOut = PoolDriver().step(PF.card(1_000, "hA", countdown = 20), PF.sheet(15_000), PF.idle(18_500))
        assertFalse(demotedOf(nearlyOut, "hA"))
    }

    @Test fun `a timer leave with no sighting never demotes`() {
        val timerOnly = PoolDriver().step(PF.card(1_000, "hA", countdown = 60), PF.expiry(61_000, "hA", PF.instanceOf(1_000)))
        assertEquals(PoolEntryVia.EXPIRY_TIMER, timerOnly.region.offerPool.single().via)
        assertFalse(demotedOf(timerOnly, "hA"))
    }

    // #1610 R1 (changed oracle: this leave did not demote before): the sheet stood until the timer fired.
    @Test fun `a timer leave with a sighting and no card back demotes`() {
        val timer = PoolDriver().step(
            PF.card(1_000, "hA", countdown = 60), PF.sheet(10_000), PF.expiry(16_000, "hA", PF.instanceOf(1_000)),
        )
        assertEquals(PoolEntryVia.EXPIRY_TIMER, timer.region.offerPool.single().via)
        assertTrue(demotedOf(timer, "hA"))
    }

    // R6-4 test 7
    @Test fun `a demoted entry that never links ends the shift an inferred decline, once`() {
        val d = PoolDriver().step(PF.card(1_000, "hA", countdown = 60), PF.sheet(3_000), PF.idle(5_000))
        assertTrue(demotedOf(d, "hA"))
        assertTrue(d.outcomes().isEmpty())
        d.endShift(30_000)
        val out = d.outcomePayloads().single()
        assertEquals(AppEventType.OFFER_DECLINED, out.outcome)
        assertEquals(DeclineEvidence.SHEET_WINDOW, out.declineEvidence)
        assertEquals(5_000L, out.decidedAt)
        assertEquals(1, d.outcomeOf(PF.instanceOf(1_000)).size)
    }
}
