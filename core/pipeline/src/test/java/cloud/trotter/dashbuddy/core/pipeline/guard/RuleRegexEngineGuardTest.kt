package cloud.trotter.dashbuddy.core.pipeline.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-scan ratchet for the #1053 boundary: **a rule-authored pattern never reaches a
 * backtracking engine.**
 *
 * `RegexSafety.compileRegex` compiles rule JSON onto RE2J, whose linear-time bound is what makes
 * "accepted ⇒ bounded" a theorem rather than the promise the retired 200 ms watchdog could not keep
 * on Android. That property is only as strong as the claim that *nothing else in the rule engine
 * builds a matcher* — one `Regex(patternFromJson)` slipped in later would reopen the whole class
 * silently, because a backtracking hang is invisible to every test that does not happen to feed it
 * a pumping input.
 *
 * Following the `TimberTagGuardTest` (#764) / `IcuRegexGuardTest` (#909) doctrine: a small,
 * predictable scanner over the source text, with a **frozen allowlist that can only burn down**.
 * Three rules:
 *
 *  1. **No `java.util.regex`** anywhere in the rule package. The JDK/ICU engine is exactly what the
 *     seam exists to keep out (and it is the engine whose host/device divergence bit #909).
 *  2. **Every `Regex(…)` / `.toRegex()` construction takes a STRING LITERAL.** That is the
 *     structural line between an *app-authored constant* — a pattern this repo wrote, reviewed, and
 *     can reason about, matching against text it already trusts to be small — and a *rule-authored*
 *     one, which arrives as a value from JSON (today from assets; tomorrow, #192/#640, from a CDN)
 *     and must go through `compileRegex`. A `Regex(someVariable)` in this package is the violation.
 *  3. **The app-authored constants are enumerated and counted.** Adding one is a deliberate act
 *     that edits this list; removing one (folding it into the shared vocabulary, or onto RE2J) is
 *     free. Counts may only drop, so the list is a visible debt ledger, not a permanent exemption.
 *
 * What it does NOT claim: that the app-authored constants are themselves ReDoS-free. They are not
 * rule data — an author wrote them and a reviewer read them — and every one of them today is a
 * bounded, linear shape. Widening that judgement into an automated check is out of scope; keeping
 * *untrusted* patterns off that engine entirely is the property this file defends.
 */
class RuleRegexEngineGuardTest {

    /**
     * Files in the rule package permitted to construct an app-authored Kotlin [Regex], with the
     * number of constructions each carries today. **These numbers may only go down.**
     */
    private val appAuthoredConstants: Map<String, Int> = mapOf(
        // The `nextSiblingMatchingRegex(<pattern>[, <n>])` navigate-spec parser (#1029) — it reads
        // the rule's own SYNTAX, not the rule's pattern; the pattern it extracts goes to compileRegex.
        "CompilerHelpers.kt" to 1,
        // Whitespace collapse for the customer-name canonical key (#733).
        "CustomerNameKey.kt" to 1,
        // The `{field}` dedupeKey template + the control-character strip (#427).
        "Ruleset.kt" to 2,
        // The parse transforms' own vocabulary (miles/minutes/items/time-of-day/glyph currency).
        "TransformRegistry.kt" to 9,
    )

    private val ruleSourceDir: File by lazy {
        File(locateRepoRoot(), "core/pipeline/src/main/java/cloud/trotter/dashbuddy/core/pipeline/rules")
    }

    @Test
    fun `the rule package never references java_util_regex`() {
        val problems = scan().filter { it.javaUtilRegexLines.isNotEmpty() }
            .flatMap { f -> f.javaUtilRegexLines.map { "${f.name}:$it: java.util.regex reference" } }
        assertTrue(
            "The rule engine must not reach the JDK/ICU regex engine (#1053): rule-authored " +
                "patterns compile onto RE2J through RegexSafety, whose linear-time bound is the " +
                "whole defence. Problems:\n" + problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    @Test
    fun `every Regex construction in the rule package takes a string literal`() {
        val problems = scan().flatMap { f ->
            f.dynamicConstructions.map {
                "${f.name}:$it: Regex(...) built from a non-literal — a rule-authored pattern must " +
                    "go through RegexSafety.compileRegex (#1053)"
            }
        }
        assertTrue(
            "A pattern that came from rule JSON must never be compiled onto a backtracking " +
                "engine — that is the #1053 boundary, and a hang it reopens is invisible to every " +
                "test that does not feed it a pumping input. Problems:\n" + problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    @Test
    fun `the app-authored constant ledger only burns down`() {
        val actual = scan().filter { it.literalConstructions > 0 }
            .associate { it.name to it.literalConstructions }

        val newFiles = actual.keys - appAuthoredConstants.keys
        assertTrue(
            "New file(s) in the rule package construct a Kotlin Regex: ${newFiles.sorted()}. " +
                "If the pattern is app-authored and linear, add it to `appAuthoredConstants` with " +
                "its count and say why; if it came from rule JSON, route it through " +
                "RegexSafety.compileRegex instead (#1053).",
            newFiles.isEmpty(),
        )

        for ((file, frozen) in appAuthoredConstants.toSortedMap()) {
            val now = actual[file] ?: 0
            assertTrue(
                "$file now constructs $now Kotlin Regex(es), over its frozen ceiling of $frozen. " +
                    "The ledger only burns down (#1053).",
                now <= frozen,
            )
            if (now < frozen) {
                // Not a failure — but the ledger must be re-frozen so it keeps ratcheting.
                assertEquals(
                    "$file is down to $now Kotlin Regex(es) from $frozen — lower its entry in " +
                        "`appAuthoredConstants` so the ratchet stays tight (#1053).",
                    frozen, now,
                )
            }
        }
    }

    @Test
    fun `the scan actually reads the rule package`() {
        // A guard that silently scans nothing is worse than no guard.
        val files = scan()
        assertTrue("no .kt files found under ${ruleSourceDir.path}", files.size > 10)
        assertTrue(
            "the scanner must see the compileRegex owner",
            files.any { it.name == "RegexSafety.kt" },
        )
    }

    // =========================================================================
    // The scan
    // =========================================================================

    private data class Scanned(
        val name: String,
        val literalConstructions: Int,
        val dynamicConstructions: List<Int>,
        val javaUtilRegexLines: List<Int>,
    )

    private fun scan(): List<Scanned> =
        ruleSourceDir.listFiles { f -> f.isFile && f.extension == "kt" }
            .orEmpty().sortedBy { it.name }.map { scanFile(it) }

    private fun scanFile(file: File): Scanned {
        val source = file.readText()
        val code = stripComments(source)
        var literal = 0
        val dynamic = mutableListOf<Int>()
        for (m in CONSTRUCTION.findAll(code)) {
            val after = code.drop(m.range.last + 1).trimStart()
            val line = lineOf(code, m.range.first)
            if (after.startsWith("\"")) literal++ else dynamic += line
        }
        val jur = JAVA_UTIL_REGEX.findAll(code).map { lineOf(code, it.range.first) }.toList()
        return Scanned(file.name, literal, dynamic, jur)
    }

    /** 1-based line number of [index] in [code] (comment-stripped, so it is an approximation). */
    private fun lineOf(code: String, index: Int): Int = code.take(index).count { it == '\n' } + 1

    /**
     * Drop `//` and block comments while KEEPING string literals verbatim, and preserving newlines
     * so reported line numbers stay usable. KDoc in this very package discusses `java.util.regex`
     * by name, so scanning raw source would false-positive on prose.
     */
    private fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("/*", i) -> {
                    val end = text.indexOf("*/", i + 2)
                    val stop = if (end < 0) text.length else end + 2
                    // keep the newlines so line numbers survive
                    text.substring(i, stop).forEach { if (it == '\n') out.append('\n') }
                    i = stop
                }
                text.startsWith("//", i) -> {
                    val end = text.indexOf('\n', i)
                    i = if (end < 0) text.length else end
                }
                text.startsWith("\"\"\"", i) -> {
                    val end = text.indexOf("\"\"\"", i + 3)
                    val stop = if (end < 0) text.length else end + 3
                    out.append(text, i, stop)
                    i = stop
                }
                text[i] == '"' -> {
                    var j = i + 1
                    while (j < text.length && text[j] != '"') {
                        if (text[j] == '\\') j++
                        j++
                    }
                    val stop = minOf(j + 1, text.length)
                    out.append(text, i, stop)
                    i = stop
                }
                else -> { out.append(text[i]); i++ }
            }
        }
        return out.toString()
    }

    private fun locateRepoRoot(): File {
        var dir = File(".").absoluteFile.normalize()
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: error("Could not locate repo root (settings.gradle.kts)")
        }
    }

    private companion object {
        /**
         * A Kotlin `Regex(` construction or a `.toRegex(` call. The lookbehind keeps `BoundedRegex(`
         * — the seam's own constructor — and any qualified `Foo.Regex(` out of the count.
         */
        val CONSTRUCTION = Regex("""(?<![A-Za-z0-9_.])Regex\s*\(|\.toRegex\s*\(""")
        val JAVA_UTIL_REGEX = Regex("""java\.util\.regex""")
    }
}
