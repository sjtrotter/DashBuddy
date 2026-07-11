package cloud.trotter.dashbuddy.core.data.analytics

import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.domain.model.event.AppEvent
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryAdjustmentPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliverySessionAssignPayload
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
     *
     * Validation (#705 F4b — parity with [adjustDelivery], which this method previously lacked
     * entirely): every money/miles value must be **finite** (a non-finite value would reach
     * `AppEventCodec` and throw a `SerializationException`), pay finite > 0, tip/cash/miles finite ≥ 0.
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
        require(pay.isFinite() && pay > 0.0) { "pay must be a finite positive amount" }
        require(tip == null || (tip.isFinite() && tip >= 0.0)) { "tip must be finite and non-negative" }
        require(cashTip == null || (cashTip.isFinite() && cashTip >= 0.0)) { "cashTip must be finite and non-negative" }
        require(miles == null || (miles.isFinite() && miles >= 0.0)) { "miles must be finite and non-negative" }
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
        // Every money/miles value must be FINITE (#705 F4b): a non-finite value (e.g. a pasted `1e999`
        // → `Infinity`) would reach `AppEventCodec` and throw a `SerializationException`.
        require(newPay == null || (newPay.isFinite() && newPay > 0.0)) { "newPay must be a finite positive amount" }
        require(newTip == null || (newTip.isFinite() && newTip >= 0.0)) { "newTip must be finite and non-negative" }
        require(newCashTip == null || (newCashTip.isFinite() && newCashTip >= 0.0)) { "newCashTip must be finite and non-negative" }
        require(newMiles == null || (newMiles.isFinite() && newMiles >= 0.0)) { "newMiles must be finite and non-negative" }
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

    /**
     * Append a driver session-(re)attribution of an orphan "(No session)" delivery (#660 piece 2).
     * [targetEventSequenceId] is the PK of the target `delivery_record`; [newSessionId] is the dash to
     * assign it to, or **null to UNASSIGN** it back to the "(No session)" bucket (the undo). The
     * envelope `sessionId` = [newSessionId] (attribution/liveness only, the corrections convention). The
     * projector applies the re-attribution — with its fail-closed guards (movable rows only, real ended
     * target session, platform coherence, cash-bearing-unassign block) — on its next drain; the original
     * event stays, and it re-prices NOTHING (attribution only).
     *
     * Validation is deliberately minimal (the projector guards are the authority, and the repository
     * cannot see the read model, per the existing read/write split): a non-null [newSessionId] must be
     * non-blank (a blank id would fold to a dangling attribution).
     */
    suspend fun assignDeliverySession(
        targetEventSequenceId: Long,
        newSessionId: String?,
        note: String? = null,
    ) {
        require(newSessionId == null || newSessionId.isNotBlank()) { "newSessionId must be non-blank when assigning" }
        appEventRepo.appendUserEvent(
            AppEvent(
                type = AppEventType.DELIVERY_SESSION_ASSIGN,
                occurredAt = System.currentTimeMillis(),
                sessionId = newSessionId,
                payload = DeliverySessionAssignPayload(
                    targetEventSequenceId = targetEventSequenceId,
                    newSessionId = newSessionId,
                    note = note,
                ),
            ),
        )
        // P7: no note text — counts/ids only.
        Timber.tag(TAG).i(
            "DELIVERY_SESSION_ASSIGN appended for delivery seq %d (assigned=%b)",
            targetEventSequenceId, newSessionId != null,
        )
    }

    private companion object {
        private const val TAG = "Corrections"
    }
}
