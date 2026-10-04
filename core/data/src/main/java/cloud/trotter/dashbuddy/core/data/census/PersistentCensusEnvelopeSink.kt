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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
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

    private class Held(val platform: Platform, val json: String)
    private val held = LinkedHashMap<String, Held>()
    private val enabled = AtomicBoolean()
    override val isEnabled: Boolean get() = enabled.get()
    private val channel = Channel<CensusRecord>(
        capacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { stats.envelopesDropped.incrementAndGet() },
    )

    init {
        scope.launch {
            preferences.censusUploadEnabled.combine(preferences.censusShareCaptures) { consent, share -> consent && share }
                .collect { value ->
                    synchronized(held) {
                        enabled.set(value)
                        if (!value) held.clear()
                    }
                }
        }
        scope.launch {
            try {
                for (record in channel) {
                    if (!isEnabled) {
                        stats.envelopesDropped.incrementAndGet()
                        continue
                    }
                    try {
                        spool.append(record)
                        stats.envelopesSpooled.incrementAndGet()
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
                held[captureId] = Held(platform, envelopeJson)
                if (held.size > 16) held.remove(held.keys.first())
                stats.envelopesHeld.incrementAndGet()
            }
        } catch (_: Throwable) {
            stats.envelopesDropped.incrementAndGet()
        }
    }

    override fun pair(captureId: String, fingerprint: String): Boolean {
        try {
            val entry = synchronized(held) { held.remove(captureId) } ?: return false
            if (!isEnabled) return false
            val projected = EnvelopeProjection.project(entry.json, fingerprint)
            if (projected == null) {
                stats.envelopesProjectionRefused.incrementAndGet()
                return false
            }
            val marker = scan(Json.parseToJsonElement(projected), 0)
            if (marker != null) {
                stats.envelopesSensitiveDropped.incrementAndGet()
                if (warned.compareAndSet(false, true)) {
                    Timber.tag("Census").w("census envelope dropped marker=%s", marker)
                }
                return false
            }
            val accepted = channel.trySend(CensusRecord(entry.platform, fingerprint, projected,
                projected.toByteArray(Charsets.UTF_8).size, captureId)).isSuccess
            if (!accepted) stats.envelopesDropped.incrementAndGet()
            return accepted
        } catch (_: Throwable) {
            stats.envelopesProjectionRefused.incrementAndGet()
            return false
        }
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

    private companion object {
        val warned = AtomicBoolean()
    }
}
