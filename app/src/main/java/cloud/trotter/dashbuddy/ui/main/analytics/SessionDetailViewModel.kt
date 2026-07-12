package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository
import cloud.trotter.dashbuddy.core.data.analytics.CorrectionRepository
import cloud.trotter.dashbuddy.domain.analytics.SessionDetail
import cloud.trotter.dashbuddy.ui.main.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * State holder for the per-dash drill-down (#650) — one immutable [SessionDetailUiState] assembled
 * reactively from [AnalyticsRepository.sessionDetail] (Principle 1 — UDF, state out), plus the two
 * correction intents ([addManualDelivery] / [adjustDelivery], events up). The sessionId is read from
 * [SavedStateHandle] (the nav argument), so this VM needs no explicit initializer.
 *
 * Reactive by construction: the source is a Room-invalidation `Flow`, so the header + rows re-emit
 * as the projector folds each delivery — a **review** surface, not a per-second tick (no
 * `rememberNow()` clock; same reframe as the hub #657). The corrections are **events, never
 * destructive edits**: an intent appends a `MANUAL_DELIVERY`/`DELIVERY_ADJUSTMENT` to the log via
 * [CorrectionRepository]; the projector folds it and Room invalidation refreshes this screen — there
 * is NO optimistic local mutation. Read-model only, no economy dependency, no new PII surface (store
 * names are driver-owned; no customer hashes are exposed).
 */
@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    analyticsRepository: AnalyticsRepository,
    private val correctionRepository: CorrectionRepository,
) : ViewModel() {

    private val sessionId: String =
        savedStateHandle[Screen.SessionDetail.ARG_SESSION_ID] ?: ""

    val uiState: StateFlow<SessionDetailUiState> =
        analyticsRepository.sessionDetail(sessionId)
            .map { SessionDetailUiState(detail = it, loading = false) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                SessionDetailUiState(),
            )

    /**
     * Append a driver-entered missed delivery for this dash (#650). `completedAt` defaults to the
     * loaded session's `endedAt ?: startedAt` — the simplest honest v1 default for when the drop
     * happened; miles are v1-null (not asked for). The projector folds it (payBasis `MANUAL`) and the
     * read-model Flow refreshes the screen.
     */
    fun addManualDelivery(pay: Double, tip: Double?, cashTip: Double?, storeName: String?, note: String?) {
        val session = uiState.value.detail?.session
        val completedAt = session?.endedAt ?: session?.startedAt ?: System.currentTimeMillis()
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

    private companion object {
        private const val TAG = "SessionDetailVm"
    }
}

/**
 * Immutable state for the per-dash drill-down (#650 PR A). [loading] is the pre-first-emission
 * state; once the read-model emits, a `null` [detail] means the session row is absent (dash not
 * found / projector rebuild) and a present one is the full [SessionDetail].
 */
data class SessionDetailUiState(
    val detail: SessionDetail? = null,
    val loading: Boolean = true,
)
