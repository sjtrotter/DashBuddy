package cloud.trotter.dashbuddy.domain.capability

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
 * The revision of PRIVACY.md the in-app disclosure refers to (bump when the document's claims change). [URL] opens
 * the CURRENT revision (a moving head); PRIVACY.md's revision history is how a reader maps a receipt's revision to
 * the text it referred to — pinning the link per revision is #1237.
 */
object PrivacyDisclosure {
    const val REVISION: Int = 1
    const val URL: String = "https://github.com/sjtrotter/DashBuddy/blob/master/PRIVACY.md"
}
