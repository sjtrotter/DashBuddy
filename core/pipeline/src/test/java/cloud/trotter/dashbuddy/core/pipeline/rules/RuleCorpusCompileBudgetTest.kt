package cloud.trotter.dashbuddy.core.pipeline.rules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every regex the shipped rulesets declare compiles, and compiles **fast** (#1053 round 2).
 *
 * `AllMatchersSuite` already proves the rules *load*; this states the other half of the load-time
 * bound explicitly, and states it over the CANONICAL generated assets rather than over a
 * hand-copied list, so a pattern arriving through a new sub-file — or, later, a different source
 * entirely (#192/#640) — is in scope automatically.
 *
 * The reason it is worth its own file: RE2J's linear-time guarantee is about *matching*. Compiling
 * is where a counted repeat is expanded by copying the sub-program, RE2J 1.8 has no program-size
 * ceiling of its own, and rule load happens **on the device, once per rule**. `(a{1000}){1000}` is
 * fifteen characters and 1 002 002 instructions. [RegexSafety.MAX_REPEAT] and
 * [RegexSafety.MAX_REPEAT_PRODUCT] are the caps that bound it; this is the assertion that the caps
 * do not bound the corpus.
 *
 * It also prints the corpus's largest counted repeat against [RegexSafety.MAX_REPEAT] on failure,
 * because that margin is genuinely tight (the payout store-name shape declares `{1,60}` against a
 * cap of 200) and an author who trips it deserves to be told the number rather than left guessing.
 */
class RuleCorpusCompileBudgetTest {

    private companion object {
        /**
         * Per-pattern compile ceiling. A corpus pattern measures well under a millisecond; the
         * worst shape the caps still admit measures 8 ms. 50 ms is a cushion for a cold JIT on
         * shared CI, chosen so a real regression (an added nested repeat) is unmistakable.
         */
        const val COMPILE_BUDGET_MS = 50L

        const val NAV_PREFIX = "nextSiblingMatchingRegex("

        /** Keys whose STRING value is a rule-authored regex, across screen/click/notification rules. */
        val REGEX_KEYS = setOf(
            "hasTextMatchesRegex",
            "hasFollowingSiblingTextMatchesRegex",
            "titleMatchesRegex",
            "textMatchesRegex",
            "bigTextMatchesRegex",
            "tickerTextMatchesRegex",
            "anyFieldMatchesRegex",
            "match",
            "find",
            "pattern",
        )
    }

    /**
     * The generated canonical rulesets. Resolved by walking up to the repo root rather than by
     * asking `:app`'s `TestRulesetFactory`: this test lives in `:core:pipeline` because
     * [RegexSafety]'s caps and class translation are `internal` to this module, and a test module
     * cannot reach across the module boundary for a helper.
     */
    private val rulesDir: File by lazy {
        val relative = "core/pipeline/build/generated/assets/importMatchersRules/rules"
        var dir = File(".").absoluteFile.normalize()
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("could not locate the repo root")
        }
        File(dir, relative).also {
            check(it.isDirectory) {
                "generated rules dir missing at ${it.path} — :core:pipeline:importMatchersRules " +
                    "must run before this test (wired in core/pipeline/build.gradle.kts)"
            }
        }
    }

    /** `<file>:<pattern>` for every rule-authored regex in the generated rulesets. */
    private fun declaredPatterns(): List<Pair<String, String>> =
        rulesDir
            .listFiles { f -> f.extension == "json" }
            ?.sortedBy { it.name }
            ?.flatMap { file ->
                val found = mutableListOf<Pair<String, String>>()
                collect(Json.parseToJsonElement(file.readText()), found, file.name)
                found
            }
            ?: emptyList()

    private fun collect(el: JsonElement, into: MutableList<Pair<String, String>>, file: String) {
        when (el) {
            is JsonObject -> {
                for ((key, value) in el) {
                    val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                    when {
                        text == null -> Unit
                        key in REGEX_KEYS -> into += file to text
                        key == "navigate" && text.startsWith(NAV_PREFIX) -> {
                            val arg = text.removePrefix(NAV_PREFIX).removeSuffix(")")
                            // Strip the optional `, <cap>` — the cap is not part of the pattern.
                            val comma = arg.lastIndexOf(',')
                            val pattern =
                                if (comma > 0 && arg.substring(comma + 1).trim().toIntOrNull() != null) {
                                    arg.substring(0, comma)
                                } else {
                                    arg
                                }
                            into += file to pattern
                        }
                    }
                }
                el.values.forEach { collect(it, into, file) }
            }
            is JsonArray -> el.forEach { collect(it, into, file) }
            else -> Unit
        }
    }

    @Test
    fun `the scan actually finds the corpus's regexes`() {
        // A budget test that measures nothing is worse than no budget test.
        val patterns = declaredPatterns()
        assertTrue(
            "expected the generated rulesets to declare many regexes, found ${patterns.size}",
            patterns.size >= 100,
        )
    }

    @Test
    fun `every declared rule regex compiles within the load-time budget`() {
        val slow = mutableListOf<String>()
        val broken = mutableListOf<String>()
        // Warm the compiler once so the first pattern is not charged for class loading.
        RuleCompiler.compileRegex("warm")
        for ((file, pattern) in declaredPatterns()) {
            val start = System.nanoTime()
            try {
                RuleCompiler.compileRegex(pattern)
            } catch (e: RuleCompileException) {
                broken += "$file: <$pattern> — ${e.message}"
                continue
            }
            val ms = (System.nanoTime() - start) / 1_000_000
            if (ms >= COMPILE_BUDGET_MS) slow += "$file: <$pattern> took ${ms}ms"
        }
        assertTrue(
            "rule regex(es) in the shipped corpus no longer compile — the caps or the pattern " +
                "language rejected something we actually ship:\n" + broken.joinToString("\n"),
            broken.isEmpty(),
        )
        assertTrue(
            "rule regex(es) exceeded the ${COMPILE_BUDGET_MS}ms per-pattern compile budget. Rule " +
                "load runs on the device, once per rule; a slow compile is a nested counted " +
                "repeat (#1053):\n" + slow.joinToString("\n"),
            slow.isEmpty(),
        )
    }

    @Test
    fun `the corpus's largest counted repeat is stated, and sits under MAX_REPEAT`() {
        var largest = 0
        var where = "(none)"
        for ((file, pattern) in declaredPatterns()) {
            for (n in countedRepeats(pattern)) {
                if (n > largest) {
                    largest = n
                    where = "$file: <$pattern>"
                }
            }
        }
        assertTrue(
            "the corpus's largest counted repeat is $largest ($where), at or over " +
                "MAX_REPEAT=${RegexSafety.MAX_REPEAT}. Raise the constant deliberately — read its " +
                "KDoc first, the product arithmetic is what actually bounds the program size.",
            largest <= RegexSafety.MAX_REPEAT,
        )
        // Stated, not silently passed: this margin is 60 vs 200 today.
        println("[#1053] corpus largest counted repeat = $largest (MAX_REPEAT=${RegexSafety.MAX_REPEAT}) at $where")
    }

    /** Every `{n}` / `{n,}` / `{n,m}` bound in [pattern], escape- and class-aware. */
    private fun countedRepeats(pattern: String): List<Int> {
        val out = mutableListOf<Int>()
        var i = 0
        var inClass = false
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> i += 2
                inClass -> { if (c == ']') inClass = false; i++ }
                c == '[' -> { inClass = true; i++ }
                c == '{' -> {
                    val close = pattern.indexOf('}', i + 1)
                    val body = if (close < 0) null else pattern.substring(i + 1, close)
                    val parts = body?.split(",")
                    if (parts != null && parts.size <= 2 && parts.all { it.all { ch -> ch.isDigit() } } &&
                        parts.first().isNotEmpty()
                    ) {
                        parts.filter { it.isNotEmpty() }.forEach { p -> p.toIntOrNull()?.let(out::add) }
                        i = close + 1
                    } else {
                        i++
                    }
                }
                else -> i++
            }
        }
        return out
    }
}
