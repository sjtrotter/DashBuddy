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
 * backtracking it defended against. What this object owns is everything the engine does NOT bound
 * for us:
 *
 *  1. **Pattern length** — [RuleCompiler.MAX_REGEX_LENGTH], measured on the pattern **as written**.
 *  2. **Compiled program size** — [MAX_PROGRAM_SIZE], **measured** on the compiled program. Linear
 *     match time says nothing about COMPILE cost, and RE2J 1.8 has no program-size ceiling of its
 *     own: the C++ RE2's `max_mem` has no equivalent in this port (there is no size or budget
 *     parameter on `com.google.re2j.Pattern.compile`, and `Compiler` expands a counted repeat by
 *     copying the sub-program `n` times). A **15-character** pattern is enough to make that fatal —
 *     `(a{1000}){1000}` compiles to 1 002 002 instructions — and rule load runs on the device, once
 *     per rule, so a careless author (or, once #192/#640 opens the CDN channel, a hostile rule)
 *     turns a length-capped pattern into an OOM. Failing loud is not enough when the failure is the
 *     process.
 *  3. **Group nesting depth** — [MAX_GROUP_DEPTH], which also bounds the tree walks RE2J does at
 *     compile and simplification time (see [BoundedRegex] on the retained `StackOverflowError`
 *     catch).
 *  4. **Device class semantics** — the Perl classes are translated toward ART's Unicode classes so
 *     a rule means roughly on the device what it meant before #1053. An **approximation**, with the
 *     differences listed rather than claimed away (see [walk]).
 *
 * ## Two gates, and why the order matters (#1053 round 4)
 *
 * Program size is **measured, not estimated**. Round 3 tried to bound it with a structural walk and
 * an adversarial review defeated that three ways — a standalone `(?i)` flag group dropped the
 * preceding atom's cost, `\Q…\E` quoting hid structure from the scan, and a 187-character literal
 * inside nested repeats packed **1 548 418 instructions (≈ 88 MiB, ≈ 131 ms)** into 200 characters
 * while satisfying the walk's product ceiling. A walk over pattern *text* cannot reliably predict
 * what a compiler *emits*; the compiled program can simply be asked.
 *
 * So [compileRegex] is:
 *
 *  1. a **coarse pre-compile guard** ([estimateInstructions] + the syntax rejections) whose ONLY job
 *     is to keep the RE2J compile itself from exhausting memory or stack on a hostile pattern. It is
 *     deliberately approximate. It is **not** the bound, and nothing downstream trusts its number;
 *  2. the compile, wrapped in `catch (Throwable)` so an `OutOfMemoryError` or `StackOverflowError`
 *     from gate 1's residue is a loud per-rule [RuleCompileException] rather than process death;
 *  3. the **measured bound** — [MAX_PROGRAM_SIZE] against `Pattern.programSize()`. This is the real
 *     one, and it is the number ADR-0010 states.
 *
 * Everything is fail-loud: an over-long, over-sized, too-deep, or unparseable pattern is a
 * [RuleCompileException] at LOAD, per file, never a surprise on the per-event hot path.
 *
 * Every regex compiled from rule JSON MUST go through [compileRegex]; nothing in the rule engine
 * constructs a matcher directly (ratcheted by `RuleRegexEngineGuardTest`).
 */
internal object RegexSafety {

    /**
     * **The bound.** Maximum instructions in the compiled RE2J program, measured with
     * `Pattern.programSize()` after the compile.
     *
     * Measured, not reasoned about: the largest program either shipped ruleset produces today is
     * **240** instructions (the #885 first-last-initial name shape), with 199 and 157 behind it —
     * an **83× margin**. `RuleCorpusCompileBudgetTest` re-measures that maximum on every run and
     * prints it, so the margin is a number in the build log rather than a claim in a comment.
     *
     * 20 000 instructions is a few hundred KiB of `Inst` objects and single-digit milliseconds to
     * compile: comfortably affordable once per rule on a device, and four orders of magnitude below
     * the 1.5 M-instruction pattern that motivated this gate.
     */
    const val MAX_PROGRAM_SIZE = 20_000

    /**
     * Ceiling on any single counted repeat bound (`{n}`, `{n,m}`, `{n,}`).
     *
     * A backstop, not the bound — [MAX_PROGRAM_SIZE] is. The largest counted repeat in either
     * ruleset today is **60** (`^.{1,60} \((\d{2,6}…)\)$`, the payout store-name shape), with
     * `{1,48}` behind it; a store-name or address bound is exactly the kind that grows, so this sits
     * at over three times the corpus. A rule that needs more than 200 of one atom is not describing
     * a screen string. (RE2J's own limit is 1 000 per written repeat; this is stricter.)
     */
    const val MAX_REPEAT = 200

    /**
     * Ceiling on the **pre-compile estimate** ([estimateInstructions]) — gate 1.
     *
     * This number exists to keep the compile in gate 2 affordable, nothing more. It is set two
     * orders of magnitude above the largest corpus estimate (126, for a pattern whose real program
     * is 240) and an order of magnitude below the 1.5 M-instruction pattern that motivated the
     * measured gate, so in practice it fires only on patterns that are trying to be expensive.
     */
    const val MAX_ESTIMATED_INSTRUCTIONS = 200_000L

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
     * pattern analysed and translated, because translation deliberately makes patterns *longer*
     * (`\s` → `[\s\p{Z}\x{0B}\x{85}]`).
     *
     * Throws [RuleCompileException] on an over-long, over-sized, too-deeply-nested, or unparseable
     * pattern. The pattern language is **RE2 syntax**: no lookaround, no backreferences, no
     * possessive or atomic groups, and no `\Q…\E` quoting.
     */
    fun compileRegex(pattern: String): BoundedRegex {
        if (pattern.length > RuleCompiler.MAX_REGEX_LENGTH)
            throw RuleCompileException(
                "Regex pattern length ${pattern.length} exceeds " +
                    "MAX_REGEX_LENGTH=${RuleCompiler.MAX_REGEX_LENGTH}",
            )
        val analysis = analyse(pattern)
        if (analysis.estimate > MAX_ESTIMATED_INSTRUCTIONS) {
            throw RuleCompileException(
                "Regex '$pattern' is estimated at ${analysis.estimate} instructions, over " +
                    "MAX_ESTIMATED_INSTRUCTIONS=$MAX_ESTIMATED_INSTRUCTIONS (#1053: the " +
                    "pre-compile guard — a pattern this large risks exhausting memory in RE2J's " +
                    "compiler before its program can be measured)",
            )
        }
        val prepared = analysis.translated

        // Gate 2's compile can still be expensive for anything gate 1 under-counted, and BOTH of its
        // failure modes are `Error`s that would otherwise escape every Exception-only catch
        // downstream and kill the load (the #909 class). Fail loud, per rule, never process death.
        val compiled = try {
            Re2Pattern.compile(prepared, Re2Pattern.CASE_INSENSITIVE)
        } catch (t: Throwable) {
            throw RuleCompileException(
                "Invalid regex pattern: '$pattern' (rule patterns are RE2 syntax — no lookaround, " +
                    "no backreferences, no \\Q quoting): ${t::class.java.simpleName}",
                t,
            )
        }
        if (compiled.programSize() > MAX_PROGRAM_SIZE) {
            throw RuleCompileException(
                "Regex '$pattern' compiles to ${compiled.programSize()} instructions, over " +
                    "MAX_PROGRAM_SIZE=$MAX_PROGRAM_SIZE (#1053: RE2J has no program-size cap of " +
                    "its own, and rule load runs on the device once per rule)",
            )
        }
        return BoundedRegex(compiled)
    }

    /**
     * The single structural walk: it runs gate 1's checks and produces the translated pattern in one
     * pass, so exactly one piece of code in the repo knows how to read a rule pattern's syntax.
     *
     * Visible for tests — the translation is the interesting half and is asserted directly.
     */
    internal fun prepare(pattern: String): String = walk(pattern)

    /**
     * Gate 1's cost estimate, exposed for tests. **Approximate by design**: it may under-count
     * (RE2J adds capture and branch instructions this does not model) and over-count (it charges
     * overhead the compiler sometimes folds away). It is a memory guard in front of the compile, not
     * a correctness control — [MAX_PROGRAM_SIZE], measured after the compile, is the bound.
     */
    internal fun estimateInstructions(pattern: String): Long = analyse(pattern).estimate

    // =========================================================================
    // The walk
    // =========================================================================

    /**
     * **Perl class → Unicode RE2 class translation — a stated device-parity APPROXIMATION (#1053).**
     *
     * Android's `java.util.regex` is ICU-backed and ICU's `\d`, `\s` and `\w` are **Unicode**: `\d`
     * is `\p{Nd}`, `\s` includes `\p{Z}`, `\w` includes non-Latin letters and combining marks. RE2's
     * are **ASCII**. Moving rule patterns onto RE2J therefore *narrowed* every rule on the device
     * while every host test stayed green — the #909 shape again, and this time it lands on the
     * Pledge:
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
     * [CurrencyShape]) are pins on the **source bytes** of a shape, not on an engine's semantics, so
     * they are unchanged as written and keep pinning what they always pinned.
     *
     * | written | outside a class | inside a class |
     * |---|---|---|
     * | `\d` | `\p{Nd}`                       | `\p{Nd}`                     |
     * | `\D` | `\P{Nd}`                       | `\P{Nd}`                     |
     * | `\s` | `[\s\p{Z}\x{0B}\x{85}]`        | `\s\p{Z}\x{0B}\x{85}`        |
     * | `\S` | `[^\s\p{Z}\x{0B}\x{85}]`       | **rejected**                 |
     * | `\w` | `[\p{L}\p{M}\p{N}\p{Pc}]`      | `\p{L}\p{M}\p{N}\p{Pc}`      |
     * | `\W` | `[^\p{L}\p{M}\p{N}\p{Pc}]`     | **rejected**                 |
     *
     * `\S` and `\W` inside a character class are rejected rather than passed through: they are the
     * negation of a *union*, which cannot be expressed as class members, and silently leaving them
     * ASCII would recreate exactly the gap this table closes. No rule uses them; an author who needs
     * one writes the explicit class.
     *
     * ## Residual differences — this is an approximation, not parity
     *
     * Exact parity is **not achievable**: RE2J 1.8 and ART's ICU ship different Unicode table
     * versions, so even identical property names disagree at the edges. Known and accepted:
     *
     *  - **`\d`** — RE2J 1.8's `\p{Nd}` lags ICU's: U+1E951 ADLAM DIGIT ONE is a decimal digit to
     *    Android and not to RE2J. `\D` inherits the inverse.
     *  - **`\s`** — VT (U+000B) and NEL (U+0085) are added explicitly above because Android's `\s`
     *    has them and `\p{Z}` does not. RE2J's older `\p{Z}` still includes U+180E, which current
     *    ICU whitespace excludes.
     *  - **`\w`** — `\p{N}` is a superset of Android's digit repertoire here (it admits superscript
     *    `²`, which ICU's `\w` excludes). Chosen deliberately: over-matching `\w` widens a match,
     *    while under-matching it would drop a redact, and the whole point of this table is that a
     *    dropped redact is the failure that costs.
     *  - **`\b`** — cannot be translated at all. RE2's word boundary is ASCII-only and there is no
     *    Unicode form to map it to. Every `\b` in the corpus sits against an ASCII word (`mi\b`,
     *    `min\b`, `\bby`, `\bgate`, `\bpin`), so nothing regresses today.
     *  - **case folding** — RE2J uses *simple* folding; ICU uses *full* folding for literals, so
     *    case-insensitive `straße` matches `STRASSE` on Android and does not here. No corpus pattern
     *    relies on it.
     */
    private fun walk(pattern: String): String = analyse(pattern).translated

    private class Analysis(val translated: String, val estimate: Long)

    /**
     * One frame per open group. [cost] accumulates the group's own concatenated cost; [pending] is
     * the cost of the most recent unit, held back because a quantifier that follows applies to it.
     */
    private class Frame {
        var cost: Long = 0
        var pending: Long = 0

        /** Retire the pending unit into the running concatenation. */
        fun settle() {
            cost += pending
            pending = 0
        }

        fun total(): Long {
            settle()
            return cost
        }
    }

    private fun analyse(pattern: String): Analysis {
        rejectQuoting(pattern)

        val out = StringBuilder(pattern.length + 32)
        val frames = ArrayDeque<Frame>().apply { addLast(Frame()) }
        var maxDepth = 0
        var i = 0

        fun frame() = frames.last()

        /** Charge a freshly-emitted unit of cost [c]; a following quantifier will multiply it. */
        fun unit(c: Long) {
            frame().settle()
            frame().pending = c
        }

        while (i < pattern.length) {
            when (val c = pattern[i]) {
                '\\' -> {
                    val next = emitEscape(pattern, i, out, inClass = false)
                    unit(1)
                    i = next
                }
                '[' -> {
                    i = emitClass(pattern, i, out)
                    unit(1) // a character class is ONE instruction regardless of how many members
                }
                '(' -> {
                    val flagEnd = standaloneFlagGroupEnd(pattern, i)
                    if (flagEnd >= 0) {
                        // `(?i)` emits no instruction and introduces no atom, so a quantifier after
                        // it applies to whatever came BEFORE it. Leaving `pending` untouched is the
                        // whole fix for `x{200}(?i){200}`, where round 3 settled it away and lost
                        // 40 000 instructions from the estimate (finding 1).
                        out.append(pattern, i, flagEnd)
                        i = flagEnd
                    } else {
                        frame().settle()
                        i = openGroup(pattern, i, out, frames)
                        maxDepth = maxOf(maxDepth, frames.size - 1)
                    }
                }
                ')' -> {
                    out.append(')')
                    if (frames.size > 1) {
                        val body = frames.removeLast().total()
                        // +GROUP_OVERHEAD for the capture/branch instructions RE2J emits per group.
                        unit(body + GROUP_OVERHEAD)
                    } else {
                        // Unbalanced ')' — RE2J's error to report, not ours.
                        unit(1)
                    }
                    i++
                }
                '|' -> {
                    // Both alternatives live in the program; charge the branch instructions too.
                    out.append('|')
                    frame().settle()
                    frame().cost += ALTERNATION_OVERHEAD
                    i++
                }
                else -> {
                    val q = readQuantifier(pattern, i)
                    if (q == null) {
                        out.append(c)
                        unit(1)
                        i++
                    } else {
                        // The quantifier applies to the pending unit: n copies of it, plus the loop.
                        frame().pending = saturate(frame().pending * q.factor + QUANTIFIER_OVERHEAD)
                        out.append(pattern, i, q.end)
                        i = q.end
                    }
                }
            }
        }

        if (maxDepth > MAX_GROUP_DEPTH) {
            throw RuleCompileException(
                "Regex '$pattern' nests $maxDepth groups, over MAX_GROUP_DEPTH=$MAX_GROUP_DEPTH " +
                    "(#1053: deep nesting drives RE2J's compile-time tree walk toward a " +
                    "StackOverflowError, which is an Error, not an Exception)",
            )
        }

        // Unclosed groups are RE2J's error to report; fold their cost in so the estimate still sees it.
        var estimate = 0L
        while (frames.isNotEmpty()) estimate = saturate(estimate + frames.removeLast().total())

        // NOTE: the estimate ceiling is enforced by [compileRegex], not here — `analyse` must be
        // able to REPORT a number over the ceiling so a test can show which of the two gates fires
        // on which pattern. Structural errors (depth, repeat bounds, syntax) still throw from here,
        // because there is no meaningful number to report for them.
        return Analysis(out.toString(), estimate)
    }

    /** Clamp so a pathological product cannot overflow into a small positive number. */
    private fun saturate(v: Long): Long =
        if (v < 0 || v > SATURATION) SATURATION else v

    /**
     * `\Q…\E` quoting is **rejected outright** rather than parsed.
     *
     * RE2J 1.8 supports it, and that is precisely the problem: a quoted region is opaque to any
     * structural scan, so `\Q[\E(a{1000}){1000}` slipped the round-3 walk entirely (the quoted `[`
     * opened a phantom character class that hid the repeats) and compiled to 1 002 003 instructions.
     * Translating inside a quoted region is also wrong — `\Q\d\E` means the literal text `\d`, and a
     * translation turns it into the literal text `\p{Nd}`.
     *
     * Both are fixable by teaching the walk about quoting. Refusing it is simpler, closes the bypass
     * structurally rather than by careful bookkeeping, and costs nothing: no rule uses quoting, and
     * an author who wants a literal writes `\.` as they already do.
     */
    private fun rejectQuoting(pattern: String) {
        var i = 0
        while (i < pattern.length - 1) {
            if (pattern[i] == '\\') {
                if (pattern[i + 1] == 'Q' || pattern[i + 1] == 'E') {
                    throw RuleCompileException(
                        "Regex '$pattern' uses \\Q…\\E quoting, which is not supported in rule " +
                            "patterns (#1053: a quoted region is opaque to the load-time guards, " +
                            "and translation inside one would change the literal). Escape the " +
                            "characters individually instead.",
                    )
                }
                i += 2
            } else {
                i++
            }
        }
    }

    /** Open a group: emit it, push a frame, and reject the constructs RE2 has no form for. */
    private fun openGroup(
        pattern: String,
        start: Int,
        out: StringBuilder,
        frames: ArrayDeque<Frame>,
    ): Int {
        out.append('(')
        var i = start + 1
        if (i < pattern.length && pattern[i] == '?') {
            // `(?:` non-capturing, and `(?flags:` scoped flags — both ordinary groups with a body.
            var k = i + 1
            while (k < pattern.length && pattern[k] in FLAG_CHARS) k++
            if (k < pattern.length && pattern[k] == ':') {
                out.append(pattern, i, k + 1)
                frames.addLast(Frame())
                return k + 1
            }
            throw RuleCompileException(
                "Regex '$pattern' uses an unsupported group construct at index $start — rule " +
                    "patterns are RE2 syntax: no lookaround ('(?=', '(?!', '(?<'), no named " +
                    "groups ('(?P<'). Only '(?:', scoped flag groups '(?i:' and standalone flag " +
                    "groups '(?i)' are allowed.",
            )
        }
        frames.addLast(Frame())
        return i
    }

    /**
     * End index of a **standalone** flag group (`(?i)`, `(?-s)`) starting at [start], or -1.
     *
     * Recognized in the main loop rather than in [openGroup] because it is not a group at all from
     * the cost model's point of view: it emits no instruction, opens no frame, and — the part that
     * matters — does not interrupt the pending unit a following quantifier applies to.
     */
    private fun standaloneFlagGroupEnd(pattern: String, start: Int): Int {
        if (start + 1 >= pattern.length || pattern[start + 1] != '?') return -1
        var k = start + 2
        while (k < pattern.length && pattern[k] in FLAG_CHARS) k++
        return if (k < pattern.length && pattern[k] == ')' && k > start + 2) k + 1 else -1
    }

    private class Quantifier(val factor: Long, val end: Int)

    /**
     * Read a quantifier at [start], or null when this position is not one (a literal `{`, or a `?`
     * already consumed as part of `(?:`).
     *
     * Factors are **body-copy** counts, matching what RE2J's compiler actually does: `{n}` and
     * `{n,m}` copy the body `n`/`m` times, while `*`, `+` and `{n,}` compile to a *loop* over one
     * copy — so an unbounded quantifier contributes 1, not a multiple. Enforces [MAX_REPEAT] on
     * every written bound.
     */
    private fun readQuantifier(pattern: String, start: Int): Quantifier? {
        fun withLazy(end: Int): Int =
            if (end < pattern.length && (pattern[end] == '?' || pattern[end] == '+')) end + 1 else end

        return when (pattern[start]) {
            '*', '+' -> Quantifier(1L, withLazy(start + 1)) // a loop: program size is the body's
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
                // A leading-zero bound is REJECTED, not read. RE2J's counted-repeat grammar refuses
                // leading zeros and silently treats `a{0201}` as the LITERAL TEXT "a{0201}" — so the
                // author's intent and the engine's reading diverge with no error anywhere. Refusing
                // the ambiguity is the only reading that cannot surprise someone (#1053 round 4).
                for (t in listOf(minText, maxText)) {
                    if (t.length > 1 && t[0] == '0') {
                        throw RuleCompileException(
                            "Regex '$pattern' writes the counted repeat {$body} with a leading " +
                                "zero. RE2 would read that as literal text rather than a repeat, " +
                                "so it is rejected instead of silently meaning something else — " +
                                "write {${t.trimStart('0').ifEmpty { "0" }}} if a repeat was meant.",
                        )
                    }
                }
                val min = minText.toIntOrNull() ?: return null
                val max = if (maxText.isEmpty()) null else maxText.toIntOrNull() ?: return null
                for (n in listOfNotNull(min, max)) {
                    if (n > MAX_REPEAT) {
                        throw RuleCompileException(
                            "Regex '$pattern' repeats {$body}, over MAX_REPEAT=$MAX_REPEAT " +
                                "(#1053: RE2J expands a counted repeat by copying the " +
                                "sub-program, and it has no program-size cap of its own)",
                        )
                    }
                }
                // `{n,}` is n copies plus a loop; `{n}` / `{n,m}` copy the body that many times.
                val factor = (max ?: min).toLong().coerceAtLeast(1L)
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
        if (e in BRACED_ESCAPES && start + 2 < pattern.length && pattern[start + 2] == '{') {
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

    /**
     * Emit a `[...]` character class with its members translated; returns the next index.
     *
     * Two pieces of RE2 class grammar the round-3 version got wrong, both of which silently changed
     * what a pattern matched rather than failing (#1053 round 4, finding 5):
     *  - a `]` **immediately after** `[` or `[^` is an ordinary member, not the terminator, so
     *    `[]\s]` is "`]` or whitespace" — the old scan ended the class at that `]` and emitted
     *    `[][\s\p{Z}]]`, which stopped matching a space and started matching *space followed by* `]`;
     *  - a POSIX class `[:alpha:]` is opaque: its `]` does not close the enclosing class, so
     *    `[[:alpha:]\s]` was truncated the same way.
     */
    private fun emitClass(pattern: String, start: Int, out: StringBuilder): Int {
        out.append('[')
        var i = start + 1
        if (i < pattern.length && pattern[i] == '^') {
            out.append('^')
            i++
        }
        // A ']' in first position is a member, not the terminator.
        if (i < pattern.length && pattern[i] == ']') {
            out.append(']')
            i++
        }
        while (i < pattern.length && pattern[i] != ']') {
            i = when {
                pattern[i] == '\\' -> emitEscape(pattern, i, out, inClass = true)
                // A POSIX class is copied whole; its ']' is not ours.
                pattern.startsWith("[:", i) -> {
                    val close = pattern.indexOf(":]", i + 2)
                    val end = if (close < 0) i + 2 else close + 2
                    out.append(pattern, i, end)
                    end
                }
                else -> {
                    out.append(pattern[i])
                    i + 1
                }
            }
        }
        if (i < pattern.length) {
            out.append(']')
            i++
        }
        return i
    }

    /** ASCII digits only, and short enough that [String.toIntOrNull] cannot be what rejects a long run. */
    private fun String.isDigits(): Boolean = length <= 9 && all { it in '0'..'9' }

    /**
     * Per-construct overheads for [estimateInstructions]. Approximate — RE2J emits capture and
     * branch instructions this only roughly models. Charged generously, because the estimate's
     * failure mode that matters is under-counting a hostile pattern into the compiler.
     */
    private const val GROUP_OVERHEAD = 4L
    private const val ALTERNATION_OVERHEAD = 4L
    private const val QUANTIFIER_OVERHEAD = 2L

    /**
     * The estimate saturates here rather than overflowing `Long` on a pathological nest. Chosen so
     * that `pending * factor` cannot overflow before [saturate] runs: 1e15 x [MAX_REPEAT] is 2e17,
     * well inside `Long`.
     */
    private const val SATURATION = 1_000_000_000_000_000L

    private const val FLAG_CHARS = "imsUu-"
    private const val BRACED_ESCAPES = "pPNxu"

    private val OUT_OF_CLASS: Map<Char, String> = mapOf(
        'd' to "\\p{Nd}",
        'D' to "\\P{Nd}",
        's' to "[\\s\\p{Z}\\x{0B}\\x{85}]",
        'S' to "[^\\s\\p{Z}\\x{0B}\\x{85}]",
        'w' to "[\\p{L}\\p{M}\\p{N}\\p{Pc}]",
        'W' to "[^\\p{L}\\p{M}\\p{N}\\p{Pc}]",
    )

    private val IN_CLASS: Map<Char, String> = mapOf(
        'd' to "\\p{Nd}",
        'D' to "\\P{Nd}",
        's' to "\\s\\p{Z}\\x{0B}\\x{85}",
        'w' to "\\p{L}\\p{M}\\p{N}\\p{Pc}",
        // 'S' and 'W' are rejected — see emitEscape.
    )
}
