package cloud.trotter.log.gig.core.pipeline.census

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/**
 * #1626 (ADR-0011 §15, Sol round 2): the screen review's allow-list, the structural first layer of the review cover.
 * A word may be held for review only when every one of its letter runs (lowercased, NFC) is in one of two lists:
 *
 * - [COMMON_RESOURCE]: the 5,000 most frequent lowercase words of each of English, Spanish and French (Google Books
 *   Ngram, CC BY 3.0, source in the file's header), with every capitalized entry (a proper noun) dropped;
 * - [RULES_RESOURCE]: every letter run of every string literal in the bundled rules, generated at build time from
 *   `matchers/` (`:core:pipeline:reviewRuleWords`): the app's known chrome, brand words included.
 *
 * A name or a place outside both lists can therefore never be shown or sent, whatever new spelling it takes; one that
 * is also a common word ("Grace", "Will", "Main") is not caught here (risk 12), which is why [ReviewWordCover]'s
 * denylist stays as the second layer. Fails closed: a missing or corrupt resource contributes no word, so less is
 * allowed.
 */
object ReviewAllowList {
    const val COMMON_RESOURCE = "/census/review-common-words.txt"
    const val RULES_RESOURCE = "/census/review-rule-words.txt"
    private const val HEADER = "#sha256="
    private const val MAX_BYTES = 1 shl 20

    private val words: Set<String> by lazy {
        val common = parse(verifyHash = true) { ReviewAllowList::class.java.getResourceAsStream(COMMON_RESOURCE) }
        val rules = parse(verifyHash = false) { ReviewAllowList::class.java.getResourceAsStream(RULES_RESOURCE) }
        common + rules
    }

    /** How many words each list contributed, for the tests. */
    internal val size: Int get() = words.size

    /** True when every letter run of [word] is allowed; false for a word with none. */
    fun allows(word: String): Boolean = allows(word, words)

    internal fun allows(word: String, allowed: Set<String>): Boolean {
        val runs = LETTER_RUN.findAll(fold(word)).map { it.value }.toList()
        return runs.isNotEmpty() && runs.all { it in allowed }
    }

    /** True when [token] alone is in the lists (a test reads this to show a list holds what it should). */
    internal fun contains(token: String): Boolean = fold(token) in words

    private fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT)

    /**
     * One list: `#` lines are comments; when [verifyHash], a `#sha256=` line must match the sha256 of every byte after
     * it (a truncated list fails). Bounded size, strict UTF-8. Any failure gives the empty set.
     */
    internal fun parse(verifyHash: Boolean, open: () -> InputStream?): Set<String> = try {
        parseOrThrow(verifyHash, open)
    } catch (_: Exception) {
        emptySet()
    }

    private fun parseOrThrow(verifyHash: Boolean, open: () -> InputStream?): Set<String> {
        val bytes = open()?.use { readBounded(it, MAX_BYTES + 1) } ?: ByteArray(0)
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return emptySet()
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        val headerAt = text.indexOf(HEADER)
        if (verifyHash) {
            if (headerAt < 0) return emptySet()
            val lineEnd = text.indexOf('\n', headerAt)
            if (lineEnd < 0) return emptySet()
            val expected = text.substring(headerAt + HEADER.length, lineEnd).trim()
            val body = text.substring(lineEnd + 1).toByteArray(Charsets.UTF_8)
            val actual = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(Locale.ROOT, it) }
            if (actual != expected) return emptySet()
        }
        return text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map(::fold).toSet()
    }

    /** At most [limit] bytes of [stream] (no `readNBytes`: API 33, above minSdk). */
    private fun readBounded(stream: InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val read = stream.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private val LETTER_RUN = Regex("""\p{L}+""")
}
