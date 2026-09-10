> **Detailed architecture reference — moved out of `CLAUDE.md` on 2026-09-07 (context-budget trim v3).**
> This file is the full, receipt-level narrative for this layer: every rule, its issue/PR receipt, and the
> reasoning that produced it. `CLAUDE.md` keeps the short summary an agent needs every session; this file is
> where a change's history and invariants are recorded in depth. **The 'every PR ships with context updates'
> rule applies here exactly as it does to `CLAUDE.md`:** when a PR changes an invariant described below, update
> this file in the same PR (and the `CLAUDE.md` summary if it goes stale).
> A `§N` reference below means the sibling numbered file here (or its `CLAUDE.md` summary).

# Multi-Region State Machine (`core/state/`)

Observations reduce into `AppState(regions)` (`:domain`): **`FlowRegion`** (R0 — ground-truth screen
interpretation; the current flow + its provenance, NOT offers), one **`PlatformRegion`** per
platform (session/task lifecycle + that platform's own offers), and **`CrossPlatformRegion`**
(derived aggregates). The steppers (`FlowRegionStepper`, `PlatformRegionStepper`,
`CrossPlatformRegionStepper`) are pure and driven by `obs.timestamp` — never a wall clock — so crash
recovery can replay observations over the last snapshot. `StateManagerV2` hosts the reduction,
exposes `StateFlow<AppState>`, and owns crash recovery; `EffectMap` diffs prev/next state into
`AppEffect`s.

**The dash running total is settle-gated (#1029).** A parsed running total moves
`Session.runningEarnings` only once it has stood **unchallenged on its own surface for a settle
window** — `PlatformRegion.pendingSessionPay` parks the read with a deadline
(`GraceConfig.sessionPaySettleMs`, 3 s, per-platform through `TransitionPolicy`) and the commit is the
stepper's **lazy expiry** on the first observation AT or past it. §2's `parseGlyphCurrency` rejects
the malformed digit-wheel intermediates, but a spin value that lands well-FORMED ($470.00 during a
$16.70 dash) is separable from a real figure only by TIME, which means state. **Repetition was
REJECTED as the discriminator:** the idle dedup hash folds `sessionPay` into `Observation.identity()`,
so `FrameGate.admit` drops every repeat of a settled wheel and a second agreeing read can never
arrive — which is also why the park arms a `SESSION_PAY_SETTLE` wake timer
(`EffectMap.diffSessionPaySettleTimer`, its own `TimeoutType` so the (type, platform) key can't
cross-cancel the `GRACE_COMMIT`/`MODE_RESUME_COMMIT` graces sharing the region): on an unchanged wheel
the timer is the ONLY observation that will ever come. The `PlatformRegion.pendingSessionPay` KDoc is
the canonical statement of the rationale. The rule:
**(a) a park is owned by (FLOW, PLATFORM)** — `PendingSessionPay.flow` + `FlowRegion.activePlatform`;
losing either DROPS it, since no other screen carries a running total and the wake timer would
otherwise commit an unchallengeable figure (the platform half is load-bearing because `stepPlatforms`
steps only `obs.platform`'s region, so two idle screens on two platforms defeat a flow-only test).
Ownership is checked on BOTH the prior and the resulting R0 (#1052 — checking only the result is
blind to a departure this region was never stepped for), a `prevFlow` failure DROPS the park whatever
the observation is, and the returning frame re-parks with a fresh window. A flow-LESS observation
(wake timer, click, loopback, flow-less notification) is never a departure frame, so it orders like a
timer (ownership before expiry) while a flow frame runs the expiry FIRST, letting a park that stood
its whole window commit on the departure frame. Another platform's screen still drops this platform's
park — fail-null, accepted. **A park is FROZEN, not killed, while the dash is not `Mode.Online`, and
its window RESTARTS on the way back (#1052):** a read parks in ANY mode, the lazy expiry SKIPS
wholesale while non-Online (no commit, no drop), and `applyModeTransition` — the single site that
moves `mode` — RE-BASES `since`/`deadline` on the transition INTO Online and re-arms the timer, so
the park stands a full window on a LIVE dash before it may commit. Dropping or refusing the park
while non-Online both STRAND a legitimate figure, because the #605 resume grace holds `Paused` across
the pause-sheet flap, the confirmed resume arrives as a wake TIMER with no frame behind it, and
`FrameGate` never re-admits the identical idle capture. (Hence `diffDeadlineTimer`'s early-wake
re-arm is guarded on `obs.timestamp < deadline`: a park kept across its own deadline would otherwise
re-arm at the 1 ms floor and spin for the length of the pause.) **A re-based park is UNCONFIRMED
(`PendingSessionPay.unconfirmed`) until a fresh readable read on its OWN surface agrees; a null read
or a bare timer at the deadline DROPS it instead of committing** — otherwise the first observation of
the re-based window is the fire itself, committing a figure nothing was on screen to contradict. The
agreeing read clears the flag without extending the deadline; a different value replaces the park.
**An equal-value read on a DIFFERENT surface RE-PARKS there** (the keep arm requires
`flow == pend.flow`), since inheriting the old surface leaves the park owned by a screen that had left.
**(b) BOTH feeds go through the gate** — the on-dash pill (`IdleFields.sessionPay`) and the receipt's
"This dash so far" (`PostTaskFields.sessionEarnings`), read off the SAME wheel via
`parseGlyphCurrency`; for the settled re-render to be admittable at all, `PostTaskFields.dedupeHash`
folds in `sessionEarnings`.
**(c) every NON-gated writer supersedes older parks** — the PostTask-entry pay accumulation and the
dash-summary total drop any park whose `since` predates them; `since >= now` keeps the receipt's own
same-frame park. The dash summary reaches the park by (f), not by this rule: `updateLifecycle` returns
early on `Flow.SessionEnded` with a live session (to arm the authoritative SESSION_END grace), so its
`updateSessionFields` arm is unreachable on the fielded path — the summary's `totalEarnings` is
instead one of the three reads `Observation.sessionPayRead()` recognizes, which is what stops a
pre-"End Dash" park committing on the summary frame and riding into the #596 close-out sweep's
`DELIVERY_COMPLETED.sessionEarnings`.
**(d) comparisons are cent-tolerant** (`accumulatedDeliveryPay + totalPay` is not bit-equal to the
2-dp figure the wheel renders).
**(e) a pending's own wake lapses it by IDENTITY; a frame lapses the park at-or-past** — the timer is
armed for `deadline − obs.timestamp` against a wall clock, so its fire lands ON the deadline
ordinarily and BEFORE it after a step-back, with no frame coming to retry. **Since #1054 round 4 that is settled by IDENTITY, not arithmetic** — the one rule, in
`GraceExpiry` (`isWakeFor` / `graceLapsed`, housed outside the oversized stepper): every arm from the
shared `ModeEffects.diffDeadlineTimer` carries `ObservationPayload.GraceWake(wakeId)` — **a
per-region GENERATION drawn from `PlatformRegion.wakeSeq` through the one `mintWakeId()` helper, not
the deadline** (#1054 round 5; a deadline is not unique — a replacement park computed after a clock
step-back holds the identical `now + settleWindow`, and the superseded wake then committed it after
zero time in its own window). So **a pending's OWN wake lapses it whenever it arrives** (an NTP step
between arm and fire changes the stamp, not the fact that the window elapsed) and a fire from a
REPLACED pending is inert. A new id is minted wherever a pending is created, replaced, re-based or
has its deadline MOVED — `withWakeIdIfDeadlineMoved` is the one installer for a destructive pending
and preserves an identity only for the SAME logical pending (same kind AND unchanged deadline; a
kind change is a replacement, and after a clock rollback a fresh `TASK_RETIRE` really can land on a
standing `SESSION_END`'s deadline, whose nearly-elapsed timer then committed it after ~10 ms); id `0` is the reserved "legacy, unidentified" value a pre-round-5 snapshot
decodes to and never matches, so such a pending is lapsed by its timestamp alone. **A FRAME lapses a grace strictly PAST the deadline and the park at-or-past**, so a
contradicting frame stamped exactly on a grace's deadline still reaches its own cancel arm (a paused
frame cancels a resume #605, a task frame cancels a misrecognized `SESSION_END` #431; on the resume
the alternative also MINTED a session whose Online→Paused follow-up left `diffMode` with no edge, a
dash no `DASH_START` describes). The park needs no such carve-out — rule (f) already makes a
contradicting read on the expiring frame supersede it. **There is no re-arm logic left at all:**
rounds 1–3's early-wake re-arm, equality carve-out and its `(type, platform)` narrowing were four
patches on one substitution of coincidence for identity, and all four are deleted. The arms also
carry `deadlineMs`, so a tail-REPLAYED arm lands on time instead of a full window late
(`OFFER_EXPIRY`/`SETTLE_UI` do not yet — #1076), and the pause-safety net joined the shared diff as
the fourth region timer (see the recovery paragraph).
**(f) a contradicting read on the expiring frame supersedes the park** it contradicts, rather than
committing the stale figure and re-parking the fresh one.
**(g) a `$0.00` read never overwrites a positive total** — the pill renders that placeholder for
seconds before the figure loads, and a dash total never legitimately returns to zero mid-dash.
Deliberately NOT a general monotonic guard.
Pure and platform-agnostic throughout (keyed by the region's own reads, deadlines from
`obs.timestamp`, no `Platform` branch, no wall clock); split immediate/gated fields —
`zoneName`/`sessionType` still write on sight; cleared on session start and end; a genuinely changed
total lands one settle window late by design. **Crash recovery DROPS any restored park**
(`AppState.recoveryHygiene`): its surface is gone and no restore path re-arms its wake timer,
so it would sit forever or be committed by whatever frame happens past its deadline — fail-null
(#745), at a cost of one settle window. It runs at the **LIVE boundary — on the FINAL state after the
tail fold, never on the snapshot** (#1052), because the tail must replay against the snapshot exactly
as recorded and scrubbing at the end also discards a park a TAIL frame re-created (its
`ScheduleTimeout` is a region timer, which the recovery fold SKIPS since #1054 — `reconcileRecoveredTimers`
is their sole armer after a restore). The drop is
**CHECKPOINTED**: `restoreState` writes the cleaned state back through `SnapshotStore.checkpoint`
(unconditional, sharing `maybeSnapshot`'s writer — one encoder) at the restored correlation version,
where snapshot rows REPLACE by key — in memory alone is not durable, and a SECOND restart with no
ordinary snapshot in between would replay the park over a since-grown journal tail. Two corrections
ride on it. **Replay stamps each journal row's own `correlationVersion`**
(`ObservationJournal.tailAfter` returns `JournalRow(cv, obs)`): `StateMachine.step` numbers its result
`prev + 1`, which matches the journal only while it is gap-free, and `append` is a fire-and-forget
queue whose writer LOGS and drops a failed insert — so after a lost row the fold undercounted and the
checkpoint would make that wrong boundary DURABLE, leaving the next restart to re-consume rows it had
already applied. **And the checkpoint is retried and fails LOUD** — `checkpoint` returns whether the
row landed (`write` splits its try: the insert is the durability, the prune is housekeeping),
`restoreState` retries once and then logs at ERROR under the `StateMachine` tag that the cleaned state
is not durable rather than letting `SnapshotStore`'s catch-all swallow it. It proceeds either way
(blocking live observations on a failing DB would trade a bounded, stated risk for total sensing
loss), **and a failed checkpoint stays PENDING, retried on every live observation until it lands**
(`StateManagerV2.recoveryCheckpointPending`, cleared on the first successful write, one DEBUG line per
attempt) — an ERROR alone left the pre-hygiene snapshot standing as the next replay base, and since
the journal and the snapshot share one database, a journal append that persists is direct evidence
the checkpoint can land too.
**Recovery: what is dropped, what is re-based, what is re-armed** (#1054) — the rule being *evidence
is dropped, a decision in flight is re-armed*. `AppState.recoveryHygiene(nowMs)` (the widened
`droppingSessionPayParks`, taking the ONE wall-clock read of the recovery path) **drops** the settle
park and the graced resume; a resume's 8 s window is UN-CONTRADICTED observation, so committing one
after a restart asserts dead process time was nobody contradicting it — and the commit MINTS a
session when the region has none and CANCELS `SESSION_PAUSED_SAFETY` through `diffMode`. It **re-bases**
`pendingDestructive` to serve its REMAINING window live (`remaining = (deadline − base) − (lastSeen −
base)` where `base = servedFrom ?: windowFrom ?: since` — `windowFrom` is the observation that last set or
moved the deadline, so a tighten after a clock rollback keeps its window (#1054 round 7), `lastSeen` = `AppState.timestamp`, `since` untouched per
#732): a restored grace has observed NONE of its window, and dead time is not un-contradicted time —
the collapsed receipt's expansion (#1033) and the misrecognized summary's contradicting task frame
can still land, where round 3's re-arm at the stale deadline fired at the 1 ms floor and committed
first. **`PendingDestructive.servedFrom` is what makes that re-base a FIXED POINT** (round 5): the
re-base moves the deadline but not `AppState.timestamp`, so without a second anchor a restart before
any new observation handed the whole elapsed dead time back as fresh window — a 2.5 s grace restored
twice became 193 500 ms, and a crash loop stretched it without bound. Idempotence is what makes a crash LOOP safe — each restart
serves the same remaining window — but the hygiene runs exactly **once** per restore (round 6):
`finishRestore` checkpoints and installs the SAME state. Round 5 ran it twice so the served window
would start at the live boundary, which made the durable base describe a different deadline from the
one the process was running, and an observation that was a no-op live then COMMITTED when the next
restart replayed it. The checkpoint write's own latency is deducted from the window instead —
milliseconds ordinarily, and a stated cost. **A live deadline MOVE re-anchors the accounting:** `withWakeIdIfDeadlineMoved` clears `servedFrom`
and stamps `PendingDestructive.windowFrom` with the MOVING observation, so the hygiene reads
`servedFrom ?: windowFrom ?: since`. Neither of the other two anchors survives a wall-clock rollback:
a stale `servedFrom` sits in the new deadline's future, and `since` — the historical arm instant #732
stamps the commit at — can be AHEAD of a tighten that landed behind it, both computing zero remaining
for a window that was never served.
`AppState.pendingDeadlineTimers()` then **re-arms** exactly two things: that grace, and the
**pause-safety net** at the platform's own deadline, AS-IS with dead time included (it is the
platform's countdown on the platform's clock). That net is state now — `PlatformRegion.pauseSafetyDeadline`,
stamped by `applyModeTransition` into Paused and cleared on the way out, armed/cancelled by
`EffectMap.diffPauseSafetyTimer` like the other three region timers — because before round 4 its
deadline lived ONLY in the engine's in-memory timer map, so a restore into Paused had no timer of any
kind and a pocketed phone whose countdown ended kept the session live for the next morning's dash to
RESUME. **A replayed REGION timer is never executed** (round 5): `SideEffectEngine` skips a
`TimeoutType.REGION_TIMERS` arm or cancel while `recovering == true`, because such an arm is
scheduled against a replayed frame's timestamp and so fires at the 1 ms floor mid-recovery — logging
a `Timer Expired` WARN into the shareable log for a pending the hygiene may be about to drop (P7),
and not merely inertly, since `graceLapsed`'s timestamp arm can still COMMIT. The recovery reconcile
is their sole authoritative armer, which is also why round 4's cancel-after-the-fact is gone: by the
time it ran, the coroutine had already fired. A **legacy payload-less `SESSION_PAUSED_SAFETY`** fire
is honoured when the region has no `pauseSafety` of its own **or when it lands at/after the armed
one's deadline** (round 6) — a payload-less fire is by construction a pre-round-5 arm, and a
master-era tail can contain the PAUSE FRAME itself, so the replay may have just reconstructed the
very net whose countdown produced the fire. Refusing it left a dash master had ended checkpointed as
still running; one landing strictly BEFORE the armed deadline is still refused. **Two stated
residuals** (round 7): a legacy fire whose OLD build's clock rolled back between arm and fire lands
before its reconstructed deadline and is refused, losing that terminal transition — identity cannot
be recovered across a wall-clock discontinuity, and the exposure is one process lifetime (the journal
tail is 48 h; every arm from this build's first run carries an id); and "payload-less means
pre-round-5" is not strictly true, since the rule-driven timer API can emit a payload-less fire of any
type (no checked-in rule does so for safety — a provenance residual on #1076). `initialize()` awaits the merge collector's SUBSCRIPTION before
`restoreState`, because `engine.events` is a `replay = 0` `SharedFlow` and an already-elapsed re-arm
fires at once — emitted with no subscriber it would be dropped silently. Everything goes out on the
LIVE path (`recovering = false`) before `_state.value` is set, `durationMs` is the bare 1 ms floor
(`deadlineMs` is the authority), and one counts-only INFO line reports the re-arms
(`Recovery re-armed N grace timers`, tag `StateMachine`). Known gaps left open, tracked as **#1076**:
a tail-replayed `OFFER_EXPIRY` / `SETTLE_UI` still fires late and a restored pending offer gets no
fresh `OFFER_EXPIRY`. (The third listed gap — "the Offline arm can overwrite a standing
`TASK_RETIRE` with `SESSION_END`" — is CLOSED by #1078's absorb rule below; the re-based grace
carries `absorbedRetireSince` across recovery, pinned by `StateManagerV2RecoveryHygieneTest`.)

**Graces.** Destructive commits are graced through the unified `pendingDestructive` slot and woken
by `GRACE_COMMIT` timers (#431), including short authoritative windows for the dash summary AND the
delivery receipt; a separate `pendingModeResume`/`MODE_RESUME_COMMIT` grace debounces a
screen-implied Paused→Online *resume* so a pause-sheet-over-receipt can't flap `DASH_PAUSED` (#605).
**The delivery-receipt window is receipt-SHAPE-keyed (#1033 layer 1):** a COLLAPSED receipt
(`parsedPay == null`) arms `GraceConfig.receiptExpandGraceMs` (8 s, per-platform through
`TransitionPolicy`) instead of the 2.5 s `authoritativeGraceMs`, because a collapsed receipt states a
total and nothing else — a completion committed off one is priced by the #691 `OFFER_PAY` estimate,
and the fielded 2026-08-23 expansion landed 3.9 s later, 1.3 s past the old window. An EXPANDED frame
keeps 2.5 s and, through the arm's `minOf(existing, new)`, TIGHTENS the widened deadline the moment
it arrives; a PostTask frame that parses no receipt at all keeps the pre-#1033 timing (fail toward the
old behaviour). Cost: a receipt that never expands commits ≤ 5.5 s later; the receipt bubble is
unaffected (it fires on the PostTask frame, not the commit) and the #596 T2 next-offer guard tolerates
it. **Layer 1 is also the ONLY path that lands an expansion in the STACKED shape** (accept the next
offer off the receipt, then expand it): layer 2's ownership rule refuses that case outright — see §5.

**A dash end ABSORBS a standing task retire (#1078) — it never discards it.** The destructive slot
holds ONE pending, and both `SESSION_END` arm sites used to REPLACE whatever stood in it. Fielded
2026-09-08 (build 8028691a, db seq 2007/2008): the dropoff screen gave way to `dash_along_the_way`,
arming a `TASK_RETIRE` at 17:43:43.859 (deadline +10 s); the dash summary armed a `SESSION_END` over
it at 17:43:52.265 — **1.614 s before the retire's own deadline** — and the retire, with it the
evidence that the delivery finished, was gone. At the `GRACE_COMMIT` (17:43:54.707) `endSession`
force-stamped `completedAt`, and `mintQualified`'s amdt-#5 T3 guard correctly refused to mint a row
for an unqualified force-stamp: **$21.00 delivered, no `DELIVERY_COMPLETED`, no row, not one WARN** —
only the `DASH_STOP` + `DELIVERY_CONFIRMED`-same-instant signature.

The fix is a new nullable field, `PendingDestructive.absorbedRetireSince` (`:domain`, additive and
snapshot-compatible; only meaningful on `SESSION_END`, a `TASK_RETIRE` never carries one). Both arm
sites — the summary arm (`updateLifecycle`, `Flow.SessionEnded`) and the Online→Offline mode arm —
absorb through **one predicate**, `PlatformRegionStepper.absorbableRetireSince()`, so they can never
disagree about what a teardown may honor. It takes a standing `TASK_RETIRE`'s own `since`, and only
when that retire's PROVENANCE (`armedFromFlow`, #596) says the task it retires actually finished:

- `Flow.OfferPresented` is **refused** — the dasher stepped off the task to deliberate on a mid-route
  add-on, so that drop is not delivered. `retireActiveTask` already refuses to close the job on such
  a retire; honoring it at a dash end would mint a completion AND an offer-pay share for undelivered
  work, which is #1078's own failure mode pointed the other way.
- `Flow.PostTask` is **refused** — a receipt-armed retire's completion is already minted by the
  PostTask-exit block on the frame that left the receipt. Honoring it again at the teardown emits a
  SECOND raw `DELIVERY_COMPLETED` for the same task: the engine's per-task `effects_fired` key hides
  it live, but a replay (and any consumer counting raw effects) double-counts.
- A **null** `armedFromFlow` is refused: unprovenanced evidence is not evidence (#745).

**The #615 arrival gate is an ADDITIONAL condition** (round 4). Provenance says the dasher LEFT the
task; it does not say the order was delivered. The retire's own T1 close already demands arrival
(`isJobPhysicallyComplete` counts only a dropoff with `arrivedAt != null`) and absorption was walking
straight past it — an Idle-armed retire on a drop the dasher was still NAVIGATING to was honored at
the summary, minting a `DELIVERY_COMPLETED` with a null arrival and a #691 offer-pay estimate riding
it. So the region's `activeTask` must also be an ARRIVED, un-unassigned DROPOFF. Note the difference
from the rejected rule 2 below: there arrival was a SUBSTITUTE for a standing retire; here it is an
extra condition on rule 1 — a watched retire AND evidence the dasher reached the doorstep.

The tighten branch keeps an already-absorbed value (`existing.absorbedRetireSince ?: absorbed`),
exactly as it keeps `since`.

**A "rule 2" was designed, reviewed and REJECTED** (round 2): absorbing at the AUTHORITATIVE summary
whenever an ARRIVED, un-unassigned dropoff was still active would have covered the retire-less 09-05
sighting on the reasoning that the platform does not let a dasher end a dash with an active order —
but it fabricates a completion, and an offer-pay share, for an arrived drop that was cancelled or
unassigned through a path the sensor never captured, and for a misrecognized summary. Fail-null beats
fail-wrong (#745): the machine records only a delivery it watched finish, so that shape stays on the
T3 side and is now made LOUD by the #1095 tripwire instead.

**An authoritative abandon disowns an absorbed retire.** The lazy-expiry branch's #736 same-frame
supersession is extended, and since round 3 it has ONE owner, `disownAbsorbedRetire`, called from
BOTH the commit frame and — because a `task:unassigned` frame arriving INSIDE the window falls
through `updateLifecycle`'s `Mode.Offline` early return, where `abandonActiveTask` is unreachable —
from a site just ahead of that return. It has two inseparable halves: `absorbedRetireSince` is
cleared (so `endSession` force-stamps and the T3 guard refuses the mint) **and** the active task is
marked `unassignedAt`. The second half is load-bearing: `EffectMap` judges the mint against the
PRE-step region, whose pending still carries the absorbed value, so clearing alone is invisible to
`retirePendingForMint` and the close-out sweep would mint anyway. The marker is answered by firewalls
that already exist (the close-out `unassignedAt` skip, `isJobPhysicallyComplete`,
`detectAcceptMismatch` counting the order as accounted) and is the truthful record, which is also
what lets `TaskEffects` emit the proper `TASK_UNASSIGNED` off it. The dash really did end; the order
really was abandoned; no money is fabricated.

**A completion the PostTask exit already minted is recorded PER TASK** (round 4). The exit block
mints the drop the receipt was about and leaves it ACTIVE — its `completedAt` is stamped only when
the retire commits — so on a later step neither `recentTasks` nor the current pending's provenance
can say the mint happened, and a re-entry (receipt → back to the same dropoff screen → idle → dash
end) let the teardown honor a FRESH, absorbable retire and emit a second raw `DELIVERY_COMPLETED`.
Round 3 derived the answer from the job-wide `exitedPostTask` flag plus `lastAnnouncedPostTaskTaskId`
and that was wrong three ways — in a stacked job D1's exit latches the flag while D2's receipt moves
the announce id, so D2 read as already-minted before it ever exited (a silent LOSS); an identity-less
task whose exit the #498 firewall refused read as minted; and a `ParsedFields.None` PostTask that set
no announce id read as not-minted, leaving the double emission in place. So the eligibility is now
ONE pure function, `exitMintCandidate` (the acted `PostTask` → non-`PostTask` edge, the
`receiptSubjectTaskId` subject, #596 amdt 2's unscoped-fallback guard, and the #564 phase / #653+#498
identity / #736 unassigned firewalls — the emitter has no eligibility logic of its own left), and the
stepper's `stampPostTaskExit` calls that SAME function on the same acted-flow edge (`actedFlowEdge`)
and records the taskId in `JobReceiptAnchors.exitMintedTaskIds` (`:domain`, additive, defaults to
empty). `mintedAtPostTaskExit(taskId)` is a set lookup, read by the close-out sweep's arm (b) and by
the #1095 tripwire's evidence. One stated caveat: a rule-driven `TASK_COMPLETED` trigger override
replaces the mint at the emitter and the stepper cannot see it, so an overridden exit is still
recorded — failing toward not double-emitting, and no checked-in ruleset declares one.

**One session-attribution rule for a closing job**, `closingSession(prev, next)` (round 3, corrected
in round 4): `next` when the session SURVIVES the step — a #1029 settle park can commit on the very
observation that closes a job, so `next` holds the total this step decided — `prev` when the session
ends or changes, because the closing job lived there, and `next` again when nothing was live before
the step (the first captured frame of a dash can mint the session and the task together). The
close-out sweep's id AND `sessionEarnings`, `diffReceiptReprice`, `diffJobClose` and `diffTask`'s
PREDECESSOR-task edges (`DELIVERY_CONFIRMED` on the retiring task, `TASK_UNASSIGNED` + its bubble)
read it. `diffTask`'s NEW-task edges (`PICKUP_NAV_STARTED`, arrivals, nav starts, their bubbles) keep
the live next-first read: on a step that ends dash A and mints dash B, B's own first pickup belongs
to B.

`endSession` then honors it: `completedTask = activeTask.completedInline(honoredAt)` (the one
spelling of the retire copy, `JobCompleteness.kt`) instead of the unqualified
`copy(completedAt = timestamp)`. **One owner** answers "does this region carry retire evidence for
its active task" — `PlatformRegion.retirePendingForMint()` (`DeliveryCompletionEffects.kt`): a live
`TASK_RETIRE`, or a `SESSION_END` with a non-null `absorbedRetireSince`. Every `mintQualified`
caller routes through it (the close-out mint + the #996 amendment-B completeness mask,
`ReceiptRepriceEffects`'s cached-receipt denominator, and the #1095 tripwire's evidence), so the
predicate cannot drift between sites. **The T3 guard is untouched:** an end with nothing absorbed
still force-stamps and still mints nothing. The cancel path needs no code — a task-flow frame inside
the window clears `pendingDestructive` outright, and the absorbed value goes with it.

**#1095 — the #810 job-close tripwire sees EVERY close edge.** `JobCloseEffects.diffJobClose` v1
required the session to survive the step with the same id, which put the `endSession` teardown out
of scope — the second silencer on the 09-08 loss. That guard existed purely to stop the same-step
stale-end + fresh-mint shape being logged against the NEW session, so it is replaced by the fix it
stood in for: the event and WARN are attributed to the job's OWN session
(`prev.session?.sessionId ?: next.session?.sessionId`). Two more changes make the teardown legible:
its evidence is the mint-qualified view (`next.recentTasks` masked by `mintQualified(prev, …)`,
widened in round 4 by `mintedAtPostTaskExit` so a normal receipted delivery whose dash ended before
the receipt's retire expired is not read as a lost drop), so `endSession`'s force-stamp cannot
silence its own close; and the pure detector gains
`minAccepts: Int = 2`, with the caller passing **1** for exactly one shape — the session ENDED, the
qualified evidence holds NO completed dropoff of this job, and the job DID reach an ARRIVED dropoff.
That names "the dash ended over a drop the dasher stood at and nothing was ever minted for it" in
lifecycle evidence alone (principle 8 — no platform literal), and deliberately leaves three
neighbours at the 2-floor: a coarse `task:active` job that never rendered a dropoff, a pickup-only
teardown, and a receipt-backed delivery with no arrival frame (its completion is qualified). WARN text and level are
unchanged (P7: ids, counts and hash prefixes only), as is the `EffectMap` ordering — `diffJobClose`
still runs after `diffDeliveryCompletion` so the store evidence is complete (see §5's Tier-1 note).

**Offers are platform-owned** — `pendingOffers: List<PendingOffer>` on the `PlatformRegion` (#438
B3, moved off the shared global R0 slot so concurrent platforms don't collide; N≥1 satisfies
ADR-0007, N>1 waits on #251). The lifecycle (`OfferLifecycle.kt` on the stepper, `OfferEffects.kt`
on `EffectMap`) runs on THIS platform's own observations: push/replace/enrich on `OfferPresented`,
click-latch (#594 decline-commit), eval-land by `offerHash`, resolve on leaving offer-presentation.
**Offer identity is presentation-scoped (#830):** `ParsedFieldsFactory.buildOffer` derives a
`ParsedOffer.presentationKey = sha256(storeNames|orders.size|orderTypes)` — the STABLE subset —
alongside the churn-prone `offerHash` (which folds in the ticking pay/distance/time). On a
live-re-quoting card (Uber re-renders pay/miles/minutes every few seconds), a different-hash frame
carrying the SAME non-null `presentationKey` as the offer currently on screen is an
**enrich-as-variant** (update `offerHash`/`offerFields`/targets, CLEAR evaluation → re-eval, but
KEEP `presentedAt` + all click latches + the speak-once `PendingOffer.firstEvalLandedAt`), NOT a
replace — so no `OFFER_TIMEOUT("Replaced by new offer")`, no discarded accept latch, and
`AppEffect.SpeakOffer` fires ONCE per physical presentation while `PostOfferNotification` still
live-updates every landing. The `OFFER_EXPIRY` re-arm on a variant keeps the deadline anchored on
the original `presentedAt` (churn can't extend the TTL) and carries the new hash; the stale old-hash
heads-up is cancelled (`BubbleManager.offerNotificationId` is per-hash). Fail-CLOSED +
platform-agnostic (P8): a null `presentationKey` degrades to replace-on-any-hash-change — a false
MERGE is impossible, and there is no `Platform` branch. A genuinely different presentation still
REPLACES. **Presentation KIND (#881):** a ruleset may parse `offerKind: match|direct` (Uber
discriminates on the card's own CTA); it rides `ParsedOffer.offerKind` → `PendingOffer.offerKind` (a
read-through, never a copy) → `OfferPayload.parsedOffer`, is NOT an input to scoring / `offerHash` /
`presentationKey`, and is null on any platform whose ruleset lacks the concept. Its one consumer is
the replace path: a DIRECT offer landing over a MATCH one is the platform's EXPECTED
direct-preempts-match hand-off, so it keeps the same event type + emission edge but logs
`"Superseded by direct offer"` and suppresses the "(offer replaced)" bubble; every other pair keeps
the older narration. Kind-keyed, never `Platform`-keyed. **Display vs identity on a stacked card
(#882):** an Uber `Delivery (N)` card renders the type chip above ONE visible store line, so the
chip used to win both store reads. The uber rule negates `^Delivery \(\d+\)$` on the **top-level**
`storeName` list ONLY (`ParsedOffer.displayStoreName`); the `orders[]` list DELIBERATELY still reads
the chip, keeping `presentationKey` chip-anchored and therefore immune to the card cycling which
store it shows (mirroring the negation would trade a display bug for a replace-storm; the
divergence is documented at both sites). Every human-facing read goes through the
`ParsedOffer.displayStores`/`displayStoreText` SSOT (evaluator `merchantName` → TTS/ledger/store
resolution, `FlowCardSnapshot.Offer.storeNames`, the fold's eval-less fallback), which prefers ≥2
real order stores, else the card headline, else the order list — so DoorDash (no top-level
`storeName` parse) is byte-identical.

**Accept survives the offer-presentation edge** as an `acceptedAt`-marked accepted-pending-
consumption entry (this REPLACED the #526 accept stash + `AcceptStash` + `offerBelongsToRegion`,
all deleted): `OFFER_ACCEPTED` fires at the edge, and the survivor is minted by the task edge
(`acceptInputsFromPending`) with full economics + pre-created placeholders even across the
`waiting_for_offer` teardown race — cleared on supersession/revocation/session-end/accept-grace
lapse. The accept grace is **per-platform** (`GraceConfig.acceptGraceMs` — DoorDash 120s / Uber
600s, #762 D2), which also added the **phase-less `task:active` flow** (`Flow.TaskActive`, a coarse
platform's in-job token — a task flow for accept-consumption/mode-Online, but `toTaskPhase()`→null
so it is inert to task lineage; leaving offer-presentation to it infers a click-less accept only
from a non-task `returnFlow`, the ambient-screen guard). A per-offer `OFFER_EXPIRY` timer
(hash-carrying payload, EffectMap-armed, no-ops on an accept-latched offer) resolves an overlay
offer that vanishes without a frame.

**Placeholders and store lineage.** An accepted offer pre-creates **symmetric placeholders**: one
dropoff per order AND one PICKUP per distinct store, each dropoff stamped with the minting accept's
`Task.mintedByOfferHash` (#997's per-drop↔offer provenance HINT, not an identity: placeholders
activate blind first-open, so the money side treats the drop's reconciled STORE as authoritative and
consults the stamp only when no store resolved — see §5). Pickup/dropoff screens resolve onto them
by hint/customer-hash keeping the offer-owned taskId; a stacked pickup that displaces another emits
`PICKUP_CONFIRMED`; and a dropoff's store is re-attributed from its pickup lineage
(`reconcileDropoffStore`, #526/#733/#745) via the **customer-hash join** (normalized,
cross-surface-stable): the drop's `customerNameHash` joins its pickups — all matches map to ONE
store → resolve it (exact single-match, unconditional); matches span ≥2 stores → the
earliest-confirmed store, but ONLY when this is the **sole activated dropoff** carrying that hash
(≥2 activated drops sharing it → INCONCLUSIVE → fall through, fail-null beats fail-wrong); a
0-match/inconclusive drop falls back to store-name-token match, never to a store outside its own
lineage. The former structural single-drop arm was DELETED (#745 — its
placeholder-count==physical-drops premise desyncs on per-order placeholders + the unparsed-offer
`dropoffCount=1` fallback). That same desync also left a same-customer multi-order job's leftover
TBD placeholder outstanding forever, defeating `isJobPhysicallyComplete` so the #596 T2 guard never
fired and the next offer folded into the finished job (the job-61 class); #749 added a **per-customer
coverage arm** (`JobCompleteness.kt`, evaluated only when the strict arm fails) that proves
completion from the pickup side — when pickups map 1:1 to orders at distinct stores their hash set
IS the job's customer set, so "every customer hash has a finished, arrived drop" ⟺ complete,
placeholder count irrelevant. The D6 join-miss WARN is edge-gated once per taskId
(`PlatformRegion.lastJoinMissWarnTaskId`).

**Unassign-via-help** (`Flow.TaskUnassigned` = wire `task:unassigned`, #736) is an **inline
(ungraced) abandon** of the active task: `abandonActiveTask` marks `Task.unassignedAt` and leaves
`completedAt` **null**. The `PICKUP_CONFIRMED` close-out sweep's `unassignedAt == null` FILTER is
the load-bearing defense that stops the seq-71 fabrication, and an explicit `unassignedAt == null`
filter in `isJobPhysicallyComplete` keeps an abandoned drop from ever reading as delivered —
whether it carries a null `completedAt` (inline shape) or a grace-stamped one (retro shape) — so
un-accounted keeps the job OPEN, never a false complete. It retires the abandoned drop's own
placeholder (by `taskId` for a dropoff-phase abandon — never a hash-join that could over-remove a
colliding sibling; by customer-hash for a pickup-phase abandon) and closes a single-order job on the
confirmation frame. Two commit shapes are handled: a **same-frame** abandon (an overdue
`TASK_RETIRE` lapsing ON the `task:unassigned` frame is dropped, not committed, so the abandon
supersedes it) and a **cross-frame retro-mark** (a help-flow retire that already committed on a
prior frame, edge-gated to the ENTRY into `task:unassigned` so a second consecutive frame can't walk
the mark onto another task; it stamps `unassignedAt` on the most-recently-retired task of the
still-open job — any phase, #752 — leaving `completedAt` intact and retiring that drop's placeholder
by `taskId` so a multi-dropoff job can still close). `Job.tasks` is reconciled EVERY step as the
**non-unassigned** lineage mirror (#752): an unassigned task lives on only in `recentTasks`, never
as an outstanding placeholder — so the #691 estimate denominator unions `job.tasks` ∪ the job's
`recentTasks` dropoffs (`OfferPayFallback.owedDropoffs`), letting a quoted-but-unassigned order
still divide the offer-pay split. It emits ONE `TASK_UNASSIGNED` event (own payload, read-model-inert
via the projector's liveness `else` arm, per-`taskId`-idempotent, fired for both the inline-abandon
and the retro-mark shapes) + an "Unassigned: <store>" bubble. A misread self-heals when a genuine
same-order frame resumes the task (the resume copy sites clear `unassignedAt`) — but ONLY while the
job stayed OPEN; after a single-order abandon the job closes and a later same-order frame mints a
NEW jobId the resume lookup can't reach (documented residual).
