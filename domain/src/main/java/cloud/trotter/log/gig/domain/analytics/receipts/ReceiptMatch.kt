package cloud.trotter.log.gig.domain.analytics.receipts

import cloud.trotter.log.gig.domain.analytics.DeliveryRecord
import cloud.trotter.log.gig.domain.analytics.PayBasis
import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptMatchRole
import cloud.trotter.log.gig.domain.state.DropPayApportioner

/** A saved receipt (#1598's `receipt_records` row) as the matching rules read it. */
data class SavedReceipt(
    val evidenceId: String,
    val jobId: String,
    val subjectTaskId: String,
    val firstSeenAt: Long,
    val totalCents: Long,
    val basePayCents: Long?,
    val tipCents: Long?,
    val proven: Boolean,
)

/** One delivery row an active (not undone) match wrote. */
data class ActiveMatchRow(
    val commandId: String,
    val evidenceId: String,
    val deliveryEventSequenceId: Long,
    val role: ReceiptMatchRole,
    val newPayCents: Long?,
    val matchedAt: Long,
)

/** Why a delivery cannot take a receipt. */
sealed interface MatchLock {
    /** Its pay came from a receipt the app placed (`PayBasis.RECEIPT_EVIDENCE`). */
    data object PaidByReceipt : MatchLock

    /** An active match covers it. */
    data class MatchedTo(val evidenceId: String) : MatchLock

    /** MANUAL, USER_CORRECTED, a dasher's money edit (`driverAdjustedAt`) or moved into the shift by the dasher. */
    data object ChangedByYou : MatchLock
}

/** One row of the preview. */
data class PlannedRow(
    val deliveryEventSequenceId: Long,
    val priorPayCents: Long?,
    val priorPayBasis: String,
    val newPayCents: Long?,
)

/** What Confirm saves, exactly as the sheet shows it. */
data class ReceiptMatchPlan(
    val evidenceId: String,
    val receiptCents: Long,
    val covered: List<PlannedRow>,
    val cleared: List<PlannedRow>,
    /** Σ the shift's delivery pay in cents now and after the match (null pay counts 0, as `SessionDetail.deliveredPay`). */
    val shiftPayBeforeCents: Long,
    val shiftPayAfterCents: Long,
)

/**
 * The one owner (#1599) of which rows a receipt match may touch, the suggestion, the split, the estimates it clears,
 * the preview, and whether a match is still intact. The sheet, the checks and the projector's apply all call these;
 * nothing re-implements them. Pure.
 */
object ReceiptMatch {

    fun payCents(d: DeliveryRecord): Long? = d.realizedPay?.let(Formats::cents)

    /**
     * Open to a receipt: an estimate or no pay, never changed by the dasher, not moved into the shift by the dasher,
     * and not COVERED by an active match. A row an active match CLEARED stays open.
     */
    fun selectable(d: DeliveryRecord, active: List<ActiveMatchRow>): Boolean =
        (d.payBasis == PayBasis.OFFER_PAY || d.payBasis == PayBasis.NONE) && d.driverAdjustedAt == null &&
            !d.sessionAssigned && coveringRow(d, active) == null

    /** Null when [selectable]; else, first match wins: MatchedTo (an active COVERED row), PaidByReceipt, ChangedByYou. */
    fun lock(d: DeliveryRecord, active: List<ActiveMatchRow>): MatchLock? {
        if (selectable(d, active)) return null
        coveringRow(d, active)?.let { return MatchLock.MatchedTo(it.evidenceId) }
        if (d.payBasis in PayBasis.RECEIPT_EVIDENCE) return MatchLock.PaidByReceipt
        return MatchLock.ChangedByYou
    }

    /** Distinct offer hashes over every row of [jobId]. */
    fun offerCount(jobId: String, deliveries: List<DeliveryRecord>): Int =
        deliveries.filter { it.jobId == jobId }.flatMap { it.jobOfferHashes }.distinct().size

    /** The receipt's own delivery: the row with the receipt's subject task id and job id, or null. */
    fun subjectOf(r: SavedReceipt, deliveries: List<DeliveryRecord>): DeliveryRecord? =
        deliveries.firstOrNull { it.taskId == r.subjectTaskId && it.jobId == r.jobId }

    /**
     * The subject when selectable; plus, when its job has at most one offer, every other selectable row of that job.
     * Empty when the subject is missing or not selectable. On a job with two or more offers it never chooses among
     * the other deliveries: no row carries its own offer, so a per-drop quote cannot be computed.
     */
    fun suggestion(r: SavedReceipt, deliveries: List<DeliveryRecord>, active: List<ActiveMatchRow>): Set<Long> {
        val subject = subjectOf(r, deliveries)?.takeIf { selectable(it, active) } ?: return emptySet()
        if (offerCount(subject.jobId, deliveries) > 1) return setOf(subject.eventSequenceId)
        return deliveries.filter { it.jobId == subject.jobId && selectable(it, active) }
            .map { it.eventSequenceId }.toSet() + subject.eventSequenceId
    }

    /**
     * The selectable `OFFER_PAY` rows of a covered row's job, not covered, when that job has at most one offer: there
     * every estimate is a share of that one offer's quote, which the receipt pays. On a job with two or more offers
     * the estimates pool every offer's quote, so they stay.
     */
    fun cleared(deliveries: List<DeliveryRecord>, covered: Set<Long>, active: List<ActiveMatchRow>): List<DeliveryRecord> {
        val jobs = deliveries.filter { it.eventSequenceId in covered }.map { it.jobId }.distinct()
            .filter { offerCount(it, deliveries) <= 1 }.toSet()
        return deliveries.filter {
            it.jobId in jobs && it.eventSequenceId !in covered && it.payBasis == PayBasis.OFFER_PAY && selectable(it, active)
        }.sortedBy { it.eventSequenceId }
    }

    /**
     * The preview for [selected] (intersected with the selectable rows), or null when nothing selectable is selected or
     * the receipt cannot be split. Covered rows in display order (completion time, then sequence id, as the sheet lists
     * them) take `DropPayApportioner.evenSplitCents(r.totalCents, n)` in that order, so the first rows take the
     * remainder cents.
     */
    fun plan(
        r: SavedReceipt,
        deliveries: List<DeliveryRecord>,
        active: List<ActiveMatchRow>,
        selected: Set<Long>,
    ): ReceiptMatchPlan? {
        val coveredRows = deliveries.filter { it.eventSequenceId in selected && selectable(it, active) }
            .sortedWith(DISPLAY_ORDER)
        if (coveredRows.isEmpty()) return null
        val shares = DropPayApportioner.evenSplitCents(r.totalCents, coveredRows.size)
        if (shares.size != coveredRows.size || shares.any { it < 0 }) return null
        val covered = coveredRows.mapIndexed { i, d -> PlannedRow(d.eventSequenceId, payCents(d), d.payBasis, shares[i]) }
        val cleared = cleared(deliveries, coveredRows.map { it.eventSequenceId }.toSet(), active)
            .map { PlannedRow(it.eventSequenceId, payCents(it), it.payBasis, null) }
        val before = deliveries.sumOf { payCents(it) ?: 0L }
        val replaced = (covered + cleared).sumOf { it.priorPayCents ?: 0L }
        return ReceiptMatchPlan(
            evidenceId = r.evidenceId,
            receiptCents = r.totalCents,
            covered = covered,
            cleared = cleared,
            shiftPayBeforeCents = before,
            shiftPayAfterCents = before - replaced + r.totalCents,
        )
    }

    /**
     * True while [d] still holds what the match wrote: COVERED -> cents == newPayCents, basis RECEIPT_MATCHED,
     * driverAdjustedAt == matchedAt; CLEARED -> no pay, basis NONE, driverAdjustedAt null. False for a missing row.
     */
    fun holdsWritten(row: ActiveMatchRow, d: DeliveryRecord?): Boolean {
        if (d == null) return false
        return when (row.role) {
            ReceiptMatchRole.COVERED -> payCents(d) == row.newPayCents && d.payBasis == PayBasis.RECEIPT_MATCHED &&
                d.driverAdjustedAt == row.matchedAt
            ReceiptMatchRole.CLEARED -> d.realizedPay == null && d.payBasis == PayBasis.NONE && d.driverAdjustedAt == null
        }
    }

    /** The sheet's row order: completion time, then sequence id. */
    val DISPLAY_ORDER: Comparator<DeliveryRecord> = compareBy({ it.completedAt }, { it.eventSequenceId })

    private fun coveringRow(d: DeliveryRecord, active: List<ActiveMatchRow>): ActiveMatchRow? =
        active.firstOrNull { it.role == ReceiptMatchRole.COVERED && it.deliveryEventSequenceId == d.eventSequenceId }
}
