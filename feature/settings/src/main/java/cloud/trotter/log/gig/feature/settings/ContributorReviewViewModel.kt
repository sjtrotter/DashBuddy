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
import cloud.trotter.log.gig.domain.census.contributor.ContributorEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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

/**
 * #1626 (Sol S6): the grant stopped standing (it ended, expired, or the census went off): the rows, their words and
 * the dasher's choices go, and the screen offers nothing until it is opened again. Sent and Deleted keep their message.
 */
internal fun ReviewUiState.grantEnded(): ReviewUiState = when (phase) {
    ReviewPhase.SENT, ReviewPhase.DELETED -> copy(review = null, confirmed = emptySet(), roles = emptyMap())
    else -> ReviewUiState(phase = ReviewPhase.NOT_ALLOWED, shiftLive = shiftLive)
}

/** The session's answer, unless the grant already ended while it ran (that stays). */
internal fun ReviewUiState.loaded(load: ReviewLoad): ReviewUiState {
    if (phase != ReviewPhase.LOADING) return this
    return when (load) {
        is ReviewLoad.Ready -> copy(phase = ReviewPhase.READY, review = load.review)
        ReviewLoad.NotAllowed -> copy(phase = ReviewPhase.NOT_ALLOWED)
        ReviewLoad.NotFound -> copy(phase = ReviewPhase.NOT_FOUND)
        ReviewLoad.NotReady -> copy(phase = ReviewPhase.NOT_READY)
    }
}

/** A Send's outcome, unless the grant ended while it ran. */
internal fun ReviewUiState.sent(outcome: SendOutcome): ReviewUiState {
    if (phase != ReviewPhase.SENDING) return this
    return when (outcome) {
        SendOutcome.SENT -> copy(phase = ReviewPhase.SENT, review = null, confirmed = emptySet(), roles = emptyMap())
        SendOutcome.NOT_FOUND -> copy(phase = ReviewPhase.NOT_FOUND, review = null)
        SendOutcome.NOT_ALLOWED, SendOutcome.INSTALL_REVOKED -> grantEnded()
        else -> copy(phase = ReviewPhase.READY, failure = outcome)
    }
}

@HiltViewModel
class ContributorReviewViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val reviewer: ContributorReviewer,
    private val contributor: CensusContributor,
) : ViewModel() {
    private val id: String = savedState.get<String>(ARG_ID).orEmpty()
    private val state = MutableStateFlow(ReviewUiState())

    /**
     * Derived from the grant on every read (Sol round 2 S6): a status that is not ON maps to the cleared state, and the
     * cache is dropped when the last subscriber leaves (`replayExpirationMillis = 0`), so a screen that comes back after
     * the grant ended never sees the review it held before.
     */
    val uiState: StateFlow<ReviewUiState> =
        combine(state, contributor.status) { s, status ->
            (if (status.entry == ContributorEntry.ON) s else s.grantEnded()).copy(shiftLive = status.shiftLive)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), ReviewUiState())

    init {
        viewModelScope.launch {
            // The server's client guide: read the grant's state before each review session, outside the upload lock.
            contributor.refresh()
            val load = reviewer.open(id)
            state.update { it.loaded(load) }
        }
        // The grant stopping to stand clears what the screen holds, whatever it is doing (Sol S6).
        viewModelScope.launch {
            contributor.status.map { it.entry == ContributorEntry.ON }.distinctUntilChanged().collect { standing ->
                if (!standing) state.update { it.grantEnded() }
            }
        }
    }

    fun toggleConfirmed(slot: String) {
        val row = state.value.review?.rows?.firstOrNull { it.slot == slot } ?: return
        if (!row.confirmable) return
        state.update { it.copy(confirmed = if (slot in it.confirmed) it.confirmed - slot else it.confirmed + slot) }
    }

    fun setRole(slot: String, role: ContributorRole?) {
        if (state.value.phase != ReviewPhase.READY) return
        state.update { it.copy(roles = if (role == null) it.roles - slot else it.roles + (slot to role)) }
    }

    fun send() {
        val current = state.value
        val review = current.review ?: return
        if (!current.canSend) return
        state.update { it.copy(phase = ReviewPhase.SENDING, failure = null) }
        viewModelScope.launch {
            // A revocation mid-send has already run the grant's state read (the reviewer does, outside the lock).
            val outcome = reviewer.send(id, current.confirmed, current.roles, review)
            state.update { it.sent(outcome) }
        }
    }

    fun delete() {
        if (state.value.phase != ReviewPhase.READY) return
        viewModelScope.launch {
            reviewer.delete(id)
            state.update { it.copy(phase = ReviewPhase.DELETED, review = null) }
        }
    }

    companion object {
        const val ARG_ID: String = "reviewId"
    }
}
