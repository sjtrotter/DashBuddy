package cloud.trotter.dashbuddy.core.pipeline.census

import cloud.trotter.dashbuddy.domain.census.contract.CaseFold

/**
 * The census's letter-run vocabulary (ADR-0011 §2; split from `SkeletonBuilder` by #1160 review AD10): the
 * code-point letter runs text slots are judged on, the camelCase split ids and classes add, and the bounded
 * cross-separator join search the frame rule runs on an id or class. Public (review AH5) so the `:app` corpus
 * mirrors call the same split rather than re-implementing it; it holds vocabulary, no policy.
 */
object LetterRuns {

    /**
     * The value's maximal runs of Unicode letters, case-FOLDED with the one [CaseFold] (review HH1) — so
     * "Adam's order" yields `adam`, `s`, `order` and "Adam, 2 items" yields `adam`, `items` (review GG1).
     * [minLetters] counts letter CODE POINTS of the original run before folding (review HH2: UTF-16 units
     * over-count a supplementary-plane letter, and a fold can change the length).
     */
    fun letterRuns(value: String, minLetters: Int = 0): List<String> =
        plainLetterRuns(value).filter { it.codePointCount(0, it.length) >= minLetters }.map { CaseFold.fold(it) }

    /** Maximal runs of Unicode letters (code-point based), unfolded. */
    fun plainLetterRuns(value: String): List<String> {
        val runs = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            if (Character.isLetter(cp)) {
                sb.appendCodePoint(cp)
            } else if (sb.isNotEmpty()) {
                runs += sb.toString()
                sb.setLength(0)
            }
            i += Character.charCount(cp)
        }
        if (sb.isNotEmpty()) runs += sb.toString()
        return runs
    }

    /**
     * A letter run split at camelCase boundaries (review KK1): lower→Upper (`chip|Adam`), and
     * Upper→Upper+lower (`XML|Adam`: before the upper that starts a lowercase word).
     */
    fun camelSegments(token: String): List<String> {
        val segments = ArrayList<String>()
        val sb = StringBuilder()
        var prev = -1
        var i = 0
        while (i < token.length) {
            val cp = token.codePointAt(i)
            val next = i + Character.charCount(cp)
            if (sb.isNotEmpty() && Character.isUpperCase(cp)) {
                val nextCp = if (next < token.length) token.codePointAt(next) else -1
                if (Character.isLowerCase(prev) ||
                    (Character.isUpperCase(prev) && nextCp >= 0 && Character.isLowerCase(nextCp))
                ) {
                    segments += sb.toString()
                    sb.setLength(0)
                }
            }
            sb.appendCodePoint(cp)
            prev = cp
            i = next
        }
        if (sb.isNotEmpty()) segments += sb.toString()
        return segments
    }

    /**
     * The id/class form's FOLDED segments (reviews KK1, MM1, AB3, AC1): [candidate]'s letter runs split at
     * camelCase boundaries, each folded ONCE (review AD6 — `CaseFold` is per code point, so a join of folded
     * segments equals the fold of the join).
     */
    fun foldedSegments(candidate: String): List<String> =
        plainLetterRuns(candidate).flatMap { camelSegments(it) }.map { CaseFold.fold(it) }

    /**
     * Does any CONTIGUOUS join of [segments] (across separators — `row_mc_kenna` → `mckenna`) equal a member
     * of [seeds]? Review AD6: the joins are substrings of ONE concatenation, and only a join whose length is
     * a seed length ([seedLengths]) is materialized. Bounded by the candidate (an id name part is ≤ 64
     * chars, a class ≤ 128).
     */
    fun anyJoinIn(segments: List<String>, seeds: Set<String>, seedLengths: Set<Int>): Boolean {
        if (seeds.isEmpty() || segments.isEmpty()) return false
        val joined = segments.joinToString("")
        val offsets = IntArray(segments.size + 1)
        for (i in segments.indices) offsets[i + 1] = offsets[i] + segments[i].length
        val maxLength = seedLengths.maxOrNull() ?: return false
        for (from in segments.indices) {
            for (to in from until segments.size) {
                val length = offsets[to + 1] - offsets[from]
                if (length > maxLength) break
                if (length in seedLengths && joined.substring(offsets[from], offsets[to + 1]) in seeds) return true
            }
        }
        return false
    }
}
