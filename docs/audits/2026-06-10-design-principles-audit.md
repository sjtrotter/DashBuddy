# Design-Principles Audit — 2026-06-10

Full-codebase audit against the CLAUDE.md **Development Principles** (UDF, MAD, clean
code / single responsibility, Kotlin/Android best practices), at master `3872684`
(post-PR #339). Method: 7 parallel auditors, one per module slice; **every main-source
Kotlin file (260 files, ~23.7k loc) was read and its leaf-ins (imports) and leaf-outs
(reverse-grepped consumers) established before judging it**. Findings cite verified
file:line evidence. Two findings were discarded as already fixed the same day
(agents quoting pre-#339 CLAUDE.md matcher text).

Issues referenced as `#NNN` exist; issues labeled `[A‑n]` are **proposed, not filed** —
see the tracker at the bottom.

---

## Scorecard

| Module | Grade | One-line verdict |
|---|---|---|
| `:domain` | **B** | Pure (zero Android imports verified file-by-file), strong wire-enum idiom; ~10% dead models/abstractions, evaluator mints presentation strings, untyped event payloads |
| `:core:pipeline` | **B** | Clean staged flows + real defense-in-depth package gating; contract drift (capture binding, transform purity), 5 redundant pref caches, hot-path re-parsing |
| `:core:state` | **B‑** | Genuinely pure reducers (time-wise) and principled UDF core; effect layer drags wall clock + device I/O + DB entities into reduction; replay identity broken by UUIDs |
| `:core:data` | **B‑** | Thin repos w/ proper mapping; AppEventRepo leaks Room entity; NoOp release capture binding unwired; odometer echo race |
| `:core:database` | **B** | Idiomatic Room, correct indexes, exported schemas; two tables never pruned; dead legacy `snapshots` table still in schema |
| `:core:datastore` | **A‑** | The #72 decomposition held: six single-concern stores behind qualifiers. It is **Preferences** DataStore (CLAUDE.md said Proto — fixed) |
| `:core:network` | **B+** | Secrets sourced correctly from local.properties; but unconditional BODY logging prints the EIA api_key to logcat |
| `:core:location` | **A‑** | Cleanest module; callbackFlow with awaitClose kill-switch |
| `:core:designsystem` | **A‑** | All 11 components verified pure (data + hoisted lambdas + tokens); LocalGlance still write-only (#318); ~73% components are Phase-0 build-ahead |
| `app:ui:bubble` | **B** | Best-in-repo Reactive-UI compliance (everything ticks via `rememberNow`); BubbleManager is a 5-in-1 UI→repo write funnel; DB entity + Gson inside `ui/` |
| `app:ui:main` | **B‑** | Right skeleton (single activity, Hilt, immutable WizardState); WizardViewModel sprawl, wholesale-duplicated economy editor, ~30 locale-unsafe money formats |
| `app glue` (effects/di/util) | **B‑** | Correct executor inversion + clean loopback; **no exception isolation at the effect boundary**, main-thread PNG encode, ~400 loc verified-dead code |

**Overall: B.** The architecture is principled where it was designed to be — pure
steppers, effects-as-data, recognition-as-data, anchor-based ticking UI — and the
defects cluster at the *edges* (effect execution, serialization, formatting, caching),
which is exactly what a good UDF design should make true. Nothing requires redesign.

---

## Top 10 risks (cross-module synthesis)

1. **One throwing effect kills the app.** `SideEffectEngine.process()` launches
   `execute()` bare into a scope with no `CoroutineExceptionHandler`
   (`SideEffectEngine.kt:84-88`); `EvaluateOffer`'s DataStore read, `LogEvent`'s Room
   insert, and `markFired` can all throw → process death. [A‑1]
2. **Crash recovery can re-click offers (latent).** `PerformOfferAction` is missing
   from `isExternalEffect` (`SideEffectEngine.kt:390-407`). Unreachable today only
   because replayed `UiInput` is rebuilt as a stub — the moment action payloads are
   persisted (#300/event-fidelity direction), recovery would auto-accept/decline live
   offers. [A‑2]
3. **Replay determinism is broken in three independent ways**: wall-clock + timezone
   inside `parseTime`/`parseDeadline` transforms (`TransformRegistry.kt:311-324` —
   the 05-19 "ghost countdown" class) [A‑4]; `UUID.randomUUID` ×6 minting
   session/job/task IDs inside the stepper (`PlatformRegionStepper.kt:154…`) [A‑5];
   fire-and-forget journal inserts + `ORDER BY sequenceId` replay
   (`StateManagerV2.kt:115`) [A‑12]. Each independently invalidates the documented
   `obs.timestamp` replay invariant.
4. **The paused-safety timer is dead.** `Observation.Timeout` has no platform →
   routes to `Platform.Unknown`'s region → `SESSION_PAUSED_SAFETY` no-ops on the
   actually-paused platform (`StateMachine.kt:61`). [A‑3]
5. **Release builds write third-party screen captures to disk.** `DiskCaptureBus` is
   bound unconditionally; the documented `NoOpCaptureBus` release binding does not
   exist anywhere (`PipelineModule.kt:21-22` vs `CaptureBus.kt:9`). Directly touches
   the privacy pledges. [A‑8]
6. **`AppEventEntity` (a Room row) travels through the state machine into Compose.**
   Built in `EffectMap.kt:836` (:core:state), carried by `AppEffect.LogEvent`,
   consumed by `FlowCardMapper` in `ui/bubble` which Gson-decodes payloads itself.
   Corroborated independently by 3 auditors; blocks #96 extraction. [A‑14, pairs #119]
7. **Idempotency writes race the effects they guard.** `markFired` is a separate
   fire-and-forget launch (`SideEffectEngine.kt:216-226`): crash windows exist in both
   directions (double-insert or lost event). Pairs with #300's wall-clock key. [A‑11]
8. **Gson snapshots bypass Kotlin null-safety.** Reflective `Unsafe` instantiation can
   null non-null fields on schema drift; `deserializeParsed` swallows all failures to
   `ParsedFields.None` unlogged (`StateManagerV2.kt:225,332-338`). [A‑13]
9. **Stale-eval mis-narration.** The evaluation loopback carries no `offerHash`
   (`FlowRegionStepper.kt:129-140`): a replaced offer inherits the previous offer's
   evaluation, and the notification/TTS speak the wrong economics. [A‑6]
10. **~55 locale-unsafe `String.format` sites on money/clock digits** (bubble 23,
    main UI ~30, plus `FormatUtils.formatCurrency` and `OfferEvaluator` warning
    strings) — `$7,50` rendering and digit-localized countdowns on non-US locales,
    including the user-facing offer notification. [A‑20]

Honorable mentions: economy text inputs eat the decimal separator while typing
(`CurrencyInput.kt:30`) [A‑18]; wizard **Skip** runs the full save and silently resets
automation thresholds (`WizardViewModel.kt:539`) [A‑9]; EIA `api_key` printed to logcat
by an unconditional BODY interceptor (`GasPriceApiModule.kt:27`) [A‑10]; screenshot PNG
compression on the main thread exactly while an offer is on screen
(`ScreenShotHandler.kt:41-54`) [A‑17]; `DELIVERY_CONFIRMED` dropped when lazy expiry
commits on a non-flow observation (`EffectMap.kt:412`) [A‑7].

### Two pleasant surprises

- **The privacy package-gate verified out** (post-#334): release service config +
  reactive listener gate + real-package attribution in all three sub-pipelines — no
  off-target-package route to capture remains. The remaining exposure is the release
  capture *binding* (risk 5) and UNKNOWN volume [A‑22].
- **Reactive-UI compliance in the bubble is exemplary** — every time-derived value
  ticks from an anchor (`expiresAt`/`deadlineMillis`/`arrivedAt`) via the canonical
  `rememberNow`; tabular numerals prevent jitter. Sole breach: `lastSessionSummary`
  stores a drifting derived duration in the VM (`BubbleViewModel.kt:163-166`).

---

## Findings mapped to EXISTING issues (no new issue needed)

| Issue | Audit findings that land there |
|---|---|
| **#96** `:feature:bubble` | BubbleManager 5-in-1 decomposition + ChatRepository write funnel (also called from a *ViewModel*); dual source of truth for `activeDashId` (racy: messages persist with `dashId=null` after end); `BubbleUiState` instead of 9 loose flows + 4 scan-accumulators that belong in the reducer; whole-`AppState` collection recomposes the bubble per pipeline event; `FlowCardSnapshot` (UI view-state) lives in `:domain`; Lab `FakeOfferCard` diverged from the real redesigned card — share one renderer post-extraction |
| **#237/#238/#240** file splits | `WizardViewModel` 586 loc / 4 concerns (grew 390→586 in one commit, past its exemption); `FlowCardItem` 761 (header + 5 bodies + 11-param `DeadlineBody` + color policy + time kit); `EffectMap` 930 with diff concerns + ser/de + reflection + entity assembly; `PlatformRegionStepper` 674 / 5 concerns; `UiNode` 328 / 4 concerns (model + matcher DSL + 3 hash strategies + printer); #238's plan still targets deleted `Mode*` composables |
| **#239 + #293** RuleCompiler | 27× `!!`; match-time regex compile inside notif-parse lambda (`RuleCompiler.kt:385`); per-find `navigate` string parsing; unknown `onFail` silently coerces to `skip`; `deriveIntentFromId` duplicates `deriveTargetFromId`; per-event platform re-filtering in `Ruleset.matchFirst` |
| **#119** event metadata | `AppEffect.LogEvent` carries the Room entity; `createMetadata()` does live device I/O (odometer/battery/PackageManager) *inside the diff* — replayed events get current battery; `ScreenShotHandler` uses 2 of the repo's only 3 `DashBuddyApplication` static reach-ins (the third is the sanctioned #119 seam) |
| **#300** duplicate app_events | Root cause re-confirmed; plus: `app_events` has no idempotency key at all (`REPLACE` on an autoGenerate PK is a no-op strategy — add a unique deterministic-key index); interacts with [A‑11] ordering |
| **#57** strings | ~115 hardcoded user-facing literals counted (bubble ~60, main ~56); strings also live in *ViewModels* (Dashboard status/welcome, platform display names, About toasts); `WizardStep`/`PermissionType` prove the resourced pattern |
| **#56** settings VMs | `SettingsMenuViewModel` is a junk-drawer union consumed as 3 *separate* instances; its theme/pro-mode surface is dead (General screen is still a placeholder); `AppPreferencesDataSource` retains theme/proMode/dead-SIM strays |
| **#97** dashboard | Confirmed: 3 of `DashboardViewModel`'s 4 flows are dead (uncollected); `AndroidViewModel` for nothing; `DashboardUiState` is the fix |
| **#163** spot-save | `ScheduleTimeout` timers are in-process `delay()` jobs — lost on death, re-armed at full (not remaining) duration; durable-timer design belongs to this work |
| **#202** projections | `OdometerRepository` DataStore echo race (persisted emission can transiently regress live miles); dead `Task.odometerAtEntry/AtArrival`, `Job.tasks`, `Session.runningMiles`, `surgeMultiplier` fields nothing writes |
| **#244** tests | 4 state tests still in `:app`; `UiNodeTest` duplicated in two trees; `SideEffectEngine` (throttle/idempotency/timers) has zero behavior tests despite being mock-friendly |
| **#245** canonical schema | `RatingsFields` vs `RatingsSnapshot` 12-field structural twins; ParsedOffer/payload/FlowCardSnapshot flattening triplication |
| **#254** constants | Economy/strategy defaults scattered inline (3.50 / 10.0 / 2.0 / 0.50 / rule targets); IRS $0.67 hardcoded twice (and stale for 2026); wizard/settings slider ranges disagree for the same metrics |
| **#314** read-model | `PeriodTotals` confirmed never populated — a HUD reading it would render believable zeros (false-confidence, Reactive-UI rule 5) |
| **#318** glance | `LocalGlance` confirmed write-only; `HeroBig` hardcodes 30.sp at exactly the site the multiplier should apply |
| **#241** DSL docs | `$schema` relative path in both rule files resolves to a nonexistent directory (editor validation silently dead) — add to #241's list |
| **#192** OTA | `JsonRuleInterpreter` non-atomic 3-var ruleset swap must be fixed before hot-reload; `enumeratePermissions` is held for #192 (mark it) |
| **#110** Stage 3 | `OfferAutomationConfig` + `ScoringRule.autoDeclineOnFail` are persisted by the wizard but **enforced by nothing** — Stage 3 is the enforcement work; until then the wizard toggles are no-ops (see also [A‑9]) |

---

## Proposed issue tracker (NOT yet filed — for triage)

### P0 — correctness & safety

| ID | Proposed title | Labels | Core evidence |
|---|---|---|---|
| A‑1 | SideEffectEngine: isolate effect execution — a throwing effect must not crash the app | `bug` | `SideEffectEngine.kt:84-88`; no CEH on `StateManagerV2.kt:42` scope |
| A‑2 | Classify `PerformOfferAction` as external — crash recovery must never replay offer clicks | `bug` `offer-engine` | `SideEffectEngine.kt:390-407` |
| A‑3 | Timeout observations route to `Platform.Unknown` — `SESSION_PAUSED_SAFETY` no-ops on the paused platform | `bug` `architecture` | `StateMachine.kt:61`, `PlatformRegionStepper.kt:228-237`, `EffectMap.kt:392-397` |
| A‑4 | Inject clock/zone into `TransformRegistry` time transforms — wall-clock `parseTime`/`parseDeadline` breaks replay (05-19 ghost-countdown class) | `bug` `pillar:matchers` `data-enrichment` | `TransformRegistry.kt:311-324` vs its own purity doc `:24-25` |
| A‑5 | Deterministic session/job/task IDs — `UUID.randomUUID` in `PlatformRegionStepper` breaks crash-recovery replay identity | `bug` `architecture` | 6 sites incl. `:154,195,413,437,523`; defeats `start_session:$sessionId` effectKey |
| A‑6 | Correlate offer-evaluation loopback by `offerHash` — replaced offer inherits stale eval (wrong TTS/notification) | `bug` `offer-engine` | `FlowRegionStepper.kt:129-140`, `StateManagerV2.kt:357-364` |
| A‑7 | Task-closure events dropped when lazy expiry commits on a non-flow observation (missing `DELIVERY_CONFIRMED`) | `bug` `data-enrichment` | `EffectMap.kt:412` vs `PlatformRegionStepper.kt:64-68` |
| A‑8 | Release builds persist screen captures — bind `NoOpCaptureBus` in release (or decide+document always-on); move binding out of `:core:pipeline` DI | `bug` `architecture` `pillar:sovereignty` | `PipelineModule.kt:21-22`; `NoOpCaptureBus` zero bindings; false KDoc `CaptureBus.kt:9` |
| A‑9 | Wizard Skip/re-run silently resets automation thresholds + `allowShopping` to hardcoded defaults | `bug` | `WizardViewModel.kt:539-547` (with TODO), `WizardScreen.kt:78,283`, re-run entry `SettingsHomeScreen.kt:144` |
| A‑10 | EIA `api_key` printed to logcat — unconditional BODY `HttpLoggingInterceptor` with default logger | `bug` | `GasPriceApiModule.kt:27`, `EiaApi.kt:10` |

### P1 — high-value fixes & refactors

| ID | Proposed title | Labels | Scope |
|---|---|---|---|
| A‑11 | Effect idempotency ordering: `markFired` after effect completion (transactional outbox) + define `EffectExecutor` ordering semantics | `bug` `architecture` | `SideEffectEngine.kt:216-226` crash windows both directions; per-effect launch discards EffectMap's emit order; pairs #300 |
| A‑12 | Crash-recovery replay fidelity: serialized journal writes, cv-ordered replay, persist internal-observation payloads, buffer pre-restore pipeline events | `bug` `architecture` | `StateManagerV2.kt:115` fire-and-forget; `ObservationDao.kt:14` orders by `sequenceId`; `toEntity` drops Timeout/UiInput/Loopback payloads; hot sources uncollected during restore |
| A‑13 | Migrate AppState snapshots + ParsedFields from Gson to kotlinx.serialization | `refactor` `architecture` | Unsafe instantiation nulls non-null fields; swallowed `deserializeParsed`; manual 13-subtype registry; gson-internal API; dead gson dep in `:core:pipeline` |
| A‑14 | Domain `AppEvent` model — stop leaking Room `AppEventEntity` through `:core:state` and `ui/bubble`; move FlowCardMapper fold beside `FlowCardSnapshot` | `refactor` `architecture` | `AppEventRepo.kt:15`, `EffectMap.kt:836`, `FlowCardMapper.kt:3,29,340`; pre-work for #96, overlaps #119 |
| A‑15 | Invert `:core:pipeline → :core:data` to domain interfaces (CaptureBus + platform prefs); drop unused `:core:state → :core:data` dep | `refactor` `architecture` | Coupling is 16 imports / 6 files, all capture API + one concrete repo; `core/state/build.gradle.kts:44` dep has zero imports |
| A‑16 | Notification path: consolidate 5 enabled-platform caches into one shared StateFlow; remove `runBlocking` + dead updater; fix reconnect-dead listener scope | `bug` `refactor` | `NotificationFilter.kt:28-40`, `NotificationListener.kt:56-59` |
| A‑17 | Move screenshot PNG compress + MediaStore write off the main thread | `bug` | `ScreenShotHandler.kt:41-54` (callback hops to main executor) |
| A‑18 | Economy text inputs reset mid-typing — `remember(value)` round-trip eats the decimal separator | `bug` | `CurrencyInput.kt:30,59`, `PairedCurrencyAndIntervalInput.kt:82` |
| A‑19 | Extract shared `EconomyEditor` — wizard card + settings screen duplicate 10 accordion sections and have already diverged | `refactor` | `EconomyCostsCard.kt` (388) vs `EconomySettingsScreen.kt` (321) |

### P2 — hygiene & consistency

| ID | Proposed title | Labels | Scope |
|---|---|---|---|
| A‑20 | Locale-explicit formatting: one shared money/duration/countdown formatter; promote `rememberNow` + time kit to `:core:designsystem`; fix PhaseChip token breach | `chore` `refactor` | ~55 bare `.format` sites; duplicated divergent `formatDuration` ×2; `FlowCardItem.kt:156-161` uses M3 roles against `DashColors`' own contract; `FormatUtils`/`OfferEvaluator` locale defaults |
| A‑21 | Dead-code purge (repo-wide, ~700+ loc) | `cleanup` | `UtilityFunctions.kt` (235, zero refs); `AccNodeUtils` 3/4 dead; `GroupedRectDecoration` + `preference-ktx` dep + 4 drawables; domain: `ClickLogEntry`/`SnapshotWrapper`/`Pipeline` trio/`DashType`/`PickupStatus`/`DropoffStatus`/ScoringUtils helpers/commented `iconResId`; data: `snapshots` table + `SnapshotDao` + `ClickLogEntryDto` + `NoOpCaptureBus` resolution + SIM keys + `PayParser` decision; app: dead DI providers (unqualified DataStore + SharedPreferences) + unused Application injection + `lastAcceptedOfferPay` + `NumberInput` + dead `_steps`; `TtsEffectHandler.shutdown` |
| A‑22 | UNKNOWN capture dedup: content-bearing identity + rolling suppression (May-triage follow-up) | `enhancement` `pillar:matchers` | `AccessibilityPipeline.kt:138-142`; UNKNOWN identity is contentless |
| A‑23 | Pipeline structure pack: `shareIn` PipelineV2 / shared capture stage / remove 6 unchecked casts / atomic ruleset-bundle swap / rule compile off main thread | `refactor` | `PipelineV2.kt:19-23`, `AccessibilityPipeline.kt:106-238`, `JsonRuleInterpreter.kt:33-39`, `DashBuddyApplication.kt:104-105` (+stale "dual-run" comment) |
| A‑24 | TransformRegistry + parse-expression hardening (extends #293's defect classes beyond RuleCompiler) | `bug` `pillar:matchers` | match-time `!!` on transform params, per-value regex recompile, bare `lowercase()`, first-key dispatch (`TransformRegistry.kt:92-155`); sha256 helper duplicated with plaintext-on-failure fallback (fail closed instead) |
| A‑25 | StateManagerV2 decomposition: extract ObservationJournal + SnapshotStore | `refactor` | 6 responsibilities in 373 loc; recovery untestable in isolation |
| A‑26 | UiNode: immutable tree (val parent wiring, read-only children) + ingestion depth/node caps | `refactor` | `UiNode.kt:34` mutable in documented-immutable contract; `AccessibilityNodeMapper.kt:34-43` unbounded recursion, IPC per child |
| A‑27 | Data-layer concurrency hygiene: injected dispatchers (5 ad-hoc scopes), thread-safe `DateTimeFormatter`, serialized log/capture writers, `StrategyRepository` typed combine, atomic platform-prefs edit, wire `observations`/`effects_fired` pruning | `chore` | `StrategyRepository.kt:31,51`, `DiskCaptureBus.kt:33`, `LogRepository.kt:40-54`, `PlatformPreferencesRepository.kt:50`, prune DAOs with zero callers |
| A‑28 | Build/manifest hygiene: `robolectric` → `testImplementation`; align JVM target 21 (`core:data`/`core:datastore` at 11); remove vestigial FGS permissions or commit to a real odometer FGS; timer `remove(key,job)` race; TTS focus on failed `speak()` | `chore` | `app/build.gradle.kts:132`; `core/data/build.gradle.kts:31`; `AndroidManifest.xml:12-17`; `SideEffectEngine.kt:198-202`; `TtsEffectHandler.kt:72-73` |
| A‑29 | Domain typing cleanup: `qualityLevel` → enum + drop `recommendationText` (UI derives copy); typed sealed payloads for `Observation.Timeout/UiInput/Loopback`; `SessionStartSource` consts; evaluator no-rules branch mislabels parsed data; consider renaming `domain.state.Flow` (kotlinx collision); fix 5 stale KDocs | `refactor` `offer-engine` | `OfferEvaluation.kt:6`, `OfferEvaluator.kt:61,146-158`, `Observation.kt:104-124`, `AppEventPayloads.kt:110`, `Flow.kt:13` |
| A‑30 | Small UI/VM behavior fixes: `lastSessionSummary` drifting duration (store anchors); chat vs cards dashId inconsistency; `simulateOffer` unmemoized in composition; GasPriceCard EV snap → VM; VehicleCard unencoded search URL; `collectAsStateWithLifecycle` adoption; StateAwareTree per-line stack capture | `bug` `chore` | `BubbleViewModel.kt:163-166,110-122`, `StrategySettingsScreen.kt:59`, `GasPriceCard.kt:47`, `VehicleCard.kt:159`, `StateAwareTree.kt:72-92` |

Suggested sequencing: A‑1/A‑2/A‑8/A‑10 are an afternoon of surgical fixes with outsized
risk reduction. A‑4/A‑5/A‑11/A‑12 + #300/#119 form one coherent **"replay integrity"
workstream** and should be planned together (they gate the #202→#314 analytics chain).
A‑14/A‑15 are the pre-work that makes #96–#99 extractions clean.

---

## Per-module appendix — file inventory

Role and flags per file, as established by the auditors (leaf-in/leaf-out edges were
verified by reverse-grep during the audit; flags ∅ means no issue found).

### :domain (77 files, 3,279 loc) — grade B

Purity verified: zero `android.`/`androidx.` imports in all 77 files; no `!!`, no
`runBlocking`, no `GlobalScope`. JVM-clock leaks: 4 constructor defaults + 1 nanoTime
hash. Wire-enum idiom (`wire` + `fromWire`) uniform across Flow/Mode/Platform/
EffectVerb/TransitionTrigger.

| File | loc | Role — flags |
|---|---|---|
| capture/CaptureEnvelope.kt | 20 | replay/corpus envelope — `platform` String though Platform enum exists |
| capture/CaptureSchema.kt | 17 | per-pipeline payload contract — ∅ |
| capture/ReplayMetadata.kt | 23 | version stamps (ADR-0003), 19 consumers — ∅ |
| capture/ReplayMetadataProvider.kt | 9 | live metadata interface — stale kdoc (impl is :core:pipeline) |
| config/DashStrategy.kt | 3 | wizard strategy enum — ∅ |
| config/EvidenceConfig.kt | 7 | evidence toggles — ∅ |
| config/OfferAutomationConfig.kt | 18 | auto accept/decline config — **dead-end: zero enforcement consumers**; stale kdoc |
| evaluation/EconomyField.kt | 37 | settable-field enum — ∅ |
| evaluation/EvaluationConfig.kt | 11 | evaluator input snapshot — ∅ |
| evaluation/MerchantAction.kt | 12 | merchant action enum — ∅ |
| evaluation/MetricType.kt | 12 | metric enum — UI labels baked into domain |
| evaluation/OfferAction.kt | 8 | verdict enum (14 consumers) — ∅ |
| evaluation/OfferEvaluation.kt | 43 | eval result — `qualityLevel`/`recommendationText` are presentation strings |
| evaluation/OfferEvaluator.kt | 241 | rank-weighted scorer — magic 70/30; misleading no-rules branch; locale formats; ignores `autoDeclineOnFail` |
| evaluation/ScoringRule.kt | 26 | sealed rules — `autoDeclineOnFail` persisted, never read |
| evaluation/ScoringUtils.kt | 36 | quality ladder — 2 of 3 fns test-only dead |
| evaluation/UserEconomy.kt | 107 | cost profile + derivations — ∅ |
| model/accessibility/BoundingBox.kt | 7 | rect — ∅ |
| model/accessibility/ParsedTime.kt | 3 | text+epoch pair — ∅ |
| model/accessibility/UiNode.kt | 328 | tree node + search DSL + 3 hashes + printer; 49 consumers — mutable (`var parent`, MutableList); 4 concerns |
| model/cards/FlowCardSnapshot.kt | 153 | bubble card view-state — UI state in :domain (→ #96); stringly action/badges |
| model/chat/ChatMessage.kt | 9 | chat record — ∅ |
| model/chat/ChatPersona.kt | 60 | sealed personas — ∅ |
| model/dash/DashType.kt | 17 | legacy earn-mode enum — **dead** (converters only; superseded by SessionType) |
| model/event/AppEventType.kt | 32 | event enum — commented-out values |
| model/event/EventMetadata.kt | 14 | event context — stringly networkType (→ #119) |
| model/event/payload/AppEventPayloads.kt | 152 | 7 payloads + SessionEndSource — SessionStartPayload.source lacks consts |
| model/location/Coordinates.kt | 31 | latlon + haversine — ∅ |
| model/location/UserLocation.kt | 11 | location — ∅ |
| model/log/clicks/ClickLogEntry.kt | 12 | click record — **dead (zero consumers)** |
| model/log/snapshots/SnapshotWrapper.kt | 9 | tree wrapper — **dead** |
| model/notification/RawNotificationData.kt | 27 | raw notif + hash — stale kdoc link |
| model/offer/OfferBadge.kt | 145 | badge enum + matchers — commented iconResId remnants |
| model/offer/ParsedOffer.kt | 52 | parsed offer — rawExtractedTexts format ambiguous |
| model/order/DropoffStatus.kt | 20 | phase enum — **dead** (converters only) |
| model/order/OrderBadge.kt | 65 | order badge enum — commented remnants |
| model/order/OrderType.kt | 70 | type enum + matcher — ∅ |
| model/order/ParsedOrder.kt | 15 | per-order result — ∅ |
| model/order/PickupStatus.kt | 20 | phase enum — **dead** (converters only) |
| model/pay/ParsedPayItem.kt | 10 | pay line — `type` doubles as store name for tips |
| model/pay/ParsedPay.kt | 16 | breakdown + totals — ∅ |
| model/ratings/RatingsSnapshot.kt | 22 | ratings snapshot — stale AppStateV2 kdoc; wall-clock default; twins RatingsFields |
| model/state/OfferEvaluationEvent.kt | 9 | legacy eval event — wall-clock default |
| model/state/StateEvent.kt | 11 | base event interface — deliberately non-sealed (documented) |
| model/state/TimeoutEvent.kt | 8 | legacy timeout event — wall-clock default; `Map<String,Any?>` payload |
| model/vehicle/FuelType.kt | 8 | fuel enum — ∅ |
| model/vehicle/VehicleClass.kt | 108 | class presets — ∅ |
| model/vehicle/VehicleDetails.kt | 17 | vehicle details — ∅ |
| model/vehicle/VehicleOption.kt | 14 | trim option — ∅ |
| pipeline/EffectVerb.kt | 47 | verb vocabulary + tier — ∅ |
| pipeline/Observation.kt | 137 | sealed observation hierarchy — `Map<String,Any?>` payloads; subtype boilerplate |
| pipeline/ObservationIdentity.kt | 35 | semantic dedup identity — ∅ |
| pipeline/PermissionTier.kt | 22 | tier enum — ∅ |
| pipeline/Pipeline.kt | 39 | Pipeline/Capturable/Composite contracts — **dead abstraction (zero implementors)**; phantom type param |
| pipeline/PipelineRegistry.kt | 17 | pipeline id/version map — ids re-hardcoded in StateManagerV2 |
| pipeline/RequestedEffect.kt | 57 | rule-declared effect + NodeRef — 3 types/file |
| pipeline/RuleEngineConstants.kt | 9 | engine versions — ∅ |
| pipeline/StateMachineContract.kt | 21 | loader compatibility sets — ∅ |
| pipeline/TransitionDefaults.kt | 91 | trigger enum + effect defaults — 2 types/file |
| provider/FuelPriceDataSource.kt | 16 | gas-price interface — `Result` idiom differs from sibling |
| provider/VehicleEfficiencyDataSource.kt | 17 | vehicle interface — throws-based, inconsistent |
| state/AppState.kt | 29 | root state + Regions — wall-clock default timestamp param |
| state/CrossPlatformRegion.kt | 32 | R1 + PeriodTotals — totals never populated (→ #314) |
| state/Flow.kt | 30 | flow enum, 21+ consumers — name collides with kotlinx Flow |
| state/FlowRegion.kt | 37 | R0 + PendingOffer — ∅ (lastClickIntent wire-string deliberate) |
| state/Job.kt | 58 | job accumulation + AcceptedOfferEconomics — ∅ |
| state/Mode.kt | 24 | mode enum — ∅ |
| state/OfferIntent.kt | 12 | accept/decline wire consts — deliberate |
| state/ParsedFields.kt | 222 | sealed parse IR, 12 subtypes — `ClickFields.dedupeHash()` uses **System.nanoTime()**; 12 subtypes one file |
| state/Platform.kt | 42 | platform enum + resolution — ∅ |
| state/PlatformRegion.kt | 99 | R2 + PendingDestructive — exemplary purity kdoc; 4 types/file |
| state/Session.kt | 14 | session record — `runningMiles` never written |
| state/SessionType.kt | 15 | earn-mode enum — ∅ |
| state/Task.kt | 27 | task segment — `odometerAtEntry/AtArrival` never written |
| state/TaskPhase.kt | 10 | phase enum — ∅ |
| state/TaskSubFlow.kt | 9 | subflow enum — ∅ |
| util/FormatUtils.kt | 6 | currency formatter — `Locale.getDefault`; display util in domain |

### :core:pipeline (28 files, 4,047 loc) — grade B

Rules assets: doordash.json 77.5KB / 84 rules (53 screens, 14 clicks, 17 notifs);
uber.json 17.7KB / 32 rules; priorities compiler-enforced unique per file/type;
`$schema` path broken (resolves one module too shallow → #241). Dependency edge to
`:core:data` is 16 imports across 6 files (capture API + one concrete prefs repo) —
invertible to `:domain` [A‑15]. Dead gson dependency declared, zero usages.

| File | loc | Role — flags |
|---|---|---|
| accessibility/AccessibilityPipeline.kt | 252 | merge+classify+gates+dedup+capture — 6 unchecked casts; 83-loc capture serializer inline; never-cancelled scope |
| …/content_changed/ContentChangedPipeline.kt | 61 | debounced content snapshots, real-package gate — per-event watchedPackages alloc |
| …/content_changed/Flow.debounceWithTimeout.kt | 35 | custom debounce operator — wall-clock timing; doc overstates guarantee |
| …/state_changed/StateChangedPipeline.kt | 39 | state-change snapshots — ∅ |
| …/windows_changed/WindowsChangedPipeline.kt | 88 | all-windows snapshots (#248 Uber overlay) — silent catch; logs ALL window titles pre-filter |
| accessibility/input/AccessibilityListener.kt | 117 | a11y service entry, reactive package gate — static `_instance` duplicates AccessibilitySource ref |
| accessibility/input/AccessibilitySource.kt | 107 | event bus + live roots — non-volatile serviceRef; getLiveWindowRoots double-adds active root |
| accessibility/mapper/AccessibilityNodeMapper.kt | 54 | node→UiNode recursion — unbounded depth/nodes; mutates post-construction |
| accessibility/mapper/RectMapper.kt | 7 | rect map — ∅ |
| accessibility/TreeSnapshot.kt | 35 | tree + metadata value type — ∅ |
| di/PipelineModule.kt | 26 | binds DiskCaptureBus + ReplayMetadataProvider — **unconditional DiskCaptureBus, all build types**; binds core:data types in pipeline module |
| notification/input/NotificationListener.kt | 60 | notif listener service — scope cancelled on disconnect, reused on reconnect → dead collector |
| notification/input/NotificationSource.kt | 20 | SBN bus — ∅ |
| notification/mapper/NotificationMapper.kt | 23 | SBN→RawNotificationData — string extras keys vs constants |
| notification/NotificationFilter.kt | 41 | package pre-filter — `runBlocking` lazy init; dead `updateEnabledPackages` → frozen cache |
| notification/NotificationPipeline.kt | 113 | notif classify/gate/dedup/capture — duplicates accessibility stage chain |
| ObservationClassifier.kt | 208 | single classify entry — write-only `lastScreenTimestamp`; logs full UNKNOWN notif text |
| PipelineEvent.kt | 36 | pre-classification union — minor duplication |
| PipelineV2.kt | 24 | cold merge → StateManagerV2 — side effects in cold flow, no shareIn |
| ReplayMetadataProviderImpl.kt | 28 | version provider impl — ∅ |
| rules/CompiledRules.kt | 119 | compiled value types — FQN style |
| rules/JsonRuleInterpreter.kt | 168 | ruleset owner + hot-reload entry — non-atomic 3-var swap; size cap chars-vs-bytes |
| rules/ParsedFieldsFactory.kt | 346 | raw map → typed fields — duplicate sha256 w/ plaintext fallback; try/valueOf/catch ×4 |
| rules/RuleCompileException.kt | 4 | typed failure — ∅ |
| rules/RuleCompiler.kt | 1217 | JSON→lambda compiler — #239/#293; 27× `!!`; match-time regex in notif parse |
| rules/Ruleset.kt | 280 | matchFirst executor — per-call platform filter; duplicated derive helper |
| rules/RulesetLoader.kt | 41 | ADR-0003 root check — 2 of 7 steps implemented (documented deferral) |
| rules/TransformRegistry.kt | 498 | transform vocabulary — **Calendar.getInstance() wall clock in parseTime/parseDeadline**; 13× `!!`; bare lowercase |

### :core:state (13 files, 2,587 loc) — grade B‑

Purity: FlowRegionStepper PURE; TransitionPolicy PURE; CrossPlatformRegionStepper PURE;
PlatformRegionStepper pure in time (obs.timestamp honored) but impure in identity
(UUIDs ×6). All reduction-path impurity routes through EffectMap (wall clock, device
I/O via createMetadata, 2 Timber calls). `:core:data` dependency declared but unused.

| File | loc | Role — flags |
|---|---|---|
| model/Transition.kt | 8 | step result pair — ∅ |
| MetadataProvider.kt | 12 | metadata seam — impl does live device I/O inside diff (→ #119) |
| EffectExecutor.kt | 26 | executor seam — contract silent on ordering [A‑11] |
| ParsedFieldsAdapter.kt | 28 | Gson subtype registry — manual, no exhaustiveness guard |
| CrossPlatformRegionStepper.kt | 42 | R1 stepper — PURE; unused obs param |
| StateMachine.kt | 66 | R0→R2→R1 orchestrator — timeout routing to Unknown platform [A‑3] |
| TransitionPolicy.kt | 74 | mode inference + classification — PURE |
| AppEffect.kt | 105 | effect ADT (17 variants) — **AppEventEntity inside ADT** (#119); wall-clock effectKey (#300) |
| util/RuntimeTypeAdapterFactory.kt | 108 | gson-extras copy — gson internal API |
| FlowRegionStepper.kt | 141 | R0 reducer — PURE; loopback eval lands without offerHash [A‑6] |
| StateManagerV2.kt | 373 | merge+reduce+journal+snapshots+recovery+bridge — 6 responsibilities [A‑25]; fire-and-forget journal [A‑12]; Gson Unsafe restore [A‑13] |
| PlatformRegionStepper.kt | 674 | R2 reducer — UUID identity impurity [A‑5]; 5 concerns (→ #237); per-call Set alloc in hot path |
| EffectMap.kt | 930 | region differ → effects — wall clock + I/O + entity assembly in diff (#300/#119); gate fail-open; 930 loc (→ #240) |

### Data layer (67 files, ~3.5k loc) — grades: data B‑ · database B · datastore A‑ · network B+ · location A‑

Highlights only (full flags in agent evidence): `AppEventRepo` returns raw entities
[A‑14]; `DiskCaptureBus` bound in all build types [A‑8] + shared `SimpleDateFormat`
across IO coroutines; `OdometerRepository` DataStore echo race (→ #202);
`StrategyRepository` positional `Flow<Any>` casts + ad-hoc IO scope (one of 5);
`PayParser` dead in main; `LogRepository` unordered fire-and-forget appends;
`PlatformPreferencesRepository.setEnabled` non-atomic read-modify-write;
`DevSettingsRepository` borrows `:core:network` BuildConfig. Database: no Migration
classes — `fallbackToDestructiveMigration(true)` (alpha-sanctioned but deletes the
event history the journal exists to protect); `observations` + `effects_fired` prune
DAOs never called [A‑27]; dead `snapshots` table + `SnapshotDao` + `ClickLogEntryDto`
still in schema v6; `app_events` REPLACE-on-autoincrement is a no-op dedup (#300);
silent enum fallbacks in `DataTypeConverters` can rewrite event history quietly.
Datastore: six single-concern **Preferences** stores behind qualifiers (CLAUDE.md said
Proto — fixed in this PR); `AppPreferencesDataSource` (303) is one cohesive economy
concern + theme/proMode/dead-SIM strays (→ #56). Network: EIA key sourced from
local.properties correctly; BODY logging leaks it to logcat [A‑10]; EPA errors
swallowed to emptyList (wizard can't tell offline from empty). Location: cleanest
module; cold un-shared GPS flow would double-register on a second collector.

### app:ui:bubble + :core:designsystem (~25 files, ~4k loc) — grade B / A‑

| File | loc | Role — flags |
|---|---|---|
| bubble/BubbleActivity.kt | 29 | bubble host — clean |
| bubble/BubbleManager.kt | 207 | channel+shortcut+notif factory+repo writer+dash-id holder — 5 responsibilities; UI→repo write funnel; binder IPC in `@Inject` init (→ #96) |
| bubble/BubbleViewModel.kt | 198 | 9 loose flows + dispatch — WhileSubscribed ✓; 4 scan-accumulators = reducer logic in VM; 1 dead flow (→ #96) |
| bubble/BubbleScreen.kt | 647 | scaffold+status+stack+chat+helpers — #238 (stale plan); dup `formatDuration`; dup import |
| bubble/cards/LiveCardBuilder.kt | 113 | pure AppState→card — pure ✓; zero tests |
| bubble/cards/FlowCardMapper.kt | 346 | event-log fold → cards — fold pure + 18 tests ✓; **consumes Room entity + Gson in ui/** [A‑14] |
| bubble/cards/FlowCardItem.kt | 761 | card composable + time kit — → #237; 23 locale-less formats; PhaseChip token breach; dead identical conditional |
| designsystem theme (6 files) | 333 | DashTheme/Colors/Type/Fonts/Shapes/Glance — LocalGlance write-only (#318) |
| designsystem components (7 files) | 616 | 11 components — all verified pure; only DashChip + DashGaugeRing consumed (Phase-0 build-ahead) |

### app:ui:main (~38 files, ~4.6k loc) — grade B‑

Key rows: MainActivity (173, single-activity NavHost ✓, hardcoded strings);
DashboardViewModel (90, 3 of 4 flows dead → #97); SettingsMenuViewModel (62, junk-drawer
×3 instances → #56); SettingsViewModel (72, misnamed; `simulateOffer` pull-based);
StrategySettingsScreen (237, simulator runs in composition unmemoized; no back
affordance; SwitchRow borrowed cross-file); EconomySettingsScreen (321) +
EconomyCostsCard (388) near-clones already diverging [A‑19]; WizardViewModel (586,
4 concerns, public MutableStateFlow, skip==save clobber [A‑9], shared-map mutation from
parallel async); CurrencyInput/Paired (decimal-eating reset [A‑18], dead NumberInput);
GroupedRectDecoration (66, dead + sole user of preference-ktx [A‑21]); PermissionType/
Card/BottomSheet (fully string-resourced — the model for #57); WizardStep/Phase
(@StringRes ✓). Theme migration verdict: PASS (zero dynamicColor/MaterialTheme
remnants; both activities wrap DashBuddyTheme).

### app glue (~15 files, ~1.7k loc) — grade B‑

| File | loc | Role — flags |
|---|---|---|
| state/effects/SideEffectEngine.kt | 407 | executor impl: 20 variants + 14 verbs + idempotency + throttle + timers + loopback — **no exception isolation** [A‑1]; PerformOfferAction not external [A‑2]; markFired race [A‑11]; timer remove race; no-op verb stubs |
| state/effects/OdometerEffectHandler.kt | 96 | odometer start/stop/pause — per-method catch ✓; non-FGS "ongoing" notif |
| state/effects/ScreenShotHandler.kt | 140 | screenshot → MediaStore — main-thread PNG [A‑17]; 2 Application static reach-ins (→ #119 adjacency) |
| state/effects/TipEffectHandler.kt | 31 | tip → bubble message — could be inline branch |
| state/effects/TtsEffectHandler.kt | 99 | spoken eval + audio focus — focus stranded on failed speak; dead shutdown() |
| state/effects/UiInteractionHandler.kt | 91 | cross-window click (PR #330) — recycling N/A on minSdk 35 ✓ |
| state/effects/OfferActionReceiver.kt | 44 | notif action → dispatch — trySend non-blocking ✓; EntryPoint idiom ✓ |
| di/AppModule.kt | 65 | bindings — 2 dead providers incl. unqualified DataStore foot-gun [A‑21] |
| di/DomainModule.kt | 22 | OfferEvaluator provider (keeps :domain annotation-free) ✓ |
| log/StateAwareTree.kt | 92 | Timber tree → file log — per-line Throwable stack capture |
| util/UtilityFunctions.kt | 235 | legacy parser grab-bag — **100% dead** [A‑21] |
| util/AccNodeUtils.kt | 127 | click strategies — 3 of 4 fns dead |
| util/PermissionUtils.kt | 75 | permission checks — healthy, keep |
| worker/DailyGasPriceWorker.kt | 58 | @HiltWorker periodic refresh — textbook ✓ |
| DashBuddyApplication.kt | 139 | Hilt root + init — synchronous main-thread rule compile [A‑23]; dead injected field; stale "dual-run" comment |

---

## Method note

Auditors: 7 parallel subagents (same model tier as the orchestrator, per the CLAUDE.md
Subagent Model Policy), read-only, each covering one module slice with a mandate to
read every file and reverse-grep its consumers before judging. Findings were
deduplicated and reconciled against the 2026-06-10 issue audit; verdicts that
conflicted with already-merged fixes (PR #339) were discarded.
