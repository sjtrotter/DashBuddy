package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.pipeline.accessibility.model.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.pipeline.accessibility.screen.matchers.IdleMapMatcher
import cloud.trotter.dashbuddy.test.base.BaseParameterizedTest
import cloud.trotter.dashbuddy.test.base.SnapshotTestStats
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import org.junit.AfterClass
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class IdleMapRegressionTest(filename: String, node: UiNode) :
    BaseParameterizedTest(filename, node) {

    override val stats = sharedStats

    companion object {
        private const val FOLDER = "MAIN_MAP_IDLE"
        val sharedStats = SnapshotTestStats(FOLDER)

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> {
            val data = TestResourceLoader.loadForParameterized(FOLDER)
            // Initialize the counter with the total files found
            sharedStats.reset(data.size)
            return data
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            // Print the big footer when all tests in this class are done
            sharedStats.printFooter()
        }
    }

    @Test
    fun `validate snapshot`() {
        val matcher = IdleMapMatcher()

        runTest(matcher) { result ->
            val info = result as ScreenInfo.IdleMap
            // Print specific details nicely indented
            println("     ℹ️ Zone: ${info.zoneName}")

            assertNotEquals(
                "Parsed marketing text as Zone Name",
                "Stay busy with lots of offers",
                info.zoneName
            )
        }
    }
}