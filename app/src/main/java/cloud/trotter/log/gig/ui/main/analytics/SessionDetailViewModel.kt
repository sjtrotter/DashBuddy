package cloud.trotter.log.gig.ui.main.analytics

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.gig.core.data.analytics.AnalyticsRepository
import cloud.trotter.log.gig.core.data.analytics.CorrectionRepository
import cloud.trotter.log.gig.core.data.analytics.ReceiptMatchRepository
import cloud.trotter.log.gig.core.data.analytics.RecordsStatusRepository
import cloud.trotter.log.gig.core.data.analytics.ShiftTimelineRepository
import cloud.trotter.log.gig.domain.analytics.checks.ShiftChecks
import cloud.trotter.log.gig.domain.analytics.receipts.ReceiptMatchPlan
import cloud.trotter.log.gig.domain.analytics.receipts.ShiftReceipts
import cloud.trotter.log.gig.ui.main.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * State holder for the shift page: one immutable [SessionDetailUiState] assembled reactively from the shift's
 * record and deliveries ([AnalyticsRepository.sessionDetail]), its folded timeline with the offers and deliveries
 * it came from ([ShiftTimelineRepository.read], the store search's names) and the records status (Principle 1, state
 * out), plus the correction intents (events up). The sessionId and the optional landing row are nav arguments read from [SavedStateHandle].
 *
 * Reactive by construction: every source is a Room-invalidation `Flow`, so a correction or a newly folded
 * delivery re-emits the page with no refresh. The corrections are **events, never destructive edits**: an intent
 * appends to the log via [CorrectionRepository]; the projector folds it and Room invalidation refreshes this
 * page; there is no optimistic local mutation. A failed read becomes [SessionDetailUiState.failed] (one WARN,
 * the failure class only) and [retry] re-subscribes. Read-model only: store names, no customer data.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val analyticsRepository: AnalyticsRepository,
    private val shiftTimelineRepository: ShiftTimelineRepository,
    private val recordsStatusRepository: RecordsStatusRepository,
    private val correctionRepository: CorrectionRepository,
    private val receiptMatchRepository: ReceiptMatchRepository,
    private val clock: Clock,
) : ViewModel() {

    private val sessionId: String =
        savedStateHandle[Screen.SessionDetail.ARG_SESSION_ID] ?: ""

    /** The timeline row a link asked to land on, or null. */
    val landingRow: String? = savedStateHandle.get<String>(Screen.SessionDetail.ARG_ROW)?.takeIf { it.isNotBlank() }

    /** Bumped by [retry]; every bump re-subscribes the page's reads. */
    private val attempts = MutableStateFlow(0)

    /** UDF intent: the failure pattern's Retry. */
    fun retry() {
        attempts.value++
    }

    val uiState: StateFlow<SessionDetailUiState> = attempts.flatMapLatest { attempt ->
        pageState()
            .onStart { if (attempt > 0) emit(SessionDetailUiState()) }
            .catch { e ->
                if (e is CancellationException) throw e
                // P7: the failure class only, never record contents.
                Timber.tag(TAG).w(e, "shift page read failed")
                emit(SessionDetailUiState(loading = false, failed = true))
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SessionDetailUiState())

    /** The timeline is folded as running while the shift has no end; it re-folds when the shift ends. */
    private fun pageState(): Flow<SessionDetailUiState> {
        val detail = analyticsRepository.sessionDetail(sessionId)
        val timeline = detail.map { it?.session?.endedAt == null }
            .distinctUntilChanged()
            .flatMapLatest { running -> shiftTimelineRepository.read(sessionId, running) }
        return combine(detail, timeline, recordsStatusRepository.status, receiptMatchRepository.read(sessionId)) { d, t, records, r ->
            val receipts = d?.let { ShiftReceipts.of(r.receipts, it.deliveries, r.active) } ?: ShiftReceipts.EMPTY
            val checks = d?.let { ShiftChecks.of(it, t.items, receipts) }.orEmpty()
            SessionDetailUiState(
                detail = d, timeline = t.items, loading = false, records = records, checks = checks,
                offers = t.offers, deliveries = t.deliveries, receipts = receipts,
            )
        }
    }

    /**
     * Append a driver-entered missed delivery for this dash (#650). `completedAt` defaults to the
     * loaded session's `endedAt ?: startedAt` — the simplest honest v1 default for when the drop
     * happened; miles are v1-null (not asked for). The projector folds it (payBasis `MANUAL`) and the
     * read-model Flow refreshes the screen.
     */
    fun addManualDelivery(pay: Double, tip: Double?, cashTip: Double?, storeName: String?, note: String?) {
        val session = uiState.value.detail?.session
        val completedAt = session?.endedAt ?: session?.startedAt ?: clock.millis()
        appendManualDelivery(completedAt, pay, tip, cashTip, storeName, note, fromOffer = null)
    }

    /**
     * "Add a missed delivery" from an accepted offer with no delivery: the same append, at the offer's time, naming
     * the offer. The projector stores the link after its guards and the offer's card then shows this delivery.
     */
    fun addMissedDeliveryForOffer(
        offerEventSequenceId: Long,
        completedAt: Long,
        pay: Double,
        tip: Double?,
        cashTip: Double?,
        storeName: String?,
        note: String?,
    ) = appendManualDelivery(completedAt, pay, tip, cashTip, storeName, note, fromOffer = offerEventSequenceId)

    private fun appendManualDelivery(
        completedAt: Long,
        pay: Double,
        tip: Double?,
        cashTip: Double?,
        storeName: String?,
        note: String?,
        fromOffer: Long?,
    ) {
        viewModelScope.launch {
            // F4c: never crash the launch on a rejected correction (a validation `require` throwing, a
            // codec/write failure). Alpha-acceptable silent reject — the record simply doesn't append;
            // the dialog's own validators are the primary guard, this is the fail-closed backstop.
            try {
                correctionRepository.addManualDelivery(
                    sessionId = sessionId,
                    storeName = storeName,
                    pay = pay,
                    tip = tip,
                    cashTip = cashTip,
                    completedAt = completedAt,
                    miles = null,
                    note = note,
                    fromOfferEventSequenceId = fromOffer,
                )
            } catch (e: CancellationException) {
                throw e // cooperative cancellation — never swallow it
            } catch (e: Exception) {
                // P7: counts/ids only — no note/store text.
                Timber.tag(TAG).w(e, "addManualDelivery rejected for session %s", sessionId)
            }
        }
    }

    /**
     * Append a driver multi-field edit of a recorded delivery (#688). Only the changed fields are
     * non-null; the projector re-applies the target row (payBasis → `USER_CORRECTED` only when pay
     * changes, net recomputed against its own frozen cpm) and the read-model Flow refreshes the screen.
     */
    fun adjustDelivery(
        targetEventSequenceId: Long,
        newStoreName: String?,
        newPay: Double?,
        newTip: Double?,
        newCashTip: Double?,
        newMiles: Double?,
        note: String?,
    ) {
        viewModelScope.launch {
            // F4c: fail-closed backstop — a rejected adjustment must not crash the launch (see
            // addManualDelivery). Alpha-acceptable silent reject; the dialog validators are primary.
            try {
                correctionRepository.adjustDelivery(
                    targetEventSequenceId = targetEventSequenceId,
                    sessionId = sessionId,
                    newStoreName = newStoreName,
                    newPay = newPay,
                    newTip = newTip,
                    newCashTip = newCashTip,
                    newMiles = newMiles,
                    note = note,
                )
            } catch (e: CancellationException) {
                throw e // cooperative cancellation — never swallow it
            } catch (e: Exception) {
                // P7: counts/ids only — no note/store text.
                Timber.tag(TAG).w(e, "adjustDelivery rejected for delivery seq %d", targetEventSequenceId)
            }
        }
    }

    /**
     * Remove a driver-assigned delivery from this dash (#660 piece 2, the undo) — writes a
     * `DELIVERY_SESSION_ASSIGN` with `newSessionId = null`, returning the row to the "(No session)"
     * bucket where it can be re-assigned correctly. Only offered for a `sessionAssigned` row (a machine
     * row is never movable by the projector's guard). The projector applies it and the read-model Flow
     * refreshes the screen (the row leaves this dash) — no optimistic local mutation.
     */
    fun unassignDelivery(targetEventSequenceId: Long) {
        viewModelScope.launch {
            // F4c: fail-closed backstop — a rejected unassign (e.g. the cash-bearing block) must not
            // crash the launch. Alpha-acceptable silent reject; the projector guard is the authority.
            try {
                correctionRepository.assignDeliverySession(
                    targetEventSequenceId = targetEventSequenceId,
                    newSessionId = null,
                )
            } catch (e: CancellationException) {
                throw e // cooperative cancellation — never swallow it
            } catch (e: Exception) {
                // P7: counts/ids only.
                Timber.tag(TAG).w(e, "unassignDelivery rejected for delivery seq %d", targetEventSequenceId)
            }
        }
    }

    /** "It was unassigned": the dasher's attestation for an accepted offer with no delivery (`OFFER_OUTCOME_CORRECTION`). */
    fun markOfferUnassigned(offerEventSequenceId: Long) {
        viewModelScope.launch {
            try {
                correctionRepository.correctOfferOutcome(offerEventSequenceId, attested = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // P7: ids only.
                Timber.tag(TAG).w(e, "markOfferUnassigned rejected for offer seq %d", offerEventSequenceId)
            }
        }
    }

    /** Undo "It was unassigned": the documented undo shape, `OFFER_OUTCOME_CORRECTION` with no resolution. */
    fun undoOfferUnassigned(offerEventSequenceId: Long) {
        viewModelScope.launch {
            try {
                correctionRepository.correctOfferOutcome(offerEventSequenceId, attested = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // P7: ids only.
                Timber.tag(TAG).w(e, "undoOfferUnassigned rejected for offer seq %d", offerEventSequenceId)
            }
        }
    }

    /** Remove a delivery the dasher added by hand (`MANUAL_DELIVERY_REMOVED`); the projector deletes its row. */
    fun removeManualDelivery(deliveryEventSequenceId: Long) {
        viewModelScope.launch {
            try {
                correctionRepository.removeManualDelivery(deliveryEventSequenceId, sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // P7: ids only.
                Timber.tag(TAG).w(e, "removeManualDelivery rejected for delivery seq %d", deliveryEventSequenceId)
            }
        }
    }

    fun correctSessionReport(operation: String, value: Double?, note: String?) {
        viewModelScope.launch {
            try {
                correctionRepository.correctSessionReport(sessionId, operation, value, note)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No exception text: a failed write must not disclose the driver's note.
                Timber.tag(TAG).w("correctSessionReport rejected for session %s", sessionId)
            }
        }
    }

    /** #1535: state this shift's miles ([miles] non-null) or restore the tracked miles (null). */
    fun correctShiftMiles(miles: Double?) {
        viewModelScope.launch {
            try {
                if (miles != null) correctionRepository.setShiftMiles(sessionId, miles) else correctionRepository.restoreShiftMiles(sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w("correctShiftMiles rejected for session %s", sessionId)
            }
        }
    }

    /** #1599: the dasher's Confirm on the receipt sheet: the plan exactly as the sheet showed it. */
    fun confirmReceiptMatch(commandId: String, plan: ReceiptMatchPlan) {
        viewModelScope.launch {
            try {
                correctionRepository.matchReceipt(sessionId, commandId, plan)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // P7: ids only.
                Timber.tag(TAG).w(e, "confirmReceiptMatch rejected for receipt %s", plan.evidenceId)
            }
        }
    }

    /** #1599: Undo match on the job sheet. */
    fun undoReceiptMatch(matchCommandId: String) {
        viewModelScope.launch {
            try {
                correctionRepository.undoReceiptMatch(sessionId, matchCommandId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // P7: ids only.
                Timber.tag(TAG).w(e, "undoReceiptMatch rejected for match %s", matchCommandId)
            }
        }
    }

    private companion object {
        private const val TAG = "SessionDetailVm"
    }
}
