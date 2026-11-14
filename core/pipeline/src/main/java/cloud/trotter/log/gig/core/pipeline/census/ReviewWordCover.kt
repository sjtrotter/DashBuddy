package cloud.trotter.log.gig.core.pipeline.census

import cloud.trotter.log.gig.core.pipeline.CustomerTextMarkers
import cloud.trotter.log.gig.core.pipeline.SensitiveTextMarkers
import cloud.trotter.log.gig.domain.privacy.PiiShapes

/**
 * #1626 (ADR-0011 §15, §21): the screen review's own cover, on top of the census filter. A word the skeleton hashed
 * passed the filter, which already withheld every value its steps catch (customer markers, lead-ins, masks, name and
 * other PII shapes, PII ids, text inputs, the frame-level duplicate rule). The review holds a word only when it also
 * passes this check, run on the word alone; a covered word is never kept, so the review screen has nothing to show for
 * that slot and nothing that could be unmasked.
 *
 * The check is deliberately wider than the filter, because a word shown on the review screen can later be sent as
 * plaintext: it covers the dasher's own greeting, account and place labels, which the filter hashes (a hash is fine;
 * the word on a reviewed screen is not), a customer's note or message and any label carrying its value after a colon,
 * and anything with a digit or an `@`. Over-covering costs a confirmation; it
 * never sends anything.
 */
object ReviewWordCover {

    /** True when the review may hold and show [word] (a canonical census word). */
    fun shows(word: String): Boolean = try {
        !covers(word)
    } catch (_: Exception) {
        false
    }

    private fun covers(word: String): Boolean =
        word.isBlank() ||
            word.any { it.isDigit() || it == '@' } ||
            SensitiveTextMarkers.findMarker(word) != null ||
            CustomerTextMarkers.unredactedMarker(word) != null ||
            PiiShapes.containsMask(word) ||
            SkeletonBuilder.judgesValue(word) ||
            GREETING.containsMatchIn(word) ||
            ACCOUNT.containsMatchIn(word) ||
            PLACE.containsMatchIn(word) ||
            NOTE.containsMatchIn(word) ||
            LABELLED_VALUE.containsMatchIn(word)

    /** A greeting carries the dasher's name ("Hi, Sam", "Welcome back"). */
    private val GREETING = Regex("""^\W*(hi|hello|hey|welcome|good (morning|afternoon|evening|night)|thanks|thank you)\b""", RegexOption.IGNORE_CASE)

    /** The dasher's account labels. */
    private val ACCOUNT = Regex("""\b(account|acct|dasher id|ending in|member since|my profile|signed in as)\b""", RegexOption.IGNORE_CASE)

    /** A customer's note or a message names its own content ("Customer Notes: ring twice"). */
    private val NOTE = Regex("""\b(note|notes|instruction|instructions|message|messages)\b""", RegexOption.IGNORE_CASE)

    /** A label and its value in one text ("Name: Sam"): the value is not app wording. */
    private val LABELLED_VALUE = Regex("""\S:\s+\S""")

    /** A place after a preposition ("Dash in Riverside", "Busy near Oak Park"): a zone, city or store name. */
    private val PLACE = Regex("""(^|\s)([Ii]n|[Aa]t|[Nn]ear|[Aa]round|[Ff]rom|[Tt]o)\s+\p{Lu}""")
}
