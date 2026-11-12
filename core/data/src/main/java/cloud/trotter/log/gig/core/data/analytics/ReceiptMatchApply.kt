package cloud.trotter.log.gig.core.data.analytics

import cloud.trotter.log.gig.core.database.analytics.AnalyticsDao
import cloud.trotter.log.gig.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.log.gig.core.database.analytics.ReceiptDao
import cloud.trotter.log.gig.core.database.analytics.ReceiptMatchDao
import cloud.trotter.log.gig.core.database.analytics.ReceiptMatchRowEntity
import cloud.trotter.log.gig.domain.analytics.ReceiptMatchFold
import cloud.trotter.log.gig.domain.analytics.ReceiptMatchUndoFold
import cloud.trotter.log.gig.domain.analytics.receipts.ReceiptMatch
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptMatchRole
import cloud.trotter.log.gig.domain.state.ReceiptScope
import timber.log.Timber

private const val TAG = "Projector"

/**
 * #1599: apply the dasher's receipt match and its undo inside the projector's batch transaction, in event order.
 *
 * All or nothing: every check runs before any write, so a match either writes every row it names or none. The
 * stale-preview check is structural, never by amount: at its log position the match applies only when every covered
 * row is still open to this receipt ([ReceiptMatch.openTo]: an estimate or no pay, no dasher edit, not covered by
 * another active match, not of a one-offer job another receipt's match already pays), the receipt's recorded total still equals the payload's, the receipt has no active match, its job is not a
 * one-offer job another receipt's match already pays ([ReceiptMatch.funded]), and the command id is new. The payload's prior amounts are a record of what the dasher saw, never a gate, so a later projector change
 * that re-prices estimates cannot drop the dasher's statement on refold. Only delivery rows sequenced before the match
 * take part (the #1517 batch-boundary rule), so incremental folding and a refold agree. Each refusal is a counted
 * skip with a WARN of ids only.
 */
internal class ReceiptMatchApply(
    private val analyticsDao: AnalyticsDao,
    private val receiptDao: ReceiptDao,
    private val matchDao: ReceiptMatchDao,
) {

    /** Returns true for a counted skip. */
    suspend fun applyMatch(fold: ReceiptMatchFold): Boolean {
        fun refuse(reason: String): Boolean {
            Timber.tag(TAG).w("RECEIPT_MATCHED %s refused: %s", fold.commandId, reason)
            return true
        }
        if (matchDao.commandRowCount(fold.commandId) > 0) return refuse("command already applied")
        val receipt = receiptDao.receipt(fold.evidenceId) ?: return refuse("receipt not found")
        if (receipt.sessionId != fold.sessionId) return refuse("receipt of another shift")
        if (receipt.scope != ReceiptScope.UNPROVEN.name) return refuse("receipt is proven")
        // Backstop: the projector writes receipt revisions in log order, so a later revision is never visible here.
        if (receipt.lastEventSequenceId > fold.sequenceId) return refuse("receipt revised after the match")
        if (receipt.totalCents != fold.receiptCents) return refuse("receipt total changed")
        val active = matchDao.activeRowsInSession(fold.sessionId).map { it.toActive() }
        if (active.any { it.evidenceId == fold.evidenceId }) return refuse("receipt already matched")

        val entities = analyticsDao.deliveryRecordsInSessionBefore(fold.sessionId, fold.sequenceId).associateBy { it.eventSequenceId }
        val rows = entities.values.map { it.toDomain() }
        val byId = rows.associateBy { it.eventSequenceId }
        val saved = receiptDao.receiptsInSession(fold.sessionId).map { it.toSaved() }
        if (ReceiptMatch.funded(receipt.jobId, fold.evidenceId, rows, active, saved)) return refuse("job already paid by another receipt")
        for ((seq, _) in fold.covered) {
            val row = byId[seq]
            if (row == null || !ReceiptMatch.openTo(fold.evidenceId, row, rows, active, saved)) return refuse("delivery $seq is not open to a match")
        }
        val coveredIds = fold.covered.map { it.first }.toSet()
        val cleared = ReceiptMatch.cleared(rows, coveredIds, active).map { it.eventSequenceId }
        if (cleared.toSet() != fold.previewCleared) {
            Timber.tag(TAG).w("RECEIPT_MATCHED %s: cleared %d, preview %d", fold.commandId, cleared.size, fold.previewCleared.size)
        }

        val sole = fold.covered.size == 1 && receipt.tipCents != null && receipt.basePayCents != null
        val soleTip = if (sole) receipt.tipCents!! / 100.0 else null
        val soleBase = if (sole) receipt.basePayCents!! / 100.0 else null
        val matchRows = ArrayList<ReceiptMatchRowEntity>(fold.covered.size + cleared.size)
        for ((seq, cents) in fold.covered) {
            val prior = entities.getValue(seq)
            analyticsDao.upsertDelivery(DeliveryCorrections.applyReceiptShare(prior, cents, soleTip, soleBase, fold.matchedAt))
            matchRows += matchRow(fold, prior, ReceiptMatchRole.COVERED, cents)
        }
        for (seq in cleared) {
            val prior = entities.getValue(seq)
            analyticsDao.upsertDelivery(DeliveryCorrections.clearEstimate(prior))
            matchRows += matchRow(fold, prior, ReceiptMatchRole.CLEARED, null)
        }
        matchDao.insertAll(matchRows)
        Timber.tag(TAG).d(
            "RECEIPT_MATCHED %s: %d covered, %d cleared, %d¢", fold.commandId, fold.covered.size, cleared.size, fold.receiptCents,
        )
        return false
    }

    /** Returns true for a counted skip. Restores each row only while every row still holds what the match wrote. */
    suspend fun applyUndo(fold: ReceiptMatchUndoFold): Boolean {
        fun refuse(reason: String): Boolean {
            Timber.tag(TAG).w("RECEIPT_MATCH_UNDONE %s refused: %s", fold.matchCommandId, reason)
            return true
        }
        val matchRows = matchDao.activeRowsForCommand(fold.matchCommandId)
        if (matchRows.isEmpty()) return refuse("no active match")
        if (matchRows.any { it.sessionId != fold.sessionId }) return refuse("match of another shift")
        val deliveries = matchRows.map { analyticsDao.deliveryRecord(it.deliveryEventSequenceId) }
        matchRows.zip(deliveries).forEach { (m, d) ->
            if (!ReceiptMatch.holdsWritten(m.toActive(), d?.toDomain())) {
                return refuse("delivery ${m.deliveryEventSequenceId} changed since the match")
            }
        }
        matchRows.zip(deliveries).forEach { (m, d) -> analyticsDao.upsertDelivery(DeliveryCorrections.restoreBeforeMatch(d!!, m)) }
        matchDao.markUndone(fold.matchCommandId, fold.undoneAt, fold.sequenceId)
        Timber.tag(TAG).d("RECEIPT_MATCH_UNDONE %s: %d rows restored", fold.matchCommandId, matchRows.size)
        return false
    }

    private fun matchRow(
        fold: ReceiptMatchFold,
        prior: DeliveryRecordEntity,
        role: ReceiptMatchRole,
        newPayCents: Long?,
    ) = ReceiptMatchRowEntity(
        commandId = fold.commandId,
        deliveryEventSequenceId = prior.eventSequenceId,
        sessionId = fold.sessionId,
        evidenceId = fold.evidenceId,
        role = role.name,
        matchEventSequenceId = fold.sequenceId,
        matchedAt = fold.matchedAt,
        priorRealizedPay = prior.realizedPay,
        priorPayBasis = prior.payBasis,
        priorNetProfit = prior.netProfit,
        priorTip = prior.tip,
        priorBasePay = prior.basePay,
        priorDriverAdjustedAt = prior.driverAdjustedAt,
        newPayCents = newPayCents,
    )
}
