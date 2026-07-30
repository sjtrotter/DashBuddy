# Adversarial Review — Ground-Up, Code-First — 2026-07-30

**Reviewer:** independent adversarial session (Fable), reading the code from scratch with no
prior stake in any design decision. **Method:** direct read of the full recognition → state →
effects → analytics spine (Ruleset, RuleCompiler, ObservationClassifier, PlatformRegionStepper,
OfferLifecycle, FlowRegionStepper, SideEffectEngine, AnalyticsProjector, OfferEvaluator, pipeline
supervision, capture bindings, rule sources, ADRs, desk-validation playbook), plus three bounded
subagent scans (UI-layer principles audit; test-suite inventory; full read of the 6,530-line
field-testing log) whose load-bearing claims were independently re-verified against source before
inclusion. Issue reconciliation ran against all 55 open issues and targeted closed-issue searches.

**Reviewed at:** `master` ≈ `ff99eb8f`–`5fe01e14` (another orchestrator was actively merging
during the review; `uber.json5`/`dropoff.json5` and the #910 build were in flight and are not
judged here).

---

## 1. The problem set

DashBuddy's core technical problem is **reconstructing a gig platform's private state machine
from its pixels** — no API, no cooperation, an actively-changing third-party UI — and doing it:

1. **On-device only** (the Pledges: no customer PII persisted raw, dasher banking blocked at the
   matcher layer, opt-in network, edge hashing);
2. **Accurately enough to be money-grade** — the product's headline claim (True Net
   Profitability) is an accounting claim, not a UX claim;
3. **While driving** — the consumer is a distracted dasher glancing at a HUD, so wrong data is
   worse than no data;
4. **Platform-agnostically** — the spine must never encode DoorDash, so that adding a platform is
   ruleset data + corpus, not code;
5. **Durably** — event-sourced (`app_events` is the source of truth), crash-recoverable by
   deterministic replay, with the analytics read-model a rebuildable projection.

This decomposes into the four hard sub-problems the architecture visibly orbits: recognition
(untrusted UI → typed observations), state reconstruction (observations → sessions/jobs/tasks
under misrecognition), effects (exactly-once side effects incl. the sole event-log writer), and
economics (frozen, immutable, reconcilable records).

## 2. Verdict up front

**The approach is viable and the field record proves it — on one platform, for one user, at the
cost of a permanent ops loop.** The architecture is sound and unusually well-executed; the field
evidence shows the *fix loop* converging on DoorDash (exact-to-the-cent money reconciliation on
~30 consecutive moneyed sessions from 06-19 → 07-26). But the same record shows three structural
debts that are not yet paid: **field-validation debt** (~three-quarters of the 150+ merged-work
checklist items have zero field confirmations), **an unproven second platform** (Uber:
162/162 lifetime offers recorded as timeouts as of 07-25; zero costed Uber jobs ever), and **a
privacy-enumeration arms race the defense keeps losing on first contact** (13 PII incident
groups; the class has never gone two consecutive pulls clean). None of these invalidate the
approach; all three bound how fast it can scale beyond one dev, one device, one platform.

---

## 3. Viability against real-world data

### 3.1 What the field record proves works

- **The event-sourced money spine.** Session-level reconciliation was exact to the cent on every
  moneyed session from 06-19 through 07-26 (13/13 lifetime sessions as of 07-13), through crash
  recovery (a real mid-checkout power-off, 06-20, 2/2), and through an APK replacement
  mid-session (07-24). The 07-29 dash after the #909 fix reconciled 1:1 (12 TTS = 12
  OFFER_RECEIVED, seq 1268–1325 continuous). For a system that reads pixels, this is the
  strongest possible empirical result.
- **The fix loop itself.** Ghost offers, phantom completions, decline-as-timeout, job absorption
  — each class was field-found, desk-root-caused from captures, red/green-tested via
  SessionReplay, fixed, and (for #498/#518) held under 2/2 validation plus a clean week. The
  capture→replay→fix pipeline is the project's real asset: field bugs become deterministic tests.
- **Crash/restart resilience.** Zero crashes since 06-03 (#297). Supervised pipeline (#430),
  supervised projector, and — post-#909 — a supervised, `Throwable`-isolating effect drain.
- **Platform-agnosticism as enforced discipline.** The UI/feature layers contain zero platform
  literals in logic (verified by scan); vocabulary goes through load-validated contracts; the
  #585/#762 campaigns closed the known seams. This is a real, tested property, not aspiration.
- **The privacy *architecture*** (as distinct from its enumeration content — §4.3): fail-closed
  gates at every layer (rulesets-not-loaded frame drop, release `NoOpCaptureBus` verified bound,
  compile-time sha256↔redact lint, marker backstops, scrub-at-sink for the exportable log).

### 3.2 What it proves doesn't work yet

- **Uber.** The second platform is the acceptance test for the whole "adding a platform is just
  data" thesis, and it currently fails it in the field: accept-detection is structurally
  undetectable (#826, textless accept node), 162/162 lifetime offers were recorded
  `OFFER_TIMEOUT`, two driven-to-completion deliveries produced **no records at all** (07-21),
  offer economics were poisoned by a time/miles parse swap (#827), and 72/114 Uber UNKNOWNs are
  one unmodeled surface (Trip Radar board, #251 — dev-gated). Uber economics have **never** been
  validated against real earnings (the checklist's own ask, never performed). The thesis itself
  isn't refuted — no `:core:state` changes were needed for Uber fixes so far, which is the
  claim — but "zero code, ship a ruleset" has not yet produced a *working* second platform.
- **Recognition as a steady state.** UNKNOWN-family batches were filed on essentially every pull:
  #462→#501→#549/#550→#795/#796→#865→#888→#912→#922 (chain length ≥ 9, still open — #888 shipped
  07-27 and was already partial-plus-spawning-#922 by 07-30). The DoorDash UNKNOWN pile shrinks
  (180→35 frames) but never zeroes. This is inherent to the problem, not a defect — but it means
  the product carries a **permanent rules-maintenance loop**, which is precisely why pillar 2
  (forkable matchers + OTA, #192/#640) is load-bearing for viability at scale and is currently
  parked.
- **The observer is part of the error budget.** The log itself documents the developer's field
  hypotheses being systematically wrong (07-29: "right in FEELING and wrong in every specific
  hypothesis — every one of H1–H5 refuted"; 07-25: dasher-observed polarity was the inverse of
  the desk finding). The desk pipeline compensates well, but its own instrumentation has
  documented false negatives (greps with no corresponding log site; the #885 double-space leak
  that evaded the standing desk recipe; capture caps blinding 4 h of a dash).

### 3.3 The bets and their current status

| Bet | Status |
|---|---|
| Recognition as data (JSON rules, no matcher code) | **Won** on DoorDash; heavily hardened compiler; editing loop is fast. |
| Multi-region state machine + graces absorb misrecognition | **Mostly won** — every cascade class (ghost/phantom/absorption) was fixable *within* the model; none forced an architecture change since ADR-0005. |
| Event sourcing + frozen economics | **Won** — rebuild ≡ backfill has survived 9 projector versions; corrections compose in log order. |
| Effects as data-integrity boundary | **Lost once, catastrophically** (#909: 91.7% of an evening, $82.10, silently destroyed by a one-char ICU regex), then re-won with supervision + `Throwable` isolation + a source-scan guard. The generalization (§4.5) is incomplete. |
| Single dev + single device generates enough ground truth | **Strained** — see §4.1; the 2-confirmation policy exists because one dash proves little, and ~75% of items have zero. |

---

## 4. Gaps, limitations, weaknesses (ranked)

### 4.1 Field-validation debt is the largest unpriced liability

The living checklist holds ~150 items; ~75% carry `Confirmed: 0/2` (my grep: 87–124 zero-marks
depending on counting; the subagent's full read: ~115 of 153), 34 at 1/2, and items merged as far
back as **2026-06-02** have never had a field confirmation. Structural aggravators:

- The playbook classifies only ~10 items as fully desk-resolvable; the rest need driving time —
  a hard ceiling given one dasher.
- Several defenses have **never fired in anger**: the #909 `Throwable`-isolation and
  restart-supervisor layers went untriggered on their one validated dash; the #810-B1 tripwire
  has 3 clean passes and 0 true positives; #603 false-arrival fix is 0/2 after ~7 weeks; the
  06-06 forged-offline fix is "held" only because the discriminating case never re-ran (3×).
- The checklist itself has become a 2,100-line append-mostly structure whose validation
  throughput (~1–3 retirements per dash) is far below its growth rate (~4–6 additions per pull).

**This is a process gap, not a code gap**: merged ≠ validated, and the gap compounds. Consider a
triage pass that (a) prunes items whose window has passed or that newer work superseded, (b)
promotes the handful that guard money/Pledge invariants to a short "must-watch" list, (c) accepts
desk-only closure for the long tail.

### 4.2 Recognition's structural boundaries (beyond the churn loop)

- **English-locale binding (unfiled).** Rule anchors (`hasText: "Decline offer"`,
  `"Deliver by"`, `"sure you want to decline"`) *and both marker SSOTs*
  (`SensitiveTextMarkers`, `CustomerTextMarkers`) are literal English strings. A dasher running
  their device in Spanish gets zero recognition — and, more importantly for the Pledge, the
  **sensitive-block and PII-scrub layers thin out too** (banking screens would fall to UNKNOWN —
  dropped from the state machine and not captured in release, so the failure direction is
  survivable, but the marker backstops on debug captures and the log scrub weaken silently).
  DoorDash serves Spanish-locale dashers; this is a real population, not a hypothetical. #428
  covers only the app's own copy/TTS. At minimum this boundary should be *documented and
  detected* (e.g., WARN when device locale ≠ en on service start).
- **No on-device recognition-health signal (unfiled).** A DoorDash app update that breaks
  anchors mid-dash is detectable only at the next desk pull (UNKNOWN census). The app already
  counts everything (`PipelineStats`); there is no "UNKNOWN-rate spike" alarm to the dasher, and
  capture envelopes/logs don't stamp the *observed platform app's versionName*, so the desk
  can't correlate rule breakage with platform updates. #916/#917 cover bubble/odometer liveness;
  recognition liveness is the missing sibling. This matters double once rules ship OTA (#640) to
  users who don't do desk pulls.
- **Single-device corpus.** 689 snapshots, one Pixel 7, one DPI, one font scale, one locale.
  46% of intent folders hold <3 frames; 31 hold exactly 1; 14 intents have zero corpus (pinned
  honestly in `knownUncoveredIntents`). The retention cap (15) prunes variants from
  well-covered screens while thin folders stay thin — a ceiling with no floor. Pillar 2's
  community-matchers future implicitly requires multi-device corpus infrastructure that doesn't
  exist yet.
- **Platform skew:** 550 DoorDash vs 31 Uber golden entries (5.3%). The coverage ratchet is also
  **platform-blind** (a DoorDash-populated `idle_map` folder marks the same-named Uber intent
  covered), so Uber thinness is partially invisible to the gate.

### 4.3 The privacy defense is architecturally strong and enumerative-ly losing

13 distinct PII incident groups; ≥5 are recurrences of a "closed" class; the enumeration was
defeated repeatedly on both axes (`Transfer out` → `Transfer in` → `Transfer $<amt>`;
`Deliver to ` → `Pickup for ` → `Delivery for `); the *cleanest pull on record* (07-26) still
produced 4 new privacy issues, and the next (07-29) produced #910 (5 sites, one recognized
surface with **no redact block at all**). Meanwhile ~2,302 unredacted evidence PNGs sat on
device (#883 is the shaped answer). The **architecture** (layered fail-closed backstops) is
right; the failing part is that the *positive* enumeration (which surfaces exist, which carry
PII) is discovered one field leak at a time. Two directions the current issue set doesn't fully
cover:

- **Default-deny capture for un-enumerated surfaces**: today an UNKNOWN screen frame is captured
  (debug) unless a marker fires — the marker set is the losing enumeration. The inverted posture
  (capture UNKNOWN only when a rules-independent *allowlist* signal says the surface is
  PII-free, or always-scrub-harder on UNKNOWN) trades corpus-intake convenience for Pledge
  safety. Worth an explicit decision rather than an implicit one.
- The **desk sweep recipes are themselves enumerations** and have already produced a false
  negative (#885's double-space). The `\s{1,4}` fix pattern should be applied to every recipe
  grep, and the recipes belong in a versioned script, not prose (partially done in the playbook).

### 4.4 CI/test blind spots (all verified against source)

1. **Migration-correctness tests never run anywhere.** `pr-check.yml` runs only
   `testDebugUnitTest :domain:test`; there is no `connectedAndroidTest` job, no nightly, and the
   8 real `MigrationTestHelper` tests are automation-dead. With the destructive fallback
   deliberately retired (#690 — correct choice), a declared-but-broken migration is now a
   device-only loud crash backed by an auto-snapshot; the guard (`SchemaVersionGuardTest`) proves
   edges are *declared*, not that they *work*. An emulator job (nightly or release-gating) or a
   documented manual pre-release step is needed.
2. **No ICU engine ever executes any regex in automation** — the #909 class is guarded by a
   source scan (`IcuRegexGuardTest`) that catches exactly one shape (bare `}` in a literal).
   Its KDoc honestly lists the blind spots (concatenated/variable patterns, `Pattern.compile`).
   Same fix vector: any instrumented job at all would close both this and (1).
3. **The `-Ddashbuddy.propExplore` nightly was designed but never built** — PR CI draws the same
   8 pinned sample sets forever; the exploration breadth that seeding (#878) traded away has no
   home. One scheduled workflow closes it.
4. **Corpus-mutating "tests" run on the CI sweep.** Only `*Suite` is excluded;
   `InboxProcessorTest`/`UnknownScreenAnalysisTest` delete/move/prune snapshot files and are
   no-ops today only because `INBOX/` is empty. Intake failures are also non-fatal (unrecognized
   capture → green test; unparseable corpus file → silently skipped by `TestResourceLoader`).
5. **The negative half of the recognition regression loop is missing**: `UNKNOWN/` (must-stay-
   unrecognized corpus) doesn't exist; `UnknownScreenAnalysisTest` silently passes on the empty
   set. Over-match regressions (#874/#875's forged-offline class — a rule matching a screen it
   shouldn't) have only 10 hand-picked fixture frames guarding them, despite over-match being
   the *more dangerous* failure direction (it forges state; under-match only drops to UNKNOWN).
6. Smaller: a wall-clock timing assertion inside a 200-sample property test
   (`ClassifyGateCaptureFuzzTest` — the exact flake shape PropSeeds was written for); one
   conditionally-vacuous property (`SnapshotSecurityScannerParityTest` asserts only inside
   `if (markerHit != null)` with no hit-count statistic); `AddonPhantomReplayTest` asserts only
   `!= delivery_summary_collapsed` (already drifted per its own KDoc);
   `AllMatchersSuite` membership has drifted from the test tree (≥4 recognition tests absent,
   undocumented); no lint/detekt on the PR path (#907's lintVitalRelease break shipped because
   only release builds run it).

For balance: the suite is in the top percentile of projects this reviewer has audited — ~2,192
tests, zero `@Ignore`, zero commented-out assertions, bidirectional ratchet guards, a written
flake post-mortem, regen-fails-loud goldens. The gaps above are the *remaining* surface.

### 4.5 The silent-death class is half-generalized

#909's lesson — "a subsystem can die while the app looks healthy" — was applied to the effect
engine (supervision + `Throwable` + honest ERROR logs) and is being applied to the bubble (#916)
and odometer (#917). Recognition liveness (§4.2) is the unfilled quadrant. A unifying shape —
each critical subsystem exports a heartbeat, and *something* (bubble chip, notification) renders
staleness to the dasher — would close the class rather than the instances. (#913's wedged-worker
liveness invariant is the right direction.)

### 4.6 Economics fail-open default (unfiled)

`OfferEvaluator.evaluate`: `val dist = offer.distanceMiles ?: 1.0`. An offer whose distance
failed to parse is scored as a **1-mile trip** — near-zero operating cost, tiny drive time —
i.e., the one place in an otherwise fail-closed codebase that fails **toward ACCEPT** on missing
data. #827's Uber time/miles swap flowed through exactly this kind of seam. `payAmount ?: 0.0`
fails safe (toward DECLINE); distance should too — score as unknown/no-verdict (the #366
`OfferQuality.UNKNOWN` path already exists) rather than fabricate a favorable denominator.
Related cosmetic: the caveat-warning copy hardcodes "DoorDash" and dollar norms inside
`:domain` — platform-flavored copy in the platform-agnostic layer (P8, minor).

### 4.7 SSOT drift at the UI edge (verified subset of the UI audit)

The core layers are SSOT-clean; the drift has re-accumulated at the display edge — exactly the
#356-family pattern the principles section predicts:

- **Divergent multi-store join**: HUD renders `"A & B"` (`FlowCardItem.kt:463`), the offer
  heads-up notification renders `"A + B"` (`BubbleManager.kt:534`); domain SSOT
  (`ParsedOffer.displayStoreText`, `" & "`) exists and is bypassed by both.
- `UNATTRIBUTED_EPSILON = 0.005` duplicated in `MoneyTab.kt:48` and `SessionDetailScreen.kt:58`
  (same package, same KDoc).
- `EMPTY_VALUE = "—"` copied into `:feature:dashboard` from an `:app` owner whose KDoc forbids
  per-file copies; undocumented (unlike the sanctioned `common_period_*` duplications).
- The focused-platform derivation (`activePlatform ?: mostRecentActivityPlatform`) hand-copied
  at 4 sites across 3 modules; belongs next to `AppState.activeSessionId()`.
- Badge display names triple-sourced and already drifted (`badgeMeta` in `FlowCardItem.kt`
  missing ≥8 branches that fall to a lowercase-the-enum fallback while
  `OfferBadge.displayName` exists); score→band-color `when` duplicated verbatim; two
  independent `OfferAction`→label maps; percent formatting hand-rolled at 3 sites with no
  `Formats.percent`; a second wall-clock time formatter in `ChatViews.kt:179` that also misses
  the 12/24-h setting change (the exact bug `TimeKit`'s KDoc exists to prevent).

None of these is individually serious; collectively they are the early state of the disease the
#356 campaign cured once. Cheap to fix as one batch while it's small.

### 4.8 Size/UDF outliers (verified)

- `FlowCardItem.kt` at **925 lines** is past the ~900-line threshold that CLAUDE.md itself names
  as the #237 trigger ("don't add to an oversized file, split it first").
- `SideEffectEngine.kt` at 816 lines / 14 constructor deps — cohesive but at the god-object
  boundary; the verb-dispatch half (`dispatchRuleEffect` + arg parsers) is a natural seam.
- `PermissionBottomSheet.kt` owns five platform-state reads + resume re-polling + launcher
  callbacks entirely inside composition (no VM/hoisting); `StrategySettingsScreen.kt:64` calls a
  ViewModel *function* from composition to derive state (UDF inversion); `WizardViewModel` at
  568 lines / 5 repos / ~40 mutators.
- `DataExportViewModel` renders raw platform exception messages to UI two lines after a comment
  explaining why the log deliberately omits them (SAF URIs are user-paths).
- Minor logging debt is honestly ratcheted (49-line allowlist, bidirectional), notable offender
  `OdometerEffectHandler` (8 untagged sites).

### 4.9 Documentation drift

- **`ROADMAP.md` violates its own contract** — "when an issue's state changes, move it here or
  this doc rots", last updated 07-05, 25 days and ~40 issues stale. Either refresh it on a cadence
  (the memory system already tracks this state) or delete it and declare the board + memories
  authoritative — a rotted roadmap is worse than none (it's the CLAUDE.md "stale memory" rule
  applied to a checked-in file).
- The field-testing README at 6,530 lines is approaching unusability as a single file (the
  checklist alone is ~2,100 lines); per-month splits or checklist extraction would help both
  humans and agents.
- CLAUDE.md itself has absorbed enormous per-issue detail (multi-hundred-word inline paragraphs
  in the module map). It works, but each session pays its full token cost; much of the
  issue-history detail belongs in ADRs/memories with one-line pointers.

---

## 5. Design-principle violations (per CLAUDE.md §Development Principles)

| # | Principle | Verdict | Evidence |
|---|---|---|---|
| 1 | UDF | **Mostly conformant**; 3 verified violations | `PermissionBottomSheet` (state owned in composition), `StrategySettingsScreen:64` (VM call from composition), `BubbleScreen` collects 13 separate flows instead of one `UiState` (pattern deviation, not a correctness bug) |
| 2 | MAD | Conformant | — |
| 3 | Single responsibility | **2 files over the project's own line** | `FlowCardItem.kt` 925; `SideEffectEngine.kt` 816/14-dep; watchlist: `SessionDetailScreen` 631, `BubbleManager` 596 (6 responsibilities), `WizardViewModel` 568 |
| 4 | Kotlin/Android practices | Conformant; one stringly-typed site | `badgeMeta(name: String)` keys raw enum-name strings (#283 class) |
| 5 | SSOT | **Core clean; UI edge re-accumulating** | §4.7 list (store-join divergence is user-visible) |
| 6 | Security & privacy | **Architecture conformant and impressive; enumeration repeatedly beaten in the field** | §4.3; plus the unfiled locale-thinning of marker layers (§4.2) and `DataExportViewModel` raw-exception-to-UI |
| 7 | Semantic logging | Conformant with ratcheted debt | Allowlist burns down; INFO+ verified PII-safe across UI/effects by scan |
| 8 | Platform-agnostic core | **Conformant in logic** (zero literals in UI/feature/state layers — verified); one copy leak | "DoorDash"/dollar norms hardcoded in `OfferEvaluator` caveat strings in `:domain` |
| — | Reactive UI | **Conformant** — every HUD time value ticks off `rememberNow()`; anchors-not-strings verified | One miss: `ChatViews` time formatter won't re-render on 12/24-h flip; `TimeTab:215` unmemoized wall-clock read (documented, benign) |

The standout *positive* finding: principles 6, 7, 8 and Reactive-UI — the ones that are hardest
to keep by discipline alone — are all backed by executable guards (marker-scan tests, tag
ratchet, scan-verified zero platform literals, `rememberNow` as the only clock). The violations
that exist cluster where no guard exists yet (UI-edge SSOT, file size).

---

## 6. Recommended issues (reconciled against open + closed tracker)

Already filed / in flight — **no new issue needed**: #910 (PII batch, in flight), #913 (wedged
drain liveness), #916 (bubble observability), #917 (odometer outage survivability), #918 (GPS
drift), #922 (offer-card inflation UNKNOWNs), #826/#827 (Uber accept/parse), #251 (Trip Radar),
#731 (NLS flapping, parked-environmental), #883 (evidence-PNG pixel redaction), #806/#856
(UNKNOWN-surface PII), #428 (i18n of app copy), #192/#636–#641 (matchers OTA), #214 (rules
tutorial/preview), #911 (dash_summary captured=false), #895/#907 (already closed by the parallel
session).

**Recommended new issues**, ranked:

1. **`OfferEvaluator` distance fail-open** — `distanceMiles ?: 1.0` scores an unparsed-distance
   offer as a 1-mile trip (fail-toward-ACCEPT). Degrade to no-verdict (`OfferQuality.UNKNOWN`
   path) instead. Labels: `bug, offer-engine`. (Cousin of closed #366's "parse failure ≠ zeroed
   economics" and open #827; neither covers the default.)
2. **Recognition health: on-device UNKNOWN-rate alarm + platform-app version stamping** — a DD
   app update breaking anchors is currently desk-detectable only; stamp the observed app's
   `versionName` into envelopes/`PipelineStats`, alarm the dasher on UNKNOWN-rate spike. The
   §4.5 heartbeat generalization can ride the same issue. Labels: `enhancement, architecture,
   on-dash-testing`. (Sibling of #916/#917; nothing covers recognition.)
3. **Locale boundary of recognition + marker layers** — document/detect the en-only assumption
   (WARN on non-English device locale; decide the posture before OTA/#640 widens the user base).
   Labels: `bug, architecture, pillar:matchers`. (Distinct from #428.)
4. **CI: run the instrumented tier somewhere** — one emulator job (nightly or release-gating)
   executes the 8 dead `MigrationTestHelper` tests *and* gives an ICU engine that actually
   executes regexes (closing the #909 guard's admitted blind spots). Labels: `ci/cd, testing`.
5. **CI: nightly `-Ddashbuddy.propExplore=true` workflow** — the #878 design's missing
   operational half. Labels: `ci/cd, testing`.
6. **Negative recognition corpus (`UNKNOWN/`) + over-match guard** — the forged-offline class
   (#857/#874/#875) is the dangerous direction and has 10 fixture frames guarding it;
   `UnknownScreenAnalysisTest` currently green-passes on an empty set. Include: make corpus
   intake failures fatal, exclude the mutating intake tests from the CI sweep, fix the
   platform-blind coverage ratchet (`idle_map` DoorDash frames mark the Uber intent covered).
   Labels: `testing, pillar:matchers`.
7. **UI-edge SSOT batch** — §4.7 as one cleanup: store-join divergence (" & " vs " + "),
   `UNATTRIBUTED_EPSILON`, `EMPTY_VALUE`, focused-platform derivation ×4, badge-label
   triple-source, score-band color, `Formats.percent`, ChatViews time formatter. Labels:
   `cleanup, refactor`. (Successor of closed #356 family / #456.)
8. **`FlowCardItem.kt` split (925 lines)** — the #237 rule applied. `SideEffectEngine`'s
   verb-dispatch seam optionally in scope. Labels: `refactor, cleanup`.
9. **UDF cleanups** — `PermissionBottomSheet` state hoisting; `StrategySettingsScreen`
   simulate-from-composition; `DataExportViewModel` raw-exception copy. Labels: `cleanup`.
10. **Field-checklist triage policy** — prune/supersede/desk-close the 0/2 backlog (§4.1);
    define an aging rule so the list can shrink. Labels: `documentation, chore`.
11. **`ROADMAP.md` refresh-or-retire** (§4.9). Labels: `documentation, chore`.
12. **Test-suite hardening batch** — timing assertion out of the fuzz property, hit-count
    statistic for the parity property, strengthen `AddonPhantomReplayTest` to pin the expected
    intent, reconcile `AllMatchersSuite` membership. Labels: `testing, cleanup`.

Suggested but explicitly a *dev decision*, not filed by this review: the §4.3 default-deny
posture for UNKNOWN-surface capture (it trades corpus intake speed for Pledge safety and
reshapes the Inbox workflow).

---

## 7. Closing assessment

Read cold, this codebase does not look like a solo alpha: the fail-closed discipline, the
event-sourced accounting, the guard-test culture (ratchets, goldens that fail on regen, seeded
properties with a written flake post-mortem), and the honesty of the documentation (residuals
and accepted trades recorded at the decision site) are all top-tier. The 07-28 #909 loss is the
counterweight lesson: the system's one catastrophic field failure came not from the adversarial
problem it was designed for, but from its own blind spot between two runtimes — and the response
(supervision, `Throwable` isolation, a source-scan guard, and an honest post-mortem in the log)
was the right one, with the §4.4 instrumented-tier gap as its unfinished half.

The strategic risks are not in the code. They are: **validation throughput** (one dasher cannot
field-validate the merge rate; the 0/2 backlog is the measurement), **the second platform**
(Uber is where the platform-agnostic thesis either proves out or forces a rethink of coarse-flow
platforms), and **the enumeration arms races** (PII surfaces and recognition anchors both churn
faster than a hand-maintained list; the OTA-matchers pillar is the designed answer and is
parked). All three are known to the project in fragments; this review's contribution is mostly
to name them as the binding constraints and to convert the fragments into the §6 list.
