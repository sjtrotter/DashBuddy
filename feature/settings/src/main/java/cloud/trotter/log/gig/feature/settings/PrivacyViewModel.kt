package cloud.trotter.log.gig.feature.settings

import androidx.lifecycle.ViewModel
import cloud.trotter.log.gig.domain.di.PrivacyNoticeUrl
import cloud.trotter.log.gig.domain.di.WideEventReceiptOffered
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * #170 — the two build facts the privacy page shows: its technical-details link (PRIVACY.md as of this build's
 * commit) and whether this build offers Screen events (false in the Play build, whose page then leaves the Screen
 * events line out).
 * Both are fixed for the process; there is no state.
 */
@HiltViewModel
class PrivacyViewModel @Inject constructor(
    @param:PrivacyNoticeUrl val privacyUrl: String,
    @param:WideEventReceiptOffered val screenEventsOffered: Boolean,
) : ViewModel()
