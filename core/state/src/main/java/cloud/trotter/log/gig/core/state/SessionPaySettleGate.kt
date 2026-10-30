package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.pipeline.TimeoutType
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.FlowRegion
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.PendingSessionPay
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.Session
import timber.log.Timber

/** Owns session-pay reads, direct writes, and parked-read lifecycle (CLAUDE.md §3). */
internal object SessionPaySettleGate {
    /**
     * Idle pill read ([ParsedFields.IdleFields.sessionPay]). [pay] may come from replayed idle
     * fields (commitModeResume); time is [now]. No session or null pay: unchanged.
     */
    fun pillRead(
        region: PlatformRegion,
        pay: Double?,
        flow: Flow,
        now: Long,
        policy: TransitionPolicy,
    ): PlatformRegion {
        var r = region
        r.session?.let { session ->
            if (pay != null) r = settleSessionPay(r, session, pay, now, flow, policy)
        }
        return r
    }

    /** Receipt "this dash so far" read, with ownership refusal logging. No session: unchanged. */
    fun receiptRead(
        region: PlatformRegion,
        obs: Observation,
        parsed: ParsedFields.PostTaskFields,
        flow: Flow,
        policy: TransitionPolicy,
    ): PlatformRegion {
        var r = region
        r.session?.let { session ->
            // #1029: the receipt's "This dash so far" is the SAME digit-wheel the on-dash
            // pill renders — `dropoff.json5` reads it through `parseGlyphCurrency` on both
            // summary rules — so it takes the SAME settle gate. Exempting it would leave
            // the identical well-formed-mid-spin hole open on the surface that closes a
            // delivery (the PR's own golden had approved a pre-roll $17.75 against a
            // $35.47 dash one second later).
            //
            // #1103: and it is read through the ONE ownership question — [ownsReceiptJob]
            // — so a receipt left on screen from the PREVIOUS dash can never park under
            // this one. The read is taken from `sessionPayRead`, which is also what the
            // expiry's contradiction check consults, so the two can never disagree about
            // what counts as this session's running total.
            val earnings = obs.sessionPayRead(r)
            if (earnings != null) {
                r = settleSessionPay(r, session, earnings, obs.timestamp, flow, policy)
            } else if (parsed.sessionEarnings != null) {
                // The refusal is otherwise INVISIBLE — #1103 cost a dash's reported
                // earnings and left no trace anywhere in the log. Ids and the platform
                // only (P7): no money, no store, no customer.
                Timber.tag("StateMachine").d(
                    "settle gate: receipt running-total read refused — " +
                        "no job owned by this session (platform=%s, session=%s)",
                    r.platform.name,
                    session.sessionId,
                )
            }
        }
        return r
    }

    /** PostTask-entry receipt total: ownership refusal, accumulation + max, and older-park drop. */
    fun receiptEntry(
        region: PlatformRegion,
        postFields: ParsedFields.PostTaskFields?,
        now: Long,
    ): PlatformRegion {
        var r = region
        // #1103 (review): the receipt's TOTAL is subject to the same ownership test as its wheel
        // read — a previous dash's receipt still on screen when a new dash starts must not seed
        // the new dash's accumulated pay either. Owned = this session's job is open, or just
        // closed on this receipt; a fresh dash has neither marker.
        if (postFields != null && postFields.totalPay > 0 && !r.ownsReceiptJob()) {
            Timber.tag("StateMachine").d(
                "receipt total %.2f refused — no job owned by this session (#1103)", postFields.totalPay,
            )
        }
        if (postFields != null && postFields.totalPay > 0 && r.ownsReceiptJob()) {
            r.session?.let { session ->
                val accumulated = (session.accumulatedDeliveryPay ?: 0.0) + postFields.totalPay
                val best = maxOf(session.runningEarnings ?: 0.0, accumulated)
                r = r.copy(session = session.copy(
                    accumulatedDeliveryPay = accumulated,
                    runningEarnings = best,
                ))
                // #1029: this is a NON-gated write of runningEarnings, so it supersedes every
                // park older than itself — otherwise a park made before the receipt could
                // expire afterwards and overwrite it. `since >= now` is load-bearing: the
                // receipt's OWN wheel read was parked by updateSessionFields a few lines
                // above, on THIS same frame, and that park must survive.
                r = r.supersedeParksOlderThan(now)
            }
        }
        return r
    }

    /** Dash-summary direct write of [total], superseding older parks. No session: unchanged. */
    fun summaryTotal(region: PlatformRegion, total: Double, now: Long): PlatformRegion {
        var r = region
        r.session?.let { session ->
            // #1029: a non-gated write, so it supersedes every park older than itself
            // (same rule as the PostTask-entry accumulation above). This arm is
            // UNREACHABLE on the fielded path — `updateLifecycle` returns early on
            // `Flow.SessionEnded` with a live session, to arm the authoritative
            // SESSION_END grace — so what actually closes the case there is
            // `Observation.sessionPayRead(region)` treating the summary's total as a
            // contradicting read at expiry time. The call stays here because this is
            // still the honest place for the invariant if that early return ever moves:
            // "no direct writer leaves an older park alive" is stated at every writer,
            // not wherever it happens to be reachable today.
            r = r.copy(session = session.copy(runningEarnings = total))
                .supersedeParksOlderThan(now)
        }
        return r
    }

    /** Mode moved; [region] already carries the new mode. Re-base a frozen park on resume. */
    fun modeChanged(
        region: PlatformRegion,
        previousMode: Mode,
        now: Long,
        policy: TransitionPolicy,
    ): PlatformRegion {
        var r = region
        // #1052 round 3: coming back Online RE-BASES a frozen running-total park. A park is frozen
        // while the dash is not Online (the expiry block in [stepCore] skips wholesale, see
        // [PendingSessionPay]) — killing it, as rounds 1 and 2 did, stranded a legitimate read for
        // the rest of the dash, because the frame that first showed the figure is the only one
        // FrameGate will ever admit for it. Freezing alone is not enough either: a window that
        // elapsed under the pause sheet proves nothing about a wheel nobody was watching, so the
        // park must stand a FULL window unchallenged WHILE ONLINE. Re-stamping `since`/`deadline`
        // here is what buys that, and the effect diff sees a moved deadline and arms the timer.
        // Written HERE because [applyModeTransition] is the single site that moves `mode` (the
        // pause-safety timeout and the graced resume commit both route through it), and guarded on
        // `previousMode` because this function also runs on every ordinary Online→Online frame — an
        // unguarded re-base would extend the window forever. Leaving Online writes NOTHING: the
        // park rides out the pause. The session start/end clears above are unchanged.
        if (previousMode != Mode.Online && r.mode == Mode.Online && r.pendingSessionPay != null) {
            // #1054 round 5: a re-based park is a NEW window, so it takes a new identity — the
            // pre-pause arm must not be able to land on it.
            val frozen = requireNotNull(r.pendingSessionPay)
            val (withId, rebased) = r.sessionPayWindow { wakeId ->
                frozen.copy(
                    since = now,
                    deadline = now + policy.sessionPaySettleMs(r.platform),
                    wakeId = wakeId,
                    // #1052 round 4: a re-based park is UNCONFIRMED. The new window is only
                    // evidence if something could have challenged it, and on the fielded shape
                    // nothing can — the resume commits as a wake TIMER, this very re-base re-arms
                    // the settle timer, and if no readable idle frame lands in between then the
                    // FIRST observation of the new window is that fire. Committing there would
                    // mint a pre-pause mid-spin figure on the strength of a window in which
                    // nothing was ever on screen. A fresh agreeing read on the park's own surface
                    // clears the flag (settleSessionPay); the expiry DROPS it otherwise. See
                    // [PendingSessionPay.unconfirmed].
                    unconfirmed = true,
                )
            }
            r = withId.copy(pendingSessionPay = rebased)
        }

        return r
    }

    /** A shift started or ended: the park is dropped. */
    fun sessionBoundary(region: PlatformRegion): PlatformRegion =
        region.copy(pendingSessionPay = null)

    /**
     * Lazy expiry: lapses at-or-past the deadline (>=, deliberately NOT graceLapsed's >),
     * or on its own SESSION_PAY_SETTLE wake; reads the mode as resolved so far in this frame.
     */
    fun lapse(
        region: PlatformRegion,
        prevFlow: FlowRegion,
        nextFlow: FlowRegion,
        obs: Observation,
    ): PlatformRegion {
        var current = region
        // #1029: lazy expiry of a parked dash running-total read. It commits once it has stood
        // UNCHALLENGED for the settle window — a different read would have replaced it (with a
        // fresh deadline) and a read equal to the committed figure would have cleared it, so
        // reaching the deadline is itself the evidence that the wheel settled here. Runs BEFORE
        // the timeout branch so the SESSION_PAY_SETTLE wake timer can land it (see
        // [PlatformRegion.pendingSessionPay] for why a timer is load-bearing here and repetition
        // is not a usable signal). Driven by obs.timestamp (never a wall clock) so crash-recovery
        // replay matches.
        //
        // Lapse is `>=` for a FRAME — rule (e)'s own choice, safe because rule (f) below lets a
        // contradicting read supersede the park at equality — OR this park's own wake, matched by
        // its `GraceWake` payload, which is what makes the timer reliable and a re-based park's old
        // wake inert (#1054 round 4; the reasoning is in `GraceExpiry.kt`).
        current.pendingSessionPay?.let { pend ->
            // #1029: a park is OWNED by (flow, PLATFORM) — the R0 surface the read was made on AND
            // the platform that put that surface on screen. `FlowRegionStepper` stamps
            // `activePlatform` on every flow-bearing frame and leaves R0 untouched on
            // Timeout/UiInput/Loopback, so R0 carries exactly the ownership fact needed. Compared
            // as `Platform` values — never a literal (principle 8).
            //
            // #1052: ownership is checked on BOTH sides of this observation. `ownedAfter` alone
            // (the #1029 shape) is blind to a departure this region was never stepped for:
            // `stepPlatforms` steps only `obs.platform`'s region, so an Uber frame moves the
            // SHARED R0 without ever reaching the DoorDash region that holds the park — and when
            // the owner returns, R0 reads owned again and the ORIGINAL deadline commits a figure
            // that was off screen for the whole interlude. `ownedBefore` is what records that
            // absence: R0 as it was BEFORE this observation is the only surviving evidence of it.
            //
            // Four-way, in order:
            //  0. the region is not Online (#1052 round 3) → SKIP THE WHOLE BLOCK, keeping the
            //     park. A park is a read of a LIVE dash total, so a non-Online dash cannot CONFIRM
            //     one — its total is not moving and nothing behind the pause sheet can contradict
            //     the figure — but neither is the evidence stale: the read taken under the sheet
            //     is the freshest there is. Frozen, not killed (see [PendingSessionPay]); the
            //     window restarts in [applyModeTransition] on the way back to Online, and the
            //     ownership rules below resume with it. A `SESSION_PAY_SETTLE` fire that lands
            //     here while non-Online is likewise inert: the park is kept, its deadline
            //     unchanged, and the re-base on resume arms a NEW deadline — so that old wake
            //     matches nothing afterwards, by identity (#1054 round 4).
            //  1. `!ownedBefore` → the surface departed while this region was not being stepped.
            //     DROP, whatever this observation is; a returning frame re-parks with a FRESH
            //     deadline through [settleSessionPay], which is the honest window for a read whose
            //     surface has just come back.
            //  2. a flow-LESS observation (a timer, a click, a loopback, a flow-less
            //     notification) that no longer owns R0 → DROP, skipping the expiry: such a frame
            //     cannot itself be the departure, so the departure already happened. (Today this
            //     is unreachable behind rule 1 — a flow-less observation leaves R0 untouched, so
            //     `ownedAfter == ownedBefore` — and it is stated anyway so the rule is complete on
            //     its own terms rather than resting on that invariant holding elsewhere forever.)
            //  3. otherwise expiry as normal, THEN `!ownedAfter` → drop.
            val ownedBefore = prevFlow.flow == pend.flow && prevFlow.activePlatform == current.platform
            val ownedAfter = nextFlow.flow == pend.flow && nextFlow.activePlatform == current.platform
            val flowBearing = (obs as? Observation.FlowObservation)?.flow != null
            // `current.mode` is this frame's mode as resolved SO FAR: the PREVIOUS frame's, except
            // where the graced-resume expiry a few lines above has already committed a resume on
            // this very observation — which is precisely the frame whose re-based park is live
            // again. Rule 0 reads it as the honest "is this dash running right now".
            if (current.mode != Mode.Online) {
                // Frozen — kept exactly as it stands, neither committed nor dropped.
            } else if (!ownedBefore || (!flowBearing && !ownedAfter)) {
                current = current.copy(pendingSessionPay = null)
            } else {
                if (obs.timestamp >= pend.deadline ||
                    obs.isWakeFor(TimeoutType.SESSION_PAY_SETTLE, pend.wakeId)
                ) {
                    // A CONTRADICTING read on the very frame the park would commit on supersedes
                    // it: a late timer and a fresh idle frame can collide, and committing the stale
                    // park first and then re-parking the fresh read for another whole window shows
                    // the dasher a figure that the same frame already disproved.
                    val read = obs.sessionPayRead(current)
                    current = when {
                        read != null && !centsEqual(read, pend.value) ->
                            current.copy(pendingSessionPay = null)
                        // #1052 round 4: an UNCONFIRMED park is re-based pre-pause evidence that
                        // no live read has agreed with. Reaching the deadline proves nothing here
                        // — a null read means the wheel was unreadable, and a bare timer means
                        // nothing was looked at at all — so DROP rather than commit. The committed
                        // figure stands and the settled value re-parks when it is next admitted
                        // (a new observation identity, since the pause frames moved FrameGate's
                        // `lastIdentity`). Fail-null beats fail-wrong (#745).
                        pend.unconfirmed -> current.copy(pendingSessionPay = null)
                        else -> commitSessionPay(current, pend)
                    }
                }

                // The park is FLOW-SCOPED — a read is evidence only while the surface it came from
                // is on screen. Placed immediately AFTER the expiry so a park that stood its whole
                // window on its own surface still commits on the departure frame; anything younger
                // is dropped (the diff emits the CancelTimeout). See [PendingSessionPay.flow].
                if (!ownedAfter) current = current.copy(pendingSessionPay = null)
            }
        }

        return current
    }

    /**
     * The **settle gate** for a parsed dash running total (#1029): a value commits to
     * [Session.runningEarnings] only once it has stood **unchallenged for the settle window**
     * ([TransitionPolicy.sessionPaySettleMs]).
     *
     * The platform now renders that total as an animated digit-wheel, so a capture can catch it
     * mid-spin. `parseGlyphCurrency` rejects the malformed intermediates at the parse layer, but a
     * spin value that happens to be well-FORMED ($470.00 on a $16.70 dash) is indistinguishable
     * from a real figure by inspection — only by TIME. Hence:
     *
     *  - a read parks in ANY mode (#1052 round 3). The mode decides when a park may be CONFIRMED,
     *                        never whether the evidence is worth keeping: a figure read under
     *                        DoorDash's pause sheet is the freshest sighting there is, and rounds 1
     *                        and 2 — which refused to park it, or dropped a park on the way out of
     *                        Online — stranded it for the rest of the dash. The #605 resume grace
     *                        keeps the mode at [Mode.Paused] across the online-implying flap frames,
     *                        the confirmed resume then arrives as a wake TIMER with no frame behind
     *                        it, and `FrameGate` never re-admits the identical idle capture, so the
     *                        read has no second chance. A park is instead FROZEN while the dash is
     *                        not Online and re-based on the transition back — see
     *                        [PendingSessionPay] for the canonical statement, [stepCore]'s expiry
     *                        block for the freeze and [applyModeTransition] for the re-base.
     *  - read == 0.00 with no figure → keep null and drop any park — a zero contradicts a
     *                        parked figure (#1041).
     *  - read == 0.00 with a positive committed total → the LOAD PLACEHOLDER;
     *                        ignore it entirely
     *                        (the same earnings-pill component renders `$0.00` for seconds before
     *                        the figure loads — fielded 08-23 15:53:42 `$0.00` → `$61.80` at :48 —
     *                        and a dash running total never legitimately returns to zero mid-dash).
     *                        Deliberately NOT a general monotonic guard: a genuine downward
     *                        correction is out of scope, only the zero placeholder is refused.
     *  - read == committed, non-null → the wheel is at rest; drop any park and change nothing.
     *                        Null never agrees with a read; the first figure must settle (#1041).
     *  - read == parked, SAME surface → the same value again on the surface it was parked from;
     *                        keep the park AND its ORIGINAL deadline (an intervening screen and a
     *                        return must not extend the window), and CLEAR
     *                        [PendingSessionPay.unconfirmed] — this is the agreement a re-based
     *                        park is waiting for (#1052 round 4).
     *  - otherwise         → a new sighting; REPLACE the park with a fresh deadline, stamped with
     *                        the [Flow] it was read on (the park dies when that surface leaves).
     *                        An equal value on a DIFFERENT surface takes this arm too (#1052 round
     *                        4): keeping the old park would leave it owned by a surface that is no
     *                        longer on screen, and the fielded receipt→pill→resume sequence then
     *                        stranded the figure for the rest of the dash.
     *
     * Both equality tests are CENT-tolerant: `runningEarnings` can hold an accumulated sum
     * (`accumulatedDeliveryPay + totalPay`) that is not bit-equal to the 2-dp figure the wheel
     * renders, and an exact `Double` compare there would needlessly re-park and re-arm.
     *
     * The commit itself is [commitSessionPay], run by lazy expiry in [stepCore] on the first
     * observation AT or past the deadline — a `SESSION_PAY_SETTLE` wake timer guarantees one
     * arrives, and that timer is load-bearing rather than punctual (see
     * [PlatformRegion.pendingSessionPay] for why repetition is not a signal this reducer can ever
     * observe).
     *
     * Split immediate/gated fields: only the running total is gated here. The other [IdleFields]
     * (`zoneName`, `sessionType`) are written immediately — they are not wheel-rendered and carry
     * no mid-animation failure mode. BOTH running-total feeds go through this one function: the
     * idle earnings pill and the receipt's own "This dash so far" wheel.
     *
     * Pure, platform-agnostic (state keyed by this region only, no [Platform] branch, no wall
     * clock — the deadline is derived from `obs.timestamp`). See
     * [PlatformRegion.pendingSessionPay].
     */
    private fun settleSessionPay(
        region: PlatformRegion,
        session: Session,
        pay: Double,
        now: Long,
        flow: Flow,
        policy: TransitionPolicy,
    ): PlatformRegion {
        val pend = region.pendingSessionPay
        val running = session.runningEarnings
        return when {
            pay == 0.0 && (running ?: 0.0) > 0.0 -> region
            pay == 0.0 && running == null -> region.copy(pendingSessionPay = null)
            running != null && centsEqual(pay, running) -> region.copy(pendingSessionPay = null)
            // The AGREEING arm (#1052 round 4): same value AND the same surface. The flow test is
            // what keeps the agreement honest — a read on ANOTHER surface that happens to state
            // the same figure used to leave the park sitting on the surface it was first read
            // from, which then has to come back before it can ever commit; on the fielded shape
            // (a receipt park agreed with by the idle pill, then a resume onto Idle) it never did,
            // and identical idle frames are deduplicated, so the figure was stranded. Falling
            // through to the replace arm re-parks it HERE, on the surface actually on screen.
            pend != null && centsEqual(pay, pend.value) && pend.flow == flow ->
                region.copy(pendingSessionPay = pend.copy(unconfirmed = false))
            else -> {
                // #1054 round 5: a replacement park is a NEW window and takes a new identity. A
                // deadline alone would not separate them — after a clock step-back the replacement
                // computes the identical `now + settleWindow`, and the superseded arm then
                // committed it after essentially no time in its own window.
                val (withId, parked) = region.sessionPayWindow { wakeId ->
                    PendingSessionPay(
                        value = pay,
                        since = now,
                        deadline = now + policy.sessionPaySettleMs(region.platform),
                        flow = flow,
                        wakeId = wakeId,
                    )
                }
                withId.copy(pendingSessionPay = parked)
            }
        }
    }

    /**
     * Two money figures that describe the same total (#1029). `Session.runningEarnings` can hold an
     * ACCUMULATED sum, which is not bit-equal to the 2-dp figure the platform renders for it, so an
     * exact `Double` compare would treat a settled read as "new" and re-park it forever.
     */
    private fun centsEqual(a: Double, b: Double): Boolean = kotlin.math.abs(a - b) < 0.005

    /**
     * The parsed dash running total this observation carries, if any (#1029) — every surface that
     * states the figure, in ONE place, so the expiry's contradiction check and the gate itself can
     * never disagree about what counts as a running-total read.
     *
     * The first two are the gated feeds (the on-dash pill and the receipt's wheel). The THIRD, the
     * dash summary's `totalEarnings`, never parks — it is an authoritative direct write — but it is
     * emphatically a running-total READ, and on the fielded path it is the ONLY thing that can
     * contradict a park at the moment the park expires: `updateLifecycle` returns early on
     * `Flow.SessionEnded` with a live session (it arms the authoritative SESSION_END grace there),
     * so the [ParsedFields.SessionEndedFields] arm of [updateSessionFields] is unreachable on that
     * path and its `supersedeParksOlderThan` never runs. Without this arm a $470 mid-spin park
     * parked before "End Dash" would COMMIT on the summary frame and ride into the #596 close-out
     * sweep's `DELIVERY_COMPLETED.sessionEarnings` and the HUD latch.
     */
    private fun Observation.sessionPayRead(region: PlatformRegion): Double? =
        when (val parsed = (this as? Observation.FlowObservation)?.parsed) {
            is ParsedFields.IdleFields -> parsed.sessionPay
            // #1103: the receipt's wheel is evidence about THIS dash only while this dash owns the
            // job the receipt describes — see [ownsReceiptJob]. A refused read is not a reading of
            // this session's total at all, so it must not contradict a park either; the gate and
            // the contradiction check share this one definition by construction.
            is ParsedFields.PostTaskFields ->
                parsed.sessionEarnings?.takeIf { region.ownsReceiptJob() }
            is ParsedFields.SessionEndedFields -> parsed.totalEarnings
            else -> null
        }

    /**
     * Does this region own the job a post-delivery receipt on screen would be describing (#1103)?
     *
     * Fielded 2026-09-10 (session 483): a dash ended on the `delivery_summary_collapsed` receipt of
     * its last delivery, the dasher started a NEW dash 8 s later, and the PREVIOUS dash's receipt
     * was still the frame on screen 14 ms after `DASH_START`. Its "This dash so far" wheel — $40.14
     * — parked under the settle gate (a park is owned by (flow, platform), which that frame
     * satisfies: PostTask on DoorDash), stood its 3 s unchallenged, and committed into the NEW
     * session's [Session.runningEarnings]; the `early_offline` stop then reported $40.14 of earnings
     * for a 2-minute dash with zero deliveries. Nothing in the job/task lifecycle ever moved — the
     * frame was pure carry-over.
     *
     * So the rule: **a receipt's running-total read is evidence only while this session owns the job
     * the receipt describes.** Two owning shapes, and no third:
     *  - [PlatformRegion.activeJob] non-null — the job is still open (its `TASK_RETIRE` grace is
     *    running), which is the ordinary receipt frame; or
     *  - [PlatformRegion.lastClosedJobReceipt] non-null — the job closed ON this receipt and the
     *    receipt is still on screen (`completeActiveJob` stamps that marker), which is the
     *    late-expansion / re-render window (#1033).
     *
     * Both are cleared by `endSession`, which is what makes the test a SESSION-ownership test rather
     * than a "was there ever a receipt" test: a fresh dash starts with neither, so the previous
     * dash's frame carries nothing this region can attribute to itself. The on-dash earnings pill
     * ([ParsedFields.IdleFields.sessionPay]) is deliberately NOT gated — it renders the live dash's
     * own total by construction and has no job to own.
     *
     * Fail-null (#745): a refused read is DROPPED, never parked and never committed. The cost is a
     * receipt frame whose job the region somehow lost, which leaves the committed figure standing
     * until the pill states it again — against inventing a previous dash's earnings into this one.
     */
    private fun PlatformRegion.ownsReceiptJob(): Boolean =
        activeJob != null || lastClosedJobReceipt != null

    /**
     * Drop a parked running-total read that is OLDER than a direct write of
     * [Session.runningEarnings] (#1029). Every writer that bypasses the settle gate calls this: a
     * park made before that write describes staler evidence, and letting it expire afterwards
     * would silently overwrite the newer figure. `since >= now` keeps a park made on the SAME
     * frame (the receipt's own wheel read, parked microseconds earlier in [updateSessionFields]).
     */
    private fun PlatformRegion.supersedeParksOlderThan(now: Long): PlatformRegion =
        copy(pendingSessionPay = pendingSessionPay?.takeIf { it.since >= now })

    /**
     * Commit a parked running-total read whose settle window lapsed (#1029). With no session to
     * describe the park is simply DROPPED — a figure parked against a dash that has since ended is
     * never invented into a later one.
     */
    private fun commitSessionPay(
        region: PlatformRegion,
        pend: PendingSessionPay,
    ): PlatformRegion {
        val session = region.session ?: return region.copy(pendingSessionPay = null)
        return region.copy(
            session = session.copy(runningEarnings = pend.value),
            pendingSessionPay = null,
        )
    }

}
