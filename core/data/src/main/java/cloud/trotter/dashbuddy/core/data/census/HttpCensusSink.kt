package cloud.trotter.dashbuddy.core.data.census

import cloud.trotter.dashbuddy.domain.capture.CensusRecord
import cloud.trotter.dashbuddy.domain.capture.CensusSink
import cloud.trotter.dashbuddy.domain.census.CensusUploadPreferences
import cloud.trotter.dashbuddy.domain.census.CensusUploadScheduler
import cloud.trotter.dashbuddy.domain.census.CensusUploadStats
import cloud.trotter.dashbuddy.domain.di.ApplicationScope
import cloud.trotter.dashbuddy.domain.di.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Debug binding only. The pipeline collector only reads a snapshot and calls trySend. */
@Singleton
class HttpCensusSink @Inject constructor(
    preferences: CensusUploadPreferences,
    private val spool: CensusSpool,
    private val scheduler: CensusUploadScheduler,
    private val stats: CensusUploadStats,
    @ApplicationScope scope: CoroutineScope,
    @IoDispatcher io: CoroutineDispatcher,
) : CensusSink {
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
            var appended = 0
            try {
                for (record in channel) {
                    if (!isEnabled) {
                        stats.spoolDropped.incrementAndGet()
                        continue
                    }
                    try {
                        val wasEmpty = spool.count() == 0
                        spool.append(record)
                        appended++
                        // Automatic delivery (dev ask 2026-10-03): the FIRST item of a non-empty spool schedules an upload
                        // within ~5 minutes; 50 appends or 100 queued items still upload immediately; the hourly sweep remains.
                        if (wasEmpty) scheduler.enqueueSoon()
                        if (appended % 50 == 0 || spool.count() >= 100) scheduler.enqueueNow()
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
