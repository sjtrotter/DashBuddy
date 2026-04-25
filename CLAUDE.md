# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this
repository.

## Build Commands

```bash
# Build the app
./gradlew :app:build

# Run all unit tests (across all modules)
./gradlew testDebugUnitTest

# Run the full regression suite (before any PR)
./gradlew testDebugUnitTest --tests "*AllMatchersSuite*"

# Run a specific regression test
./gradlew testDebugUnitTest --tests "*DashPausedRegressionTest*"

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
:app → :domain, :core:data, :core:database, :core:datastore, :core:location, :core:network
:core:data → :domain, :core:database, :core:datastore, :core:location, :core:network
:core:database → :domain
:core:network → :domain
:core:location → :domain
:core:datastore → :domain
```

- **`:domain`** — Pure Kotlin library. Domain models, evaluation logic, repository interfaces. No
  Android dependencies.
- **`:core:database`** — Room entities, DAOs, and database setup.
- **`:core:data`** — Repository implementations, mappers, data sources. Bridges domain interfaces to
  concrete data layers.
- **`:core:network`** — Retrofit clients, OkHttp interceptors, EIA gas price API integration.
- **`:core:location`** — Play Services GPS tracking.
- **`:core:datastore`** — Proto DataStore for app preferences.
- **`:app`** — The main module. Contains the accessibility pipeline, screen matchers, state machine,
  side effects, UI (Compose + overlays), and Hilt DI wiring.

## Architecture: Recognition Pipeline + State Machine

The core architectural challenge is understanding a third-party app's UI without an API. The
solution is a multi-stage pipeline:

### 1. Accessibility Pipeline (`app/.../pipeline/accessibility/`)

`AccessibilityListener` captures raw Android `AccessibilityEvent`s. These are normalized into an
immutable `UiNode` tree. Two sub-pipelines process events:

- **`WindowPipeline`** — Detects which screen is displayed by running the `UiNode` tree through a
  chain of `ScreenMatcher` implementations.
- **`ViewPipeline`** — Classifies tap/click events.

### 2. Screen Matchers (`app/.../matchers/`)

18+ `ScreenMatcher` implementations, each responsible for recognizing one screen type (e.g.,
`IdleMapMatcher`, `OfferMatcher`, `DashPausedMatcher`). They use a **weighted priority system** to
resolve conflicts. `SensitiveScreenMatcher` runs first and blocks any further processing of
banking/personal information screens.

### 3. State Machine (`app/.../state/StateManagerV2.kt`)

A central reducer merges three event streams (pipeline events, engine events, UI clicks) and routes
them to state-specific handlers. The output is a `StateFlow<AppStateV2>`. There are 9 state classes:
`Initializing`, `IdleOffline`, `AwaitingOffer`, `OfferPresented`, `OnPickup`, `OnDelivery`,
`PostDelivery`, `PostDash`, `DashPaused`, `PausedOrInterrupted`.

### 4. Side Effect Engine (`app/.../state/effects/`)

State transitions trigger handlers: `DefaultEffectHandler` (DB writes), `OdometerEffectHandler` (
mileage), `NotificationHandler`, `TimeoutHandler`, `TipEffectHandler`, `UiInteractionHandler`.

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
3. For **unknown screens**: read the X-Ray report, write a new `ScreenMatcher`, register it in
   `TestMatcherFactory.kt`, re-run.
4. For **sensitive screens**: manually redact the JSON, move to `snapshots/SENSITIVE/`, verify with
   `SensitiveScreenRegressionTest`.
5. Commit only the sorted files from their category folders (never from `INBOX/`).

**Adding a new `ScreenMatcher`:**

1. Create the class in `app/src/main/java/.../matchers/`.
2. Register it in `TestMatcherFactory.kt` (mirrors the Hilt DI graph for tests).
3. Register it in the Hilt module that provides the matcher set to the live pipeline.
4. Run `InboxProcessorTest` and then `AllMatchersSuite` to verify.

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

**Before creating a branch**, mark the issue(s) as **In Progress** in the GitHub project board.
See `CLAUDE.local.md` for the command to update the project Status field.

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
GH="/c/Program Files/GitHub CLI/gh.exe"
"$GH" pr merge <NUMBER> --merge
```

## Session Orientation

At the start of a session or before picking up new work:

1. **Check open issues and their blocking relationships** — don't just look at what's open, look at
   what's blocking what.
2. **Prioritize blockers first** — if issue A is blocking issues B, C, and D, resolve A before
   starting B, C, or D.
3. **Determine logical next steps** from the milestone/phase ordering, then check those candidates
   for unresolved blockers before committing to a direction.
4. **Update `memory/project_state.md`** if the current state has drifted from what's recorded.

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