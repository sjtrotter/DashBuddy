package cloud.trotter.dashbuddy.guard

import java.io.File

/**
 * The ONE repo-root locator for `:app` source-scan tests (#1151 review MM10 — it had four copies):
 * walk up from the test's working directory to the first directory holding `settings.gradle.kts`.
 *
 * Scope (review NN10): this is the `:app` owner only. `:core:pipeline` keeps its own copy
 * (`RuleRegexEngineGuardTest.locateRepoRoot`, plus the inline walk in `RuleCorpusCompileBudgetTest`)
 * — sharing across modules would need a test-fixtures artifact, deliberately out of scope here.
 */
object RepoRoot {
    fun locate(): File {
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
