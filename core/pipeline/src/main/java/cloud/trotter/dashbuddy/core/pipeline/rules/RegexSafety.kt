package cloud.trotter.dashbuddy.core.pipeline.rules

import com.google.re2j.Pattern as Re2Pattern

/**
 * The ONE place rule regexes are compiled (#418). Extracted from [RuleCompiler] (audit #11) so the
 * untrusted-rule regex boundary has a findable, independently-testable home — it is a load-time
 * security control, not incidental compiler plumbing.
 *
 * Rule patterns run on **RE2J**, whose NFA simulation is linear in `input × pattern` and cannot
 * backtrack (#1053, ADR-0010) — so "accepted ⇒ bounded match time" is a property of the engine
 * rather than a guard in front of it, and the #418 structural ReDoS heuristic is gone with the
 * backtracking it defended against. What this object still owns is everything the engine does NOT
 * bound for us:
 *
 *  1. **Pattern length** — [RuleCompiler.MAX_REGEX_LENGTH], measured on the pattern **as written**.
 *  2. **Compiled program size** — [MAX_REPEAT] / [MAX_REPEAT_PRODUCT]. Linear match time says
 *     nothing about COMPILE cost, and RE2J 1.8 has no program-size ceiling: the C++ RE2's `max_mem`
 *     has no equivalent in this port (there is no size or budget parameter on
 *     `com.google.re2j.Pattern.compile`, and `Compiler` expands a counted repeat by copying the
 *     sub-program `n` times). A **15-character** pattern is enough to make that fatal —
 *     `(a{1000}){1000}` compiles to **1 002 002** instructions, and a 23-character nested repeat
 *     exhausts a capped heap *at load*. Rule load runs on the device, once per rule, so a careless
 *     author (or, once #192/#640 opens the CDN channel, a hostile rule) turns a length-capped
 *     pattern into an OOM. Failing loud is not enough when the failure is the process.
 *  3. **Group nesting depth** — [MAX_GROUP_DEPTH], which also bounds the tree walks RE2J does at
 *     compile and simplification time (see [BoundedRegex] on the retained `StackOverflowError`
 *     catch).
 *  4. **Device/host class semantics** — the Perl classes are translated to Unicode-aware RE2
 *     classes so a rule means on ART what it meant before #1053 (see [translated] below).
 *
 * Everything else is fail-loud parsing: an over-long, over-sized, too-deep, or unparseable pattern
 * is a [RuleCompileException] at LOAD, per file, never a surprise on the per-event hot path.
 *
 * Every regex compiled from rule JSON MUST go through [compileRegex]; nothing in the rule engine
 * constructs a matcher directly (ratcheted by `RuleRegexEngineGuardTest`).
 */
internal object RegexSafety {

    /**
     * Ceiling on any single counted repeat (`{n}`, `{n,m}`, `{n,}`) and the factor an unbounded
     * quantifier (`*`, `+`) contributes to [MAX_REPEAT_PRODUCT].
     *
     * **This is tight against the shipped corpus and deliberately so — read before raising a rule's
     * repeat.** The largest counted repeat in either ruleset today is **60**
     * (`^.{1,60} \((\d{2,6}…)\)$`, the payout store-name shape), with `{1,48}` behind it. A rule
     * that legitimately needs a longer bound — a store name, an address line — will hit this cap,
     * and the honest fix is to raise the constant *here* after checking the product arithmetic
     * below, not to work around it in the rule. The cap is a backstop; [MAX_REPEAT_PRODUCT] is what
     * actually bounds the program.
     */
    const val MAX_REPEAT = 64

    /**
     * Ceiling on the product of NESTED repeat factors — the multiplier RE2J's compiler applies when
     * it expands counted repeats by copying the sub-program.
     *
     * The arithmetic: a group's own repeat multiplies every repeat inside it, so `(a{50}){50}` is
     * 2 500 copies of `a` (≈ 2 602 instructions) and `((a{50}){50}){50}` is 125 000 (≈ 130 102 —
     * the shape that OOMs). At this cap, `(a{64}){64}` is 4 096 copies of one atom; the worst
     * pattern that fits in [RuleCompiler.MAX_REGEX_LENGTH] characters *and* the cap — 30 atoms in a
     * group, each `{64}`, the group `{64}` — measures **123 010** instructions and 8 ms to compile,
     * versus the 1 002 002 an uncapped 15-char pattern reaches. Bounded, and bounded by a number
     * derived from the two constants rather than hoped for.
     *
     * An unbounded quantifier contributes [MAX_REPEAT], so `(a+)+` costs 4 096 — legal, because on
     * a non-backtracking engine it is also perfectly safe (`RegexReDoSTest` proves it matches in
     * microseconds). The corpus's largest product is far below: its deepest nesting is 2 groups.
     */
    const val MAX_REPEAT_PRODUCT = 4_096L

    /**
     * Ceiling on group nesting. The corpus's deepest pattern nests **2** groups, so this is 8×
     * headroom. It exists because RE2J walks the parsed tree recursively when it simplifies and
     * compiles, and an `Error` from that walk is the #909 class — an `Error` escapes every
     * `Exception`-only catch downstream (see [BoundedRegex]).
     */
    const val MAX_GROUP_DEPTH = 16

    /**
     * Compile a rule-supplied pattern into a case-insensitive [BoundedRegex].
     *
     * Order matters. The length cap is measured on the pattern **as the author wrote it** — that is
     * what `docs/rules.schema.json` validates and what a rule review reads — and only then is the
     * pattern analysed and translated, because [translated] deliberately makes patterns *longer*
     * (`\s` → `[\s\p{Z}]`). Analysis runs before the RE2J compile because the whole point of the
     * size caps is that reaching the compile is already too late.
     *
     * Throws [RuleCompileException] on an over-long, over-sized, too-deeply-nested, or unparseable
     * pattern. The pattern language is **RE2 syntax**: no lookaround, no backreferences, no
     * possessive or atomic groups. That restriction is what makes the linear-time bound a theorem.
     */
    fun compileRegex(pattern: String): BoundedRegex {
        if (pattern.length > RuleCompiler.MAX_REGEX_LENGTH)
            throw RuleCompileException(
                "Regex pattern length ${pattern.length} exceeds " +
                    "MAX_REGEX_LENGTH=${RuleCompiler.MAX_REGEX_LENGTH}",
            )
        val prepared = prepare(pattern)
        return try {
            BoundedRegex(Re2Pattern.compile(prepared, Re2Pattern.CASE_INSENSITIVE))
        } catch (e: Exception) {
            throw RuleCompileException(
                "Invalid regex pattern: '$pattern' (rule patterns are RE2 syntax — no lookaround, " +
                    "no backreferences)",
                e,
            )
        }
    }

    /**
     * The single structural walk: it validates the size/depth caps and produces the
     * Unicode-translated pattern in one pass, so there is exactly one piece of code in the repo
     * that knows how to read a rule pattern's syntax.
     *
     * Visible for tests — [translated] is the interesting half and is asserted directly.
     */
    internal fun prepare(pattern: String): String = walk(pattern)

    // =========================================================================
    // The walk
    // =========================================================================

    /**
     * **Perl class → Unicode RE2 class translation (#1053 round 2).**
     *
     * Android's `java.util.regex` is ICU-backed and ICU's `\d`, `\s` and `\w` are **Unicode**:
     * `\d` is `\p{Nd}` (so `١٢٣` are digits), `\s` includes `\p{Z}` (so NBSP and the thin spaces a
     * layout emits are whitespace), `\w` includes non-Latin letters. RE2's are **ASCII**. Moving
     * rule patterns onto RE2J therefore *narrowed* every rule on the device while every host test
     * stayed green — the #909 shape again, and this time it lands on the Pledge:
     *
     *  - the #885 first-last-initial name shape separates tokens with `\s{1,4}`, so a name rendered
     *    with a non-breaking space would stop matching and the redact entry that masks a **customer
     *    name** would silently not fire;
     *  - Uber's two `Going to …` notification rules discriminate on `\d` — a dropoff address
     *    rendered with non-ASCII digits would fall from the address-masking dropoff rule to the
     *    **redact-less** pickup rule, and `CustomerTextMarkers` deliberately excludes the
     *    store-ambiguous `"Going to "` prefix, so a raw customer address would reach the envelope.
     *
     * So the classes are translated here, at the one compile seam, and rule authors keep writing
     * `\d`/`\s`/`\w`. The byte-SSOT pins (`SnapshotRedactor.FIRST_LAST_INITIAL_PATTERN`,
     * [CurrencyShape]) are pins on the **source bytes** of a shape, not on an engine's semantics,
     * so they are unchanged as written and keep pinning what they always pinned.
     *
     * | written | outside a class | inside a class |
     * |---|---|---|
     * | `\d` | `\p{Nd}`          | `\p{Nd}`        |
     * | `\D` | `\P{Nd}`          | `\P{Nd}`        |
     * | `\s` | `[\s\p{Z}]`       | `\s\p{Z}`       |
     * | `\S` | `[^\s\p{Z}]`      | **rejected**    |
     * | `\w` | `[\p{L}\p{N}_]`   | `\p{L}\p{N}_`   |
     * | `\W` | `[^\p{L}\p{N}_]`  | **rejected**    |
     *
     * `\S` and `\W` inside a character class are rejected rather than passed through: they are the
     * negation of a *union*, which cannot be expressed as class members, and silently leaving them
     * ASCII would recreate exactly the gap this table closes. No rule uses them; an author who
     * needs one writes the explicit class.
     *
     * **`\b` is a stated residual.** RE2's word boundary is ASCII-only and there is no Unicode form
     * to translate it to. Every `\b` in the corpus sits against an ASCII word (`mi\b`, `min\b`,
     * `\bby`, `\bgate`, `\bpin`), so nothing regresses today — but a `\b` beside a non-ASCII letter
     * would behave differently on ART than it did before #1053. Recorded in ADR-0010 and CLAUDE.md.
     */
    private fun walk(pattern: String): String {
        val out = StringBuilder(pattern.length + 16)
        // innerMax per open group; index 0 is the top level. Values are clamped so a deeply nested
        // pattern saturates instead of overflowing Long.
        val frames = ArrayDeque<Long>().apply { addLast(1L) }
        var pendingGroup: Long? = null
        var maxDepth = 0
        var i = 0

        fun contribute(v: Long) {
            val clamped = if (v > MAX_REPEAT_PRODUCT) MAX_REPEAT_PRODUCT + 1 else v
            frames[frames.size - 1] = maxOf(frames[frames.size - 1], clamped)
        }

        fun flushPending() {
            pendingGroup?.let { contribute(it) }
            pendingGroup = null
        }

        while (i < pattern.length) {
            when (val c = pattern[i]) {
                '\\' -> {
                    flushPending()
                    i = emitEscape(pattern, i, out, inClass = false)
                }
                '[' -> {
                    flushPending()
                    i = emitClass(pattern, i, out)
                }
                '(' -> {
                    flushPending()
                    i = openGroup(pattern, i, out, frames)
                    maxDepth = maxOf(maxDepth, frames.size - 1)
                }
                ')' -> {
                    flushPending()
                    out.append(')')
                    // An unbalanced ')' is RE2J's error to report, not ours — keep the top frame.
                    pendingGroup = if (frames.size > 1) frames.removeLast() else null
                    i++
                }
                else -> {
                    val q = readQuantifier(pattern, i)
                    if (q == null) {
                        flushPending()
                        out.append(c)
                        i++
                    } else {
                        contribute((pendingGroup ?: 1L) * q.factor)
                        pendingGroup = null
                        out.append(pattern, i, q.end)
                        i = q.end
                    }
                }
            }
        }
        flushPending()

        if (maxDepth > MAX_GROUP_DEPTH) {
            throw RuleCompileException(
                "Regex '$pattern' nests $maxDepth groups, over MAX_GROUP_DEPTH=$MAX_GROUP_DEPTH " +
                    "(#1053: deep nesting drives RE2J's compile-time tree walk toward a " +
                    "StackOverflowError, which is an Error, not an Exception)",
            )
        }
        val product = frames.first()
        if (product > MAX_REPEAT_PRODUCT) {
            throw RuleCompileException(
                "Regex '$pattern' expands to at least $product copies of a sub-pattern, over " +
                    "MAX_REPEAT_PRODUCT=$MAX_REPEAT_PRODUCT (#1053: RE2J has no program-size cap, " +
                    "so nested counted repeats OOM the device at rule LOAD — the match is linear, " +
                    "the compile is not)",
            )
        }
        return out.toString()
    }

    /** Open a group: emit it, push a frame, and reject the constructs RE2 has no form for. */
    private fun openGroup(
        pattern: String,
        start: Int,
        out: StringBuilder,
        frames: ArrayDeque<Long>,
    ): Int {
        out.append('(')
        var i = start + 1
        if (i < pattern.length && pattern[i] == '?') {
            if (i + 1 < pattern.length && pattern[i + 1] == ':') {
                out.append("?:")
                frames.addLast(1L)
                return i + 2
            }
            // A pure flag group — `(?i)`, `(?-s)`. No body, so no frame.
            var k = i + 1
            while (k < pattern.length && pattern[k] in "imsU-") k++
            if (k < pattern.length && pattern[k] == ')') {
                out.append(pattern, i, k + 1)
                return k + 1
            }
            throw RuleCompileException(
                "Regex '$pattern' uses an unsupported group construct at index $start — rule " +
                    "patterns are RE2 syntax: no lookaround ('(?=', '(?!', '(?<'), no named " +
                    "groups ('(?P<'). Only '(?:' and flag groups are allowed.",
            )
        }
        frames.addLast(1L)
        return i
    }

    private class Quantifier(val factor: Long, val end: Int)

    /**
     * Read a quantifier at [start], or null when this position is not one (a literal `{`, a `?`
     * that has already been consumed as part of `(?:`). Enforces [MAX_REPEAT] on every written
     * bound; an unbounded quantifier reports [MAX_REPEAT] as its factor.
     */
    private fun readQuantifier(pattern: String, start: Int): Quantifier? {
        fun withLazy(end: Int): Int =
            if (end < pattern.length && (pattern[end] == '?' || pattern[end] == '+')) end + 1 else end

        return when (pattern[start]) {
            '*', '+' -> Quantifier(MAX_REPEAT.toLong(), withLazy(start + 1))
            '?' -> Quantifier(1L, withLazy(start + 1))
            '{' -> {
                val close = pattern.indexOf('}', start + 1)
                if (close < 0) return null
                val body = pattern.substring(start + 1, close)
                val comma = body.indexOf(',')
                val minText = if (comma < 0) body else body.substring(0, comma)
                val maxText = if (comma < 0) "" else body.substring(comma + 1)
                // Not a quantifier body -> this '{' is a literal brace, not our business.
                if (!minText.isDigits() || !maxText.isDigits()) return null
                val min = minText.toIntOrNull() ?: return null
                val hasComma = comma >= 0
                val max = if (maxText.isEmpty()) null else maxText.toIntOrNull() ?: return null
                for (n in listOfNotNull(min, max)) {
                    if (n > MAX_REPEAT) {
                        throw RuleCompileException(
                            "Regex '$pattern' repeats {$body}, over MAX_REPEAT=$MAX_REPEAT " +
                                "(#1053: RE2J expands a counted repeat by copying the " +
                                "sub-program, and it has no program-size cap)",
                        )
                    }
                }
                val factor = when {
                    max != null -> max.toLong()
                    hasComma -> MAX_REPEAT.toLong() // {n,} is unbounded
                    else -> min.toLong()
                }
                Quantifier(factor, withLazy(close + 1))
            }
            else -> null
        }
    }

    /** Emit one escape sequence starting at the backslash; returns the next index. */
    private fun emitEscape(pattern: String, start: Int, out: StringBuilder, inClass: Boolean): Int {
        if (start + 1 >= pattern.length) {
            out.append('\\') // trailing backslash — RE2J's error to report
            return start + 1
        }
        val e = pattern[start + 1]
        // A braced escape (`\p{L}`, `\x{1F600}`) is one atom; its braces are NOT a quantifier.
        if (e in "pPNxu" && start + 2 < pattern.length && pattern[start + 2] == '{') {
            val close = pattern.indexOf('}', start + 3)
            val end = if (close < 0) pattern.length else close + 1
            out.append(pattern, start, end)
            return end
        }
        val translated = if (inClass) IN_CLASS[e] else OUT_OF_CLASS[e]
        if (translated != null) {
            out.append(translated)
            return start + 2
        }
        if (inClass && (e == 'S' || e == 'W')) {
            throw RuleCompileException(
                "Regex '$pattern' uses '\\$e' inside a character class. It is the negation of a " +
                    "union, so it cannot be translated to a Unicode-aware RE2 class member, and " +
                    "leaving it ASCII would silently narrow the rule on an Android device " +
                    "(#1053). Write the explicit class instead.",
            )
        }
        out.append(pattern, start, start + 2)
        return start + 2
    }

    /** Emit a `[...]` character class with its members translated; returns the next index. */
    private fun emitClass(pattern: String, start: Int, out: StringBuilder): Int {
        out.append('[')
        var i = start + 1
        if (i < pattern.length && pattern[i] == '^') {
            out.append('^')
            i++
        }
        while (i < pattern.length && pattern[i] != ']') {
            i = if (pattern[i] == '\\') {
                emitEscape(pattern, i, out, inClass = true)
            } else {
                out.append(pattern[i])
                i + 1
            }
        }
        if (i < pattern.length) {
            out.append(']')
            i++
        }
        return i
    }

    /**
     * ASCII digits only, and short enough that [toIntOrNull] cannot be the thing that rejects an
     * over-long run. Hand-parsed rather than matched with a Kotlin [Regex]: this is the one file
     * whose job is keeping rule data off that engine, and a bare `{` in a rule is a literal brace,
     * not an error — "not a quantifier body" has to be a quiet `null`, not a throw.
     */
    private fun String.isDigits(): Boolean =
        length <= 9 && all { it in '0'..'9' }

    private val OUT_OF_CLASS: Map<Char, String> = mapOf(
        'd' to "\\p{Nd}",
        'D' to "\\P{Nd}",
        's' to "[\\s\\p{Z}]",
        'S' to "[^\\s\\p{Z}]",
        'w' to "[\\p{L}\\p{N}_]",
        'W' to "[^\\p{L}\\p{N}_]",
    )

    private val IN_CLASS: Map<Char, String> = mapOf(
        'd' to "\\p{Nd}",
        'D' to "\\P{Nd}",
        's' to "\\s\\p{Z}",
        'w' to "\\p{L}\\p{N}_",
        // 'S' and 'W' are rejected — see emitEscape.
    )
}
