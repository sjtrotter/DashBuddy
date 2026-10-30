package cloud.trotter.log.gig.core.state

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source-scan ownership guard for the settle gate's writes (CLAUDE.md §3 rule (c)). */
class SessionPaySettleOwnershipGuardTest {
    @Test
    fun `the settle gate owns park and running earnings writes`() {
        val repoRoot = locateRepoRoot()
        val sourceRoot = File(repoRoot, "core/state/src/main")
        assertTrue("Missing state main sources: ${sourceRoot.path}", sourceRoot.isDirectory)
        val packagePath = "java/cloud/trotter/log/gig/core/state/"
        val gate = packagePath + "SessionPaySettleGate.kt"
        val recovery = packagePath + "RecoveryHygiene.kt"
        val owners = mapOf(
            "pendingSessionPay =" to setOf(gate, recovery),
            "runningEarnings =" to setOf(gate),
        )
        val problems = mutableListOf<String>()
        // Same walkTopDown source-scan approach as TimberTagGuardTest and IcuRegexGuardTest.
        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val sourcePath = file.relativeTo(sourceRoot).path.replace(File.separatorChar, '/')
                val repoPath = file.relativeTo(repoRoot).path.replace(File.separatorChar, '/')
                file.readLines().forEachIndexed { index, line ->
                    for ((write, allowed) in owners) {
                        if (write in line && sourcePath !in allowed) {
                            problems += "$repoPath:${index + 1}: $write — the settle gate owns " +
                                "these writes (CLAUDE.md §3 rule (c))"
                        }
                    }
                }
            }
        assertTrue(problems.sorted().joinToString("\n"), problems.isEmpty())
    }

    /** Walk up from a module or repo cwd, as in the app guards' RepoRoot.locate(). */
    private fun locateRepoRoot(): File {
        var dir = File(".").absoluteFile.normalize()
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
                ?: error(
                    "Could not locate repo root (settings.gradle.kts) walking up from " +
                        File(".").absoluteFile.normalize().path,
                )
        }
    }
}
