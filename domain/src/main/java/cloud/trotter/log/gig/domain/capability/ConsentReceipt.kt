package cloud.trotter.log.gig.domain.capability

import kotlinx.serialization.Serializable

/**
 * #170: the record beside a consent decision — when, under which app version, against which disclosure revision.
 *
 * Forward-compat rule: the stores decode with `ignoreUnknownKeys` and re-encode the WHOLE map on every write, so
 * a field added later MUST carry a default — otherwise every older receipt decodes as empty and the next decision
 * overwrites the store with only its own key.
 */
@Serializable
data class ConsentReceipt(
    val decidedAt: Long,
    val appVersion: String,
    val disclosureRevision: Int,
    val granted: Boolean,
)

/**
 * The revision of PRIVACY.md the in-app disclosure refers to (bump when the document's claims change). PRIVACY.md's
 * revision history is how a reader maps a receipt's revision to the text it referred to.
 *
 * [URL] is the master copy: the stable link the Play Console policy entry uses. The in-app link (#170) opens the
 * copy as of the build's own commit, [urlAt] with `BuildConfig.PRIVACY_REF`, provided to every screen as the
 * `@PrivacyNoticeUrl` string; a build with no clean commit falls back to master.
 */
object PrivacyDisclosure {
    const val REVISION: Int = 16

    /** PRIVACY.md at a git ref (a commit sha or `master`). */
    fun urlAt(ref: String): String = "https://github.com/sjtrotter/gig.log/blob/$ref/PRIVACY.md"

    val URL: String = urlAt("master")
}
