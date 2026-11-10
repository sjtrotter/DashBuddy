package cloud.trotter.log.gig.test.util

import java.io.File

/**
 * The ONE owner of "which screen rules does `matchers/rules/doordash/<surface>.json5` declare" — the
 * population every surface-family parity guard enforces over: dropoff (#993 banner, #1039 subpremise,
 * #1107 instruction body, #1126 bounded address slots) and pickup (#1397 merchant instruction body).
 * Three tests used to carry their own regex scan of the dropoff source file and had already drifted
 * (one accepted single-quoted ids, one a second path); PR #1213's review folded them here (principle 5).
 *
 * Reads the JSON5 SOURCE (the generated asset loses the surface file), both quote styles, screen
 * rules only (the files also declare a `clicks` section, which carries no redact). `comment` values
 * are prose and never contain an `"id":` key, so a bare regex is sufficient here.
 */
object DoorDashRuleSource {
    fun sourceFile(surface: String): File =
        listOf("../matchers/rules/doordash/$surface.json5", "matchers/rules/doordash/$surface.json5")
            .map(::File).firstOrNull { it.isFile }
            ?: error("matchers/rules/doordash/$surface.json5 not found from ${File(".").absolutePath}")

    /** Every `doordash.screen.*` id declared in the [surface] source file, in declaration order. */
    fun screenRuleIds(surface: String): List<String> {
        val ids = Regex("""["']id["']\s*:\s*["'](doordash\.screen\.[A-Za-z_0-9]+)["']""")
            .findAll(sourceFile(surface).readText())
            .map { it.groupValues[1] }
            .toList()
        check(ids.isNotEmpty()) { "expected the $surface section to declare screen rules" }
        return ids
    }
}
