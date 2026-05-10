package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.rules.Ruleset
import cloud.trotter.dashbuddy.test.base.SnapshotTestStats
import cloud.trotter.dashbuddy.test.util.SnapshotLibrarian
import cloud.trotter.dashbuddy.test.util.SnapshotScreenDiagnostics
import cloud.trotter.dashbuddy.test.util.SnapshotSecurityScanner
import cloud.trotter.dashbuddy.test.util.SnapshotSession
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import org.junit.AfterClass
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

@RunWith(Parameterized::class)
class InboxProcessorTest(
    private val filename: String,
    private val node: UiNode,
    private val breadcrumbs: List<String>
) {

    companion object {
        private const val INBOX = "INBOX"
        val sharedStats = SnapshotTestStats(INBOX)

        private val screenRuleset: Ruleset<UiNode> by lazy {
            TestRulesetFactory.screenRuleset
        }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> {
            val data = try {
                TestResourceLoader.loadForParameterizedWithBreadcrumbs(INBOX)
            } catch (_: Exception) {
                emptyList()
            }

            if (data.isEmpty()) {
                sharedStats.reset(0)
                return listOf(arrayOf("EMPTY_INBOX", UiNode(), emptyList<String>()))
            }

            SnapshotSession.reset()
            sharedStats.reset(data.size)
            return data
        }

        @JvmStatic
        @BeforeClass
        fun setUp() = sharedStats.printHeader()

        @JvmStatic
        @AfterClass
        fun tearDown() = sharedStats.printFooter()
    }

    @Test
    fun `process inbox file`() {
        if (filename == "EMPTY_INBOX") {
            println("\n   Inbox is empty!")
            return
        }

        println("\n  Processing: $filename")
        printFileLink(INBOX, filename)

        // 1. IDENTIFY via JSON ruleset
        val result = screenRuleset.matchFirst(node)
        val identifiedScreen = result?.intent ?: "UNKNOWN"

        // 2. SECURITY SCAN
        val securityReport = SnapshotSecurityScanner.scan(node)

        // --- DECISION MATRIX ---

        // A. TOXIC
        if (identifiedScreen == "SENSITIVE" || securityReport.isToxic) {
            handleToxicFile(securityReport)
            return
        }

        // B. UNKNOWN
        if (identifiedScreen == "UNKNOWN") {
            handleUnknownFile()
            return
        }

        // C. KNOWN & CLEAN
        handleKnownFile(identifiedScreen)
    }

    // --- HANDLERS ---

    private fun handleKnownFile(screenName: String) {
        println("     STATUS: IDENTIFIED ($screenName)")
        sharedStats.recordSuccess()

        try {
            val targetFolder = SnapshotLibrarian.archiveSnapshot(
                filename = filename,
                sourceFolder = INBOX,
                targetFolder = screenName
            )
            println("     MOVED: snapshots/$screenName/$filename")
            SnapshotLibrarian.pruneFolder(targetFolder)

        } catch (e: Exception) {
            println("     ERROR: ${e.message}")
        }
    }

    private fun handleUnknownFile() {
        println("     STATUS: UNKNOWN")

        val variantResult = SnapshotSession.checkVariant(node, filename)

        if (variantResult is SnapshotSession.VariantResult.Duplicate) {
            println("     DELETING DUPLICATE")
            println("        (Identical structure to: ${variantResult.originalFilename})")

            val file = File("src/test/resources/snapshots/$INBOX/$filename")
            if (file.exists()) {
                file.delete()
            }
            return
        }

        SnapshotScreenDiagnostics.printXRay(node, breadcrumbs)
    }

    private fun handleToxicFile(report: SnapshotSecurityScanner.ScanResult) {
        SnapshotSecurityScanner.printReport(report)
        fail("Sensitive data detected in $filename.")
    }

    private fun printFileLink(folder: String, filename: String) {
        try {
            val file = File("src/test/resources/snapshots/$folder/$filename")
            println("     OPEN: ${file.absolutePath}")
        } catch (_: Exception) {
        }
    }
}
