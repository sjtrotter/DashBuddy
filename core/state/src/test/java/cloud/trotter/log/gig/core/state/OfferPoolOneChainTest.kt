package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.DeclineEvidence
import cloud.trotter.log.gig.domain.model.event.payload.OfferPayload
import cloud.trotter.log.gig.domain.model.offer.OfferScope
import cloud.trotter.log.gig.domain.state.AcceptedOfferEconomics
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.Job
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.PendingOffer
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.PoolEntryVia
import cloud.trotter.log.gig.domain.state.PooledOffer
import cloud.trotter.log.gig.domain.state.Session
import cloud.trotter.log.gig.domain.state.Task
import cloud.trotter.log.gig.domain.state.TaskPhase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber

/**
 * #1610: a shift where one store chain has most offers. R1 — a timer leave whose decline sheet stood until the
 * countdown ran out is demoted (no card frame after the last sighting). E4′ — a demoted entry guards a sole
 * candidate only when that candidate is an older rival: a STANDALONE sole candidate presented strictly after the
 * demoted entry left links. Synthetic data only.
 *
 * Non-vacuity (on master `5e4a2bcf1`): the 11-07 shape ties every job (the timer-left X stays live); the timer-leave
 * demotion cases fail on `demoted == false`; the E4′ waiver cases tie or absorb on the guard.
 */
class OfferPoolOneChainTest {

    private val warns = mutableListOf<String>()
    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority == android.util.Log.WARN && message.startsWith("#1119")) warns += message
        }
    }

    @Before fun plant() = Timber.plant(tree)
    @After fun uproot() = Timber.uproot(tree)

    private fun demotedOf(d: PoolDriver, hash: String) = d.region.offerPool.single { it.offer.offerHash == hash }.demoted
    private fun linked(d: PoolDriver) = d.region.activeJob!!.acceptedOffers.map { it.offerHash }
    private fun isTie(d: PoolDriver) =
        d.region.activeJob!!.acceptedOffers.isEmpty() && d.region.offerPool.any { it.ambiguousJobIds.isNotEmpty() }

    /** A whole delivery from the pickup at [at]: arrive, drop off, then two idle frames that complete it. */
    private fun PoolDriver.delivery(at: Long, customer: String): PoolDriver = step(
        PF.pickup(at),
        PF.pickup(at + 1_000, flow = Flow.TaskPickupArrived),
        PF.dropoff(at + 10_000, customer),
        PF.dropoff(at + 15_000, customer, flow = Flow.TaskDropoffArrived),
        PF.idle(at + 20_000),
        PF.idle(at + 35_000),
    )

    // Test 1 — the 11-07 shape.
    @Test fun `a one-chain shift links every job when the timer-left decline is demoted and the newer card waives the guard`() {
        val d = PoolDriver()
        // X: the sheet stood until the timer fired 1.7 s later, no card frame after it.
        d.step(PF.card(1_000, "hX", countdown = 30), PF.sheet(29_300), PF.expiry(31_000, "hX", PF.instanceOf(1_000)))
        assertTrue("X demoted by the sheet standing until the timer", demotedOf(d, "hX"))
        // A: left without a decision; the pickup at the chain links A alone.
        d.step(PF.idle(32_000), PF.card(40_000, "hA", countdown = 30), PF.idle(50_000))
        d.delivery(60_000, "cust-1")
        assertTrue("A linked", d.outcomeOf(PF.instanceOf(40_000)).single().type == AppEventType.OFFER_ACCEPTED)
        // D sheet-declined (demoted at a frame leave), B presented 28 s after D left, pickup 40 s after D left.
        d.step(PF.card(100_000, "hD", countdown = 60), PF.sheet(105_000), PF.idle(110_000))
        assertTrue(demotedOf(d, "hD"))
        d.step(PF.card(138_000, "hB", countdown = 30), PF.idle(145_000))
        d.delivery(150_000, "cust-2")
        assertTrue("B linked", d.outcomeOf(PF.instanceOf(138_000)).single().type == AppEventType.OFFER_ACCEPTED)
        // Once more: D2 declined 51 s before the pickup that links B2.
        d.step(PF.card(200_000, "hD2", countdown = 60), PF.sheet(204_000), PF.idle(209_000))
        d.step(PF.card(215_000, "hB2", countdown = 30), PF.idle(225_000))
        d.delivery(260_000, "cust-3")
        assertTrue("B2 linked", d.outcomeOf(PF.instanceOf(215_000)).single().type == AppEventType.OFFER_ACCEPTED)

        d.endShift(400_000)
        for (declined in listOf(1_000L, 100_000L, 200_000L)) {
            val out = d.outcomeOf(PF.instanceOf(declined)).map { it.payload as OfferPayload }
            assertEquals("declined at $declined", listOf(AppEventType.OFFER_DECLINED), out.map { it.outcome })
            assertEquals(DeclineEvidence.SHEET_WINDOW, out.single().declineEvidence)
        }
        for (accepted in listOf(40_000L, 138_000L, 215_000L)) {
            assertEquals(listOf(AppEventType.OFFER_ACCEPTED), d.outcomeOf(PF.instanceOf(accepted)).map { it.type })
        }
        assertTrue("no tie WARN: $warns", warns.none { "tie" in it })
    }

    // Test 2 — the fielded last-second accepts: the timer fires, the pickup follows 1.6–8.1 s later.
    @Test fun `a timer leave with no sighting stays live and links`() {
        for (delay in listOf(1_600L, 8_100L)) {
            val d = PoolDriver().step(PF.card(1_000, "hA", countdown = 30), PF.expiry(31_000, "hA", PF.instanceOf(1_000)))
            assertFalse(demotedOf(d, "hA"))
            d.step(PF.pickup(31_000 + delay))
            assertEquals("delay $delay", listOf("hA"), linked(d))
        }
    }

    // Test 3
    @Test fun `a timer leave with a sighting then the card back stays live and links`() {
        val d = PoolDriver().step(
            PF.card(1_000, "hA", countdown = 30), PF.sheet(20_000), PF.card(22_000, "hA", countdown = 9),
            PF.expiry(31_000, "hA", PF.instanceOf(1_000)),
        )
        assertEquals(PoolEntryVia.EXPIRY_TIMER, d.region.offerPool.single().via)
        assertFalse(demotedOf(d, "hA"))
        d.step(PF.pickup(33_000))
        assertEquals(listOf("hA"), linked(d))
    }

    @Test fun `on a timer leave the last sighting governs repeated sightings`() {
        val last = PoolDriver().step(
            PF.card(1_000, "hA", countdown = 30), PF.sheet(10_000), PF.card(12_000, "hA", countdown = 19), PF.sheet(20_000),
            PF.expiry(31_000, "hA", PF.instanceOf(1_000)),
        )
        assertTrue("a sighting after the card frame demotes", demotedOf(last, "hA"))
        val inverse = PoolDriver().step(
            PF.card(1_000, "hA", countdown = 30), PF.sheet(10_000), PF.sheet(15_000), PF.card(20_000, "hA", countdown = 11),
            PF.expiry(31_000, "hA", PF.instanceOf(1_000)),
        )
        assertFalse("a card frame after the last sighting cancels it", demotedOf(inverse, "hA"))
    }

    @Test fun `a timer leave with a sighting and no parsed countdown demotes`() {
        val d = PoolDriver().step(PF.card(1_000, "hA"), PF.sheet(100_000), PF.expiry(121_000, "hA", PF.instanceOf(1_000)))
        assertEquals(PoolEntryVia.EXPIRY_TIMER, d.region.offerPool.single().via)
        assertTrue(demotedOf(d, "hA"))
    }

    // Test 4 — E4′ keeps the guard for an older rival (frame-demoted and timer-demoted).
    @Test fun `an older live candidate beside a demoted entry within 60 s still ties`() {
        val frame = PoolDriver().step(
            PF.card(1_000, "hA"), PF.idle(5_000),
            PF.card(10_000, "hB", countdown = 60), PF.sheet(14_000), PF.idle(16_000),
        )
        assertTrue(demotedOf(frame, "hB"))
        frame.step(PF.pickup(30_000))
        assertTrue(isTie(frame))

        val timer = PoolDriver().step(
            PF.card(1_000, "hA"), PF.idle(5_000),
            PF.card(10_000, "hB", countdown = 30), PF.sheet(38_000), PF.expiry(40_000, "hB", PF.instanceOf(10_000)),
        )
        assertTrue(demotedOf(timer, "hB"))
        timer.step(PF.pickup(50_000))
        assertTrue(isTie(timer))
        assertTrue(timer.outcomes().none { it.type == AppEventType.OFFER_ACCEPTED })
    }

    @Test fun `a candidate presented at exactly the instant the demoted entry left keeps the guard`() {
        // B replaces D on screen: D leaves REPLACED at 8 000, the instant B is presented.
        val d = PoolDriver().step(
            PF.card(1_000, "hD", countdown = 60), PF.sheet(5_000), PF.card(8_000, "hB", countdown = 30), PF.idle(12_000),
        )
        val dEntry = d.region.offerPool.single { it.offer.offerHash == "hD" }
        assertTrue(dEntry.demoted)
        assertEquals(8_000L, dEntry.leftAt)
        assertEquals(8_000L, d.region.offerPool.single { it.offer.offerHash == "hB" }.offer.presentedAt)
        d.step(PF.pickup(20_000))
        assertTrue(isTie(d))
    }

    // Test 5
    @Test fun `an add-on candidate presented after the demoted entry left keeps the guard`() {
        val d = PoolDriver().step(
            PF.card(1_000, "hD", countdown = 60), PF.sheet(5_000), PF.idle(8_000),
            PF.card(10_000, "hC", scope = OfferScope.ADD_ON), PF.idle(14_000),
        )
        assertTrue(demotedOf(d, "hD"))
        d.step(PF.pickup(20_000))
        assertTrue(isTie(d))
    }

    @Test fun `a standalone candidate presented after the demoted entry left links`() {
        val d = PoolDriver().step(
            PF.card(1_000, "hD", countdown = 60), PF.sheet(5_000), PF.idle(8_000),
            PF.card(10_000, "hB"), PF.idle(14_000),
        )
        assertTrue(demotedOf(d, "hD"))
        d.step(PF.pickup(20_000))
        assertEquals(listOf("hB"), linked(d))
        assertTrue(warns.isEmpty())
    }

    // Test 6
    @Test fun `two live standalone cards at one chain still tie, even both newer than a demoted entry`() {
        val d = PoolDriver().step(
            PF.card(1_000, "hD", countdown = 60), PF.sheet(5_000), PF.idle(8_000),
            PF.card(10_000, "hA"), PF.idle(14_000), PF.card(15_000, "hB"), PF.idle(18_000),
        )
        d.step(PF.pickup(20_000))
        assertTrue(isTie(d))
        assertEquals(1, warns.size)
    }

    private fun pooled(hash: String, presentedAt: Long, leftAt: Long, demoted: Boolean) = PooledOffer(
        offer = PendingOffer(hash, ParsedFields.OfferFields(parsedOffer = PF.parsedOffer(hash)), presentedAt,
            returnFlow = Flow.Idle, instanceId = PF.instanceOf(presentedAt)),
        leftAt = leftAt, via = PoolEntryVia.FRAME, sessionId = "S1", candidateStoreKeys = setOf("store a"), demoted = demoted,
    )

    private fun staleJobState(pool: List<PooledOffer>) = PF.online(PlatformRegion(
        PF.platform, mode = Mode.Online, session = Session("S1", startedAt = 0L),
        activeJob = Job("stale-job", listOf("Store B"), "old-offer", startedAt = 100L, tasks = listOf(
            Task("sp", "stale-job", TaskPhase.PICKUP, expectedStoreHint = "Store B", startedAt = 100L),
            Task("sd", "stale-job", TaskPhase.DROPOFF, startedAt = 100L),
        ), acceptedOffers = listOf(AcceptedOfferEconomics("old-offer", payAmount = 8.0, acceptedAt = 100L))),
        lastActedFlow = Flow.Idle, offerPool = pool,
    ))

    @Test fun `the stale-job edge reads the same waived guard list`() {
        val waived = PoolDriver(staleJobState(listOf(
            pooled("hD", presentedAt = 200L, leftAt = 400L, demoted = true),
            pooled("hB", presentedAt = 500L, leftAt = 700L, demoted = false),
        ))).step(PF.pickup(1_000))
        assertNotEquals("stale-job", waived.region.activeJob!!.jobId)
        assertEquals(listOf("hB"), linked(waived))

        val older = PoolDriver(staleJobState(listOf(
            pooled("hD", presentedAt = 600L, leftAt = 800L, demoted = true),
            pooled("hB", presentedAt = 500L, leftAt = 700L, demoted = false),
        ))).step(PF.pickup(1_000))
        assertEquals("an older candidate keeps the guard", "stale-job", older.region.activeJob!!.jobId)
        assertTrue(older.outcomes().isEmpty())
    }
}
