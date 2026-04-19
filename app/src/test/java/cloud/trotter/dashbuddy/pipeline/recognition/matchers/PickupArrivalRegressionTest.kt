package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.order.PickupStatus
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.PickupArrivalMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.PickupArrivalParser
import cloud.trotter.dashbuddy.test.base.BaseParameterizedTest
import cloud.trotter.dashbuddy.test.base.SnapshotTestStats
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class PickupArrivalRegressionTest(filename: String, node: UiNode) :
    BaseParameterizedTest(filename, node) {

    override val stats = sharedStats

    companion object {
        private const val FOLDER = "PICKUP_DETAILS_POST_ARRIVAL_PICKUP_SINGLE"
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
        val matcher = PickupArrivalMatcher()
        val parser = PickupArrivalParser()

        runTest(matcher, parser) { result ->
            val info = result as ScreenInfo.PickupDetails
            assertEquals(Screen.PICKUP_DETAILS_POST_ARRIVAL_PICKUP_SINGLE, info.screen)
            assertEquals("Status should always be ARRIVED", PickupStatus.ARRIVED, info.status)
            println("      Store: ${info.storeName}  Customer hash: ${info.customerNameHash?.take(8)}...")
        }
    }
}
