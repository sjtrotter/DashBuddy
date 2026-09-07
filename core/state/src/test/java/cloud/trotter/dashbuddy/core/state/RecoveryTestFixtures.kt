package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.core.database.observation.ObservationDao
import cloud.trotter.dashbuddy.core.database.observation.ObservationEntity
import cloud.trotter.dashbuddy.core.database.snapshot.AppStateSnapshotDao
import cloud.trotter.dashbuddy.core.database.snapshot.AppStateSnapshotEntity
import cloud.trotter.dashbuddy.core.pipeline.PipelineV2
import cloud.trotter.dashbuddy.domain.model.state.StateEvent
import cloud.trotter.dashbuddy.domain.state.AppState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.encodeToString
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The in-memory persistence and wiring every `StateManagerV2` recovery test needs (#1054 round 4).
 *
 * Three suites had grown their own copies of the same four things, which is the drift Principle 5
 * names: `StateManagerV2RecoveryHygieneTest` had the strongest `FakeSnapshotDao` (REPLACE by
 * correlation version, with an `inserts` counter — the only one that can express what a SECOND
 * restart reads back) while `StateManagerV2RecoveryRearmTest` had a weaker one, and the manager
 * construction was written out twice. One owner each.
 */

/** The observation journal, in memory. `since` mirrors the real DAO's ordering contract. */
internal class FakeObservationDao(rows: List<ObservationEntity> = emptyList()) : ObservationDao {
    private val rows = rows.toMutableList()

    override suspend fun insert(entity: ObservationEntity): Long {
        rows += entity
        return rows.size.toLong()
    }

    override suspend fun since(afterVersion: Long): List<ObservationEntity> =
        rows.filter { it.correlationVersion > afterVersion }.sortedBy { it.correlationVersion }

    override suspend fun latest(): ObservationEntity? = rows.maxByOrNull { it.correlationVersion }
    override suspend fun pruneOlderThan(cutoff: Long) {}
}

/** One immutable snapshot row, for a test that only ever restores and never re-reads. */
internal class FixedSnapshotDao(private val entity: AppStateSnapshotEntity) : AppStateSnapshotDao {
    override suspend fun insert(entity: AppStateSnapshotEntity) {}
    override suspend fun latest(): AppStateSnapshotEntity = entity
    override suspend fun pruneOlderThan(cutoff: Long) {}
}

/**
 * A real snapshot table: rows keyed by `correlationVersion`, REPLACE on conflict — exactly the
 * production entity's `@PrimaryKey` + `OnConflictStrategy.REPLACE`. [FixedSnapshotDao] cannot
 * express the #1052 round-2 case at all, because the whole question there is what the SECOND
 * restart reads back after the first one wrote; [inserts] is how a test sees that write happen.
 */
internal class FakeSnapshotDao(seed: AppStateSnapshotEntity? = null) : AppStateSnapshotDao {
    private val rows = LinkedHashMap<Long, AppStateSnapshotEntity>()
    var inserts = 0
        private set

    init {
        if (seed != null) rows[seed.correlationVersion] = seed
    }

    override suspend fun insert(entity: AppStateSnapshotEntity) {
        rows[entity.correlationVersion] = entity
        inserts++
    }

    override suspend fun latest(): AppStateSnapshotEntity? =
        rows.values.maxByOrNull { it.correlationVersion }

    override suspend fun pruneOlderThan(cutoff: Long) {}
}

/** Encode [state] as the snapshot row a restore would read back. */
internal fun snapshotOf(state: AppState, sessionId: String = "s1") = AppStateSnapshotEntity(
    correlationVersion = state.correlationVersion,
    capturedAt = 1L,
    sessionId = sessionId,
    stateJson = StateJson.encodeToString(state),
)

/**
 * A `StateManagerV2` over CALLER-OWNED persistence, so two restarts can share one disk.
 *
 * The real `StateMachine` throughout — recovery tests are about what the machine does to a restored
 * state, so mocking the steppers would leave nothing worth asserting. Only the pipeline is a mock
 * (it emits nothing in these tests); [engine] is the caller's, usually a recording fake.
 */
internal fun recoveryManager(
    journalDao: ObservationDao,
    snapshotDao: AppStateSnapshotDao,
    engine: EffectExecutor,
    dispatcher: CoroutineDispatcher,
): StateManagerV2 {
    val pipeline: PipelineV2 = mock()
    whenever(pipeline.events).thenReturn(MutableSharedFlow(extraBufferCapacity = 16))

    return StateManagerV2(
        pipeline = pipeline,
        engine = engine,
        stateMachine = StateMachine(
            FlowRegionStepper(), PlatformRegionStepper(),
            CrossPlatformRegionStepper(), TransitionPolicy(),
            EffectMap(),
        ),
        journal = ObservationJournal(journalDao),
        snapshots = SnapshotStore(snapshotDao),
        defaultDispatcher = dispatcher,
        ioDispatcher = dispatcher,
    )
}
