package cloud.trotter.dashbuddy.core.data.census

import cloud.trotter.census.contract.SensitiveMarkerScan
import cloud.trotter.dashbuddy.core.data.di.CensusEnvelopeSpool
import cloud.trotter.dashbuddy.core.data.settings.DevSettingsRepository
import cloud.trotter.dashbuddy.domain.capture.CensusEnvelopeSink
import cloud.trotter.dashbuddy.domain.capture.CensusRecord
import cloud.trotter.dashbuddy.domain.census.CensusUploadStats
import cloud.trotter.dashbuddy.domain.census.EnvelopeProjection
import cloud.trotter.dashbuddy.domain.di.IoDispatcher
import cloud.trotter.dashbuddy.domain.state.Platform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Debug binding only. The sensing thread reads a snapshot; disk writes drain on the owned IO scope. */
@Singleton
class PersistentCensusEnvelopeSink internal constructor(
    preferences: DevSettingsRepository,
    private val spool: CensusSpool,
    private val stats: CensusUploadStats,
    scope: CoroutineScope,
) : CensusEnvelopeSink {
    @Inject constructor(
        preferences: DevSettingsRepository,
        @CensusEnvelopeSpool spool: CensusSpool,
        stats: CensusUploadStats,
        @IoDispatcher io: CoroutineDispatcher,
    ) : this(preferences, spool, stats, CoroutineScope(SupervisorJob() + io))

    private class Held(val captureId: String, val platform: Platform, val json: String)
    private class Queued(val held: Held, val fingerprint: String, val generation: Long)
    private val held = LinkedHashMap<String, Held>()
    private val enabled = AtomicBoolean()
    private val warned = AtomicBoolean()
    private val generation = AtomicLong()
    private val mutex = Mutex()
    override val isEnabled: Boolean get() = enabled.get()
    private val channel = Channel<Queued>(
        capacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { stats.envelopesDropped.incrementAndGet() },
    )

    init {
        scope.launch {
            preferences.censusUploadEnabled.combine(preferences.censusShareCaptures) { consent, share -> consent && share }
                .distinctUntilChanged()
                .collect { value ->
                    synchronized(held) {
                        enabled.set(value)
                    }
                    if (!value) invalidate()
                }
        }
        scope.launch {
            try {
                for (queued in channel) {
                    try {
                        mutex.withLock {
                            if (queued.generation != generation.get() || !isEnabled) {
                                stats.envelopesDropped.incrementAndGet()
                                return@withLock
                            }
                            val record = project(queued) ?: return@withLock
                            // Invalidation advances the generation even while projection is running.
                            if (queued.generation == generation.get()) {
                                spool.append(record)
                                stats.envelopesSpooled.incrementAndGet()
                            } else stats.envelopesDropped.incrementAndGet()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        stats.envelopesDropped.incrementAndGet()
                    }
                }
            } finally {
                channel.cancel()
            }
        }
    }

    override fun hold(captureId: String, platform: Platform, envelopeJson: String) {
        try {
            synchronized(held) {
                if (!isEnabled) return
                held.remove(captureId)
                held[captureId] = Held(captureId, platform, envelopeJson)
                if (held.size > 16) held.remove(held.keys.first())
                stats.envelopesHeld.incrementAndGet()
            }
        } catch (_: Throwable) {
            stats.envelopesDropped.incrementAndGet()
        }
    }

    override fun pair(captureId: String, fingerprint: String): Boolean {
        try {
            return synchronized(held) {
                val entry = held.remove(captureId) ?: return false
                if (!isEnabled) return false
                val accepted = channel.trySend(Queued(entry, fingerprint, generation.get())).isSuccess
                if (!accepted) stats.envelopesDropped.incrementAndGet()
                accepted
            }
        } catch (_: Throwable) {
            stats.envelopesDropped.incrementAndGet()
            return false
        }
    }

    override suspend fun invalidate() {
        synchronized(held) {
            generation.incrementAndGet()
            held.clear()
        }
        mutex.withLock { spool.clear() }
    }

    /** Projection and scanning stay on the IO consumer, never the sensing thread. */
    private fun project(queued: Queued): CensusRecord? {
        try {
            val entry = queued.held
            val projected = EnvelopeProjection.projectToObject(entry.json, queued.fingerprint)
            if (projected == null) {
                stats.envelopesProjectionRefused.incrementAndGet()
                return null
            }
            val marker = scan(projected, 0)
            if (marker != null) {
                stats.envelopesSensitiveDropped.incrementAndGet()
                if (warned.compareAndSet(false, true)) {
                    Timber.tag("Census").w("census envelope dropped markerId=%s", markerLogId(marker))
                }
                return null
            }
            val json = projected.toString()
            val bytes = json.toByteArray(Charsets.UTF_8).size
            if (bytes > EnvelopeProjection.MAX_BYTES) {
                stats.envelopesProjectionRefused.incrementAndGet()
                return null
            }
            return CensusRecord(entry.platform, queued.fingerprint, json, bytes, entry.captureId)
        } catch (_: Throwable) {
            stats.envelopesProjectionRefused.incrementAndGet()
            return null
        }
    }

    /** Shape owner: core.pipeline.MarkerLogId; marker constants only, never captured text. */
    private fun markerLogId(marker: String): String {
        val head = marker.asSequence().filter { it.isLetterOrDigit() }.take(2).joinToString(separator = "")
        return if (head.isEmpty()) "?${marker.length}" else "$head${marker.length}"
    }

    /** Every decoded key and string value, including metadata; excessive depth fails closed. */
    private fun scan(element: JsonElement, depth: Int): String? {
        require(depth <= 64)
        when (element) {
            is JsonObject -> for ((key, value) in element) {
                SensitiveMarkerScan.findMarker(key)?.let { return it }
                scan(value, depth + 1)?.let { return it }
            }
            is JsonArray -> for (value in element) scan(value, depth + 1)?.let { return it }
            is JsonPrimitive -> if (element.isString) return SensitiveMarkerScan.findMarker(element.content)
        }
        return null
    }
}
