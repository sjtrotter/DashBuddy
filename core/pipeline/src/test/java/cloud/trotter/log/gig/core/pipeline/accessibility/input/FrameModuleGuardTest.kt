package cloud.trotter.log.gig.core.pipeline.accessibility.input

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class FrameModuleGuardTest {
    private fun mainSources(): File {
        val working = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        return generateSequence(working) { it.parentFile }
            .map { File(it, "core/pipeline/src/main") }
            .firstOrNull { it.isDirectory }
            ?: error("Cannot find core/pipeline/src/main from $working")
    }

    @Test fun `main pipeline never defaults a cache generation`() {
        val defaultGeneration = Regex("\\bgen\\s*:\\s*Long\\s*=")
        val violations = mainSources().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { defaultGeneration.containsMatchIn(it.readText()) }
            .map { it.path }.toList()
        assertEquals(emptyList<String>(), violations)
    }

    @Test fun `frame resolver policy and generic walk cannot import accessibility framework types`() {
        val module = File(mainSources(), "java/cloud/trotter/log/gig/core/pipeline/accessibility/input")
        val files = listOf("FrameWindowResolver.kt", "FramePolicy.kt", "FrameWindowAccess.kt", "FrameWindowResults.kt", "FrontWindowWalk.kt")
        val frameworkImport = Regex("(?m)^\\s*import\\s+android\\.view\\.accessibility(?:\\.|\\s|$)")
        for (name in files) {
            val file = File(module, name)
            assertTrue("guard must inspect $name", file.isFile)
            assertFalse(name, frameworkImport.containsMatchIn(file.readText()))
        }
    }
}
