package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.pipeline.model.UiNode
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenInfo
import cloud.trotter.dashbuddy.test.base.BaseParameterizedTest
import cloud.trotter.dashbuddy.test.base.SnapshotTestStats
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import org.junit.AfterClass
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class SensitiveDataScanTest(
    private val filename: String,
    private val node: UiNode
) : BaseParameterizedTest(filename, node) {

    override val stats = sharedStats

    companion object {
        private const val FOLDER = "INBOX"
        val sharedStats = SnapshotTestStats(FOLDER)

        // Duplicate keywords here for reporting purposes (or make them public in the Matcher)
        private val SENSITIVE_KEYWORDS = listOf(
            "Bank Account", "Routing Number", "Verify Identity", "Social Security",
            "Crimson", "Biometric", "Available Balance", "View card details",
            "Linked accounts", "Debit card", "Account number", "Statements & documents",
            "Card status", "Lock card", "Emergency contact details", "Withdraw"
        )

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> {
            return try {
                val data = TestResourceLoader.loadForParameterized(FOLDER)
                sharedStats.reset(data.size)
                data
            } catch (_: Exception) {
                println("⚠️ No UNKNOWN snapshots found.")
                emptyList()
            }
        }

        @JvmStatic
        @AfterClass
        fun tearDown() = sharedStats.printFooter()
    }

    @Test
    fun `scan for sensitive data`() {
        stats.onTestStart()
        println("\n  📸 Scanning: $filename")

        val matcher = SensitiveScreenMatcher()
        val result = matcher.matches(node)

        if (result is ScreenInfo.Sensitive) {
            println("     🚨 STATUS: TOXIC (Sensitive Data Detected)")

            // --- REPORTING LOGIC ---
            // Re-scan the tree to find exactly WHAT triggered it so you can see it
            val triggers = findTriggers(node)
            println("     🔍 EVIDENCE:")
            triggers.forEach { (text, keyword) ->
                println("        • Found \"$text\" (Matched keyword: '$keyword')")
            }

            println("     📝 ACTION: Redact these values in the JSON file immediately.")

            // Fail the test so it shows as RED in Android Studio
            fail("Sensitive data detected in $filename! See logs for details.")
        } else {
            println("     ✅ STATUS: CLEAN")
            stats.recordSuccess()
        }
    }

    // Helper to find the specific text nodes that caused the match
    private fun findTriggers(root: UiNode): List<Pair<String, String>> {
        val found = mutableListOf<Pair<String, String>>()

        fun walk(n: UiNode) {
            val text = n.text ?: ""
            // Check if this node matches any keyword
            val matchedKeyword = SENSITIVE_KEYWORDS.firstOrNull {
                text.contains(it, ignoreCase = true)
            }

            if (matchedKeyword != null) {
                found.add(text to matchedKeyword)
            }

            n.children.forEach { walk(it) }
        }

        walk(root)
        return found
    }
}