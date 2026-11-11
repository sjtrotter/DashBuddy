# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this
repository.

## Alpha Status

**This is alpha software. There is exactly one user: the developer.** Do not
prioritize backward compatibility, migration paths, graceful degradation for end users, or
polish concerns unless they directly affect the developer's ability to test the app. Treat
every decision with a single-user, single-device assumption until explicitly told otherwise.

## Project Vision & Strategic Pillars

gig.log exists to give independent contractor delivery drivers the same information the
platform already has about their own work. Mission: **driver sovereignty through
transparent, on-device, user-owned tooling.** Three pillars, each with its own architectural and
licensing posture (detail, pricing and RFCs: `docs/ROADMAP.md` § Strategic pillars):

1. **Driver Sovereignty (the product).** A free local tier and a paid managed-cloud tier; source-available.
2. **Distributed Integrity (the matchers).** The recognition rules, licensed Apache-2.0 so they can be forked.
   They stay in-tree in `matchers/`; the separate repo was closed as not planned.
3. **Academic Federation (the research).** Opt-in, anonymized aggregation across university-hosted instances
   under IRB review; only aggregate results are returned.

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
`PRIVACY.md` (repo root) is the engineering ground-truth privacy document; a change to what the app reads, stores or sends updates it in the same PR. A change to a claim there updates the in-app privacy page (`privacy_*`) and every disclosure string that states it, in the same PR.

**Framing discipline.** When writing public-facing material — issues, RFCs, README, grant
copy, marketing — describe the academic pillar as **empirical measurement of the visible
offer surface**, never as reverse-engineering, model recovery, or algorithm characterization.
The DoorDash ICA §15.4 prohibits reverse-engineering of the platform; gig.log does not
do that and our written material must not suggest otherwise. See `LEGAL.md` for the
good-faith ICA interpretation that governs scope decisions.

## Build Commands

**Local Gradle is small runs only, one at a time, through `~/dashbuddy/tools/pr-loop/gt.sh`'s memory guard. PR CI
is the gate.** Never run the full gate below on the laptop, never start two Gradle runs at once, and never run a
Claude analysis agent beside one; `./gradlew --stop` when a run finishes. Host rules and `JAVA_HOME` are in
`CLAUDE.local.md`.

```bash
# The full unit gate (PR CI runs it; locally run one module, e.g. ./gradlew :domain:test)
./gradlew testDebugUnitTest :domain:test censusContractTest

# Ruleset/recognition regressions only (fast; does NOT sort INBOX or re-triage UNKNOWN)
./gradlew :app:testDebugUnitTest --tests "*AllMatchersSuite*"

# One recognition test (e.g. the golden-corpus positive guard)
./gradlew :app:testDebugUnitTest --tests "*GoldenSnapshotRegressionTest"

# Property/fuzz classes unseeded at 10x samples, off the PR path (nightly/manual). When it finds
# something, paste the reported seed into that property's pinned SEED const (root build.gradle.kts explains the flag).
./gradlew :core:pipeline:testDebugUnitTest :app:testDebugUnitTest -Dgiglog.propExplore=true

# Instrumented tests (connected device/emulator)
./gradlew :app:connectedAndroidTest

# One module's build
./gradlew :core:database:build

# Key-screen screenshots (PR CI renders them after the gate). Without -PscreenShots=true the
# *ScreenshotsTest classes compile but never run.
./gradlew --continue :feature:bubble:testDebugUnitTest --tests '*ScreenshotsTest' \
  :app:testDebugUnitTest --tests '*ScreenshotsTest' -PscreenShots=true
```

Versions live in the catalog `gradle/libs.versions.toml`. Min SDK 30, target SDK 36, JVM 21.

## Module Structure

The project uses modular Clean Architecture with a strict dependency graph:

```
:app → :domain, :core:data, :core:database, :core:datastore, :core:designsystem, :core:location, :core:network, :core:pipeline, :core:state, :feature:settings, :feature:setup, :feature:dashboard, :feature:bubble
:core:state → :domain, :core:database, :core:pipeline
:core:pipeline → :domain
:core:data → :domain, :core:database, :core:datastore, :core:location, :core:network
:core:database → :domain
:core:network → :domain
:core:location → :domain
:core:datastore → :domain

:feature:settings  → :domain, :core:data, :core:designsystem
:feature:setup     → :domain, :core:data
:feature:dashboard → :domain, :core:designsystem
:feature:bubble    → :domain, :core:designsystem

matchers (included build, not a :core module) ⇒ canonicalizes rules → :core:pipeline consumes as generated assets
census-contract (included build, Apache-2.0) ⇒ the census wire contract + the rule-authoring contract (`authoring/`: vocabulary + `RuleDraft` generator) that the census dashboard and the app's round-trip gate share; :domain depends on it (api), the dashbuddy-census server includeBuilds it
```

**Feature modules.** UI lives per feature under `:feature:*` (`cloud.trotter.log.gig.feature.<name>`). A feature
module depends only down the graph: never on `:app`, never on another feature. Each module declares only the deps it
uses. The host and the injections decide where a ViewModel lives, not the source path: one that injects `:app` or
`:core:state` machinery (`DashboardViewModel`, `BubbleViewModel`) stays in `:app`. Before changing a `:feature:*`
module, the bubble HUD, the Garage or navigation, read
[`docs/architecture/06-ui-modules.md`](docs/architecture/06-ui-modules.md) (it also holds the full module catalogue).

- **`:domain`**: pure Kotlin, no Android dependencies. Domain models, state regions, evaluation logic, the
  pipeline, provider and capture contracts, the `PlatformPreferences` read interface, and the number, money and
  duration formatting owner (`format.Formats`, `format.TimeFormats`). The `region` package's cell id is the only
  location-shaped value that may ever leave the device. Repository implementations and Hilt bindings live in
  `:core:data`. The census wire contract lives in the `census-contract/` included build; `domain.privacy.PiiShapes`
  stays here.
- **`:core:pipeline`**: accessibility and notification pipelines, the JSON rule engine, the observation
  classifier. Reads third-party UI.
- **`:core:state`**: the multi-region state machine, effect map, `TransitionPolicy`, crash recovery
  (`StateManagerV2`). Defines the `EffectExecutor` and `MetadataProvider` interfaces.
- **`:core:database`**: Room. **No `fallbackToDestructiveMigration`:** `app_events` is the source of truth, so an
  upgrade Room has no path for must fail loud. A schema change bumps `GigLogDatabase.VERSION`, regenerates the
  exported schema JSON, adds the `AutoMigration` (or `Migration`) and its `MigrationTestHelper` case; CI's
  `Schemas committed` step and `SchemaVersionGuardTest` enforce it. Before changing the schema, read
  `docs/architecture/05-analytics-read-model.md` § Database safety posture.
- **`:core:data`**: repository implementations and mappers. **`:core:network`**: Retrofit/OkHttp, EIA gas prices,
  the census transport. **`:core:location`**: Play Services GPS.
- **`:core:datastore`**: Preferences DataStore, one store per concern behind Hilt qualifiers. A user artifact that
  nothing in `app_events` can rebuild (the weekly plan, the vehicles) lives here, never in Room, because a
  `PROJECTOR_VERSION` bump wipes the read model.
- **`:core:designsystem`**: the brand system and the shared `App*` components (no project deps, no M3 dynamic
  color, no `Dash*` names), plus the shared display vocabulary (`time.TimeKit`, `text.EMPTY_VALUE`). Only generic
  data-in/lambdas-out components live here; feature-specific composables stay with their feature.
- **`:app`**: the UI shell and overlays, side-effect handlers, Hilt wiring, the `GigLogApplication` entry point.
- **`matchers/`**: an included build, not a `:core` module. It owns the per-platform JSON5 rule source and the
  canonicalizer; `:core:pipeline:importMatchersRules` generates `assets/rules/<platform>.json`, and no rule asset is
  committed. Apache-2.0, kept in-tree.

## Architecture: Recognition Pipeline + State Machine

gig.log reads a third-party app's UI without an API: sensor pipelines, then JSON rules, then a multi-region state
machine, then side effects, then an analytics read model. Each subsection below lists only the invariants that break
silently. The full rules, with the issue behind each, live in `docs/architecture/0N-*.md`.

### 1. Sensor Pipelines (`core/pipeline/`)

Before changing a pipeline gate, capture, a redaction entry, a backstop or the census, read
[`docs/architecture/01-sensor-pipelines.md`](docs/architecture/01-sensor-pipelines.md).

- `PipelineV2.events` is a hot `shareIn` stream, so side effects never double-run.
- `AccessibilityPipeline.output()` gates in order: rulesets not loaded (fail closed) → sensitive/noise → disabled
  platform → UNKNOWN (captured to disk for triage, never forwarded to the state machine). Snapshots carry the window's
  real package, so our own overlay is dropped.
- Redaction is envelope-only: recognition, parse and dedup run on the original tree, and `CaptureWriter` applies the
  matched rule's `redact` block to the envelope.
- The mask is `[redacted:<4hex>]` (the first 4 hex of the token's sha256), fail-closed to plain `[redacted]`.
  `maskNode` is first-match-wins, so blanket and plain entries sit ahead of generic shape entries.
- A redact protects only frames its own rule wins. A banner that can inflate over another rule's frame is declared
  by every rule it can land on; parity tests pin these.
- For an id-less, prefix-less slot, a recognized rule that forgets to redact has no backstop. The backstops
  (`SensitiveTextMarkers`, `CustomerTextMarkers`, `ID_MARKERS`, `UnknownAddressBackstop`) are rules-independent
  data; `ID_MARKERS` and the address backstop run on UNKNOWN envelopes only.
- Recognize-only is not state-inert: it moves `FrameGate.lastIdentity`. Only a rule with no `state` block is
  lifecycle-neutral.
- Release binds `NoOpCaptureBus` and `NoOpCensusSink`; UNKNOWN captures are the debug-only exception. A census
  failure never changes recognition.
- Liveness diagnostics (`RecognitionHealth`, the `ParseShortfall` censuses, `PlatformAppVersions`) fail open: a
  diagnostic failure must never become a recognition gate.
- Anchors and marker lists are English literals. On a non-`en` device recognition falls to UNKNOWN; `ID_MARKERS` is
  the one locale-immune defence.
- The `PipelineStats` INFO summary starts with `app=<versionName>` (`<base>+<git sha>`). Read a pull's build from
  the logs; never infer it.

### 2. JSON Rule Engine (`core/pipeline/.../rules/` + generated `assets/rules/`)

Before editing `matchers/rules/` or the rule compiler, read
[`docs/architecture/02-rule-engine.md`](docs/architecture/02-rule-engine.md).

- Recognition is data, not code. Source is per-platform JSON5 under `matchers/rules/` (spec ADR-0001, schema
  `docs/rules.schema.json`, sub-files `docs/rules.fragment.schema.json`); a DoorDash sub-file merge fails the build
  on a duplicate rule id. Rule order within a file is inert.
- `matchFirst` evaluates the non-overrideable partition first, then the overrideable one, each by priority.
  `sensitive.known` is priority 0 and non-overrideable.
- Rules cannot actuate: click and gesture verbs are compile-rejected. Rules expose target bindings that the app-owned
  `RuleAction` registry consumes, and a screen rule that binds one declares it in `enables: [...]`.
- New rule↔state vocabulary goes through the load-validated contract (`ParsedFieldsFactory.REQUIRED_FIELDS_BY_SHAPE`,
  `StateMachineContract.EFFECT_INTENTS` and `REQUIRED_FIELDS_BY_FLOW`), never a magic string in a Kotlin `when`.
- A screen rule using the `sha256` transform must declare a non-empty `redact`.
- **Rule regexes are RE2 syntax** (RE2J, linear time): no lookaround, backreferences, or possessive or atomic groups.
  Every pattern compiles through `RegexSafety.compileRegex`: length ≤ 200, and the compiled program is measured,
  `programSize()` ≤ 1 000. Never estimate a program size from pattern text. On `RegexEvaluationFailed`, recognition
  treats the rule as no-match, parse yields null, and redaction masks the whole node (a `false` default would ship it
  raw).
- `CurrencyShape` is the one definition of a well-formed money figure. `parseGlyphCurrency` and
  `nextSiblingMatchingRegex` read id-less money renders, but neither catches a well-formed mid-spin read; the settle
  gate (§3) does.
- A rule drafted from a census capture passes `CensusDraftRoundTripTest`.

### 3. Multi-Region State Machine (`core/state/`)

Before changing a stepper, a grace, a timer, recovery or the offer lifecycle, read
[`docs/architecture/03-state-machine.md`](docs/architecture/03-state-machine.md).

- Observations reduce into `AppState(regions)`: `FlowRegion` (the screen interpretation, not offers), one
  `PlatformRegion` per platform (session and task lifecycle, plus that platform's offers), and `CrossPlatformRegion`
  (derived aggregates).
- Steppers are pure and driven by `obs.timestamp`, never a wall clock, so recovery replays observations over the last
  snapshot. `EffectMap` turns prev/next plus the step's `StepDecisions` into `AppEffect`s; decisions are never
  persisted. No `Platform` branch anywhere: per-platform values ride `GraceConfig` through `TransitionPolicy`.
- Settle gate: a parsed running total moves `Session.runningEarnings` only after standing unchallenged on its own
  surface for 3 s (`SessionPaySettleGate`). The total is nullable: null renders as the em dash, never `$0.00`, and a
  `$0.00` read never overwrites a positive total.
- Every grace arm carries a `WakeId`; a fire from a replaced pending is inert. There is no re-arm logic.
- Recovery drops evidence and re-arms decisions in flight: `recoveryHygiene` runs once on the final state, a replayed
  region timer is never executed, and a snapshot never gets ahead of its step's effects
  (`EffectExecutor.afterProcessed`).
- Destructive commits (dash summary, delivery receipt, task retire) go through `pendingDestructive` and
  `GRACE_COMMIT`.
- Offers are platform-owned (`PlatformRegion.pendingOffers`) and their identity is ruleset-declared
  (`presentationKey`). A null key replaces on any hash change, so a false merge is impossible.
- An offer that leaves without a committed decline or an accept click waits in the shift-long `offerPool`; new work at
  one store links its sole candidate, a tie links nothing, the rest end `OFFER_NOT_TAKEN`. One outcome per instance
  (`PendingOffer.instanceKey`).
- An accepted offer pre-creates placeholder tasks. Store lineage joins on the customer hash and fails null rather
  than wrong.
- Stateless notification pushes with no effect intent never change the lifecycle (`NotificationNeutralityTest`);
  the screen is the sole pause and deadline writer. An overdue grace may still expire when any observation,
  a push included, arrives.

### 4. Side Effect Engine (`app/.../state/effects/`)

Before changing an effect handler, a tap, an offer surface, the offer voice or the evaluator, read
[`docs/architecture/04-side-effect-engine.md`](docs/architecture/04-side-effect-engine.md).

- `SideEffectEngine` executes `AppEffect`s with `effects_fired` idempotency (recovery-aware) and owns the fail-closed
  action gates: the live `PermissionTierChecker` and the capability consent gate, with no auto-grant.
- `UiInteractionHandler` is the only path that ever clicks a third-party app. It resolves the clickable owner,
  re-verifies at fire time, and aborts on any failed check (`TapAbortPolicy`).
- `AppEffect.LogEvent` is the only writer of `app_events`. The engine must never die silently: the per-item catch is
  `Throwable` and the drain loop is supervised.
- The evaluator fails closed on a missing distance: no `?: 1.0` fallback. Consumers branch on
  `OfferEvaluation.hasDistanceMetrics` and never render placeholders as measurements.
- Every offer surface (card, heads-up, chat line, voice) renders one `OfferStory`: facts, missing inputs and rule
  notes, never scores or verdicts.
- `OdometerFixPolicy` gates every odometer fix; a lat/long is never logged.
- **Write `\}`, never `}`, in a main-source `Regex(…)` literal.** The ICU/JVM divergence once destroyed most of an
  evening's data; `IcuRegexGuardTest` fails on a bare `}`.

### 5. Analytics Read-Model (`core/data/.../analytics/`, `core/database/.../analytics/`)

Before changing a fold, a DAO read, a correction event, the ledger or a Recap or Plan screen, read
[`docs/architecture/05-analytics-read-model.md`](docs/architecture/05-analytics-read-model.md).

- CQRS: `app_events` is the source of truth; `delivery_records`, `session_records`, `offer_records`, `stores` and
  `pickup_records` are a rebuildable projection. `AnalyticsProjector` folds exactly once (records and watermark in
  one transaction); a `PROJECTOR_VERSION` bump wipes and refolds. `sequenceId` is the fold and order key; "when it
  really happened" reads the payload.
- **Economics are frozen per record, never recomputed.** `NetProfit` is the one cost-math owner, and
  `AnalyticsRepository` is DAO-only. `cashTip` stays outside `realizedPay` and `netProfit` and is added only at read
  sites.
- Corrections are append-only events, folded non-destructively; a machine re-price never overwrites a driver-owned
  row. A hand-added delivery is removed by its own event (`MANUAL_DELIVERY_REMOVED`); its `MANUAL_DELIVERY` stays
  in the log.
- Pay comes from a receipt only on proof (`ReceiptCoverage`: one accepted offer whose card shows one drop, and that
  drop alone), else an `OFFER_PAY` estimate. Every receipt read is evidence (`receipt_records`); an unproven one
  places and suppresses nothing.
- `ShiftEarnings` is the one owner of the bubble's headline. Expenses and fuel are a separate ledger that never
  enters earnings or the daily goal.
- Recap's reads are window-anchored (`AnalyticsWindow`, Monday weeks); Plan's heatmap, leaderboard and plan are
  lifetime under a declaring badge. The CSV export excludes customer and address hashes.
- Look-back screens read `RecordsStatus` (`RecordsStatusRepository`); never render $0.00 or an empty period while
  REBUILDING.
- The shift page checks delivery pay only against an independent total (`ReportSource.independent`: the app's
  summary or one the dasher set), never the fallback that equals delivery pay.
- `ShiftChecks` is the one owner of whether an ended shift needs a check. A recorded unassign stamps the offers it
  names; a missed delivery added from an offer stores the offer on the delivery row, never rewriting the offer row.

## Development Principles

Every new feature or refactor holds to these; they are design inputs, not afterthoughts. Code cites them by number.

1. **UDF (Unidirectional Data Flow).** State flows down, events flow up. Steppers/reducers are
   pure (no Android dependencies, no wall clock — `obs.timestamp` only); side effects happen at
   the edge (`EffectMap` diff → `SideEffectEngine`), never inside reducers. UI observes immutable
   state (`StateFlow<AppState>`, per-screen immutable `UiState` data classes) and dispatches
   events/intents — it never writes to repositories directly. The Reactive UI rules below are the
   Compose-facing half of this.
2. **MAD (Modern Android Development).** Kotlin-first, Compose-first, coroutines + Flow for async,
   Hilt for DI, Room / Preferences DataStore for persistence, version catalog for dependencies.
   Layer modules under `:core:*` / `:domain`, feature modules under `:feature:*`, Package-by-Feature inside each.
3. **Clean code / single responsibility.** Small, focused files and classes: don't add to a file past ~900 lines,
   split it first. No god objects; keep repositories domain-scoped. Composables stay small and stateless with
   hoisted state; shared components take pure data + lambdas.
4. **Kotlin/Android best practices.** Idiomatic Kotlin (data/sealed types over stringly-typed
   values), structured concurrency (scoped coroutines; `SharingStarted.WhileSubscribed`
   for shared flows), locale-safe machine string ops (`Locale.ROOT`), immutability by default.
   Translate only into `en` (default), `es` and `fr`, language-level, never a regional variant such as `es-rUS`
   (`LocaleAllowlistGuardTest`). Guard an API call above minSdk 30 with `Build.VERSION.SDK_INT >= …`; never suppress
   `NewApi` (the root `lint.xml` makes it fatal in `lintVitalRelease`).
5. **Single source of truth (SSOT).** Every piece of state, configuration, logic, or copy has
   exactly one owner; everything else *derives* from it (reactively, where it can change). No
   hand-maintained second copies — a private cache of a preference, a re-implemented formatter,
   a duplicated constant, or a parallel UI assembly is a divergence bug waiting to fire. When two surfaces need the
   same thing, extract one definition and point both at it; when a value can be computed from an owned anchor,
   compute it — don't store it twice.
6. **Security & privacy first.** The Pledges above are design inputs; every feature is measured against them before
   it ships. Working rules:
   - **Treat third-party UI and any remote rule source as untrusted input:** bounded ingestion (size, depth, node and
     regex caps, §2), fail-closed validation, and a remote rule source signature-verified before compile
     (`RulesetVerifier`; detail in `docs/architecture/02-rule-engine.md`).
   - **The dasher's sensitive screens are blocked, never parsed or stored**, in both sensor pipelines, and the gate
     fails closed beyond rule coverage: frames drop until rulesets load, every platform shipping screen rules must
     ship sensitive rules (load-time check), and UNKNOWN captures pass the `SensitiveTextMarkers` backstop.
     **Customers are hashed, not blocked.** A recognition change must not be able to downgrade the block side, nor
     start storing raw customer PII on the hash side.
   - **PII is hashed at the edge before it is persisted or could be uploaded** (`sha256`, fail-closed: never echo
     plaintext on failure). Captures are debug-only (release binds `NoOpCaptureBus`). Evidence screenshots need
     `EvidenceConfig`'s master toggle (default off) AND a per-category toggle; an uncategorized capture never fires.
   - **Secrets never reach logs** (the EIA `api_key` is redacted); network logging is debug-gated.
   - **Capability gates fail closed.** An effect whose permission tier is not granted (live OS state) does not fire.
     Taps are app-owned `RuleAction`s verified at fire time (package scope, label allowlist, strict click); any
     failed check aborts to manual. An automation tap also needs a granted capability key, one per (rule, action),
     pinned to the sorted set of every branch's binding definition. **There is no auto-grant:** every capability
     lands undecided, and only an explicit user act grants; a denial is durable. A dasher-pressed Accept/Decline is
     its own consent. Wide event receipt is a separate feature consent, not a capability grant. Before changing
     consent, read `docs/design/rule-capability-consent.md`.
   - When a change touches recognition, capture, network, or effects, state its security/privacy posture in the PR:
     what's trusted, what's gated, what's scrubbed.
7. **Semantic, PII-safe logging.** Levels carry meaning, and the log is two products: an on-device DEBUG firehose
   (`app.log`) and an INFO+ slice the dasher exports as a bug report (`shareable.log`, Settings → Data & Privacy →
   Export Data).
   - **VERBOSE**: per-frame trace (`SCREEN:` lines). Firehose only; never shipped or exported.
   - **DEBUG**: single reducer/effect steps, gate decisions, capture writes. Firehose only.
   - **INFO**: user-meaningful milestones (offer received/accepted/declined, delivery completed, crash recovery,
     periodic `PipelineStats`). **PII-safe by construction:** economics, counters, state names and `sha256` hashes
     only, **no raw store, customer or address text** (the dasher's own first + last-initial is fine). Raw
     third-party UI text lives at DEBUG/VERBOSE only.
   - **WARN**: a defended invariant fired (a suppressed ghost, a fail-closed denial, a grace waking a commit). If it
     is benign and frequent, it is VERBOSE.
   - **ERROR**: lost data or a crashed subsystem. Always shipped and exported.

   Every component logs under its own stable tag (`Timber.tag("Pipeline")`), never the catch-all `App`;
   `TimberTagGuardTest` ratchets its allowlist (`app/src/test/resources/timber-tag-guard-allowlist.txt`) down only.
   Every INFO+ line passes a fail-closed `LogScrubber` at the sink (`LogRepository`), bound to `SensitiveTextMarkers`,
   which redacts to `[scrubbed:<marker>]`; do not trust call-site discipline. When a change adds or moves a log site,
   state its level and, for INFO+, confirm it carries no raw PII.
8. **Platform-agnostic core.** The recognition→state→effects spine never encodes a specific gig
   platform. Recognition is data (per-platform rulesets), not code; platform identity resolves
   only through the `Platform` registry (`fromRuleId`/`fromPackage`/`fromWire`) — never a literal
   `== Platform.DoorDash`, hardcoded package name, or wire string gating logic. Anything that
   varies by platform — vocabulary, required parse fields, effect-bearing intents, grace timing,
   offer slots, lifecycle-edge anchors, learned rate models — is either ruleset data validated at
   load or state keyed by `Platform`, never a global tuned to whichever platform we field-test
   most. New vocabulary goes through the load-validated contract (§2), so an OTA ruleset cannot smuggle an effect.
   The acceptance test: adding a platform means shipping a ruleset + corpus with **zero** `:core:state` /
   `:core:pipeline` / `:domain` edits. DoorDash-heavy field testing masks violations, so the design-goal review
   enforces this.

If a change genuinely can't satisfy one of these, say so explicitly in the PR description instead
of silently violating it.

## Reactive UI Principles

We picked Jetpack Compose for a reason — **leverage it**. Especially in the bubble HUD, which is a
glance surface where stale data is worse than no data because it gives the dasher false confidence.
Every UI element that depends on time, state, or external data should re-render automatically when
that input changes. No manual refresh, no waiting for the next state-machine transition to update
something the user is looking at right now.

1. **State drives UI continuously, not just on transitions.** Anything time-derived — timers,
   countdowns, deadlines, relative timestamps — must tick. A composable that renders
   `formatDuration(now - arrivedAt)` once at state-change time is broken; it freezes the moment the
   reducer last fired.
2. **State classes hold the anchor, the UI derives the value.** Store `arrivedAt: Long?`, not
   `secondsAtStore: Int`; store `pickupDeadlineMs: Long?`, not `"7 min left"`. The composable reads the anchor + a
   ticker and computes the display string.
3. **The ticker is `rememberNow()`** (`:core:designsystem` `time.TimeKit`, a `produceState` loop): `val now by
   rememberNow()` and derive from it. Don't roll your own `LaunchedEffect` + `mutableStateOf` per timer.
4. **Don't conflate "state changed" with "UI updated".** Time-sensitive views are driven by the ticker, not by
   waiting for a `StateManagerV2` emission.
5. **Bubble HUD has the strictest reactivity bar.** If a value can change while the dasher is
   looking at it, it must re-render without a state transition. Treat any frozen-looking value as
   a defect, not a styling choice.
6. **One clock, and tests own it.** A composable never reads `System.currentTimeMillis()`; it reads time
   from `rememberNow()` (ticking values) or `LocalNowProvider.current` (a one-off read), a `java.time.Clock` that
   defaults to the system clock. Compose tests provide a clock they control (how: `docs/architecture/06-ui-modules.md`
   § Compose tests). A one-time effect persists its mark at start, never at its end.

When in doubt: would the dasher believe a stale value? If yes, it's reactive-broken.

## Snapshot and replay tests

Before adding snapshot fixtures, changing a rule's parse output, or writing a replay test, read
[`app/src/test/README.md`](app/src/test/README.md). Four rules hold every time:

- **A pushed fixture is public.** A branch that adds snapshot fixtures runs the intake and corpus privacy tests
  locally before its first push. Commit sorted files only, never from `INBOX/`.
- **`InboxProcessorTest` and `UnknownScreenAnalysisTest` mutate the corpus.** A plain sweep excludes them; run them
  by name only.
- A change that alters parse output regenerates `snapshots/approved-parse-output.json` and the census golden
  deliberately; reviewing their diff is the regression gate.
- In a session-replay test the captured `app_events` encode the bug: assert hand-written correct behaviour, **never
  `replay == db`**.

## Agent skills

### Issue tracker

GitHub issues in `sjtrotter/gig.log`; every issue is labelled and added to the Roadmap project. Before filing or
editing an issue, read `docs/agents/issue-tracker.md` (labels, board, blocking).

### Domain docs

Single-context: a root `GLOSSARY.md` (created when the first term is settled) and `docs/adr/`. See `docs/agents/domain.md`.

### Copy style

User-facing copy follows `docs/design/copy-style.md` (label not sentence beside a number, one disclosure per screen,
plain repeated nouns, no adverbs or marketing). Screen design follows `DESIGN.md`: the bubble's glance rules versus the
main app's, how the app uses Material 3 and where it departs, the colour-role map, and the settled and open design
decisions.

### Delivery chain

The user skills under `~/.claude/skills` run the PR loop in this repo, in this order. Before spawning a subagent, or
when CI will not start, a PR needs its artifacts, or you work from a remote session, read `docs/agents/workflow.md`
(model policy, CI logs and artifacts, escape hatches).

1. **Plan:** `to-spec` and `to-tickets` (the developer starts those two). Design questions go to the council; ask the
   developer only the low-confidence or split ones. The builder spec follows `codex-harness` §3.
2. **Build and prove:** an Opus subagent builds; the session pushes the branch behind a draft PR, and PR CI
   (`pr-check.yml`, `build-and-test`) is the gate. Read a red run with `gh run view <id> --log-failed` and its
   `gate-summary.txt`. Bugs go through `diagnosing-bugs` and `tdd`.
3. **Finish the PR's contents:** the CLAUDE.md, `docs/architecture/` and field-testing checklist updates go into the
   branch before a round's push (steps 2 and 4 repeat per round, and step 3's edits ride the next push), and the
   author writes the design-goal review (the PR template's block). `deslop` the diff.
4. **Review:** `sol-loop` on the full diff, side by side with PR CI. Each round is one push of committed work; that
   head's CI run and Sol round start together on the same SHA, and any push that changes the diff starts a new round.
   Stop at the first round whose head is both CI-green and Sol-clean; at the three-round cap with no such head, the
   developer decides. `blast-radius` when the change touches a shared path. Before a review round, read
   `.claude/council.md` (seats, attack axes, round rules). A draft's run is `draft-check` (no release lint, no
   screenshots); `gh pr ready` before the round expected to be the last, so its head gets the full `build-and-test`.
5. **File:** `file-pr`, which fills `.github/pull_request_template.md` with both review blocks into the PR
   (`gh pr edit`).
6. **Watch and merge:** `babysit-pr` (`pr-wait --until green`). Once `build-and-test` passes on the head, merge with
   `~/dashbuddy/tools/pr-loop/post-merge.sh <issue|-> <pr>`: it runs `gh pr merge --merge`, removes the worktree,
   deletes the branch locally and on the remote, pulls master, sets the board to Done and drops `in-progress`.
   Merging needs no "full send" here: the developer is the only author. Then update the memory notes. `full-stop`
   and `full-send` do not know steps 3 and 6; they apply here all the same.

## Git Workflow

- **Before creating a branch**, set the issue(s) to In Progress on the project board and add the `in-progress`
  label (`~/dashbuddy/tools/pr-loop/start-issue.sh` does both; ids in `CLAUDE.local.md`).
- **Always create a branch**; never commit feature or fix work directly to `master`. Never modify git config.
- Never hardcode workstation paths, ids or command paths in this file; they live in the gitignored `CLAUDE.local.md`.
- Branch naming: `<type>/<issue-number(s)>-<short-description>` with every issue the branch covers, e.g.
  `feature/110-bubble-offer-mode` or `feature/79-80-offer-bubble-net-pay`. Types: `feature/`, `fix/`, `refactor/`, `chore/`, `test/`, `ci/`, `docs/`.
- **Merge commits only, never `--squash`**, and always prune the branch: `post-merge.sh` (delivery chain step 6)
  does both. A remote session (web or mobile) cannot delete branches: see `docs/agents/workflow.md`.
- **Builders never open `~/dashbuddy/logs` or raw captures. No personal data goes into an issue, a PR or a commit.**

**Every PR ships with context updates.** As part of preparing or merging any PR:

1. **Memories:** update the persistent memory (see *Claude Memory Upkeep*) with what the PR changed and what it means
   for project status. Every merged PR changes status, so this always applies.
2. **CLAUDE.md holds rules and pointers only.** Edit a line only when the PR makes it wrong, and prefer deleting to
   adding. Never add an issue number, a date or a "since #N" history here. A new invariant goes into its reference
   file; it earns a line here only if breaking it does silent damage that no test catches. CI fails a PR when this
   file passes 40,000 bytes (`claude-md-budget.yml`); raising the budget is a reviewed one-line change.
3. **`docs/architecture/`**: a PR that changes an invariant, adds a rule, or retires one records it, with its
   history, in the layer's reference file.

**Every PR gets a design-goal review.** Before the adversarial review (it can be written while PR CI runs), review the
full diff against the Development Principles and record it in the PR description under `### Design-goal review`:

1. For each principle 1–8 the diff touches (plus the Reactive UI rules for UI diffs), one line: how it conforms, or
   the explicit, justified violation (never silent).
2. Principle 8 applies to every PR touching `:core:state`, `:core:pipeline`, `:domain` or rule JSON: no new platform
   literals in logic, new vocabulary through the load-validated contract, per-platform values keyed by `Platform`.
3. Fix in-PR what can be fixed in-PR. A violation that cannot be fixed there is filed as an issue and linked from the
   review block; a PR never merges with an unrecorded violation.
4. When the PR changes CLAUDE.md, one line: "CLAUDE.md delta: +N/-M bytes, why it must be inline".

**Then the adversarial review:** `sol-loop`, run as delivery chain step 4. Its axes, reviewer strength and round
rules live in `.claude/council.md`.

**Docs-only PRs can skip CI:** put the literal `[skip ci]` in the PR **body** when the diff has no code. The check is a
bare `contains()`, so a code PR whose body merely describes the token skips its own CI: paraphrase it, or add
`[force ci]` to force the run.

## Session Orientation

At the start of a session or before picking up new work:

1. Check open issues and what blocks what; resolve a blocker before the issues it blocks.
2. Pick next steps from the milestone and phase ordering, then check those candidates for unresolved blockers.
3. Read the current-state memory (the "RESUME HERE" line at the top of `MEMORY.md`) and update it if the state has
   drifted.
4. Check the last `Property Exploration (nightly)` run (`gh run list --workflow prop-explore.yml --limit 1`); a red
   run names a property and a seed, and it stays red until someone reads it.

## Claude Memory Upkeep

The persistent memory directory (path in `CLAUDE.local.md` § Local workstation) is shared state for every agent and
session on this repo. **Keeping memories current is part of finishing any piece of work.** Whenever your work changes
project status (merging a PR, closing, re-scoping or filing issues, completing a phase, locking a design decision,
running an audit, or finding that a memory is no longer true), update the memory files in the same session and keep
the `MEMORY.md` index in sync, one pointer line per memory.

- **A stale memory is worse than no memory.** Correct or delete wrong memories on sight.
- The current-state memory is replaced, not accumulated, when a newer full audit supersedes it (delete the old file,
  update the `MEMORY.md` pointer).
- Memories hold status, decisions and context the repo does not record; don't duplicate code, git history, issues,
  ADRs or this file.

## Claude Subagent Model Policy

Opus subagents build (`model: opus`, set explicitly), `sol-loop` reviews, and the session adjudicates every finding
that gates a merge. Codex Astra is the fallback builder when the budget is tight. Hand any builder only a bounded,
already-vetted plan. Before spawning a subagent, read `docs/agents/workflow.md` § Model policy.

## Field Testing Logs

- **About to dash** ("starting a field test", "about to dash", "heading out", "what should I look for"): read §1 of
  `docs/field-testing/CHECKLIST.md` and the item files it links (`grep -l '^status: must-watch'
  docs/field-testing/checklist/open/*`), and report back concisely what to watch for and how to tell each item is
  working. They are about to drive: keep it short and glanceable.
- **A narrated dash** ("field testing log", "dashing log", "field log", or a run of bugs and UX notes from driving):
  **record, do not fix.** Write a session file with every cause phrased as a hypothesis, never a concluded answer or
  a fix, and push no code. Before writing it, read `docs/field-testing/README.md` § Writing a session log.
- **A PR or issue whose change needs field validation** (on-dash behaviour, bubble/HUD, recognition rules or parsers,
  anything verified only against captured data) also adds a checklist item at `Confirmed: 0/2`. Before adding,
  confirming or closing an item, read `docs/field-testing/CHECKLIST.md` §0. Closures are the developer's; never
  record a confirmation you did not witness.
