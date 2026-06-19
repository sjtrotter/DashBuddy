package cloud.trotter.dashbuddy.core.pipeline.rules

/**
 * The ONE place rule regexes are turned into [Regex] (#418). Extracted from
 * [RuleCompiler] (audit #11) so the ReDoS guard has a findable,
 * independently-testable home — it is the load-time security boundary for the
 * untrusted-rule path, not incidental compiler plumbing.
 *
 * Recognition runs regex on the per-event hot path, and Kotlin/Java [Regex] has
 * no match timeout, so one pathological pattern hangs the classification thread.
 * Every regex compiled from rule JSON MUST go through [compileRegex]; nothing in
 * the rule engine constructs a [Regex] directly.
 *
 * The length cap stays owned by [RuleCompiler.MAX_REGEX_LENGTH] (the documented
 * public constant other modules reference) — this object reads it, it does not
 * re-declare it.
 */
internal object RegexSafety {

    /**
     * Compile a rule-supplied pattern into a case-insensitive [Regex], enforcing
     * the length cap and the catastrophic-backtracking guard first. Throws
     * [RuleCompileException] on an over-long, ReDoS-prone, or invalid pattern.
     */
    fun compileRegex(pattern: String): Regex {
        if (pattern.length > RuleCompiler.MAX_REGEX_LENGTH)
            throw RuleCompileException(
                "Regex pattern length ${pattern.length} exceeds " +
                    "MAX_REGEX_LENGTH=${RuleCompiler.MAX_REGEX_LENGTH}",
            )
        assertNoCatastrophicBacktracking(pattern)
        return try {
            Regex(pattern, RegexOption.IGNORE_CASE)
        } catch (e: Exception) {
            throw RuleCompileException("Invalid regex pattern: '$pattern'", e)
        }
    }

    /**
     * Reject regex prone to catastrophic backtracking at COMPILE time (#418).
     * Java/Kotlin [Regex] has no match timeout, and recognition runs regex on
     * the per-event hot path, so one pathological pattern hangs the
     * classification thread. The dominant exploit class — and the careless-
     * author footgun — is an UNBOUNDED quantifier applied to a group whose body
     * already contains an unbounded quantifier: `(a+)+`, `(a*)*`, `(.*)+`,
     * `(\d+){2,}`. This is a conservative structural check: it accepts linear
     * patterns like `(\d+)`, `(ab)+`, `\d+\.\d+`, `(?:abc)+` and rejects the
     * nested-unbounded family.
     */
    fun assertNoCatastrophicBacktracking(pattern: String) {
        // Phase 1: neutralize escapes and char classes, so the structural walk
        // sees only literal atoms, group parens, and quantifiers. Inside a char
        // class `[...]` the chars `(`, `)`, `+`, `*` are literal, and an escaped
        // atom (`\d`) is one logical atom — both would confuse the walk.
        val cleaned = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            when (pattern[i]) {
                '\\' -> { cleaned.append('a'); i += 2 } // escaped atom → neutral atom
                '[' -> {
                    i++
                    while (i < pattern.length && pattern[i] != ']') {
                        if (pattern[i] == '\\') i++
                        i++
                    }
                    i++ // past ']'
                    cleaned.append('a') // the class is one neutral atom
                }
                else -> { cleaned.append(pattern[i]); i++ }
            }
        }

        // Phase 2: walk the cleaned pattern; per group, track whether its body
        // (recursively) contains an unbounded quantifier.
        val s = cleaned.toString()
        val containsUnbounded = ArrayDeque<Boolean>()
        containsUnbounded.addLast(false) // top level
        var j = 0
        while (j < s.length) {
            when (s[j]) {
                '(' -> { containsUnbounded.addLast(false); j++ }
                ')' -> {
                    val bodyUnbounded = containsUnbounded.removeLast()
                    val qEnd = unboundedQuantifierEnd(s, j + 1)
                    if (qEnd != -1) {
                        if (bodyUnbounded) {
                            throw RuleCompileException(
                                "Regex '$pattern' nests unbounded quantifiers (ReDoS risk) — " +
                                    "an unbounded quantifier wraps a group that already repeats unboundedly.",
                            )
                        }
                        // This group is itself unbounded → its parent now is too.
                        if (containsUnbounded.isNotEmpty()) {
                            containsUnbounded[containsUnbounded.size - 1] = true
                        }
                        j = qEnd
                    } else {
                        if (bodyUnbounded && containsUnbounded.isNotEmpty()) {
                            containsUnbounded[containsUnbounded.size - 1] = true
                        }
                        j++
                    }
                }
                '*', '+' -> {
                    containsUnbounded[containsUnbounded.size - 1] = true
                    j++
                    if (j < s.length && s[j] == '?') j++ // lazy
                }
                '{' -> {
                    val qEnd = unboundedQuantifierEnd(s, j)
                    if (qEnd != -1) {
                        containsUnbounded[containsUnbounded.size - 1] = true
                        j = qEnd
                    } else {
                        // bounded {n} / {n,m} — skip past it
                        val close = s.indexOf('}', j)
                        j = if (close == -1) j + 1 else close + 1
                    }
                }
                else -> j++
            }
        }
    }

    /** End index after an UNBOUNDED quantifier at [pos] (`*`, `+`, `{n,}`), or -1. */
    private fun unboundedQuantifierEnd(s: String, pos: Int): Int {
        if (pos >= s.length) return -1
        return when (s[pos]) {
            '*', '+' -> if (pos + 1 < s.length && s[pos + 1] == '?') pos + 2 else pos + 1
            '{' -> {
                val close = s.indexOf('}', pos)
                if (close == -1) return -1
                val body = s.substring(pos + 1, close)
                if (!body.matches(Regex("\\d+,"))) return -1 // {n,} only; {n} / {n,m} are bounded
                if (close + 1 < s.length && s[close + 1] == '?') close + 2 else close + 1
            }
            else -> -1
        }
    }
}
