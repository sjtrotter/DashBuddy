package cloud.trotter.log.gig.ui.main.settings

import cloud.trotter.log.gig.core.data.census.contributor.CensusContributor
import cloud.trotter.log.gig.core.data.census.contributor.ContributorStatus
import cloud.trotter.log.gig.domain.census.contributor.DecisionVersion
import cloud.trotter.log.gig.domain.census.contributor.ContributorEntry
import cloud.trotter.log.gig.domain.census.contributor.ContributorRecord
import kotlinx.coroutines.flow.flowOf
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** #1625: a contributor with nothing to show, for the census screen's tests that are not about screen reviews. */
internal fun hiddenReviews(): CensusContributor = mock<CensusContributor>().also {
    whenever(it.status).thenReturn(
        flowOf(
            ContributorStatus(
                ContributorRecord(), ContributorEntry.HIDDEN, canAccept = false, shiftLive = false,
                version = DecisionVersion(0, 0, "https://census.example", null, null),
            ),
        ),
    )
}
