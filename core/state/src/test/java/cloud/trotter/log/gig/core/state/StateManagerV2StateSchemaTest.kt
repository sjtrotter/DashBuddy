package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.core.database.observation.ObservationEntity
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.state.AppState
import cloud.trotter.log.gig.domain.state.PoolEntryVia
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1119 S2: recovery across the upgrade fails to fresh. A snapshot written by an older reducer generation
 * (`stateSchema` 0) is not replayed: the manager starts from an empty state ABOVE every journalled row, so no
 * old-reducer observation runs through the pool (a card that already logged its outcome live cannot log a second),
 * and the next restart neither re-detects nor replays the old rows. A current-generation snapshot replays normally.
 *
 * Non-vacuity: on master `77c089dac` there is no `stateSchema`; the old snapshot's tail replays (case 1 then sees the
 * card resolve on the Idle row and log an outcome, and the restored state keeps the live session).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StateManagerV2StateSchemaTest {

    /** A live shift with a card on screen, as a pre-S2 build would have snapshotted it. */
    private fun liveWithCard(): AppState {
        val d = PoolDriver().step(PF.card(1_000, "hA"))
        return d.state.copy(correlationVersion = 100L)
    }

    /** Journal rows 101..120 replaying frames after the snapshot; an Idle row early on would pool the card. */
    private fun tail(state: AppState): List<ObservationEntity> {
        val journal = ObservationJournal(FakeObservationDao())
        val frames: List<Observation> = listOf(PF.idle(5_000)) + (2..20).map { PF.idle(5_000L + it * 1_000) }
        return frames.mapIndexed { i, obs ->
            with(journal) { obs.toEntity(state.copy(correlationVersion = 101L + i)) }
        }
    }

    @Test fun `an old-schema snapshot starts fresh above the journal and replays no tail`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val old = liveWithCard().copy(stateSchema = 0)
        val journalDao = FakeObservationDao(tail(old))
        val snapshotDao = FakeSnapshotDao(snapshotOf(old, stampSchema = false))
        val engine = InlineEffectExecutor(dispatcher)
        val manager = recoveryManager(journalDao, snapshotDao, engine, dispatcher)
        manager.initialize()
        runCurrent()

        val restored = manager.state.value
        assertTrue("no platform regions", restored.regions.platforms.isEmpty())
        assertEquals(120L, restored.correlationVersion)
        assertEquals(AppState.STATE_SCHEMA, restored.stateSchema)
        assertTrue("no LogEvent from the tail", engine.processed.none { it is AppEffect.LogEvent })
        val latest = snapshotDao.latest()!!
        assertEquals(120L, latest.correlationVersion)
        assertEquals(AppState.STATE_SCHEMA, StateJson.decodeFromString<AppState>(latest.stateJson).stateSchema)

        // A second manager over the same disk restores cv 120 normally: no second fresh start, no replay.
        val again = InlineEffectExecutor(dispatcher)
        val second = recoveryManager(journalDao, snapshotDao, again, dispatcher)
        second.initialize()
        runCurrent()
        assertEquals(120L, second.state.value.correlationVersion)
        assertEquals(AppState.STATE_SCHEMA, second.state.value.stateSchema)
        assertTrue(again.processed.none { it is AppEffect.LogEvent })
    }

    @Test fun `a current-schema snapshot replays its tail and keeps the pool`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val pooled = PoolDriver().step(PF.card(1_000, "hA"), PF.idle(5_000)).state.copy(correlationVersion = 100L)
        assertEquals(1, pooled.regions.platforms.getValue(PF.platform).offerPool.size)
        val journal = ObservationJournal(FakeObservationDao())
        val row = with(journal) { PF.idle(9_000).toEntity(pooled.copy(correlationVersion = 101L)) }
        val engine = InlineEffectExecutor(dispatcher)
        val manager = recoveryManager(FakeObservationDao(listOf(row)), FakeSnapshotDao(snapshotOf(pooled)), engine, dispatcher)
        manager.initialize()
        runCurrent()

        val restored = manager.state.value
        assertEquals(101L, restored.correlationVersion)
        val entry = restored.regions.platforms.getValue(PF.platform).offerPool.single()
        assertEquals(PoolEntryVia.FRAME, entry.via)
        assertEquals(PF.instanceOf(1_000), entry.instanceKey)
        assertTrue(engine.processed.filterIsInstance<AppEffect.LogEvent>().none { it.event.type in PF.OUTCOMES })
    }
}
