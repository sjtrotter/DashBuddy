package cloud.trotter.dashbuddy.core.pipeline.accessibility.event.coalesce

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/** Default bound on concurrently-open bursts (keys are window ids — a handful in practice). */
internal const val COALESCE_MAX_KEYS = 64

/**
 * Per-key burst coalescer (#1148 D3) — replaces the old `debounceWithTimeout`, whose max-wait was
 * checked only when the NEXT event arrived, read the wall clock, and coalesced globally.
 *
 * Semantics, per key:
 * - A burst OPENS on the first event for a key; every event is folded into the burst's
 *   accumulator with [merge] (`acc == null` on the opening event).
 * - The burst is emitted (and CLOSES) on whichever comes first:
 *   (a) [quietMs] elapse with no event for that key, or
 *   (b) [maxWaitMs] elapse since the burst OPENED — a SCHEDULED timer that fires with no arrival.
 * - After a close the next event opens a new burst, so a continuing flood emits at most every
 *   [maxWaitMs], and the final quiet always yields a trailing emission (the guaranteed trailing
 *   refresh).
 * - Leading edge (opt-in, [leadingEdge], #1148 review F5): a burst that opens on a key with NO
 *   close in the last [maxWaitMs] emits its opening event IMMEDIATELY (an accumulator of one), so
 *   the first frame of a transition after idle carries no quiet/max delay. Its later quiet/max
 *   flush then emits only if more events merged in — a lone event yields ONE emission, never a
 *   duplicate. A burst opening within [maxWaitMs] of the key's previous close (a continuing flood)
 *   gets no leading emission. The cooldown is a per-key `delay(maxWaitMs)` job in a map bounded
 *   at [maxKeys]. Off by default.
 *
 * Timing uses only coroutine [delay] — monotonic, and virtual-time testable under `runTest`; the
 * operator never reads a wall clock. The open-burst map is bounded at [maxKeys]: admitting a new
 * key at the cap FLUSHES (emits early, never drops) the least-recently-touched burst, with one
 * WARN per collection. Every timer is a child of the collecting scope, so cancelling the
 * collector cancels them all.
 *
 * Backpressure (#1148 review F2): a timer must take one of [maxKeys] EMISSION PERMITS before it
 * closes its burst, and holds it until its `send` returns. With a stalled consumer the permits run
 * out, so a due burst stays OPEN and keeps MERGING (its timers wait on a permit) instead of being
 * removed and replaced by a fresh burst with fresh timers — live coroutines stay ≤ 3 × [maxKeys]
 * (a sender blocked in `send` + the open burst's two timers, per key; plus, with [leadingEdge],
 * at most one short-lived cooldown job per key) and no event is lost: when the consumer resumes,
 * the merged burst carries every event that arrived meanwhile.
 */
fun <T, K, A : Any> Flow<T>.coalesceByKey(
    quietMs: Long = 150L,
    maxWaitMs: Long = 300L,
    keyOf: (T) -> K,
    merge: (acc: A?, T) -> A,
    maxKeys: Int = COALESCE_MAX_KEYS,
    leadingEdge: Boolean = false,
): Flow<A> {
    require(quietMs > 0 && maxWaitMs > 0) { "quietMs and maxWaitMs must be positive" }
    require(maxKeys > 0) { "maxKeys must be positive" }
    return channelFlow {
        val coalescer = KeyedCoalescer<T, K, A>(this, quietMs, maxWaitMs, maxKeys, leadingEdge, merge)
        collect { value -> coalescer.onEvent(keyOf(value), value) }
    }
}

private class KeyedCoalescer<T, K, A : Any>(
    private val scope: ProducerScope<A>,
    private val quietMs: Long,
    private val maxWaitMs: Long,
    private val maxKeys: Int,
    private val leadingEdge: Boolean,
    private val merge: (A?, T) -> A,
) {
    private inner class Burst(var acc: A, val leadingEmitted: Boolean) {
        /** Bumped per event; a quiet timer only fires if no event arrived after it was armed. */
        var quietGen = 0L
        var quietJob: Job? = null
        var maxJob: Job? = null

        /** Events merged after the leading emission — a closing flush emits only if > 0. */
        var mergedSinceLeading = 0

        /** What a close should emit: null when the leading emission already covered it. */
        fun closingValue(): A? = if (leadingEmitted && mergedSinceLeading == 0) null else acc
    }

    private val lock = Mutex()

    /** Emission permits: bounds the flushes in flight (#1148 review F2). */
    private val permits = Semaphore(maxKeys)

    /** Access-ordered: iteration starts at the least-recently-touched open burst. */
    private val bursts = LinkedHashMap<K, Burst>(16, 0.75f, true)

    /** Keys closed within the last [maxWaitMs] (leading-edge cooldown, F5); insertion-ordered. */
    private val recentlyClosed = LinkedHashMap<K, Job>()
    private var evictionWarned = false

    suspend fun onEvent(key: K, value: T) {
        var evicted: A? = null
        var leading: A? = null
        lock.withLock {
            val burst = bursts[key]
            if (burst == null) {
                if (bursts.size >= maxKeys) evicted = evictOldestLocked()
                val acc = merge(null, value)
                val lead = leadingEdge && key !in recentlyClosed
                if (lead) leading = acc
                val opened = Burst(acc, leadingEmitted = lead)
                bursts[key] = opened
                opened.maxJob = scope.launch {
                    delay(maxWaitMs)
                    flush(key, opened, quietGen = null)
                }
                armQuietLocked(key, opened)
            } else {
                burst.acc = merge(burst.acc, value)
                if (burst.leadingEmitted) burst.mergedSinceLeading++
                burst.quietJob?.cancel()
                armQuietLocked(key, burst)
            }
        }
        evicted?.let { scope.send(it) }
        leading?.let { scope.send(it) }
    }

    private fun armQuietLocked(key: K, burst: Burst) {
        val gen = ++burst.quietGen
        burst.quietJob = scope.launch {
            delay(quietMs)
            flush(key, burst, quietGen = gen)
        }
    }

    /**
     * Closes [burst] if it is still the open burst for [key] (and, for a quiet timer, no event
     * arrived since it was armed), emitting its value unless the leading emission already covered
     * it. The emission permit is taken BEFORE the burst is removed, so under a stalled consumer
     * the burst stays open and merging (F2). Only the OTHER timer is cancelled — the caller is one
     * of them and must not cancel itself before `send`.
     */
    private suspend fun flush(key: K, burst: Burst, quietGen: Long?) {
        permits.acquire()
        try {
            val value = lock.withLock {
                if (bursts[key] !== burst) return
                if (quietGen != null && burst.quietGen != quietGen) return
                bursts.remove(key)
                if (quietGen == null) burst.quietJob?.cancel() else burst.maxJob?.cancel()
                markClosedLocked(key)
                burst.closingValue()
            } ?: return
            scope.send(value)
        } finally {
            permits.release()
        }
    }

    /** Starts [key]'s leading-edge cooldown (F5); a no-op when the leading edge is off. */
    private fun markClosedLocked(key: K) {
        if (!leadingEdge) return
        recentlyClosed.remove(key)?.cancel()
        if (recentlyClosed.size >= maxKeys) {
            val oldest = recentlyClosed.keys.first()
            recentlyClosed.remove(oldest)?.cancel()
        }
        lateinit var cooldown: Job
        cooldown = scope.launch {
            delay(maxWaitMs)
            lock.withLock { if (recentlyClosed[key] === cooldown) recentlyClosed.remove(key) }
        }
        recentlyClosed[key] = cooldown
    }

    private fun evictOldestLocked(): A? {
        val (oldestKey, oldest) = bursts.entries.first().let { it.key to it.value }
        bursts.remove(oldestKey)
        oldest.quietJob?.cancel()
        oldest.maxJob?.cancel()
        markClosedLocked(oldestKey)
        if (!evictionWarned) {
            evictionWarned = true
            Timber.tag("Pipeline").w(
                "Coalescer key cap (%d) reached — flushing the least-recently-touched burst early (#1148)",
                maxKeys,
            )
        }
        return oldest.closingValue()
    }
}
