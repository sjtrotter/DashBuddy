package cloud.trotter.dashbuddy.guard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-scan guard for the dev's locale-allowlist directive (#428 scope note, 2026-07-30,
 * landed as part of #907): DashBuddy translates into a **fixed, small language set** —
 * `en` (the untagged default), `es`, `fr` — and **never** a regional variant (`es-rUS`,
 * `fr-rCA`, …). "Maybe fold es-rUS -> generic es if copy doesn't differ" was exactly what #907
 * did (zero string overlap between `values-es` and `values-es-rUS` made the fold lossless); this
 * test is the permanent backstop so a regional-variant `values-*` directory can't creep back in
 * and silently reopen the class of break #907 fixed — a lint-vital `ExtraTranslation` failure
 * when a module's default-locale owner of a string moves (#98-style extraction) and the
 * regional-variant copy is left orphaned in the old module.
 *
 * Follows the `TimberTagGuardTest` (#764) / `IcuRegexGuardTest` (#909) source-scan doctrine:
 * read directory names off disk rather than reflecting over the built APK, because "which
 * `values-*` directories exist" is a filesystem property, not something the Kotlin/AGP type
 * system encodes for a unit test to see.
 *
 * ## What it catches
 *
 * Any `values-<qualifier>` resource directory, in any module's `src/main/res` (or any other
 * main-type source set — `debug`/`release`/…), whose qualifier is a **locale** qualifier — a
 * 2-letter ISO 639-1 language code, optionally followed by an `-r<REGION>` region qualifier —
 * that is either:
 * - not in the allowed language set (`es`, `fr`), or
 * - carries a region qualifier at all (`values-es-rUS`, `values-fr-rCA`, …), which the dev
 *   directive rules out even for an allowed language.
 *
 * A **non-locale** qualifier — `values-night` (the app's dark-theme resource set, a UI-mode
 * qualifier, not a locale), `values-v31` (an API-level qualifier), `values-land` (orientation),
 * etc. — must **not** match. The detector anchors on the qualifier shape itself
 * (`^values-([a-z]{2})(-r[A-Z]{2})?(-.*)?\$`) rather than an allowlist of "known non-locale
 * qualifiers", so a *future* non-locale qualifier that happens to look like one (unlikely, but
 * the shape is deliberately narrow: exactly 2 lowercase letters, then nothing or a proper
 * `-rXX` region, then nothing or more qualifiers) is the only thing that could false-positive —
 * none exists in the tree today (verified by hand at the time this guard was written).
 *
 * ## Deliberately NOT covered
 *
 * This is a **language-allowlist** gate, not a translation-**completeness** gate — CLAUDE.md's
 * #907 plan is explicit that the completeness tooth (are all ~393 keys translated, not just
 * some) is deferred until the translation-completion work itself lands; `values-es` sits at
 * roughly 84/393 keys today and that is expected, not a guard failure.
 */
class LocaleAllowlistGuardTest {

    // =========================================================================
    // The scan
    // =========================================================================

    @Test
    fun `every locale-qualified values directory is on the allowlist and carries no region`() {
        val problems = mutableListOf<String>()
        for (root in roots) {
            val rootDir = File(repoRoot, root)
            val resDir = File(rootDir, "res")
            if (!resDir.isDirectory) continue
            val localeDirs = resDir.listFiles { f -> f.isDirectory } ?: emptyArray()
            for (dir in localeDirs) {
                val match = localeQualifierRegex.matchEntire(dir.name) ?: continue
                val language = match.groupValues[1]
                val hasRegion = match.groupValues[2].isNotEmpty()
                val relPath = dir.relativeTo(repoRoot).path.replace(File.separatorChar, '/')
                when {
                    hasRegion -> problems += "$relPath: regional-variant locale qualifier " +
                        "('${dir.name}') — the dev locale allowlist is language-only " +
                        "($allowedLanguages, no -r region); fold into the language-level " +
                        "'values-$language' directory instead (the #907 es-rUS -> es pattern)"

                    language !in allowedLanguages -> problems += "$relPath: locale " +
                        "'$language' is not in the allowed set ($allowedLanguages) — either " +
                        "this is a new language the dev has decided to support (update " +
                        "[allowedLanguages] here to match) or it should not exist yet"
                }
            }
        }
        assertTrue(
            "Locale-qualified 'values-*' resource director(y/ies) violate the dev's locale " +
                "allowlist directive (CLAUDE.md #428 scope note / #907, 2026-07-30): translate " +
                "into $allowedLanguages only, language-level (no '-r<REGION>' variants). " +
                "Problems:\n" + problems.sorted().joinToString("\n"),
            problems.isEmpty(),
        )
    }

    /**
     * Anchored on the qualifier SHAPE (2-letter language code, optional `-r<REGION>`, optional
     * further qualifiers), not a hand-maintained list of "known non-locale qualifiers" — so
     * `values-night`/`values-v31`/`values-land` etc. never need to be enumerated to be excluded;
     * they simply don't fit this shape.
     */
    private val localeQualifierRegex = Regex("""^values-([a-z]{2})(-r[A-Z]{2})?(-.*)?$""")

    /** The dev's standing locale allowlist (#428 scope note, 2026-07-30): `en` (untagged default) + these. */
    private val allowedLanguages = setOf("es", "fr")

    // =========================================================================
    // Scan roots — mirrors TimberTagGuardTest (#764/#853) / IcuRegexGuardTest (#909):
    // every main-type source set of every module, feature modules discovered dynamically.
    // =========================================================================

    private val repoRoot: File by lazy { locateRepoRoot() }

    private val modules: List<String> by lazy {
        val layerModules = listOf(
            "app",
            "core/database",
            "core/data",
            "core/datastore",
            "core/designsystem",
            "core/location",
            "core/network",
            "core/pipeline",
            "core/state",
            "domain",
        )
        val featureModules = File(repoRoot, "feature")
            .listFiles { f -> f.isDirectory }
            ?.map { "feature/${it.name}" }
            ?.sorted()
            ?: emptyList()
        layerModules + featureModules
    }

    /** Test code is out of scope — a test-fixture 'res' folder never ships in a build variant. */
    private val excludedSourceSetPrefixes = listOf("test", "androidTest")

    private val roots: List<String> by lazy {
        modules.flatMap { module ->
            val srcDir = File(repoRoot, "$module/src")
            val sourceSets = srcDir.listFiles { f -> f.isDirectory }?.toList() ?: emptyList()
            sourceSets
                .filter { dir -> excludedSourceSetPrefixes.none { dir.name.startsWith(it) } }
                .map { dir -> "$module/src/${dir.name}" }
        }
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
