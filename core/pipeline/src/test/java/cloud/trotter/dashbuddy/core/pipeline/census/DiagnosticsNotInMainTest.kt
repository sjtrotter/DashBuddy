package cloud.trotter.dashbuddy.core.pipeline.census

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1160 reviews AD4, AE2 — the frame-rule-OFF diagnostic never ships. Checked against BUILT output, not
 * source: the compiled main classes this very test runs against (and every other main/release class
 * directory or `classes.jar` under `build/intermediates`) hold no `…census.diagnostics` class, while the
 * test-fixtures output DOES hold `DiagnosticSkeletonBuilder` (the negative control proving the scan reads
 * real class files). Fails LOUD when no compiled main output is found — CI always compiles main first.
 */
class DiagnosticsNotInMainTest {

    private val diagnosticsPath = "cloud/trotter/dashbuddy/core/pipeline/census/diagnostics/"
    private val fixtureClass = "cloud.trotter.dashbuddy.core.pipeline.census.diagnostics.DiagnosticSkeletonBuilder"

    private fun location(cls: Class<*>): File {
        val source = checkNotNull(cls.protectionDomain?.codeSource?.location) { "no code source for ${cls.name}" }
        return File(source.toURI())
    }

    private fun diagnosticsClassesIn(root: File): List<String> = when {
        root.isDirectory -> File(root, diagnosticsPath).walkTopDown().filter { it.isFile && it.extension == "class" }.map { it.path }.toList()
        root.isFile && root.extension == "jar" -> ZipFile(root).use { zip ->
            zip.entries().toList().map { it.name }.filter { it.startsWith(diagnosticsPath) && it.endsWith(".class") }
        }
        else -> emptyList()
    }

    /**
     * The `:core:pipeline` module dir, resolved from [SkeletonBuilder]'s compiled code source (review AH2 —
     * never the working directory): walk up to the dir that holds this module's `build.gradle.kts` and its
     * main source tree.
     */
    private fun moduleDir(): File {
        var dir: File? = location(SkeletonBuilder::class.java)
        while (dir != null) {
            if (File(dir, "build.gradle.kts").isFile && File(dir, "src/main/java/cloud/trotter/dashbuddy/core/pipeline").isDirectory) return dir
            dir = dir.parentFile
        }
        error("no :core:pipeline module dir above ${location(SkeletonBuilder::class.java)}")
    }

    /**
     * Main (non-test, non-fixture) class outputs of this module under `build/intermediates`, in ONE walk
     * (review AH2): every dir holding `SkeletonBuilder.class`, and every `classes.jar`.
     */
    private fun mainOutputs(): List<File> {
        val marker = "cloud/trotter/dashbuddy/core/pipeline/census/SkeletonBuilder.class"
        fun isTestOrFixture(f: File) = listOf("TestFixtures", "UnitTest", "AndroidTest").any { f.path.contains(it, ignoreCase = true) }
        val outputs = ArrayList<File>()
        File(moduleDir(), "build/intermediates").walkTopDown().filter { it.isFile }.forEach { f ->
            when {
                isTestOrFixture(f) -> Unit
                f.path.endsWith(marker) -> outputs += File(f.path.removeSuffix(marker))
                f.name == "classes.jar" -> outputs += f
            }
        }
        return outputs
    }

    @Test
    fun `no diagnostics class in any compiled main output`() {
        val main = location(SkeletonBuilder::class.java)
        assertTrue("SkeletonBuilder's output must not be the fixtures output: $main", !main.path.contains("TestFixtures", ignoreCase = true))
        val outputs = mainOutputs()
        // FAIL loud (AH2): the running main output itself is found by the walk, never appended to it.
        assertTrue("no compiled main class output under ${File(moduleDir(), "build/intermediates")}", outputs.isNotEmpty())
        assertTrue("the running main output $main was not found by the walk", outputs.any { it.canonicalFile == main.canonicalFile })
        val leaked = outputs.flatMap { diagnosticsClassesIn(it) }
        assertEquals(emptyList<String>(), leaked)
    }

    @Test
    fun `negative control - the test-fixtures output holds the diagnostic class`() {
        val fixture = Class.forName(fixtureClass)
        val fixtures = location(fixture)
        assertTrue("fixtures output not found at $fixtures", fixtures.exists())
        assertTrue(fixtures.path, diagnosticsClassesIn(fixtures).any { it.endsWith("DiagnosticSkeletonBuilder.class") })
        assertTrue(fixtures != location(SkeletonBuilder::class.java))
    }
}
