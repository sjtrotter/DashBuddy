package cloud.trotter.dashbuddy.domain.state

import cloud.trotter.dashbuddy.domain.model.pay.ParsedPay
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPayItem

/**
 * Splits one combined delivery receipt ([ParsedPay]) into a per-drop realized-pay share
 * (#528 Slice A). A stacked job produces a single combined receipt — per-order **tips** keyed by
 * store, but a **lump** base — so on a stack today one drop absorbs the whole receipt while the
 * others record null pay. This apportioner gives each drop its own share so the DELIVERY_COMPLETED
 * rows are individually meaningful.
 *
 * Attribution:
 * - **Tips are EXACT** when the drops map 1:1 onto the receipt's tip lines by store-brand tokens
 *   ([StoreNameMatch.sharedLeadingTokens]) — the same correlation the store-chain projection uses.
 * - **Base is an equal-split ESTIMATE.** Neither the offer nor the receipt exposes a per-order
 *   base, so v1 divides the lump base evenly across the drops. Honest, documented, revisited by a
 *   later slice.
 * - **Fallback to a pure equal-split of the receipt total** whenever the drops don't map cleanly
 *   1:1 onto the tip lines — a blank/duplicate dropoff store name (#526), a same-store batch
 *   (GoPuff: N customers, 1 store, N same-labelled tip lines), or an unmatched bare-number label
 *   (#607). This is what keeps a same-store batch from double-counting a single tip line across
 *   drops.
 *
 * The load-bearing property is the **no-double-count invariant**: the shares sum EXACTLY to
 * [ParsedPay.total] (the *receipt* total — tips adjust post-delivery, so this is authoritative over
 * the offer-time [Job.totalPayAmount]). Computed in integer cents, the rounding remainder to the
 * last drop, so summing the emitted shares reproduces the receipt to the cent.
 *
 * Pure and side-effect-free (no PII: store names are not PII; customer identity is already hashed
 * upstream). Reused from `:core:state` at the DELIVERY_COMPLETED mint sites.
 */
object DropPayApportioner {

    /**
     * Per-drop realized-pay shares as `taskId -> dollars`.
     *
     * Returns an **empty map** when [parsedPay] is null — no itemized receipt means there is
     * nothing to attribute, so callers record `null` per drop rather than splitting a bare total
     * (a mid-job collapsed receipt carries a `totalPay` but no [ParsedPay]; splitting that would
     * treat one drop's partial as the whole job).
     *
     * A single drop maps to the whole [ParsedPay.total] so consumers read one field uniformly.
     *
     * [dropoffTasks] is the set of the job's delivered dropoffs (the completion rows that will be
     * minted); the denominator and the shares are derived from it, deduped by `taskId`.
     */
    fun apportion(parsedPay: ParsedPay?, dropoffTasks: List<Task>): Map<String, Double> {
        if (parsedPay == null) return emptyMap()
        val drops = dropoffTasks.distinctBy { it.taskId }
        if (drops.isEmpty()) return emptyMap()

        val totalCents = toCents(parsedPay.total)

        if (drops.size == 1) {
            return mapOf(drops.first().taskId to totalCents / 100.0)
        }

        // Raw (double) share per drop, before cent reconciliation.
        val matchedTips = injectiveTipMatch(drops, parsedPay.customerTips.filter { it.type.isNotBlank() })
        val rawShares: Map<String, Double> = if (matchedTips != null) {
            val baseShare = parsedPay.totalBasePay / drops.size
            drops.associate { it.taskId to (matchedTips.getValue(it.taskId) + baseShare) }
        } else {
            val evenShare = parsedPay.total / drops.size
            drops.associate { it.taskId to evenShare }
        }

        return reconcileToCents(drops, rawShares, totalCents)
    }

    /**
     * An **equal split of a bare total** across the job's dropoffs (#691) — the offer-pay fallback
     * for a wholly receipt-less job (a DoorDash shop order shows NO per-delivery receipt; pay exists
     * only on the accepted offer + the running dash total). Unlike [apportion], which deliberately
     * refuses to split a bare [ParsedPay]-less total, this splits an ESTIMATE ([Job.offerPayTotal],
     * the accepted-offer guaranteed pay) equally: the platform's actual per-drop split is unknowable
     * without a receipt, so the honest estimate is the offer total divided evenly.
     *
     * Reuses the SAME cents-exact remainder-to-last invariant as [apportion] ([reconcileToCents]), so
     * the emitted shares sum EXACTLY to [total] in integer cents and no drop double-counts.
     *
     * Returns an **empty map** when [total] is null, ≤ 0, or [dropoffTasks] is empty — a pay-less
     * offer (or a partial-null stack that summed to null) must not stamp $0 estimate rows. The caller
     * records `null` per drop → the fold keeps `PayBasis.NONE` for those completions.
     *
     * [dropoffTasks] is the job's OWN owed-dropoff set (deduped by `taskId`); the denominator and the
     * shares are derived from it.
     */
    fun equalSplit(total: Double?, dropoffTasks: List<Task>): Map<String, Double> {
        if (total == null || total <= 0.0) return emptyMap()
        val drops = dropoffTasks.distinctBy { it.taskId }
        if (drops.isEmpty()) return emptyMap()

        val totalCents = toCents(total)
        if (drops.size == 1) {
            return mapOf(drops.first().taskId to totalCents / 100.0)
        }
        val evenShare = total / drops.size
        val rawShares = drops.associate { it.taskId to evenShare }
        return reconcileToCents(drops, rawShares, totalCents)
    }

    /**
     * Reconcile per-drop raw (double) shares to integer cents so they sum EXACTLY to [totalCents];
     * the rounding remainder is assigned to the LAST drop. The one splitting invariant shared by
     * [apportion] (tip-matched or even receipt split) and [equalSplit] (offer-pay estimate split).
     */
    private fun reconcileToCents(
        drops: List<Task>,
        rawShares: Map<String, Double>,
        totalCents: Long,
    ): Map<String, Double> {
        val result = LinkedHashMap<String, Double>(drops.size)
        var assigned = 0L
        drops.forEachIndexed { index, drop ->
            val shareCents = if (index == drops.lastIndex) {
                totalCents - assigned
            } else {
                toCents(rawShares.getValue(drop.taskId)).also { assigned += it }
            }
            result[drop.taskId] = shareCents / 100.0
        }
        return result
    }

    /**
     * A clean, unambiguous 1:1 assignment of tip lines to drops, or null when none exists (→ the
     * caller falls back to an equal split). Requires a true bijection: equal counts, every drop
     * has a non-blank store name, every drop matches exactly one tip line, and no two drops claim
     * the same line. A same-store batch (both drops match both lines) fails the "exactly one
     * candidate" test → null, so a single tip line can never be counted for two drops.
     */
    private fun injectiveTipMatch(drops: List<Task>, tips: List<ParsedPayItem>): Map<String, Double>? {
        if (tips.size != drops.size) return null
        if (drops.any { it.storeName.isNullOrBlank() }) return null
        val used = mutableSetOf<Int>()
        val out = LinkedHashMap<String, Double>(drops.size)
        for (drop in drops) {
            val store = drop.storeName!!
            val candidates = tips.indices.filter {
                StoreNameMatch.sharedLeadingTokens(tips[it].type, store) >= 1
            }
            if (candidates.size != 1) return null          // ambiguous or unmatched → fall back
            val idx = candidates.single()
            if (!used.add(idx)) return null                // not injective → fall back
            out[drop.taskId] = tips[idx].amount
        }
        return out
    }

    private fun toCents(dollars: Double): Long = Math.round(dollars * 100.0)
}
