# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this
repository.

## Alpha Status

**This is alpha software. There is exactly one user: the developer.** Do not
prioritize backward compatibility, migration paths, graceful degradation for end users, or
polish concerns unless they directly affect the developer's ability to test the app. Treat
every decision with a single-user, single-device assumption until explicitly told otherwise.

## Project Vision & Strategic Pillars

DashBuddy exists to give independent contractor delivery drivers the same information the
platform already has about their own work. Mission: **driver sovereignty through
transparent, on-device, user-owned tooling.**

The roadmap rests on three pillars, each defended by a separate architectural and licensing
posture so neutralizing one does not collapse the others:

1. **Driver Sovereignty (the product).** Free local tier (True Net Profitability, bubble HUD,
   on-device session tracking, CSV export). Paid managed-cloud tier ($5/mo, $49/yr, $99
   lifetime for first 500 users) for sync and aggregation-derived market context.
   Source-available license; auditable byte-for-byte.
2. **Distributed Integrity (the matchers).** Recognition layer published as a separate
   Apache-2.0 repo, delivered to running app instances via signed JSON over CDN. Forkable;
   if upstream is compromised, drivers can switch sources via configuration. See #192.
3. **Academic Federation (the research).** Opt-in (k=10 cohort, on-device DP budget, edge
   PII scrub) anonymized aggregation across university-hosted instances under IRB review.
   No central corpus; only aggregate query results returned. AGPL-3.0 aggregator. See #193,
   #194.

**Pledges (non-negotiable).** All recognition / evaluation / economic computation happens
on-device. We protect **the dasher** (our user): the dasher's own sensitive screens — banking /
DasherDirect balances & transfers, payment, their own identity documents — are blocked at the
matcher layer, never parsed or stored; so are **document-image capture surfaces** (the license-scan
camera, the signature pad), regardless of whose, because they're an image of an ID / a signature.
**Customers are hashed, not blocked:** customer PII (name, address) is recognized and `sha256`'d at
the edge so we can tell customers apart for dedup/correlation without ever keeping their actual
info — so an alcohol delivery's ID-CHECK instruction screen and arrival card are *recognized* (name
hashed), only the literal scanner/signature surfaces are blocked. The dasher's own name in
first-last-initial form (e.g. the main-menu greeting) is fine to process. Network access is opt-in
per feature. PII scrubbing runs at the edge before any upload.

**Framing discipline.** When writing public-facing material — issues, RFCs, README, grant
copy, marketing — describe the academic pillar as **empirical measurement of the visible
offer surface**, never as reverse-engineering, model recovery, or algorithm characterization.
The DoorDash ICA §15.4 prohibits reverse-engineering of the platform; DashBuddy does not
do that and our written material must not suggest otherwise. See `LEGAL.md` for the
good-faith ICA interpretation that governs scope decisions.

Cross-references: monetization plan #141; matchers infra RFC #192; aggregation RFC #193;
academic federation RFC #194.

## Build Commands

```bash
# Build the app
./gradlew :app:build

# Run all unit tests (across all modules — :domain is pure-JVM, its task is `test`)
./gradlew testDebugUnitTest :domain:test

# Run ONLY the ruleset/recognition regressions (fast pre-PR check — no state
# machine / DB / UI). Side-effect-free; does NOT sort INBOX or re-triage UNKNOWN.
./gradlew :app:testDebugUnitTest --tests "*AllMatchersSuite*"

# Run a single recognition test (e.g. just the golden-corpus positive guard)
./gradlew :app:testDebugUnitTest --tests "*GoldenSnapshotRegressionTest"

# Run instrumented tests (requires connected device/emulator)
./gradlew :app:connectedAndroidTest

# Build a specific core module
./gradlew :core:database:build
```

Dependencies are managed via the version catalog at `gradle/libs.versions.toml`. Min SDK is 35,
target SDK is 36, Kotlin 2.3.20, JVM 21.

## Module Structure

The project uses modular Clean Architecture with a strict dependency graph:

```
:app → :domain, :core:data, :core:database, :core:datastore, :core:designsystem, :core:location, :core:network, :core:pipeline, :core:state
:core:state → :domain, :core:database, :core:pipeline
:core:pipeline → :domain
:core:data → :domain, :core:database, :core:datastore, :core:location, :core:network
:core:database → :domain
:core:network → :domain
:core:location → :domain
:core:datastore → :domain
```

- **`:domain`** — Pure Kotlin library. Domain models, state regions, evaluation logic,
  pipeline/provider contracts, the capture contracts (`CaptureBus`, `EnvelopeBuilder`,
  capture schemas/DTOs), the `PlatformPreferences` read interface (#355), and the
  number/money/duration formatting SSOT (`format.Formats` money/decimal + `format.TimeFormats`
  `formatDuration`/`formatCountdown` — the locale policy, #358/#456/#467; lives here so both the
  UI and the state layer route through one definition; the Compose time helpers
  `rememberNow`/`rememberTimeFormatter` stay in `:core:designsystem`). No Android
  dependencies. (Repository *implementations* and Hilt bindings live in `:core:data`.)
- **`:core:pipeline`** — Accessibility pipeline, notification pipeline, JSON rule engine
  (RuleCompiler, Ruleset, JsonRuleInterpreter), observation classifier. Reads third-party UI.
- **`:core:state`** — Multi-region state machine (StateMachine, FlowRegionStepper,
  PlatformRegionStepper, CrossPlatformRegionStepper), effect map, `TransitionPolicy` (the
  expected/unexpected classification + commit graces; replaced the old `HealingPolicy`), crash
  recovery (StateManagerV2). Defines `EffectExecutor` and `MetadataProvider` interfaces.
- **`:core:database`** — Room entities, DAOs, and database setup.
- **`:core:data`** — Repository implementations, mappers, data sources. Bridges domain interfaces to
  concrete data layers.
- **`:core:network`** — Retrofit clients, OkHttp interceptors, EIA gas price API integration.
- **`:core:location`** — Play Services GPS tracking.
- **`:core:datastore`** — Preferences DataStore (seven single-concern stores behind Hilt
  qualifiers — app prefs, strategy, dev settings, odometer, app state, platforms,
  rule-capability grants).
- **`:core:designsystem`** — Brand system (no project deps): fixed dark/light palette (`AppColors`),
  Hanken Grotesk + Space Grotesk fonts (tabular numerals), `AppTheme` + `LocalGlance` (the public
  theme wrapper is `DashBuddyTheme`, the only `Dash`-named symbol kept — it's the app name, not a
  platform term), and the shared component library (`AppCard`, `AppChip`, `AppStatTile`,
  `AppGaugeRing`, `AppSegmented`, `AppSlider`, `AppBarChart`, `AppAccordion`). The design-system
  brand vocabulary is `App*`, not `Dash*` (#468) — no platform-flavoured names. No M3 dynamic
  color. Feature-specific composables stay with their feature (Package by Feature); only generic,
  data-in/lambdas-out components live here.
- **`:app`** — UI (Compose + overlays), side effect handlers (SideEffectEngine, odometer, screenshots,
  TTS, tips), Hilt DI wiring, and the `DashBuddyApplication` entry point.

## Architecture: Recognition Pipeline + State Machine

The core architectural challenge is understanding a third-party app's UI without an API. The
solution is a multi-stage pipeline:

### 1. Sensor Pipelines (`core/pipeline/`)

`AccessibilityListener` / `AccessibilitySource` capture raw Android `AccessibilityEvent`s;
`AccessibilityNodeMapper` normalizes window content into an immutable `UiNode` tree (defined in
`:domain`). Per-event-type sub-pipelines (`ContentChangedPipeline` — debounced,
`StateChangedPipeline`, `WindowsChangedPipeline`, plus click handling in `AccessibilityPipeline`)
and a parallel `NotificationPipeline` (`NotificationListener` → `NotificationFilter` →
`NotificationMapper`) emit `PipelineEvent`s. `AccessibilityPipeline.output()` drops in stages:
a fail-closed **rulesets-not-loaded** gate (#432), then **sensitive**/**noise** (the shared content
gate, #399), then **disabled-platform** (defense-in-depth), then **UNKNOWN** (captured to disk for
triage, never forwarded to the state machine). Snapshots are attributed to the window's *real* package (not the event's), so
our own overlay is dropped (#4 / PR #334). Frame admission is `FrameGate` (identity dedup +
content-hash rolling suppression of UNKNOWN frames, #360); envelope assembly is the shared
`CaptureWriter` (#361); `PipelineV2.events` is a HOT `shareIn` stream — one upstream pass feeds
all collectors, so side effects (captures, dedup state) can never double-run (#361). The merged
upstream is supervised — a crash logs + counts a restart and resubscribes with backoff instead of
silencing all sensing (#430) — and `PipelineStats` counts every gate decision, mapping failure,
and restart (periodic summary log line).

### 2. JSON Rule Engine (`core/pipeline/.../rules/` + `assets/rules/`)

Recognition is **data, not code**. Per-platform rule files
(`core/pipeline/src/main/assets/rules/doordash.json`, `uber.json` — spec in ADR-0001, editor schema
`docs/rules.schema.json`) are compiled by `RuleCompiler` and matched by `ObservationClassifier`.
Rules carry a `priority` (sensitive rules are priority 0 and `overrideable: false`, blocking all
further processing of banking/identity screens), `require` predicates, `bind` blocks, and `parse`
blocks that produce typed fields via `ParsedFieldsFactory`. There are no Kotlin matcher classes —
changing recognition means editing rule JSON plus corpus tests. **Rules cannot declare actuation**
(#425): the compiler rejects `click`/gesture effect verbs; rules instead expose well-known *target
bindings* (`acceptButton`, `declineButton`, `expandButton`) that the app-owned `RuleAction`
registry (`:domain`) consumes — see `docs/design/rule-capability-consent.md`.

### 3. Multi-Region State Machine (`core/state/`)

Observations reduce into `AppState(regions)` (`:domain`): **`FlowRegion`** (R0 — ground-truth
screen interpretation; holds the pending offer), one **`PlatformRegion`** per platform
(session/task lifecycle; unified grace via `pendingDestructive` — destructive commits are graced, incl. short authoritative windows for the dash summary AND the delivery receipt, and woken by `GRACE_COMMIT` timers, #431), and **`CrossPlatformRegion`**
(derived aggregates). The steppers (`FlowRegionStepper`, `PlatformRegionStepper`,
`CrossPlatformRegionStepper`) are pure and driven by `obs.timestamp` — never a wall clock — so
crash recovery can replay observations over the last snapshot. `StateManagerV2` hosts the
reduction, exposes `StateFlow<AppState>`, and owns crash recovery; `EffectMap` diffs prev/next
state into `AppEffect`s.

### 4. Side Effect Engine (`app/.../state/effects/`)

`SideEffectEngine` executes `AppEffect`s with `effects_fired` idempotency dedup (recovery-aware),
runs the evaluation loopback (offer eval → `OfferEvaluationEvent` back into the machine), and owns
the fail-closed action gates (#417): live `PermissionTierChecker` + the capability consent gate on
automation-triggered `RuleAction`s (grant store: `RuleCapabilityRepository` over the
`rule_capability_grants` DataStore; asset rules auto-grant at load).
Handlers: `OdometerEffectHandler`, `ScreenShotHandler`, `TipEffectHandler`, `TtsEffectHandler`,
`UiInteractionHandler` (package-scoped, label-verified `RuleAction` taps — the only path that ever
clicks a third-party app, #425), `OfferActionReceiver` (notification Accept/Decline actions).

## Development Principles

Every new feature or refactor holds to these — they are forefront design inputs, not afterthoughts:

1. **UDF (Unidirectional Data Flow).** State flows down, events flow up. Steppers/reducers are
   pure (no Android dependencies, no wall clock — `obs.timestamp` only); side effects happen at
   the edge (`EffectMap` diff → `SideEffectEngine`), never inside reducers. UI observes immutable
   state (`StateFlow<AppState>`, per-screen immutable `UiState` data classes) and dispatches
   events/intents — it never writes to repositories directly. The Reactive UI rules below are the
   Compose-facing half of this.
2. **MAD (Modern Android Development).** Kotlin-first, Compose-first, coroutines + Flow for async,
   Hilt for DI, Room / Proto DataStore for persistence, version catalog for dependencies.
   Modularization follows the MAD roadmap (the milestones are literally named "MAD Phase N"):
   layer modules under `:core:*` / `:domain`, feature modules under `:feature:*` (Phase 6),
   Package-by-Feature inside each.
3. **Clean code / single responsibility.** Small, focused files and classes — the #237 family
   exists because three files were allowed to grow past ~900 lines; don't add to an oversized
   file, split it first. No god objects (the original `SettingsRepository` was decomposed for
   exactly this; keep repositories domain-scoped). Composables stay small and stateless with
   hoisted state; shared components take pure data + lambdas.
4. **Kotlin/Android best practices.** Idiomatic Kotlin (data/sealed types over stringly-typed
   values — see #283), structured concurrency (scoped coroutines; `SharingStarted.WhileSubscribed`
   for shared flows), locale-safe machine string ops (`Locale.ROOT`), immutability by default.
5. **Single source of truth (SSOT).** Every piece of state, configuration, logic, or copy has
   exactly one owner; everything else *derives* from it (reactively, where it can change). No
   hand-maintained second copies — a private cache of a preference, a re-implemented formatter,
   a duplicated constant, or a parallel UI assembly is a divergence bug waiting to fire. The
   campaign receipts: five independent enabled-platform caches with three staleness behaviors
   (#356), twin `formatDuration`s that disagreed on negatives (#358), a duplicated sha256 whose
   copies had the same plaintext-leak bug (#362), and two hand-maintained economy editors that
   had already drifted (#357). When two surfaces need the same thing, extract one definition
   and point both at it; when a value can be computed from an owned anchor, compute it —
   don't store it twice.
6. **Security & privacy first.** The non-negotiable Pledges above (on-device computation,
   sensitive screens blocked at the matcher layer, opt-in network, edge PII scrub) are design
   inputs, not afterthoughts — every feature is measured against them before it ships. Working
   rules:
   - **Treat third-party UI and (eventually) downloaded rules as untrusted input.** The
     accessibility tree comes from another app; once the matchers split (#192) lands, rule JSON
     comes from a CDN. Both get bounded ingestion (size/depth/node/regex caps), fail-closed
     validation, and — for any remote rule source — **signature/integrity verification before
     compile**, which does not exist yet and is a hard prerequisite for that path.
   - **The dasher's sensitive screens are blocked, never parsed or stored** (the dasher's banking /
     DasherDirect / payment / own identity docs) — plus **document-image capture surfaces** (the
     license-scan camera, the signature pad), regardless of whose, since they're an image of an ID /
     a signature — at the matcher layer, in both sensor pipelines (#399). The gate fails CLOSED
     beyond rule coverage (#432): frames are dropped entirely until rulesets load, every platform
     shipping screen rules must ship sensitive rules (load-time check), and UNKNOWN captures are
     scrubbed by a rules-independent text-marker backstop (`SensitiveTextMarkers`, the SSOT the test
     scanner shares). **Customers are hashed, not blocked** (#463): customer-facing delivery screens
     — incl. an alcohol delivery's ID-CHECK instruction + arrival card — are *recognized*, with the
     customer name/address `sha256`'d in the parse, so only the literal scanner/signature surfaces
     are sensitive. A recognition change must not be able to downgrade the block side, nor start
     storing raw customer PII on the hash side.
   - **PII is hashed at the edge before it is persisted or could be uploaded** (`sha256`,
     fail-closed — never echo plaintext on failure, #362). This is how customers are
     differentiated without keeping their info; the dasher's own name (first + last-initial) is
     acceptable to process. Captures are debug-only (release binds
     `NoOpCaptureBus`, #346). Evidence screenshots are gated by `EvidenceConfig` at the engine
     edge (#426): master toggle AND per-category toggle, and an uncategorized capture never
     fires — the master default is OFF.
   - **Secrets never reach logs** (EIA api_key redaction, #348); network logging is debug-gated.
   - **Capability gates fail closed.** An effect whose permission tier isn't granted does not
     fire — tiers back onto real OS state (live accessibility-service handle, runtime location
     grant; #417 removed the always-true stub). Rules cannot request actuation at all (#425) —
     taps are app-owned `RuleAction`s aimed by ruleset target bindings and verified at fire
     time (package scope + label allowlist + strict click); any failed check aborts to manual.
     Automation-initiated taps must additionally be covered by a granted, content-pinned
     capability key (#417): rule loads enumerate capabilities and reconcile them into the
     grant store *before* rules go live; bundled (asset) sources auto-grant, remote sources
     never will. A dasher-pressed Accept/Decline is its own consent (integrity checks still
     apply). Consent UI = #422 PR 3.
   When a change touches recognition, capture, network, or effects, state its security/privacy
   posture in the PR — what's trusted, what's gated, what's scrubbed.
7. **Semantic, PII-safe logging.** Log levels carry *meaning*, not volume convenience, and the log is
   two products: an on-device **DEBUG firehose** for us, and an **INFO+ slice a user can export as a
   bug report** to send to the developer. Pairs with #6 (the export is a privacy surface). The 06-19
   dash is the receipt: ~110k lines, one Timber tag (`App`), DEBUG 76% / INFO 22% / WARN 1.2% / ERROR
   0, with per-frame `SCREEN:` spam at INFO and benign tree-mapper noise drowning the real WARN — and
   raw merchant names already leaking into INFO+ lines (`Pickup: H-E-B`, a TTS line naming two stores).
   The level taxonomy:
   - **VERBOSE** — per-frame trace (`SCREEN:` lines, tree-mapper `👻 NULL CHILDREN`). Firehose only;
     never shipped or exported.
   - **DEBUG** — single reducer/effect steps (`PROCESSING: <Event>`, grace-resume, gate decisions,
     capture writes). Firehose only.
   - **INFO** — user-meaningful milestones (offer received/accepted/declined, delivery completed,
     crash recovery, periodic `PipelineStats`). This is the **shareable** stream and **must be PII-safe
     by construction**: economics, counters, state names, `sha256` hashes only — **no raw
     store/customer/address text** (the dasher's own first + last-initial is fine). Raw third-party UI
     text lives at DEBUG/VERBOSE only.
   - **WARN** — a defended invariant fired (a suppressed phantom/ghost, a fail-closed gate denial, a
     grace timer waking a commit, an unhandled event). Must not be drowned by benign noise — if it's
     benign and frequent, it's VERBOSE.
   - **ERROR** — lost data or a crashed subsystem (pipeline restart, snapshot/journal write or decode
     failure, recovery failure). Always shipped/exported.

   Every component logs under its own **stable tag** (`Timber.tag("Pipeline")`, `"StateMachine"`,
   `"Effects"`…), never the catch-all `App`. The INFO-must-be-PII-safe rule is **fail-closed and
   tested** (reuse `SensitiveTextMarkers`): a raw merchant/customer string in an INFO+ line is a
   privacy defect of the same class as leaking it to disk — gate the shareable-export sink behind that
   test, do not trust call-site discipline. When a change adds or moves a log site, state its level and
   (for INFO+) confirm it carries no raw PII. (Implementation tracked in #551.)
8. **Platform-agnostic core.** The recognition→state→effects spine never encodes a specific gig
   platform. Recognition is data (per-platform rulesets), not code; platform identity resolves
   only through the `Platform` registry (`fromRuleId`/`fromPackage`/`fromWire`) — never a literal
   `== Platform.DoorDash`, hardcoded package name, or wire string gating logic. Anything that
   varies by platform — vocabulary, required parse fields, effect-bearing intents, grace timing,
   offer slots, lifecycle-edge anchors, learned rate models — is either ruleset data validated at
   load or state keyed by `Platform`, never a global tuned to whichever platform we field-test
   most. New rule↔state vocabulary goes through the enumerated, load-validated contract
   (`StateMachineContract` + `REQUIRED_FIELDS_BY_SHAPE`), not a magic string consumed by a Kotlin
   `when`. The acceptance test: adding a platform means shipping a ruleset + corpus with **zero**
   `:core:state` / `:core:pipeline` / `:domain` edits. DoorDash-heavy field testing *masks*
   violations (a global slot never collides while only one platform runs), so this is enforced at
   PR-review time — see *Every PR gets a design-goal review* under Git Workflow, and the drift
   catalog in #585 (+ the per-platform ownership pack #438) for the known seams and receipts.

If a change genuinely can't satisfy one of these, say so explicitly in the PR description instead
of silently violating it. The design-goal review below exists to catch exactly that before merge.

## Reactive UI Principles

We picked Jetpack Compose for a reason — **leverage it**. Especially in the bubble HUD, which is a
glance surface where stale data is worse than no data because it gives the dasher false confidence.
Every UI element that depends on time, state, or external data should re-render automatically when
that input changes. No manual refresh, no waiting for the next state-machine transition to update
something the user is looking at right now.

**Core rules:**

1. **State drives UI continuously, not just on transitions.** Anything time-derived — timers,
   countdowns, deadlines, relative timestamps — must tick. A composable that renders
   `formatDuration(now - arrivedAt)` once at state-change time is broken; it freezes the moment the
   reducer last fired.
2. **State classes hold the anchor, the UI derives the value.** Store `arrivedAt: Long?` on
   `OnPickup`, not `secondsAtStore: Int`. Store `pickupDeadlineMs: Long?`, not
   `"7 min left"`. The composable reads the anchor + a ticker and computes the display string.
   Keeps the state minimal, lets the UI stay fresh without reducer churn.
3. **The 1-Hz ticker is a `produceState` loop.** Cheap, idiomatic, scoped to the composable:

   ```kotlin
   @Composable
   fun rememberNow(tickMs: Long = 1000L): State<Long> = produceState(System.currentTimeMillis()) {
       while (true) { value = System.currentTimeMillis(); delay(tickMs) }
   }
   ```

   Then `val now by rememberNow()` and derive whatever you need. Don't roll your own
   `LaunchedEffect` + `mutableStateOf` for each timer — use the helper.
4. **Don't conflate "state changed" with "UI updated".** Compose recomposes when any observed
   state changes — including a `now: Long` flow. Time-sensitive views are driven by the ticker, not
   by waiting for a `StateManagerV2` emission.
5. **Bubble HUD has the strictest reactivity bar.** If a value can change while the dasher is
   looking at it, it must re-render without a state transition. Treat any frozen-looking value as
   a defect, not a styling choice.

When in doubt: would the dasher believe a stale value? If yes, it's reactive-broken.

## Snapshot Regression Testing

Tests are data-driven using captured UI hierarchy JSON files under
`app/src/test/resources/snapshots/`.

**Adding new test cases (Inbox Workflow):**

1. Drop raw UI hierarchy `.json` files into `snapshots/INBOX/` (gitignored).
2. Run `InboxProcessorTest` — it auto-sorts recognized screens into category folders, fails on PII,
   and prints an X-Ray report for unknowns.
3. For **unknown screens**: read the X-Ray report, add or broaden a rule in
   `core/pipeline/src/main/assets/rules/<platform>.json`, re-run.
4. For **sensitive screens**: manually redact the JSON, move to `snapshots/SENSITIVE/`, verify with
   `AllMatchersSuite` (the golden guard asserts every `SENSITIVE/` snapshot is caught by a
   sensitive rule or flagged toxic by `SnapshotSecurityScanner`).
5. Commit only the sorted files from their category folders (never from `INBOX/`).
6. New corpus changes the parse-output golden — regenerate it deliberately (next section, step 4)
   and commit `snapshots/approved-parse-output.json` together with the new snapshots.

**Adding or changing a recognition rule** (there are no matcher classes to register — rules are data):

1. Edit the platform's rule JSON (`$schema` gives editor autocomplete/validation against
   `docs/rules.schema.json`). Golden-corpus folder name == expected intent.
2. Tests compile the **production** rule files via `TestRulesetFactory` — nothing else to wire up.
3. Run `InboxProcessorTest` (sorting) and then `AllMatchersSuite` (golden guard + parse-output
   golden + ruleset + classifier regressions) to verify.
4. If the change legitimately alters **parse output** (`ParseOutputGoldenTest` fails, #433):
   regenerate with `./gradlew :app:testDebugUnitTest --tests "*ParseOutputGoldenTest*"
   -DupdateParseGolden=true`, then **review the diff of `approved-parse-output.json`** — that
   review is the regression gate — and commit it with the rule change. The same test also
   ratchets corpus coverage (new intents should ship with corpus) and lints dedupeKey
   `{field}` templates against fields the rule actually parses.

### Session replay (capture sequence → recognition → state machine)

The snapshot tests above are **per-frame** (does this screen recognize/parse right, in isolation).
To reproduce a **field bug that's emergent across a session** — a ghost offer, a re-mint, doubled
dropoffs — `SessionReplay` (`app/src/test/.../test/util/SessionReplay.kt`) replays a *chronological
sequence* of real device `CaptureEnvelope`s: `replayRecognition` (Level A → `List<Observation>` via
the production rules), `reduce` (Level B → folds through the real `StateMachine`, returning a
per-frame trace of `{frame, observation, stateAfter, events}` where events match the db `app_events`
shape), and `reduceMixed` (Level B with **click + grace-timer injection** — a timestamp-ordered mix of
real screens, real/synthetic clicks, and synthetic `GRACE_COMMIT` timers folded through one
`StateMachine` + one classifier wired to both screen and click rulesets).
The captured session's db `app_events` is a **characterization** oracle (it *encodes* the bug), so
Level-B assertions are hand-authored correct-behaviour invariants, **never `replay == db`**.
`GhostOfferReplayTest` (Level A/B, #498) and `SingleDeliveryReplayTest` (the click+timer injection
worked example — accept-click → pickup → dropoff → complete, exactly one dropoff, #498/#503/#518) are
the worked examples; both are green. Remaining frontier (on-device review tool, verdict export,
eval-loopback net economics, GoPuff multi-drop repro) is tracked under epic #505.

## Key Technologies

- **DI:** Hilt 2.59.2 with KSP
- **UI:** Jetpack Compose (BOM 2026.03.00), Material 3, custom `BubbleActivity` floating overlay
- **Async:** Kotlin Coroutines 1.10.2 + Flows
- **DB:** Room 2.8.4 (KSP)
- **Prefs:** DataStore Preferences 1.2.1
- **Network:** Retrofit 3.0.0 + OkHttp 5.3.2
- **Testing:** JUnit 4, Mockito-Kotlin 6.3.0, Robolectric 4.16.1
- **Logging:** Timber 5.0.1

## Git Workflow

**Before creating a branch**, mark the issue(s) as **In Progress** in the GitHub project board
**and add the `in-progress` label** to the issue(s). See `CLAUDE.local.md` for the command to
update the project Status field.

**Always create a branch** before starting work on any issue or set of issues. Never commit
feature/fix work directly to `master`.

Branch naming: `<type>/<issue-number(s)>-<short-description>`, e.g. `feature/110-bubble-offer-mode` or `fix/42-crash-on-startup`. Common types:

| Type | Use for |
|---|---|
| `feature/` | New functionality |
| `fix/` | Bug fixes |
| `refactor/` | Code restructuring, no behavior change |
| `chore/` | Housekeeping, dependency bumps, config |
| `test/` | Adding or fixing tests |
| `ci/` | CI/CD pipeline changes |
| `docs/` | Documentation only |

Include all relevant issue numbers when a branch covers multiple issues, e.g. `feature/79-80-offer-bubble-net-pay`.

**Always merge PRs with `--merge`** (a true merge commit), never `--squash`. Squash loses
per-commit history and makes it harder to bisect or attribute changes. **Always pass
`--delete-branch`** so the merged branch is pruned automatically — without it, merged branches
pile up on the remote (a 57-branch cleanup on 2026-06-12 was the receipt).

```bash
gh pr merge <NUMBER> --merge --delete-branch
```

(The `gh` binary path and all project/field IDs are workstation-specific — they live in
`CLAUDE.local.md`, which is gitignored. Never hardcode workstation paths in this file.)

**Branch deletion is workstation-only. Field/remote agents MUST NOT attempt to delete their own
branch.** Agents running in the remote execution environment (Claude Code on the web / mobile app)
reach GitHub through a local auth proxy (`http://local_proxy@127.0.0.1:…`) that **refuses ref
deletions** — every form of the delete hangs the connection (`send-pack: unexpected disconnect` /
`the remote end hung up unexpectedly`, then a misleading `Everything up-to-date`). This was verified
2026-06-18 against the merged branch with **all** of: `git push origin --delete <branch>`,
`git push origin :<branch>`, and a retry with the sandbox disabled — all failed identically. The
GitHub MCP server (the only GitHub access these agents have besides git) exposes **no**
branch/ref-delete tool, and `merge_pull_request` has no delete-branch option. So there is **no method
available to a field/remote agent to delete a branch.** Do **not** run the delete (in any form) and
do **not** retry it — repeated failing attempts are just noise. Instead: after merging via the MCP
`merge_pull_request` tool, **leave the branch** and note in your final reply that it needs manual
cleanup — the merged branch can be pruned from the **GitHub PR page's "Delete branch" button** or by
the **workstation agent** with the `gh pr merge … --delete-branch` command above. (The
`--delete-branch` rule applies to the workstation `gh` flow, which can and should prune; it does not
apply to remote/MCP merges, which cannot.)

**Every PR ships with context updates — no exceptions.** As part of preparing/merging ANY PR:

1. **Memories** — update the persistent memory (see *Claude Memory Upkeep*) to record what the
   PR changed and what it means for project status. Every merged PR changes status by
   definition, so this step always applies.
2. **CLAUDE.md** — re-check the sections the change touches (architecture, modules, workflows,
   commands, labels, principles); if the PR makes any statement in this file stale, fix it
   **in the same PR**.

A PR that skips these is incomplete — future agents inherit their entire context from these
two places.

**Every PR gets a design-goal review — no exceptions.** After CI is green and before
`gh pr merge`, review the **full diff** against the Development Principles and record the result
in the PR description under a `### Design-goal review` heading (append it if the PR was opened
without one):

1. Walk principles 1–8 (UDF, MAD, single responsibility, Kotlin/Android practices, SSOT,
   security & privacy, semantic logging, platform-agnostic core) — plus the Reactive UI rules for
   UI-touching diffs. For each goal the diff **touches**, write one line: how the change conforms,
   or the explicit, justified violation (never silent). Goals the diff doesn't touch need no line.
2. The platform-agnosticism check (principle 8) applies to **every** PR touching `:core:state`,
   `:core:pipeline`, `:domain`, or rule JSON: no new platform literals in logic; new vocabulary
   goes through the enumerated, load-validated contract (never a magic string + a Kotlin `when`);
   per-platform values are keyed by `Platform`, not stored as globals.
3. Fix in-PR what can be fixed in-PR. A violation that legitimately can't be fixed there is
   filed as an issue (labels + board, per the sections below) and linked from the review block —
   a PR never merges with an unrecorded violation.

The audits keep finding drift that PR review could have caught (the #356 SSOT family; the #585
platform-coupling catalog). This loop moves detection from post-hoc audit to merge time.

**Then an adversarial review — an independent reviewer, not self-attestation (#589).** The
design-goal review above is the *author* walking their own diff, and self-review shares the
author's blind spots (exactly how the #356 and #585 drift slipped in). So after it, and before
`gh pr merge`, spawn a **separate** reviewer to attack the diff — reuse the existing skills, don't
build parallel machinery: run `/code-review` (already multi-agent/adversarial) and, for anything
touching untrusted input or the Pledges, `/security-review`. The adversary works three axes it must
**not** take the author's framing on:

1. **Correctness** — bugs, edge cases, reuse/simplification (`/code-review`'s default).
2. **Design principles** — walk principles 1–8 + the Reactive UI rules hunting for violations the
   author *missed or rationalized away*. **Platform-agnosticism (#8) is mandatory** for any diff
   touching `:core:state` / `:core:pipeline` / `:domain` / rule JSON.
3. **Security** — `/security-review` the branch; for diffs touching the untrusted-input boundaries
   (rule compile/ingest, accessibility-tree mapping, regex, PII/sensitive handling, capture/effects)
   confirm the fail-closed + bounded-ingestion + no-plaintext-leak properties still hold, backed by
   the security property/fuzz suite (#590).

**Same-tier rule:** the adversarial reviewer runs at the **session model tier** (Mythos/Fable
class) — never downgraded to a lower tier. An adversary weaker than the author is theater; this is
the Subagent Model Policy applied to review. Record the outcome in a `### Adversarial review` block
in the PR description; fix findings in-PR or file + link them. A PR merges only once the adversarial
pass is clean or its findings are triaged.

**Docs-only / non-code PRs can skip CI.** The `pr-check.yml` workflow skips the
`build-and-test` job when the **PR description (body)** contains the literal
string **`[skip ci]`** (`if: ${{ !contains(github.event.pull_request.body, '[skip ci]') }}`).
So for a PR that touches no compiled code (only Markdown/docs), put `[skip ci]`
in the PR body to avoid a pointless ~6-minute build. Note the exact token is
`[skip ci]` in the **body** — not `[no-ci]`, not a comment, not a label. Only use
it when the diff genuinely has no code; when in doubt, let CI run.

## Session Orientation

At the start of a session or before picking up new work:

1. **Check open issues and their blocking relationships** — don't just look at what's open, look at
   what's blocking what.
2. **Prioritize blockers first** — if issue A is blocking issues B, C, and D, resolve A before
   starting B, C, or D.
3. **Determine logical next steps** from the milestone/phase ordering, then check those candidates
   for unresolved blockers before committing to a direction.
4. **Update the project-state memory** (`project_state_<date>.md` in the memory directory —
   see *Claude Memory Upkeep* below) if the current state has drifted from what's recorded.

## Claude Memory Upkeep

The persistent memory directory (path in `CLAUDE.local.md` § Local workstation) is shared state
for **every** Claude agent and session that works on this repo — orientation quality next session
depends on what got written down this session. **Keeping memories current is part of finishing
any piece of work, not an optional extra.** Whenever your work changes project status — merging
a PR, closing / re-scoping / filing issues, completing a milestone phase, locking a design
decision, running an audit, or discovering that something a memory records is no longer true —
update the relevant memory file(s) **in the same session**, and keep the `MEMORY.md` index in
sync (one pointer line per memory).

Rules of thumb:

- **A stale memory is worse than no memory** — it actively misleads the next agent. Correct or
  delete wrong memories on sight; deleting a wrong memory is as valuable as writing a new one.
- The dated `project_state_<date>.md` snapshot is **replaced** when a newer full audit
  supersedes it (delete the old file, update the `MEMORY.md` pointer) — don't accumulate
  stale snapshots.
- Don't duplicate what the repo already records (code, git history, issues, ADRs, this file) —
  memories hold status, decisions, and context that are *not* derivable from the repo.

## Claude Subagent Model Policy

**The intended shape of substantial work is a three-phase pipeline: PLAN AND VET AT FABLE →
BUILD WITH LOWER TIERS → VALIDATE AT FABLE.** The developer deliberately pays the fable cost
at both ends: idea-building, design, and vetting happen rich and up front (waiting out a
usage reset is an accepted side effect — the developer prefers spending more on initial
planning over cheap plans), and the PR checks close the loop at the top tier. The two ends
justify the middle: a well-vetted plan with sharp boundaries is precisely what makes the
build safely delegable, and the fable check is what makes a cheap build trustworthy.
**"Reserve fable" therefore means one thing: don't burn the scarce tier on mechanical
execution in the middle.** It does NOT mean skimping on planning, design, vetting, or
review. Never dip into usage credits or pay-by-token on the developer's behalf (the
2026-06-25 mid-workflow session-limit collapse — 36 agents dead, all spend wasted — is the
receipt for budget awareness).

Rules, in precedence order:

1. **PR checks are ALWAYS fable-tier.** The adversarial pre-merge review (the #589/#591
   loop), `/code-review`, `/security-review`, and any verdict that gates a merge run on the
   top tier, no exceptions. The reviewer is the safety net for everything built below it,
   and it has repeatedly caught author-missed defects (PRs #609/#610/#611). Save budget on
   the builder, never on the reviewer.
2. **Planning, design, and vetting run at fable by design.** Usually that is the main loop
   itself; fable *subagents* are also legitimate in this phase — adversarial design vetting,
   judge panels, final adjudication of conflicting evidence, pledge/security-critical
   verdicts — name the phase when spawning one. The deliverable of this phase is a plan
   bounded enough to hand down: named files, the expected change shape, acceptance criteria,
   and what the tests/review must prove.
3. **The default subagent tier for everything else is opus — set `model` explicitly at
   every spawn.** Do NOT omit the model to "inherit the parent": when the session itself
   runs fable, omission silently burns the scarce tier on work opus handles. (This
   deliberately replaces the old "prefer the exact same model" guidance.)
4. **Delegating BELOW opus is allowed only for building well-bounded implementations from
   an already-vetted plan**, and only when confident the smaller model can execute: named
   files, an explicit expected change shape, and cheap verification (existing tests and/or
   the fable PR check will catch a failure inexpensively — boundedness is what makes
   verification cheap, which is what makes the delegation actually save anything). Sonnet
   is the bounded-build workhorse; haiku only for trivially mechanical scans. Never hand a
   lower tier open-ended research, design, root-cause analysis, or judgment work —
   boundedness is the control that limits hallucinations and expansive errors. If the plan
   isn't bounded enough to delegate, that is a phase-2 gap: sharpen the plan at fable, don't
   promote the builder.
5. **Weigh the usage limit at the moment of delegation.** There is no meter to read, so:
   treat fable scarcity as a standing assumption, not a measurement; if limit errors have
   appeared this session, or the developer has signaled they need their usage (e.g. about
   to dash), shrink or defer the work, or ask before launching — a workflow that dies
   mid-fan-out wastes everything it already spent. When the budget is tight, **economize in
   the middle phase**: the plan/vet and validate ends keep fable priority (the developer
   prefers waiting for a reset over cheap planning or cheap review).

## Field Testing Logs

**At the START of a field-testing session** — when the developer signals they're about to
dash or are starting a field test (e.g. "starting a field test", "about to dash", "heading
out", "field testing now", "what should I look for") — **first read the
`## Next field test — things to look for` checklist in `docs/field-testing/README.md` and
report it back concisely**: tell the developer, in plain terms, what to watch for and how to
tell if each item is working. This is the primary job of a field-testing agent launched on
the phone before a dash. Keep it short and glanceable — they're about to be driving.

**Each checklist item needs two independent field confirmations before it's validated** — a
single dash can pass by luck or miss the edge case. Track with a `- Confirmed: N/2` sub-line
(note each sighting's date/conditions). Only on the **second clean** confirmation do you move
the item into that session's log entry and delete it from the checklist; if an item is found
**broken**, move it to the log immediately so it gets triaged.

**Closing the loop — add items when work needs field validation.** Whenever you open a PR or
close an issue for a change that **needs or would benefit from field validation** — on-dash
behavior, bubble/HUD changes, recognition rules or parsers, or anything verified only against
captured data — **also add a matching item to the `## Next field test — things to look for`
checklist** (what to watch + how to tell it's working + the PR/issue number, starting at
`Confirmed: 0/2`). Treat this as part of finishing the PR/closing the issue, not an
afterthought — it's how field-validation work reaches the developer on the next dash.

When the developer narrates observations from an active or just-completed dashing session —
any of: "this is a field testing log", "dashing log", "field log", "on-dash testing notes",
or just rattling off bugs/UX observations from time spent driving — the right action is to
**record, not fix**:

1. **Add an entry to `docs/field-testing/README.md`**, newest first, following the format
   documented at the top of that file (Date / Platform(s) tested / Branch under test / Field
   conditions / sectioned observations). Item numbers are session-local — reset to #1 each
   session.
2. **Don't start implementing — but desk-side exploration is encouraged.** Reading the
   code, tracing the suspect flow, citing file/line refs, and proposing what the cause
   *might* be is genuinely useful and welcome inside the log entry. **What's not welcome is
   framing any of it as a concluded answer or a fix to apply.** Phrase everything as a
   **hypothesis**: "likely cause", "one possibility", "would need to confirm X by capturing
   Y", "if this hypothesis holds, one direction might be …". Never write "Proposed fix:"
   followed by a concrete action and never push code changes. The developer decides what's
   actually wrong and what to do about it; the log entry exists to feed that decision, not
   pre-empt it.
3. **Match the structure of prior entries.** Existing sections include Bugs / Field UX
   context / Open questions / Meta / architecture / Research / design / Verification TODOs.
   Omit sections that don't apply for the session.
4. **Branch under test** — if not stated, infer from the most recent merge commit on `master`
   and label it (e.g. "`master` at `<sha>` (post-#NNN merge)"); the developer can correct it.

## GitHub Issues — Labels & Project

**Always** add every new issue to the DashBuddy Roadmap project. See `CLAUDE.local.md` for the
project number, owner, and `gh` CLI path used to do this.

**Always** apply at least one label. Available labels and when to use them:

| Label           | Use for                                                |
|-----------------|--------------------------------------------------------|
| `enhancement`   | New user-facing features                               |
| `refactor`      | Code restructuring with no behavior change             |
| `bug`           | Something broken                                       |
| `architecture`  | Design decisions, RFCs, interface definitions          |
| `offer-engine`  | Offer evaluation, scoring, UserEconomy, OfferEvaluator |
| `testing`       | Test infrastructure, regression tests, coverage        |
| `ci/cd`         | GitHub Actions, build pipelines, release automation    |
| `chore`         | Housekeeping, non-feature maintenance                  |
| `cleanup`       | Code hygiene, removing dead code, string extraction    |
| `documentation` | Docs improvements                                      |
| `data-enrichment`  | Parser fields for event sourcing fidelity / replay  |
| `on-dash-testing`  | Bug or behavior discovered while actively dashing in the field |
| `in-progress`   | Actively being worked / partially landed (pairs with board Status = In Progress) |

Apply multiple labels when appropriate (e.g. `refactor` + `architecture`, `testing` +
`offer-engine`).

## GitHub Issues — Blocking Relationships

When one issue is pre-work for or directly blocks another, **always set the blocking relationship**
so the dependency is visible in the GitHub UI. Use the `addBlockedBy` GraphQL mutation — see
`CLAUDE.local.md` for the exact command.

Convention: if you say "pre-work for #X" or "blocked by #Y" in an issue description, make it real.

## Device Setup Note

To run on a physical device, manually enable DashBuddy under **Android Settings → Accessibility →
Downloaded Apps**. The app requires `ACCESSIBILITY_SERVICE` and `ACCESS_FINE_LOCATION` permissions.