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

        scope.launch(dispatcher) {
            try {
                val activeSession = next.regions.platforms.values
                    .maxByOrNull { it.lastObservedAt }?.session
                snapshotDao.insert(
                    AppStateSnapshotEntity(
                        correlationVersion = next.correlationVersion,
                        capturedAt = System.currentTimeMillis(),
                        sessionId = activeSession?.sessionId,
                        stateJson = StateJson.encodeToString(next),
                    )
                )
                // Prune snapshots older than the retention window.
                snapshotDao.pruneOlderThan(System.currentTimeMillis() - SNAPSHOT_RETENTION_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.e(e, "Failed to write state snapshot")
            }
        }
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
