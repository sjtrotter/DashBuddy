package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.core.pipeline.rules.CompiledRedact
import cloud.trotter.dashbuddy.domain.privacy.MaskTokens

/**
 * #1116 — the GATED return task line `"Return <name> to <store>"` on UNKNOWN screen and click envelopes:
 * only the customer's NAME is masked, the store stays raw (#886).
 *
 * A hand-written, single-pass parse per line (review r1 #3: a regex with overlapping whitespace and
 * wildcard quantifiers went cubic on `"Return" + 4,090 spaces`, inside the 4,096-character field cap):
 *  1. leading whitespace is skipped and the literal `Return` (any case) is required, followed by a
 *     whitespace run, which is consumed WHOLE;
 *  2. chrome exclusion AFTER that run (review r1 #4): a name slot that opens with the word `to` is the
 *     platform's own `Return to dash` button (any spacing, any tail), never a customer;
 *  3. the separator is the LAST whitespace-`to`-whitespace that is followed by non-blank text; the name is
 *     everything between the lead-in and that separator, trailing whitespace dropped, and must be
 *     non-blank and not already a mask token.
 * Every `\n`-separated line of a field is parsed on its own (review r1 #5: a merged field carrying two
 * task lines masks both names). Work is O(length): each character is read a constant number of times,
 * counted through the optional `steps` cell so a test pins the bound without a stopwatch.
 *
 * Deliberately NOT a `CustomerTextMarkers.MARKERS` entry (whole-field, every path) and asymmetric with the
 * intake gate (`PiiShapes.GATED_NAME_PREFIXES`, which also requires the first-name + last-initial shape,
 * #1064; `customerLeadIn` stays the intake's one owner): the runtime UNKNOWN side fails toward privacy.
 */
object ReturnTaskLine {

    /** True when some line of [text] carries a raw return-line name. */
    fun hasRawName(text: String, steps: LongArray? = null): Boolean {
        var ls = 0
        while (ls <= text.length) {
            val le = text.indexOf('\n', ls).let { if (it < 0) text.length else it }
            if (nameRange(text, ls, le, steps) != null) return true
            ls = le + 1
        }
        return false
    }

    /** [text] with every raw return-line name replaced by [CompiledRedact.REDACTED]; [text] itself when none. */
    fun mask(text: String, steps: LongArray? = null): String {
        var out: StringBuilder? = null
        var copied = 0
        var ls = 0
        while (ls <= text.length) {
            val le = text.indexOf('\n', ls).let { if (it < 0) text.length else it }
            val range = nameRange(text, ls, le, steps)
            if (range != null) {
                val sb = out ?: StringBuilder(text.length).also { out = it }
                sb.append(text, copied, range.first).append(CompiledRedact.REDACTED)
                copied = range.last + 1
            }
            ls = le + 1
        }
        val sb = out ?: return text
        return sb.append(text, copied, text.length).toString()
    }

    /** The raw name's index range within the line `[ls, le)` of [text], or null. */
    private fun nameRange(text: String, ls: Int, le: Int, steps: LongArray?): IntRange? {
        var i = ls
        while (i < le && text[i].isWhitespace()) { i++; tick(steps) }
        if (le - i < PREFIX.length || !text.regionMatches(i, PREFIX, 0, PREFIX.length, ignoreCase = true)) return null
        i += PREFIX.length
        if (i >= le || !text[i].isWhitespace()) return null
        while (i < le && text[i].isWhitespace()) { i++; tick(steps) }
        val nameStart = i
        if (nameStart >= le || isToWord(text, nameStart, le)) return null

        var lastNonBlank = le - 1
        while (lastNonBlank >= nameStart && text[lastNonBlank].isWhitespace()) { lastNonBlank--; tick(steps) }

        var separator = -1
        var k = nameStart
        while (k + 3 < le) {
            tick(steps)
            if (text[k].isWhitespace() && text[k + 3].isWhitespace() &&
                text.regionMatches(k + 1, "to", 0, 2, ignoreCase = true) && lastNonBlank > k + 3
            ) {
                separator = k
            }
            k++
        }
        if (separator < 0) return null
        var nameEnd = separator
        while (nameEnd > nameStart && text[nameEnd - 1].isWhitespace()) { nameEnd--; tick(steps) }
        if (nameEnd <= nameStart) return null
        if (MaskTokens.isMask(text.substring(nameStart, nameEnd))) return null
        return nameStart until nameEnd
    }

    /** The word `to` (any case) at [at], ending at a whitespace or the line end. */
    private fun isToWord(text: String, at: Int, le: Int): Boolean =
        at + 2 <= le && text.regionMatches(at, "to", 0, 2, ignoreCase = true) &&
            (at + 2 == le || text[at + 2].isWhitespace())

    private fun tick(steps: LongArray?) {
        if (steps != null) steps[0]++
    }

    private const val PREFIX = "Return"
}
