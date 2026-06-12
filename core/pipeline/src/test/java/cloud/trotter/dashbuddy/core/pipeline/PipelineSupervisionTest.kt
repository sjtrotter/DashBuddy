package cloud.trotter.dashbuddy.core.pipeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * #430 — the sensing upstream must survive its own exceptions. A crash
 * resubscribes (counted + logged); cancellation propagates untouched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PipelineSupervisionTest {

    @Test
    fun `supervised flow resubscribes after upstream crashes and keeps emitting`() = runTest {
        val stats = PipelineStats()
        var subscriptions = 0
        val flaky = flow {
            subscriptions++
            emit(subscriptions * 10)
            if (subscriptions <= 2) throw IllegalStateException("boom #$subscriptions")
            emit(subscriptions * 10 + 1)
        }

        val collected = flaky.supervised(stats, tag = "test", baseDelayMs = 10).toList()

        // Two crashes, three subscriptions; emissions before each crash are kept.
        assertEquals(listOf(10, 20, 30, 31), collected)
        assertEquals(3, subscriptions)
        assertEquals(2L, stats.restartCount)
    }

    @Test
    fun `backoff delay is applied between resubscribes (virtual time)`() = runTest {
        val stats = PipelineStats()
        var subscriptions = 0
        val start = testScheduler.currentTime
        val flaky = flow<Int> {
            subscriptions++
            if (subscriptions <= 3) throw IllegalStateException("boom")
            emit(1)
        }

        flaky.supervised(stats, tag = "test", baseDelayMs = 100).toList()

        // Linear backoff: 100*1 + 100*2 + 100*3 = 600ms of virtual delay.
        assertEquals(600L, testScheduler.currentTime - start)
        assertEquals(3L, stats.restartCount)
    }

    @Test
    fun `cancellation is never retried - it propagates`() = runTest {
        val stats = PipelineStats()
        val cancelling = flow<Int> { throw CancellationException("scope died") }

        try {
            cancelling.supervised(stats, tag = "test", baseDelayMs = 10).toList()
            fail("expected CancellationException to propagate")
        } catch (e: CancellationException) {
            assertTrue(e.message!!.contains("scope died"))
        }
        assertEquals("a cancellation must not count as a restart", 0L, stats.restartCount)
    }
}
