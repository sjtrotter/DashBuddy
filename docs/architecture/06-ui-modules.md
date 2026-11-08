> **Detailed architecture reference** (created by CLAUDE.md trim v4, 2026-11-06, from the module and UI
> narrative that had no other home). This file holds the full rules for the module layout and the UI features,
> with the issue or PR behind each and the reasoning. `CLAUDE.md` keeps only the invariants that break silently,
> plus a pointer here. When a PR changes something described below, update this file in the same PR; change a
> `CLAUDE.md` line only when the PR makes it wrong. A `§N` reference below means the sibling numbered file here.

# Modules and UI features

The dependency graph itself is in `CLAUDE.md` § Module Structure (it is short and every session needs it).
Min SDK 30 is guarded where it matters: `bubblePreference` (API 31) falls back to `areBubblesAllowed` below 31.

## Feature modules (`:feature:*`)

**Feature modules.** UI extracted per-feature under
`:feature:*` (`cloud.trotter.log.gig.feature.<name>` namespace), depending only DOWN the graph
(`:app → :feature:* → :domain / :core:*`); a feature module **never** depends on `:app` or on
another feature. `:app` legitimately depends on each feature for its moved `@HiltViewModel`s and
any screens/copy the app shell still hosts. Each module declares only the deps it uses (the
honest-deps doctrine — `:feature:setup` carries no `:core:designsystem`, `:feature:dashboard` no
Hilt/`:core:data`). Residency rule: the HOST and the injections decide ownership, not the source
path — a ViewModel that injects `:app`/`:core:state` machinery (`DashboardViewModel`,
`BubbleViewModel`) stays in `:app` rather than forcing an inversion (the honest-smaller-module
doctrine), and a multi-consumer string/color is duplicated by choice into a module with the `:app`
copy authoritative, documented at both sites (`common_content_desc_back` #99, `@color/white` #854).

Shipped: `:feature:settings` (#99); `:feature:setup` (#98 — wizard VM/model/step cards; #1359 slice A made `wizard/vehicle/VehicleDraft` +
`VehicleDraftState` the vehicle form's one owner — the EPA lookup cascade, type/class/powertrain and EV-trim changes, and the
five vehicle-shaped user-set fields — rendered by `VehicleCard(step, draft, actions, …)`, hosted by the wizard and by the
`:app` Garage editor (`ui/main/settings/garage/`, #1359 slice B); the Garage list is an Android two-part row
(radio + name selects; divider + gear opens the details, where Retire lives) and honours the mid-dash swap lock (#1379);
a saved vehicle's details page is the one live editor of its costs and name, addressed by id, with fixed identity
in the heading and no Save button; Reset to estimates resets its costs, keeping name, identity and fuel economy;
unfinished fields flush before leaving or retiring, and a failed write stops the transition (#1385);
a snackbar offers Undo for the latest saved field, restoring its previous value or estimate; type and
identity change only by adding a new vehicle; the shared number inputs save when an edited field is finished; retired vehicles sit in a Retired section and can be restored (#1383);
the cost drafts stay in `WizardState` and land in the saved profile; the
`WizardScreen` shell + `EconomyCostsCard` stay in `:app` with the shared `ui/components/economy/*`
editor family, and the Dashboard-hosted `setup/consent/` #843 + `setup/permissions/` sheets stay in
`:app`);

`:feature:dashboard` (#97, reshaped by #977/#981/#1024 — Home's presentational composables
over the shared `DashboardRow` vocabulary, pure data-in/lambdas-out over `:domain` +
`:core:designsystem`; Home's review rows are the hub's own `reviewTexts`/`ReviewList` reused from
`:app` as a composable slot, so flag thresholds/copy keep ONE owner, and `DashboardScreen`/
`DashboardViewModel`/`DashboardUiState` stay in `:app`);

and `:feature:bubble` (#96/#867 — the HUD's
presentational content: the #1333 `hud/*` package (S2a: `HudScreen` with its live / offer / log / resting modes and
three composable slots, the fixed chrome — phase strip, head, top row (Online, Miles), the earnings hero (#1443), the static goal pill, dock,
dash ribbon — and the resting + first-run cards, each a pure data-in/lambdas-out composable over a pure mapper:
`HudStateMapper`, `HudModeResolver`, `TopRowMapper`, `GoalPillMapper`, `DockMapper`, `RibbonMapper`/`RibbonLayout`;

S2b: the state card family (`StateCardMapper` picks waiting / pickup navigating, arrived, shopping / dropoff navigating, at the door / receipt from the active card first, the flow second; `TrackMapper` per design §11 with the late rule; `StateHeadline`, `WaitingAnchor`, `ShopPace`); `PostTask.totalPay` is nullable (an unparsed receipt total is the em dash, never `$0.00`);

S2c: the offer card (`OfferCardMapper` picks the standard card, the COMBINED add-on card when the job was minted from a known offer (a display-only `OfferEvaluator` run on the summed TOTAL quote, never persisted), or the raw-delta fallback; `PayBarMapper` with the two fixed in-bar pills and car = gross minus net in cents; `GaugeMapper` against the strategy's targets; `TripLineMapper` shape-only, capped at nine nodes; the card and notification render domain `OfferStory` facts without a score ring); the add-on drop-count rule has one owner, `domain.model.offer.AddOnStops`, which `:core:state`'s `JobAcceptFlow.appendAddOn` calls;

S2d: the log (`LogMapper` over the pure `JobGrouper` (one card per job, an accepted offer joins its job by `jobOfferHashes` first), `CashTipFold` (amount from the delivery PROJECTION, time from the latest cash adjustment, a later tip moves the card to the tip time) and `LogChatFilter` (Dispatcher/System lines only, outcome/acknowledgement literals dropped, and a fail-closed guard over `PiiShapes`: a kept line never carries a name shape, a customer lead-in, a mask or a hash); expenses and fuel read the ledger projection, never a per-session event fold, so voids apply; `LogView` is a bottom-anchored reverse `LazyColumn` with a top fade, the compact hero + goal pill above it and the dock's Log block shown as Now while open; `BubbleViewModel.logData` follows `presentation.cardSources`, subscribed only by the log slot; `ChatViews.kt`/`BubbleHelpers.kt` deleted);

S2e: the one-time goal-met sequence (pure `GoalMetSequence` phases, `GoalCelebrationResolver` over a persisted `app_state` mark `goal_met_mark` keyed by day, metric (`earned`, #1443) and goal cents, written at start; reduced motion via `ValueAnimator.areAnimatorsEnabled()`), `HudSheetHost` (the fixed hero-to-dock frame S3's sheets rise into) and the undo band, and `FontScaleTier` (labels beside at 1.3, the job column drops at 1.5, the compact hero at 1.8, of fontScale times glance);

S2f: the odometer sheet (the first content of `HudSheetHost`: pure `OdometerSheet` over the shared `hud/keypad/` (3×4 grid, an optional point key, backspace; S3's money sheets reuse it), the shared vehicle chip and Add vehicle (Add vehicle and the empty-garage Odometer block open the Garage's Add-vehicle form in the app and close the bubble), `OdometerReadingCheck` copy that warns and never blocks, the 10 s saved notice in the undo band; the cue to log a reading is the dock's Odometer block lit in accent for the first ten minutes of a dash and after a dash until a reading, plus one "Log your odometer" line after the shift-start line in the session-start notification, one rule (`OdometerCue`) behind the `odometer_prompts` pref; the dock block, cue, start line and resting comparison follow the active vehicle's odometer capability: built in for motor vehicles, optional and off by default for e-bikes and bicycles, with a per-vehicle Odometer switch on its page (#1361)); vehicles live in the `vehicles` DataStore (`GarageRepository`, one active), readings are `ODOMETER_READING` events (`OdometerReadingRepository`, `sessionId` always null) and `MileageReconciliation` shows car / tracked / other miles on the resting footer; #1355 made the garage the dasher's vehicle list: a `Vehicle` carries a typed `VehicleProfile` (identity, consumption and applicable fuel, wear and fixed-cost figures) and a retired mark, the economy's vehicle-shaped values are owned by the ACTIVE vehicle; #1379 replaces the resting row and #1360 head label with the header vehicle chip (type icon + capped name, also in the reading sheet), a swap menu with "Add vehicle…", the mid-dash lock toast after any live dash passes ten minutes, and one `openInApp` route that collapses the bubble when Add vehicle or Settings opens the app (`collapseBubble()` sends the bubble's task to the back, as Accept/Decline do; finishing the activity would make Android remove the bubble).

S3a: the cash tip sheet (`hud/cashtip/`, panels 11c/11e: the dash's latest delivery preselected, a `+`/`−` toggle and `$1 $5 $10 $20 Other` over the shared keypad, one button stating the commit; Other replaces the entered amount; a tip is the existing `DELIVERY_ADJUSTMENT` with `newCashTip = previous + entered`, written by `:app`'s `CashTipActions` and hosted by `CashTipHud` on the `OdometerHud` shape) and the undo strip (`hud/undo/UndoStrip`, the first content of S2e's undo band: what and where, Undo right, a 10 s drain on the frame clock; Undo is a second adjustment restoring the previous value; the delivery card's foot shows the tip as pending until its row reflects it); #1443: the hero is the displayed shift's earnings (`ShiftEarnings`), unlabelled, with its cash tips apart as `+$X cash`; expenses are a separate ledger that never enters the hero or the goal);

S3b: `hud/expense/` supplies the compact six-tile subcard and keypad sheet, with required gallons for Gas (`FUEL_FILLUP`), `CAR_CARE` and recent labels derived from existing expense notes; `ExpenseHud`/`ExpenseActions` write through `LedgerRepository`, share S3a’s extracted `MoneyEntry` and undo strip, and use one three-sheet exclusive route and one newest-of-three undo-band decision; an expense is logged any time, and one with no owning dash is unassigned and still shows in the HUD log beside the dash of that day; an entry with no dash on a day with no dash is in the export);

S3c: `hud/goal/GoalSheet` writes the sticky daily goal preference (`daily_goal_dollars` since #1443: app earnings across every app, no cash or costs) through `GoalHud`/`GoalActions`, with whole-dollar $5 steps, presets and No goal; both hero pills enter the same fixed frame, and all four entry sheets are exclusive),

`formatters/*`,
`cards/*`, and `session/*` (the pure `BubbleSessionResolver` — follow-active + manual switcher / pinned / merged —
plus the stateless `PlatformSwitcherRow`); no Hilt/`:core:data`/`:core:state`, while `BubbleActivity`,
`BubbleScreen` (hosts `HudScreen` and maps the ViewModel's flows into `HudUiState`; a sheet host such as S2f's `OdometerHud`, a remembered `@Stable` object with composable slot members, collects its own sheet-local state, the shape S3's sheets copy; since #1425 all four sheets register with one `BubbleSheetHost`, which owns exclusive open, Back delegation, the newest-undo band and completion routing: a save closes a sheet only while it is still open with the same `entryId`), `BubbleManager` and
`BubbleViewModel` (`displayedShift`, `dailyGoal`, `todayEarned`) stay in `:app`, which consumes the moved renderers
as a legal `:app → :feature` dependency).

Recap (`ui/main/analytics/*`: the week in `AnalyticsScreen` with its sections under `recap/`, the day page under
`day/`, the shift page) is a separate sibling surface, left for a future extraction.

**Navigation (#1447):** a Material 3 bottom bar with three icon-only tabs, Home,
Recap and Plan (`TopLevel`, enum names `ANALYTICS` and `PLAYBOOK`; each name is the icon's content description and
its long-press tooltip), over one flat graph (`mainGraph`, every `Screen.graphRoutes` entry); the tab
navigator (`TopLevel`, `RouteTarget`/`routeTarget`, `MainNavigator`'s `NavController` extensions, `MainNavActions`,
one method per intent) lives in `:app` `ui/main/navigation/`, the shell (`MainScaffold`, `MainNavHost`) in `ui/main/`;
route changes animate through `ScreenTransitions` (fade through between tabs, shared axis inside a tab, none under
reduced motion, #1524).
Tab switches, parking (`popBackStack(root, inclusive, saveState)`) and fresh clears start only at a tab root and
fail closed (WARN) elsewhere, so a parked tab is one lone root and no editor or draft is ever popped; a deep link off
a root only pushes; Dashboard is never a saved-state key; never call `clearBackStack(String)` (in 2.9.7 it drops
Dashboard's empty mapping and a later save then stacks another tab over Home). Recap's Earned / Est. Profit switch lives in its
`SavedStateHandle`; its week is the persisted `AnalyticsWindowSelection`. The day page (`Screen.Day`,
`analytics/day/{date}`) and the shift page (`analytics/session/{sessionId}?row={row}`) are argument routes in
`graphRoutes` only, pushed over Recap, never deep-link targets.

## Module catalogue

- **`:domain`** — Pure Kotlin library. Domain models, state regions, evaluation logic,
  the #1194 `region` package (offline county-subdivision internal points (~36k), county→CBSA join, `RegionCell`, `PrimaryCellPolicy`;
  the cell id is the only location-shaped value that may ever leave the device),
  pipeline/provider contracts, the capture contracts (`CaptureBus`, `EnvelopeBuilder`,
  capture schemas/DTOs), the `PlatformPreferences` read interface (#355), and the
  number/money/duration formatting SSOT (`format.Formats` money/decimal/**percent** — #942 pulled
  the `(x * 100).roundToInt()` shape down off four UI call sites — + `format.TimeFormats`
  `formatDuration`/`formatCountdown` — the locale policy, #358/#456/#467; lives here so both the
  UI and the state layer route through one definition; the Compose time helpers
  `rememberNow`/`rememberTimeFormatter` stay in `:core:designsystem`). No Android
  dependencies. (Repository *implementations* and Hilt bindings live in `:core:data`.) The census wire contract now lives in the
  **`census-contract/` included build** (`cloud.trotter.census.contract`, Apache-2.0, #1173;
  `:domain` depends on it with `api`; owns `sha256OrNull`, `AnonymousWrappers`, `SensitiveMarkerData`).
  `domain.privacy.PiiShapes` stays in `:domain` (app licence), the
  promoted customer-PII pattern SSOT that the test-side `SnapshotRedactor` delegates to and the census
  filter reads.
- **`:core:pipeline`** — Accessibility pipeline, notification pipeline, JSON rule engine
  (RuleCompiler, Ruleset, JsonRuleInterpreter), observation classifier. Reads third-party UI.
- **`:core:state`** — Multi-region state machine (StateMachine, FlowRegionStepper,
  PlatformRegionStepper, CrossPlatformRegionStepper), effect map, `TransitionPolicy` (screen-
  authoritative mode resolution + commit graces; replaced the old `HealingPolicy`), crash
  recovery (StateManagerV2). Defines `EffectExecutor` and `MetadataProvider` interfaces.
- **`:core:database`**: Room entities, DAOs and database setup. The data-safety posture and the schema
  release checklist are in [05-analytics-read-model.md](05-analytics-read-model.md) § Database safety posture.
- **`:core:data`** — Repository implementations, mappers, data sources. Bridges domain interfaces to
  concrete data layers; census Keystore credentials, bounded file spool, debug upload sink (#1182), trusted-envelope sink + spool (debug), and the identity reset + upload lock (#1185).
- **`:core:network`** — Retrofit clients, OkHttp interceptors, EIA gas price API integration, signed census transport (#1182).
- **`:core:location`** — Play Services GPS tracking.
- **`:core:datastore`** — Preferences DataStore (twelve single-concern stores behind Hilt
  qualifiers: ten in `DataStoreModule` — app prefs, strategy, dev settings, odometer, app state,
  platforms, rule-capability grants, the #1151 event-receipt consent, the #981 **weekly plan**, and
  the #1333 S2f **vehicles** store (the saved vehicles and the active one, a user artifact like the
  plan, backed up) — plus `census_credentials` and the census health store in `:core:data`'s
  `CensusCredentialsModule`, beside the Keystore sealer). The weekly-plan store is deliberately
  NOT a Room table: a saved plan is a **user artifact** (nothing in `app_events` implies it, and
  nothing can rebuild it), so it must not live in the read-model DB that a `PROJECTOR_VERSION`
  bump wipes — see §5.
- **`:core:designsystem`** — Brand system (no project deps): fixed dark/light palette (`AppColors`),
  Hanken Grotesk + Space Grotesk fonts (tabular numerals), `AppTheme` + `LocalGlance` (the public
  theme wrapper is `GigLogTheme`, named for the app; #1411 renamed the app from DashBuddy to gig.log and retired
  the last `Dash*` brand symbols), and the shared component library (`AppCard`, `AppChip`, `AppStatTile`,
  `AppGaugeRing`, `AppSegmented`, `AppSlider`, `AppBarChart`, `AppAccordion`). The design-system
  brand vocabulary is `App*`, not `Dash*` (#468) — no platform-flavoured names. No M3 dynamic
  color. Feature-specific composables stay with their feature (Package by Feature); only generic,
  data-in/lambdas-out components live here. It also owns the shared **display vocabulary** two
  modules would otherwise copy: the Compose time helpers (`time.TimeKit` — `rememberNow`,
  `rememberTimeFormatter`) and the em-dash no-value placeholder (`text.EMPTY_VALUE`, #942 — the
  `:app` analytics hub and `:feature:dashboard` each held a copy, and a feature module can never
  reach an `:app` owner).
- **`:app`** — UI (Compose + overlays), side effect handlers (SideEffectEngine, odometer, screenshots,
  TTS, tips), Hilt DI wiring, and the `GigLogApplication` entry point.
- **`matchers/`** — the recognition **ruleset** source, as a self-contained **included Gradle build**
  (`includeBuild("matchers")` in the root `settings.gradle.kts`), NOT a `:core:*` project module. Owns the
  per-platform **JSON5 rule source** and the kotlinx-serialization canonicalizer. A platform source is
  EITHER a flat `matchers/rules/<platform>.json5` file (uber) OR a `matchers/rules/<platform>/` **directory of
  human-readable surface sub-files** (doordash — `_manifest.json5` metadata (`format_version`/`platform_id`) + per-surface
  `sensitive`/`offer`/`dash-lifecycle`/`pickup`/`dropoff`/`nav-comms`/`ratings-feedback`/`chrome`
  + `notifications.json5`) that the canonicalizer **merges** (sub-files sorted by name,
  arrays concatenated) into ONE canonical `assets/rules/<platform>.json` (#639); the merge fails the build
  loud on a duplicate rule id across sub-files (the canonicalize-time analog of the runtime #624/#633 rejects).
  `:core:pipeline:importMatchersRules` imports the canonical output into generated `assets/rules/*.json` — the
  app loader/tests/runtime are unchanged, still one file per platform (there are no committed `assets/rules/*.json`).
  See [02-rule-engine.md](02-rule-engine.md) + ADR-0009. It is **licensed
  Apache-2.0** (`matchers/LICENSE`, dual-licensed against the app's PolyForm Shield) so a fork needs no
  relicensing. The ruleset stays in-tree and is developed directly from the JSON5 source: the split to a
  separate repo was deferred on 2026-07-03, and #637 (create that repo) was closed as not planned on 2026-10-04.

## Compose tests

From `CLAUDE.md` Reactive UI rule 6 (moved by trim v4). A composable reads time from `rememberNow()` (ticking values)
or `LocalNowProvider.current` (a one-off read), both `:core:designsystem`. Compose tests live in `src/testDebug` (the
test host activity is a debug dependency), provide a clock they control, set `mainClock.autoAdvance = false`, move
the clock and call `mainClock.advanceTimeBy(2_000)`: one tick of 1,000 ms rounds to whole frames and can miss the
recomposition (see the `ComposeSmokeTest`s in `:feature:bubble` and `:app`). Motion runs on the Compose frame clock
(`mainClock.advanceTimeBy`), wall time on `LocalNowProvider`, and a one-time effect persists its mark at start, never
at its end.

`rememberNow()` is a `produceState` loop over `LocalNowProvider.current` that re-reads the clock every `tickMs`
(default 1,000 ms); `val now by rememberNow()` and derive from it.
