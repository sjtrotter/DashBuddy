package cloud.trotter.dashbuddy.core.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.core.pipeline.rules.Ruleset
import cloud.trotter.dashbuddy.test.util.SnapshotLibrarian
import cloud.trotter.dashbuddy.test.util.SnapshotSecurityScanner
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

        private val screenRuleset: Ruleset<UiNode> by lazy {
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

        // --- STEP 1: CLASSIFY (JSON ruleset) ---
        val result = screenRuleset.matchFirst(myNode)
        val identifiedScreen = result?.intent ?: "UNKNOWN"

        // Sensitive screens are a hard stop: they must be hand-redacted and moved to
        // SENSITIVE/. Never graduate them and never run the X-ray (it prints raw text).
        val securityReport = SnapshotSecurityScanner.scan(myNode)
        if (identifiedScreen.startsWith("sensitive") || securityReport.isToxic) {
            SnapshotSecurityScanner.printReport(securityReport)
            fail("Sensitive data detected in $myFilename. Redact and move to SENSITIVE/.")
            return
        }

        // A screen becoming recognized is PROGRESS, not a failure. Graduate it into the
        // regression corpus using the same archive mechanism the inbox processor uses
        // (redact + move + prune), so it settles in a single pass. The move is visible in
        // `git status` for review — if a graduation looks wrong, the rule over-matched.
        if (identifiedScreen != "UNKNOWN") {
            println("   GRADUATED: now recognized as '$identifiedScreen' (was UNKNOWN).")
            try {
                val target = SnapshotLibrarian.archiveSnapshot(myFilename, FOLDER, identifiedScreen)
                SnapshotLibrarian.pruneFolder(target)
                println("   MOVED → snapshots/$identifiedScreen/$myFilename")
            } catch (e: Exception) {
                println("   ERROR graduating $myFilename: ${e.message}")
            }
            return
        }

        println("   CONFIRMED: still UNKNOWN.")
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
