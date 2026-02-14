package cloud.trotter.dashbuddy.test.base

import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenInfo
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.model.UiNode
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.rules.TestWatcher
import org.junit.runner.Description

abstract class BaseParameterizedTest(
    private val filename: String,
    private val node: UiNode
) {
    // Child classes must provide this
    abstract val stats: SnapshotTestStats

    // This runs automatically before every test method
    init {
        // We defer this call until the test actually starts so `stats` is initialized
        // Use a lazy hook or call it inside runTest if init causes issues with property access order
    }

    @get:Rule
    val watchman = object : TestWatcher() {
        override fun succeeded(description: Description?) {
            stats.recordSuccess()
        }
    }

    protected fun runTest(
        matcher: ScreenMatcher,
        customChecks: (ScreenInfo) -> Unit = {}
    ) {
        // Trigger Header (will only print once per class)
        stats.onTestStart()

        println("\n  📸 Checking: $filename")
        // println("     Matcher: ${matcher::class.simpleName}")

        val result = matcher.matches(node)
        val resultName = result?.javaClass?.simpleName ?: "NULL"

        if (result == null) {
            println("     ❌ RESULT: NULL (Expected Match)")
        } else {
            println("     ✅ RESULT: $resultName")
        }

        assertNotNull("Matcher returned NULL for $filename", result)

        try {
            if (result != null) {
                customChecks(result)
            }
        } catch (e: AssertionError) {
            println("     ❌ CUSTOM CHECK FAILED: ${e.message}")
            throw e
        }
    }
}