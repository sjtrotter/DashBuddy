package cloud.trotter.log.gig.core.pipeline.census

import cloud.trotter.log.gig.core.pipeline.CustomerTextMarkers
import cloud.trotter.log.gig.core.pipeline.ReturnTaskLine
import cloud.trotter.log.gig.core.pipeline.SensitiveTextMarkers
import cloud.trotter.log.gig.domain.privacy.PiiShapes

/**
 * #1626 (ADR-0011 §15, §21): the screen review's own cover, on top of the census filter. A word the skeleton hashed
 * passed the filter, which already withheld every value its steps catch (customer markers, lead-ins, masks, name and
 * other PII shapes, PII ids, text inputs, the frame-level duplicate rule). The review holds a word only when it also
 * passes this check, run on the word alone; a covered word is never kept, so the review screen has nothing to show for
 * that slot and nothing that could be unmasked.
 *
 * Two layers: [ReviewAllowList] first (every token must be a common en/es/fr word or a word of the bundled rules), then
 * the denylist below. The check is deliberately wider than the filter, because a word shown on the review screen can later be sent as
 * plaintext: it covers the dasher's own greeting, account and place labels, which the filter hashes (a hash is fine;
 * the word on a reviewed screen is not), a customer's note or message, a return-task line's name, any text with a colon (a label and its value),
 * and anything with a digit or an `@`. Over-covering costs a confirmation; it
 * never sends anything.
 */
object ReviewWordCover {

    /** True when the review may hold and show [word] (a canonical census word). */
    fun shows(word: String): Boolean = try {
        // The allow-list is the first layer (Sol round 2): a word with any token outside it is never held.
        ReviewAllowList.allows(word) && !covers(word)
    } catch (_: Exception) {
        false
    }

    private fun covers(word: String): Boolean =
        word.isBlank() ||
            word.any { it.isDigit() || it == '@' } ||
            SensitiveTextMarkers.findMarker(word) != null ||
            CustomerTextMarkers.unredactedMarker(word) != null ||
            ReturnTaskLine.hasRawName(word) ||
            PiiShapes.containsMask(word) ||
            SkeletonBuilder.judgesValue(word) ||
            GREETING.containsMatchIn(word) ||
            ACCOUNT.containsMatchIn(word) ||
            PLACE.containsMatchIn(word) ||
            NOTE.containsMatchIn(word) ||
            word.any { it in COLONS }

    /** A greeting carries the dasher's name ("Hi, Sam", "Welcome back"). */
    private val GREETING = Regex("""^\W*(hi|hello|hey|welcome|good (morning|afternoon|evening|night)|thanks|thank you)\b""", RegexOption.IGNORE_CASE)

    /** The dasher's account labels. */
    private val ACCOUNT = Regex("""\b(account|acct|dasher id|ending in|member since|my profile|signed in as)\b""", RegexOption.IGNORE_CASE)

    /** A customer's note or a message names its own content ("Customer Notes: ring twice"). */
    private val NOTE = Regex("""\b(note|notes|instruction|instructions|message|messages)\b""", RegexOption.IGNORE_CASE)

    /**
     * A label and its value in one text ("Name: Sam", "Zone:Springfield"): any colon at all covers the word, with or
     * without spaces, and so does every colon-like character the canonical fold might not map to `:`.
     */
    private const val COLONS = ":\uFF1A\uFE55\uFE13\u2236\uA789\u02D0\u02F8\u0589\u05C3\u0703\u0704\u1361\u1364\u1804\u1C7E\u205A\u2E2C\u303D"

    /**
     * A place after a preposition, in any case ("Dash in Riverside", "DASH IN SPRINGFIELD", "Busy near downtown"): a
     * zone, city or store name. It also covers chrome such as "Arrived at store"; over-covering costs a confirmation.
     */
    private val PLACE = Regex("""(^|\s)(in|at|near|around|from|to)\s+\p{L}""", RegexOption.IGNORE_CASE)
}
