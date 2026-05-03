package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.rules.ScreenRuleset
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class UnknownScreenAnalysisTest(filename: String, node: UiNode) {

    private val myFilename = filename
    private val myNode = node

    companion object {
        private const val FOLDER = "UNKNOWN"

        private val screenRuleset: ScreenRuleset by lazy {
            TestRulesetFactory.screenRuleset
        }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> {
            return try {
                TestResourceLoader.loadForParameterized(FOLDER)
            } catch (_: Exception) {
                println("No UNKNOWN snapshots found. Create folder 'snapshots/$FOLDER' to use this tool.")
                emptyList()
            }
        }
    }

    @Test
    fun `verify unknown and analyze`() {
        println("\n  INSPECTING: $myFilename")

        // --- STEP 1: VALIDATION (JSON ruleset) ---
        val result = screenRuleset.matchFirst(myNode)
        val identifiedScreen = result?.target ?: "UNKNOWN"

        // If it returns anything OTHER than UNKNOWN, we have a problem.
        if (identifiedScreen != "UNKNOWN") {
            val msg = "VALIDATION FAILED: This screen is NOT unknown!\n" +
                    "   It was matched as: $identifiedScreen\n" +
                    "   Action: Move this file to the correct regression folder."
            println(msg)
            fail(msg)
        }

        println("   CONFIRMED: Ruleset says this is UNKNOWN.")
        println("   GENERATING ANALYSIS REPORT...")

        // --- STEP 2: ANALYSIS ---
        val candidates = collectMatcherCandidates(myNode)

        if (candidates.isEmpty()) {
            println("   No usable text or IDs found in this tree!")
            return
        }

        // Text Anchors
        val textNodes = candidates.filter { !it.text.isNullOrBlank() }
        if (textNodes.isNotEmpty()) {
            println("\n   TEXT ANCHORS (Use for unique phrases):")
            textNodes.forEach { println("      \"${it.text}\"") }
        }

        // Content Descriptions
        val descNodes = candidates.filter { !it.contentDescription.isNullOrBlank() }
        if (descNodes.isNotEmpty()) {
            println("\n   CONTENT DESCRIPTIONS (Icons/Buttons):")
            descNodes.forEach { println("      \"${it.contentDescription}\"") }
        }

        // Resource IDs
        val idNodes = candidates.filter { !it.viewIdResourceName.isNullOrBlank() }
        if (idNodes.isNotEmpty()) {
            println("\n   RESOURCE IDs (Use with caution):")
            idNodes.forEach {
                println("      ${it.viewIdResourceName}  (Class: ${it.className})")
            }
        }
        println("\n---------------------------------------------------")
    }

    private fun collectMatcherCandidates(root: UiNode): List<UiNode> {
        val results = mutableListOf<UiNode>()
        fun walk(node: UiNode) {
            if (!node.text.isNullOrBlank() ||
                !node.contentDescription.isNullOrBlank() ||
                !node.viewIdResourceName.isNullOrBlank()
            ) {
                results.add(node)
            }
            node.children.forEach { walk(it) }
        }
        walk(root)
        return results
    }
}
