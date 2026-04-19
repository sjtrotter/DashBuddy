package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers.OfferMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers.OfferParser
import cloud.trotter.dashbuddy.test.base.BaseParameterizedTest
import cloud.trotter.dashbuddy.test.base.SnapshotTestStats
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class OfferMatcherRegressionTest(filename: String, node: UiNode) :
    BaseParameterizedTest(filename, node) {

    override val stats = sharedStats

    companion object {
        private const val FOLDER = "OFFER_POPUP"
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
        val matcher = OfferMatcher()
        val parser = OfferParser()

        runTest(matcher, parser) { result ->
            val info = result as ScreenInfo.Offer
            assertEquals(Screen.OFFER_POPUP, info.screen)
            assertNotNull("Pay amount must be present", info.parsedOffer.payAmount)
            println("      Pay: $${info.parsedOffer.payAmount}  Dist: ${info.parsedOffer.distanceMiles} mi  Orders: ${info.parsedOffer.orders.size}")
        }
    }
}
