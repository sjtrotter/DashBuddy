/*
 * Copyright 2026 Stephen Trotter
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Census wire contract (ADR-0011). This package depends on nothing but the JDK,
 * kotlinx-serialization and `domain.util.sha256OrNull`, so that extracting it to a
 * standalone Apache-2.0 `census-contract/` build is a move, not a rewrite.
 */
package cloud.trotter.dashbuddy.domain.census.contract

/**
 * The normative `kind` classifier (ADR-0011 §1 table), in its two stages.
 *
 * Stage 1, [shapeKind], is the GRAMMAR (table rows 2–4): it yields the intermediate [ShapeKind] of a
 * trimmed, already-CAPPED value (the §2 40-character cap runs before the grammar, so every test here
 * sees bounded input). Stage 2 is the §2 filter in the builder: it consults the shape (step 6 — only
 * `words:1..8` may be hashed) and decides whether the EMITTED kind is [WITHHELD] (row 1, a constant
 * that reveals nothing) or the shape's own [ShapeKind.wire].
 *
 * Every letter/digit test is CODE-POINT based (`Character.isLetter(int)` / `isDigit(int)`), so
 * supplementary-plane scripts count. Whitespace is `Character.isWhitespace || isSpaceChar` — the same
 * predicate Kotlin's `trim()`/`isBlank()` use, so the classifier and the trim agree on NBSP.
 *
 * Money and time are deliberately NOT classes (ADR §1): they are `mixed`.
 */
object KindClassifier {

    /** Row 1 — a withholding filter step fired (or the hash failed). A constant: no length, no count. */
    const val WITHHELD: String = "withheld"

    /** Row 2 — every non-whitespace code point is a Unicode decimal digit. */
    const val DIGITS: String = "digits"

    /** Row 4 — everything else (money, clock times, unit numbers, codes, symbols, emoji). */
    const val MIXED: String = "mixed"

    /** Row 3 — prefix of `words:N` (`N` in 1..8). */
    const val WORDS_PREFIX: String = "words:"

    /** Row 3 above [MAX_HASHABLE_WORDS] runs — body text, emitted WITHOUT a hash. */
    const val WORDS_OVER: String = "words:8+"

    /** The largest run count that may carry a hash. */
    const val MAX_HASHABLE_WORDS: Int = 8

    /** The grammar's intermediate result. */
    sealed interface ShapeKind {
        /** The wire `kind` this shape emits when no withholding step fired. */
        val wire: String

        /** True only for `words:1..8` — the ONLY shape that may carry a hash (§2 step 6). */
        val hashable: Boolean get() = false

        data object Digits : ShapeKind {
            override val wire: String = DIGITS
        }

        data object Mixed : ShapeKind {
            override val wire: String = MIXED
        }

        /** [count] ≥ 1 word runs; above [MAX_HASHABLE_WORDS] it is `words:8+`. */
        data class Words(val count: Int) : ShapeKind {
            init {
                require(count >= 1) { "a words shape has at least one run" }
            }

            override val wire: String =
                if (count > MAX_HASHABLE_WORDS) WORDS_OVER else "$WORDS_PREFIX$count"

            override val hashable: Boolean get() = count <= MAX_HASHABLE_WORDS
        }
    }

    /**
     * Classify [value] — trimmed and capped by the caller — into its [ShapeKind]. First match wins:
     * `digits`, then `words:N`, else `mixed`. A blank value is never classified (the builder omits it);
     * if one arrives anyway it is `mixed`, the non-hashable fallback.
     */
    fun shapeKind(value: String): ShapeKind {
        val codePoints = value.codePoints().toArray()
        val nonWhitespace = codePoints.filter { !isWhitespace(it) }
        if (nonWhitespace.isEmpty()) return ShapeKind.Mixed
        if (nonWhitespace.all { Character.isDigit(it) }) return ShapeKind.Digits

        var words = 0
        for (run in splitRuns(codePoints)) {
            // Drop a run with no letter AND no digit (`&`, `→`, `-`, emoji) BEFORE counting.
            if (run.none { Character.isLetter(it) || Character.isDigit(it) }) continue
            val stripped = stripEdgePunctuation(run)
            // Every remaining run: at least one letter, no digit.
            if (stripped.any { Character.isDigit(it) }) return ShapeKind.Mixed
            if (stripped.none { Character.isLetter(it) }) return ShapeKind.Mixed
            words++
        }
        return if (words >= 1) ShapeKind.Words(words) else ShapeKind.Mixed
    }

    /** True when [kind] is one of the wire kinds this grammar (plus [WITHHELD]) can emit. */
    fun isWireKind(kind: String): Boolean = when {
        kind == WITHHELD || kind == DIGITS || kind == MIXED || kind == WORDS_OVER -> true
        kind.startsWith(WORDS_PREFIX) -> hashableWordCount(kind) != null
        else -> false
    }

    /** True when [kind] is `words:1..8` — the only kinds that carry (and require) a hash. */
    fun isHashableKind(kind: String): Boolean = hashableWordCount(kind) != null

    private fun hashableWordCount(kind: String): Int? {
        if (!kind.startsWith(WORDS_PREFIX)) return null
        val digits = kind.substring(WORDS_PREFIX.length)
        if (digits.length != 1 || digits[0] !in '1'..'8') return null
        return digits[0] - '0'
    }

    private fun isWhitespace(cp: Int): Boolean = Character.isWhitespace(cp) || Character.isSpaceChar(cp)

    private fun splitRuns(codePoints: IntArray): List<IntArray> {
        val runs = mutableListOf<IntArray>()
        var start = -1
        for (i in codePoints.indices) {
            if (isWhitespace(codePoints[i])) {
                if (start >= 0) runs += codePoints.copyOfRange(start, i)
                start = -1
            } else if (start < 0) {
                start = i
            }
        }
        if (start >= 0) runs += codePoints.copyOfRange(start, codePoints.size)
        return runs
    }

    /** Strip LEADING and TRAILING Unicode punctuation (general category P*); interior `'`/`-` stay. */
    private fun stripEdgePunctuation(run: IntArray): IntArray {
        var from = 0
        var to = run.size
        while (from < to && isPunctuation(run[from])) from++
        while (to > from && isPunctuation(run[to - 1])) to--
        return run.copyOfRange(from, to)
    }

    private fun isPunctuation(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.CONNECTOR_PUNCTUATION,
        Character.DASH_PUNCTUATION,
        Character.START_PUNCTUATION,
        Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION,
        Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION,
        -> true
        else -> false
    }
}
