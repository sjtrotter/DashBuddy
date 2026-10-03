package cloud.trotter.dashbuddy.core.data.census

import cloud.trotter.dashbuddy.domain.census.CensusUploadStats
import cloud.trotter.dashbuddy.domain.census.HealthGrammar
import cloud.trotter.dashbuddy.domain.census.HealthKey
import cloud.trotter.dashbuddy.domain.census.HealthLedger
import cloud.trotter.dashbuddy.domain.census.HealthSink
import cloud.trotter.dashbuddy.domain.di.ApplicationScope
import cloud.trotter.dashbuddy.domain.di.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Records locally regardless of upload consent; all ledger mutations and disk writes share one mutex. */
@Singleton
class PersistentHealthSink @Inject constructor(
    private val store: HealthLedgerStore,
    private val stats: CensusUploadStats,
    @ApplicationScope scope: CoroutineScope,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : HealthSink {
    private data class Event(val key: HealthKey, val ruleId: String?, val trip: Boolean, val epoch: Long)
    private val mutex = Mutex()
    private val ingress = Any()
    private var epoch = 0L
    private var ledger: HealthLedger? = null
    private var lastPrunedDay: String? = null
    private var dirty = false
    private val channel = Channel<Event>(
        capacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { stats.healthDropped.incrementAndGet() },
    )
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(io) {
            var retryDelayMillis = 1_000L
            for (ignored in wake) {
                try {
                    mutex.withLock { drain() }
                    retryDelayMillis = 1_000L
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Keep queued records for a later load; never apply them to an empty substitute.
                    delay(retryDelayMillis)
                    retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(60_000)
                    wake.trySend(Unit)
                }
            }
        }
        scope.launch(io) {
            while (true) {
                // Review receipt (#1197): periodic dirty flush, never a traffic-reset trailing debounce.
                delay(60_000)
                try {
                    mutex.withLock { if (dirty) flush() }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    Timber.tag("Census").w("census health flush failures=1")
                }
            }
        }
    }

    override fun onScreen(timestampMillis: Long, platformWire: String, platformAppVersion: String?, ruleId: String?) =
        enqueue(timestampMillis, platformWire, platformAppVersion, ruleId, trip = false)

    override fun onTrip(timestampMillis: Long, platformWire: String, platformAppVersion: String?) =
        enqueue(timestampMillis, platformWire, platformAppVersion, null, trip = true)

    private fun enqueue(timestampMillis: Long, platformWire: String, version: String?, ruleId: String?, trip: Boolean) {
        try {
            if (version == null) {
                stats.healthSkippedNoVersion.incrementAndGet()
                return
            }
            if (!HealthGrammar.platformAppVersion.matches(version)) {
                stats.healthSkippedBadVersion.incrementAndGet()
                return
            }
            val day = Instant.ofEpochMilli(timestampMillis).atZone(ZoneOffset.UTC).toLocalDate().toString()
            synchronized(ingress) {
                if (!channel.trySend(Event(HealthKey(day, platformWire, version), ruleId, trip, epoch)).isSuccess) {
                    stats.healthDropped.incrementAndGet()
                }
            }
            wake.trySend(Unit)
        } catch (_: Throwable) {
            stats.healthDropped.incrementAndGet()
        }
    }

    /** Drains queued frames before saving, then prunes beyond the local eight-day retention boundary. */
    suspend fun snapshot(): HealthLedger = withContext(io) {
        mutex.withLock {
            drain()
            ledger = requireNotNull(ledger).pruneBefore(LocalDate.now(ZoneOffset.UTC).minusDays(8).toString())
            dirty = true
            flush()
            requireNotNull(ledger)
        }
    }

    /** Applies exact-revision receipts against the latest frames and saves immediately. */
    suspend fun apply(transform: (HealthLedger) -> HealthLedger) = withContext(io) {
        mutex.withLock {
            drain()
            ledger = transform(requireNotNull(ledger))
            dirty = true
            flush()
        }
    }

    /** Clears memory, queued old-generation frames and disk as one serialized identity boundary. */
    suspend fun reset() = withContext(io) {
        mutex.withLock {
            if (ledger == null) ledger = loadLedger()
            synchronized(ingress) { epoch++ }
            ledger = requireNotNull(ledger).clear()
            dirty = true
            store.clear()
            // Save the in-memory generation too, including after an earlier failed reset write.
            flush()
        }
    }

    private suspend fun loadLedger(): HealthLedger = try {
        val loaded = store.load()
        val todayUtc = LocalDate.now(ZoneOffset.UTC)
        val pruned = loaded.pruneBefore(todayUtc.minusDays(8).toString())
        lastPrunedDay = todayUtc.toString()
        if (pruned != loaded) dirty = true
        pruned
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        stats.healthLoadFailures.incrementAndGet()
        if (loadFailureWarned.compareAndSet(false, true)) {
            Timber.tag("Census").w("census health load failed")
        }
        throw e
    }

    private suspend fun drain() {
        if (ledger == null) ledger = loadLedger()
        // Bound each pass so continuous producers cannot starve snapshots or the flush loop.
        repeat(1024) {
            val event = channel.tryReceive().getOrNull() ?: return
            if (event.epoch != synchronized(ingress) { epoch }) return@repeat
            if (event.key.day != lastPrunedDay) {
                ledger = requireNotNull(ledger).pruneBefore(LocalDate.parse(event.key.day).minusDays(8).toString())
                lastPrunedDay = event.key.day
                dirty = true
            }
            val current = requireNotNull(ledger)
            val updated = if (event.trip) current.trip(event.key) else current.record(event.key, event.ruleId)
            if (updated === current) {
                stats.healthRowsRefused.incrementAndGet()
                return@repeat
            }
            ledger = updated
            if (!event.trip) stats.healthRecorded.incrementAndGet()
            dirty = true
        }
    }

    private suspend fun flush() {
        if (!dirty) return
        store.save(requireNotNull(ledger))
        dirty = false
    }

    companion object {
        internal val loadFailureWarned = AtomicBoolean()
    }
}
