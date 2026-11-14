package cloud.trotter.log.gig.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.gig.core.data.census.contributor.CensusContributor
import cloud.trotter.log.gig.core.data.census.contributor.DecisionVersion
import cloud.trotter.log.gig.core.data.census.contributor.GrantRequestOutcome
import cloud.trotter.log.gig.domain.census.contributor.ContributorEnd
import cloud.trotter.log.gig.domain.census.contributor.ContributorEntry
import cloud.trotter.log.gig.domain.census.contributor.GrantRefusal
import cloud.trotter.log.gig.domain.census.contributor.InvitationAnswer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * #1625: the reviewer screen. Holds no eligibility count: only whether Accept is open now ([canAccept]).
 * [outcome] is the last Accept's outcome in this process, shown until the record says more.
 */
data class ContributorUiState(
    val entry: ContributorEntry,
    val canAccept: Boolean,
    val shiftLive: Boolean,
    val answer: InvitationAnswer,
    /** The live grant's first day it no longer works (`YYYY-MM-DD`, UTC); null without one. */
    val expiresDay: String?,
    val ended: ContributorEnd?,
    val refusal: GrantRefusal?,
    val requesting: Boolean = false,
    val outcome: GrantRequestOutcome? = null,
    /** What an Accept pressed now is decided under; null until the store answers. */
    val version: DecisionVersion? = null,
)

@HiltViewModel
class ContributorViewModel @Inject constructor(private val contributor: CensusContributor) : ViewModel() {

    /** Null until the store answers. */
    val uiState: StateFlow<ContributorUiState?> =
        combine(contributor.status, contributor.requesting, contributor.lastOutcome) { status, requesting, outcome ->
            val record = status.record
            ContributorUiState(
                entry = status.entry,
                canAccept = status.canAccept,
                shiftLive = status.shiftLive,
                answer = record.answer,
                expiresDay = record.grant?.expiresDay?.takeIf { status.entry == ContributorEntry.ON },
                ended = record.ended,
                refusal = record.refusal,
                requesting = requesting,
                outcome = outcome,
                version = status.version,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Accept: the grant request runs on the application scope, so leaving the screen cannot cut it short. */
    fun accept() {
        val version = uiState.value?.version ?: return
        contributor.acceptAsync(version)
    }

    /** Not now, No thanks and Stop also run on the application scope: Not now and No thanks close the screen. */
    fun notNow() {
        contributor.notNowAsync()
    }

    fun noThanks() {
        contributor.noThanksAsync()
    }

    fun stop() {
        contributor.stopAsync()
    }
}
