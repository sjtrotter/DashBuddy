package cloud.trotter.dashbuddy.log

import cloud.trotter.dashbuddy.domain.pipeline.StateMachineContract
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-scan guard for the #762 D1 drift class: a raw effect-bearing notification-intent string
 * literal (`"additional_tip"`, …) must never appear in production Kotlin in `:core:state`/`:app` —
 * intents there must be compared against the
 * [NotificationIntent][cloud.trotter.dashbuddy.domain.state.NotificationIntent] SSOT constants, not
 * hand-typed literals. The exact bug this backstops: `NotificationEffects` used to branch on a
 * literal `"additional_tip"`, so a rule/effect rename could silently desync the two sides and drop
 * a recognized tip notification.
 *
 * The forbidden literal set is **derived from the SSOT itself** —
 * [StateMachineContract.EFFECT_INTENTS]`.keys` — so adding a new effect-bearing intent automatically
 * extends this guard with no test edit. The one legitimate home for each literal is the
 * `NotificationIntent` `const val` definition, which lives in `:domain` (outside the scanned
 * modules) and so is invisible to this scan by construction.
 *
 * Follows the repo-root source-scan shape of [TimberTagGuardTest] (read `.kt` off disk rather than
 * reflecting over compiled code — this is a textual property, "does this literal appear in source",
 * not something the type system encodes). Main-type source sets only; test code is out of scope.
 */
class NotificationIntentSsotGuardTest {

    /** Modules where a `NotificationFields.intent` comparison could live. */
    private val modules = listOf("core/state", "app")

    /** Source-set directory-name prefixes to skip — test code is out of scope. */
    private val excludedSourceSetPrefixes = listOf("test", "androidTest")

    private val repoRoot: File by lazy { locateRepoRoot() }

    private val roots: List<String> by lazy {
        modules.flatMap { module ->
            val srcDir = File(repoRoot, "$module/src")
            (srcDir.listFiles { f -> f.isDirectory }?.toList() ?: emptyList())
                .filter { dir -> excludedSourceSetPrefixes.none { dir.name.startsWith(it) } }
                .map { dir -> "$module/src/${dir.name}" }
        }
    }

    @Test
    fun `no effect-bearing intent literal appears outside the NotificationIntent SSOT`() {
        val forbidden = StateMachineContract.EFFECT_INTENTS.keys
        assertTrue(
            "EFFECT_INTENTS is empty — the guard has nothing to protect; if that's intended, " +
                "remove this test, otherwise the contract lost its keys",
            forbidden.isNotEmpty(),
        )

        val problems = mutableListOf<String>()
        for (root in roots) {
            val rootDir = File(repoRoot, root)
            if (!rootDir.isDirectory) continue
            rootDir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    val text = file.readText()
                    for (intent in forbidden) {
                        // The literal token exactly as it would appear in a comparison: a
                        // double-quoted string equal to the intent wire value.
                        if (text.contains("\"$intent\"")) {
                            val relPath = file.relativeTo(repoRoot).path.replace(File.separatorChar, '/')
                            problems += "$relPath: raw effect-intent literal \"$intent\" — compare " +
                                "against NotificationIntent.${intent.uppercase()} (the SSOT), not a literal"
                        }
                    }
                }
        }
        assertTrue(
            "Effect-bearing notification-intent string literal(s) found in production Kotlin " +
                "(#762 D1). Use the NotificationIntent SSOT constants instead. Problems:\n" +
                problems.sorted().joinToString("\n"),
            problems.isEmpty(),
        )
    }

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
