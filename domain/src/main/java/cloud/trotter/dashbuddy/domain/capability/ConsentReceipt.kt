package cloud.trotter.dashbuddy.domain.capability

import kotlinx.serialization.Serializable

/** #170: the record beside a consent decision — when, under which app version, against which disclosure revision. */
@Serializable
data class ConsentReceipt(
    val decidedAt: Long,
    val appVersion: String,
    val disclosureRevision: Int,
    val granted: Boolean,
)

/** The revision of PRIVACY.md the in-app disclosure refers to (bump when the document's claims change). */
object PrivacyDisclosure {
    const val REVISION: Int = 1
    const val URL: String = "https://github.com/sjtrotter/DashBuddy/blob/master/PRIVACY.md"
}
