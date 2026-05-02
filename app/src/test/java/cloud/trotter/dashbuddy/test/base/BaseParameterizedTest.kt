package cloud.trotter.dashbuddy.test.base

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenParser
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.rules.TestWatcher
import org.junit.runner.Description

abstract class BaseParameterizedTest(
    private val filename: String,
    private val node: UiNode
) {
    // Child classes must provide this
    abstract val stats: SnapshotTestStats

    @get:Rule
    val watchman = object : TestWatcher() {
        override fun succeeded(description: Description?) {
            stats.recordSuccess()
        }
    }

    /**
     * Runs a two-phase recognition + parsing test.
     *
     * 1. Calls [matcher].matches([node]) — expects a non-null Screen.
     * 2. If [parser] is provided, calls [parser].parse([node]) to get [ParsedFields] for
     *    [customChecks]. Otherwise uses [ParsedFields.None].
     */
    protected fun runTest(
        matcher: ScreenMatcher,
        parser: ScreenParser? = null,
        customChecks: (Screen, ParsedFields) -> Unit = { _, _ -> }
    ) {
        stats.onTestStart()
        println("\n  📸 Checking: $filename")

        val screen = matcher.matches(node)
        if (screen == null) {
            println("     ❌ RESULT: NULL (Expected Match)")
            fail("Matcher returned NULL for $filename")
            return
        }
        println("     ✅ MATCHED: $screen")

        val result: ParsedFields = parser?.parse(node) ?: ParsedFields.None
        println("     ℹ️  PARSED: ${result::class.simpleName}")

        assertNotNull("Parser returned NULL for $filename", result)

        try {
            customChecks(screen, result)
        } catch (e: AssertionError) {
            println("     ❌ CUSTOM CHECK FAILED: ${e.message}")
            throw e
        }
    }
}
