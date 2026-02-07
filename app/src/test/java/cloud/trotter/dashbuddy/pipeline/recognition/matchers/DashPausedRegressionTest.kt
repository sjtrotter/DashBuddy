package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.pipeline.model.UiNode
import cloud.trotter.dashbuddy.pipeline.recognition.screen.ScreenInfo
import cloud.trotter.dashbuddy.pipeline.recognition.screen.matchers.DashPausedMatcher
import cloud.trotter.dashbuddy.test.base.BaseParameterizedTest
import cloud.trotter.dashbuddy.test.base.SnapshotTestStats
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import org.junit.AfterClass
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.concurrent.TimeUnit

@RunWith(Parameterized::class)
class DashPausedRegressionTest(filename: String, node: UiNode) :
    BaseParameterizedTest(filename, node) {

    override val stats = sharedStats

    companion object {
        private const val FOLDER = "DASH_PAUSED"
        val sharedStats = SnapshotTestStats(FOLDER)

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> {
            val data =
                TestResourceLoader.loadForParameterized(FOLDER)
            // Initialize the counter with the total files found
            sharedStats.reset(data.size)
            return data
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            // Print the big footer when all tests in this class are done
            IdleMapRegressionTest.sharedStats.printFooter()
        }
    }

    @Test
    fun `validate snapshot`() {
        val matcher = DashPausedMatcher()

        runTest(matcher) { result ->
            val info = result as ScreenInfo.DashPaused
            val formatted = String.format(
                "%02d:%02d",
                TimeUnit.MILLISECONDS.toMinutes(info.remainingMillis),
                TimeUnit.MILLISECONDS.toSeconds(info.remainingMillis) % 60
            )
            println("      (Resume In: $formatted)")
        }
    }
}