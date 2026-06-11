package cloud.trotter.dashbuddy.core.data.log

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * #364 — log lines must land in submission order. The old per-line
 * fire-and-forget launches could interleave on the IO pool and race the
 * rotation check; appends now drain through one single-writer channel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LogRepositoryTest {

    @Test
    fun `lines land in exact submission order`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = LogRepository(context, dispatcher)

        val n = 200
        repeat(n) { i -> repo.appendLog("line-$i\n") }
        advanceUntilIdle()

        val logFile = File(context.getExternalFilesDir(null) ?: context.filesDir, "app.log")
        val lines = logFile.readLines()
        assertEquals((0 until n).map { "line-$it" }, lines)
    }
}
