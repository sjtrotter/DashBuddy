package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.pipeline.model.UiNode
import cloud.trotter.dashbuddy.pipeline.recognition.screen.Screen
import cloud.trotter.dashbuddy.pipeline.recognition.screen.matchers.LegacyEnumMatcher
import cloud.trotter.dashbuddy.test.base.BaseParameterizedTest
import cloud.trotter.dashbuddy.test.base.SnapshotTestStats
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import org.junit.AfterClass
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class TimelineRegressionTest(filename: String, node: UiNode) :
    BaseParameterizedTest(filename, node) {

    override val stats = sharedStats

    companion object {
        private const val FOLDER = "TIMELINE_VIEW"
        val sharedStats = SnapshotTestStats(FOLDER)

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> {
            val data = TestResourceLoader.loadForParameterized(FOLDER)
            sharedStats.reset(data.size)
            return data
        }

        @JvmStatic
        @AfterClass
        fun tearDown() = sharedStats.printFooter()
    }

    @Test
    fun `validate snapshot`() {
        val matcher = LegacyEnumMatcher(Screen.TIMELINE_VIEW)

        runTest(matcher)
    }
}