package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.Screen
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenClassifier
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenInfo
import cloud.trotter.dashbuddy.pipeline.accessibility.model.UiNode
import cloud.trotter.dashbuddy.test.util.TestMatcherFactory
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
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

        // 1. REPLICATE THE DAGGER GRAPH
        // We manually instantiate the recognizer exactly how Hilt would do it.
        private val recognizer: ScreenClassifier by lazy {
            ScreenClassifier(
                injectedMatchers = TestMatcherFactory.createAllMatchers()
            )
        }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> {
            return try {
                TestResourceLoader.loadForParameterized(FOLDER)
            } catch (_: Exception) {
                println("⚠️ No UNKNOWN snapshots found. Create folder 'snapshots/$FOLDER' to use this tool.")
                emptyList()
            }
        }
    }

    @Test
    fun `verify unknown and analyze`() {
        println("\n╔══════════════════════════════════════════════════════════════╗")
        println("║ 🕵️ INSPECTING: $myFilename")
        println("╚══════════════════════════════════════════════════════════════╝")

        // --- STEP 1: VALIDATION (Real Production Logic) ---
        // We ask the actual ScreenRecognizer what it thinks this is.
        val identification = recognizer.identify(myNode)

        // If it returns anything OTHER than Simple(Screen.UNKNOWN), we have a problem.
        if (identification !is ScreenInfo.Simple || identification.screen != Screen.UNKNOWN) {
            val matchedType = if (identification is ScreenInfo.Simple) {
                identification.screen.name
            } else {
                identification::class.simpleName
            }

            val msg = "❌ VALIDATION FAILED: This screen is NOT unknown!\n" +
                    "   It was matched as: $matchedType\n" +
                    "   Action: Move this file to the correct regression folder."
            println(msg)
            fail(msg)
        }

        println("   ✅ CONFIRMED: Production Recognizer says this is UNKNOWN.")
        println("   🔎 GENERATING ANALYSIS REPORT...")

        // --- STEP 2: ANALYSIS ---
        // (X-Ray Logic remains the same)
        val candidates = collectMatcherCandidates(myNode)

        if (candidates.isEmpty()) {
            println("   ❌ No usable text or IDs found in this tree!")
            return
        }

        // Text Anchors
        val textNodes = candidates.filter { !it.text.isNullOrBlank() }
        if (textNodes.isNotEmpty()) {
            println("\n   🔤 TEXT ANCHORS (Use for unique phrases):")
            textNodes.forEach { println("      • \"${it.text}\"") }
        }

        // Content Descriptions
        val descNodes = candidates.filter { !it.contentDescription.isNullOrBlank() }
        if (descNodes.isNotEmpty()) {
            println("\n   🏷️ CONTENT DESCRIPTIONS (Icons/Buttons):")
            descNodes.forEach { println("      • \"${it.contentDescription}\"") }
        }

        // Resource IDs
        val idNodes = candidates.filter { !it.viewIdResourceName.isNullOrBlank() }
        if (idNodes.isNotEmpty()) {
            println("\n   🆔 RESOURCE IDs (Use with caution):")
            idNodes.forEach {
                println("      • ${it.viewIdResourceName}  (Class: ${it.className})")
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