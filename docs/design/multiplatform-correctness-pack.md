# Multiplatform Correctness Pack — #438 items 5–9 design (co-designed with #245/ADR-0007 + #251)

**Status:** Agreed (dev design session, 2026-07-06). Anchors verified at `master @ 1a934e9c`.
Fable adversarial design-vet: APPROVE-WITH-FIXES — all findings (4 HIGH / 7 MED / 6 LOW)
folded into these specs; the vet confirmed every cited anchor, the `StateJson`
`ignoreUnknownKeys` snapshot-compat claim, the `(type, platform)` timer keying, and the
B1→B2→B3 sequencing.
**Scope:** the deferred co-design items from #438 (items 5/6/7/8/9). Items 1–4 landed in PR #682.
**Constraints honored, not implemented here:** ADR-0007 (canonical offer shape is a list, N≥1)
and #251 (Uber match screen) — see §Scope decisions.

## The unifying diagnosis

All five items are instances of one class: **a global-singular resource commanded by
per-platform signals**. The global R0 flow-diff drives per-platform lifecycle edges (item 5);
one scalar `FlowRegion.pendingOffer` holds what is really per-platform pending state (item 7);
actuation intents carry no platform/offer identity (item 8); one GPS singleton + one session
anchor obey every platform's session diff (item 9); one grace-constant set times every
platform (item 6). Single-platform field testing masks all of them (Principle 8's known
failure mode); every one breaks the moment two platforms run concurrently.

## Locked decisions (dev, 2026-07-06)

1. **Item 7 goes Option B: offers move out of R0 onto `PlatformRegion`** as an owned
   `pendingOffers: List<PendingOffer>` (not Option A's keyed map inside `FlowRegion`).
   Rationale in §Item 7. The dev explicitly waived preserving the #526/#699 accept-stash
   mechanics ("not worried about validating an old PR if we overwrite it anyway").
2. **Item 9 overlap miles: full-to-each + overlap flag.** Miles driven while executing a
   task belong to that task's platform (unambiguous; #528's per-drop deltas). Dual-online
   *idle* miles are claimed in full by **each** live session, flagged as overlap; every
   aggregate/IRS/CSV read anchors to the **real odometer total**, so double-counting is
   structurally impossible at the read site (the #688 cash-tip locked-accounting precedent).
3. **ADR-0007 and #251 are compatibility constraints only.** The list-shaped per-platform
   slot satisfies ADR-0007's "offer shape should be a list, not a scalar"; ADR-0007 stays
   Proposed and its implementation remains #245's own series. #251's rule/parser format
   waits for a desk capture of the match screen. The N>1 state contract — per-offer expiry
   by hash-carrying timer payload, click→offer correlation by per-card target bindings —
   is locked in B3 (vet M5), so #251's remaining work should need **zero edits to the
   mint/placeholder machinery** and lands as ruleset data + parser shape (Principle 8's
   acceptance test, scoped honestly: `OfferLifecycle.kt`'s correlation rule is defined
   now precisely so the capture doesn't reopen it).
4. **Build sequence B1→B6** (below), opus builders from these bounded specs, fable
   adversarial PR checks — the standing three-phase pipeline.

## Item 7 — why Option B (platform-owned offers)

R0's charter is "what the worker is looking at right now — fast, transient"
(`FlowRegion.kt:8-15`). Concurrency admits offers that are *not* what the worker is looking
at: an Uber `SYSTEM_ALERT_WINDOW` offer pending behind a DoorDash screen (#248), an offer
surviving being buried, offers needing expiry timers because their overlay can vanish without
emitting a frame. Those are **per-platform durable state with screen-driven inputs** — the
definition of R2+ (`PlatformRegion`), not R0.

The codebase has been signalling this with band-aids:

- **The #526 accept stash is a hand-maintained mirror** (`PlatformRegion.lastAcceptedOffer`,
  re-armed idempotently every step by `armAcceptStash` with supersession/revocation/expiry
  rules) that exists only because R0 pops the offer before the platform edge fires (the F3
  teardown race). A private copy of another region's state is the exact Principle-5 shape.
  Under B, F3 survival becomes a lifecycle rule on the *owned* offer — the mirror deletes.
- **`offerBelongsToRegion` (#526 FIX2a)** infers offer platform from `sourceRuleId` because
  the slot isn't keyed. Under B ownership is structural: a region only steps on its own
  platform's observations, so its offers cannot be foreign.
- **Offer effects are the odd ones out in `EffectMap.diffFlowRegion`** — session/job/task
  lifecycle effects all emit from `diffPlatformRegion`. Under B they join them.
- **`PendingOffer.returnFlow`** captured from the global flow is wrong for an overlay offer
  (it records the *other* platform's screen); under B it records the owning region's own
  `lastActedFlow`.

Cost comparison: A and B touch the same consumer surface (`EffectMap`, `SideEffectEngine`,
`LiveCardBuilder`, replay tests). B adds the stepper move + stash deletion but avoids paying
the consumer migration twice (A-now-B-later rewrites every touch point again). A-was-audit-
endorsed is provenance, not argument — the audit predates #699's stash, which is the
strongest evidence for B.

**B's one load-bearing prerequisite:** `Observation.UiInput` and `Observation.Loopback`
carry no platform (`Platform.fromRuleId(null)` = Unknown), and post-#682 Unknown
observations never step any region (`StateMachine.kt:75`). Under B an identity-less eval
loopback would never land on the owning region's offer — breaking every offer
notification/TTS, single-platform included. So identity stamping (B2) **must land before
the move (B3)**. `Observation.Timeout.targetPlatform` (#342) is the established pattern —
the same bug class ("timeouts carry no ruleId → the owning region never sees the fire")
was fixed exactly this way.

## Build sequence & bounded specs

### B1 — item 5: per-region lifecycle edges (D3)

**Problem (verified current):** `StateMachine.stepPlatforms` steps only `obs.platform`'s
region but passes the **global** `prevFlow` (`StateMachine.kt:77`). All lifecycle edges diff
`prevFlow.flow → nextFlow.flow` (`PlatformRegionStepper.updateLifecycle` `:530-531`,
`updateJobLifecycle` `:658` accept edge reading `prevFlow.pendingOffer` at `:663`,
PostTask-close `:709`, `updateTaskLifecycle`). Under concurrency `prevFlow` is whatever
platform last touched R0: an Uber `OfferPresented` followed by a DoorDash task frame fires
DoorDash's accept edge and mints a job from Uber's pending offer.

**Change shape:**
- `:domain` `PlatformRegion`: add `lastActedFlow: Flow? = null` (decode-compat default) —
  the last **non-null** `flowObs.flow` this region stepped on. Stamping rules (vet H4):
  - Stamp `flowObs.flow` — **not** `nextFlow.flow`. A FlowObservation with `flow = null`
    (flow-less clicks, notifications — `FlowRegionStepper.kt:34-37` keeps R0's prior flow
    on them) must NOT stamp: `nextFlow.flow` on such a frame is whatever platform last
    owned R0, which would import the exact contamination item 5 removes.
  - The stamp lives in the `step()` wrapper (the `armAcceptStash` slot,
    `PlatformRegionStepper.kt:85-89`), **not** inside `updateLifecycle` — the SessionEnded
    (`:497-519`) and Offline (`:521-527`) early-returns would skip it and change
    single-platform behavior (stale prev on post-PostTask edges).
  - Non-flow observations (Timeout/UiInput/Loopback) never change it.
- `:core:state` `PlatformRegionStepper`: edges derive **both sides** per-region (vet H4):
  `prev = region.lastActedFlow ?: prevFlow.flow` (fallback = legacy snapshots; identical
  single-platform) and `next = flowObs.flow ?: prev` (a flow-less own-platform obs is not
  a flow edge — never diff against the global `nextFlow.flow`). Applies to
  `updateLifecycle`, `updateJobLifecycle`, `updateTaskLifecycle`, and every other
  `prevFlow.flow`/`nextFlow.flow` edge read in the stepper.
- **EffectMap has the same disease and is in-scope (vet H3):** `diffPlatformRegion` is
  called for every platform with the **global** `prevFlow/nextFlow` (`EffectMap.kt:93-105`)
  and keys its own edges on them — the PostTask-exit `DELIVERY_COMPLETED` mint (`:369`),
  `diffTask`'s PostTask `ResumeOdometer` (`:961`), `diffPostTask`'s gate (`:1088`).
  (Concretely: DD sits on PostTask, an Uber frame flips the global flow → DD's
  `DELIVERY_COMPLETED` mints prematurely off the Uber observation.) Fix: these per-platform
  blocks diff `prev.lastActedFlow → next.lastActedFlow` off the region pair; remove the
  global-flow params from `diffPlatformRegion` if nothing legitimate remains.
- The accept-edge `prevFlow.pendingOffer` read (`:663`) gets an `offerBelongsToRegion`
  gate (interim — B3 deletes the cross-region read entirely; the vet confirmed the
  existing function's signature fits this call site).
- **EffectMap #518 guard:** the audit proposed retiring the cross-job completion guard.
  Do **not** blindly retire: it was built for the *same-platform cross-job* leak (#518),
  which B1 does not obviate. The builder verifies what it defends; retire only the
  cross-*platform* half if separable, else leave with a comment noting B1 now guards the
  reducer side.

**Acceptance:** new `:core:state` unit tests — (a) interleaved DoorDash `OfferPresented` +
Uber task observation: no cross-platform job mint/economics/completion; (b) DD on PostTask
+ Uber frame: no premature DD `DELIVERY_COMPLETED`, no duplicate receipt bubble from the
non-observing region; (c) a flow-less own-platform notification does not stamp or fire
edges. All existing tests + replay fixtures green unchanged (single-platform behavior
identical by the fallback).

### B2 — item 8a: observation identity (the load-bearing slice)

**Change shape (follows the `Timeout.targetPlatform` precedent, #342):**
- `:domain` `Observation.UiInput` and `Observation.Loopback`: add
  `targetPlatform: Platform? = null` + `override val platform get() = targetPlatform ?:
  Platform.fromRuleId(ruleId)`, and add `offerHash: String? = null` to `UiInput`
  (Loopback already carries it in `ObservationPayload.EvaluationResult`).
- **`AppEffect.EvaluateOffer` gains a `platform` field (vet M2)** — the engine cannot
  stamp what it doesn't hold: today's `EvaluateOffer(parsedOffer, offerHash)`
  (`AppEffect.kt:143`) carries no platform and `ParsedOffer` has none. Populated at the
  EffectMap emission site, derived from `pendingOffer.sourceRuleId` via
  `Platform.fromRuleId` (the offer's own provenance — NOT `next.activePlatform`, which is
  the same global mirror this pack removes).
- Emission sites populate identity: the eval loopback in `SideEffectEngine` (stamps the
  effect-carried platform + hash); `OfferActionReceiver` and `BubbleViewModel`
  accept/decline dispatches (platform wire + offerHash ride the PendingIntent extras from
  `BubbleManager`; full PendingIntent identity is B4 — B2 only adds the extras and
  threads them through).
- **`ObservationJournal` codec (vet M1):** `internalPayloadOf`/`toObservation`
  (`ObservationJournal.kt:105-115, 170-181`) persist and restore `targetPlatform` (the
  `InternalObsPayload.targetPlatform` field already exists, used only by Timeout) and
  UiInput's `offerHash` — otherwise crash-recovery replay reconstructs identity-less
  observations that Unknown-skip at `StateMachine.kt:75` and post-B3 replay diverges from
  the live run. Additive JSON; old journal rows decode unchanged.
- `EffectMap`'s UiInput block resolves `platform` from the carried identity (no longer
  `next.activePlatform`).

**Acceptance:** dispatching Accept/Decline from the notification and the bubble yields a
`UiInput` with a real platform + offerHash; the eval loopback observation carries the
offer's platform (vet L2: pre-B3 the evaluation still lands in R0 — the test asserts the
*stamping*, not the landing); a journal round-trip preserves both fields. Removes item
4's Unknown-mint sources at the root (the `stepPlatforms` filter stays as
defense-in-depth).

### B3 — item 7: the move (platform-owned offers)

**Change shape:**
- `:domain`: `FlowRegion` loses `pendingOffer` (keeps `flow`/`sourceRuleId`/
  `activePlatform`/`lastObservedAt`); `PlatformRegion` gains
  `pendingOffers: List<PendingOffer> = emptyList()`. `AcceptStash` + 
  `PlatformRegion.lastAcceptedOffer` are **deleted** (see lifecycle below). Old snapshots
  decode via defaults (a live pending offer at upgrade is lost — acceptable, offers live
  ~30s; alpha).
- `:core:state`: offer lifecycle moves from `FlowRegionStepper` into a new
  `OfferLifecycle.kt` (internal extensions on `PlatformRegionStepper`, the
  `JobAcceptFlow.kt` precedent — `PlatformRegionStepper` is at the #237 size ceiling).
  Lifecycle on the owned list, driven by own-platform observations only:
  - **Push/replace/update** on own `OfferPresented` frames (same hash → enrich targets/
    fields; new hash → push; the current single-offer replace semantics apply per
    platform. N>1 concurrent offers per platform become reachable only when #251's
    match-screen parse lands — the list shape is ready, the population isn't).
  - **Click latch** (`handleOfferClick` logic, incl. the #594 decline-commit latch) moves
    here, resolving against the own list. The N>1 correlation contract is locked now
    (vet M5): a click resolves to the offer whose bound `targets` contain the clicked
    node (match-screen rules will bind per-card targets), falling back to the latest
    offer when the list has exactly one entry; ambiguous multi-offer clicks with no
    target match latch nothing (WARN). Populating per-card targets is #251's
    capture-informed work — data, not `:core:state` logic.
  - **Eval landing** (`handleLoopback`) correlates by `offerHash` within the own list.
  - **Resolution + the event contract (vet H2 — the accept event must not vanish):**
    own flow leaving offer-presentation resolves offers per today's outcome rules
    (`resolveOfferOutcome` inputs unchanged). An accept-latched offer **survives** that
    edge as bookkeeping (this replaces the F3 stash), but its **events fire at today's
    edge, not at consumption**: `OFFER_ACCEPTED` (payload unchanged — the analytics
    projector folds it), `CancelOfferNotification`, and the outcome bubble card all emit
    when the own flow leaves offer-presentation with the latch set. The survivor then
    carries a distinct `accepted-pending-consumption` lifecycle state (post-event, purely
    for the mint): consumed by the task edge (`acceptInputsFromPending` becomes the single
    mint source — richer than the stash since the owned offer carries full fields +
    evaluation), cleared on supersession/revocation (#526 FIX2b semantics as lifecycle
    rules), session end, or accept-grace lapse (today's `isStashExpired` window). An
    unconsumed latched offer that lapses emits **no further event** (the accept was
    already logged; the never-minted job is the F3-failure case — WARN, Principle 7).
  - **Expiry (vet H1 — must not kill an accepted offer):** new `TimeoutType.OFFER_EXPIRY`
    timer armed on push — `presentedAt + initialCountdownSeconds*1000`, default TTL
    **120s** when no countdown parsed (vet L5: no rule currently parses a countdown, so
    120s is the de-facto TTL — fine, it can never fire early) — cancelled on resolution.
    The fire **no-ops when the offer is accept-latched** (`isAcceptLatched()` checked at
    fire): both TTLs land inside the accept-grace window, and a fire resolving an
    accepted survivor as TIMEOUT would log a false `OFFER_TIMEOUT` and destroy the mint —
    exactly the #526 regression the survival rule prevents. A latched offer's only exits
    are consumption, revocation, session end, and accept-grace lapse. Timer arming is an
    **effect emitted by EffectMap** (`ScheduleTimeout`, the GRACE_COMMIT mechanism —
    reducer purity, Principle 1), never armed inside the stepper. **The timer's
    `payload` carries the `offerHash` (vet M5)** and the fire resolves by hash — N>1
    offers per platform each hold their own logical expiry even though the registry slot
    is `(type, platform)`; re-arming on each push with the earliest outstanding deadline,
    or a payload-hash no-op on mismatch, is the builder's choice so long as every offer's
    expiry eventually fires.
  - `returnFlow` is stamped from the owning region's `lastActedFlow` (vet L1: fallback
    `Flow.Idle` when a region's first-ever observation is the offer).
- `EffectMap`: the offer blocks (received/replaced/eval-landed/resolved/click-ack) move
  from `diffFlowRegion` to `diffPlatformRegion`, diffing `prev/next.pendingOffers` by
  hash — extracted to a new `OfferEffects.kt` rather than grown in place (vet L4:
  `EffectMap.kt` is already past the #237 ceiling; mirror the `OfferLifecycle.kt` move).
  **Event payload shapes AND emission sites are both contract** (vet H2): same payloads,
  same edges — the projector and the replay oracles see identical event streams for
  single-platform sessions. Two more consumers the move must re-key (vet M3/M4):
  - `diffConfirmDeclineAction` (`EffectMap.kt:1243`) gates #577 quick-decline on
    `next.regions.flow.pendingOffer != null` — re-key on the observing platform's owned
    list or auto-confirm silently dies, single-platform included.
  - The UiInput block's `onOfferFlow` gate (`EffectMap.kt:300`) drops any tap unless the
    **global** R0 flow is `OfferPresented` — which drops the headline multiplatform case
    (acting on a buried Uber overlay offer from its heads-up while DoorDash owns the
    screen). The gate is **replaced** by carried-identity resolution: (platform,
    offerHash) resolves against that platform's `pendingOffers`; found → act, not found →
    WARN + abort to manual. No global-flow precondition remains.
- Consumers: `SideEffectEngine` (eval + notification paths, per offer),
  `LiveCardBuilder.kt:41` (foreground offer = `regions[flow.activePlatform]`'s latest),
  `StateManagerV2` snapshots (shape change only).
- `JobAcceptFlow.kt`: `armAcceptStash`/`acceptInputsFromStash`/`offerBelongsToRegion`
  and the D1c stash-consumption branches in `updateJobLifecycle` delete; the accept edge
  reads the region's own accept-latched offer. Mint/placeholder/swap logic (`AcceptInputs`
  onward) is untouched — it was already source-agnostic.

**Acceptance:** every existing replay fixture green **unchanged** (accept→pickup→dropoff→
complete, two-pickup stack, ghost-offer, F3-race tests re-asserted against owned-offer
survival — same observable behavior: job minted with full economics + placeholders). New
SessionReplay Level-B interleave: DD offer → synthetic Uber overlay offer → DD accept —
no false resolution of either offer, no cross-platform economics, correct per-offer
notifications. Projector/analytics unaffected (payload-compat test).

### B4 — item 8b: actuation identity end-to-end

**Change shape:** per-offer PendingIntent identity via a `data` URI
(`dashbuddy://offer/<platform-wire>/<offerHash>?action=accept|decline` — URIs participate
in `Intent.filterEquals`, unlike extras; no request-code arithmetic), replacing the fixed
request codes 10/11 in `BubbleManager`. Per-offer notification ids derived from
`offerHash` (stable int hash), replacing the single `OFFER_NOTIFICATION_ID = 2`;
`cancelOfferNotification(offerHash)` consumes the hash `AppEffect.CancelOfferNotification`
already carries end-to-end (today dropped at the last hop, `SideEffectEngine`) — including
the inline cancel of the fixed id in `OfferActionReceiver.kt:39` (vet L3, an
easy-to-miss migration site).
`EffectMap`/`SideEffectEngine` resolve a tap by carried (platform, offerHash) against the
owned list; hash mismatch or missing offer → WARN + abort to manual (also closes the
intra-platform stale-banner race). #579 (voice) and #110 (auto-accept) inherit this
contract.

**Acceptance:** two concurrent offers' PendingIntents no longer collide
(`filterEquals`-distinct); a tap on offer A's banner while offer B is current WARN-aborts
instead of acting on B; cancel dismisses only the resolved offer's banner.

### B5 — item 9: odometer arbitration

**Problem (audit, confirmed shape):** four parameterless `data object` odometer effects
(`AppEffect.kt:119-122`) emitted from **each** platform's region diff; handler `startUp()`
= `startTracking(); resetSession()`; one persisted `SESSION_ANCHOR`; one tracking job in
the `@Singleton` repository. Concurrency: second session's Start **zeroes** the first's
accrued miles; first session ending kills GPS under the other; one platform's arrival
pauses GPS mid-drive on the other's leg.

**Change shape:**
- Arbitrate at the diff level: Start/Stop emit from **CrossPlatformRegion** diffs
  (`activeSessionCount` 0→1 → Start, 1→0 → Stop; the vet confirmed the count includes
  paused and grace-window sessions — correct for arbitration).
- **The stationary signal is a specified predicate, not a level guess (vet M6).** Today's
  Pause/Resume are event *edges* — Pause on arrival (`EffectMap.kt:899`), Resume on
  pickup-nav start (`:830`), a new dropoff leg (`:993`), and PostTask entry (`:961-963`) —
  and the naive level (`activeTask.subPhase == ARRIVED`) diverges from those edges at
  PostTask-under-retire-grace (task still ARRIVED but today Resume fired). The per-region
  predicate is therefore: **stationary ⇔ the region's own `lastActedFlow` context is an
  arrived task sub-flow and not PostTask** — i.e. the region is stationary from arrival
  until any of today's Resume edges (nav-start / new-leg / PostTask entry) fires for it.
  Pause emits only when ALL live regions are stationary; Resume when any stops being so.
  The builder proves predicate ≡ today's edges by asserting identical
  Pause/Resume sequences on the existing single-platform replay fixtures.
- **Recovery reconciliation (vet M6).** Odometer effects are recovery-suppressed externals
  (`SideEffectEngine.kt:611-628`, `StateManagerV2.kt:137-144`); today's per-edge Resume
  re-fires on the next live nav edge after a crash, but a level-*crossing* Resume won't
  (the level is already "moving" at restore → no crossing → GPS dead until a full
  Pause→Resume cycle; lost miles = lost IRS deduction). On the first live observation
  post-recovery: if `activeSessionCount > 0`, emit Start/Resume per the current level.
- Drop `resetSession()` from `startUp()`; session anchors become per-`sessionId`
  persisted keys in `OdometerLocalDataSource`; the repository serves per-session/
  per-platform miles flows plus the global total.
- **Route or retire the dormant rule-verb odometer effects (vet L6):**
  `ODOMETER_START/STOP/PAUSE/RESUME` (`SideEffectEngine.kt:453-456`) bypass the
  arbitration; no rule uses them today, but left live a future ruleset could zero a live
  session. Retire (preferred — rules cannot own the odometer) or route through the
  arbiter.
- **Attribution (locked):** task-execution miles → the task's platform. Dual-online idle
  miles → full-to-each live session, **flagged as overlap**; all aggregate/IRS/CSV reads
  anchor to the real odometer total (never Σ per-session miles). The analytics-side
  overlap column/exposure lands with #528's slices, not here — B5 is the arbitration +
  anchor layer.
- **Invariant:** `metadata.odometer` on app_events (the projector's miles input, #314)
  keeps its exact semantics — the raw cumulative device odometer reading. Only
  session-total bookkeeping changes. A payload-compat test guards this.

**Acceptance:** unit tests — dual-session interleave: second Start does not zero the
first's miles; first Stop does not kill tracking while the second is live; Pause requires
both stationary. Single-platform behavior byte-identical. Projector miles unchanged on
replay fixtures.

### B6 — item 6: per-platform timing seam

**Change shape:** `GraceConfig(gracePeriodMs, authoritativeGraceMs, pauseResumeGraceMs,
pauseTimeoutBufferMs, expandSettleMs)` keyed by `Platform` behind `PlatformPreferences`
(#355), seeded with today's constants (`TransitionPolicy.kt:23-48` — 10 000 / 2 500 /
8 000; `EffectMap.kt:67,75` — 1 000 / 500). `TransitionPolicy` gains platform-keyed
accessors (`gracePeriodMs(platform)` etc.); `EffectMap`'s two constants fold into the same
config (`EXPAND_SETTLE_MS` waits on DoorDash's dialog animation — genuinely per-platform).
Mirrors the `TransitionDefaults` per-platform-override precedent. Distinct from #439
(dormant `classify()` path) and #254 (per-driver auto-tuning).

**Mechanism (vet M7):** the pure steppers and EffectMap stay config-*reactive*-free — the
config is an **eagerly-materialized synchronous snapshot** injected as a value provider
(the `strategyRepository.evidenceConfig.value` pattern the engine already uses), read once
per `diff()`/step, never collected inside a reducer. **Accepted tradeoff:** a grace edited
between a live run and its crash replay changes replayed commit timing — today's
compile-time constants are replay-stable, a DataStore-backed value is not. This is
accepted for the single-user alpha (grace edits are rare dev actions; the journal replays
observations, not effect timing) and noted here so the adversarial PR review doesn't
re-litigate it.

**Acceptance:** defaults produce identical behavior (all suites green unchanged); a test
overrides one platform's grace and asserts the other platform's timing is unaffected.

## Follow-ups this design creates (file at build time)

- #251: capture-informed match-screen parse + per-offer click resolution (state contract
  ready; zero `:core:state` edits expected — that's the test).
- #528: consume the per-session anchors + overlap flag for per-drop/GPS $/mi.
- ADR-0007: annotate the offer-shape section — the state layer's slot is now a
  per-platform list (N≥1 satisfied); ADR stays Proposed pending #245.
- Field-test checklist items for B3 (offer lifecycle unchanged single-platform: card,
  notification, TTS, accept mint economics) and B5 (session miles accrue/survive
  pause-resume as before).
