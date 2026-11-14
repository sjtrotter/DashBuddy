package cloud.trotter.log.gig.feature.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.census.contract.contributor.ContributorRole
import cloud.trotter.log.gig.core.data.census.contributor.CensusContributor
import cloud.trotter.log.gig.core.data.census.contributor.ContributorReviewer
import cloud.trotter.log.gig.core.data.census.contributor.ReviewLoad
import cloud.trotter.log.gig.core.data.census.contributor.ScreenReview
import cloud.trotter.log.gig.core.data.census.contributor.SendOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** #1626: where the review screen is. */
enum class ReviewPhase { LOADING, READY, SENDING, SENT, DELETED, NOT_ALLOWED, NOT_FOUND, NOT_READY }

/**
 * #1626: the review screen's state. [review]'s rows carry a word only for a constant slot this phone still holds;
 * [confirmed] and [roles] are the dasher's choices, by slot id. Held in the ViewModel only, never saved state: the
 * words must not reach a saved-instance bundle.
 */
data class ReviewUiState(
    val phase: ReviewPhase = ReviewPhase.LOADING,
    val review: ScreenReview? = null,
    val confirmed: Set<String> = emptySet(),
    val roles: Map<String, ContributorRole> = emptyMap(),
    /** The last Send that did not go through. */
    val failure: SendOutcome? = null,
    val shiftLive: Boolean = false,
) {
    val canSend: Boolean get() = phase == ReviewPhase.READY && (confirmed.isNotEmpty() || roles.isNotEmpty())
}

@HiltViewModel
class ContributorReviewViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val reviewer: ContributorReviewer,
    private val contributor: CensusContributor,
) : ViewModel() {
    private val id: String = savedState.get<String>(ARG_ID).orEmpty()
    private val state = MutableStateFlow(ReviewUiState())

    val uiState: StateFlow<ReviewUiState> =
        combine(state, contributor.status.map { it.shiftLive }) { s, live -> s.copy(shiftLive = live) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewUiState())

    init {
        viewModelScope.launch {
            // The server's client guide: read the grant's state before each review session, outside the upload lock.
            contributor.refresh()
            val phase = when (val load = reviewer.open(id)) {
                is ReviewLoad.Ready -> {
                    state.update { it.copy(review = load.review) }
                    ReviewPhase.READY
                }
                ReviewLoad.NotAllowed -> ReviewPhase.NOT_ALLOWED
                ReviewLoad.NotFound -> ReviewPhase.NOT_FOUND
                ReviewLoad.NotReady -> ReviewPhase.NOT_READY
            }
            state.update { it.copy(phase = phase) }
        }
    }

    fun toggleConfirmed(slot: String) {
        val row = state.value.review?.rows?.firstOrNull { it.slot == slot } ?: return
        if (!row.confirmable) return
        state.update { it.copy(confirmed = if (slot in it.confirmed) it.confirmed - slot else it.confirmed + slot) }
    }

    fun setRole(slot: String, role: ContributorRole?) {
        state.update { it.copy(roles = if (role == null) it.roles - slot else it.roles + (slot to role)) }
    }

    fun send() {
        val current = state.value
        val review = current.review ?: return
        if (!current.canSend) return
        state.update { it.copy(phase = ReviewPhase.SENDING, failure = null) }
        viewModelScope.launch {
            val outcome = reviewer.send(id, current.confirmed, current.roles, review)
            when (outcome) {
                SendOutcome.SENT -> state.update { it.copy(phase = ReviewPhase.SENT) }
                SendOutcome.NOT_FOUND -> state.update { it.copy(phase = ReviewPhase.NOT_FOUND) }
                SendOutcome.NOT_ALLOWED, SendOutcome.INSTALL_REVOKED -> {
                    // The grant or the census ID ended on the server: the contributor's state read ends it here too.
                    contributor.refresh()
                    state.update { it.copy(phase = ReviewPhase.NOT_ALLOWED) }
                }
                else -> state.update { it.copy(phase = ReviewPhase.READY, failure = outcome) }
            }
        }
    }

    fun delete() {
        viewModelScope.launch {
            reviewer.delete(id)
            state.update { it.copy(phase = ReviewPhase.DELETED, review = null) }
        }
    }

    companion object {
        const val ARG_ID: String = "reviewId"
    }
}
