package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.core.database.snapshot.AppStateSnapshotDao
import cloud.trotter.dashbuddy.core.database.snapshot.AppStateSnapshotEntity
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.state.StateEvent
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1271 scenario 4 — a snapshot never lands AHEAD of the effects of the steps it covers.
 *
 * Recovery restores the latest snapshot and replays only the journal after it, so a snapshot of
 * step N written while step N's `LogEvent`s still wait in the engine's queue makes them
 * unreachable: a process death in between loses them for good. The end-to-end receipt is
 * `CrashRestartE2ETest` (a retire's `DELIVERY_CONFIRMED` lost); this pins the ordering at the
 * manager boundary with an engine that holds its queue until released.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StateManagerV2SnapshotOrderTest {

    /** Holds every effect and barrier until [release], then runs them in queue order; logs each. */
    private class GatedEngine(dispatcher: CoroutineDispatcher, private val log: MutableList<String>) : EffectExecutor {
        private val scope = CoroutineScope(dispatcher)
        private val held = mutableListOf<Pair<String, suspend () -> Unit>>()
        private var open = false
        override val events: SharedFlow<StateEvent> = MutableSharedFlow(extraBufferCapacity = 16)

        val heldEffects get() = held.count { it.first.startsWith("effect") }

        override fun process(effect: AppEffect, recovering: Boolean, correlationVersion: Long) =
            enqueue("effect ${effect::class.simpleName}") {}

        override fun afterProcessed(action: suspend () -> Unit) = enqueue("barrier", action)

        private fun enqueue(label: String, action: suspend () -> Unit) {
            held += label to action
            if (open) drain()
        }

        fun release() {
            open = true
            drain()
        }

        private fun drain() {
            val batch = held.toList()
            held.clear()
            scope.launch {
                batch.forEach { (label, action) ->
                    log += label
                    action()
                }
            }
        }
    }

    /** [FakeSnapshotDao], logging each insert into the engine's log. */
    private class LoggingSnapshotDao(private val log: MutableList<String>) : AppStateSnapshotDao {
        val inner = FakeSnapshotDao()
        override suspend fun insert(entity: AppStateSnapshotEntity) {
            log += "snapshot cv=${entity.correlationVersion}"
            inner.insert(entity)
        }
        override suspend fun latest() = inner.latest()
        override suspend fun pruneOlderThan(cutoff: Long) = inner.pruneOlderThan(cutoff)
    }

    private fun liveIdle(timestamp: Long) = Observation.Screen(
        timestamp = timestamp,
        captureId = null,
        ruleId = "doordash.screen.waiting_for_offer",
        metadata = ReplayMetadata.EMPTY,
        flow = Flow.Idle,
        modeHint = Mode.Online,
        parsed = ParsedFields.IdleFields(sessionPay = null),
    )

    @Test
    fun `a live snapshot waits behind the effects queued before it`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val log = mutableListOf<String>()
        val snapshots = LoggingSnapshotDao(log)
        val engine = GatedEngine(dispatcher, log)
        val manager = recoveryManager(FakeObservationDao(), snapshots, engine, dispatcher)
        manager.initialize()
        runCurrent()

        // The first online frame starts a session — a major transition, so it is snapshotted.
        manager.dispatch(liveIdle(10_000L))
        runCurrent()
        assertEquals(1L, manager.state.value.correlationVersion)
        assertTrue("the step emitted effects, and they are still queued", engine.heldEffects > 0)
        assertEquals("so its snapshot has not been written", 0, snapshots.inner.inserts)

        engine.release()
        runCurrent()
        val snapshot = log.indexOfFirst { it.startsWith("snapshot") }
        assertTrue("the snapshot landed once the queue ran: $log", snapshot >= 0)
        assertEquals(
            "…after every effect queued ahead of it: $log",
            log.count { it.startsWith("effect") }, log.subList(0, snapshot).count { it.startsWith("effect") },
        )
    }

    @Test
    fun `the recovery checkpoint waits for the effects the tail replay re-issued`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val journal = FakeObservationDao()
        val log = mutableListOf<String>()
        val snapshots = LoggingSnapshotDao(log)

        // A first process: a session start (snapshotted) and two ordinary frames after it (the tail).
        val first = recoveryManager(journal, snapshots, InlineEffectExecutor(dispatcher), dispatcher)
        first.initialize()
        runCurrent()
        listOf(10_000L, 11_000L, 12_000L).forEach { first.dispatch(liveIdle(it)); runCurrent() }
        first.close()
        val written = snapshots.inner.inserts
        assertEquals("the tail is the two frames after the snapshot", 1L, snapshots.inner.latest()!!.correlationVersion)

        // The relaunch, over an engine that has not run the replay's effects yet.
        val engine = GatedEngine(dispatcher, log)
        val second = recoveryManager(journal, snapshots, engine, dispatcher)
        second.initialize()
        runCurrent()
        assertEquals("the checkpoint waits for the replayed effects", written, snapshots.inner.inserts)
        assertEquals("…and so does the install", 0L, second.state.value.correlationVersion)

        engine.release()
        runCurrent()
        assertEquals("then the checkpoint lands at the restored version", 3L, snapshots.inner.latest()!!.correlationVersion)
        assertEquals("…and the restored state is installed", 3L, second.state.value.correlationVersion)
        second.close()
    }
}
