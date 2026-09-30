package cloud.trotter.dashbuddy.core.pipeline.accessibility.event.coalesce

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber

/**
 * #1148 D3 — [coalesceByKey] semantics under virtual time: quiet gap, SCHEDULED max-wait (fires
 * with no arrival), guaranteed trailing emission, no leading edge, per-key independence, the
 * bounded key map, and cancellation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoalesceByKeyTest {

    /** A test event: its key, a change-type bit, and when (virtual ms) it is emitted. */
    private data class Ev(val key: Int, val bits: Int, val at: Long)

    private data class Acc(val key: Int, val bits: Int, val count: Int)

    private fun mergeEv(acc: Acc?, e: Ev): Acc =
        if (acc == null) Acc(e.key, e.bits, 1) else acc.copy(bits = acc.bits or e.bits, count = acc.count + 1)

    /** Emits [events] at their `at` offsets (absolute virtual time, ascending). */
    private fun timed(events: List<Ev>): Flow<Ev> = flow {
        var now = 0L
        for (e in events) {
            if (e.at > now) delay(e.at - now)
            now = e.at
            emit(e)
        }
    }

    /** Collects the coalesced stream, stamping each emission with the virtual time it left. */
    private suspend fun TestScope.run(
        events: List<Ev>,
        maxKeys: Int = COALESCE_MAX_KEYS,
    ): List<Pair<Long, Acc>> =
        timed(events)
            .coalesceByKey(quietMs = 150L, maxWaitMs = 300L, keyOf = { it.key }, merge = ::mergeEv, maxKeys = maxKeys)
            .map { testScheduler.currentTime to it }
            .toList()

    private val warnings = mutableListOf<String>()
    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority == android.util.Log.WARN) warnings += message
        }
    }

    @Before fun plant() = Timber.plant(tree)
    @After fun uproot() = Timber.uproot(tree)

    @Test
    fun `a short burst emits ONCE at last event + quiet, bits OR-ed, no leading edge`() = runTest {
        val bursts = (0 until 5).map { Ev(key = 7, bits = 1 shl it, at = it * 20L) }

        val out = run(bursts)

        assertEquals(1, out.size)
        val (time, acc) = out.single()
        assertEquals("emitted at last(80) + quiet(150)", 230L, time)
        assertEquals(5, acc.count)
        assertEquals(0b11111, acc.bits)
    }

    @Test
    fun `a continuous flood emits on the SCHEDULED max-wait and then a trailing frame`() = runTest {
        // Every 20 ms for 1 s: 0, 20, …, 980.
        val flood = (0 until 50).map { Ev(key = 1, bits = 1, at = it * 20L) }

        val out = run(flood)
        val times = out.map { it.first }

        assertEquals("max-wait cadence + trailing quiet emission", listOf(300L, 600L, 900L, 1130L), times)
        assertEquals("no event is lost across bursts", 50, out.sumOf { it.second.count })
    }

    @Test
    fun `max-wait fires with NO arrival after it (scheduled, not arrival-checked)`() = runTest {
        // Events 0..280 every 20 ms, then silence: the old operator only checked max-wait on
        // arrival; the new one must fire at 300 from its own timer (quiet would be 430).
        val events = (0..14).map { Ev(key = 1, bits = 1, at = it * 20L) }

        val out = run(events)

        assertEquals(listOf(300L), out.map { it.first })
        assertEquals(15, out.single().second.count)
    }

    @Test
    fun `interleaved keys coalesce independently`() = runTest {
        val events = listOf(
            Ev(1, 1, 0), Ev(2, 4, 10), Ev(1, 2, 20), Ev(2, 8, 30), Ev(1, 1, 40),
        )

        val out = run(events)

        assertEquals(2, out.size)
        val byKey = out.associate { it.second.key to it }
        assertEquals(190L, byKey.getValue(1).first) // last key-1 event 40 + 150
        assertEquals(Acc(1, 0b11, 3), byKey.getValue(1).second)
        assertEquals(180L, byKey.getValue(2).first) // last key-2 event 30 + 150
        assertEquals(Acc(2, 0b1100, 2), byKey.getValue(2).second)
    }

    @Test
    fun `key cap flushes the least-recently-touched burst early, WARNs once, keeps working`() = runTest {
        val events = listOf(Ev(1, 1, 0), Ev(2, 1, 0), Ev(3, 1, 0), Ev(4, 1, 0))

        val out = run(events, maxKeys = 2)

        // Key 1 evicted when key 3 arrived, key 2 when key 4 arrived — both flushed at t=0.
        assertEquals(listOf(0L to 1, 0L to 2, 150L to 3, 150L to 4).toSet(), out.map { it.first to it.second.key }.toSet())
        assertEquals("every burst still emitted exactly once", 4, out.size)
        assertEquals("one WARN per collection, not per eviction", 1, warnings.size)
    }

    @Test
    fun `no events means no emissions`() = runTest {
        val out = emptyFlow<Ev>()
            .coalesceByKey(keyOf = { it.key }, merge = ::mergeEv)
            .toList()
        assertTrue(out.isEmpty())
    }

    @Test
    fun `cancelling the collector cancels the per-key timers`() = runTest {
        val upstream = MutableSharedFlow<Ev>(extraBufferCapacity = 4)
        val emitted = mutableListOf<Acc>()
        val job = launch(StandardTestDispatcher(testScheduler)) {
            upstream.coalesceByKey(keyOf = { it.key }, merge = ::mergeEv).collect { emitted += it }
        }
        runCurrent()
        assertTrue(upstream.tryEmit(Ev(1, 1, 0)))
        assertTrue(upstream.tryEmit(Ev(2, 1, 0)))
        advanceTimeBy(50)
        runCurrent()

        job.cancel()
        advanceUntilIdle()

        assertTrue("no timer may fire after cancellation", emitted.isEmpty())
        assertTrue(job.isCancelled)
        assertTrue("no per-key job outlives the collector", job.children.none())
        assertEquals("upstream subscription released", 0, upstream.subscriptionCount.value)
    }
}
