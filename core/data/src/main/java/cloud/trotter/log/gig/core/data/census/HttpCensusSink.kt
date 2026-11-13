package cloud.trotter.log.gig.core.data.census

import cloud.trotter.census.contract.CensusSkeletonSchema
import cloud.trotter.log.gig.domain.capture.CensusRecord
import cloud.trotter.log.gig.domain.capture.CensusSink
import cloud.trotter.log.gig.domain.census.CensusSampleKey
import cloud.trotter.log.gig.domain.census.CensusUploadPreferences
import cloud.trotter.log.gig.domain.census.CensusUploadStats
import cloud.trotter.log.gig.domain.di.ApplicationScope
import cloud.trotter.log.gig.domain.di.IoDispatcher
import cloud.trotter.log.gig.domain.di.ReadsUnboundPlatforms
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The census sink of every build (#1620), behind the census consent. The pipeline collector only reads a snapshot and
 * calls trySend; one IO consumer queues a record only for a platform the dasher's choices cover, and only the day's
 * first skeleton of its variant ([CensusDailySample]). Queueing never asks for an upload: the daily window does.
 */
@Singleton
class HttpCensusSink @Inject constructor(
    private val preferences: CensusUploadPreferences,
    private val spool: CensusSpool,
    private val sample: CensusDailySample,
    private val stats: CensusUploadStats,
    @ApplicationScope scope: CoroutineScope,
    @IoDispatcher io: CoroutineDispatcher,
    @ReadsUnboundPlatforms readsUnboundPlatforms: Boolean,
) : CensusSink {
    private val readable = Platform.readable(readsUnboundPlatforms)
    private val enabled = preferences.enabled.stateIn(scope, SharingStarted.Eagerly, false)
    private val schemas = preferences.acceptedSchemaIds.stateIn(scope, SharingStarted.Eagerly, null)
    // Wait for the persisted policy snapshot before enabling publication after process restart.
    override val isEnabled: Boolean get() = enabled.value && schemas.value != null
    override val acceptedSchemaIds: Set<String> get() = schemas.value.orEmpty()
    private val channel = Channel<CensusRecord>(
        capacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { stats.spoolDropped.incrementAndGet() },
    )

    init {
        scope.launch(io) {
            try {
                for (record in channel) {
                    if (!isEnabled) {
                        stats.spoolDropped.incrementAndGet()
                        continue
                    }
                    try {
                        if (record.platform !in preferences.choices.first().platformsAmong(readable)) {
                            stats.platformNotChosen.incrementAndGet()
                            continue
                        }
                        val item = CensusSkeletonSchema.deserialize(record.skeletonJson)
                        val key = requireNotNull(CensusSampleKey.of(item))
                        if (!sample.admit(item.day, key)) {
                            stats.sampledRepeat.incrementAndGet()
                            continue
                        }
                        spool.append(record)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // No throwable message: a failed write can contain the serialized item.
                        stats.spoolDropped.incrementAndGet()
                        stats.uploadFailures.incrementAndGet()
                        Timber.tag("Census").i("census spool failures=%d", stats.uploadFailures.get())
                    }
                }
            } finally {
                channel.cancel()
            }
        }
    }

    override fun offer(record: CensusRecord): Boolean = channel.trySend(record).isSuccess
}
