package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.pipeline.model.UiNode
import cloud.trotter.dashbuddy.pipeline.recognition.Screen
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenInfo
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenRecognizer
import cloud.trotter.dashbuddy.test.base.BaseParameterizedTest
import cloud.trotter.dashbuddy.test.base.SnapshotTestStats
import cloud.trotter.dashbuddy.test.util.TestMatcherFactory
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@RunWith(Parameterized::class)
class InboxProcessorTest(
    private val filename: String,
    private val node: UiNode
) : BaseParameterizedTest(filename, node) {

    override val stats = sharedStats

    companion object {
        private const val INBOX_FOLDER = "INBOX"
        val sharedStats = SnapshotTestStats(INBOX_FOLDER)

        private val recognizer: ScreenRecognizer by lazy {
            ScreenRecognizer(TestMatcherFactory.createAllMatchers())
        }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> {
            // 1. Load Data
            val data = try {
                TestResourceLoader.loadForParameterized(INBOX_FOLDER)
            } catch (_: Exception) {
                emptyList()
            }

            // 2. Handle Empty Inbox
            if (data.isEmpty()) {
                // If empty, reset stats to 0. We do NOT print the header here.
                sharedStats.reset(0)
                // Return dummy so JUnit doesn't crash
                return listOf(arrayOf("EMPTY_INBOX", UiNode()))
            }

            // 3. Reset stats for the upcoming run
            sharedStats.reset(data.size)
            return data
        }

        /**
         * 🆕 NEW: Prints the header when the Test Class node starts.
         * This puts the output inside "InboxProcessorTest" in the UI.
         */
        @JvmStatic
        @BeforeClass
        fun setUp() {
            // Only print if there are actual files (optional check)
            sharedStats.printHeader()
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            sharedStats.printFooter()
        }
    }

    @Test
    fun `process inbox file`() {
        // --- 0. CHECK FOR THE DUMMY TOKEN ---
        if (filename == "EMPTY_INBOX") {
            println("\n   🎉 Inbox is empty!")
            return
        }

        println("\n  📥 Processing: $filename")

        // --- STEP 1: TOXICITY CHECK ---
        val sensitiveMatcher = SensitiveScreenMatcher()
        val sensitiveMatch = sensitiveMatcher.matches(node)

        if (sensitiveMatch is ScreenInfo.Sensitive) {
            handleToxicFile()
            return
        }

        // --- STEP 2: IDENTIFICATION ---
        val identification = recognizer.identify(node)

        if (identification.screen == Screen.UNKNOWN) {
            handleUnknownFile()
        } else {
            handleKnownFile(identification)
        }
    }

    // --- HANDLERS ---

    private fun handleToxicFile() {
        println("     🚨 STATUS: TOXIC (Sensitive Data Detected)")
        println("     🔍 FULL TEXT DUMP:")
        collectAllText(node).forEach { println("       • $it") }
        org.junit.Assert.fail("Sensitive data detected in $filename. Redact and move manually.")
    }

    private fun handleKnownFile(info: ScreenInfo) {
        val screenName = info.screen.name
        println("     ✅ STATUS: IDENTIFIED ($screenName)")
        println("     📦 ACTION: Moving to 'snapshots/$screenName'...")

        try {
            moveFile(targetFolderName = screenName)
            println("     ✨ SUCCESS: File moved.")
        } catch (e: Exception) {
            println("     ❌ ERROR: Could not move file: ${e.message}")
            // Note: We don't fail() here, so the test "passes" even if move fails,
            // but the error is logged. Call fail() if you want to stop the build.
        }
    }

    private fun handleUnknownFile() {
        println("     ❓ STATUS: UNKNOWN")
        println("     🔎 X-RAY REPORT (First 10 text fields):")
        collectAllText(node)
            .filter { it.length > 3 }
            .take(10)
            .forEach { println("       • $it") }
        println("        Action: File remains in INBOX for analysis.")
    }

    // --- UTILITIES ---

    private fun moveFile(targetFolderName: String) {
        val projectRoot = File("src/test/resources/snapshots")
        val source = File(projectRoot, "$INBOX_FOLDER/$filename")
        val destFolder = File(projectRoot, targetFolderName)

        if (!destFolder.exists()) {
            println("        (Creating new folder: $targetFolderName)")
            destFolder.mkdirs()
        }

        val dest = File(destFolder, filename)
        Files.move(source.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun collectAllText(root: UiNode): List<String> {
        val results = mutableListOf<String>()
        fun walk(n: UiNode) {
            if (!n.text.isNullOrBlank()) results.add(n.text)
            n.children.forEach { walk(it) }
        }
        walk(root)
        return results
    }
}