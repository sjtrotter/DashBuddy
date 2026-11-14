package cloud.trotter.log.gig.domain.census.contributor

import kotlinx.coroutines.flow.Flow

/** #1625: whether a shift is running on any delivery app, from the app's state machine (bound in `:app`). */
interface ShiftActivity {
    val shiftLive: Flow<Boolean>
}

/**
 * #1625: the check every contributor upload makes first (#1626 uploads). True only with the census on, a live grant
 * issued to this phone's current census ID, and no deletion request since it was issued. A check that finds the grant
 * expired, revoked or withdrawn ends it, which also drops the reviews waiting on the phone.
 */
interface ContributorGate {
    suspend fun mayUpload(): Boolean
}

/**
 * #1625: the reviews waiting on the phone, held by #1626's review queue (its stored candidates and the words in
 * memory). Cleared whenever the grant ends (expiry, revocation, the dasher's Stop, the census off, a data deletion); a
 * clear that cannot finish throws, so the contributor runs it again.
 */
fun interface PendingContributorReviews {
    suspend fun clear()
}
