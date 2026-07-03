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

    private fun eval(net: Double, dist: Double, opCpm: Double) = OfferEvaluation(
        action = OfferAction.ACCEPT,
        score = 80.0,
        qualityLevel = OfferQuality.GOOD,
        payAmount = net + dist * opCpm,
        fuelCostEstimate = 0.0,
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
    ) = ev(
        AppEventType.DELIVERY_COMPLETED, sid, at,
        DeliveryPayload(
            jobId = jobId, taskId = taskId, storeName = "StoreX",
            customerHash = "cust-$taskId", addressHash = "addr-$taskId",
            phaseStartedAt = phaseStartedAt, arrivedAt = at - 120_000, completedAt = at,
            totalPay = totalPay, parsedPay = parsedPay, dropRealizedPay = dropRealizedPay,
        ),
        odometer = odo,
    )

    private fun dashStop(sid: String, at: Long, odo: Double?, earnings: Double?) = ev(
        AppEventType.DASH_STOP, sid, at,
        SessionStopPayload(sid, endedAt = at, source = SessionEndSource.SUMMARY_SCREEN, totalEarnings = earnings),
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
}
