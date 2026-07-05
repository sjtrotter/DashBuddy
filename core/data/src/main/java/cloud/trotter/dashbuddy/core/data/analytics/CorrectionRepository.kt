package cloud.trotter.dashbuddy.core.data.analytics

import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.domain.model.event.AppEvent
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.ManualDeliveryPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.PayAdjustmentPayload
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Write-side entry point for driver corrections (#650) — appends `MANUAL_DELIVERY` / `PAY_ADJUSTMENT`
 * events to the durable `app_events` log. Deliberately SEPARATE from the read-side [AnalyticsRepository]
 * (Principle 3 — single responsibility: one repository reads the projected records, another writes the
 * correction events; they never share a code path). Corrections are **events, never destructive edits**:
 * the original capture and its provenance stay in the log; the projector folds the correction on its
 * next `maxSequenceId()` drain and Room invalidation refreshes any reactive UI — there is no manual
 * projector nudge, and no optimistic local mutation.
 *
 * Privacy (P7): the INFO milestones log counts / sequence ids / a `sha256`-free session id only — never
 * the driver-authored note or store text (those are driver-owned local data).
 */
@Singleton
class CorrectionRepository @Inject constructor(
    private val appEventRepo: AppEventRepo,
) {

    /**
     * Append a driver-entered missed delivery (#650). [pay] is required; [tip]/[storeName]/[miles]/[note]
     * are optional driver knowledge. [completedAt] is when the delivery happened (the caller picks an
     * honest default, e.g. the session's end/start). The projector folds it into a `delivery_record`
     * (payBasis `MANUAL`) on its next drain.
     */
    suspend fun addManualDelivery(
        sessionId: String,
        storeName: String?,
        pay: Double,
        tip: Double?,
        completedAt: Long,
        miles: Double?,
        note: String?,
    ) {
        appEventRepo.appendUserEvent(
            AppEvent(
                type = AppEventType.MANUAL_DELIVERY,
                occurredAt = System.currentTimeMillis(),
                sessionId = sessionId,
                payload = ManualDeliveryPayload(
                    sessionId = sessionId,
                    storeName = storeName,
                    pay = pay,
                    tip = tip,
                    completedAt = completedAt,
                    miles = miles,
                    note = note,
                ),
            ),
        )
        // P7: no note/store text — counts/ids only.
        Timber.tag(TAG).i("MANUAL_DELIVERY appended for session %s", sessionId)
    }

    /**
     * Append a driver re-price of an already-recorded delivery (#650). [targetEventSequenceId] is the
     * PK of the `delivery_record` to re-price; [sessionId] is for attribution/liveness only. The
     * projector rewrites the target row's realizedPay to [newPay] (payBasis → `USER_CORRECTED`, net
     * recomputed against the row's own frozen cost basis) on its next drain — the original event stays.
     */
    suspend fun adjustDeliveryPay(
        targetEventSequenceId: Long,
        sessionId: String?,
        newPay: Double,
        note: String?,
    ) {
        appEventRepo.appendUserEvent(
            AppEvent(
                type = AppEventType.PAY_ADJUSTMENT,
                occurredAt = System.currentTimeMillis(),
                sessionId = sessionId,
                payload = PayAdjustmentPayload(
                    targetEventSequenceId = targetEventSequenceId,
                    sessionId = sessionId,
                    newPay = newPay,
                    note = note,
                ),
            ),
        )
        // P7: no note text — counts/ids only.
        Timber.tag(TAG).i("PAY_ADJUSTMENT appended for delivery seq %d", targetEventSequenceId)
    }

    private companion object {
        private const val TAG = "Corrections"
    }
}
