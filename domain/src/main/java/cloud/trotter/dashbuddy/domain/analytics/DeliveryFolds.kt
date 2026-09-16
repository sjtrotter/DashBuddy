package cloud.trotter.dashbuddy.domain.analytics

import cloud.trotter.dashbuddy.domain.evaluation.NetProfit
import cloud.trotter.dashbuddy.domain.model.event.SequencedAppEvent
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.PickupPayload
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.StoreKeys

/**
 * The `DELIVERY_COMPLETED` + `PICKUP_CONFIRMED` record-mint folds, split out of [RecordFolds] (#761,
 * the #237 file-ceiling residue; [LegFolds] is the same-package precedent). Pure — no Android / DB /
 * wall clock — driven only by the event's own timestamps, metadata, and the threaded
 * [SessionFoldContext]. These are the two folds that MINT read-model rows ([DeliveryFold] /
 * [PickupFold]) plus the store-resolution trigger + leg consumption; the offer/session/correction
 * folds stay behind ([RecordFolds] keeps the dispatcher and the session-lifecycle folds, [CorrectionFolds]
 * owns the driver-correction folds). Shared session helpers ([resolveContext] / [advance], and the leg
 * helpers in [LegFolds]) remain the ONE top-level definition in `RecordFolds.kt` / `LegFolds.kt`.
 */
internal object DeliveryFolds {

    fun foldDeliveryCompleted(
        event: SequencedAppEvent,
        context: SessionFoldContext?,
        currentCostPerMile: Double?,
    ): FoldOutcome {
        val e = event.event
        val p = e.payload as? DeliveryPayload
            ?: return FoldOutcome(context = context, skip = "DELIVERY_COMPLETED: missing/malformed payload")
        val sid = e.sessionId
        val ctx = sid?.let { resolveContext(context, it, e.occurredAt) }
        val platformWire = ctx?.platform?.wire ?: Platform.Unknown.wire
        val odo = event.metadata?.odometer
        val completedAt = p.completedAt ?: e.occurredAt

        // #653: a full-receipt stamp on an IDENTITY-LESS drop of an ALREADY-delivered
        // (multi-delivery) job is SUSPECT. The signal the fold can see: this jobId already
        // delivered ≥1 drop this session (it is in the accumulated/hydrated `deliveredJobIds`),
        // this completion carries the whole `parsedPay` receipt with NO apportioned
        // `dropRealizedPay` share, AND it has no customer identity (both hashes null — the
        // #498/#517/#518 phantom class by construction; the payload copies the task's null hashes,
        // and identity-less drops are excluded from the apportion denominator upstream). Its
        // sibling drops' shares already sum to that receipt, so stamping the full receipt here
        // would double-count the period SUM. Drop the pay/tip/base rather than inflating.
        // The identity discriminator is LOAD-BEARING for history: the pre-#528 mint shape for a
        // legitimate receipted multi-drop stack was N−1 null-pay rows + ONE identity-BEARING row
        // carrying the whole receipt (the job's only money, usually folding LAST) — that row must
        // keep RECEIPT_TOTAL on the v3 refold, not be nulled. A single-delivery job (this jobId's
        // first/only drop) is likewise NOT flagged — a bare totalPay on the sole drop is correct.
        // Defense-in-depth behind the EffectMap identity firewall (which blocks new phantoms).
        val priorDeliveryForThisJob = ctx != null && p.jobId in ctx.deliveredJobIds
        val suspectFullReceipt =
            priorDeliveryForThisJob && p.parsedPay != null && p.dropRealizedPay == null &&
                p.customerHash == null && p.addressHash == null

        // #691 receipt-evidence predicate: a completion carries PAY-BEARING receipt evidence iff it
        // has an itemized receipt OR a POSITIVE bare total. `totalPay` is coerced from the pay screen
        // by `ParsedFieldsFactory.buildPostTask` as `f.double("totalPay") ?: 0.0`, so a transient
        // `delivery_summary_collapsed` frame that fails to parse its final value produces a $0.00
        // `PostTaskFields` — a pseudo-receipt. A real $0 delivery receipt isn't a thing, so a 0.0
        // total is NOT receipt evidence and must not suppress the offer-pay estimate. The same
        // predicate gates stamp-suppression at the EffectMap edge (one definition, two sites).
        val payBearingReceipt = p.parsedPay != null || (p.totalPay ?: 0.0) > 0.0

        // #691 mixed-receipt guard: an offer-pay ESTIMATE (an equal split of the offer total)
        // assumes the WHOLE job is receipt-less. If a sibling drop of this job already folded a REAL
        // receipt this session, mixing the two over-counts invisibly (the reported-total
        // reconciliation floors at 0), so a receipt-less drop of such a job keeps NONE. Defense-in-
        // depth behind the EffectMap job-scoped stamp condition (which is the primary control).
        // FIX 4: a null session context fails CLOSED — with no `receiptedJobIds` to consult we cannot
        // rule out a sibling receipt, so an OFFER_PAY estimate is withheld (matching the suspect
        // check's degraded-context under-count direction — never over-count on missing context).
        val jobAlreadyReceipted = ctx != null && p.jobId in ctx.receiptedJobIds
        val useOfferShare = ctx != null && !suspectFullReceipt &&
            p.dropRealizedPay == null && !payBearingReceipt &&
            p.offerPayShare != null && !jobAlreadyReceipted

        // Pay + basis. dropRealizedPay is a per-drop share (possibly of a multi-drop receipt); its
        // absence with a bare totalPay means the whole receipt landed on this one drop. With neither,
        // the #691 offer-pay estimate is the fallback before NONE. OFFER_PAY is evaluated ABOVE
        // RECEIPT_TOTAL so a STAMPED estimate on a $0-coerced pseudo-receipt folds as the estimate,
        // not as $0.00 — but ONLY when `offerPayShare` is present (null on ALL pre-#691 events), so a
        // historical $0-total row with no stamp still folds RECEIPT_TOTAL $0.00 byte-for-byte (no
        // PROJECTOR_VERSION bump; the un-stamped $0-pseudo-receipt residual rides to #703).
        val realizedPay = when {
            suspectFullReceipt -> null
            p.dropRealizedPay != null -> p.dropRealizedPay
            useOfferShare -> p.offerPayShare
            p.totalPay != null -> p.totalPay
            else -> null
        }
        val payBasis = when {
            suspectFullReceipt -> PayBasis.SUSPECT_FULL_RECEIPT
            p.dropRealizedPay != null -> PayBasis.DROP_SHARE
            useOfferShare -> PayBasis.OFFER_PAY
            p.totalPay != null -> PayBasis.RECEIPT_TOTAL
            else -> PayBasis.NONE
        }
        // tip/basePay only when this drop IS the job's sole drop — i.e. it carries the WHOLE receipt.
        // The apportioner stamps `dropRealizedPay` even on a single drop (its share == the total), so
        // a null-drop bare receipt OR a share equal to the receipt total is the sole-drop signal; a
        // smaller share is a stacked drop, which has no per-drop tip split yet (→ null). A suspect
        // full-receipt drop (#653) is never the sole drop — its siblings already carry the receipt.
        val receipt = p.parsedPay
        val soleDrop = !suspectFullReceipt && receipt != null &&
            soleDropOfReceipt(p.dropRealizedPay, receipt.total)
        val tip = if (soleDrop) receipt.totalTip else null
        val basePay = if (soleDrop) receipt.totalBasePay else null

        // Partition deltas. Anchor = the previous completion (or DASH_START) in the same session.
        // Floor the per-row MILES delta at 0 like the session-level SUM (MAX(…,0)): a mid-session
        // odometer reset yields a NEGATIVE delta, which would otherwise INFLATE this row's netProfit
        // (pay − negativeMiles × cpm). The next drop re-anchors off this (lower) reading naturally.
        val prevOdo = ctx?.prevDropOdometer ?: ctx?.startOdometer
        val spanRealizedMiles = if (odo != null && prevOdo != null) (odo - prevOdo).coerceAtLeast(0.0) else null

        // #1108 — the TIME partition, with the fold-order defect removed and the QUANTITY kept.
        //
        // A drop's realized minutes are the gap since the previous completion, which is deliberate:
        // Σ over a dash's drops is the dasher's working time, and `TimeEconomics.deliveryMinutes`,
        // `NetPerHourPair.whileWorking` and `EstimateVsReality`'s realized rate are all built on
        // that. What was wrong is the ANCHOR's monotonicity. Completions fold in `sequenceId` order,
        // which is not completion order (§5's ordering contract): both drops of the fielded job …500
        // emitted their `DELIVERY_COMPLETED` at ONE instant and the close-out sweep emitted the
        // LATER-completed drop first, so the anchor moved to 17:37:18 and the earlier-completed
        // sibling — which really finished at 17:30:11 — folded `(completedAt − anchor)` = −7.12 min
        // for a drop that took 10.9. Lifetime, the only three negative-minute rows in the database
        // are each the first drop of a 2-drop job.
        //
        // Two rules, together:
        //  1. the anchor NEVER moves backwards (`prevDropAt = max(prevDropAt, completedAt)` below),
        //     so one out-of-order emission cannot hand the NEXT job's drop an anchor in its future
        //     and cascade the defect down the dash;
        //  2. a row whose own `completedAt` still predates the anchor (the same-instant siblings
        //     themselves, or a clock artefact) folds NULL minutes. Not a negative — which reaches a
        //     rate — and not a floored 0, which is a MEASUREMENT of zero working time and would drag
        //     every mean that reads it. Null is the read model's own vocabulary for "not measured":
        //     #975 excludes an offer with no realized minutes from the estimate-vs-reality sample
        //     and #983's rates are null rather than `$0.00/hr`. Fail-null (#745): the sibling's
        //     minutes are simply not attributable, and Σ for the dash undercounts by that drop.
        val prevAt = ctx?.prevDropAt ?: ctx?.startedAt
        val minutesInverted = prevAt != null && completedAt < prevAt
        val realizedMinutes = when {
            prevAt == null -> null
            minutesInverted -> null
            else -> (completedAt - prevAt) / 60_000.0
        }

        // #688 phase B: consume this drop's per-leg mileage from the session leg accumulator.
        //  - milesToDropoff = this drop's own taskId entry (unambiguous).
        //  - milesToStore   = one store leg of THIS job, stamped ONLY on a LEG-SUM row (milesToDropoff
        //    != null). Exact NORMALIZED-chain store-form match wins (#688 review Fix 3 — the #159
        //    StoreKeys SSOT, so a payout form-drift like "StoreX #55" still matches the pickup form;
        //    a null payload storeName can't match, the #526/#557 "Unknown store" family), else the
        //    oldest unclaimed leg of the job (FIFO); claimed once (DEV-DECISION 2: no fabricated split —
        //    a shared-store stack's first drop claims the whole store leg, the sibling gets null).
        //  - A LEGACY row (missed DELIVERY_ARRIVED ⇒ milesToDropoff null) stamps NO store leg (#688
        //    review Fix 4 — leg-sum ≠ realizedMiles must stay the driver-edit trail, not a machine
        //    lone-leg stamp on an untouched row) and RETIRES the session's already-closed store legs
        //    below (session-wide — the legacy span is a session-level delta, re-verify widening).
        val legState = ctx?.legState ?: LegState()
        // #1108: a share RESERVED for this drop by a sibling's span-basis fold owns its mileage —
        // the job is on the span basis, and a job's drops never sit on two bases. Any leg this drop
        // has since closed is ignored (and dropped below): the reserving span already contains it.
        val reservedSpanMiles = legState.spanApportionedMiles[p.taskId]
        val milesToDropoff = if (reservedSpanMiles == null) legState.pendingDropoffLegs[p.taskId] else null
        val storeLegs = legState.pendingStoreLegs
        val claimIdx = if (milesToDropoff != null) {
            val exact = if (p.storeName != null) {
                storeLegs.indexOfFirst {
                    it.jobId == p.jobId && it.storeName != null &&
                        StoreKeys.normalizedChain(it.storeName) == StoreKeys.normalizedChain(p.storeName)
                }
            } else {
                -1
            }
            if (exact >= 0) exact else storeLegs.indexOfFirst { it.jobId == p.jobId }
        } else {
            -1
        }
        val milesToStore = claimIdx.takeIf { it >= 0 }?.let { storeLegs[it].miles }

        // #1108 — ONE mileage basis per job. A span-basis fold takes the whole
        // `prevDropOdometer → completion` delta, which physically CONTAINS the to-dropoff legs of
        // this job's siblings that have already arrived but not yet completed. Leaving those legs
        // pending let the sibling claim one again on the leg-sum basis, and the two bases then
        // double-counted the shared driving: fielded job …500, drop 2 (no `DELIVERY_ARRIVED` — the
        // platform went straight from drop 1's confirm to drop 2's) folded the whole 8.82 mi span
        // and drop 1 then folded 6.00 mi of leg sum out of the same 8.82.
        //
        // So the span is APPORTIONED: an equal share to this drop and to each sibling whose pending
        // leg it swallowed, whose legs are retired here and whose shares are reserved in
        // [LegState.spanApportionedMiles] for their own folds to read. Σ per job ≤ the job's span by
        // construction (strictly less if a reserved sibling is unassigned and never folds — the
        // sanctioned under-attribution direction). An equal split is deliberately chosen over
        // "sibling keeps its measured leg, the span row takes the residue": that residue shape is
        // exactly the mixed basis this fix exists to remove, and a measured leg for one drop is not
        // evidence about the other drop's share of a jointly-driven route.
        //
        // The RETIRE is session-wide and the RESERVATION is job-scoped, which is not an inconsistency:
        // it is #688 review Fix 1's rule for store legs, extended to dropoff legs. The span is a
        // SESSION-level delta (previous completion → this one, regardless of job), so a CROSS-JOB
        // pending leg closed inside it is equally double-countable and is retired too — what it does
        // NOT get is a share of THIS job's mileage, because that would attribute one job's driving to
        // another job's row. Such a drop falls back to its own span delta, which after this completion
        // is exactly the driving since: honest, and one-sided in the same direction as Fix 1's own
        // trade (an unknown closure order retires, accepting per-row under-attribution over a
        // double-count). Nothing is retired when the span itself is unmeasurable (no odometer): the
        // siblings keep their legs and the pre-#1108 behaviour stands.
        val spanBasis = milesToDropoff == null && reservedSpanMiles == null && spanRealizedMiles != null
        val retiredSiblings: List<String> =
            if (spanBasis) legState.pendingDropoffLegs.keys.filterNot { it == p.taskId } else emptyList()
        val swallowedSiblings: List<String> = retiredSiblings.filter { legState.dropoffLegInJob(it, p.jobId) }
        val spanShareMiles: Double? = spanRealizedMiles?.let { it / (1 + swallowedSiblings.size) }
        val spanReservations: Map<String, Double> =
            if (spanShareMiles != null) swallowedSiblings.associateWith { spanShareMiles } else emptyMap()

        // realizedMiles becomes the leg SUM only when the final (to-dropoff) leg is known; a lone store
        // leg understates (the span delta already contains it), so partial leg data never replaces the
        // total — the `milesToDropoff != null` gate (§2). Invariant: realizedMiles == (milesToStore ?: 0)
        // + milesToDropoff iff milesToDropoff != null.
        val realizedMiles = when {
            milesToDropoff != null -> (milesToStore ?: 0.0) + milesToDropoff
            reservedSpanMiles != null -> reservedSpanMiles
            else -> spanShareMiles
        }

        // Frozen economy — immutable historical fact.
        val (frozenCpm, costBasis) = when {
            ctx?.lastEvaluatedCostPerMile != null -> ctx.lastEvaluatedCostPerMile to CostBasis.OFFER_FROZEN
            currentCostPerMile != null -> currentCostPerMile to CostBasis.CURRENT_FALLBACK
            else -> null to CostBasis.NONE
        }
        // The fuel/non-fuel split is populated ONLY off the OFFER_FROZEN basis — the offer's own
        // evaluation carried it. CURRENT_FALLBACK/NONE deliveries have no split (the live economy read
        // supplies a bare cpm, not its components), so they stay null → the 4-step waterfall falls back
        // to 3-step for those rows (#659). By construction frozenFuelPerMile + frozenNonFuelPerMile ≈
        // frozenCostPerMile when all present.
        val frozenFuelPerMile = if (costBasis == CostBasis.OFFER_FROZEN) ctx?.lastEvaluatedFuelPerMile else null
        val frozenNonFuelPerMile = if (costBasis == CostBasis.OFFER_FROZEN) ctx?.lastEvaluatedNonFuelPerMile else null
        val netProfit = if (frozenCpm != null && realizedPay != null && realizedMiles != null) {
            NetProfit.net(realizedPay, realizedMiles, frozenCpm)
        } else {
            null
        }

        val delivery = DeliveryFold(
            eventSequenceId = event.sequenceId,
            sessionId = sid,
            platform = platformWire,
            jobId = p.jobId,
            taskId = p.taskId,
            storeName = p.storeName,
            customerHash = p.customerHash,
            addressHash = p.addressHash,
            phaseStartedAt = p.phaseStartedAt,
            arrivedAt = p.arrivedAt,
            completedAt = completedAt,
            deadlineMillis = p.deadlineMillis,
            realizedPay = realizedPay,
            payBasis = payBasis,
            tip = tip,
            cashTip = null, // machine completions never carry a driver cash tip (#688)
            basePay = basePay,
            odometerAtCompletion = odo,
            realizedMiles = realizedMiles,
            realizedMinutes = realizedMinutes,
            frozenCostPerMile = frozenCpm,
            frozenFuelPerMile = frozenFuelPerMile,
            frozenNonFuelPerMile = frozenNonFuelPerMile,
            netProfit = netProfit,
            costBasis = costBasis,
            // #159 B1/B2: persist the FULL receipt store-form set on the one completion that carries
            // parsedPay (null on receipt-less sibling drops), so resolution reads running keys from
            // ROWS — every store of a multi-store stack stays keyed even after a payout-less re-run.
            payoutStoreForms = p.parsedPay?.customerTips
                ?.mapNotNull { it.type.takeIf { t -> t.isNotBlank() } }
                ?.takeIf { it.isNotEmpty() },
            milesToStore = milesToStore,
            milesToDropoff = milesToDropoff,
        )

        // #691: mark the job receipted once any drop folds RECEIPT EVIDENCE, so a later receipt-less
        // sibling of the same job is denied the offer-pay estimate (the mixed-receipt guard above).
        // Includes SUSPECT_FULL_RECEIPT (#653): its money was nulled, but the receipt still HAPPENED,
        // so it proves the job is not receipt-less. SSOT with the DAO hydration IN-list.
        val jobReceipted = payBasis in PayBasis.RECEIPT_EVIDENCE
        val newCtx = ctx?.copy(
            deliveries = ctx.deliveries + 1,
            deliveredJobIds = ctx.deliveredJobIds + p.jobId,
            receiptedJobIds = if (jobReceipted) ctx.receiptedJobIds + p.jobId else ctx.receiptedJobIds,
            // Advance the miles anchor only when this drop had an odometer, so a null-odometer drop
            // folds its miles into the next drop instead of resetting the partition; the time anchor
            // always advances (time is always known).
            prevDropOdometer = odo ?: ctx.prevDropOdometer,
            // #1108: MONOTONIC. `sequenceId` is the fold order and `completedAt` is not monotonic in
            // it, so taking this completion raw let one out-of-order emission poison every later
            // drop's partition. The anchor is the LATEST completion seen, never the last folded.
            prevDropAt = maxOf(ctx.prevDropAt ?: 0L, completedAt),
            lastEventAt = maxOf(ctx.lastEventAt, e.occurredAt),
            lastOdometer = odo ?: ctx.lastOdometer,
            // #688: consume the claimed store leg + this drop's dropoff leg, then advance the leg
            // anchor to the completion odometer (null ⇒ anchor unchanged, miles roll forward).
            legState = legState.copy(
                prevLegOdometer = odo ?: legState.prevLegOdometer,
                pendingStoreLegs = when {
                    // Leg-sum row: consume the single claimed store leg.
                    claimIdx >= 0 -> storeLegs.filterIndexed { i, _ -> i != claimIdx }
                    // Legacy row (#688 review Fix 1 + re-verify widening): RETIRE pending store legs
                    // SESSION-WIDE whose closure is at/before this completion odometer (or unknown).
                    // The legacy span is a SESSION-level partition delta (prevDrop→completion,
                    // regardless of job), so a CROSS-JOB leg closed inside it — the add-on shape: J2's
                    // pickup arrival en route while J1's order is out, then J1 completes legacy — is
                    // equally double-countable; the original job-scoped retire left that real
                    // double-count open. Keep only legs provably closed AFTER this completion odo (a
                    // genuinely later arrival's). A null completion odo OR null leg-closure marker ⇒
                    // retire (fail-null under-attribution, never a double-count — the sanctioned error
                    // direction).
                    milesToDropoff == null -> storeLegs.filter { leg ->
                        leg.closedAtOdometer != null && odo != null && leg.closedAtOdometer > odo
                    }
                    // Leg-sum row that found no store leg to claim: leave the queue untouched.
                    else -> storeLegs
                },
                // #1108: this drop's own leg goes (consumed as `milesToDropoff`, or ignored because a
                // reservation owned its mileage — either way the drop is done and its leg must never
                // outlive it), and so do the sibling legs this span swallowed, whose shares are
                // reserved below. `dropoffLegJobIds` mirrors the leg map key-for-key.
                pendingDropoffLegs = legState.pendingDropoffLegs - p.taskId - retiredSiblings.toSet(),
                dropoffLegJobIds = legState.dropoffLegJobIds - p.taskId - retiredSiblings.toSet(),
                // This drop's reservation is consumed; the siblings' are written for their own folds.
                spanApportionedMiles = (legState.spanApportionedMiles - p.taskId) + spanReservations,
            ),
        )
        // #159: every DELIVERY_COMPLETED re-triggers store resolution for its job (job-scoped). The
        // payout is NOT threaded — resolution reads payoutStoreForms from the committed rows (B1).
        val resolution = StoreResolution(
            sessionId = sid,
            platform = platformWire,
            jobId = p.jobId,
            offerHashes = p.jobOfferHashes,
        )
        return FoldOutcome(
            context = newCtx,
            delivery = delivery,
            resolution = resolution,
            // #1108: the orchestrator logs this at DEBUG — `:domain` is a pure JVM module with no
            // logger, and a null-minutes row is otherwise indistinguishable from a legitimately
            // unmeasured one. Ids only (P7): no money, no store, no customer.
            note = if (minutesInverted) {
                "realizedMinutes null: completedAt precedes the session partition anchor " +
                    "(job=${p.jobId}, task=${p.taskId})"
            } else {
                null
            },
        )
    }

    /**
     * A completed pickup (#159) → one [PickupFold] visits row. `PICKUP_CONFIRMED` carries the full
     * `phaseStartedAt/arrivedAt/confirmedAt` progression; `storeKey` is stamped later by resolution.
     * Advances liveness like any session-attributed event (the pre-#159 `else` branch behaviour).
     */
    fun foldPickupConfirmed(event: SequencedAppEvent, context: SessionFoldContext?): FoldOutcome {
        val e = event.event
        val p = e.payload as? PickupPayload
            ?: return FoldOutcome(context = context, skip = "PICKUP_CONFIRMED: missing/malformed payload")
        val sid = e.sessionId
        val ctx = sid?.let { resolveContext(context, it, e.occurredAt) }
        val platformWire = ctx?.platform?.wire ?: Platform.Unknown.wire
        val pickup = PickupFold(
            eventSequenceId = event.sequenceId,
            sessionId = sid,
            platform = platformWire,
            jobId = p.jobId,
            taskId = p.taskId,
            storeName = p.storeName,
            phaseStartedAt = p.phaseStartedAt,
            arrivedAt = p.arrivedAt,
            confirmedAt = p.confirmedAt ?: e.occurredAt,
            deadlineMillis = p.deadlineMillis,
            activity = p.activity,
            storeAddress = p.storeAddress,
        )
        // #688: PICKUP_CONFIRMED advances the leg anchor only (parked — keeps the departure point
        // fresh for the to-dropoff leg), alongside the existing liveness advance.
        val odo = event.metadata?.odometer
        val newCtx = ctx?.let { it.advance(e.occurredAt, odo).copy(legState = it.legState.advanceAnchor(odo)) }
        return FoldOutcome(context = newCtx, pickup = pickup)
    }
}
