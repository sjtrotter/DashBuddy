package cloud.trotter.dashbuddy.domain.analytics

import cloud.trotter.dashbuddy.domain.evaluation.OfferAction
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import cloud.trotter.dashbuddy.domain.evaluation.OfferQuality
import cloud.trotter.dashbuddy.domain.model.event.AppEvent
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.EventMetadata
import cloud.trotter.dashbuddy.domain.model.event.SequencedAppEvent
import cloud.trotter.dashbuddy.domain.model.event.payload.AppEventPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.OfferPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionEndSource
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStartPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStartSource
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStopPayload
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPay
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPayItem
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure fold unit tests (#314, `:domain:test`) for [RecordFolds] — no Android, no DB, no wall clock.
 * The context is threaded by hand exactly as the orchestrator threads it in-batch, so these assert
 * the fold's record values and the session accumulator directly.
 */
class RecordFoldsTest {

    private var seq = 0L
    private val cpm = 0.30

    private fun ev(
        type: AppEventType,
        sessionId: String?,
        at: Long,
        payload: AppEventPayload?,
        odometer: Double? = null,
    ) = SequencedAppEvent(
        sequenceId = ++seq,
        event = AppEvent(type = type, occurredAt = at, sessionId = sessionId, payload = payload),
        metadata = odometer?.let { EventMetadata(odometer = it) },
    )

    private fun dashStart(sid: String, at: Long, odo: Double?, source: String = SessionStartSource.INTERACTION) =
        ev(
            AppEventType.DASH_START, sid, at,
            SessionStartPayload(sid, Platform.DoorDash.name, startedAt = at, source = source, startScreen = "x"),
            odometer = odo,
        )

    private fun eval(
        net: Double,
        dist: Double,
        opCpm: Double,
        fuelPerMile: Double = 0.0,
        nonFuelPerMile: Double = opCpm - fuelPerMile,
    ) = OfferEvaluation(
        action = OfferAction.ACCEPT,
        score = 80.0,
        qualityLevel = OfferQuality.GOOD,
        payAmount = net + dist * opCpm,
        // Route-total estimates — the fold divides by distanceMiles to recover the per-mile split.
        fuelCostEstimate = fuelPerMile * dist,
        nonFuelCostEstimate = nonFuelPerMile * dist,
        operatingCostPerMile = opCpm,
        netPayAmount = net,
        distanceMiles = dist,
        dollarsPerMile = if (dist > 0) net / dist else 0.0,
        dollarsPerHour = 20.0,
        estimatedTimeMinutes = 15.0,
        itemCount = 1.0,
        merchantName = "StoreX",
    )

    private fun offerAccepted(sid: String, at: Long, hash: String, evaluation: OfferEvaluation?) = ev(
        AppEventType.OFFER_ACCEPTED, sid, at,
        OfferPayload(
            offerHash = hash,
            parsedOffer = ParsedOffer(offerHash = hash, payAmount = 12.0, distanceMiles = 3.0, itemCount = 1),
            evaluation = evaluation,
            outcome = AppEventType.OFFER_ACCEPTED,
            presentedAt = at - 30_000,
            decidedAt = at,
            returnFlow = Flow.Idle,
        ),
    )

    private fun parsedPay(base: Double, tip: Double, tipStore: String = "StoreX") = ParsedPay(
        appPayComponents = listOf(ParsedPayItem(type = "Base Pay", amount = base)),
        customerTips = listOf(ParsedPayItem(type = tipStore, amount = tip)),
    )

    private fun delivery(
        sid: String?,
        at: Long,
        jobId: String,
        taskId: String,
        totalPay: Double? = null,
        dropRealizedPay: Double? = null,
        parsedPay: ParsedPay? = null,
        odo: Double? = null,
        phaseStartedAt: Long = at - 600_000,
        // Identity-bearing by default (a real delivered drop). A #498/#653 phantom payload has
        // BOTH hashes null (the payload copies the task's null hashes) — pass true to model it.
        identityLess: Boolean = false,
    ) = ev(
        AppEventType.DELIVERY_COMPLETED, sid, at,
        DeliveryPayload(
            jobId = jobId, taskId = taskId, storeName = "StoreX",
            customerHash = if (identityLess) null else "cust-$taskId",
            addressHash = if (identityLess) null else "addr-$taskId",
            phaseStartedAt = phaseStartedAt, arrivedAt = at - 120_000, completedAt = at,
            totalPay = totalPay, parsedPay = parsedPay, dropRealizedPay = dropRealizedPay,
        ),
        odometer = odo,
    )

    private fun dashStop(sid: String, at: Long, odo: Double?, earnings: Double?, platform: String? = null) = ev(
        AppEventType.DASH_STOP, sid, at,
        SessionStopPayload(
            sid, endedAt = at, source = SessionEndSource.SUMMARY_SCREEN, totalEarnings = earnings,
            platform = platform,
        ),
        odometer = odo,
    )

    // Thread a session's events, returning the outcomes in order and the final context.
    private fun foldSession(events: List<SequencedAppEvent>, currentCpm: Double? = null): Pair<List<FoldOutcome>, SessionFoldContext?> {
        var ctx: SessionFoldContext? = null
        val outcomes = events.map { e ->
            val o = RecordFolds.foldEvent(e, ctx, currentCpm)
            ctx = o.context ?: ctx
            o
        }
        return outcomes to ctx
    }

    @Test
    fun `full single-delivery session folds to expected records`() {
        val s = "S1"
        val (outcomes, ctx) = foldSession(
            listOf(
                dashStart(s, 1_000, odo = 100.0),
                offerAccepted(s, 2_000, "h1", eval(net = 9.0, dist = 3.0, opCpm = 0.25)),
                delivery(s, 3_000, "J1", "T1", totalPay = 10.0, parsedPay = parsedPay(base = 7.0, tip = 3.0), odo = 105.0),
                dashStop(s, 4_000, odo = 110.0, earnings = 10.0),
            ),
        )

        val d = outcomes[2].delivery!!
        assertEquals("J1", d.jobId)
        assertEquals(10.0, d.realizedPay!!, 1e-9)
        assertEquals(PayBasis.RECEIPT_TOTAL, d.payBasis)
        assertEquals(5.0, d.realizedMiles!!, 1e-9)               // 105 − 100
        assertEquals((3_000 - 1_000) / 60_000.0, d.realizedMinutes!!, 1e-9)
        assertEquals(0.25, d.frozenCostPerMile!!, 1e-9)          // from the accepted offer's eval
        assertEquals(CostBasis.OFFER_FROZEN, d.costBasis)
        assertEquals(10.0 - 5.0 * 0.25, d.netProfit!!, 1e-9)     // 8.75
        assertEquals(3.0, d.tip!!, 1e-9)                          // single drop ⇒ tip from parsedPay
        assertEquals(7.0, d.basePay!!, 1e-9)

        val offer = outcomes[1].offer!!
        assertEquals("OFFER_ACCEPTED", offer.outcome)
        assertEquals(0.25, offer.estOperatingCostPerMile!!, 1e-9)
        assertEquals("doordash", offer.platform)

        assertEquals(1, ctx!!.deliveries)
        assertEquals(1, ctx.jobsCompleted)
        assertEquals(1, ctx.offersAccepted)
        assertEquals(1, ctx.offersReceived)
        assertEquals(100.0, ctx.startOdometer!!, 1e-9)
        assertEquals(110.0, ctx.lastOdometer!!, 1e-9)
        assertEquals(4_000L, ctx.endedAt)
        assertEquals(SessionEndSource.SUMMARY_SCREEN, ctx.endSource)
        assertEquals(10.0, ctx.reportedEarnings!!, 1e-9)
        assertEquals("doordash", ctx.platform.wire)
    }

    @Test
    fun `frozen economy - offer basis wins, no offer falls back to current, neither is NONE`() {
        val s = "S2"
        // Delivery WITH a preceding accepted offer → OFFER_FROZEN.
        val (withOffer, _) = foldSession(
            listOf(
                dashStart(s, 1_000, odo = 0.0),
                offerAccepted(s, 2_000, "h", eval(net = 8.0, dist = 4.0, opCpm = 0.40)),
                delivery(s, 3_000, "J1", "T1", totalPay = 10.0, odo = 4.0),
            ),
            currentCpm = 0.99,
        )
        val d1 = withOffer[2].delivery!!
        assertEquals(CostBasis.OFFER_FROZEN, d1.costBasis)
        assertEquals(0.40, d1.frozenCostPerMile!!, 1e-9)

        // Delivery with NO offer in the session but a current economy → CURRENT_FALLBACK.
        val (noOffer, _) = foldSession(
            listOf(
                dashStart(s, 1_000, odo = 0.0),
                delivery(s, 3_000, "J1", "T1", totalPay = 10.0, odo = 4.0),
            ),
            currentCpm = 0.50,
        )
        val d2 = noOffer[1].delivery!!
        assertEquals(CostBasis.CURRENT_FALLBACK, d2.costBasis)
        assertEquals(0.50, d2.frozenCostPerMile!!, 1e-9)
        assertEquals(10.0 - 4.0 * 0.50, d2.netProfit!!, 1e-9)

        // No offer AND no current economy → NONE, null net.
        val (none, _) = foldSession(
            listOf(
                dashStart(s, 1_000, odo = 0.0),
                delivery(s, 3_000, "J1", "T1", totalPay = 10.0, odo = 4.0),
            ),
            currentCpm = null,
        )
        val d3 = none[1].delivery!!
        assertEquals(CostBasis.NONE, d3.costBasis)
        assertNull(d3.frozenCostPerMile)
        assertNull(d3.netProfit)
    }

    @Test
    fun `fuel and non-fuel per-mile split is extracted from the offer eval and frozen on the delivery`() {
        val s = "F1"
        val (outcomes, _) = foldSession(
            listOf(
                dashStart(s, 1_000, odo = 100.0),
                // opCpm 0.25 split as fuel 0.10 / non-fuel 0.15 per mile over a 4-mile route.
                offerAccepted(s, 2_000, "h1", eval(net = 9.0, dist = 4.0, opCpm = 0.25, fuelPerMile = 0.10)),
                delivery(s, 3_000, "J1", "T1", totalPay = 10.0, odo = 105.0),
            ),
        )

        // Offer row carries the per-mile split (route-total estimate ÷ distanceMiles).
        val offer = outcomes[1].offer!!
        assertEquals(0.10, offer.estFuelPerMile!!, 1e-9)
        assertEquals(0.15, offer.estNonFuelPerMile!!, 1e-9)

        // Delivery freezes the same split off the OFFER_FROZEN basis.
        val d = outcomes[2].delivery!!
        assertEquals(CostBasis.OFFER_FROZEN, d.costBasis)
        assertEquals(0.10, d.frozenFuelPerMile!!, 1e-9)
        assertEquals(0.15, d.frozenNonFuelPerMile!!, 1e-9)
        // The invariant the 4-step waterfall relies on: fuel + non-fuel ≈ frozenCostPerMile.
        assertEquals(d.frozenCostPerMile!!, d.frozenFuelPerMile!! + d.frozenNonFuelPerMile!!, 1e-9)
    }

    @Test
    fun `a distance-0 offer eval captures cpm but null fuel-non-fuel split (guarded division)`() {
        val s = "F2"
        // An eval whose distanceMiles is 0 — the per-mile split would divide by zero, so it stays null
        // while operatingCostPerMile is still captured. The delivery keeps OFFER_FROZEN cpm, null split
        // (→ the waterfall falls back to 3-step for this row).
        val zeroDistEval = eval(net = 5.0, dist = 0.0, opCpm = 0.30, fuelPerMile = 0.12)
        val (outcomes, _) = foldSession(
            listOf(
                dashStart(s, 1_000, odo = 100.0),
                offerAccepted(s, 2_000, "h1", zeroDistEval),
                delivery(s, 3_000, "J1", "T1", totalPay = 10.0, odo = 105.0),
            ),
        )
        val offer = outcomes[1].offer!!
        assertNull(offer.estFuelPerMile)
        assertNull(offer.estNonFuelPerMile)
        assertEquals(0.30, offer.estOperatingCostPerMile!!, 1e-9)

        val d = outcomes[2].delivery!!
        assertEquals(CostBasis.OFFER_FROZEN, d.costBasis)
        assertEquals(0.30, d.frozenCostPerMile!!, 1e-9)
        assertNull("no split on a distance-0 offer", d.frozenFuelPerMile)
        assertNull(d.frozenNonFuelPerMile)
    }

    @Test
    fun `CURRENT_FALLBACK and NONE deliveries carry no fuel-non-fuel split`() {
        val s = "F3"
        // No offer in the session → CURRENT_FALLBACK cpm, but the live economy read supplies a bare
        // cpm, not its split → null fuel/non-fuel.
        val (fallback, _) = foldSession(
            listOf(
                dashStart(s, 1_000, odo = 0.0),
                delivery(s, 3_000, "J1", "T1", totalPay = 10.0, odo = 4.0),
            ),
            currentCpm = 0.50,
        )
        val dFallback = fallback[1].delivery!!
        assertEquals(CostBasis.CURRENT_FALLBACK, dFallback.costBasis)
        assertEquals(0.50, dFallback.frozenCostPerMile!!, 1e-9)
        assertNull(dFallback.frozenFuelPerMile)
        assertNull(dFallback.frozenNonFuelPerMile)

        // No offer AND no current economy → NONE, null cpm and null split.
        val (none, _) = foldSession(
            listOf(
                dashStart(s, 1_000, odo = 0.0),
                delivery(s, 3_000, "J1", "T1", totalPay = 10.0, odo = 4.0),
            ),
            currentCpm = null,
        )
        val dNone = none[1].delivery!!
        assertEquals(CostBasis.NONE, dNone.costBasis)
        assertNull(dNone.frozenFuelPerMile)
        assertNull(dNone.frozenNonFuelPerMile)
    }

    @Test
    fun `startSource is the DASH_START source for a real start and null for a synthesized placeholder`() {
        // A real DASH_START stamps startSource with the payload's source.
        val realCtx = RecordFolds.foldEvent(
            dashStart("G1", 1_000, odo = 0.0, source = SessionStartSource.INTERACTION), null, null,
        ).context
        assertEquals(SessionStartSource.INTERACTION, realCtx!!.startSource)

        // An offer arriving before any DASH_START synthesizes an `_unknown` placeholder — startSource
        // stays null (the retro-finding-2 signal that this session has NOT seen a real start).
        val placeholderCtx = RecordFolds.foldEvent(
            offerAccepted("G2", 2_000, "h", eval(net = 5.0, dist = 2.0, opCpm = 0.3)), null, null,
        ).context
        assertEquals(Platform.Unknown, placeholderCtx!!.platform)
        assertNull("a synthesized placeholder has no startSource", placeholderCtx.startSource)

        // The DASH_START that later lands upgrades the placeholder and stamps startSource.
        val upgraded = RecordFolds.foldEvent(
            dashStart("G2", 2_100, odo = 10.0, source = SessionStartSource.INTERACTION), placeholderCtx, null,
        ).context
        assertEquals(SessionStartSource.INTERACTION, upgraded!!.startSource)
        assertEquals(2_100L, upgraded.startedAt)
    }

    @Test
    fun `RECOVERY re-start keeps the original startSource`() {
        var ctx: SessionFoldContext? = null
        ctx = RecordFolds.foldEvent(
            dashStart("G3", 1_000, odo = 50.0, source = SessionStartSource.INTERACTION), ctx, null,
        ).context
        ctx = RecordFolds.foldEvent(
            dashStart("G3", 9_000, odo = 60.0, source = SessionStartSource.RECOVERY), ctx, null,
        ).context
        assertEquals("original interaction source preserved", SessionStartSource.INTERACTION, ctx!!.startSource)
    }

    @Test
    fun `RECOVERY re-start does not clobber startedAt or startOdometer`() {
        val s = "S3"
        var ctx: SessionFoldContext? = null
        ctx = RecordFolds.foldEvent(dashStart(s, 1_000, odo = 50.0), ctx, null).context
        // A crash-recovery re-start of the SAME session at a later time with a later odometer.
        ctx = RecordFolds.foldEvent(dashStart(s, 9_000, odo = 60.0, source = SessionStartSource.RECOVERY), ctx, null).context
        assertEquals("startedAt is the original", 1_000L, ctx!!.startedAt)
        assertEquals("startOdometer is the original", 50.0, ctx.startOdometer!!, 1e-9)
        assertEquals("lastEventAt advances", 9_000L, ctx.lastEventAt)
        assertEquals("lastOdometer advances", 60.0, ctx.lastOdometer!!, 1e-9)
    }

    @Test
    fun `RECOVERY re-start fills a still-null startOdometer`() {
        val s = "S3b"
        var ctx: SessionFoldContext? = null
        ctx = RecordFolds.foldEvent(dashStart(s, 1_000, odo = null), ctx, null).context
        ctx = RecordFolds.foldEvent(dashStart(s, 2_000, odo = 42.0, source = SessionStartSource.RECOVERY), ctx, null).context
        assertEquals(42.0, ctx!!.startOdometer!!, 1e-9)
    }

    @Test
    fun `stacked dropRealizedPay rows sum to the receipt and carry no per-drop tip split`() {
        val s = "S4"
        // Two drops of one stacked job; the apportioner (upstream) already split the $20 receipt.
        val (outcomes, ctx) = foldSession(
            listOf(
                dashStart(s, 1_000, odo = 0.0),
                offerAccepted(s, 1_500, "h", eval(net = 15.0, dist = 5.0, opCpm = 0.20)),
                delivery(s, 3_000, "J1", "T1", dropRealizedPay = 11.34, parsedPay = parsedPay(8.0, 12.0), odo = 5.0),
                delivery(s, 4_000, "J1", "T2", dropRealizedPay = 8.66, parsedPay = parsedPay(8.0, 12.0), odo = 8.0),
            ),
        )
        val d1 = outcomes[2].delivery!!
        val d2 = outcomes[3].delivery!!
        assertEquals(PayBasis.DROP_SHARE, d1.payBasis)
        assertEquals(20.0, d1.realizedPay!! + d2.realizedPay!!, 1e-9)   // Σ shares == receipt
        assertNull("no per-drop tip split on a stack", d1.tip)
        assertNull(d1.basePay)
        assertEquals("stacked job counted once", 1, ctx!!.jobsCompleted)
        assertEquals(2, ctx.deliveries)
    }

    @Test
    fun `a full-receipt phantom drop in a receipted multi-delivery job is SUSPECT, no period double-count (#653)`() {
        val s = "S4b"
        // A receipted stack: two identity-bearing drops (A, B) split the $20 receipt via the
        // apportioner (their dropRealizedPay shares sum to it). A third, identity-less phantom drop
        // (C) of the SAME job slips through carrying the WHOLE receipt (parsedPay) with NO share —
        // the #498/#517/#518 shape. Stamping the full receipt on C would double-count the period SUM.
        val (outcomes, ctx) = foldSession(
            listOf(
                dashStart(s, 1_000, odo = 0.0),
                offerAccepted(s, 1_500, "h", eval(net = 15.0, dist = 5.0, opCpm = 0.20)),
                delivery(s, 3_000, "J1", "T1", dropRealizedPay = 11.34, parsedPay = parsedPay(8.0, 12.0), odo = 5.0),
                delivery(s, 4_000, "J1", "T2", dropRealizedPay = 8.66, parsedPay = parsedPay(8.0, 12.0), odo = 8.0),
                // Phantom: IDENTITY-LESS (both hashes null — the real #498 class), full receipt,
                // NO apportioned share, and this job already delivered ≥1 drop.
                delivery(s, 5_000, "J1", "T3", totalPay = 20.0, dropRealizedPay = null, parsedPay = parsedPay(8.0, 12.0), odo = 9.0, identityLess = true),
            ),
        )
        val a = outcomes[2].delivery!!
        val b = outcomes[3].delivery!!
        val phantom = outcomes[4].delivery!!

        assertEquals(PayBasis.SUSPECT_FULL_RECEIPT, phantom.payBasis)
        assertNull("suspect drop records no realized pay", phantom.realizedPay)
        assertNull("suspect drop records no tip", phantom.tip)
        assertNull("suspect drop records no base pay", phantom.basePay)
        assertNull("no realized pay ⇒ no net", phantom.netProfit)

        // The no-double-count invariant: the period SUM over the job's drops equals the receipt.
        val periodSum = outcomes.mapNotNull { it.delivery?.realizedPay }.sum()
        assertEquals("Σ realizedPay == receipt (phantom adds nothing)", 20.0, periodSum, 1e-9)
        assertEquals("siblings still carry their apportioned shares", 20.0, a.realizedPay!! + b.realizedPay!!, 1e-9)
    }

    @Test
    fun `a single-delivery job with a bare receipt keeps RECEIPT_TOTAL, not SUSPECT (#653 control)`() {
        val s = "S4c"
        // First (and only) drop of its job: parsedPay present, no dropRealizedPay share. This is the
        // legitimate sole-drop shape — the whole receipt IS this drop's pay. Must NOT be flagged.
        val (outcomes, _) = foldSession(
            listOf(
                dashStart(s, 1_000, odo = 0.0),
                delivery(s, 3_000, "J9", "T9", totalPay = 12.0, dropRealizedPay = null, parsedPay = parsedPay(9.0, 3.0), odo = 5.0),
            ),
        )
        val d = outcomes[1].delivery!!
        assertEquals(PayBasis.RECEIPT_TOTAL, d.payBasis)
        assertEquals(12.0, d.realizedPay!!, 1e-9)
        assertEquals("sole drop carries the receipt tip", 3.0, d.tip!!, 1e-9)
    }

    @Test
    fun `pre-#528 receipted stack — the identity-BEARING full-receipt last drop keeps RECEIPT_TOTAL (#653 F1)`() {
        val s = "S4d"
        // The HISTORICAL (05-17 → 07-03, pre-#528) mint shape for a legitimate receipted 2-drop
        // stack: N−1 null-pay rows + ONE identity-bearing row carrying the whole receipt — no
        // dropRealizedPay anywhere (the field didn't exist yet). That receipted drop is the job's
        // ONLY money and normally folds LAST (jobId already in deliveredJobIds). The v3 refold
        // must keep it RECEIPT_TOTAL with full pay — NOT flag it SUSPECT (which would zero the
        // entire pre-#528 field campaign's stack pay). The identity discriminator is what saves it.
        val (outcomes, _) = foldSession(
            listOf(
                dashStart(s, 1_000, odo = 0.0),
                offerAccepted(s, 1_500, "h", eval(net = 15.0, dist = 5.0, opCpm = 0.20)),
                // Drop A: identity-bearing, NO pay signal at all (the pre-#528 non-announced drop).
                delivery(s, 3_000, "J1", "TA", odo = 5.0),
                // Drop B: identity-BEARING, whole receipt (parsedPay + totalPay), no share field.
                delivery(s, 4_000, "J1", "TB", totalPay = 20.0, dropRealizedPay = null, parsedPay = parsedPay(8.0, 12.0), odo = 8.0),
            ),
        )
        val a = outcomes[2].delivery!!
        val b = outcomes[3].delivery!!

        assertEquals("non-announced sibling has no pay signal", PayBasis.NONE, a.payBasis)
        assertNull(a.realizedPay)

        assertEquals("identity-bearing full-receipt drop is LEGITIMATE, not suspect", PayBasis.RECEIPT_TOTAL, b.payBasis)
        assertEquals(20.0, b.realizedPay!!, 1e-9)
        assertEquals("it carries the receipt tip (sole money row)", 12.0, b.tip!!, 1e-9)
        assertEquals(8.0, b.basePay!!, 1e-9)

        // Period SUM == receipt: the stack's money is counted exactly once.
        val periodSum = outcomes.mapNotNull { it.delivery?.realizedPay }.sum()
        assertEquals("Σ realizedPay == receipt (counted once, not zeroed)", 20.0, periodSum, 1e-9)
    }

    @Test
    fun `partition miles sum to the session odometer delta`() {
        val s = "S5"
        val (outcomes, _) = foldSession(
            listOf(
                dashStart(s, 1_000, odo = 100.0),
                delivery(s, 2_000, "J1", "T1", totalPay = 5.0, odo = 104.0), // 4 mi
                delivery(s, 3_000, "J2", "T2", totalPay = 5.0, odo = 111.0), // 7 mi
                delivery(s, 4_000, "J3", "T3", totalPay = 5.0, odo = 120.0), // 9 mi
            ),
        )
        val miles = outcomes.mapNotNull { it.delivery?.realizedMiles }
        assertEquals(listOf(4.0, 7.0, 9.0), miles)
        assertEquals("Σ per-drop miles == last − start odo", 20.0, miles.sum(), 1e-9)
    }

    @Test
    fun `null odometer drop yields null miles and folds its distance into the next drop`() {
        val s = "S6"
        val (outcomes, _) = foldSession(
            listOf(
                dashStart(s, 1_000, odo = 100.0),
                delivery(s, 2_000, "J1", "T1", totalPay = 5.0, odo = null),   // no odo → null miles
                delivery(s, 3_000, "J2", "T2", totalPay = 5.0, odo = 115.0),  // spans the gap: 115 − 100
            ),
        )
        assertNull(outcomes[1].delivery!!.realizedMiles)
        assertEquals(15.0, outcomes[2].delivery!!.realizedMiles!!, 1e-9)
    }

    @Test
    fun `a mid-session odometer reset floors the per-drop miles at zero (never negative)`() {
        val s = "S6b"
        val (outcomes, _) = foldSession(
            listOf(
                dashStart(s, 1_000, odo = 100.0),
                delivery(s, 2_000, "J1", "T1", totalPay = 5.0, odo = 105.0), // +5
                delivery(s, 3_000, "J2", "T2", totalPay = 5.0, odo = 90.0),  // odometer reset: −15 → 0
                delivery(s, 4_000, "J3", "T3", totalPay = 5.0, odo = 95.0),  // re-anchored: 95 − 90 = 5
            ),
        )
        assertEquals(5.0, outcomes[1].delivery!!.realizedMiles!!, 1e-9)
        assertEquals("negative delta floored, never inflates netProfit", 0.0, outcomes[2].delivery!!.realizedMiles!!, 1e-9)
        assertEquals(5.0, outcomes[3].delivery!!.realizedMiles!!, 1e-9)
    }

    @Test
    fun `a malformed payload is skipped and reported, not folded`() {
        val s = "S7"
        val bad = SequencedAppEvent(
            sequenceId = ++seq,
            event = AppEvent(AppEventType.DELIVERY_COMPLETED, occurredAt = 2_000, sessionId = s, payload = null),
        )
        val ctx = RecordFolds.foldEvent(dashStart(s, 1_000, odo = 0.0), null, null).context
        val out = RecordFolds.foldEvent(bad, ctx, null)
        assertNull(out.delivery)
        assertEquals("context is preserved unchanged", ctx, out.context)
        org.junit.Assert.assertNotNull(out.skip)
    }

    @Test
    fun `a delivery with no session id produces an unknown-platform row and no context`() {
        val out = RecordFolds.foldEvent(
            delivery(sid = null, at = 2_000, jobId = "J1", taskId = "T1", totalPay = 5.0, odo = 4.0),
            context = null,
            currentCostPerMile = null,
        )
        assertEquals(Platform.Unknown.wire, out.delivery!!.platform)
        assertNull(out.delivery!!.sessionId)
        assertNull("no session context is created for a session-less event", out.context)
    }

    @Test
    fun `DASH_STOP's platform stamp upgrades an _unknown context left by a skipped DASH_START`() {
        val s = "S8"
        // A malformed DASH_START (no payload) is skipped — no context is created.
        val badStart = SequencedAppEvent(
            sequenceId = ++seq,
            event = AppEvent(AppEventType.DASH_START, occurredAt = 1_000, sessionId = s, payload = null),
        )
        val skipOut = RecordFolds.foldEvent(badStart, context = null, currentCostPerMile = null)
        assertNotNull(skipOut.skip)
        assertNull(skipOut.context)

        // A mid-session offer (no known DASH_START) synthesizes an `_unknown` placeholder context.
        val offerOut = RecordFolds.foldEvent(
            offerAccepted(s, 2_000, "h", eval(net = 5.0, dist = 2.0, opCpm = 0.3)),
            context = skipOut.context,
            currentCostPerMile = null,
        )
        val unknownCtx = offerOut.context
        assertEquals(Platform.Unknown, unknownCtx!!.platform)

        // DASH_STOP arrives carrying the real platform (#314 stamp) — the `_unknown` context is
        // upgraded, not left stuck forever.
        val stopOut = RecordFolds.foldEvent(
            dashStop(s, 3_000, odo = null, earnings = 10.0, platform = Platform.DoorDash.name),
            context = unknownCtx,
            currentCostPerMile = null,
        )
        assertEquals("DASH_STOP's platform stamp refines an _unknown session", Platform.DoorDash, stopOut.context!!.platform)
        assertEquals("doordash", stopOut.context!!.platform.wire)
    }

    @Test
    fun `DASH_STOP's platform stamp does NOT clobber an already-real platform`() {
        val s = "S9"
        val (outcomes, ctx) = foldSession(
            listOf(
                dashStart(s, 1_000, odo = 0.0),
                // A malformed/mismatched platform stamp on DASH_STOP must never override a real,
                // already-established session platform.
                dashStop(s, 2_000, odo = null, earnings = 5.0, platform = Platform.Uber.name),
            ),
        )
        assertEquals("real platform from DASH_START is preserved", Platform.DoorDash, outcomes.last().context!!.platform)
        assertEquals(Platform.DoorDash, ctx!!.platform)
    }
}
