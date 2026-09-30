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

    /** Main (non-test, non-fixture) class outputs of this module: dirs holding `SkeletonBuilder.class`, and jars. */
    private fun mainOutputs(): List<File> {
        val intermediates = File("build/intermediates")
        val marker = "cloud/trotter/dashbuddy/core/pipeline/census/SkeletonBuilder.class"
        fun isTestOrFixture(f: File) = f.path.contains("TestFixtures", ignoreCase = true) ||
            f.path.contains("UnitTest", ignoreCase = true) || f.path.contains("AndroidTest", ignoreCase = true)
        val dirs = intermediates.walkTopDown()
            .filter { it.isFile && it.path.endsWith(marker) }
            .map { File(it.path.removeSuffix(marker)) }
            .filterNot { isTestOrFixture(it) }
        val jars = intermediates.walkTopDown().filter { it.isFile && it.name == "classes.jar" }.filterNot { isTestOrFixture(it) }
        return (dirs + jars).toList()
    }

    @Test
    fun `no diagnostics class in any compiled main output`() {
        val main = location(SkeletonBuilder::class.java)
        assertTrue("compiled main output not found at $main", main.exists())
        assertTrue("SkeletonBuilder's output must not be the fixtures output: $main", !main.path.contains("TestFixtures", ignoreCase = true))
        val outputs = (mainOutputs() + main).distinct()
        assertTrue("no compiled main output under build/intermediates", outputs.isNotEmpty())
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
