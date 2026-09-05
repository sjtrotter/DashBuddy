package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.settings.GraceConfig
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingSessionPay
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The **settle gate** on the dash running total (#1029).
 *
 * The platform renders that total as an animated digit-wheel and captures land mid-spin.
 * `parseGlyphCurrency` throws out the malformed intermediates at the parse layer, but roughly one
 * fielded read in eight is well-FORMED and wrong ($470.00 during a $16.70 dash) — indistinguishable
 * from a real figure by inspection. What separates them is TIME, and specifically ELAPSED time, not
 * repetition: `IdleFields.dedupeHash` folds `sessionPay` into the screen's observation identity and
 * `FrameGate.admit` drops an identical frame, so a settled wheel is admitted exactly ONCE. A read
 * therefore commits only once it has stood **unchallenged** for `sessionPaySettleMs`, landed by
 * lazy expiry on the first observation AT or past its deadline — usually the `SESSION_PAY_SETTLE`
 * wake timer, which on a settled wheel is the only observation that will ever come.
 *
 * Round 3 (#1029 review) added the four properties a park needs to be evidence rather than a
 * countdown: it is FLOW-SCOPED (leaving the surface drops it), BOTH running-total feeds go through
 * it (the receipt's own "This dash so far" wheel included), every non-gated writer of
 * `runningEarnings` SUPERSEDES an older park, and a contradicting read on the expiring frame wins
 * over the park it contradicts.
 */
class SessionPaySettleGateTest {

    private val stepper = PlatformRegionStepper()
    private val flowStepper = FlowRegionStepper()
    private val policy = TransitionPolicy()

    /** The settle window under test — read from the SSOT, never a literal. */
    private val settle = GraceConfig.SESSION_PAY_SETTLE_MS

    private val t0 = 10_000L

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun idleScreen(sessionPay: Double?, timestamp: Long) = Observation.Screen(
        timestamp = timestamp,
        captureId = null,
        ruleId = "doordash.screen.waiting_for_offer",
        metadata = ReplayMetadata.EMPTY,
        flow = Flow.Idle,
        modeHint = Mode.Online,
        parsed = ParsedFields.IdleFields(sessionPay = sessionPay),
    )

    /**
     * A screen that is NOT the pill's surface — the fielded departure (an offer overlay landing
     * over `waiting_for_offer`). Carries no running total of any kind.
     */
    private fun offerScreen(timestamp: Long) = Observation.Screen(
        timestamp = timestamp,
        captureId = null,
        ruleId = "doordash.screen.offer_popup",
        metadata = ReplayMetadata.EMPTY,
        flow = Flow.OfferPresented,
        modeHint = Mode.Online,
        parsed = ParsedFields.None,
    )

    /** The delivery receipt — the OTHER wheel-rendered running total (#1029 S2). */
    private fun receiptScreen(
        timestamp: Long,
        sessionEarnings: Double?,
        totalPay: Double = 0.0,
    ) = Observation.Screen(
        timestamp = timestamp,
        captureId = null,
        ruleId = "doordash.screen.delivery_summary_expanded",
        metadata = ReplayMetadata.EMPTY,
        flow = Flow.PostTask,
        modeHint = Mode.Online,
        parsed = ParsedFields.PostTaskFields(totalPay = totalPay, sessionEarnings = sessionEarnings),
    )

    /** The `SESSION_PAY_SETTLE` wake timer, routed at this region (#438 8a). */
    private fun timeout(timestamp: Long) = Observation.Timeout(
        timestamp = timestamp,
        type = TimeoutType.SESSION_PAY_SETTLE,
        targetPlatform = Platform.DoorDash,
    )

    private fun region(
        runningEarnings: Double = 0.0,
        accumulatedDeliveryPay: Double = 0.0,
        pending: PendingSessionPay? = null,
    ) = PlatformRegion(
        platform = Platform.DoorDash,
        mode = Mode.Online,
        session = Session(
            sessionId = "s1",
            startedAt = 100L,
            runningEarnings = runningEarnings,
            accumulatedDeliveryPay = accumulatedDeliveryPay,
        ),
        lastObservedAt = 500L,
        pendingSessionPay = pending,
    )

    /**
     * One observation through the real steppers.
     *
     * R0 is stamped with `activePlatform = DoorDash` because that is what a real DoorDash frame
     * leaves behind (`FlowRegionStepper` sets it on every flow-bearing observation) — and park
     * ownership is (flow, PLATFORM), so a Timeout, which leaves R0 untouched, reads it from here.
     * The cross-platform half of that rule is exercised against the REAL `StateMachine` in
     * `SessionPayParkOwnershipTest`, where R0 is threaded rather than reconstructed.
     */
    private fun step(region: PlatformRegion, obs: Observation): PlatformRegion {
        val flow = FlowRegion(
            flow = region.lastActedFlow ?: Flow.Idle,
            activePlatform = Platform.DoorDash,
        )
        val nextFlow = flowStepper.step(flow, obs)
        return stepper.step(region, flow, nextFlow, obs, policy)
    }

    /** Fold `(timestamp, read)` pairs through the real stepper, one idle frame each. */
    private fun feed(start: PlatformRegion, vararg reads: Pair<Long, Double?>): PlatformRegion {
        var region = start
        for ((t, read) in reads) region = step(region, idleScreen(read, t))
        return region
    }

    private fun assertPark(
        region: PlatformRegion,
        value: Double,
        since: Long,
        deadline: Long,
        flow: Flow = Flow.Idle,
    ) {
        val pend = region.pendingSessionPay
        assertNotNull("expected a parked read", pend)
        assertEquals(value, pend!!.value, 0.0001)
        assertEquals("park.since", since, pend.since)
        assertEquals("park.deadline", deadline, pend.deadline)
        assertEquals("park.flow", flow, pend.flow)
    }

    private fun assertEarnings(expected: Double, region: PlatformRegion, message: String = "") {
        assertEquals(message, expected, region.session?.runningEarnings ?: Double.NaN, 0.0001)
    }

    // =========================================================================
    // Parking
    // =========================================================================

    @Test
    fun `a single read does NOT commit on its own frame`() {
        val result = feed(region(runningEarnings = 16.70), t0 to 470.00)
        assertEarnings(16.70, result, "one sighting must not move the committed figure")
        assertPark(result, 470.00, t0, t0 + settle)
    }

    @Test
    fun `a mid-spin read is replaced by the next read, and the deadline moves with it`() {
        val result = feed(region(), t0 to 470.00, (t0 + 200L) to 16.70)
        assertEarnings(0.0, result, "nothing may commit inside the window")
        assertPark(result, 16.70, t0 + 200L, t0 + 200L + settle)
    }

    @Test
    fun `a repeated read within the window keeps the ORIGINAL deadline`() {
        // A return to the surface must not extend the window — otherwise a wheel re-rendering the
        // same figure could hold the commit off forever.
        val result = feed(region(), t0 to 16.70, (t0 + 1_000L) to 16.70)
        assertPark(result, 16.70, t0, t0 + settle)
        assertEarnings(0.0, result)
    }

    @Test
    fun `a read equal to the committed figure clears the park`() {
        val start = region(
            runningEarnings = 16.70,
            pending = PendingSessionPay(470.00, t0, t0 + settle, Flow.Idle),
        )
        val result = feed(start, t0 to 16.70)
        assertEarnings(16.70, result)
        assertNull(
            "a read that equals the committed total means the wheel is at rest — drop the park",
            result.pendingSessionPay,
        )
    }

    @Test
    fun `a null read leaves both the committed figure and the park alone`() {
        val park = PendingSessionPay(25.20, t0, t0 + settle, Flow.Idle)
        val result = feed(region(runningEarnings = 16.70, pending = park), (t0 + 1L) to null)
        assertEarnings(16.70, result)
        assertEquals(park, result.pendingSessionPay)
    }

    @Test
    fun `a committed total that is an accumulated SUM still reads as at rest`() {
        // #1029 S4: `runningEarnings` holds `accumulatedDeliveryPay + totalPay`, which for these
        // two real deliveries is 16.700000000000003 — not bit-equal to the 16.70 the wheel renders.
        // An exact Double compare would re-park a settled read on every single frame.
        val summed = 4.15 + 12.55
        val result = feed(region(runningEarnings = summed), t0 to 16.70)
        assertNull("a cents-equal read is the same figure", result.pendingSessionPay)
        assertEarnings(summed, result)
    }

    // =========================================================================
    // The $0.00 load placeholder (S7)
    // =========================================================================

    @Test
    fun `a zero read never overwrites a positive running total`() {
        // The same earnings-pill component renders `$0.00` for seconds before the figure loads
        // (fielded 08-23 15:53:42 `$0.00` -> `$61.80` at :48). A dash total never legitimately
        // returns to zero mid-dash, so the read is ignored outright — not even parked.
        val result = feed(region(runningEarnings = 16.70), t0 to 0.0)
        assertNull("the placeholder must not even park", result.pendingSessionPay)
        assertEarnings(16.70, result)
    }

    @Test
    fun `a zero read on a fresh dash is simply the figure at rest`() {
        val result = feed(region(runningEarnings = 0.0), t0 to 0.0)
        assertNull(result.pendingSessionPay)
        assertEarnings(0.0, result)
    }

    @Test
    fun `a zero read does not disturb a park already standing`() {
        val park = PendingSessionPay(25.20, t0, t0 + settle, Flow.Idle)
        val result = feed(region(runningEarnings = 16.70, pending = park), (t0 + 100L) to 0.0)
        assertEquals("the placeholder is ignored, not treated as a challenge", park, result.pendingSessionPay)
    }

    // =========================================================================
    // Committing
    // =========================================================================

    @Test
    fun `the parked read commits by lazy expiry on the next observation past its deadline`() {
        val parked = feed(region(), t0 to 470.00, (t0 + 200L) to 16.70)
        val result = feed(parked, (t0 + 200L + settle + 1L) to null)
        assertEarnings(16.70, result)
        assertNull("the park is spent once it commits", result.pendingSessionPay)
    }

    @Test
    fun `the wake timer commits the park when no further frame arrives`() {
        // THE FrameGate case, and the reason the timer exists: on a settled wheel every later
        // frame carries the identical observation identity (IdleFields.dedupeHash folds in
        // sessionPay), so FrameGate.admit drops it and NO screen observation ever reaches the
        // machine again. Only SESSION_PAY_SETTLE can land the figure.
        val parked = feed(region(), t0 to 470.00, (t0 + 200L) to 16.70)
        val result = step(parked, timeout(t0 + 200L + settle + 1L))
        assertEarnings(16.70, result)
        assertNull(result.pendingSessionPay)
    }

    @Test
    fun `a wake timer landing EXACTLY on the deadline still commits`() {
        // #1029 S5: the timer is armed for exactly `deadline - obs.timestamp` and fires against a
        // wall clock, so landing on the deadline is the ordinary case, not an edge one. With a
        // strict `>` it would no-op — and no frame is coming to retry.
        val parked = feed(region(), t0 to 16.70)
        val result = step(parked, timeout(t0 + settle))
        assertEarnings(16.70, result)
        assertNull(result.pendingSessionPay)
    }

    @Test
    fun `a wake timer BEFORE the deadline does nothing`() {
        val parked = feed(region(), t0 to 16.70)
        val result = step(parked, timeout(t0 + settle - 1L))
        assertEarnings(0.0, result)
        assertPark(result, 16.70, t0, t0 + settle)
    }

    @Test
    fun `a real change commits once unchallenged for the window`() {
        val parked = feed(region(runningEarnings = 16.70), t0 to 25.20)
        assertEarnings(16.70, parked, "still held inside the window")

        val result = step(parked, timeout(t0 + settle + 1L))
        assertEarnings(25.20, result)
        assertNull(result.pendingSessionPay)
    }

    @Test
    fun `a flicker away and back never commits the flicker`() {
        // The wheel spins through 470 between two settled 16.70 reads: the second read clears the
        // park outright, so even a later wake timer has nothing to commit.
        val flicked = feed(region(runningEarnings = 16.70), t0 to 470.00, (t0 + 300L) to 16.70)
        assertNull("the park is dropped, not held", flicked.pendingSessionPay)

        val result = step(flicked, timeout(t0 + settle + 1L))
        assertEarnings(16.70, result)
    }

    @Test
    fun `two DIFFERENT spin values park only the newest, and neither commits early`() {
        val result = feed(
            region(runningEarnings = 16.70),
            t0 to 470.00,
            (t0 + 200L) to 580.00,
            (t0 + 400L) to 70.00,
        )
        assertEarnings(16.70, result)
        assertPark(result, 70.00, t0 + 400L, t0 + 400L + settle)
    }

    @Test
    fun `a contradicting read on the expiring frame supersedes the park`() {
        // #1029 S6: a late timer and a fresh frame collide. Committing the stale park first and
        // then re-parking the fresh read for another whole window would show the dasher a figure
        // the very same frame already disproved.
        val parked = feed(region(runningEarnings = 16.70), t0 to 470.00)
        val result = feed(parked, (t0 + settle + 1L) to 16.70)
        assertEarnings(16.70, result, "470 must never have been committed")
        assertNull("the fresh read equals the committed total, so nothing is parked", result.pendingSessionPay)
    }

    @Test
    fun `expiry with no session drops the park without inventing a figure`() {
        val sessionless = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Offline,
            pendingSessionPay = PendingSessionPay(470.00, t0, t0 + settle, Flow.Idle),
        )
        val result = step(sessionless, timeout(t0 + settle + 1L))
        assertNull("no session may be invented to hold it", result.session)
        assertNull(result.pendingSessionPay)
    }

    // =========================================================================
    // The park is FLOW-SCOPED (S1)
    // =========================================================================

    @Test
    fun `leaving the surface the read came from DROPS the park`() {
        // The fielded shape (08-23 17:35): a mid-spin pill read parks, an offer overlay lands half
        // a second later, and no later screen carries a running total — so nothing can contradict
        // the park and the wake timer would commit the spin value into the dash. A read is
        // evidence only while its surface is on screen.
        val parked = feed(region(runningEarnings = 16.70), t0 to 470.00)
        assertPark(parked, 470.00, t0, t0 + settle)

        val departed = step(parked, offerScreen(t0 + 500L))
        assertNull("the park dies with its surface", departed.pendingSessionPay)

        val later = step(departed, timeout(t0 + settle + 1L))
        assertEarnings(16.70, later, "the committed figure simply stands")
    }

    @Test
    fun `a park that stood its WHOLE window still commits on the departure frame`() {
        // The drop runs after the expiry, so a read that was never challenged for the full window
        // is not punished for the frame that happens to end its surface.
        val parked = feed(region(runningEarnings = 16.70), t0 to 25.20)
        val departed = step(parked, offerScreen(t0 + settle + 1L))
        assertEarnings(25.20, departed)
        assertNull(departed.pendingSessionPay)
    }

    // =========================================================================
    // The receipt's own wheel takes the same gate (S2)
    // =========================================================================

    @Test
    fun `the receipt's This-dash-so-far figure is parked, not written straight through`() {
        // `dropoff.json5` reads it through `parseGlyphCurrency` off the SAME animated wheel the
        // pill uses, so it has the same well-formed-mid-spin failure. This PR's own golden had
        // approved a pre-roll $17.75 for a receipt whose expanded capture one second later read
        // $35.47.
        val result = step(region(runningEarnings = 16.70), receiptScreen(t0, sessionEarnings = 470.00))
        assertEarnings(16.70, result, "the receipt's wheel does not bypass the gate")
        assertPark(result, 470.00, t0, t0 + settle, flow = Flow.PostTask)

        val committed = step(result, timeout(t0 + settle + 1L))
        assertEarnings(470.00, committed, "unchallenged on its own surface for the window")
    }

    @Test
    fun `a receipt read REPLACES a stale idle park rather than being blocked by it`() {
        val parked = feed(region(runningEarnings = 16.70), t0 to 470.00)
        val result = step(parked, receiptScreen(t0 + 100L, sessionEarnings = 25.20))
        assertEarnings(16.70, result)
        assertPark(result, 25.20, t0 + 100L, t0 + 100L + settle, flow = Flow.PostTask)

        val committed = step(result, timeout(t0 + 100L + settle + 1L))
        assertEarnings(25.20, committed)
    }

    // =========================================================================
    // Non-gated writers supersede older parks (S3)
    // =========================================================================

    @Test
    fun `the PostTask pay accumulation supersedes a park older than itself`() {
        // #1029 S3(a): the accumulation writes runningEarnings directly. A park made before it
        // describes staler evidence and must not expire over the top of it afterwards.
        val start = region(runningEarnings = 16.70, accumulatedDeliveryPay = 16.70)
        val parked = feed(start, t0 to 17.00)
        assertPark(parked, 17.00, t0, t0 + settle)

        val receipt = step(parked, receiptScreen(t0 + 100L, sessionEarnings = null, totalPay = 8.50))
        assertEarnings(25.20, receipt, "16.70 accumulated + this delivery's 8.50")
        assertNull("the older park is superseded", receipt.pendingSessionPay)

        val later = step(receipt, timeout(t0 + settle + 1L))
        assertEarnings(25.20, later)
    }

    @Test
    fun `the receipt's OWN park, made on the same frame, survives the accumulation`() {
        // The `since >= now` half of the supersession rule: updateSessionFields parks the
        // receipt's wheel read microseconds before the accumulation block runs on that same frame.
        val start = region(runningEarnings = 16.70, accumulatedDeliveryPay = 16.70)
        val receipt = step(start, receiptScreen(t0, sessionEarnings = 25.20, totalPay = 8.50))
        assertEarnings(25.20, receipt)
        assertPark(receipt, 25.20, t0, t0 + settle, flow = Flow.PostTask)

        val later = step(receipt, timeout(t0 + settle + 1L))
        assertEarnings(25.20, later, "committing its own figure is a no-op")
    }

    @Test
    fun `the dash summary cannot be overwritten by a park armed before it`() {
        // #1029 S3(b), fielded: park $470, End Dash, the summary states $16.70 and arms the short
        // authoritative grace, then the settle timer fires INTO the still-live session and the
        // #596 close-out sweep stamps 470 into DELIVERY_COMPLETED.sessionEarnings. The summary is
        // not the pill's surface, so the flow-scoped drop closes it at the door.
        val parked = feed(region(runningEarnings = 16.70), t0 to 470.00)
        val summary = Observation.Screen(
            timestamp = t0 + 600L,
            captureId = null,
            ruleId = "doordash.screen.dash_summary",
            metadata = ReplayMetadata.EMPTY,
            flow = Flow.SessionEnded,
            modeHint = Mode.Offline,
            parsed = ParsedFields.SessionEndedFields(totalEarnings = 16.70),
        )
        val ended = step(parked, summary)
        assertNull("no park may outlive the dash it describes", ended.pendingSessionPay)

        val later = step(ended, timeout(t0 + settle + 1L))
        assertEarnings(16.70, later, "the dash's real total stands")
    }

    @Test
    fun `the dash summary's own total contradicts a park expiring on that very frame`() {
        // #1029 R2, the fielded shape the flow-scoped drop alone does NOT cover: `updateLifecycle`
        // returns EARLY on Flow.SessionEnded with a live session (it arms the authoritative
        // SESSION_END grace there), so the SessionEndedFields arm of updateSessionFields — and its
        // supersedeParksOlderThan — is unreachable. If the summary frame is also the frame the
        // park's deadline lands on, the expiry runs FIRST and commits $470 before anything looks at
        // the flow. `Observation.sessionPayRead()` counts `totalEarnings` as a running-total read,
        // so the contradiction check drops the park instead.
        val parked = feed(region(runningEarnings = 16.70), t0 to 470.00)
        assertPark(parked, 470.00, t0, t0 + settle)

        val summary = Observation.Screen(
            timestamp = t0 + settle,
            captureId = null,
            ruleId = "doordash.screen.dash_summary",
            metadata = ReplayMetadata.EMPTY,
            flow = Flow.SessionEnded,
            modeHint = Mode.Offline,
            parsed = ParsedFields.SessionEndedFields(totalEarnings = 16.70),
        )
        val ended = step(parked, summary)
        assertEarnings(16.70, ended, "the spin value must never reach the dash's stated total")
        assertNull("the contradicted park is dropped, not committed", ended.pendingSessionPay)
    }

    @Test
    fun `the same holds when the summary arrives PAST the deadline`() {
        val parked = feed(region(runningEarnings = 16.70), t0 to 470.00)
        val summary = Observation.Screen(
            timestamp = t0 + settle + 1L,
            captureId = null,
            ruleId = "doordash.screen.dash_summary",
            metadata = ReplayMetadata.EMPTY,
            flow = Flow.SessionEnded,
            modeHint = Mode.Offline,
            parsed = ParsedFields.SessionEndedFields(totalEarnings = 16.70),
        )
        val ended = step(parked, summary)
        assertEarnings(16.70, ended)
        assertNull(ended.pendingSessionPay)
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Test
    fun `the park is cleared when the session ends`() {
        var flow = FlowRegion(flow = Flow.Idle)
        var region = region(
            runningEarnings = 16.70,
            pending = PendingSessionPay(470.00, 4_000L, 4_000L + settle, Flow.Idle),
        )

        // The dash summary arms the (graced) session end...
        val summary = Observation.Screen(
            timestamp = 5_000L,
            captureId = null,
            ruleId = "doordash.screen.dash_summary",
            metadata = ReplayMetadata.EMPTY,
            flow = Flow.SessionEnded,
            modeHint = Mode.Offline,
            parsed = ParsedFields.None,
        )
        var nextFlow = flowStepper.step(flow, summary)
        region = stepper.step(region, flow, nextFlow, summary, policy)
        flow = nextFlow

        // ...and a frame well past the grace deadline commits it.
        val later = summary.copy(timestamp = 5_000L + 60L * 60_000L)
        nextFlow = flowStepper.step(flow, later)
        region = stepper.step(region, flow, nextFlow, later, policy)

        assertNull("the session must actually be gone for this to prove anything", region.session)
        assertNull(
            "an un-settled read describes the dash that just ended",
            region.pendingSessionPay,
        )
    }

    @Test
    fun `the park is cleared when a NEW session starts`() {
        // A read parked against the previous dash must never settle against this one.
        val offline = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Offline,
            pendingSessionPay = PendingSessionPay(470.00, 8_000L, 8_000L + settle, Flow.Idle),
        )
        val obs = idleScreen(sessionPay = null, timestamp = 9_000L)
        val nextFlow = flowStepper.step(FlowRegion(), obs)
        val started = stepper.step(offline, FlowRegion(), nextFlow, obs, policy)

        assertEquals("a session must have been minted", 9_000L, started.session?.startedAt)
        assertNull(started.pendingSessionPay)
    }
}
