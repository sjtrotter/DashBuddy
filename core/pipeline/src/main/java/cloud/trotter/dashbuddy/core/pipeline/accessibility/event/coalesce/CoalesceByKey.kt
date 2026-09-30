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
 * - Leading edge (opt-in, [leadingEdge], #1148 review F5/G3/G4): a burst that opens on a key
 *   with NO emission in the last [maxWaitMs] emits its opening event IMMEDIATELY (an accumulator
 *   of one), so the first frame of a transition after idle carries no quiet/max delay. The
 *   burst's accumulator then restarts EMPTY, so its later quiet/max flush carries only the events
 *   merged after the lead and emits only if there are any — a lone event yields ONE emission, and
 *   the emitted counts always sum to the raw event count. The cooldown is anchored on the key's
 *   last EMISSION (a lead, a flush that emits, an eviction that emits); a silent close starts
 *   none. It is a per-key `delay(maxWaitMs)` job in a map bounded at [maxKeys]. Off by default.
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
    /**
     * An open burst. [acc] is null only for a leading burst with nothing merged since its lead
     * (G4) — closing it then emits nothing.
     */
    private inner class Burst(var acc: A?) {
        /** Bumped per event; a quiet timer only fires if no event arrived after it was armed. */
        var quietGen = 0L
        var quietJob: Job? = null
        var maxJob: Job? = null
    }

    private val lock = Mutex()

    /** Emission permits: bounds the flushes in flight (#1148 review F2). */
    private val permits = Semaphore(maxKeys)

    /** Access-ordered: iteration starts at the least-recently-touched open burst. */
    private val bursts = LinkedHashMap<K, Burst>(16, 0.75f, true)

    /** Keys that EMITTED within the last [maxWaitMs] (leading-edge cooldown, G3); insertion-ordered. */
    private val recentlyEmitted = LinkedHashMap<K, Job>()
    private var evictionWarned = false

    suspend fun onEvent(key: K, value: T) {
        var evicted: A? = null
        var leading: A? = null
        lock.withLock {
            val burst = bursts[key]
            if (burst == null) {
                if (bursts.size >= maxKeys) evicted = evictOldestLocked()
                val first = merge(null, value)
                val opened = if (leadingEdge && key !in recentlyEmitted) {
                    leading = first
                    markEmittedLocked(key)
                    Burst(acc = null) // G4: the trailing flush carries only post-lead events
                } else {
                    Burst(acc = first)
                }
                bursts[key] = opened
                opened.maxJob = scope.launch {
                    delay(maxWaitMs)
                    flush(key, opened, quietGen = null)
                }
                armQuietLocked(key, opened)
            } else {
                burst.acc = merge(burst.acc, value)
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
                burst.acc?.also { markEmittedLocked(key) } // a silent close starts no cooldown (G3)
            } ?: return
            scope.send(value)
        } finally {
            permits.release()
        }
    }

    /** (Re)starts [key]'s leading-edge cooldown at an EMISSION (G3); a no-op when leading is off. */
    private fun markEmittedLocked(key: K) {
        if (!leadingEdge) return
        recentlyEmitted.remove(key)?.cancel()
        if (recentlyEmitted.size >= maxKeys) {
            val oldest = recentlyEmitted.keys.first()
            recentlyEmitted.remove(oldest)?.cancel()
        }
        lateinit var cooldown: Job
        cooldown = scope.launch {
            delay(maxWaitMs)
            lock.withLock { if (recentlyEmitted[key] === cooldown) recentlyEmitted.remove(key) }
        }
        recentlyEmitted[key] = cooldown
    }

    private fun evictOldestLocked(): A? {
        val (oldestKey, oldest) = bursts.entries.first().let { it.key to it.value }
        bursts.remove(oldestKey)
        oldest.quietJob?.cancel()
        oldest.maxJob?.cancel()
        oldest.acc?.let { markEmittedLocked(oldestKey) }
        if (!evictionWarned) {
            evictionWarned = true
            Timber.tag("Pipeline").w(
                "Coalescer key cap (%d) reached — flushing the least-recently-touched burst early (#1148)",
                maxKeys,
            )
        }
        return oldest.acc
    }
}
