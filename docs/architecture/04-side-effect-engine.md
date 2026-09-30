> **Detailed architecture reference — moved out of `CLAUDE.md` on 2026-09-07 (context-budget trim v3).**
> This file is the full, receipt-level narrative for this layer: every rule, its issue/PR receipt, and the
> reasoning that produced it. `CLAUDE.md` keeps the short summary an agent needs every session; this file is
> where a change's history and invariants are recorded in depth. **The 'every PR ships with context updates'
> rule applies here exactly as it does to `CLAUDE.md`:** when a PR changes an invariant described below, update
> this file in the same PR (and the `CLAUDE.md` summary if it goes stale).
> A `§N` reference below means the sibling numbered file here (or its `CLAUDE.md` summary).

# Side Effect Engine (`app/.../state/effects/`)

`SideEffectEngine` executes `AppEffect`s with `effects_fired` idempotency dedup (recovery-aware),
runs the evaluation loopback (offer eval → `OfferEvaluationEvent` back into the machine), and owns
the fail-closed action gates (#417): live `PermissionTierChecker` + the capability consent gate on
automation-triggered `RuleAction`s (grant store: `RuleCapabilityRepository` over the
`rule_capability_grants` DataStore; **no auto-grant (#843)** — `reconcile` only publishes the
enumeration, every capability lands *undecided* until the user opts in via the consent prompt).
Handlers: `OdometerEffectHandler`, `ScreenShotHandler`, `TipEffectHandler`, `TtsEffectHandler`,
`UiInteractionHandler` (package-scoped, label-verified `RuleAction` taps — the only path that ever
clicks a third-party app, #425), `OfferActionReceiver` (notification Accept/Decline actions).

**Every odometer fix is gated (#1057/#918).** `OdometerRepository` used to add ANY inter-fix
displacement over 5 m straight into the persisted cumulative total, so one spurious fused fix ~1,457 km
away added **905.37 mi in 18.4 min** (2026-09-03) — freezing `netProfit −302.73` on a $22.95 delivery
and leaving every session since 905 mi high — while indoor multipath jitter accrued phantom miles at a
parked desk, both silently. The pure `:domain` `OdometerFixPolicy` now judges each fix against the last
**accepted** one (`MIN_DELTA_METERS` 5, `MAX_ACCURACY_METERS` 50, `MAX_SPEED_MPS` 67 ≈ 150 mph,
`MAX_DELTA_WITHOUT_TIME_METERS` 2 000 when no clock is shared by both fixes — `Coordinates` gained
nullable `accuracyMeters`/`timestampMs`/`monotonicMs`, which `FusedLocationDataSource` had been dropping
on the floor) and returns Accept / Reference / Ignore / Reject: **neither an Ignore nor a Reject moves the
reference**, so a teleport anchors nothing AND slow creep inside the jitter floor still accumulates as
one later Accept, and a poor-accuracy fix cannot even seed the reference (fail-null). Four properties
carry the design (round 2, all four Astra-found). **`INVALID_FIX` comes first:** every bound is a
comparison and every comparison against NaN is false, so a non-finite/out-of-range fix used to fall
THROUGH into `Accept(NaN)` — one NaN in the cumulative total destroys it permanently — and
`Coordinates.distanceTo` clamps its haversine intermediate into `[0, 1]` so a near-antipodal pair can
never produce that NaN in the first place. **Elapsed time is measured on the MONOTONIC clock**
(`Location.elapsedRealtimeNanos`) wherever both fixes carry one, falling back to wall time only for
clock-less sources: `Location.time` is settable, and an NTP step-back mid-drive used to reject every fix
(`NON_MONOTONIC_TIME`, then `IMPLAUSIBLE_SPEED`) for ~110 s of silently lost mileage per correction.
**An `Ignore` refreshes the reference's TIMING, not its position** (the repository does it, so the
policy stays a pure function of two fixes): the reference is an accrual anchor, not a last observation,
and leaving its timestamp stale let a 1,457 km teleport after seven parked hours imply a plausible
57.8 m/s. And **rejection logging is EPISODE-gated** — poor reception is a condition, not an event, and
one WARN per rejected fix is 720–1,800 lines an hour that drown the exceptional rejects (principle 7):
a streak of consecutive rejections WARNs on entry, WARNs again every `STREAK_REMINDER_EVERY` = 100, and
closes with ONE INFO on recovery, while a rejection whose reason DIFFERS from the streak's opening one
still WARNs individually. The repository owns only the side effects — every line carrying **numbers and
the reason enum only** (a latitude/longitude is the dasher's location PII and is never logged, at any
level) plus ONE bounded DEBUG `Odometer fixes:` summary per 100 judged fixes. **Standing residual:** the gate is
forward-looking only — the pre-fix +905 mi offset in the persisted total, and delivery row 1801's
frozen economics, are **not** auto-repaired (frozen economics are immutable by design; a rebase
mechanism for the cumulative total is an open dev decision on #1057).
**The evaluator fails CLOSED on a missing input (#936).** A `?: 1.0` distance fallback invented a
favourable **one-mile trip** — near-zero operating cost, near-zero drive time, an inflated `$/hr` —
biasing the verdict toward ACCEPT precisely where the input was least trustworthy (the #827 class of
seam). Distance is now the `0.0` "no distance" sentinel, and an offer that would otherwise be
*scored* without one returns the existing no-verdict shape instead — `OfferAction.NOTHING` / score 0
/ `OfferQuality.UNKNOWN`, mirroring the no-scoring-rules return. The three **distance-independent**
verdicts stay in front of it (shopping opt-out #762 D12, protect-stats, merchant BLOCK). The
returned economics are the honest subset: real gross pay and the economy's own
`operatingCostPerMile` (a profile constant the projector freezes as the session cpm — zeroing it
would make the session's deliveries look cost-free), with every distance-derived figure an explicit
`0.0` placeholder and `netPayAmount` == gross (no cost deducted ≠ a one-mile cost).
**`OfferEvaluation.hasDistanceMetrics` is the one predicate consumers branch on** so those
placeholders are never rendered as measurements — `FlowCardSnapshot.Offer.from` (bubble card +
heads-up custom views get null → their existing `—` / hidden gauge), `toNotificationSummary`,
`TtsEffectHandler` (a no-verdict utterance, not "zero dollars an hour"),
`JobAcceptFlow.acceptInputsFromPending` + `FlowCardMapper`'s #460 co-hero, and
`RecordFolds.foldOffer` (null frozen estimates, so `AVG(score)`/`AVG(estDollarsPerHour)` aren't
dragged toward a fabricated 0). The #659 fuel/non-fuel split was already guarded at the fold
(`distanceMiles > 0.0` → null split → 3-step waterfall), so no NaN can reach a frozen economics
column and no `PROJECTOR_VERSION` bump was needed (historical evaluations froze the fabricated 1.0
mile, so a refold reproduces them byte-identically).
**Dedupe granularity is the rule's to declare (#859).** The durable `effects_fired` row is "at most
once per 48h"; a rule effect that declares its own `throttleMs` opts OUT of it and into that
declared window (the engine's wall-clock throttle, keyed by the same `effectKey`) — one declared
value, one gate. Without that the strictly-stronger row silently subsumed the declaration, making a
*stable* dedupe key destructive (`offer-ss-{presentationHash}` would capture one offer per store per
48h). Residual: the throttle map is in-memory, so a process restart re-arms it (at most one extra
capture). App-emitted keyed effects (bubbles, sessions, `EffectMap` captures) declare nothing and
keep the 48h idempotency unchanged. Evidence **filenames** are sanitized at the one evidence gate
(`EvidenceFilename.sanitizePrefix`): a rule's `"Offer - {storeName}"` whose field parsed null saves
as `Offer`, never the literal token — a fail-safe under, not a replacement for, the
`ParseOutputGoldenTest` arg-template lint that still flags the un-interpolating rule.
**The engine is a data-integrity boundary and must never die silently (#909).** `AppEffect.LogEvent`
is the ONLY writer of `app_events`, so a dead drain worker inside a live process is total silent
loss: `process()` keeps `trySend`-ing into `Channel(UNLIMITED)` while the app looks healthy. That
fired on 2026-07-28, when a `Regex` literal valid on the host JVM but **rejected by Android's
ICU-backed engine** threw an `ExceptionInInitializerError` — an **`Error`** that slipped past the
worker's per-item `catch (e: Exception)` — and destroyed 91.7% of that evening's data. Three
standing rules follow: (1) the per-item catch is **`Throwable`**, with only `CancellationException`
rethrown — the scope is a `SupervisorJob`, so rethrowing a `VirtualMachineError` would surface
nothing and only trade a loud bounded per-effect failure for unbounded silence; (2) the drain loop
is **supervised** with a capped linear backoff (`superviseDrainWorker`, the #430 pipeline precedent)
and every failure logs at ERROR with an honest message (`"Effect failed — isolated, the engine is
still draining"`) — the scope handler covers only the **detached** coroutines (timers, delayed
posts); (3) the ICU/JVM regex divergence is **not executable from any unit or Robolectric test**, so
it is caught by source scan — `IcuRegexGuardTest` (`:app`, the #764 `TimberTagGuardTest` doctrine)
fails the build on a bare, unescaped `}` in any main-source `Regex(…)`/`.toRegex()` literal. Write
`\}`, never `}` (reference shape: `Ruleset.TEMPLATE_PATTERN` = `\{(\w+)\}`). Rule-authored
patterns are a separate path that #1053 closed differently: they do not run on that engine at all
(§2 — RE2J, not `java.util.regex`).
**The offer voice is the family's fifth member (#991).** `TtsEffectHandler` built its `TextToSpeech`
once in `init` and latched `isReady` true forever, so a dropped engine binder lost every utterance
across three dashes with nothing but a WARN. The engine now comes from a `TtsEngineFactory` seam and
EVERY way an utterance can be lost — a non-SUCCESS `speak()` return, an async
`UtteranceProgressListener.onError`, a FAILED `onInit`, and an offer skipped while a rebuild is
outstanding — escalates through the pure `TtsRecoveryPolicy`: rebuild immediately, then rebuilds
gated by a linear 30 s-step / 5 min-cap backoff, then (3 consecutive losses **and** ≥2 rebuilds
attempted **and** ≥60 s of streak — the floor exists because the notice is once-per-process and must
not be burnt by a burst during one slow recovery) a `TtsHealthNotifier` notice. Notify and rebuild
compose — telling the dasher is not a reason to skip the rebuild. A `speak()` SUCCESS means only
QUEUED, so the reset lives on `onDone`; engine identity is generation-checked so a superseded
engine's late callback can't ready or mis-language its replacement; and the rebuild is detached onto
the app scope so a wedged TTS binder can never block the drain worker that owns the `app_events`
writer.
**A tap that never landed is not a fire (#1102).** `EXPAND_EARNINGS` is emitted as a
`ScheduleTimeout(SETTLE_UI)` carrying the bind's `NodeRef`, and the tap re-resolves that ref when the
timeout fires. The 09-13 and 09-15 pulls show DoorDash's prism receipt sheet still SLIDING when the
frame that reaches the state machine is captured, freezing `expandButton` at `top` 4435 / 4395 / 4263 /
2224 / 1982 against a settled 1774–1890 on a 1080×2400 screen — so that first tap re-resolves against a
rect nothing overlaps and fails closed (`Could not find any live node`, never a label rejection). What
the trail ALSO shows, on all 7 failures: the settled re-render was admitted ~600 ms later, re-armed a
fresh `SETTLE_UI` with live bounds, and that retry was swallowed by the engine's 1 000 ms action
throttle as "within 1000ms of the last fire" — the failed attempt had stamped the window. The fix is at
the throttle: the stamp is taken before the click (#618 F3's queued-duplicate guard) but RESTORED to its
prior value when `performVerifiedClick` returns false — for AUTOMATION taps only. A USER tap keeps its
completion stamp even when it failed: its #602 bounded retry can spend ~1.5 s, and rolling the window
back would let a queued duplicate start another retry that could land on a REPLACEMENT offer's button
(`PerformRuleAction` carries no offer identity). For the automated path nothing widens — every retry is
a full re-resolve behind the same package, label and consent gates, and only a newly ADMITTED frame can
arm one. Two roads not taken, both reviewed:
raising `expandSettleMs` (500 → 900 ms) cannot repair a ref whose bounds were frozen mid-slide — the tap
fails whenever it fires — and only widens the confirm-decline vs `OFFER_EXPIRY` window; and a
label-hash re-find when the bounds walk comes back empty was built and WITHDRAWN (a clickable parent
containing a non-clickable row satisfies label CONTAINMENT; a budget cut-off could leave one wrong
survivor; unbudgeted child fetches; label collection crossing the package boundary — the constraints
are on #1102). #1149 rebuilt that re-find as strategy 2b under exactly those constraints (next section).

**Tap target re-resolution (#1149).** From the 2026-09-21 TalkBack study
(`~/dashbuddy/research/talkback-study/ASTRA-REPORT.md`, wins 1 + 2: refresh the actual action owner before
dispatch; re-resolve a bind semantically before geometry). DashBuddy taps with `performAction(ACTION_CLICK)`,
never coordinates, so frozen bounds never aimed a tap — they decided WHICH node resolved.

- **Owner first (D1).** `AccNodeUtils.resolveActionOwner` is the one "what does a tap land on" rule: self →
  parent, at most `MAX_OWNER_WALK` = 32 steps, a cycle guard (`==` on visited nodes), clickable =
  `isClickable` OR an advertised `ACTION_CLICK`; no owner → no tap. `performVerifiedClick` maps every candidate
  to its owner BEFORE verification: owner-less or foreign-package owners are dropped (WARN, counts only);
  candidates sharing an owner are deduped (a button's title TextView and the button are one control, not a #734
  tie; the matched node stays the ranker's text/bounds `evidence`). **Each distinct owner is `refresh()`ed once,
  up front (review I1)** and dropped if the refresh fails, so every scan and verification reads its CURRENT
  state (a recycled row that rebinds Decline → Accept is verified as Accept); `clickNodeStrict(owner, pkg)` then
  clicks with NO second refresh — it only re-checks that the owner still takes a click in the scoped package.
  Labels are verified on the OWNER's bounded subtree plus the matched node's OWN bounded subtree through the same
  `LabelHorizon` rule (review I8/Y1 — a title, or a container whose label sits on its child, more than 3 levels
  below its button still verifies; the semantic fingerprint stays owner-scoped). An owner's evidence is its STRONGEST fresh member under the cross-owner
  ranker's own tiers — exact stored text, then max overlap with the captured rect (review W1) — so owners compete
  strongest-vs-strongest, independent of child order; a tie INSIDE one owner keeps a tied-strongest member
  (`ClickCandidateRanker.strongest`, review X1 — they are interchangeable), while a tie between distinct owners
  still fails closed. That evidence is as fresh and scoped as the owner
  (review J1): a matched node that is not the owner is refreshed first, must be in the scoped package and must
  still resolve to the same owner, else the target is dropped as stale. The #1093 nested abort, #788 window scoping, #600
  ranking and #734 tie abort all operate on owners.
- **A clickable descendant's labels are its own (review I3 — replaced the compound-owner rule).** Every label
  scan stops at a clickable descendant. A container therefore never borrows a nested button's "Decline" and
  passes the expectation — the danger the first-round "compound owner" refusal targeted, which is GONE: it
  failed open when its own scan was cut, and it refused legitimate composite rows (a receipt row holding an info
  chevron and a link). Consequence: a label-less clickable wrapper around the row no longer inherits the row's
  fingerprint, so the ROW is found and clicked; the #1093 nested abort still fires when a wrapper carries its own
  copy of the labels. The FOREIGN boundary is inherited at mapping (review V1): a same-package node beneath a
  foreign wrapper is `foreignPackage` too, so its bind is refused exactly as fire-time discovery never reaches it.
- **One label horizon (review I2) and one clickability predicate (review J2).** `NodeRef.LABEL_SCAN_DEPTH` (3) /
  `LABEL_SCAN_NODES` (24) in `:domain` are the single owner, and so is the RULE (review N8): `LabelHorizon.scan`
  over a tiny `LabelNode` adapter (`ownLabels`, `takesClick`, `foreign`, `unreadableChildren`, `children()` with
  null = an unreadable slot) is the one implementation — bind time wraps a mapped `UiNode` (`UiLabelNode`, via
  `Ruleset.buildNodeRef` → `NodeRef.hintLabelsOf`), fire time wraps a live node (`LiveLabelNode`, fetching lazily,
  each slot budgeted before it is touched) for the post-refresh verification scan and the 2b walk's
  already-fetched nodes (`WalkNode`, no second fetch) for each candidate's horizon. Both sides therefore read the
  same horizon with the same ownership rule, and
  "takes a click" is ONE predicate on both sides: `UiNode.takesClick` (`isClickable || hasClickAction` — the
  #1147 field brought forward; `clickAction` in the DTO, default false and omitted, so fixtures are unchanged; set
  by the mapper from the action list; not in `allText` or any content hash) mirrors
  the live `AccessibilityNodeInfo.takesClick()` (one extension, #1149 T7). Otherwise an action-only descendant was absorbed at bind time and excluded at
  fire time, and a twin could become the sole survivor. **The bind side fingerprints the ACTION OWNER (review L2):**
  `NodeRef.bindHintsOf` walks from the bound node to its nearest `takesClick` self-or-ancestor (`MAX_OWNER_WALK`,
  owned by `NodeRef`) and hashes THAT region, as fire time does; the owner's class rides the ref as
  `ownerClassHint`, the 2b class filter (`classNameHint` stays the bound node's, for strategies 1–3). **Package and
  readability parity (L3/L4):** the mapper stamps `UiNode.foreignPackage` (DTO `foreign`) on nodes of another
  package than the window root and `UiNode.unreadableChildren` (DTO `nullKids`) from its null-`getChild` count —
  both default-and-omitted, so fixtures are byte-identical, and neither is in `allText` or any hash;
  `hintLabelsOf` spends a foreign child's slot without reading it (as `scanLabels` does) and marks the bind
  INCOMPLETE when an in-horizon node reports unreadable children (so the ref is unprovable and 2b is skipped).
- **Completeness rides the ref (review J3).** `NodeRef.labelHintsComplete` (default false — legacy
  journal/snapshot refs load as unprovable) records that the bind-time scan was complete AND (review P7) that the
  distinct set fit in `MAX_LABEL_HINTS` — truncation is recorded where it is known, so a control with exactly 6
  labels is provable (no size proxy); `NodeRef.hasExactFingerprint` (hints present and complete) is the one owner,
  required by `fingerprintMatches` and gating strategy 2b. An unprovable ref skips 2b for strategy 3's containment
  check.
- **Labels before geometry (D2, strategy 2b).** Between the text strategy and the bounds walk: a ref with
  `labelHintHashes` is re-found by walking each root for nodes that take a click, match `ownerClassHint` (L2), and
  whose COMPLETE label region is the ref's **exact** fingerprint (`NodeRef.fingerprintMatches`). Bounds are not an
  entrance test. The four #1102 review constraints on the withdrawn re-find are the design: (1) EXACT set, never
  containment — a clickable parent card holding the row plus other text is not a candidate, and a bind-time set
  that filled `MAX_LABEL_HINTS` is unprovable; (2) a search that cannot complete leaves no lone survivor;
  (3) every child fetch is budgeted before the binder call, nulls included — and the walk is ONE pass (review I7):
  each node's label region is derived post-order from the children the walk already fetched, so the fetch
  budget counts real IPC once; (4) label collection never reads an embedded foreign-package subtree, in discovery
  AND verification.
  - **The outcome rule (review L1/N1, `decideSemanticOutcome` — one owner).** **The DECIDING set is the active
    platform window's search when it produced ≥ 1 hit, otherwise every scoped window's search (with all their
    incompleteness) — the #788 "active contributes none → keep them all" rule** (a small same-package dialog
    active over the still-sliding receipt sheet must not hand the tap to frozen bounds). The active root is walked
    FIRST and the others only when it has no hit (N2); under our bubble all are walked. The roots and the active
    root come from ONE enumeration (`AccessibilitySource.getLiveWindowRoots()` → `LiveRoots(active, roots)`, N3).
    H = the deciding set's 2b hits, I = any of its windows incomplete: **|H| = 0 → fall through to strategy 3
    (whether or not I); |H| ≥ 1 ∧ I → abort ("semantic re-find inconclusive"); else use H (≥ 2 are twins).** Why: `👻 NULL CHILDREN` appears in ~5 % of
    fielded frames (23 957 lines across 143 pulled files; `recycler_view` 3 219×), and the round-2 "any null
    child aborts, no fallback" veto would have refused the expand tap on that share of receipts. A 2b hit that
    becomes unprovable at verification — a failed owner refresh (L5), an incomplete post-refresh scan or a
    fingerprint mismatch (J6) — counts as |H| ≥ 1 ∧ I in the deciding set and aborts.
  - **Incompleteness (review I4, J4, J5).** A null child makes a scan incomplete. A window's search is
    INCOMPLETE when a bound cut the walk — the MAPPER's own tree budget
    (`TreeLimits.MAX_TREE_DEPTH` 60 / `MAX_TREE_NODES` 4 000, review J5: "incomplete" means a tree the mapper
    itself would have truncated) — or a child read null (recorded in the window AND in the enclosing label
    regions; the walk continues collecting hits), or a candidate's
    OWN label region is cut by the slot cap AND its visible labels are still consistent with the fingerprint
    (visible hint set ⊆ the ref's, review J4) — never "a non-match that lets its twin win". A region already
    carrying a label outside the set cannot be an exact match whatever is unseen, so a big unrelated card does not
    count. The `LABEL_SCAN_DEPTH` cut is the HORIZON, not incompleteness (vet decision on I2 × I4b):
    both sides define the fingerprint as the owner's labels within depth 3, excluding clickable descendants, so a
    control with deeper nodes still has a fully determined fingerprint and IS matched. Verifying a label
    EXPECTATION stays lenient: a found label suffices, and since I3 no collected label comes from a nested
    control.
  - **Strategy 3 shares the predicate (review J7) and is bounded (review R5):** the bounds walk uses
    `takesClick()`, so an action-only Compose control at the exact rect is found, and it applies the
    mapper's `TreeLimits` (depth + fetch budget, nulls counted) — a cut walk takes NO candidates from that root.
  - **Semantic twins abort, unconditionally (review I5/R8).** ≥ 2 2b survivors after owner dedupe and #788
    scoping abort to manual (WARN, counts) — the overlap tier would pick by the captured rect, the very evidence
    2b distrusts. There is no stored-text tie-break (the round-5 one was dead by construction and was removed).
  - **A vanished candidate is stale, not an orphan (review S1):** an id/text candidate whose owner walk fails
    because the node itself is gone (parent chain null, and its own refresh fails) counts as STALE, so R6's
    abort fires instead of handing the tap to a lower window's twin.
  - **A cut ACTIVE bounds walk aborts (review S2):** the mirror of L1 for strategy 3 — if the active platform
    window's bounds walk is cut by `TreeLimits`, a background window's control never becomes the sole survivor.
    **Under keep-all scoping, ANY cut walk aborts (review T1):** with no verified active-window candidate (our
    bubble active) every window decides, so a cut window could hold the intended control past its cut.
  - **Unreadable windows are cut windows for strategy 3 (review T3):** under keep-all scoping an unreadable
    application window counts like a `TreeLimits`-cut window (abort); the active window is excluded from that count
    by its own `isActive` flag (T4), not the root read.
  - **Label-free by design is not unprovable (review T9):** an action with a null label expectation
    (`EXPAND_EARNINGS` — "the control carries no text") is never counted as unprovable for lacking a label; the
    icon-only census (S7) stays for label-verified actions.
  - **One source for "active" (review U1/U2):** `LiveRoots.active` is the root of the window the ENUMERATION flags
    `isActive` (`rootInActiveWindow` only as the fallback; if the two name different windows there is no active root
    for that attempt — keep-all, fail closed), and an active application window whose root is readable nowhere is
    COUNTED unreadable, so a background twin never becomes the sole candidate.
  - **Geometry is re-checked after the refresh (review U3):** a bounds-derived target keeps its credit only if the
    refreshed owner still has the class hint and the captured rect (exact for a hint-less or exact hit, the same IoU
    tier for a relaxed one); otherwise it is dropped as stale (the R6/S1 multi-target abort applies).
  - **The hash key is the WHOLE label (review T2):** `hintKeyOrNull` = the capped, trimmed, ROOT-lowercased full
    label (the 40-char key let "… merchant Alice" and "… merchant Bob" collide); a legacy ref's strategy-3
    containment may miss a long label across the upgrade — accepted, fail closed.
  - **Evidence labels are capped (review S3):** the matched node's own text/description join the owner's labels
    through the same `UiTextBounds` cap-then-blank-filter as every other label.
  - **A stale target among several aborts (review R6):** in the id/text arms too, a target dropped because its
    owner or matched node failed `refresh()` aborts the tap when ≥ 2 targets existed — otherwise the drop would
    also un-scope #788 and hand the tap to another window's twin. A lone stale target is simply not clicked.
  - **A refused bind is UNRESOLVED (review R1):** a bind whose bound node or owner walk is foreign, or that has
    no owner at all, emits NO `NodeRef` (so not even strategy 3's exact-bounds arm can tap a same-class control
    at the rect); for an action target it rides the #1093 `bindShortfall` census. An action target whose
    bind-time fingerprint is UNPROVABLE (unreadable children, > 6 labels) still has a reference but is counted
    in its own census (review R7): `bindUnprovable{<rule>#<bind>=n}` on the `PipelineStats` summary, one
    `Pipeline` WARN per rule+bind per process. Both censuses cover `RuleAction` target binds only.
  - **One live owner walk, scoped (review P2):** `resolveActionOwner(node, pkg)` ends with NO owner at the first
    node of another package — a foreign hop is never crossed, exactly as bind time refuses it (N5).
  - **Unreadable windows (review P3/R3):** `LiveRoots.unreadableWindows` counts enumerated APPLICATION windows —
    not the active one, not picture-in-picture — whose root was null; when every window decides (our bubble, or
    no active hit), one counts as an incomplete window. Residual: such a window's package is unknown, so a
    foreign app's unreadable window still counts.
  - **One text cap (review R2):** `UiTextBounds.cap` (4 096, `:domain`) is applied by the mapper at ingestion
    and by `LabelHorizon` before the blank filter, so bind and fire see the same string.
    The live CLASS name goes through the same cap (`liveClassName`, review V2) in semantic discovery, the bounds
    walk and the U3 re-check, so an over-long class compares like with like.
  The real receipt trees (~60 nodes, depth ≤ 19) sit far inside the bound, and on all three id-less corpus
  frames 2b finds exactly the row — including from a ref captured 400 px low or on the "Continue dashing" rect
  (`ActuationBindingResolutionTest`, which skips the legacy `"clickable"`-key fixtures — #1154).
- **Not done (D3).** A geometry-only frame refreshing bindings without re-entering the state machine: NO.
  `Observation.identity()` is the dedup SSOT and does not change; with 2b a slid control is re-found by labels,
  so the frozen-bounds problem is fixed where it bites. #1102's throttle semantics and the capability gates
  (#417/#425) are unchanged — this changes HOW a target is re-found, never WHAT may be tapped.
- **Residuals.** (−1) **Accepted bounded cost:** when 2b falls through (no hit in the deciding set), strategy 3
  re-walks the windows 2b already walked — each walk is bounded by `TreeLimits`, so the worst case is two
  budgeted walks per tap. (0) **A null child in the deciding window makes a lone hit inconclusive for THAT frame —
  ACCEPTED (review N9).** The tap aborts and is retried on the next admitted frame (the #1102 throttle restore);
  the field item watches the `semantic re-find inconclusive` WARN rate. (1) **The accessibility rebind race —
  ACCEPTED (review L9).** After an owner `refresh()`, the
  accessibility client's subtree cache can still serve a pre-rebind copy of a CHILD to `getChild`; every
  accessibility consumer lives with this, and DashBuddy refreshes the owner and the matched evidence node (I1/J1)
  but cannot force-refresh a whole subtree cheaply. (2) **A label-less incomplete region no longer vetoes
  (review R4):** a region vetoes only with ≥ 1 visible letter-bearing key, all in the ref's set; a hidden twin
  under an unreadable label-less region is the accepted residual. (3) An unreadable APPLICATION window of
  unknown package still counts as incomplete when every window decides (R3).
  The WARN vocabulary: `no clickable, fresh owner … (N stale)`, `semantic re-find inconclusive (N hit(s),
  incomplete)` (the |H| ≥ 1 ∧ I abort, also used for L5/J6), `semantic twins`; the L1 fall-through is DEBUG. The
  pre-existing `Could not find any live node` WARN no longer prints `ref.text` (moved to DEBUG, review I9a).
