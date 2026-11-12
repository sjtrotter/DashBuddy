package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.evaluation.OfferAction
import cloud.trotter.log.gig.domain.evaluation.OfferEvaluation
import cloud.trotter.log.gig.domain.evaluation.OfferQuality
import cloud.trotter.log.gig.domain.model.accessibility.BoundingBox
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.DeliveryPayload
import cloud.trotter.log.gig.domain.model.event.payload.OfferPayload
import cloud.trotter.log.gig.domain.model.event.payload.OfferReceivedPayload
import cloud.trotter.log.gig.domain.model.offer.OfferKind
import cloud.trotter.log.gig.domain.model.offer.OfferScope
import cloud.trotter.log.gig.domain.pipeline.NodeRef
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.pipeline.TimeoutType
import cloud.trotter.log.gig.domain.state.AcceptedOfferEconomics
import cloud.trotter.log.gig.domain.state.DestructiveKind
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.Job
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.PendingDestructive
import cloud.trotter.log.gig.domain.state.PendingOffer
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.PoolEntryVia
import cloud.trotter.log.gig.domain.state.PooledOffer
import cloud.trotter.log.gig.domain.state.Session
import cloud.trotter.log.gig.domain.state.Task
import cloud.trotter.log.gig.domain.state.TaskPhase
import cloud.trotter.log.gig.domain.state.mintWakeId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber

/**
 * #1119 S2: the offer pool, case by case (spec § Tests `OfferPoolTest` 1–17; council round 6 where it overrides).
 *
 * Non-vacuity: on master `77c089dac` every case that links (7, 12, 14, 16, 17) or ties (10, 13) fails — a card that
 * leaves to Idle logs `OFFER_TIMEOUT` and is gone, so the pickup mints a bare job and nothing links; every pooling
 * case (1, 4, 5, 6) fails on the `OFFER_TIMEOUT` it logs at the leave (case 6: master logs `OFFER_DECLINED` from the
 * sheet); and the pool, the instance key and `OFFER_NOT_TAKEN` (15) do not exist there.
 */
class OfferPoolTest {

    private val warns = mutableListOf<String>()
    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority == android.util.Log.WARN && message.startsWith("#1119")) warns += message
        }
    }

    @Before fun plant() = Timber.plant(tree)
    @After fun uproot() = Timber.uproot(tree)

    private val stepper = PlatformRegionStepper()
    private val button = NodeRef("accept", "Accept", "android.widget.Button", BoundingBox(0, 0, 100, 50), "fp/accept")

    private fun evaluation(net: Double) = OfferEvaluation(
        action = OfferAction.ACCEPT, score = 70.0, qualityLevel = OfferQuality.GOOD, payAmount = 9.0,
        fuelCostEstimate = 1.0, netPayAmount = net, distanceMiles = 4.0, dollarsPerMile = net / 4.0,
        dollarsPerHour = net * 3, estimatedTimeMinutes = 20.0, itemCount = 1.0, merchantName = "Store A",
    )

    private fun pooledThenIdle(): PoolDriver =
        PoolDriver().step(PF.card(1_000, "hA").copy(targets = mapOf("acceptButton" to button)), PF.idle(5_000))

    // 1
    @Test fun `a card that leaves to Idle waits in the pool with no outcome`() {
        val d = PoolDriver().step(PF.card(1_000, "hA").copy(targets = mapOf("acceptButton" to button)))
        d.step(PF.idle(5_000))
        assertTrue(d.outcomes().isEmpty())
        assertTrue(d.last.any { it is AppEffect.CancelOfferNotification && it.offerHash == "hA" })
        assertTrue(d.last.any { it is AppEffect.CancelTimeout && it.type == TimeoutType.OFFER_EXPIRY })
        val entry = d.region.offerPool.single()
        assertEquals(PoolEntryVia.FRAME, entry.via)
        assertEquals(5_000L, entry.leftAt)
        assertTrue(entry.offer.targets.isEmpty())
        assertEquals(setOf("store a"), entry.candidateStoreKeys)
        assertEquals(PF.instanceOf(1_000), entry.instanceKey)
        assertTrue(d.region.pendingOffers.isEmpty())
    }

    // 2
    @Test fun `a committed decline click is declined at the leave and never pooled`() {
        val d = PoolDriver().step(PF.card(1_000, "hA"), PF.decline(3_000), PF.idle(5_000))
        val log = d.last.filterIsInstance<AppEffect.LogEvent>().single { it.event.type == AppEventType.OFFER_DECLINED }
        assertEquals("log:OFFER_DECLINED:${PF.instanceOf(1_000)}", log.effectKey)
        assertEquals(listOf(AppEventType.OFFER_DECLINED), d.outcomes().map { it.type })
        assertTrue(d.region.offerPool.isEmpty())
    }

    // 3
    @Test fun `an accept click survives the leave with acceptedAt and is never pooled`() {
        val d = PoolDriver().step(PF.card(1_000, "hA"), PF.accept(3_000), PF.idle(5_000))
        val survivor = d.region.pendingOffers.single()
        assertEquals(3_000L, survivor.acceptedAt)
        assertEquals(listOf(AppEventType.OFFER_ACCEPTED), d.outcomes().map { it.type })
        assertTrue(d.region.offerPool.isEmpty())
        assertEquals(5_000L, d.region.lastAcceptResolvedAt)
    }

    // 4
    @Test fun `a replaced card is pooled with no outcome, and a direct offer over a match is a supersession`() {
        val d = PoolDriver().step(PF.card(1_000, "hA"), PF.card(3_000, "hB", stores = listOf("Store B")))
        assertTrue(d.outcomes().isEmpty())
        assertEquals(PoolEntryVia.REPLACED, d.region.offerPool.single().via)
        val received = d.events.filter { it.type == AppEventType.OFFER_RECEIVED }.map { it.payload as OfferReceivedPayload }
        assertEquals(listOf(PF.instanceOf(1_000), PF.instanceOf(3_000)), received.map { it.offerInstanceId })

        val m = PoolDriver().step(PF.card(1_000, "hA", kind = OfferKind.MATCH), PF.card(3_000, "hB", kind = OfferKind.DIRECT))
        assertEquals(PoolEntryVia.SUPERSEDED_BY_DIRECT, m.region.offerPool.single().via)
        assertTrue(m.outcomes().isEmpty())
    }

    // 5
    @Test fun `the expiry timer pools its own instance, ignores another and spares an accepted card`() {
        val d = PoolDriver().step(PF.card(1_000, "hA"))
        d.step(PF.expiry(121_000, "hA", "doordash:999"))
        assertNotNull("a fire for another instance is a no-op", d.region.presentedOffer())
        d.step(PF.expiry(121_000, "hA", PF.instanceOf(1_000)))
        assertEquals(PoolEntryVia.EXPIRY_TIMER, d.region.offerPool.single().via)
        assertNull(d.region.presentedOffer())
        assertTrue(d.outcomes().isEmpty())

        val latched = PoolDriver().step(PF.card(1_000, "hA"), PF.accept(2_000), PF.expiry(121_000, "hA", PF.instanceOf(1_000)))
        assertNotNull(latched.region.presentedOffer())
        assertTrue(latched.region.offerPool.isEmpty())
        assertTrue(latched.outcomes().isEmpty())
    }

    // 6 (council round 6: the sighting demotes, it does not decline)
    @Test fun `a sheet sighting then a leave with time on the countdown pools a demoted entry, not a decline`() {
        val d = PoolDriver().step(PF.card(1_000, "hA", countdown = 51), PF.sheet(20_000), PF.idle(22_000))
        assertTrue(d.outcomes().isEmpty())
        val entry = d.region.offerPool.single()
        assertTrue(entry.demoted)
        assertEquals(20_000L, entry.offer.declineSheetSeenAt)
    }

    // 7
    @Test fun `a pickup at the pooled offer's store links it as the click path would`() {
        val d = pooledThenIdle()
        val entry = d.region.offerPool.single()
        d.step(PF.pickup(10_000))
        val job = d.region.activeJob!!
        val expected = stepper.acceptInputsFromPending(entry.offer, entry.leftAt).economics
        assertEquals(expected, job.acceptedOffers.single())
        assertEquals(1, expected.cardDropoffCount)
        assertEquals(listOf(PF.CARD_STORE), expected.storeHints)
        assertEquals(PF.instanceOf(1_000), expected.offerInstanceId)
        assertEquals(1, job.tasks.count { it.phase == TaskPhase.DROPOFF })
        assertEquals(1, job.tasks.count { it.phase == TaskPhase.PICKUP })
        val out = d.outcomePayloads().single()
        assertEquals(AppEventType.OFFER_ACCEPTED, out.outcome)
        assertEquals(job.jobId, out.linkedJobId)
        assertEquals(5_000L, out.decidedAt)
        assertEquals(PF.instanceOf(1_000), out.offerInstanceId)
        assertEquals(10_000L, d.region.lastAcceptResolvedAt)
        assertTrue(d.region.offerPool.isEmpty())
    }

    // 8
    @Test fun `a pickup at another store mints a bare job and the offer keeps waiting`() {
        val d = pooledThenIdle().step(PF.pickup(10_000, store = "Store B"))
        assertTrue(d.region.activeJob!!.acceptedOffers.isEmpty())
        assertEquals(1, d.region.offerPool.size)
        assertTrue(d.outcomes().isEmpty())
    }

    // 9 (council round 6: the store-less frame is held, then mints bare exactly as before)
    @Test fun `a store-less work frame holds, then mints bare from the held frame and links nothing`() {
        for (storeless in listOf(PF.pickup(10_000, store = null), PF.pickup(10_000, store = "Unknown"), PF.taskActive(10_000))) {
            val d = pooledThenIdle().step(storeless)
            assertNull("held, not minted", d.region.activeJob)
            assertNotNull(d.region.heldWorkFrame)
            d.step(PF.idle(11_000))
            val job = d.region.activeJob!!
            assertTrue(job.acceptedOffers.isEmpty())
            assertEquals(10_000L, job.startedAt)
            assertEquals(1, d.region.offerPool.size)
            assertTrue(d.outcomes().isEmpty())
        }
    }

    // 10
    @Test fun `two pooled offers at the store tie on a bare job and link nothing`() {
        val d = PoolDriver().step(PF.card(1_000, "hA"), PF.idle(5_000), PF.card(6_000, "hB"), PF.idle(9_000))
        warns.clear()
        d.step(PF.pickup(10_000))
        val job = d.region.activeJob!!
        assertTrue(job.acceptedOffers.isEmpty())
        assertEquals(listOf(listOf(job.jobId), listOf(job.jobId)), d.region.offerPool.map { it.ambiguousJobIds })
        assertTrue(d.outcomes().isEmpty())
        assertEquals(1, warns.size)
    }

    // 11
    @Test fun `an add-on card over a live job matches only the stores new to it`() {
        val liveJob = Job("J0", listOf(PF.CARD_STORE), "h0", startedAt = 100L, tasks = listOf(
            Task("p0", "J0", TaskPhase.PICKUP, expectedStoreHint = PF.CARD_STORE, storeName = PF.PICKUP_STORE, startedAt = 100L),
        ))
        val withJob = PF.online(PlatformRegion(PF.platform, mode = Mode.Online, session = Session("S1", startedAt = 0L),
            activeJob = liveJob, lastActedFlow = Flow.TaskPickupNavigation))
        val addOn = PF.card(1_000, "hC", stores = listOf(PF.CARD_STORE, "Store C"), scope = OfferScope.ADD_ON)
        val d = PoolDriver(withJob).step(addOn, PF.idle(5_000))
        assertEquals(setOf("store c"), d.region.offerPool.single().candidateStoreKeys)
        val bare = PoolDriver().step(addOn, PF.idle(5_000))
        assertEquals(setOf("store a", "store c"), bare.region.offerPool.single().candidateStoreKeys)
    }

    private val oldPickup = Task("old-p", "old-job", TaskPhase.PICKUP, storeName = "Store Z", startedAt = 100L,
        arrivedAt = 150L, confirmedAt = 200L, completedAt = 200L)
    private val oldDrop = Task("old-d", "old-job", TaskPhase.DROPOFF, customerNameHash = "c-old", startedAt = 250L,
        arrivedAt = 300L)

    private fun pooled(hash: String, presentedAt: Long = 500L, returnFlow: Flow = Flow.TaskDropoffArrived,
        scope: OfferScope = OfferScope.STANDALONE, sessionId: String? = "S1", tied: List<String> = emptyList()) = PooledOffer(
        offer = PendingOffer(hash, ParsedFields.OfferFields(parsedOffer = PF.parsedOffer(hash, scope = scope)), presentedAt,
            returnFlow = returnFlow, instanceId = PF.instanceOf(presentedAt)),
        leftAt = presentedAt + 100, via = PoolEntryVia.FRAME, sessionId = sessionId, candidateStoreKeys = setOf("store a"),
        ambiguousJobIds = tied,
    )

    private fun finishedJobState(armedFrom: Flow, pool: List<PooledOffer>) = PF.online(PlatformRegion(
        PF.platform, mode = Mode.Online, session = Session("S1", startedAt = 0L),
        activeJob = Job("old-job", listOf("Store Z"), "old-offer", startedAt = 100L, tasks = listOf(oldPickup, oldDrop),
            acceptedOffers = listOf(AcceptedOfferEconomics("old-offer", payAmount = 8.0, acceptedAt = 100L))),
        activeTask = oldDrop, recentTasks = listOf(oldPickup),
        pendingDestructive = PendingDestructive(DestructiveKind.TASK_RETIRE, since = 800L, deadline = 60_800L,
            armedFromFlow = armedFrom, wakeId = PlatformRegion(PF.platform, wakeSeq = 9L).mintWakeId().second),
        lastActedFlow = Flow.Idle, offerPool = pool,
    ))

    // 12
    @Test fun `a pooled offer at the store of new work over a finished job closes it and links`() {
        val d = PoolDriver(finishedJobState(Flow.PostTask, listOf(pooled("hA")))).step(PF.pickup(1_000))
        val job = d.region.activeJob!!
        assertNotEquals("old-job", job.jobId)
        assertEquals(listOf("hA"), job.acceptedOffers.map { it.offerHash })
        assertEquals(listOf(AppEventType.OFFER_ACCEPTED), d.outcomes().map { it.type })
        assertTrue(d.events.any { it.type == AppEventType.DELIVERY_COMPLETED && (it.payload as DeliveryPayload).taskId == "old-d" })

        val addOnRetire = PoolDriver(finishedJobState(Flow.OfferPresented, listOf(pooled("hA")))).step(PF.pickup(1_000))
        assertEquals("old-job", addOnRetire.region.activeJob!!.jobId)
        assertTrue(addOnRetire.outcomes().isEmpty())
        assertEquals(1, addOnRetire.region.offerPool.size)
    }

    // 13
    @Test fun `a tie over a finished job closes it and ties on the bare job`() {
        val d = PoolDriver(finishedJobState(Flow.PostTask, listOf(pooled("hA"), pooled("hB", presentedAt = 600L))))
            .step(PF.pickup(1_000))
        val job = d.region.activeJob!!
        assertNotEquals("old-job", job.jobId)
        assertTrue(job.acceptedOffers.isEmpty())
        assertEquals(listOf(listOf(job.jobId), listOf(job.jobId)), d.region.offerPool.map { it.ambiguousJobIds })
        assertTrue(d.outcomes().isEmpty())
        assertTrue(d.events.any { it.type == AppEventType.DELIVERY_COMPLETED })
    }

    private fun staleJobState(pool: List<PooledOffer>, ownedStore: String? = null) = PF.online(PlatformRegion(
        PF.platform, mode = Mode.Online, session = Session("S1", startedAt = 0L),
        activeJob = Job("stale-job", listOf("Store B"), "old-offer", startedAt = 100L, tasks = listOfNotNull(
            Task("sp", "stale-job", TaskPhase.PICKUP, expectedStoreHint = "Store B", startedAt = 100L),
            Task("sd", "stale-job", TaskPhase.DROPOFF, startedAt = 100L),
            ownedStore?.let { Task("sp2", "stale-job", TaskPhase.PICKUP, storeName = it, startedAt = 100L, completedAt = 150L) },
        ), acceptedOffers = listOf(AcceptedOfferEconomics("old-offer", payAmount = 8.0, acceptedAt = 100L))),
        lastActedFlow = Flow.Idle, offerPool = pool,
    ))

    // 14
    @Test fun `the stale-job edge links a sole standalone offer at a store new to the job, and nothing else`() {
        val d = PoolDriver(staleJobState(listOf(pooled("hA", returnFlow = Flow.Idle)))).step(PF.pickup(1_000))
        val job = d.region.activeJob!!
        assertNotEquals("stale-job", job.jobId)
        assertEquals(listOf("hA"), job.acceptedOffers.map { it.offerHash })
        assertEquals(listOf(AppEventType.OFFER_ACCEPTED), d.outcomes().map { it.type })

        val refusals = mapOf(
            "presented before the job started" to staleJobState(listOf(pooled("hA", presentedAt = 50L, returnFlow = Flow.Idle))),
            "presented over a task flow" to staleJobState(listOf(pooled("hA", returnFlow = Flow.TaskPickupNavigation))),
            "an add-on" to staleJobState(listOf(pooled("hA", returnFlow = Flow.Idle, scope = OfferScope.ADD_ON))),
            "a store the job owns" to staleJobState(listOf(pooled("hA", returnFlow = Flow.Idle)), ownedStore = PF.PICKUP_STORE),
            "a second candidate" to staleJobState(listOf(pooled("hA", returnFlow = Flow.Idle), pooled("hB", presentedAt = 600L, returnFlow = Flow.Idle))),
        )
        for ((why, start) in refusals) {
            val r = PoolDriver(start).step(PF.pickup(1_000))
            assertEquals(why, "stale-job", r.region.activeJob!!.jobId)
            assertTrue(why, r.outcomes().isEmpty())
        }
    }

    // 15
    @Test fun `the shift end records one outcome for every offer still without one`() {
        val presented = PendingOffer("hP", ParsedFields.OfferFields(parsedOffer = PF.parsedOffer("hP")), 2_000,
            returnFlow = Flow.Idle, instanceId = PF.instanceOf(2_000))
        val survivor = PendingOffer("hS", ParsedFields.OfferFields(parsedOffer = PF.parsedOffer("hS")), 1_500,
            returnFlow = Flow.Idle, instanceId = PF.instanceOf(1_500), lastClickIntent = "accept_offer",
            acceptClickAt = 1_600, acceptedAt = 1_600)
        val pool = listOf(pooled("hA", sessionId = "S0"), pooled("hB", presentedAt = 600L, tied = listOf("J7")))
        fun state(presentedOffer: PendingOffer) = PF.online(PlatformRegion(PF.platform, mode = Mode.Online,
            session = Session("S1", startedAt = 0L), pendingOffers = listOf(survivor, presentedOffer), offerPool = pool,
            lastActedFlow = Flow.OfferPresented))
        val end = Observation.UiInput(timestamp = 3_000, action = Observation.UiInput.ACTION_END_SHIFT,
            targetPlatform = PF.platform, sessionId = "S1")

        val d = PoolDriver(state(presented)).step(end)
        val out = d.outcomePayloads()
        assertEquals(3, out.size)
        assertTrue(out.all { it.outcome == AppEventType.OFFER_NOT_TAKEN })
        assertEquals(3, out.map { it.offerInstanceId }.toSet().size)
        assertEquals(listOf("J7"), out.single { it.offerHash == "hB" }.ambiguousJobIds)
        assertEquals(mapOf("hA" to "S0", "hB" to "S1", "hP" to "S1"),
            d.outcomes().associate { (it.payload as OfferPayload).offerHash to it.sessionId })
        assertTrue(out.none { it.offerHash == "hS" })
        assertTrue(d.region.offerPool.isEmpty())
        assertTrue(d.region.pendingOffers.isEmpty())

        val declined = PoolDriver(state(presented.copy(lastClickIntent = "decline_offer", declineCommittedAt = 2_500))).step(end)
        assertEquals(AppEventType.OFFER_DECLINED, declined.outcomePayloads().single { it.offerHash == "hP" }.outcome)
        val accepted = PoolDriver(state(presented.copy(lastClickIntent = "accept_offer", acceptClickAt = 2_500))).step(end)
        assertEquals(AppEventType.OFFER_ACCEPTED, accepted.outcomePayloads().single { it.offerHash == "hP" }.outcome)
    }

    // 16
    @Test fun `an evaluation landing after the card was pooled rides the link`() {
        val d = pooledThenIdle().step(PF.evalLanded(6_000, "hA", evaluation(net = 6.5)))
        assertEquals(6.5, d.region.offerPool.single().offer.evaluation!!.netPayAmount, 1e-9)
        d.step(PF.pickup(10_000))
        assertEquals(6.5, d.region.activeJob!!.acceptedOffers.single().netPay!!, 1e-9)
    }

    // 17
    @Test fun `the store match is on the chain key`() {
        val d = pooledThenIdle().step(PF.pickup(10_000, store = "STORE A #55"))
        assertEquals(listOf("hA"), d.region.activeJob!!.acceptedOffers.map { it.offerHash })
        assertFalse(d.region.offerPool.isNotEmpty())
    }
}
