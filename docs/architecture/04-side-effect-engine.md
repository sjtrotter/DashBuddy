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
