package cloud.trotter.dashbuddy.test.util

import java.io.File

/**
 * The ONE owner of "which screen rules does `matchers/rules/doordash/dropoff.json5` declare" — the
 * population every dropoff-family parity guard enforces over (#993 banner, #1039 subpremise, #1107
 * instruction body, #1126 bounded address slots). Three tests used to carry their own regex scan of
 * the source file and had already drifted (one accepted single-quoted ids, one a second path);
 * PR #1213's review folded them here (principle 5).
 *
 * Reads the JSON5 SOURCE (the generated asset loses the surface file), both quote styles, screen
 * rules only (the file also declares a `clicks` section, which carries no redact). `comment` values
 * are prose and never contain an `"id":` key, so a bare regex is sufficient here.
 */
object DropoffRuleSource {
    private val candidates = listOf("../matchers/rules/doordash/dropoff.json5", "matchers/rules/doordash/dropoff.json5")

    fun sourceFile(): File = candidates.map(::File).firstOrNull { it.isFile }
        ?: error("matchers/rules/doordash/dropoff.json5 not found from ${File(".").absolutePath}")

    /** Every `doordash.screen.*` id declared in the dropoff surface source file, in declaration order. */
    fun screenRuleIds(): List<String> {
        val ids = Regex("""["']id["']\s*:\s*["'](doordash\.screen\.[A-Za-z_0-9]+)["']""")
            .findAll(sourceFile().readText())
            .map { it.groupValues[1] }
            .toList()
        check(ids.isNotEmpty()) { "expected the dropoff section to declare screen rules" }
        return ids
    }
}
