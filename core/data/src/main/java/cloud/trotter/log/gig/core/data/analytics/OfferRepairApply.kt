package cloud.trotter.log.gig.core.data.analytics

import cloud.trotter.log.gig.core.database.analytics.AnalyticsDao
import cloud.trotter.log.gig.domain.analytics.ManualSourceLinkFold
import cloud.trotter.log.gig.domain.analytics.OfferUnassignFold
import cloud.trotter.log.gig.domain.model.event.AppEventType
import timber.log.Timber

private const val TAG = "Projector"

/**
 * #1517: stamp a recorded unassign on the shift's accepted offers its job named, inside the batch transaction in
 * event order. Only offers sequenced before the unassign and not yet stamped move, so a refold stamps the same rows.
 * Never a skip: an unassign that names no offer row of the shift is a valid no-op. Ids and counts only in the log.
 */
internal suspend fun AnalyticsDao.applyOfferUnassign(fold: OfferUnassignFold): Boolean {
    val stamped = stampOfferUnassigned(
        fold.sessionId, fold.offerHashes, AppEventType.OFFER_ACCEPTED.name, fold.beforeSequenceId, fold.unassignedAt,
    )
    Timber.tag(TAG).d("TASK_UNASSIGNED seq %d: %d accepted offer(s) stamped", fold.beforeSequenceId, stamped)
    return false
}

/**
 * #1517: store the accepted offer a missed delivery was added from on the delivery row, after the guards. Returns
 * true for a counted skip (WARN with ids only); the delivery row itself stays, without the link. Refused: a missing
 * row, an offer that is not accepted, is resolved or unassigned, belongs to another shift, already has a delivery
 * naming its hash, or already has a missed delivery. The evidence is the shift's delivery rows sequenced before this
 * one, so an incremental fold and a refold decide alike; `linkedJobId` is not read, because store resolution stamps
 * it after the batch's corrections.
 */
internal suspend fun AnalyticsDao.applyManualSourceLink(fold: ManualSourceLinkFold): Boolean {
    val delivery = fold.deliverySequenceId
    val offerSeq = fold.offerSequenceId
    fun refuse(reason: String): Boolean {
        Timber.tag(TAG).w("MANUAL_DELIVERY %d: source offer %d %s, link refused", delivery, offerSeq, reason)
        return true
    }
    val offer = offerRecord(offerSeq) ?: return refuse("not found")
    if (deliveryRecord(delivery) == null) return refuse("has no delivery row to link")
    if (offer.outcome != AppEventType.OFFER_ACCEPTED.name) return refuse("is not accepted")
    if (offer.outcomeResolved != null) return refuse("is already resolved")
    if (offer.unassignedAt != null) return refuse("has a recorded unassign")
    if (offer.sessionId != fold.sessionId) return refuse("belongs to another shift")
    val earlier = deliveryRecordsInSessionBefore(fold.sessionId, delivery)
    if (earlier.any { it.sourceOfferSeq == offerSeq }) return refuse("already has a missed delivery")
    // The timeline's own hash rule (TimelineDelivery.offerHashes): the job's set, else the sole hash on an older row.
    if (earlier.any { offer.offerHash in it.toTimeline().offerHashes }) return refuse("already has a delivery")
    setDeliverySourceOffer(delivery, offerSeq)
    return false
}
