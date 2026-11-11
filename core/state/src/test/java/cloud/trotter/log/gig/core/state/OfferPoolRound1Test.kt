package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.capture.ReplayMetadata
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.state.AppState
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.PendingOffer
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.PoolEntryVia
import cloud.trotter.log.gig.domain.state.PooledOffer
import cloud.trotter.log.gig.domain.state.Session
import cloud.trotter.log.gig.domain.state.TaskPhase
import cloud.trotter.log.gig.domain.state.TaskSubFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1119 S2, review round 1 (Sol r1 S1–S6 on `41c568aa8`). Every case is written against APIs that head already had,
 * so each was run on it and fails there (the PR report names the failing assertion).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfferPoolRound1Test {

    private fun pooled(): PoolDriver = PoolDriver().step(PF.card(1_000, "hA"), PF.idle(5_000))

    // S1: coming back Online from Offline must not heal a bare job before the pool decides.
    @Test fun `S1 - a matching pickup after an Offline blip links instead of healing a bare job`() {
        val d = PoolDriver().step(PF.card(1_000, "hA"), PF.idle(5_000).copy(modeHint = Mode.Offline), PF.pickup(8_000))
        assertEquals(listOf("hA"), d.region.activeJob!!.acceptedOffers.map { it.offerHash })
        assertEquals(listOf(AppEventType.OFFER_ACCEPTED), d.outcomes().map { it.type })
    }

    // S2: the held frame's task evidence lands on the linked task.
    @Test fun `S2 - a link out of the hold keeps the held frame's arrival and shopping fields`() {
        val heldArrival = Observation.Screen(
            timestamp = 10_000, captureId = null, ruleId = "doordash.screen.pickup_shopping", metadata = ReplayMetadata.EMPTY,
            flow = Flow.TaskPickupArrived, modeHint = Mode.Online,
            parsed = ParsedFields.TaskFields(phase = TaskPhase.PICKUP, subFlow = TaskSubFlow.ARRIVED, itemsRemaining = 5),
        )
        val d = pooled().step(heldArrival, PF.pickup(11_000, flow = Flow.TaskPickupArrived))
        assertEquals(listOf("hA"), d.region.activeJob!!.acceptedOffers.map { it.offerHash })
        val task = d.region.activeTask!!
        assertEquals("arrived at the held frame", 10_000L, task.arrivedAt)
        assertEquals(5, task.itemsRemaining)
        assertEquals(PF.PICKUP_STORE, task.storeName)
        assertEquals(10_000L, task.startedAt)
    }

    // S3: the hold's time cap holds without a later flow frame.
    @Test fun `S3 - the hold ends at its cap on a non-flow observation, and its wake is armed`() {
        val held = pooled().step(PF.pickup(10_000, store = null))
        val armed = held.last.filterIsInstance<AppEffect.ScheduleTimeout>().filter { it.type.name == "STORELESS_HOLD" }
        val d = held.step(Observation.Loopback(timestamp = 20_001, effect = "unrelated", targetPlatform = PF.platform))
        val job = d.region.activeJob
        assertNotNull("the lapsed hold minted its bare job", job)
        assertEquals(10_000L, job!!.startedAt)
        assertTrue(job.acceptedOffers.isEmpty())
        assertEquals("the hold's wake is armed at its deadline", listOf(20_000L), armed.map { it.deadlineMs })
    }

    private fun entry(hash: String, at: Long, demoted: Boolean = false, tied: List<String> = emptyList()) = PooledOffer(
        offer = PendingOffer(hash, ParsedFields.OfferFields(parsedOffer = PF.parsedOffer(hash)), at, returnFlow = Flow.Idle,
            instanceId = PF.instanceOf(at)),
        leftAt = at + 100, via = PoolEntryVia.FRAME, sessionId = "S1", candidateStoreKeys = setOf("store a"),
        ambiguousJobIds = tied, demoted = demoted,
    )

    // S4: an entry that tied keeps the tie on its record when it later links.
    @Test fun `S4 - a link keeps the entry's earlier tie`() {
        val start = PF.online(PlatformRegion(PF.platform, mode = Mode.Online, session = Session("S1", startedAt = 0L),
            lastActedFlow = Flow.Idle,
            offerPool = listOf(entry("hA", 1_000, tied = listOf("J1")), entry("hB", 2_000, demoted = true, tied = listOf("J1")))))
        val d = PoolDriver(start).step(PF.pickup(200_000))
        val accepted = d.outcomePayloads().single { it.outcome == AppEventType.OFFER_ACCEPTED }
        assertEquals("hA", accepted.offerHash)
        assertEquals(listOf("J1"), accepted.ambiguousJobIds)
    }

    // S5: the tie guard measures to the edge's observation, not the held frame.
    @Test fun `S5 - the tie guard measures from the store-bearing frame, past 60 s it links`() {
        val d = PoolDriver().step(
            PF.card(1_000, "hA", countdown = 30),
            PF.expiry(31_000, "hA", PF.instanceOf(1_000)),
            PF.card(40_000, "hB", countdown = 60),
            PF.sheet(44_000),
            PF.idle(50_000), // B demoted at 50 000
            PF.pickup(105_000, store = null), // held 55 s after B's leave
            PF.pickup(111_000), // the edge, 61 s after B's leave
        )
        assertEquals(listOf("hA"), d.region.activeJob!!.acceptedOffers.map { it.offerHash })
    }

    // S6: an old journal with no usable snapshot starts fresh above it, durably.
    @Test fun `S6 - no snapshot over an old journal starts fresh above the journal and checkpoints it`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val journal = ObservationJournal(FakeObservationDao())
        val base = PoolDriver().step(PF.card(1_000, "hA")).state
        val rows = (0 until 20).map { i -> with(journal) { PF.idle(5_000L + i * 1_000).toEntity(base.copy(correlationVersion = 1L + i)) } }
        val snapshotDao = FakeSnapshotDao()
        val engine = InlineEffectExecutor(dispatcher)
        val manager = recoveryManager(FakeObservationDao(rows), snapshotDao, engine, dispatcher)
        manager.initialize()
        runCurrent()
        assertEquals(20L, manager.state.value.correlationVersion)
        val latest = snapshotDao.latest()
        assertNotNull("the fresh state is checkpointed above the journal", latest)
        assertEquals(20L, latest!!.correlationVersion)
        assertEquals(AppState.STATE_SCHEMA, StateJson.decodeFromString<AppState>(latest.stateJson).stateSchema)
        assertTrue(engine.processed.none { it is AppEffect.LogEvent })
    }
}
