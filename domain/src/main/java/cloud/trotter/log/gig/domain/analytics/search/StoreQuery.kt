package cloud.trotter.log.gig.domain.analytics.search

import java.util.Locale

/**
 * A store search (#1540): the dasher's text lowercased with [Locale.ROOT], every whitespace run collapsed to one space,
 * trimmed. A name matches when its own normalized form contains [normalized], so a stack's "Five Guys & Chipotle"
 * matches "chipotle". Collapsing never joins words: "wing stop" does not match "Wingstop". Not
 * `StoreKeys.normalizedChain`, which strips parts of a name and is meant for canonical keys.
 *
 * The query is a merchant name, but it is still never written to an INFO or higher log line.
 */
@JvmInline
value class StoreQuery private constructor(val normalized: String) {

    /** True when [name] contains this query once both are normalized; a null or blank name never matches. */
    fun matches(name: String?): Boolean = name != null && normalize(name)?.contains(normalized) == true

    companion object {
        /** The normalized form of [text]; null when it is blank (no query). */
        fun normalize(text: String): String? {
            // Char.isWhitespace covers the no-break space a rendered name can carry; no regex, so the JVM and ICU agree.
            val out = StringBuilder(text.length)
            var space = false
            for (c in text.lowercase(Locale.ROOT)) {
                if (c.isWhitespace()) {
                    space = out.isNotEmpty()
                } else {
                    if (space) out.append(' ')
                    space = false
                    out.append(c)
                }
            }
            return out.toString().takeIf { it.isNotEmpty() }
        }

        /** The query for [text]; null when it is blank. */
        fun of(text: String): StoreQuery? = normalize(text)?.let(::StoreQuery)
    }
}
