package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.pipeline.model.UiNode
import cloud.trotter.dashbuddy.pipeline.recognition.screen.ScreenInfo
import cloud.trotter.dashbuddy.pipeline.recognition.screen.matchers.SensitiveScreenMatcher
import cloud.trotter.dashbuddy.test.base.BaseParameterizedTest
import cloud.trotter.dashbuddy.test.base.SnapshotTestStats
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class SensitiveScreenRegressionTest(filename: String, node: UiNode) :
    BaseParameterizedTest(filename, node) {

    override val stats = sharedStats

    companion object {
        // IMPORTANT: Ensure this folder exists at src/test/resources/snapshots/SENSITIVE
        private const val FOLDER = "SENSITIVE"

        val sharedStats = SnapshotTestStats(FOLDER)

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> {
            return try {
                val data = TestResourceLoader.loadForParameterized(FOLDER)
                sharedStats.reset(data.size)
                data
            } catch (_: Exception) {
                // Graceful fallback if folder is missing (common for new Sensitive tests)
                println("⚠️ No SENSITIVE snapshots found. Create folder 'snapshots/$FOLDER' and add REDACTED files.")
                emptyList()
            }
        }

        @JvmStatic
        @AfterClass
        fun tearDown() = sharedStats.printFooter()
    }

    @Test
    fun `validate sensitive match`() {
        val matcher = SensitiveScreenMatcher()

        runTest(matcher) { result ->
            // Verify it specifically returns the Sensitive type
            assertTrue(
                "❌ Matcher return ${result::class.simpleName}, expected ScreenInfo.Sensitive",
                result is ScreenInfo.Sensitive
            )
        }
    }
}