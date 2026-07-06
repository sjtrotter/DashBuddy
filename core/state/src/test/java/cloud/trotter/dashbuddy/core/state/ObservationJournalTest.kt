package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.core.database.observation.ObservationDao
import cloud.trotter.dashbuddy.core.database.observation.ObservationEntity
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.evaluation.OfferAction
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingOffer
import cloud.trotter.dashbuddy.domain.state.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import cloud.trotter.dashbuddy.domain.evaluation.OfferQuality
import cloud.trotter.dashbuddy.domain.pipeline.ObservationPayload

/**
 * #352 — the observation journal's fidelity guarantees:
 * - single-writer appends land in submission order, gap-free (the old
 *   per-insert fire-and-forget launches could reorder);
 * - internal observations (Loopback/UiInput/Timeout) round-trip with their
 *   REAL payloads instead of replaying as stubs — most importantly the
 *   `offer_evaluated` loopback, whose evaluation must land on replay.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObservationJournalTest {

    private class FakeObservationDao : ObservationDao {
        val inserted = mutableListOf<ObservationEntity>()
        override suspend fun insert(entity: ObservationEntity): Long {
            delay(1) // emulate IO latency — single-writer order must still hold
            inserted += entity
            return inserted.size.toLong()
        }

        override suspend fun since(afterVersion: Long): List<ObservationEntity> =
            inserted.filter { it.correlationVersion > afterVersion }
                .sortedBy { it.correlationVersion }

        override suspend fun latest(): ObservationEntity? =
            inserted.maxByOrNull { it.correlationVersion }

        override suspend fun pruneOlderThan(cutoff: Long) {
            inserted.removeAll { it.occurredAt < cutoff }
        }
    }

    private fun screenObs(t: Long) = Observation.Screen(
        timestamp = t, captureId = null, ruleId = "doordash.screen.idle",
        metadata = ReplayMetadata.EMPTY, flow = Flow.Idle, modeHint = Mode.Online,
        parsed = ParsedFields.None,
    )

    private fun state(cv: Long) = AppState(timestamp = cv, correlationVersion = cv)

    private fun evaluation() = OfferEvaluation(
        action = OfferAction.ACCEPT, score = 74.0, qualityLevel = OfferQuality.GOOD, payAmount = 7.50,
        fuelCostEstimate = 0.5, netPayAmount = 6.50, distanceMiles = 3.2,
        dollarsPerMile = 2.03, dollarsPerHour = 22.0, estimatedTimeMinutes = 18.0,
        itemCount = 3.0, merchantName = "HEB",
    )

    @Test
    fun `appends land in submission order with no gaps`() = runTest {
        val dao = FakeObservationDao()
        val journal = ObservationJournal(dao)
        journal.start(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)), StandardTestDispatcher(testScheduler))

        (1L..20L).forEach { cv -> journal.append(screenObs(t = cv), state(cv)) }
        advanceUntilIdle()

        assertEquals((1L..20L).toList(), dao.inserted.map { it.correlationVersion })
    }

    @Test
    fun `offer_evaluated loopback round-trips and lands its evaluation`() = runTest {
        val dao = FakeObservationDao()
        val journal = ObservationJournal(dao)
        journal.start(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)), StandardTestDispatcher(testScheduler))
        val eval = evaluation()
        val loopback = Observation.Loopback(
            timestamp = 2_000L,
            effect = "offer_evaluated",
            payload = ObservationPayload.EvaluationResult(
                action = eval.action.name,
                offerHash = "offer-A",
                evaluation = eval,
            ),
        )

        journal.append(loopback, state(cv = 1))
        advanceUntilIdle()
        val replayed = journal.tailAfter(0).single() as Observation.Loopback

        assertEquals("offer_evaluated", replayed.effect)
        val result = replayed.payload as ObservationPayload.EvaluationResult
        assertEquals("offer-A", result.offerHash)
        assertEquals(eval, result.evaluation)

        // And the replayed loopback LANDS on a pending offer — the fidelity gap
        // this issue exists for.
        val region = FlowRegion(
            flow = Flow.OfferPresented,
            pendingOffer = PendingOffer(
                offerHash = "offer-A",
                offerFields = ParsedFields.OfferFields(
                    parsedOffer = ParsedOffer(offerHash = "offer-A", payAmount = 7.5),
                ),
                presentedAt = 1_000L,
                returnFlow = Flow.Idle,
            ),
        )
        val next = FlowRegionStepper().step(region, replayed)
        assertNotNull(next.pendingOffer?.evaluation)
    }

    @Test
    fun `UiInput action and Timeout target platform survive the round-trip`() = runTest {
        val dao = FakeObservationDao()
        val journal = ObservationJournal(dao)
        journal.start(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)), StandardTestDispatcher(testScheduler))

        journal.append(Observation.UiInput(timestamp = 1L, action = "accept_offer"), state(1))
        journal.append(
            Observation.Timeout(
                timestamp = 2L, type = TimeoutType.SESSION_PAUSED_SAFETY,
                targetPlatform = Platform.DoorDash,
            ),
            state(2),
        )
        advanceUntilIdle()
        val tail = journal.tailAfter(0)

        val ui = tail[0] as Observation.UiInput
        assertEquals("accept_offer", ui.action)

        val timeout = tail[1] as Observation.Timeout
        assertEquals(TimeoutType.SESSION_PAUSED_SAFETY, timeout.type)
        assertEquals(Platform.DoorDash, timeout.targetPlatform)
        assertEquals(Platform.DoorDash, timeout.platform) // routing (#342) preserved
    }

    @Test
    fun `UiInput offer identity survives the round-trip (#438 item 8a)`() = runTest {
        val dao = FakeObservationDao()
        val journal = ObservationJournal(dao)
        journal.start(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)), StandardTestDispatcher(testScheduler))

        journal.append(
            Observation.UiInput(
                timestamp = 1L, action = "accept_offer",
                targetPlatform = Platform.Uber, offerHash = "offer-U",
            ),
            state(1),
        )
        advanceUntilIdle()
        val ui = journal.tailAfter(0).single() as Observation.UiInput

        assertEquals("accept_offer", ui.action)
        assertEquals(Platform.Uber, ui.targetPlatform)
        assertEquals(Platform.Uber, ui.platform) // derived override honours the stamp
        assertEquals("offer-U", ui.offerHash)
    }

    @Test
    fun `Loopback target platform survives the round-trip (#438 item 8a)`() = runTest {
        val dao = FakeObservationDao()
        val journal = ObservationJournal(dao)
        journal.start(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)), StandardTestDispatcher(testScheduler))

        journal.append(
            Observation.Loopback(
                timestamp = 1L, effect = "offer_evaluated",
                targetPlatform = Platform.DoorDash,
                payload = ObservationPayload.EvaluationResult(action = "ACCEPT", offerHash = "offer-A"),
            ),
            state(1),
        )
        advanceUntilIdle()
        val loop = journal.tailAfter(0).single() as Observation.Loopback

        assertEquals(Platform.DoorDash, loop.targetPlatform)
        assertEquals(Platform.DoorDash, loop.platform)
    }

    @Test
    fun `an OLD journal row without the new identity fields still decodes (#438 item 8a)`() = runTest {
        // Additive-JSON contract: a UiInput row persisted before B2 carries only `action` in its
        // payloadJson. The new offerHash/targetPlatform keys must decode to null defaults, not throw.
        val dao = FakeObservationDao()
        val journal = ObservationJournal(dao)
        dao.inserted += ObservationEntity(
            occurredAt = 7L, sessionId = null, pipelineId = "internal.ui", ruleId = null,
            platform = Platform.Unknown.name, flow = null, modeHint = null, parsedJson = "{}",
            captureId = null, metadataJson = "{}", correlationVersion = 1L,
            payloadJson = """{"action":"accept_offer"}""",
        )

        val ui = journal.tailAfter(0).single() as Observation.UiInput
        assertEquals("accept_offer", ui.action)
        assertEquals(null, ui.targetPlatform)
        assertEquals(null, ui.offerHash)
        assertEquals(Platform.Unknown, ui.platform) // no stamp, no ruleId → Unknown (today's fallback)
    }

    @Test
    fun `flow observations round-trip their parsed fields`() = runTest {
        val dao = FakeObservationDao()
        val journal = ObservationJournal(dao)
        journal.start(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)), StandardTestDispatcher(testScheduler))
        val parsed = ParsedFields.OfferFields(
            parsedOffer = ParsedOffer(offerHash = "h9", payAmount = 11.25, distanceMiles = 4.0),
        )
        val obs = Observation.Screen(
            timestamp = 5L, captureId = null, ruleId = "doordash.screen.offer_popup",
            metadata = ReplayMetadata.EMPTY, flow = Flow.OfferPresented, modeHint = Mode.Online,
            parsed = parsed,
        )

        journal.append(obs, state(5))
        advanceUntilIdle()
        val replayed = journal.tailAfter(0).single() as Observation.Screen

        assertEquals(parsed, replayed.parsed)
        assertEquals(Flow.OfferPresented, replayed.flow)
        assertTrue(replayed.platform == Platform.DoorDash)
    }
}
