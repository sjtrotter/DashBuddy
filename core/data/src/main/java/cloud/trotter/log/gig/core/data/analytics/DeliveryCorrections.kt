package cloud.trotter.log.gig.core.data.analytics

import cloud.trotter.log.gig.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.log.gig.domain.analytics.DeliveryAdjustmentFold
import cloud.trotter.log.gig.domain.analytics.PayAdjustmentFold
import cloud.trotter.log.gig.domain.analytics.PayBasis
import cloud.trotter.log.gig.domain.analytics.ReceiptRepriceFold
import cloud.trotter.log.gig.domain.evaluation.NetProfit

/** Row-local correction decisions; the projector owns reads, logs, writes and store sweeps. */
internal object DeliveryCorrections {
    /**
     * A MANUAL row stays MANUAL (#650 review F1): a driver correcting their own statement leaves it
     * a driver statement (the audit trail lives in the log), and its net keeps the MANUAL
     * missing-terms-as-0 policy (net-additive). Machine rows flip to USER_CORRECTED with the machine
     * recompute (null when not computable). Net uses the row's OWN frozen cpm, never today's economy;
     * tip/basePay are left untouched.
     */
    fun applyPay(row: DeliveryRecordEntity, fold: PayAdjustmentFold, occurredAt: Long): CorrectionOutcome.Write {
        val manual = row.payBasis == PayBasis.MANUAL
        val net = when {
            manual -> NetProfit.net(fold.newPay, row.realizedMiles ?: 0.0, row.frozenCostPerMile ?: 0.0)
            row.frozenCostPerMile != null && row.realizedMiles != null ->
                NetProfit.net(fold.newPay, row.realizedMiles!!, row.frozenCostPerMile!!)
            else -> null
        }
        return CorrectionOutcome.Write(
            row.copy(
                realizedPay = fold.newPay,
                payBasis = if (manual) PayBasis.MANUAL else PayBasis.USER_CORRECTED,
                netProfit = net,
                // #1033 round 6: a driver monetary edit — the machine re-price must never overwrite it.
                driverAdjustedAt = occurredAt,
            ),
        )
    }

    /**
     * Apply one driver DELIVERY_ADJUSTMENT (#688) multi-field re-apply. Copies each non-null field;
     * null ⇒ that column is left unchanged. Two same-row edits compose when the caller supplies the
     * prior returned row. A note-only edit still returns Write, preserving adjustment skip counts.
     */
    fun applyDelivery(row: DeliveryRecordEntity, fold: DeliveryAdjustmentFold, occurredAt: Long): CorrectionOutcome.Write {
        val followUps = mutableListOf<CorrectionFollowUp>()
        // FIX 5 (#653 double-count guard): a SUSPECT_FULL_RECEIPT row's money was nulled because its
        // siblings' shares already sum to the receipt — editing pay/tip back onto it re-opens the
        // double count with one innocent tap. Block the pay+tip terms; store/cash/note still apply.
        // `originalPayBasis` is the first-fold, never-rewritten basis, so this holds even after a
        // prior non-pay edit. The dialog also disables the Pay field for these rows (a second gate).
        val suspectReceipt = row.originalPayBasis == PayBasis.SUSPECT_FULL_RECEIPT
        if (suspectReceipt && (fold.newPay != null || fold.newTip != null)) {
            followUps += CorrectionFollowUp.PayTipBlockedOnSuspectReceipt
        }
        val reqNewPay = if (suspectReceipt) null else fold.newPay
        val reqNewTip = if (suspectReceipt) null else fold.newTip

        // FIX 3 (#688 F3 invariant): cash is only meaningful on a SESSIONFUL row — gross counts it via a
        // session join, net counts it session-inclusive. A null-session cash row would enter net but
        // never gross, skewing the true-net waterfall cost negative. So drop a cash edit on a
        // null-session row (one PII-safe WARN); every other field still applies. This makes the
        // "cash-bearing rows are always sessionful" invariant real rather than merely documented.
        val cashRejected = fold.newCashTip != null && row.sessionId == null
        if (cashRejected) {
            followUps += CorrectionFollowUp.CashIgnoredOnSessionlessRow
        }

        val newStore = fold.newStoreName ?: row.storeName
        val newPay = reqNewPay ?: row.realizedPay
        val newTip = reqNewTip ?: row.tip
        val newCashTip = if (cashRejected) row.cashTip else when (val cash = fold.newCashTip) {
            null -> row.cashTip
            0.0 -> null
            else -> cash
        }
        val newMiles = fold.newMiles ?: row.realizedMiles
        val manual = row.payBasis == PayBasis.MANUAL
        val payChanged = reqNewPay != null
        val milesChanged = fold.newMiles != null
        // Basis (VET F1): payBasis rewrites exactly when realizedPay does. A machine row flips to
        // USER_CORRECTED iff pay changed; a MANUAL row stays MANUAL; a cash/tip/store-only edit leaves
        // the basis (and its "est. offer pay" disclosure) intact.
        val newBasis = when {
            manual -> PayBasis.MANUAL
            payChanged -> PayBasis.USER_CORRECTED
            else -> row.payBasis
        }
        // Net recompute only when a net-bearing term (pay or miles) changed; otherwise the stored
        // netProfit is preserved byte-identically (cash/tip/store/note-only edits do not touch net).
        // MANUAL keeps the missing-terms-as-0 net-additive policy over the FINAL values; a machine row
        // recomputes against its OWN frozen cpm. #1133: a pay-less miles edit still carries −cost;
        // missing miles or frozen cpm keeps net null.
        val net = when {
            !payChanged && !milesChanged -> row.netProfit
            manual -> NetProfit.net(newPay ?: 0.0, newMiles ?: 0.0, row.frozenCostPerMile ?: 0.0)
            else -> NetProfit.realized(newPay, newMiles, row.frozenCostPerMile)
        }
        // #159 H1: a driver-supplied newStoreName NULLS the resolved storeKey AND sets the pin, so the
        // driver's fix wins the report-card grouping (its grouping becomes read-side
        // normalizedChain(newStoreName), F9) and store resolution NEVER re-keys it back to the pickup
        // anchor (every resolution UPDATE carries `WHERE storeKeyPinned = 0`). The pin is derived from
        // this event, so a from-zero refold re-derives it (F8 wipe clears it, the replayed event resets
        // it) — rebuild-deterministic. A store-name-less edit leaves storeKey/pin untouched.
        val storeChanged = fold.newStoreName != null
        val priorStoreKey = row.storeKey
        if (storeChanged && priorStoreKey != null) {
            followUps += CorrectionFollowUp.SweepStoreIfUnreferenced(priorStoreKey)
        }
        return CorrectionOutcome.Write(
            // originalPayBasis (+ every unmentioned column) is preserved by `row.copy`.
            row.copy(
                storeName = newStore,
                realizedPay = newPay,
                tip = newTip,
                cashTip = newCashTip,
                realizedMiles = newMiles,
                payBasis = newBasis,
                netProfit = net,
                storeKey = if (storeChanged) null else row.storeKey,
                storeKeyPinned = if (storeChanged) 1 else row.storeKeyPinned,
                // Pay, tip, and miles edits own the receipt economics. A cash-only edit owns
                // the cash column, which the re-price never touches, so it is not pay ownership.
                // A tip-only edit leaves payBasis intact (#688), so it still needs this stamp.
                driverAdjustedAt = if (
                    reqNewPay != null || reqNewTip != null || fold.newMiles != null
                ) {
                    occurredAt
                } else {
                    row.driverAdjustedAt
                },
            ),
            followUps = followUps,
        )
    }

    /**
     * Apply one machine DELIVERY_RECEIPT_REPRICE (#1033 layer 2) to the supplied row. The receipt's
     * apportioned share replaces the OFFER_PAY estimate with DROP_SHARE. Net recomputes against the
     * row's OWN frozenCostPerMile and realizedMiles, never today's economy. originalPayBasis, cash,
     * store keys, mileage and every other unmentioned column are preserved via row.copy.
     *
     * Driver ownership is checked first: MANUAL, USER_CORRECTED or driverAdjustedAt refuses the
     * machine re-price. A later driver adjustment still wins in log order. Then an already-priced
     * DROP_SHARE row is skipped without rewriting or stamping receiptRepricedAt for a correction
     * that corrected nothing; the caller supplies the persisted row, which the state machine cannot
     * know. Missing-row checks belong to the projector.
     */
    fun applyReceiptReprice(row: DeliveryRecordEntity, fold: ReceiptRepriceFold): CorrectionOutcome {
        // #1033 round 6: `driverAdjustedAt` is the load-bearing half of this guard. A TIP-ONLY
        // DELIVERY_ADJUSTMENT deliberately leaves `payBasis` alone (#688 VET F1 — the row keeps its
        // "est. offer pay" disclosure), so the basis test alone let a later re-price overwrite the
        // driver's own tip. Either signal refuses.
        if (row.driverAdjustedAt != null ||
            row.payBasis == PayBasis.MANUAL || row.payBasis == PayBasis.USER_CORRECTED
        ) {
            return CorrectionOutcome.Skip(
                RepriceSkip.DriverOwned(row.payBasis, row.driverAdjustedAt != null),
            )
        }
        // #1033 round 8: "already priced" is answered HERE, against the row itself — the state
        // machine cannot know which completions actually persisted (it tried, through two review
        // rounds, and both attempts refused legitimate corrections). A re-price whose values the row
        // already holds is a no-op: no rewrite, so no `receiptRepricedAt` churn either. Refold-stable
        // by construction — the comparison sees the same rows on a from-zero refold.
        if (row.payBasis == PayBasis.DROP_SHARE &&
            sameCents(row.realizedPay, fold.realizedPay) &&
            sameCents(row.tip, fold.tip) &&
            sameCents(row.basePay, fold.basePay)
        ) {
            return CorrectionOutcome.Skip(RepriceSkip.AlreadyPriced(Math.round(fold.realizedPay * 100.0)))
        }
        val net = if (row.frozenCostPerMile != null && row.realizedMiles != null) {
            NetProfit.net(fold.realizedPay, row.realizedMiles!!, row.frozenCostPerMile!!)
        } else {
            null
        }
        return CorrectionOutcome.Write(
            // originalPayBasis (+ every unmentioned column) is preserved by `row.copy`.
            row.copy(
                realizedPay = fold.realizedPay,
                tip = fold.tip,
                basePay = fold.basePay,
                payBasis = PayBasis.DROP_SHARE,
                netProfit = net,
                receiptRepricedAt = fold.repricedAt,
            ),
        )
    }

    /** Cent-exact equality for two nullable money figures; two nulls are equal. */
    private fun sameCents(a: Double?, b: Double?): Boolean = when {
        a == null && b == null -> true
        a == null || b == null -> false
        else -> Math.round(a * 100.0) == Math.round(b * 100.0)
    }

}

internal sealed interface CorrectionOutcome {
    data class Write(val row: DeliveryRecordEntity, val followUps: List<CorrectionFollowUp> = emptyList()) : CorrectionOutcome
    data class Skip(val reason: RepriceSkip) : CorrectionOutcome
}

internal sealed interface RepriceSkip {
    data class DriverOwned(val payBasis: String, val driverAdjusted: Boolean) : RepriceSkip
    data class AlreadyPriced(val cents: Long) : RepriceSkip
}

internal sealed interface CorrectionFollowUp {
    data object PayTipBlockedOnSuspectReceipt : CorrectionFollowUp
    data object CashIgnoredOnSessionlessRow : CorrectionFollowUp
    data class SweepStoreIfUnreferenced(val storeKey: String) : CorrectionFollowUp
}
