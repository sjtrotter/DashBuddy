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
    override val isEnabled: Boolean get() = enabled.value
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
                        spool.append(record)
                        appended++
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
