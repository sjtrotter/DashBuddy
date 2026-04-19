package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.core.data.pay.PayParser
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.DeliverySummaryMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.DeliverySummaryParser
import cloud.trotter.dashbuddy.test.base.BaseParameterizedTest
import cloud.trotter.dashbuddy.test.base.SnapshotTestStats
import cloud.trotter.dashbuddy.test.util.ConsoleTree
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import org.junit.AfterClass
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import timber.log.Timber

@RunWith(Parameterized::class)
class DeliverySummaryCollapsedRegressionTest(
    filename: String,
    node: UiNode,
    breadcrumbs: List<String>
) :
    BaseParameterizedTest(filename, node) {

    override val stats = sharedStats

    companion object {
        init {
            Timber.uprootAll()
            Timber.plant(ConsoleTree())
        }

        private const val FOLDER = "DELIVERY_SUMMARY_COLLAPSED"
        val sharedStats = SnapshotTestStats(FOLDER)

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> {
            val data = TestResourceLoader.loadForParameterizedWithBreadcrumbs(FOLDER)
            sharedStats.reset(data.size)
            return data
        }

        @JvmStatic
        @AfterClass
        fun tearDown() = sharedStats.printFooter()
    }

    @Test
    fun `validate snapshot`() {
        val matcher = DeliverySummaryMatcher()
        val parser = DeliverySummaryParser(PayParser())

        runTest(matcher, parser) { result ->
            val info = result as ScreenInfo.DeliverySummary
            assertNotNull("Result should be DeliverySummary", info)
        }
    }
}