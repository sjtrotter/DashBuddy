package cloud.trotter.dashbuddy.core.pipeline.accessibility.event.coalesce

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
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
 * - Leading edge (opt-in, [leadingEdge], #1148 review F5/G3/G4/H7): a burst that opens on a key
 *   with NO emission in the last [maxWaitMs] emits its opening event IMMEDIATELY (an accumulator
 *   of one), so the first frame of a transition after idle carries no quiet/max delay. The lead
 *   is sent by a launched sender that HOLDS an emission permit, never on the collector (H7: a
 *   collector suspended in `send` stops collection and the hot upstream drops raw events); with
 *   no free permit (a stalled consumer) there is no lead and the burst simply merges. The
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
 * removed and replaced by a fresh burst with fresh timers — live coroutines stay ≤ 4 × [maxKeys]
 * (a sender blocked in `send` + the open burst's max timer, its ONE quiet job (H8) and that job's
 * pending timed wait, per key; plus, with [leadingEdge], at most one short-lived cooldown job per
 * key) and no event is lost: when the consumer resumes, the merged burst carries every event that
 * arrived meanwhile.
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
        /** Bumped per event; the quiet timer only flushes if no event arrived since it last woke. */
        @Volatile var quietGen = 0L

        /** Conflated "an event arrived" signal to the burst's ONE quiet job (H8). */
        val pokes = Channel<Unit>(Channel.CONFLATED)
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
        lock.withLock {
            val burst = bursts[key]
            if (burst == null) {
                if (bursts.size >= maxKeys) {
                    evictOldestLocked()?.let { v -> if (!sendWithPermitLocked(v)) evicted = v }
                }
                val first = merge(null, value)
                // H7: a lead is sent by a launched sender HOLDING an emission permit — never on the
                // collector, whose suspension would stall collection and let the hot upstream drop
                // raw events. No permit (a stalled consumer) → no lead: the burst opens with the
                // event and merges.
                val opened = if (leadingEdge && key !in recentlyEmitted && sendWithPermitLocked(first)) {
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
                startQuietLocked(key, opened)
            } else {
                burst.acc = merge(burst.acc, value)
                // H8: no cancel/relaunch per raw event — bump the generation and poke the one
                // long-lived quiet job, which restarts its quiet window.
                burst.quietGen++
                burst.pokes.trySend(Unit)
            }
        }
        // An eviction that found no free permit is sent here, on the collector (backpressure — it
        // cannot be dropped); with a single-key or few-key stream this path is not reached.
        evicted?.let { scope.send(it) }
    }

    /**
     * Launches a sender for [value] if an emission permit is free RIGHT NOW (H7), holding the
     * permit through `send`. Returns false (nothing launched) when none is.
     */
    private fun sendWithPermitLocked(value: A): Boolean {
        if (!permits.tryAcquire()) return false
        // The permit is released from the job's COMPLETION handler, not a `finally` in its body:
        // a body that never starts (the collector is cancelled while the sender is still queued)
        // runs no `finally`, and the permit would leak with it (PR #1150 review round 4).
        // `invokeOnCompletion` fires on every terminal state, including cancel-before-start.
        scope.launch { scope.send(value) }.invokeOnCompletion { permits.release() }
        return true
    }

    /**
     * The burst's ONE quiet job (#1148 review H8 — a map pan is hundreds of raw events a second, and
     * cancelling + relaunching a coroutine per event was the old cost). It waits up to [quietMs] for
     * a poke; a poke restarts the window, a timeout flushes — so the emission still lands exactly
     * [quietMs] after the burst's last event. A flush that finds a newer generation (an event raced
     * the timeout) keeps looping; one that finds the burst closed ends the job.
     */
    @OptIn(ExperimentalCoroutinesApi::class) // select.onTimeout
    private fun startQuietLocked(key: K, burst: Burst) {
        burst.quietJob = scope.launch {
            while (true) {
                val gen = burst.quietGen
                // Wait for a poke (restart the window) or the quiet timeout. The wait costs one
                // short-lived child of this job while it is pending (measured under
                // kotlinx-coroutines 1.10) — per burst, never per raw event.
                val poked = select<Boolean> {
                    burst.pokes.onReceive { true }
                    onTimeout(quietMs) { false }
                }
                if (poked) continue
                if (flush(key, burst, quietGen = gen)) return@launch
            }
        }
    }

    /**
     * Closes [burst] if it is still the open burst for [key] (and, for the quiet timer, no event
     * arrived since it last woke), emitting its value unless the leading emission already covered
     * it. The emission permit is taken BEFORE the burst is removed, so under a stalled consumer
     * the burst stays open and merging (F2). Only the OTHER timer is cancelled — the caller is one
     * of them and must not cancel itself before `send`. Returns false only for a stale quiet wake
     * (keep waiting); true once the burst is no longer open.
     */
    private suspend fun flush(key: K, burst: Burst, quietGen: Long?): Boolean {
        // Cheap pre-check under the lock BEFORE queuing for a permit (round 4): a dead wake (the max
        // timer of a burst the quiet job already closed, or a stale quiet wake) must not sit in
        // the semaphore's FIFO ahead of live flushes and consume a freed permit for nothing. The
        // same checks are repeated under the lock after the acquire — the permit wait is a window.
        lock.withLock {
            if (bursts[key] !== burst) return true
            if (quietGen != null && burst.quietGen != quietGen) return false
        }
        permits.acquire()
        try {
            val value = lock.withLock {
                if (bursts[key] !== burst) return true // already closed — the caller is done
                if (quietGen != null && burst.quietGen != quietGen) return false // stale: keep waiting
                bursts.remove(key)
                if (quietGen == null) burst.quietJob?.cancel() else burst.maxJob?.cancel()
                burst.acc?.also { markEmittedLocked(key) } // a silent close starts no cooldown (G3)
            } ?: return true
            scope.send(value)
            return true
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
