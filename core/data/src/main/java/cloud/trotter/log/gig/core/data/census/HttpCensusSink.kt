package cloud.trotter.log.gig.core.data.census

import cloud.trotter.census.contract.CensusSkeletonSchema
import cloud.trotter.log.gig.domain.capture.CensusRecord
import cloud.trotter.log.gig.domain.capture.CensusSink
import cloud.trotter.log.gig.domain.census.CensusSampleKey
import cloud.trotter.log.gig.domain.census.CensusUploadPreferences
import cloud.trotter.log.gig.domain.census.CensusUploadStats
import cloud.trotter.log.gig.domain.census.contributor.ContributorReviewIntake
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
    /** #1626: takes a spooled screen as a review candidate; it checks the contributor gate itself. */
    private val reviews: ContributorReviewIntake = ContributorReviewIntake.NONE,
) : CensusSink {
    private val readable = Platform.readable(readsUnboundPlatforms)
    private val enabled = preferences.enabled.stateIn(scope, SharingStarted.Eagerly, false)
    private val schemas = preferences.acceptedSchemaIds.stateIn(scope, SharingStarted.Eagerly, null)
    // Wait for the persisted policy snapshot before enabling publication after process restart.
    override val isEnabled: Boolean get() = enabled.value && schemas.value != null
    override val acceptedSchemaIds: Set<String> get() = schemas.value.orEmpty()
    override val collectsReviewWords: Boolean get() = isEnabled && reviews.wantsCandidates
    /** A record with the spool's clear boundary at the moment it was offered (#1622). */
    private class Offered(val record: CensusRecord, val generation: Long)

    private val channel = Channel<Offered>(
        capacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { stats.spoolDropped.incrementAndGet() },
    )

    init {
        scope.launch(io) {
            try {
                for (offered in channel) {
                    val record = offered.record
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
                        val key = requireNotNull(CensusSampleKey.of(item, record.presentFields))
                        if (!sample.admit(item.day, key)) {
                            stats.sampledRepeat.incrementAndGet()
                            continue
                        }
                        spool.append(record, offered.generation)
                        // #1626: only a spooled skeleton (the one the server will hold) can be reviewed; its words stay in memory.
                        if (record.reviewWords != null) reviews.offer(record)
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

    override fun offer(record: CensusRecord): Boolean = channel.trySend(Offered(record, spool.generation)).isSuccess
}
