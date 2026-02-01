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
import org.junit.Assert.fail
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
            // We use a try-catch in case the folder doesn't exist at all
            val data = try {
                TestResourceLoader.loadForParameterized(INBOX_FOLDER)
            } catch (_: Exception) {
                emptyList()
            }

            // 2. The "Empty Inbox" Fix
            if (data.isEmpty()) {
                // Return a single DUMMY item so JUnit doesn't crash.
                // We use a blank UiNode() since we won't actually read it.
                return listOf(arrayOf("EMPTY_INBOX", UiNode()))
            }

            sharedStats.reset(data.size)
            return data
        }

        @JvmStatic
        @AfterClass
        fun tearDown() = sharedStats.printFooter()
    }

    @Test
    fun `process inbox file`() {
        // --- 0. CHECK FOR THE DUMMY TOKEN ---
        if (filename == "EMPTY_INBOX") {
            println("\n🎉 INBOX IS EMPTY! No files to process.")
            println("   (This is a good thing. It means you are caught up.)")
            return // Pass the test immediately
        }

        stats.onTestStart()
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

    // --- HANDLERS (Same as before) ---

    private fun handleToxicFile() {
        println("     🚨 STATUS: TOXIC (Sensitive Data Detected)")
        println("     🔍 FULL TEXT DUMP:")
        collectAllText(node).forEach { println("       • $it") }
        fail("Sensitive data detected in $filename. Redact and move manually.")
    }

    private fun handleKnownFile(info: ScreenInfo) {
        val screenName = info.screen.name
        println("     ✅ STATUS: IDENTIFIED ($screenName)")
        println("     📦 ACTION: Moving to 'snapshots/$screenName'...")

        try {
            moveFile(targetFolderName = screenName)
            println("     ✨ SUCCESS: File moved.")
            stats.recordSuccess()
        } catch (e: Exception) {
            println("     ❌ ERROR: Could not move file: ${e.message}")
            stats.recordSuccess()
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
        stats.recordSuccess()
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