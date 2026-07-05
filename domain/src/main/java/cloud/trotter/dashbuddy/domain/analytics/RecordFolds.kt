package cloud.trotter.dashbuddy.domain.analytics

import cloud.trotter.dashbuddy.domain.evaluation.NetProfit
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.SequencedAppEvent
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.OfferPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStartPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStopPayload
import cloud.trotter.dashbuddy.domain.state.Platform

/** Provenance of a delivery's realized pay (#314) — mirrors the DB column, owned here as SSOT. */
object PayBasis {
    /** The drop's apportioned share of a combined receipt (`DropPayApportioner`, #528). */
    const val DROP_SHARE = "DROP_SHARE"
    /** The whole receipt total, stamped on this drop (single-drop / collapsed-receipt shape). */
    const val RECEIPT_TOTAL = "RECEIPT_TOTAL"
    /**
     * A full-receipt stamp on a drop of an ALREADY-delivered (multi-delivery) job — dropped as
     * suspect (#653). An identity-less phantom drop in a receipted stack arrives with the whole
     * `parsedPay` and NO apportioned `dropRealizedPay` share, while its sibling drops' shares
     * already sum to that receipt; stamping the full receipt here would double-count the period
     * SUM. Fold-side defense-in-depth behind the #498/#653 upstream identity firewall — no
     * realizedPay/tip/base is recorded for such a row.
     */
    const val SUSPECT_FULL_RECEIPT = "SUSPECT_FULL_RECEIPT"
    /** No pay signal on the completion (receipt-skipped #596). */
    const val NONE = "NONE"
}

/**
 * Provenance of a delivery's frozen operating-cost-per-mile (#314) — an IMMUTABLE fact, never
 * re-costed when the economy editor changes. Owned here as the SSOT the fold and the DB share.
 */
object CostBasis {
    /**
     * From a closing offer's frozen `OfferEvaluation.operatingCostPerMile` in the same session.
     * Correlated at **session** granularity (not a per-job offer↔delivery join, which the event log
     * cannot express): operating-cost-per-mile derives from the one `UserEconomy` a session runs
     * under, so every offer in the session shares it and it is the correct basis for any delivery.
     */
    const val OFFER_FROZEN = "OFFER_FROZEN"

    /** Stamped self-contained into the completion event (reserved; no live source stamps it yet). */
    const val CAPTURED = "CAPTURED"

    /**
     * No offer basis available (a delivery in an offer-less/healed session, or one whose offer
     * predates the fold watermark) — the current economy's cpm was used. Honestly flagged; NOT
     * rebuild-stable (a later refold re-stamps it with that day's economy).
     */
    const val CURRENT_FALLBACK = "CURRENT_FALLBACK"

    /** Neither an offer basis nor a current economy — no net computable. */
    const val NONE = "NONE"
}

/**
 * A delivery read-model row as produced by the pure fold — the `:domain` shape the projector maps
 * 1:1 onto `DeliveryRecordEntity` at the transaction edge (`:domain` cannot see `:core:database`).
 */
data class DeliveryFold(
    val eventSequenceId: Long,
    val sessionId: String?,
    val platform: String,
    val jobId: String,
    val taskId: String,
    val storeName: String?,
    val customerHash: String?,
    val addressHash: String?,
    val phaseStartedAt: Long,
    val arrivedAt: Long?,
    val completedAt: Long,
    val deadlineMillis: Long?,
    val realizedPay: Double?,
    val payBasis: String,
    val tip: Double?,
    val basePay: Double?,
    val odometerAtCompletion: Double?,
    val realizedMiles: Double?,
    val realizedMinutes: Double?,
    val frozenCostPerMile: Double?,
    /** Fuel component of [frozenCostPerMile] (per-mile), frozen from the offer basis; null off it (#659). */
    val frozenFuelPerMile: Double?,
    /** Non-fuel component of [frozenCostPerMile] (per-mile), frozen from the offer basis; null off it (#659). */
    val frozenNonFuelPerMile: Double?,
    val netProfit: Double?,
    val costBasis: String,
)

/** An offer read-model row as produced by the pure fold — mapped 1:1 onto `OfferRecordEntity`. */
data class OfferFold(
    val eventSequenceId: Long,
    val sessionId: String?,
    val platform: String,
    val offerHash: String,
    val outcome: String,
    val presentedAt: Long,
    val decidedAt: Long,
    val payAmount: Double?,
    val distanceMiles: Double?,
    val itemCount: Int,
    val merchantName: String?,
    val score: Double?,
    val action: String?,
    val quality: String?,
    val estNetPay: Double?,
    val estDollarsPerHour: Double?,
    val estDollarsPerMile: Double?,
    val estTimeMinutes: Double?,
    val estOperatingCostPerMile: Double?,
    /** `fuelCostEstimate ÷ distanceMiles` (per-mile); null when distance ≤ 0. Rebuild-stable split hydration (#659). */
    val estFuelPerMile: Double?,
    /** `nonFuelCostEstimate ÷ distanceMiles` (per-mile); null when distance ≤ 0 (#659). */
    val estNonFuelPerMile: Double?,
)

/**
 * The result of folding ONE event: the updated session [context] (null when the event carries no
 * sessionId and so touches no session), any [delivery]/[offer] record it produced, and — for a
 * DASH_START whose session was not previously known — [freshSession] so the orchestrator can run
 * the inferred-close of prior open sessions. [skip] names why an event produced nothing (a null/
 * malformed payload), so the projector can count + WARN per batch.
 */
data class FoldOutcome(
    val context: SessionFoldContext? = null,
    val delivery: DeliveryFold? = null,
    val offer: OfferFold? = null,
    val freshSession: Boolean = false,
    val skip: String? = null,
)

/**
 * The pure event-sourced fold (#314): `(event, session context, current cpm) → record deltas`.
 *
 * No Android, no DB, no wall clock — driven only by the event's own timestamps and metadata, so a
 * backfill from watermark 0 and an incremental catch-up fold produce the same rows, and a
 * `projectorVersion` rebuild reproduces them. The orchestrator owns everything impure: paging,
 * context hydration from the record tables, the inferred-close DB query, and the records+watermark
 * transaction — and that transaction is what makes the fold **exactly-once**: delivery/offer rows
 * are keyed by source `sequenceId` (REPLACE-idempotent), but the folded session *counters* are not,
 * so re-processing an already-folded event WOULD double them — which the atomic watermark prevents
 * (a re-run over a drained log folds nothing; only new events past the watermark are ever folded).
 *
 * [currentCostPerMile] is the live economy's operating cost-per-mile, supplied by the orchestrator
 * only for the [CostBasis.CURRENT_FALLBACK] path (a delivery with no offer basis); it never
 * overrides an [CostBasis.OFFER_FROZEN] basis, so an economy edit cannot move a frozen row that has
 * one.
 */
object RecordFolds {

    fun foldEvent(
        event: SequencedAppEvent,
        context: SessionFoldContext?,
        currentCostPerMile: Double?,
    ): FoldOutcome {
        val e = event.event
        val sid = e.sessionId
        val odo = event.metadata?.odometer
        return when (e.type) {
            AppEventType.DASH_START -> foldDashStart(event, context)
            AppEventType.OFFER_ACCEPTED,
            AppEventType.OFFER_DECLINED,
            AppEventType.OFFER_TIMEOUT -> foldOffer(event, context)
            AppEventType.DELIVERY_COMPLETED -> foldDeliveryCompleted(event, context, currentCostPerMile)
            AppEventType.DASH_STOP -> foldDashStop(event, context)
            // Every other session-attributed event just advances the liveness/odometer anchors so
            // the open-session duration and lastOdometer stay honest; the watermark still moves past
            // them. Events with no session touch nothing.
            else -> {
                if (sid == null) return FoldOutcome(context = context)
                val ctx = resolveContext(context, sid, e.occurredAt)
                FoldOutcome(context = ctx.advance(e.occurredAt, odo))
            }
        }
    }

    private fun foldDashStart(event: SequencedAppEvent, context: SessionFoldContext?): FoldOutcome {
        val e = event.event
        val p = e.payload as? SessionStartPayload
            ?: return FoldOutcome(context = context, skip = "DASH_START: missing/malformed payload")
        val sid = e.sessionId ?: p.sessionId
            ?: return FoldOutcome(context = context, skip = "DASH_START: no sessionId")
        val platform = Platform.fromName(p.platform) ?: Platform.Unknown
        val odo = event.metadata?.odometer
        // A RECOVERY (or any duplicate) re-start of a session that was ALREADY STARTED WITH A REAL
        // PLATFORM must NOT clobber the original startedAt/platform/startOdometer — keep the earlier
        // anchor, only fill a still-null odometer and advance liveness. Not a fresh session ⇒ no
        // inferred-close. The `platform != Unknown` guard is load-bearing: a `_unknown` placeholder
        // (synthesized for a pre-DASH_START event, possibly PERSISTED and rehydrated across a batch
        // boundary and thus carrying started=true) must fall through to the upgrade arm below, or the
        // real platform/startedAt would be discarded and the session would stay `_unknown` forever.
        if (context != null && context.sessionId == sid && context.started && context.platform != Platform.Unknown) {
            return FoldOutcome(
                context = context.copy(
                    startOdometer = context.startOdometer ?: odo,
                    lastEventAt = maxOf(context.lastEventAt, e.occurredAt),
                    lastOdometer = odo ?: context.lastOdometer,
                    // Already a real start — keep its original startSource; only fill if somehow null.
                    startSource = context.startSource ?: p.source,
                ),
            )
        }
        // A placeholder synthesized for a session-scoped event ordered just ahead of DASH_START (in
        // the same batch OR persisted and rehydrated in a later one) is UPGRADED here with the real
        // platform/startedAt/startOdometer; its accumulated counters and liveness carry over. This
        // still counts as a fresh session (its first true start).
        if (context != null && context.sessionId == sid) {
            return FoldOutcome(
                context = context.copy(
                    platform = platform,
                    startedAt = p.startedAt,
                    startOdometer = context.startOdometer ?: odo,
                    lastEventAt = maxOf(context.lastEventAt, e.occurredAt),
                    lastOdometer = odo ?: context.lastOdometer,
                    started = true,
                    startSource = p.source,
                ),
                freshSession = true,
            )
        }
        return FoldOutcome(
            context = SessionFoldContext(
                sessionId = sid,
                platform = platform,
                startedAt = p.startedAt,
                lastEventAt = e.occurredAt,
                startOdometer = odo,
                lastOdometer = odo,
                started = true,
                startSource = p.source,
            ),
            freshSession = true,
        )
    }

    private fun foldOffer(event: SequencedAppEvent, context: SessionFoldContext?): FoldOutcome {
        val e = event.event
        val p = e.payload as? OfferPayload
            ?: return FoldOutcome(context = context, skip = "${e.type}: missing/malformed payload")
        val sid = e.sessionId
        val ctx = sid?.let { resolveContext(context, it, e.occurredAt) }
        val platformWire = ctx?.platform?.wire ?: Platform.Unknown.wire
        val eval = p.evaluation
        val parsed = p.parsedOffer
        // Per-mile fuel/non-fuel split from the SAME frozen evaluation the cpm comes from: the eval
        // carries route-total estimates + distance, so per-mile = estimate ÷ distanceMiles. Guard a
        // non-positive distance → null split (the delivery falls back to the 3-step waterfall). By
        // construction fuelPerMile + nonFuelPerMile ≈ operatingCostPerMile (both totals are that same
        // per-mile rate × distance), the invariant the waterfall relies on (#659).
        val evalDist = eval?.distanceMiles
        val fuelPerMile = if (eval != null && evalDist != null && evalDist > 0.0) eval.fuelCostEstimate / evalDist else null
        val nonFuelPerMile = if (eval != null && evalDist != null && evalDist > 0.0) eval.nonFuelCostEstimate / evalDist else null
        val offer = OfferFold(
            eventSequenceId = event.sequenceId,
            sessionId = sid,
            platform = platformWire,
            offerHash = p.offerHash,
            outcome = e.type.name,
            presentedAt = p.presentedAt,
            decidedAt = p.decidedAt,
            payAmount = parsed.payAmount,
            distanceMiles = parsed.distanceMiles,
            itemCount = parsed.itemCount,
            merchantName = eval?.merchantName ?: parsed.orders.firstOrNull()?.storeName,
            score = eval?.score,
            action = eval?.action?.name,
            quality = eval?.qualityLevel?.name,
            estNetPay = eval?.netPayAmount,
            estDollarsPerHour = eval?.dollarsPerHour,
            estDollarsPerMile = eval?.dollarsPerMile,
            estTimeMinutes = eval?.estimatedTimeMinutes,
            estOperatingCostPerMile = eval?.operatingCostPerMile,
            estFuelPerMile = fuelPerMile,
            estNonFuelPerMile = nonFuelPerMile,
        )
        val newCtx = ctx?.let {
            it.advance(e.occurredAt, event.metadata?.odometer)
                .bumpOutcome(e.type)
                // Capture the session-uniform frozen cpm AND its fuel/non-fuel split from any closing
                // offer that carries an evaluation (accepted/declined/timeout all reflect the same
                // economy). Keep-prior on a null (a distance-0 offer captures cpm but no split) so a
                // later good split isn't wiped — session-uniformity makes them numerically identical.
                .copy(
                    lastEvaluatedCostPerMile = eval?.operatingCostPerMile ?: it.lastEvaluatedCostPerMile,
                    lastEvaluatedFuelPerMile = fuelPerMile ?: it.lastEvaluatedFuelPerMile,
                    lastEvaluatedNonFuelPerMile = nonFuelPerMile ?: it.lastEvaluatedNonFuelPerMile,
                )
        }
        return FoldOutcome(context = newCtx, offer = offer)
    }

    private fun foldDeliveryCompleted(
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

        // #653: a full-receipt stamp on a drop of an ALREADY-delivered (multi-delivery) job is
        // SUSPECT. The signal the fold can see: this jobId already delivered ≥1 drop this session
        // (it is in the accumulated/hydrated `deliveredJobIds`), yet this completion carries the
        // whole `parsedPay` receipt with NO apportioned `dropRealizedPay` share — the identity-less
        // phantom shape (#498/#517/#518), excluded from the apportion denominator upstream. Its
        // sibling drops' shares already sum to that receipt, so stamping the full receipt here would
        // double-count the period SUM. Drop the pay/tip/base rather than inflating. A single-delivery
        // job (this jobId's first/only drop) is NOT flagged — a bare totalPay on the sole drop is
        // correct and keeps RECEIPT_TOTAL. Defense-in-depth behind the EffectMap identity firewall.
        val priorDeliveryForThisJob = ctx != null && p.jobId in ctx.deliveredJobIds
        val suspectFullReceipt =
            priorDeliveryForThisJob && p.parsedPay != null && p.dropRealizedPay == null

        // Pay + basis. dropRealizedPay is a per-drop share (possibly of a multi-drop receipt);
        // its absence with a bare totalPay means the whole receipt landed on this one drop.
        val realizedPay = if (suspectFullReceipt) null else p.dropRealizedPay ?: p.totalPay
        val payBasis = when {
            suspectFullReceipt -> PayBasis.SUSPECT_FULL_RECEIPT
            p.dropRealizedPay != null -> PayBasis.DROP_SHARE
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
            (p.dropRealizedPay == null || kotlin.math.abs(p.dropRealizedPay - receipt.total) < 0.005)
        val tip = if (soleDrop) receipt?.totalTip else null
        val basePay = if (soleDrop) receipt?.totalBasePay else null

        // Partition deltas. Anchor = the previous completion (or DASH_START) in the same session.
        // Floor the per-row delta at 0 like the session-level SUM (MAX(…,0)): a mid-session odometer
        // reset yields a NEGATIVE delta, which would otherwise INFLATE this row's netProfit
        // (pay − negativeMiles × cpm). The next drop re-anchors off this (lower) reading naturally.
        val prevOdo = ctx?.prevDropOdometer ?: ctx?.startOdometer
        val realizedMiles = if (odo != null && prevOdo != null) (odo - prevOdo).coerceAtLeast(0.0) else null
        val prevAt = ctx?.prevDropAt ?: ctx?.startedAt
        val realizedMinutes = if (prevAt != null) (completedAt - prevAt) / 60_000.0 else null

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
            basePay = basePay,
            odometerAtCompletion = odo,
            realizedMiles = realizedMiles,
            realizedMinutes = realizedMinutes,
            frozenCostPerMile = frozenCpm,
            frozenFuelPerMile = frozenFuelPerMile,
            frozenNonFuelPerMile = frozenNonFuelPerMile,
            netProfit = netProfit,
            costBasis = costBasis,
        )

        val newCtx = ctx?.copy(
            deliveries = ctx.deliveries + 1,
            deliveredJobIds = ctx.deliveredJobIds + p.jobId,
            // Advance the miles anchor only when this drop had an odometer, so a null-odometer drop
            // folds its miles into the next drop instead of resetting the partition; the time anchor
            // always advances (time is always known).
            prevDropOdometer = odo ?: ctx.prevDropOdometer,
            prevDropAt = completedAt,
            lastEventAt = maxOf(ctx.lastEventAt, e.occurredAt),
            lastOdometer = odo ?: ctx.lastOdometer,
        )
        return FoldOutcome(context = newCtx, delivery = delivery)
    }

    private fun foldDashStop(event: SequencedAppEvent, context: SessionFoldContext?): FoldOutcome {
        val e = event.event
        val p = e.payload as? SessionStopPayload
            ?: return FoldOutcome(context = context, skip = "DASH_STOP: missing/malformed payload")
        val sid = e.sessionId ?: p.sessionId
            ?: return FoldOutcome(context = context, skip = "DASH_STOP: no sessionId")
        val ctx = resolveContext(context, sid, e.occurredAt, platformName = p.platform)
        val odo = event.metadata?.odometer
        return FoldOutcome(
            context = ctx.copy(
                endedAt = p.endedAt,
                endSource = p.source,
                reportedEarnings = p.totalEarnings ?: ctx.reportedEarnings,
                reportedDurationMillis = p.sessionDurationMillis ?: ctx.reportedDurationMillis,
                lastEventAt = maxOf(ctx.lastEventAt, e.occurredAt),
                lastOdometer = odo ?: ctx.lastOdometer,
            ),
        )
    }

    /**
     * The in-memory/hydrated context for [sid], or a synthesized `_unknown`-platform context when a
     * session-scoped event has no known DASH_START (fold started mid-session past the watermark, or
     * a lost start). A synthesized session degrades to a null-odometer / _unknown-platform row —
     * never a wrong one. [platformName] refines an `_unknown` platform when the event itself carries
     * one (DASH_STOP's #314 stamp) — whether that `_unknown` context is being synthesized here for
     * the first time, or was already synthesized by an EARLIER event in the session and is only now
     * being hydrated/matched by [sid] (a skipped/malformed DASH_START leaves the session `_unknown`
     * until its DASH_STOP arrives with the real platform; without this, the upgrade path only fired
     * for a brand-new context and an already-`_unknown` session stayed `_unknown` forever). Never
     * clobbers a REAL platform — only an `Unknown` one is eligible — and a [platformName] that itself
     * fails to resolve (or resolves back to [Platform.Unknown]) leaves the context untouched.
     */
    private fun resolveContext(
        context: SessionFoldContext?,
        sid: String,
        occurredAt: Long,
        platformName: String? = null,
    ): SessionFoldContext {
        val existing = context?.takeIf { it.sessionId == sid }
        if (existing != null) {
            if (existing.platform == Platform.Unknown && platformName != null) {
                val resolved = Platform.fromName(platformName)
                if (resolved != null && resolved != Platform.Unknown) {
                    return existing.copy(platform = resolved)
                }
            }
            return existing
        }
        return SessionFoldContext(
            sessionId = sid,
            platform = platformName?.let { Platform.fromName(it) } ?: Platform.Unknown,
            startedAt = occurredAt,
            lastEventAt = occurredAt,
        )
    }

    private fun SessionFoldContext.advance(occurredAt: Long, odometer: Double?): SessionFoldContext =
        copy(
            lastEventAt = maxOf(lastEventAt, occurredAt),
            lastOdometer = odometer ?: lastOdometer,
        )

    private fun SessionFoldContext.bumpOutcome(type: AppEventType): SessionFoldContext = when (type) {
        AppEventType.OFFER_ACCEPTED -> copy(offersAccepted = offersAccepted + 1)
        AppEventType.OFFER_DECLINED -> copy(offersDeclined = offersDeclined + 1)
        AppEventType.OFFER_TIMEOUT -> copy(offersTimeout = offersTimeout + 1)
        else -> this
    }
}
