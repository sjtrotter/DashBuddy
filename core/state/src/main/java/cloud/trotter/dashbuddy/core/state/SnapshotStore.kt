package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.core.database.snapshot.AppStateSnapshotDao
import cloud.trotter.dashbuddy.core.database.snapshot.AppStateSnapshotEntity
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Flow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Snapshot cadence + persistence + restore (#352), extracted from StateManagerV2
 * so the recovery seam is testable in isolation.
 */
@Singleton
class SnapshotStore @Inject constructor(
    private val snapshotDao: AppStateSnapshotDao,
) {

    companion object {
        /** Write a state snapshot every N accepted observations. */
        const val SNAPSHOT_INTERVAL = 5

        /** Keep snapshots for 24h. */
        const val SNAPSHOT_RETENTION_MS = 24 * 60 * 60 * 1000L
    }

    /** A successfully decoded snapshot. */
    data class Restored(val state: AppState, val correlationVersion: Long)

    fun maybeSnapshot(scope: CoroutineScope, dispatcher: CoroutineDispatcher, prev: AppState, next: AppState) {
        val shouldSnapshot =
            next.correlationVersion % SNAPSHOT_INTERVAL == 0L ||
                isMajorTransition(prev, next)

        if (!shouldSnapshot) return

        scope.launch(dispatcher) { write(next) }
    }

    /**
     * Write [state] as a snapshot **unconditionally** — no cadence, no major-transition gate
     * (#1052).
     *
     * Crash recovery's only caller: `StateManagerV2.restoreState` installs a CLEANED final state
     * (the #1029 park hygiene), and that cleaning is durable only if the cleaned state becomes the
     * next replay base. Otherwise the next restart replays the ORIGINAL snapshot plus a tail that
     * has since grown — the same pre-crash park, expiring against a live frame that landed past its
     * deadline. Snapshot rows are keyed by `correlationVersion` with `REPLACE`, so checkpointing at
     * the restored version overwrites exactly the row that carried the park, leaving the journal
     * tail after it untouched.
     *
     * Suspends rather than launching (unlike [maybeSnapshot]) so the recovery path can order the
     * write ahead of the first live observation. Shares [write] with [maybeSnapshot] — ONE
     * serializer, ONE DAO path (principle 5).
     *
     * @return true iff the row is durable (#1052 round 3). [write] swallows every failure, which
     *   is correct for the cadence — another snapshot is five observations away — and wrong for
     *   the checkpoint, whose whole job is durability: a swallowed insert failure silently reopens
     *   the double-recovery hole. `StateManagerV2.restoreState` retries once on false and then
     *   reports it at ERROR; [maybeSnapshot] ignores the outcome as before.
     */
    suspend fun checkpoint(state: AppState): Boolean = write(state)

    /**
     * The snapshot writer both [maybeSnapshot] and [checkpoint] go through. Never throws; returns
     * whether the snapshot ROW landed.
     *
     * The two DAO calls take separate `try`s deliberately: the insert IS the durability, while
     * pruning is housekeeping that cannot un-write the row above it. Folding them together would
     * report a prune failure as a lost checkpoint and send the caller into a retry + ERROR for a
     * snapshot that is sitting safely on disk.
     */
    private suspend fun write(state: AppState): Boolean {
        try {
            val activeSession = state.regions.platforms.values
                .maxByOrNull { it.lastObservedAt }?.session
            snapshotDao.insert(
                AppStateSnapshotEntity(
                    correlationVersion = state.correlationVersion,
                    capturedAt = System.currentTimeMillis(),
                    sessionId = activeSession?.sessionId,
                    stateJson = StateJson.encodeToString(state),
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Timber.e(e, "Failed to write state snapshot")
            return false
        }
        // Prune snapshots older than the retention window.
        try {
            snapshotDao.pruneOlderThan(System.currentTimeMillis() - SNAPSHOT_RETENTION_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Timber.tag("StateMachine").e(e, "Failed to prune old snapshots — the row itself landed")
        }
        return true
    }

    /**
     * The latest decodable snapshot, or null. Schema drift within kotlinx's
     * tolerance decodes with defaults; anything beyond fails LOUDLY here and the
     * caller starts fresh (#353).
     */
    suspend fun restoreLatest(): Restored? {
        val snapshot = snapshotDao.latest() ?: return null
        return try {
            Restored(
                state = StateJson.decodeFromString<AppState>(snapshot.stateJson),
                correlationVersion = snapshot.correlationVersion,
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to deserialize snapshot — starting fresh")
            null
        }
    }

    private fun isMajorTransition(prev: AppState, next: AppState): Boolean {
        val allPlatforms = (prev.regions.platforms.keys + next.regions.platforms.keys)
        for (p in allPlatforms) {
            val prevRegion = prev.regions.platforms[p]
            val nextRegion = next.regions.platforms[p]

            // Session start/end
            if (prevRegion?.session?.sessionId != nextRegion?.session?.sessionId) return true

            // Job start/end
            if (prevRegion?.activeJob?.jobId != nextRegion?.activeJob?.jobId) return true
        }

        // Flow transitions that mark lifecycle boundaries
        val prevFlow = prev.regions.flow.flow
        val nextFlow = next.regions.flow.flow
        if (prevFlow != nextFlow) {
            val majorFlows = setOf(
                Flow.OfferPresented,
                Flow.SessionEnded,
            )
            if (nextFlow in majorFlows || prevFlow in majorFlows) return true
        }

        return false
    }
}
