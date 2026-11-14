package cloud.trotter.log.gig.feature.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.census.contract.contributor.ContributorRole
import cloud.trotter.census.contract.contributor.QuarantineNote
import cloud.trotter.log.gig.core.data.census.contributor.AskOutcome
import cloud.trotter.log.gig.core.data.census.contributor.AskRefusal
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

/** #1626: where the review screen is. ASKING and ASKED are #1627's "Ask the developer". */
enum class ReviewPhase { LOADING, READY, SENDING, ASKING, SENT, ASKED, DELETED, NOT_ALLOWED, NOT_FOUND, NOT_READY }

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
    /** #1627: the last Ask the developer that sent nothing. */
    val askFailure: AskRefusal? = null,
    /** #1627: after Ask the developer: the UTC day the server deletes the item by. */
    val askedExpiresDay: String? = null,
    /** #1627: the server's hash of what it sealed did not match the phone's. */
    val askUnconfirmed: Boolean = false,
    val shiftLive: Boolean = false,
) {
    val canSend: Boolean get() = phase == ReviewPhase.READY && (confirmed.isNotEmpty() || roles.isNotEmpty())

    /** #1627: Ask the developer needs no choice; the outline goes with whatever was chosen. */
    val canAsk: Boolean get() = phase == ReviewPhase.READY && review != null
}

/**
 * #1626 (Sol S6): the grant stopped standing (it ended, expired, or the census went off): the rows, their words and
 * the dasher's choices go, and the screen offers nothing until it is opened again. Sent and Deleted keep their message.
 */
internal fun ReviewUiState.grantEnded(): ReviewUiState = when (phase) {
    ReviewPhase.SENT, ReviewPhase.ASKED, ReviewPhase.DELETED -> copy(review = null, confirmed = emptySet(), roles = emptyMap())
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

/** #1627: an Ask the developer's outcome, unless the grant ended while it ran. */
internal fun ReviewUiState.asked(outcome: AskOutcome): ReviewUiState {
    if (phase != ReviewPhase.ASKING) return this
    fun done(expiresDay: String, unconfirmed: Boolean) = copy(
        phase = ReviewPhase.ASKED, review = null, confirmed = emptySet(), roles = emptyMap(),
        askedExpiresDay = expiresDay, askUnconfirmed = unconfirmed,
    )
    return when (outcome) {
        is AskOutcome.Sent -> done(outcome.expiresDay, unconfirmed = false)
        is AskOutcome.Unconfirmed -> done(outcome.expiresDay, unconfirmed = true)
        is AskOutcome.NotSent -> when (outcome.reason) {
            AskRefusal.NOT_FOUND -> copy(phase = ReviewPhase.NOT_FOUND, review = null)
            AskRefusal.NOT_ALLOWED, AskRefusal.INSTALL_REVOKED -> grantEnded()
            else -> copy(phase = ReviewPhase.READY, askFailure = outcome.reason)
        }
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
        state.update { it.copy(phase = ReviewPhase.SENDING, failure = null, askFailure = null) }
        viewModelScope.launch {
            // A revocation mid-send has already run the grant's state read (the reviewer does, outside the lock).
            val outcome = reviewer.send(id, current.confirmed, current.roles, review)
            state.update { it.sent(outcome) }
        }
    }

    /**
     * #1627: Ask the developer, with the dasher's choices so far and [note]. The grant's state is read first, outside
     * the upload lock, as before a review session.
     */
    fun ask(note: QuarantineNote?) {
        val current = state.value
        val review = current.review ?: return
        if (!current.canAsk) return
        state.update { it.copy(phase = ReviewPhase.ASKING, failure = null, askFailure = null) }
        viewModelScope.launch {
            contributor.refresh()
            val outcome = reviewer.ask(id, current.confirmed, current.roles, note, review)
            state.update { it.asked(outcome) }
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
