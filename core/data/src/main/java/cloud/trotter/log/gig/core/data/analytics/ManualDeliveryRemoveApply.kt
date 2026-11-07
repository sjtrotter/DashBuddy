package cloud.trotter.log.gig.core.data.analytics

import cloud.trotter.log.gig.core.database.analytics.AnalyticsDao
import cloud.trotter.log.gig.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.log.gig.domain.analytics.ManualDeliveryRemoveFold
import cloud.trotter.log.gig.domain.analytics.PayBasis
import timber.log.Timber

private const val TAG = "Projector"

/**
 * #1517: delete a removed hand-added delivery's row inside the batch transaction, in event order (the
 * `LedgerApply.applyLedgerVoid` shape). The fold already lowered the shift's counts when it still held the delivery;
 * the row's `sourceOfferSeq` link goes with it, so its offer is open again. Returns the deleted row (its store key
 * may need a sweep), or null for a counted skip with a WARN of ids only: a missing or already removed row, a row
 * that is not a hand-added delivery of that shift.
 */
internal suspend fun AnalyticsDao.applyManualDeliveryRemove(fold: ManualDeliveryRemoveFold): DeliveryRecordEntity? {
    val seq = fold.targetEventSequenceId
    fun refuse(reason: String): DeliveryRecordEntity? {
        Timber.tag(TAG).w("MANUAL_DELIVERY_REMOVED: delivery %d %s, skipped", seq, reason)
        return null
    }
    val row = deliveryRecord(seq) ?: return refuse("not found or already removed")
    if (!fold.inShift || row.originalPayBasis != PayBasis.MANUAL || row.sessionId != fold.sessionId) {
        return refuse("is not a hand-added delivery of shift ${fold.sessionId}")
    }
    deleteDeliveryRecord(seq)
    return row
}
