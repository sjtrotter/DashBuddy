package cloud.trotter.dashbuddy.core.pipeline.census

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1160 review AD4 — the frame-rule-OFF diagnostic never ships: `main` holds no `diagnostics` package and no
 * main source constructs a `FrameFilter` with the frame rule off. It lives in `src/testFixtures` only.
 */
class DiagnosticsNotInMainTest {

    private val main = File("src/main/java/cloud/trotter/dashbuddy/core/pipeline")

    @Test
    fun `main has no diagnostics package and no frame-rule-off construction`() {
        assertTrue("run from the :core:pipeline module dir", main.isDirectory)
        val diagnostics = File(main, "census/diagnostics")
        assertTrue(!diagnostics.exists() || diagnostics.walkTopDown().none { it.isFile })
        val offSwitch = main.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("frameLevel = false") }.map { it.name }.toList()
        assertTrue(offSwitch.toString(), offSwitch.isEmpty())
        assertTrue(File("src/testFixtures/java/cloud/trotter/dashbuddy/core/pipeline/census/diagnostics/DiagnosticSkeletonBuilder.kt").isFile)
    }
}
