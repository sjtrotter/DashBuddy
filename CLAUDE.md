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
on-device. Sensitive screens (banking, identity, payment) are blocked at the matcher layer.
Network access is opt-in per feature. PII scrubbing runs at the edge before any upload.

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

# Run all unit tests (across all modules)
./gradlew testDebugUnitTest

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
  pipeline/provider contracts, and the capture contracts (`CaptureBus`, `EnvelopeBuilder`,
  capture schemas/DTOs) plus the `PlatformPreferences` read interface (#355). No Android
  dependencies. (Repository *implementations* and Hilt bindings live in `:core:data`.)
- **`:core:pipeline`** — Accessibility pipeline, notification pipeline, JSON rule engine
  (RuleCompiler, Ruleset, JsonRuleInterpreter), observation classifier. Reads third-party UI.
- **`:core:state`** — Multi-region state machine (StateMachine, FlowRegionStepper,
  PlatformRegionStepper, CrossPlatformRegionStepper), effect map, healing policy, crash recovery
  (StateManagerV2). Defines `EffectExecutor` and `MetadataProvider` interfaces.
- **`:core:database`** — Room entities, DAOs, and database setup.
- **`:core:data`** — Repository implementations, mappers, data sources. Bridges domain interfaces to
  concrete data layers.
- **`:core:network`** — Retrofit clients, OkHttp interceptors, EIA gas price API integration.
- **`:core:location`** — Play Services GPS tracking.
- **`:core:datastore`** — Preferences DataStore (six single-concern stores behind Hilt
  qualifiers — app prefs, strategy, dev settings, odometer, app state, platforms).
- **`:core:designsystem`** — Brand system (no project deps): fixed dark/light palette (`DashColors`),
  Hanken Grotesk + Space Grotesk fonts (tabular numerals), `DashTheme` + `LocalGlance`, and the
  shared component library (`DashCard`, `DashChip`, `DashStatTile`, `DashGaugeRing`, `DashSegmented`,
  `DashSlider`, `DashBarChart`, `DashAccordion`). No M3 dynamic color. Feature-specific composables
  stay with their feature (Package by Feature); only generic, data-in/lambdas-out components live here.
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
`NotificationMapper`) emit `PipelineEvent`s. `AccessibilityPipeline.output()` has three drop gates:
**sensitive** screens, **noise**, and **UNKNOWN** (captured to disk for triage, never forwarded to
the state machine). Snapshots are attributed to the window's *real* package (not the event's), so
our own overlay is dropped (#4 / PR #334). Frame admission is `FrameGate` (identity dedup +
content-hash rolling suppression of UNKNOWN frames, #360); envelope assembly is the shared
`CaptureWriter` (#361); `PipelineV2.events` is a HOT `shareIn` stream — one upstream pass feeds
all collectors, so side effects (captures, dedup state) can never double-run (#361).

### 2. JSON Rule Engine (`core/pipeline/.../rules/` + `assets/rules/`)

Recognition is **data, not code**. Per-platform rule files
(`core/pipeline/src/main/assets/rules/doordash.json`, `uber.json` — spec in ADR-0001, editor schema
`docs/rules.schema.json`) are compiled by `RuleCompiler` and matched by `ObservationClassifier`.
Rules carry a `priority` (sensitive rules are priority 0 and `overrideable: false`, blocking all
further processing of banking/identity screens), `require` predicates, `bind` blocks, and `parse`
blocks that produce typed fields via `ParsedFieldsFactory`. There are no Kotlin matcher classes —
changing recognition means editing rule JSON plus corpus tests.

### 3. Multi-Region State Machine (`core/state/`)

Observations reduce into `AppState(regions)` (`:domain`): **`FlowRegion`** (R0 — ground-truth
screen interpretation; holds the pending offer), one **`PlatformRegion`** per platform
(session/task lifecycle; unified grace via `pendingDestructive`), and **`CrossPlatformRegion`**
(derived aggregates). The steppers (`FlowRegionStepper`, `PlatformRegionStepper`,
`CrossPlatformRegionStepper`) are pure and driven by `obs.timestamp` — never a wall clock — so
crash recovery can replay observations over the last snapshot. `StateManagerV2` hosts the
reduction, exposes `StateFlow<AppState>`, and owns crash recovery; `EffectMap` diffs prev/next
state into `AppEffect`s.

### 4. Side Effect Engine (`app/.../state/effects/`)

`SideEffectEngine` executes `AppEffect`s with `effects_fired` idempotency dedup (recovery-aware)
and runs the evaluation loopback (offer eval → `OfferEvaluationEvent` back into the machine).
Handlers: `OdometerEffectHandler`, `ScreenShotHandler`, `TipEffectHandler`, `TtsEffectHandler`,
`UiInteractionHandler` (cross-window accessibility clicks), `OfferActionReceiver` (notification
Accept/Decline actions).

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

If a change genuinely can't satisfy one of these, say so explicitly in the PR description instead
of silently violating it.

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

**Adding or changing a recognition rule** (there are no matcher classes to register — rules are data):

1. Edit the platform's rule JSON (`$schema` gives editor autocomplete/validation against
   `docs/rules.schema.json`). Golden-corpus folder name == expected intent.
2. Tests compile the **production** rule files via `TestRulesetFactory` — nothing else to wire up.
3. Run `InboxProcessorTest` (sorting) and then `AllMatchersSuite` (golden guard + ruleset +
   classifier regressions) to verify.

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
per-commit history and makes it harder to bisect or attribute changes.

```bash
gh pr merge <NUMBER> --merge
```

(The `gh` binary path and all project/field IDs are workstation-specific — they live in
`CLAUDE.local.md`, which is gitignored. Never hardcode workstation paths in this file.)

**Every PR ships with context updates — no exceptions.** As part of preparing/merging ANY PR:

1. **Memories** — update the persistent memory (see *Claude Memory Upkeep*) to record what the
   PR changed and what it means for project status. Every merged PR changes status by
   definition, so this step always applies.
2. **CLAUDE.md** — re-check the sections the change touches (architecture, modules, workflows,
   commands, labels, principles); if the PR makes any statement in this file stale, fix it
   **in the same PR**.

A PR that skips these is incomplete — future agents inherit their entire context from these
two places.

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

When a Claude agent fans out subagents (Agent tool / workflows), every subagent must run on a
model **at most one tier below** the spawning agent's own model — and **prefer the exact same
model** unless there is a specific, stated reason to downgrade (e.g. a trivially mechanical
search). Never let subagents silently fall to a lower default tier: set the model explicitly at
spawn time.

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