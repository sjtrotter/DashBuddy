package cloud.trotter.dashbuddy.core.data.analytics

import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.domain.model.event.AppEvent
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryAdjustmentPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.ManualDeliveryPayload
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Write-side entry point for driver corrections (#650/#688) — appends `MANUAL_DELIVERY` /
 * `DELIVERY_ADJUSTMENT` events to the durable `app_events` log. (The legacy `PAY_ADJUSTMENT` event
 * stays fully readable by the fold/projector for history, but the new UI writes only the widened
 * `DELIVERY_ADJUSTMENT`.) Deliberately SEPARATE from the read-side [AnalyticsRepository]
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
        cashTip: Double?,
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
                    cashTip = cashTip,
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
     * Append a driver multi-field edit of an already-recorded delivery (#688) — the widened
     * correction. [targetEventSequenceId] is the PK of the target `delivery_record`; [sessionId] is
     * for attribution/liveness only. Every new-value field is optional (null ⇒ that column is left
     * unchanged); the projector re-applies the target row on its next drain (payBasis → `USER_CORRECTED`
     * only when [newPay] changes, per the never-silent disclosure rule; net recomputed against the row's
     * own frozen cost basis) — the original event stays.
     *
     * Validation: at least one new-value field OR [note] must be non-null (a note-only annotation is
     * valid, #688 F6); money is > 0 where present, tip/cash ≥ 0, miles ≥ 0.
     */
    suspend fun adjustDelivery(
        targetEventSequenceId: Long,
        sessionId: String?,
        newStoreName: String? = null,
        newPay: Double? = null,
        newTip: Double? = null,
        newCashTip: Double? = null,
        newMiles: Double? = null,
        note: String? = null,
    ) {
        require(
            newStoreName != null || newPay != null || newTip != null ||
                newCashTip != null || newMiles != null || note != null,
        ) { "DELIVERY_ADJUSTMENT must change at least one field or carry a note" }
        require(newPay == null || newPay > 0.0) { "newPay must be positive" }
        require(newTip == null || newTip >= 0.0) { "newTip must be non-negative" }
        require(newCashTip == null || newCashTip >= 0.0) { "newCashTip must be non-negative" }
        require(newMiles == null || newMiles >= 0.0) { "newMiles must be non-negative" }
        appEventRepo.appendUserEvent(
            AppEvent(
                type = AppEventType.DELIVERY_ADJUSTMENT,
                occurredAt = System.currentTimeMillis(),
                sessionId = sessionId,
                payload = DeliveryAdjustmentPayload(
                    targetEventSequenceId = targetEventSequenceId,
                    sessionId = sessionId,
                    newStoreName = newStoreName,
                    newPay = newPay,
                    newTip = newTip,
                    newCashTip = newCashTip,
                    newMiles = newMiles,
                    note = note,
                ),
            ),
        )
        // P7: no note/store text — counts/ids only.
        Timber.tag(TAG).i("DELIVERY_ADJUSTMENT appended for delivery seq %d", targetEventSequenceId)
    }

    private companion object {
        private const val TAG = "Corrections"
    }
}
