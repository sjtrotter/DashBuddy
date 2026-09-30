package cloud.trotter.dashbuddy.guard

import java.io.File

/**
 * The ONE repo-root locator for `:app` source-scan tests (#1151 review MM10 — it had four copies):
 * walk up from the test's working directory to the first directory holding `settings.gradle.kts`.
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
