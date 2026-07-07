package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.core.database.observation.ObservationDao
import cloud.trotter.dashbuddy.core.database.observation.ObservationEntity
import cloud.trotter.dashbuddy.core.database.snapshot.AppStateSnapshotDao
import cloud.trotter.dashbuddy.core.database.snapshot.AppStateSnapshotEntity
import cloud.trotter.dashbuddy.core.pipeline.PipelineV2
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.state.StateEvent
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * #352 — events that arrive while crash recovery is still restoring must be
 * buffered and processed afterwards, not silently dropped. The pipeline's hot
 * SharedFlows don't replay, and the old code only attached its collector AFTER
 * restore completed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StateManagerV2RestoreBufferTest {

    private class FakeObservationDao : ObservationDao {
        val inserted = mutableListOf<ObservationEntity>()
        override suspend fun insert(entity: ObservationEntity): Long {
            inserted += entity; return inserted.size.toLong()
        }

        override suspend fun since(afterVersion: Long): List<ObservationEntity> =
            inserted.filter { it.correlationVersion > afterVersion }
                .sortedBy { it.correlationVersion }

        override suspend fun latest(): ObservationEntity? =
            inserted.maxByOrNull { it.correlationVersion }

        override suspend fun pruneOlderThan(cutoff: Long) {}
    }

    /** latest() sleeps so the test can interleave a live event mid-restore. */
    private class SlowSnapshotDao(private val delayMs: Long) : AppStateSnapshotDao {
        override suspend fun insert(entity: AppStateSnapshotEntity) {}
        override suspend fun latest(): AppStateSnapshotEntity? {
            delay(delayMs)
            return null
        }

        override suspend fun pruneOlderThan(cutoff: Long) {}
    }

    private fun screenObs(t: Long) = Observation.Screen(
        timestamp = t, captureId = null, ruleId = "doordash.screen.idle",
        metadata = ReplayMetadata.EMPTY, flow = Flow.Idle, modeHint = Mode.Online,
        parsed = ParsedFields.None,
    )

    @Test
    fun `events arriving during restore are processed after it, not dropped`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val pipelineEvents = MutableSharedFlow<StateEvent>(extraBufferCapacity = 16)
        val engineEvents = MutableSharedFlow<StateEvent>(extraBufferCapacity = 16)
        val pipeline: PipelineV2 = mock()
        whenever(pipeline.events).thenReturn(pipelineEvents)
        val engine: EffectExecutor = mock()
        whenever(engine.events).thenReturn(engineEvents)

        val manager = StateManagerV2(
            pipeline = pipeline,
            engine = engine,
            stateMachine = StateMachine(
                FlowRegionStepper(), PlatformRegionStepper(),
                CrossPlatformRegionStepper(), TransitionPolicy(),
                EffectMap(),
            ),
            journal = ObservationJournal(FakeObservationDao()),
            snapshots = SnapshotStore(SlowSnapshotDao(delayMs = 100)),
            defaultDispatcher = dispatcher,
            ioDispatcher = dispatcher,
        )

        manager.initialize()
        advanceTimeBy(50)
        runCurrent() // restore still sleeping in SlowSnapshotDao.latest()

        pipelineEvents.emit(screenObs(t = 1_000L))
        runCurrent() // event lands in the buffer; restore hasn't finished
        assertEquals(0L, manager.state.value.correlationVersion)

        advanceTimeBy(60)
        runCurrent() // restore completes; buffered event drains and steps state
        assertEquals(1L, manager.state.value.correlationVersion)
        assertEquals(Flow.Idle, manager.state.value.regions.flow.flow)
    }
}
