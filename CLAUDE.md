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

Cross-references: matchers infra RFC #192; aggregation RFC #193; academic federation RFC #194.
(Monetization pricing is recorded in pillar 1 above; the cloud-data-platform RFC #141 was closed
2026-07-12 with its un-built cloud half folded into the pillar epics.)

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

# Re-run the #590 security property/fuzz suite UNSEEDED at 10x samples (#878).
# Every property pins a seed by default so PR CI is deterministic; this flag is the
# exploration path — run it off the PR path (nightly/manual), and when it finds
# something, paste the reported seed into that property's pinned SEED const.
./gradlew :core:pipeline:testDebugUnitTest :app:testDebugUnitTest -Ddashbuddy.propExplore=true

# Run instrumented tests (requires connected device/emulator)
./gradlew :app:connectedAndroidTest

# Build a specific core module
./gradlew :core:database:build
```

Dependencies are managed via the version catalog at `gradle/libs.versions.toml`. Min SDK is 30
(guarded: `bubblePreference` API 31 falls back to `areBubblesAllowed` below 31), target SDK is 36,
Kotlin 2.3.20, JVM 21.

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
```

**Feature modules (MAD Phase 6 — complete; no extractions remain).** UI extracted per-feature under
`:feature:*` (`cloud.trotter.dashbuddy.feature.<name>` namespace), depending only DOWN the graph
(`:app → :feature:* → :domain / :core:*`); a feature module **never** depends on `:app` or on
another feature. `:app` legitimately depends on each feature for its moved `@HiltViewModel`s and
any screens/copy the app shell still hosts. Each module declares only the deps it uses (the
honest-deps doctrine — `:feature:setup` carries no `:core:designsystem`, `:feature:dashboard` no
Hilt/`:core:data`). Residency rule: the HOST and the injections decide ownership, not the source
path — a ViewModel that injects `:app`/`:core:state` machinery (`DashboardViewModel`,
`BubbleViewModel`) stays in `:app` rather than forcing an inversion (the honest-smaller-module
doctrine), and a multi-consumer string/color is duplicated by choice into a module with the `:app`
copy authoritative, documented at both sites (`common_content_desc_back` #99, `@color/white` #854).
Shipped: `:feature:settings` (#99); `:feature:setup` (#98 — wizard VM/model/step cards; the
`WizardScreen` shell + `EconomyCostsCard` stay in `:app` with the shared `ui/components/economy/*`
editor family, and the Dashboard-hosted `setup/consent/` #843 + `setup/permissions/` sheets stay in
`:app`); `:feature:dashboard` (#97, reshaped by #977/#981/#1024 — Home's presentational composables
over the shared `DashboardRow` vocabulary, pure data-in/lambdas-out over `:domain` +
`:core:designsystem`; Home's review rows are the hub's own `reviewTexts`/`ReviewList` reused from
`:app` as a composable slot, so flag thresholds/copy keep ONE owner, and `DashboardScreen`/
`DashboardViewModel`/`DashboardUiState` stay in `:app`); and `:feature:bubble` (#96/#867 — the HUD's
presentational content: `formatters/*`, `cards/*`, the view renderers, and `session/*` (the pure
`BubbleSessionResolver` — follow-active + manual switcher / pinned / merged — plus the stateless
`PlatformSwitcherRow`); no Hilt/`:core:data`/`:core:state`, while `BubbleActivity`, `BubbleScreen`
(nav host), `BubbleManager` and `BubbleViewModel` stay in `:app`, which consumes the moved renderers
as a legal `:app → :feature` dependency). The analytics hub (`ui/main/analytics/*`) is a separate
sibling surface, left for a future extraction.

- **`:domain`** — Pure Kotlin library. Domain models, state regions, evaluation logic,
  pipeline/provider contracts, the capture contracts (`CaptureBus`, `EnvelopeBuilder`,
  capture schemas/DTOs), the `PlatformPreferences` read interface (#355), and the
  number/money/duration formatting SSOT (`format.Formats` money/decimal/**percent** — #942 pulled
  the `(x * 100).roundToInt()` shape down off four UI call sites — + `format.TimeFormats`
  `formatDuration`/`formatCountdown` — the locale policy, #358/#456/#467; lives here so both the
  UI and the state layer route through one definition; the Compose time helpers
  `rememberNow`/`rememberTimeFormatter` stay in `:core:designsystem`). No Android
  dependencies. (Repository *implementations* and Hilt bindings live in `:core:data`.)
- **`:core:pipeline`** — Accessibility pipeline, notification pipeline, JSON rule engine
  (RuleCompiler, Ruleset, JsonRuleInterpreter), observation classifier. Reads third-party UI.
- **`:core:state`** — Multi-region state machine (StateMachine, FlowRegionStepper,
  PlatformRegionStepper, CrossPlatformRegionStepper), effect map, `TransitionPolicy` (screen-
  authoritative mode resolution + commit graces; replaced the old `HealingPolicy`), crash
  recovery (StateManagerV2). Defines `EffectExecutor` and `MetadataProvider` interfaces. (#715
  struck the dormant Expected/Unexpected `outcomes` classification — schema key, compiler support,
  `TransitionPolicy.classify()`, `TransitionKind`, `PlatformRegion.lastTransitionKind`: no ruleset
  ever declared `outcomes`, so `Unexpected` was unreachable. `#438`'s Offline→Online healing edge
  never depended on it.)
- **`:core:database`** — Room entities, DAOs, and database setup. **Data-safety posture (#690):
  no `fallbackToDestructiveMigration`.** `app_events` is the analytics source of truth (the
  read-model tables are a rebuildable projection of it), so an upgrade that Room has no path for must
  **fail loud** (`IllegalStateException`) rather than silently DROP-and-recreate the log — the dev
  backs up + resolves instead of losing history. The supported upgrade base is **v8+**: on-disk
  versions below v8, and downgrades (on-disk newer than the code), are an **intentional loud crash at
  startup**, each preceded by a fresh pre-crash snapshot. `DatabaseModule` **eagerly opens** the DB
  at injection time (`openHelper.writableDatabase`) so the failure is a deterministic startup crash,
  not a lazy first-query one that the projector/recovery supervision would swallow; just before the
  open it takes a pre-open file snapshot (`DatabaseBackup`, WAL-aware version read, dedup-marked,
  version-prefixed dirs, last 2, failure-tolerant) whenever the on-disk version ≠ the code version. Schema version is one SSOT const
  (`DashBuddyDatabase.VERSION`). **Release checklist when changing the schema: bump
  `DashBuddyDatabase.VERSION` → regenerate the exported schema JSON (`exportSchema`) → add the
  `AutoMigration` (or a manual `Migration`) covering the new edge → add its `MigrationTestHelper`
  case** (`core/database/src/androidTest`). Two **real gates** enforce this: the CI
  `Schemas committed (Room export drift)` step fails if the build regenerated an uncommitted schema
  JSON, and `SchemaVersionGuardTest` (unit) source-scans the `@Database` migration edges (it's BINARY
  retention) to prove they chain v8 → `VERSION` with no gap — the forgotten-`AutoMigration` class the
  retired fallback used to mask. Migration-*correctness* tests are instrumented (`connectedAndroidTest`)
  and do NOT gate the unit-only PR CI.
- **`:core:data`** — Repository implementations, mappers, data sources. Bridges domain interfaces to
  concrete data layers.
- **`:core:network`** — Retrofit clients, OkHttp interceptors, EIA gas price API integration.
- **`:core:location`** — Play Services GPS tracking.
- **`:core:datastore`** — Preferences DataStore (eight single-concern stores behind Hilt
  qualifiers — app prefs, strategy, dev settings, odometer, app state, platforms,
  rule-capability grants, and the #981 **weekly plan**). The weekly-plan store is deliberately
  NOT a Room table: a saved plan is a **user artifact** (nothing in `app_events` implies it, and
  nothing can rebuild it), so it must not live in the read-model DB that a `PROJECTOR_VERSION`
  bump wipes — see §5.
- **`:core:designsystem`** — Brand system (no project deps): fixed dark/light palette (`AppColors`),
  Hanken Grotesk + Space Grotesk fonts (tabular numerals), `AppTheme` + `LocalGlance` (the public
  theme wrapper is `DashBuddyTheme`, the only `Dash`-named symbol kept — it's the app name, not a
  platform term), and the shared component library (`AppCard`, `AppChip`, `AppStatTile`,
  `AppGaugeRing`, `AppSegmented`, `AppSlider`, `AppBarChart`, `AppAccordion`). The design-system
  brand vocabulary is `App*`, not `Dash*` (#468) — no platform-flavoured names. No M3 dynamic
  color. Feature-specific composables stay with their feature (Package by Feature); only generic,
  data-in/lambdas-out components live here. It also owns the shared **display vocabulary** two
  modules would otherwise copy: the Compose time helpers (`time.TimeKit` — `rememberNow`,
  `rememberTimeFormatter`) and the em-dash no-value placeholder (`text.EMPTY_VALUE`, #942 — the
  `:app` analytics hub and `:feature:dashboard` each held a copy, and a feature module can never
  reach an `:app` owner).
- **`:app`** — UI (Compose + overlays), side effect handlers (SideEffectEngine, odometer, screenshots,
  TTS, tips), Hilt DI wiring, and the `DashBuddyApplication` entry point.
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
  See §"JSON Rule Engine" below + ADR-0009. It is **licensed
  Apache-2.0** (`matchers/LICENSE`, dual-licensed against the app's PolyForm Shield) so the eventual split to
  a separate forkable repo needs no relicensing — but that split (#192/#637) is **deferred; the ruleset is
  kept in-tree for now** (2026-07-03 decision) and developed directly from the JSON5 source.

## Architecture: Recognition Pipeline + State Machine

The core architectural challenge is understanding a third-party app's UI without an API. The
solution is a multi-stage pipeline. Each subsection below is the
**summary an agent needs every session** — the invariants and where they live; the full receipt-level
narrative (every rule with its issue/PR history and rationale) lives in `docs/architecture/0N-*.md`, one
file per subsection, and is maintained under the same every-PR context-update rule as this file:

### 1. Sensor Pipelines (`core/pipeline/`)

Full reference: [`docs/architecture/01-sensor-pipelines.md`](docs/architecture/01-sensor-pipelines.md).

`AccessibilityListener`/`AccessibilitySource` capture `AccessibilityEvent`s; `AccessibilityNodeMapper`
normalizes a window into an immutable `UiNode` tree (`:domain`). Per-event-type sub-pipelines
(`ContentChangedPipeline` debounced, `StateChangedPipeline`, `WindowsChangedPipeline`, clicks) and the
parallel `NotificationPipeline` emit `PipelineEvent`s. `AccessibilityPipeline.output()` gates in order:
**rulesets-not-loaded** (fail-closed, #432) → **sensitive/noise** (#399) → **disabled platform** →
**UNKNOWN** (captured to disk for triage, never forwarded to the state machine). Snapshots are attributed
to the window's *real* package, so our own overlay is dropped. `FrameGate` admits frames (identity dedup +
UNKNOWN hash suppression, #360); `CaptureWriter` assembles envelopes (#361) and applies the matched
rule's **`redact`** block (#598) — **envelope-only**: recognition, parse and dedup run on the original
tree, and a click's `contentHash` stays on the original node. `PipelineV2.events` is a HOT `shareIn` stream (side effects never
double-run); the upstream is supervised with restart backoff (#430); `PipelineStats` counts every gate
decision and prints a periodic INFO summary whose head carries `app=<versionName>` (`<base>+<git sha>`,
PR #1066 — read a pull's build from the logs, never infer it).

**Redaction invariants (Pledge — a recognized rule that forgets to redact has NO backstop):**
- Mask is `[redacted:<4hex>]` = first 4 hex of sha256 of the stripped/trimmed token (#623); a
  customer-NAME entry flagged `normalize: customerName` hashes the canonical key (#733) so the mask hex
  equals the parse's `customerNameHash` prefix across name FORMS ("Brandy S"/"Brandy Smith"). Fail-closed to
  plain `[redacted]`.
- `plainMask` (#795) opts a bounded-ALPHABET value (4-digit PIN, subpremise/unit number, length-bounded
  note) into hash-less `[redacted]`; the rule-independent **short-token floor** (#889) does the same for
  any sub-4-char token (`normalize: customerName` entries exempt). `plainMask`+`normalize` together is a
  compile reject. A structural scan over both platforms' assets forces `plainMask` on every subpremise
  entry (#986/#934); the split `Apt/Suite: <n>` form anchors on its label sibling (#1039).
- `maskNode` is **first-match-wins**: blanket/plain entries sit AHEAD of generic shape entries, or a
  digit-leading value gets claimed and hashed by the street shape.
- **Combined-frame class (#993):** a redact protects only frames its OWN rule wins, so a banner that can
  inflate over another rule's frame (`arriving_at_title`) is declared by EVERY rule in `dropoff.json5`
  plus `navigation_generic` (parity test). The Google-Nav maneuver cluster is masked on dropoff-phase nav
  rules + `navigation_generic`; `pickup_navigation`'s MERCHANT address stays raw by design (#886), while a phase-ambiguous slot
  over-masks toward privacy — `timeline_task_detail` (#985) anchors on `Copy address` text AND
  `Close sheet` contentDescription, BOTH required, and masks the merchant render too (store names raw).
- The id-less **name shape** joins tokens with `\s{1,4}` (never a literal space, #885) and is byte-SSOT
  with `SnapshotRedactor.FIRST_LAST_INITIAL_PATTERN`. A sub-flow sibling copies the entry verbatim so
  the hex stays equal (#992/#1031). A name entry enumerates every conjugation a surface renders;
  `timeline`'s redact/parse prefix lists diverge only by documented exclusion (guard test, #994/#998).
- Coverage spans screen surfaces AND notification envelopes (#620). A receipt-scan camera is
  recognize-and-redact, not blocked (#995). Sensitive rules prefer **view-id anchors** (locale-immune,
  #938/#924/#1059); `SensitiveSurfaceBlockTest` pins claimed-by-rule + `sensitive.known` + dropped at
  the content gate.
- **Recognize-only is NOT state-inert** (it moves `FrameGate.lastIdentity`); "no `state` block" buys
  lifecycle neutrality, asserted by `FlowlessRecognitionNeutralityTest`.

**Backstops (rules-independent, cross-platform DATA):** `SensitiveTextMarkers` drops the dasher's
banking screens; `CustomerTextMarkers` (#624/#806) scrubs a node/field carrying a customer-PII marker on
recognized AND UNKNOWN screen/notification/click envelopes, plus `ID_MARKERS` (view-id suffixes whose
VALUE is PII, `hasIdSuffix`, #910/#993/#1058) on UNKNOWN screen + click envelopes only (a recognized
frame keeps its rule's deliberate decisions). A click envelope inherits the SCREEN rule's redact
(`Observation.Click.screenRuleId`, #910). Candidate text markers are vetted against the corpus
(`CaptureBackstopCorpusTest`); chrome-ambiguous prefixes are rejected and the rule redact is the primary
control. Intake prefixes (`SnapshotRedactor.NAME_PREFIXES`) are deliberately asymmetric with the runtime
markers, with a floor (#1064): a chrome-ambiguous intake prefix carries a tail predicate
(`GATED_NAME_PREFIXES`), `SnapshotRedactor.customerLeadIn` is the one owner, hand-written fixture
pseudonyms are exempted by the byte-exact `CorpusDecoys` list, and a raw-pseudonym assertion is pinned
to files BY VALUE, never folder-wide. Field-enumeration SSOTs keep a new model field from missing a
scrub site: `RawNotificationData.textFields()` (#666 — the 5 flat text fields; `actionLabels` is
deliberately outside it and scrubbed separately) and `UiNodeTextField` +
`UiNode.scrubbableStrings()` (#835; `stateDescription` is scrubbed but `UiNode.allText` — what rules
match on — still excludes it: widening a scrub layer must never move a classification). Rules skip a
file with duplicate ids and a later file re-declaring an id (#624/#633). Release binds `NoOpCaptureBus`
(#346); UNKNOWN captures are the documented debug-only exception.

**English-device assumption (#938):** anchors and both marker SSOTs are English literals; on a non-`en`
device recognition falls to UNKNOWN and the Pledge layers thin. `ID_MARKERS` is the one locale-immune
defence. `RecognitionLocale` (`:domain`) + `LocaleBoundaryNotifier` (`:app`) make it loud: one WARN per
sensor start + a once-per-install notice (`app_state` flag `locale_boundary_notice_shown`). Translating
anchors is deliberately NOT done until a non-English corpus exists.

**Liveness (#937/#1036, the #909 silent-death family) — every piece fail-OPEN and inert to frame
processing; a diagnostic failure must never become a recognition gate:** `PlatformAppVersions` stamps the observed
app's `versionName` onto `ReplayMetadata.platformAppVersion` (additive, nullable, no test may require
it); `RecognitionHealth` trips one WARN + notice per platform per process when a full 50-frame admitted
window is ≥ 0.80 UNKNOWN. `Ruleset.matchFirst` reports a `ParseShortfall` when a matched branch parsed
nothing usable — every evidence field unresolved, or a shape-REQUIRED field null — judged per field by
`ParseFieldKind` (`CONSTANT` excluded, `NULLABLE` null, `COLLECTION` empty), measured pre-validate and
even when a `Skip` validator discards the branch; counted post-admission in `PipelineStats` (per-rule
`parseShortfall{…}` + one WARN per rule per process, tag `ParseHealth`, rule ids only; the benign baseline — rules whose one evidence field is legitimately optional, incl.
the 8.95.6 `earnings_pill` carousel on `waiting_for_offer` — is listed in the reference). **An OPTIONAL
`bind` target that resolves no node on a matched frame is the third trigger (#1093)** — the receipt's
`expandButton` anchored on an id 8.93.7 removed, and because the bind was optional the
`EXPAND_EARNINGS` tap was simply never emitted, for weeks, with no trace; it rides the same
`ParseShortfall` (`unresolvedOptionalBindings`) into its OWN census (`bindShortfall{<rule>.<bind>=n}`, one
WARN per rule+bind per process) and never moves the parse count. The #1063 first-frame offer card
(Decline not yet rendered) is an expected, bounded tripper. Notices share
`AppNoticeChannel` (ids 102 locale / 103 recognition / 105 TTS; 104 is `weekly_plan_channel`).

### 2. JSON Rule Engine (`core/pipeline/.../rules/` + generated `assets/rules/`)

Full reference: [`docs/architecture/02-rule-engine.md`](docs/architecture/02-rule-engine.md).

Recognition is **data, not code**. Source is per-platform **JSON5** under `matchers/rules/` (spec
ADR-0001, schema `docs/rules.schema.json`; sub-files use `docs/rules.fragment.schema.json`), owned by the
included `matchers` build (ADR-0009). `:core:pipeline:importMatchersRules` canonicalizes/merges it into
**generated** `assets/rules/<platform>.json` consumed by both the APK and the unit tests
(`TestRulesetFactory`); there are **no committed** rule assets, so a JSON5 edit flows straight into
recognition tests. Rule order within a file is inert (unique priorities per section). `RuleCompiler`
compiles, `ObservationClassifier` matches.

- **Partitions:** `matchFirst` evaluates the non-overrideable partition first, then the overrideable
  one, each priority-ordered (#419). `sensitive.known` is priority 0 + `overrideable: false`;
  `sensitive.catchall` is priority 999 + overrideable.
- **Blocks:** `require` predicates, `bind`, `parse` (typed via `ParsedFieldsFactory`), `redact`
  (#598 — a screen rule using the `sha256` transform MUST declare a non-empty `redact`; branch-level
  `redact` is rejected). Effect `dedupeKey`s interpolate `{field}` against the branch's RAW parse, plus two DERIVED
  reserved tokens the classifier resolves post-factory (`DedupeTokens`, the lint's SSOT):
  `{parsedHash}` (content identity, #427) and `{presentationHash}` (presentation identity, #859 —
  fail-closed to `offerHash` when `presentationKey` is null). A derived field is never an ordinary
  `{field}` template.
- **No actuation from rules (#425):** click/gesture verbs are compile-rejected; rules expose target
  bindings (`acceptButton`, `declineButton`, `expandButton`) that the app-owned `RuleAction` registry
  consumes (`docs/design/rule-capability-consent.md`).
- New rule↔state vocabulary goes through the enumerated, load-validated contract —
  `ParsedFieldsFactory.REQUIRED_FIELDS_BY_SHAPE` plus `StateMachineContract.EFFECT_INTENTS` /
  `.REQUIRED_FIELDS_BY_FLOW` — enforced at compile per file (principle 8).

**Rule regexes run on RE2J (#1053, ADR-0010).** Every rule pattern — predicate, parse `find`, redact
`match`, `nextSiblingMatchingRegex` — compiles through the ONE seam `RegexSafety.compileRegex` →
`BoundedRegex`, a linear-time NFA (no backtracking), replacing the unsound #418 heuristic and the #590
watchdog (which could never fire on Android). Pattern language is **RE2 syntax**: no lookaround,
backreferences, possessive/atomic groups. Bounded ingestion is three gates: (1) a coarse pre-compile
guard — `MAX_REGEX_LENGTH` 200 (as written), `MAX_REPEAT` 200, `MAX_GROUP_DEPTH` 16, `\Q…\E` and
leading-zero bounds rejected, `(?i:…)` accepted, cost estimate ≤ 200 000 whose only job is to keep the
compile from exhausting memory; (2) the compile wrapped in `catch (Throwable)` → loud
`RuleCompileException`, whole-file rejection; (3) the **MEASURED** bound `Pattern.programSize()` ≤
`MAX_PROGRAM_SIZE` = **1 000** (corpus max 240, printed by `RuleCorpusCompileBudgetTest`). Never
estimate a compiled program size from pattern text — measure it. The cap bounds EXPOSURE to match-time
stack overflow (recursion grows with nullable nesting, not instruction count); the backstop is
`RegexEvaluationFailed` (raised by `BoundedRegex.evaluating`, one WARN per pattern per process), which
**each consumer answers**: recognition treats the rule as no-match, parse yields null, **redaction masks
the WHOLE node/field** with plain `[redacted]` — a `false` default is fail-OPEN for a redact selector.
The catch is deliberately NOT in `PredicateCompiler`. The Perl classes are TRANSLATED toward ART's
Unicode classes at the seam (`\d`→`\p{Nd}`, `\s`→`[\s\p{Z}\x{0B}\x{85}]`,
`\w`→`[\p{L}\p{M}\p{N}\p{Pc}\x{200C}\x{200D}]`, negations likewise; `\S`/`\W` inside a class rejected) —
an APPROXIMATION, not parity: Unicode-table lag (Adlam), simple vs full case folding, ASCII `\b`,
listed in #1086. Authors keep writing `\d`; byte-SSOT pins are on source bytes. Guards:
`RuleRegexEngineGuardTest` (no `java.util.regex` in the rule package; every `Regex(…)` literal-only,
frozen count ledger), `RuleCorpusCompileBudgetTest` (50 ms load budget + program-size max), instrumented
`RuleRegexIsLinearTimeTest` (nightly on ART). The one corpus cost: uber's `^Going to (?!\d)` became
`^Going to (?:\D|$)`.

**Two bounded primitives for id-less renders (#1029, DoorDash 8.93.7 shipped its money surfaces with no
view ids):** (1) the **`parseGlyphCurrency` transform** reads an animated digit-wheel fused by `read:
allText` — rejects any non-ASCII digit and any `-`/`−`/`(` (#1052), keeps only `$`/digit/`.`/`,`, then
full-matches `CurrencyShape` (`$` + a bare `0` or 1–4 digits with NO leading zero, or one
`[1-9],\d{3}` thousands group, + exactly 2 decimals — `$016.70` is out of shape) or returns
null (bounded 256 chars, fail-closed — ~1 in 5 fielded reads is mid-animation; `parseCurrency` is WRONG on
that shape). (2) the **`nextSiblingMatchingRegex(<pattern>[, <cap>])` navigate** scans ≤ `MAX_SIBLING_SCAN`
= 8 following siblings for the first whose text full-matches (pattern compiled at rule load; cap outside
1..8 or unparsable isolates the rule); the three DoorDash money scans declare `2` as a CORRECTNESS control
(an unbounded scan returns the NEXT row's money as this one's). The sibling walk has one owner,
`UiNode.followingSiblings()`/`precedingSibling()`, by REFERENTIAL identity (`UiNode.equals` ignores
children). `CurrencyShape` is the ONE definition of a well-formed figure, byte-pinned by
`CurrencyShapePinTest` over the generated assets. Neither primitive catches a well-FORMED mid-spin read;
that is the settle gate's job (§3).

### 3. Multi-Region State Machine (`core/state/`)

Full reference: [`docs/architecture/03-state-machine.md`](docs/architecture/03-state-machine.md).

Observations reduce into `AppState(regions)` (`:domain`): **`FlowRegion`** (R0 — ground-truth screen
interpretation, NOT offers), one **`PlatformRegion`** per platform (session/task lifecycle + that
platform's offers), **`CrossPlatformRegion`** (derived aggregates). The steppers are pure and driven by
`obs.timestamp` — never a wall clock — so crash recovery replays observations over the last snapshot.
`StateManagerV2` hosts the reduction and crash recovery; `EffectMap` diffs prev/next into `AppEffect`s.
No `Platform` branch anywhere (principle 8); per-platform values ride `GraceConfig` through
`TransitionPolicy`.

**Settle gate (#1029/#1052/#1054) — a parsed running total moves `Session.runningEarnings` only after
standing unchallenged on its own surface for `sessionPaySettleMs` (3 s).** `PlatformRegion.pendingSessionPay`
parks the read; commit is the stepper's lazy expiry, woken by a `SESSION_PAY_SETTLE` timer (the idle dedup
hash folds `sessionPay` into `Observation.identity()`, so a repeat can never arrive — repetition was
REJECTED as the discriminator). Rules: (a) a park is owned by (FLOW, PLATFORM), checked on both prior and
resulting R0; losing either DROPS it; (b) BOTH wheel feeds are gated (`IdleFields.sessionPay`,
`PostTaskFields.sessionEarnings` — and `PostTaskFields.dedupeHash` folds in `sessionEarnings` so the
settled re-render is admittable at all); (c) every non-gated writer supersedes older parks; (d) comparisons are
cent-tolerant; (e) a pending's OWN wake lapses it by IDENTITY, a frame lapses a grace strictly PAST its
deadline and a park at-or-past — and ORDER matters: a flow frame runs the expiry FIRST (a park that
stood its window commits on the departure frame), a flow-LESS observation checks ownership first; (f) a contradicting read on the expiring frame supersedes the park; (g) a
`$0.00` read never overwrites a positive total. A park is FROZEN while the dash is not `Mode.Online` and
re-based UNCONFIRMED on the way back (a fresh agreeing read on its own surface is required to commit).
Crash recovery DROPS any restored park.

**Timer identity (#1054):** every arm from `ModeEffects.diffDeadlineTimer` carries
`ObservationPayload.GraceWake(wakeId)`, a per-region generation from `PlatformRegion.wakeSeq` via
`mintWakeId()` (a deadline is not unique); `GraceExpiry.isWakeFor`/`graceLapsed` is the one rule; a fire
from a replaced pending is inert; id `0` is the legacy unidentified value. There is no re-arm logic.
Arms carry `deadlineMs` (`OFFER_EXPIRY`/`SETTLE_UI` do not yet — #1076).

**Recovery (#1052/#1054) — evidence is dropped, a decision in flight is re-armed.** `AppState.recoveryHygiene`
runs ONCE on the FINAL state after the journal tail fold (never on the snapshot): drops the settle park and
the graced resume; re-bases `pendingDestructive` to its REMAINING window
(`base = servedFrom ?: windowFrom ?: since`; `servedFrom` makes the re-base a fixed point across a crash
loop; a live deadline MOVE re-anchors via `windowFrom`); `pendingDeadlineTimers()` re-arms that grace and
the **pause-safety net** (`PlatformRegion.pauseSafetyDeadline`, real state since #1054). A replayed
REGION timer is never executed (`SideEffectEngine` skips `REGION_TIMERS` while `recovering`); the
reconcile is their sole armer. Replay stamps each journal row's own `correlationVersion`; the cleaned
state is CHECKPOINTED (`SnapshotStore.checkpoint`, retried once, then ERROR + pending-retry on every
live observation). `initialize()` awaits the collector's subscription before `restoreState`. A legacy
payload-less `SESSION_PAUSED_SAFETY` fire is honoured when no armed net exists or it lands at/after
the armed deadline. Open gaps: #1076 (tail-replayed offers), #1083.

**Graces.** Destructive commits use the unified `pendingDestructive` + `GRACE_COMMIT` (#431: dash summary,
delivery receipt, task retire); `pendingModeResume`/`MODE_RESUME_COMMIT` debounces a screen-implied
Paused→Online resume (#605). The receipt window is SHAPE-keyed (#1033): a COLLAPSED receipt
(`parsedPay == null`) arms `receiptExpandGraceMs` (8 s); an EXPANDED frame keeps 2.5 s and tightens via
`minOf`; a PostTask frame that parses NO receipt at all keeps the pre-#1033 timing. Layer 1 is the ONLY path that lands an expansion in the STACKED shape (§5's re-price refuses it).

**Offers are platform-owned** (`PlatformRegion.pendingOffers`, #438 B3; `OfferLifecycle.kt`/`OfferEffects.kt`).
Identity is presentation-scoped (#830): `ParsedOffer.presentationKey = sha256(storeNames|orders.size|orderTypes)`
beside the churn-prone `offerHash`; a same-key different-hash frame is an **enrich-as-variant** (keep
`presentedAt`, click latches, speak-once; re-eval; `OFFER_EXPIRY` stays anchored on the original
`presentedAt`), a null key degrades to replace-on-any-change (a false MERGE is impossible). `offerKind`
(`match|direct`, #881) is a read-through — never a scoring input, never in `offerHash` or
`presentationKey`; DIRECT over MATCH logs "Superseded by
direct offer" and suppresses the replaced bubble. Display store reads go through
`ParsedOffer.displayStores`/`displayStoreText` (#882; the `orders[]` list deliberately keeps the
`Delivery (N)` chip so `presentationKey` is immune to store cycling).

**Accept survives the offer-presentation edge** as an `acceptedAt`-marked pending entry; the task edge
mints the survivor (`acceptInputsFromPending`). The accept grace is per-platform (`GraceConfig.acceptGraceMs`,
#762 D2), which also added the phase-less `task:active` flow (`Flow.TaskActive`, `toTaskPhase()` → null;
leaving offer-presentation to it infers a click-less accept ONLY from a non-task `returnFlow`).
A per-offer `OFFER_EXPIRY` timer resolves an overlay offer that vanishes without a frame and no-ops on
an accept-latched offer.

**Placeholders and store lineage.** An accepted offer pre-creates symmetric placeholders (one dropoff per
order + one pickup per distinct store; dropoffs stamped `Task.mintedByOfferHash`, a HINT not an identity,
#997). Screens resolve onto them by hint/customer-hash; a dropoff's store is re-attributed from pickup
lineage via the normalized **customer-hash join** (#526/#733/#745: single store → resolve; ≥2 stores →
earliest-confirmed only when this is the sole activated drop with that hash; else fall back to store-name
tokens, never outside the lineage; fail-null beats fail-wrong). `JobCompleteness.kt`'s per-customer
coverage arm (#749) proves completion from the pickup side when the strict arm fails — ONLY when pickups
map 1:1 to orders at distinct stores (their hash set IS the customer set) and every customer hash has a
finished, arrived drop.

**Unassign-via-help** (`Flow.TaskUnassigned`, #736/#752) is an inline ungraced abandon: `Task.unassignedAt`
set, `completedAt` null; the `unassignedAt == null` filters in the `PICKUP_CONFIRMED` close-out sweep and
`isJobPhysicallyComplete` are load-bearing. The abandoned drop's placeholder is retired by `taskId` for a dropoff-phase abandon (never a
hash join that could over-remove a sibling), by customer hash for a pickup-phase one; the cross-frame
retro-mark is edge-gated to the ENTRY into `task:unassigned` and leaves `completedAt` intact;
`Job.tasks` is reconciled every step as the non-unassigned lineage mirror; one `TASK_UNASSIGNED` event per
`taskId`; a misread self-heals only while the job stayed open.

### 4. Side Effect Engine (`app/.../state/effects/`)

Full reference: [`docs/architecture/04-side-effect-engine.md`](docs/architecture/04-side-effect-engine.md).

`SideEffectEngine` executes `AppEffect`s with `effects_fired` idempotency (recovery-aware), runs the
evaluation loopback, and owns the fail-closed action gates (#417): live `PermissionTierChecker` + the
capability consent gate (`RuleCapabilityRepository`; **no auto-grant, #843**). Handlers:
`OdometerEffectHandler`, `ScreenShotHandler`, `TipEffectHandler`, `TtsEffectHandler`,
`UiInteractionHandler` (the only path that ever clicks a third-party app, #425), `OfferActionReceiver`.

- **Every odometer fix is gated (#1057/#918).** The pure `:domain` `OdometerFixPolicy` judges each fix
  against the last ACCEPTED one (`MIN_DELTA_METERS` 5, `MAX_ACCURACY_METERS` 50, `MAX_SPEED_MPS` 67,
  `MAX_DELTA_WITHOUT_TIME_METERS` 2 000) → Accept / Reference / Ignore / Reject; neither Ignore nor Reject
  moves the reference; `INVALID_FIX` (NaN/out-of-range) is checked FIRST; elapsed time uses the monotonic
  clock where both fixes carry one; an Ignore refreshes the reference's TIMING only; rejection logging is
  episode-gated (numbers + reason enum only — a lat/long is never logged). The pre-fix +905 mi offset is
  not auto-repaired (dev decision).
- **The evaluator fails CLOSED on a missing distance (#936):** no `?: 1.0` fallback; an offer that would
  be scored without one returns the no-verdict shape (`NOTHING` / score 0 / `UNKNOWN`) after the three
  distance-independent verdicts (shopping opt-out, protect-stats, merchant BLOCK).
  The returned economics keep the REAL gross and the economy's real `operatingCostPerMile` (zeroing cpm
  would make the session's deliveries look cost-free) with `netPayAmount == gross` and every
  distance-derived figure an explicit `0.0` placeholder; `OfferEvaluation.hasDistanceMetrics` is the one
  predicate consumers branch on so placeholders are never rendered as measurements, and the offer fold
  persists the frozen estimates as NULL, not zero.
- **Dedupe granularity is the rule's to declare (#859):** a rule effect with `throttleMs` opts out of the
  48 h `effects_fired` row into its own wall-clock window (in-memory; a restart re-arms it). Evidence
  filenames are sanitized at the one gate (`EvidenceFilename.sanitizePrefix`).
- **The engine must never die silently (#909):** `AppEffect.LogEvent` is the ONLY writer of `app_events`.
  The per-item catch is `Throwable` (only `CancellationException` rethrown); the drain loop is supervised
  with capped backoff; and the ICU/JVM regex divergence (an `ExceptionInInitializerError` destroyed 91.7 %
  of an evening's data) is caught by source scan — `IcuRegexGuardTest` fails on a bare `}` in any
  main-source `Regex(…)` literal. **Write `\}`, never `}`.** Rule-authored patterns don't run on that
  engine at all (§2, RE2J).
- **The offer voice (#991):** `TtsEffectHandler` builds its engine from a `TtsEngineFactory` seam; every
  way an utterance can be lost escalates through the pure `TtsRecoveryPolicy` (rebuild → backoff → a
  once-per-process `TtsHealthNotifier` notice after 3 losses ∧ ≥2 rebuilds ∧ ≥60 s); a `speak()` SUCCESS means only QUEUED, so the
  streak resets on `onDone`; notify and rebuild COMPOSE (telling the dasher never skips the rebuild);
  engine identity is generation-checked; the rebuild is detached from the drain worker.

### 5. Analytics Read-Model (`core/data/.../analytics/`, `core/database/.../analytics/`, #314)

Full reference: [`docs/architecture/05-analytics-read-model.md`](docs/architecture/05-analytics-read-model.md)
— the receipt-level history of every fold rule, migration and surface below.

**CQRS:** `app_events` is the source of truth; `delivery_records`/`session_records`/`offer_records`
(+ `stores`/`pickup_records`) are a rebuildable projection. `AnalyticsProjector` (`:core:data`, every
launch, supervised) folds via the pure `RecordFolds`/`SessionFoldContext`/`CorrectionFolds` (`:domain`)
**exactly-once** (records + watermark in one transaction, PK = source `sequenceId`); a `PROJECTOR_VERSION`
bump wipes + refolds the whole log. **Ordering contract (#732):** `sequenceId` is the fold/order key;
`occurredAt` may lag it (`PICKUP_CONFIRMED`); "when did it really happen" reads the payload.

**Economics are FROZEN per record, never recomputed** (dev decision): `netProfit`, `frozenCostPerMile` and
the fuel/non-fuel split (#659) are computed at projection time against the offer's own frozen
`OfferEvaluation` cpm; `NetProfit` (`:domain`) is the one cost-math SSOT; `AnalyticsRepository` is
**DAO-only** (no economy dependency). `cashTip` stays outside `realizedPay`/`netProfit` and is added to gross/net only at the read
sites, so the reconciliation's Σ-attributed stays cash-free (#688);
`originalPayBasis` is stamped at first fold and never rewritten (#703).

**Pay basis ladder:** real pay from `DeliveryPayload.dropRealizedPay`/`totalPay` (#528); a receipt with
no itemization still prices its drops because `buildPostTask` SYNTHESIZES `ParsedPay` from the receipt's
scalars on the flat 8.93.7 layout (#1029; a COLLAPSED receipt stays `parsedPay == null`); else a
`PayBasis.OFFER_PAY` ESTIMATE from `offerPayShare` (#691), consumed only if no sibling drop already
folded a real receipt. Two mint sites, two policies: the INLINE (PostTask-exit) mint keeps the
conservative pooled split over the QUOTED owed orders (`OfferPayFallback.shareFor`); the whole
attribution ladder runs ONCE at the terminal close (`OfferPayFallback.closeAttribution`, #996/#997):
eligible-owed shrink (proven-complete jobs drop never-mintable placeholders; UNASSIGNED never shrunk;
an `endSession` bail can never prove completeness — it force-stamps `completedAt`) →
store ladder (`PER_OFFER_STORE` / `SUB_POOLED_STORE` / `STAMP_FALLBACK` / `CONSOLIDATED_CUSTOMER`,
matched through `StoreKeys.normalizedChain`) → wholesale `JOB_POOLED` degrade. Σ stamped ≤ Σ quotes is
structural. **A receipt speaks only for the drops it described when read (#1073):** `ReceiptCoverage` is
captured with the cached receipt and intersects the mint apportion, the attach and the re-price
denominator (one owner); completion eligibility is NEVER coverage-gated. Subject = the ACTIVE dropoff,
else the job's last completed drop (`receiptSubjectTaskId()`); residual #1081.

**Late-expanded receipt re-price (#1033 layer 2):** `EffectMap.diffReceiptReprice` emits one
`DELIVERY_RECEIPT_REPRICE` per delivered drop of the job, decided as a pure STEPPER transition
(`decideReceiptReprice`, at every itemized receipt frame, at the job close, and at `endSession`), keyed
`…:<taskId>:<jobId>:r<repriceRevision>`. Ownership is ONE temporal question — has any acceptance
resolved since this receipt appeared (`lastAcceptResolvedAt` vs `receiptSeenAt`) — so the STACKED
late-expand shape is refused (fail-null, #745). Stepper state: `lastDecidedPay` is compared STRUCTURALLY (a hash collided), the one-step
`pendingReceiptReprice` handoff is cleared at the top of the next step (a restored value can never
re-emit), and `lastAcceptResolvedAt` survives both the survivor's expiry and the mint. The projector
applies by (jobId, taskId) — itemization only on the receipt's sole drop (`soleDropOfReceipt`),
`payBasis` → `DROP_SHARE`, net against the row's own frozen cpm, a missing row is a counted skip —
no-ops when the row already matches, never overwrites a DRIVER-owned row
(`MANUAL`/`USER_CORRECTED`/`driverAdjustedAt`, Room v16); a later driver adjustment wins the pay while
`receiptRepricedAt` survives, and the drill-down discloses "re-priced from the receipt" whenever it is set.

**#1030 — `RecordFolds.reportedEarningsOf` is the rule's one owner:** a `totalEarnings` of `0.0` is a
report only when the end source is the summary screen; the stamp is `takeIf { > 0.0 }`, the fold
normalizes, `SessionEndedFields.totalEarnings` is nullable, and the DAO mirrors the source-keyed rule on
every `reportedEarnings` arm (a blanket `NULLIF` was rejected).

**Store entity resolution (#159, #773, #887, #1000):** pure `StoreResolver`/`StoreKeys`; deterministic
`storeKey = platform|normalizedChain|runningKey`; resolve-from-rows inside the batch transaction;
receipt key > address (`@`-prefixed street number) key > chain-only, monotonic upgrades only; a driver
correction pins (`storeKeyPinned`); superseded zero-reference identity rows are swept STRICTLY LAST
(fails toward keeping); a pickup-less job still stamps the exact offer link (`linkOfferToJobIfUnlinked`).
Per-store reads group on the resolved key.

**Corrections are append-only events**, folded non-destructively and rebuild-faithfully:
`MANUAL_DELIVERY`, `DELIVERY_ADJUSTMENT` (a machine row's `payBasis` → `USER_CORRECTED` iff pay changes,
a MANUAL row stays MANUAL; net recomputes only when pay/miles change, against the row's OWN frozen cpm;
`newCompletedAt` banned; new UI never writes legacy `PAY_ADJUSTMENT`), `DELIVERY_SESSION_ASSIGN`
(attribution ONLY — `sessionId` + `sessionAssigned`, no re-pricing — behind five fail-closed guards:
row exists, row is null-session or already-assigned, target is a real ENDED session, platform
coherence, no cash-bearing unassign; the session `deliveries` counter moves by a relative ±1; #660),
and `OFFER_OUTCOME_CORRECTION` / Tier-1 `JobAcceptMismatchResolver` writing `outcomeResolved` (#810 B2:
Tier 1 resolves only when EXACTLY one accepted offer is store-unaccounted and all others accounted —
anything else is INCONCLUSIVE → driver attestation; `EffectMap` emits `JOB_ACCEPT_MISMATCH` AFTER the
closing job's final `DELIVERY_COMPLETED` because the reconcile reads only earlier rows). The outcome,
funnel and list reads exclude a resolved orphan; `WorkGaps` deliberately KEEPS it (the accept is the
instant waiting ended). **Per-leg mileage (#688 B):** lifecycle odometer stamps fold into
`milesToStore`/`milesToDropoff` with claim-once store legs; `realizedMiles` becomes the leg SUM only when
`milesToDropoff != null` while session/period/IRS/CSV totals stay odometer-span-anchored; a driver
`newMiles` edit wins `realizedMiles`/net but the machine leg columns are never rewritten (provenance);
`session_records.legStateJson` keeps incremental ≡ refold. The **"(No session)" bucket** counts in gross and per-day (#660 piece 1).

**Read surfaces (the Analytics HUB's sources are all window-anchored — the Playbook's heatmap,
leaderboard and plan are deliberately LIFETIME under a declaring badge; one assembly path each):** `AnalyticsWindow` +
`PeriodBounds.of(window|period)` (#970; Monday weeks, fixed bounds for a paged window, selection persisted
as `AnalyticsWindowSelection`); pay mix + platform split through the same `assemble` (#973;
`bonuses & other` is the floored residue, zero coverage renders "not recorded"); Offers tab (#975;
`AnalyticsTab` order is declaration order, selection transient; estimate-vs-reality keeps the DAO's
unfiltered accepted-offer LEFT JOIN as the stated denominator, applies its exclusions in the pure factory —
null estimate, unlinked offer, a stack, missing realized net/minutes — and renders both bars as a MEAN of
per-offer rates; `MAX_OFFER_PAGE` 250); Home = "Today" (#977; `DayPlanner` over the lifetime
heatmap, `MIN_SAMPLED_HOURS` 5, `Recap →` writes the persisted selection before navigating); heatmap
Rate/Hours toggle + store leaderboard (#979, outlier ≥ 1.5× fleet median p50 dwell, ≥3 stores); **Weekly
Plan (#981)** — `HourOfWeekSampler` (per-day (date, hour) samples off the heatmap's two lifetime reads) + `WeeklyPlanner`
(median ranking with `MIN_SAMPLE_DAYS` 3 separate days per cell, ≥2 h/≤4 h runs, edits REPLAYED, every
weekday reported with a measured reason), `SavedWeeklyPlan` FROZEN at
save in the `weekly_plan` DataStore (a user artifact, deliberately NOT a Room table), `WeeklyPlanWorker`
grades Sunday 18:00 on its own channel 104 — planned vs actual INSIDE the planned windows only, money
outside reported separately, never folded in; Time tab (#983; `WorkGaps` pairs a completion with the next
accept in the same dash — never across dashes or days, an accept past the dash's effective end is
dropped, a dash's last drop is a tail not a gap, a gap ≥ 2 h is counted and STATED never excluded;
`sequenceId` for "which", timestamps for "how long"; while-working vs
whole-shift net/hr, null never `$0.00/hr`; `HourComposition` states coverage as a field); the **three
destinations (#1024)**: Home = today, the hub (Money · Offers · Time) = the past, the Playbook = the next
move, with `HowNumbersWorkFooter` as the one disclosure per screen and `PlanProgress` a VIEW of
`WeeklyPlanGrade`. Every §9 thin-data state states its reason in a named place. The free-tier **CSV
export** (#319) is a row-level read (merchant names exported, customer/address hashes excluded).

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
   a duplicated constant, or a parallel UI assembly is a divergence bug waiting to fire (the
   campaign receipts: #356 enabled-platform caches, #358 twin `formatDuration`s, #362 duplicated
   sha256, #357 drifted economy editors). When two surfaces need the same thing, extract one
   definition
   and point both at it; when a value can be computed from an owned anchor, compute it —
   don't store it twice.
6. **Security & privacy first.** The non-negotiable Pledges above (on-device computation,
   sensitive screens blocked at the matcher layer, opt-in network, edge PII scrub) are design
   inputs, not afterthoughts — every feature is measured against them before it ships. Working
   rules:
   - **Treat third-party UI and (eventually) downloaded rules as untrusted input.** The
     accessibility tree comes from another app; once the matchers split (#192) lands, rule JSON
     comes from a CDN. Both get bounded ingestion (size/depth/node/regex caps — for a rule regex
     that means length 200, repeat/depth caps, and the compiled program **measured** at
     `programSize()` ≤ 1 000, since a linear-time MATCH says nothing about COMPILE cost or
     match-time stack DEPTH and RE2J has no program-size ceiling), a **linear-time regex engine** so
     an accepted pattern's match time is bounded by construction rather than by a watchdog, an
     evaluation failure that is a distinguishable exception each boundary answers safely
     (**redaction masks the whole node**, never ships it raw), and the Perl classes translated at
     compile so device class semantics are **approximated** rather than silently narrowed to ASCII
     (residual differences listed — #1053 / ADR-0010, §2), fail-closed
     validation, and — for any remote rule source — **signature/integrity verification before
     compile** (#416, in-tree): `RulesetVerifier` (ECDSA P-256/SHA-256, no new crypto dep) gates
     `JsonRuleInterpreter.load`, which now accepts only a `VerifiedRulesetBytes` mintable solely by
     a passing signature check — compile-from-remote-bytes is structurally unreachable without
     verification against the *configured source's* pinned key; bundled assets are exempt (APK
     signature covers them). Signing tooling: `matchers/tools/`.
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
     capability key (#417): rule loads enumerate capabilities and publish them to the grant
     store *before* rules go live, but **grant NOTHING — there is no auto-grant (#843)**. Every
     capability, bundled (asset) OR remote, lands *undecided* (three states: granted / denied /
     undecided; only an explicit user act grants; the gate fires only on granted). Per Google
     Play policy the user opts into EACH automation individually. A dasher-pressed Accept/Decline
     is its own consent (integrity checks still apply). Consent is collected by a **prompt**
     (`ConsentPromptSheet`, #843 — the app's front door, joining the a11y/notification permission
     chain: fires on app foreground whenever `capabilities − granted − denied ≠ ∅`, one Allow /
     Don't-allow row per undecided capability, no "allow all"; "Not now" defers, a denial is
     durable) and reviewed/revoked in Settings → Data & Privacy → **Automation & Consent**
     (`CapabilityConsentScreen`/`ViewModel`, writing through `RuleCapabilityGrants.setGranted`);
     the settings screen is a consent *record*, never a second gate — enforcement stays at the
     `PerformRuleAction` seam, and a denial persists so a later load can't silently re-grant it
     (fail-closed). A one-shot schema migration (`RuleCapabilityDataSource`, #843) clears any
     pre-#843 auto-granted keys on upgrade (denials preserved) so the prompt re-collects consent.
   When a change touches recognition, capture, network, or effects, state its security/privacy
   posture in the PR — what's trusted, what's gated, what's scrubbed.
7. **Semantic, PII-safe logging.** Log levels carry *meaning*, not volume convenience, and the log is
   two products: an on-device **DEBUG firehose** for us, and an **INFO+ slice a user can export as a
   bug report** to send to the developer. Pairs with #6 (the export is a privacy surface). The
   receipt was a 110k-line dash log under one Timber tag (`App`), per-frame `SCREEN:` spam at INFO
   drowning the real WARNs, and raw merchant names already leaking into INFO+ lines. The level
   taxonomy:
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
   `"Effects"`…), never the catch-all `App`. The tag rule is **enforced by a ratchet guard**
   (#764, `TimberTagGuardTest` in `:app` unit tests): any new bare `Timber.i/w/e/wtf(` (incl. the
   `Timber.Forest.*` form) in a main-type source set fails the build; the frozen allowlist
   (`app/src/test/resources/timber-tag-guard-allowlist.txt`, 27 files as of #944) is the visible debt list —
   tag a file's sites, shrink its entry (counts dropping below the frozen number also fail, so the
   list only burns down). The INFO-must-be-PII-safe rule is **fail-closed and
   tested** (reuse `SensitiveTextMarkers`): a raw merchant/customer string in an INFO+ line is a
   privacy defect of the same class as leaking it to disk — gate the shareable-export sink behind that
   test, do not trust call-site discipline. When a change adds or moves a log site, state its level and
   (for INFO+) confirm it carries no raw PII. Shipped as two sinks in `LogRepository` (#551): the
   verbatim DEBUG firehose (`app.log`, on-device only) and the PII-safe INFO+ `shareable.log` a user
   exports as a bug report (Settings → Data & Privacy → Export Data), where every INFO+ line passes a
   **fail-closed scrub at the sink** — an injected `LogScrubber` bound to `SensitiveTextMarkers` (one
   marker SSOT, no `:core:data`→`:core:pipeline` edge), a hit/throw/unbound scrubber redacting to a
   `[scrubbed:<marker>]` placeholder rather than trusting call sites (the #590 replay gate patrols
   upstream; the sink is the last line).
8. **Platform-agnostic core.** The recognition→state→effects spine never encodes a specific gig
   platform. Recognition is data (per-platform rulesets), not code; platform identity resolves
   only through the `Platform` registry (`fromRuleId`/`fromPackage`/`fromWire`) — never a literal
   `== Platform.DoorDash`, hardcoded package name, or wire string gating logic. Anything that
   varies by platform — vocabulary, required parse fields, effect-bearing intents, grace timing,
   offer slots, lifecycle-edge anchors, learned rate models — is either ruleset data validated at
   load or state keyed by `Platform`, never a global tuned to whichever platform we field-test
   most. New rule↔state vocabulary goes through the enumerated, load-validated contract
   (`ParsedFieldsFactory.REQUIRED_FIELDS_BY_SHAPE`, plus `StateMachineContract`'s #762 `EFFECT_INTENTS`
   (effect-bearing notification intent → its required parse fields) and `REQUIRED_FIELDS_BY_FLOW`
   (deliberately empty today — every `task:*` flow has a legitimate parse-less rule), both enforced
   at compile by `RuleCompiler` as *declaration* checks, fail-loud per file; unknown intents stay
   informational by construction, so an OTA ruleset can't smuggle an effect), not a magic string
   consumed by a Kotlin `when`. The acceptance test: adding a platform means shipping a ruleset + corpus with **zero**
   `:core:state` / `:core:pipeline` / `:domain` edits. DoorDash-heavy field testing *masks*
   violations (a global slot never collides while only one platform runs), so this is enforced at
   PR-review time — see *Every PR gets a design-goal review* under Git Workflow, and the drift
   catalog #585 → #762 (both closed; D2 resolved as the phase-less `task:active` flow +
   per-platform accept grace, ADR-0002's 2026-07-15 amendment REJECTING a peer
   `TaskPhase.TRANSIT`; the corpus-gated Uber notification-flow enrichment lives on as #785),
   plus the per-platform ownership pack #438 for the known seams and receipts.

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
3. For **unknown screens**: read the X-Ray report, add or broaden a rule in the platform's JSON5
   source — the flat `matchers/rules/<platform>.json5` (uber) or the matching surface sub-file under
   `matchers/rules/<platform>/` (doordash, e.g. `pickup.json5`) — canonicalized/merged into generated
   assets by `:core:pipeline:importMatchersRules` (#635/#639), re-run.
4. For **sensitive screens**: manually redact the JSON, move to `snapshots/SENSITIVE/`, verify with
   `AllMatchersSuite` (the golden guard asserts every `SENSITIVE/` snapshot is caught by a
   sensitive rule or flagged toxic by `SnapshotSecurityScanner`).
5. Commit only the sorted files from their category folders (never from `INBOX/`).
6. New corpus changes the parse-output golden — regenerate it deliberately (next section, step 4)
   and commit `snapshots/approved-parse-output.json` together with the new snapshots.

**The intake tools MUTATE the corpus, and are excluded from a plain sweep (#941).**
`InboxProcessorTest` and `UnknownScreenAnalysisTest` redact/move/prune/delete files under
`snapshots/` as their normal operation, so `app/build.gradle.kts` excludes them from an UNFILTERED
`testDebugUnitTest` run (alongside the pre-existing `*Suite` exclusion) — "a sweep never rewrites
the corpus" is structural. Run them deliberately by name:
`./gradlew :app:testDebugUnitTest --tests "*InboxProcessorTest"`. Naming a test sets Gradle's
`commandLineIncludePatterns`, which turns the whole exclusion block off.

**Corpus reads fail LOUD (#941).** `TestResourceLoader.loadSnapshots` THROWS on an unparseable
file for every directory except the two *staging* areas (`INBOX/`, `UNKNOWN/`), where a malformed
raw pull is expected traffic — otherwise a corrupt committed fixture sits in git looking like
coverage while no test reads it. `snapshots/UNKNOWN/negative/` is committed corpus and therefore
strict: the leaf directory name decides.

**The pruner keeps the NEWEST, and never evicts an incumbent to admit a newcomer (#929/#941).**
`SnapshotLibrarian.pruneFolder` caps a folder at 15 distinct content variants. It sorts by the
capture timestamp *parsed out of the filename* (both conventions: legacy `20260128_155954_491_…`
and current `2026-07-17_18-08-53-720__…`, whose lexical orders are inverted against their real
chronology — PR #926 deleted 9 fixtures over exactly that). And when the folder was already at cap
**before** the run, the file the run just added is the one dropped: incumbents are never evicted to
make room (PR #800's capped-additions-only discipline). Below cap, a fresher
capture of identical content still replaces the stale one — that is the retention policy working.

**The negative corpus (`snapshots/UNKNOWN/negative/`, #941).** Over-match is the dangerous
recognition failure: under-match only drops a frame to UNKNOWN, but over-match *forges state*
(#857/#874/#875 claimed `modeHint: offline` from absence and split live dashes; #858 minted an
offer out of a "no longer available" banner). `NegativeCorpusStaysUnknownTest` (an
`AllMatchersSuite` member) asserts every committed frame there still classifies UNKNOWN under its
own platform partition, with a minimum-count floor so an emptied folder fails instead of
green-passing. To add one: sweep it for PII, keep the `__<platformWire>__` capture-naming token,
and drop it in. The parent `snapshots/UNKNOWN/` stays gitignored staging; `negative/` is
un-ignored by an explicit negation, and the subfolder placement is load-bearing — the flat
staging loaders list files, so they can never graduate or prune a committed negative frame.

**Adding or changing a recognition rule** (there are no matcher classes to register — rules are data):

1. Edit the platform's JSON5 rule source — the flat `matchers/rules/<platform>.json5` (uber) or the
   relevant surface sub-file under `matchers/rules/<platform>/` (doordash); `$schema` gives editor
   autocomplete/validation (the full `docs/rules.schema.json`, or `docs/rules.fragment.schema.json` for a
   sub-file). Golden-corpus folder name == expected intent.
2. Tests compile the **canonicalized production** rule files via `TestRulesetFactory` (which reads the
   generated `assets/rules/`, populated by `:core:pipeline:importMatchersRules`) — nothing else to wire up.
3. Run `InboxProcessorTest` (sorting) and then `AllMatchersSuite` (golden guard + parse-output
   golden + ruleset + classifier regressions) to verify.
4. If the change legitimately alters **parse output** (`ParseOutputGoldenTest` fails, #433):
   regenerate with `./gradlew :app:testDebugUnitTest --tests "*ParseOutputGoldenTest*"
   -DupdateParseGolden=true`, then **review the diff of `approved-parse-output.json`** — that
   review is the regression gate — and commit it with the rule change. The same test also
   ratchets corpus coverage (new intents should ship with corpus) and lints dedupeKey
   `{field}` templates against fields the rule actually parses. The coverage ratchet is keyed
   by **platform + intent** (#941) and measured by classifying the corpus inside each
   platform's rule partition — not by "a folder with that intent's name exists" — so a
   DoorDash-populated folder can no longer mark the same-named Uber intent covered.

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
`GhostOfferReplayTest` (Level A/B, #498) and `SingleDeliveryReplayTest` (click+timer injection:
accept-click → pickup → dropoff → complete, exactly one dropoff, #498/#503/#518) are the worked
examples, both green. Remaining frontier (on-device review tool, verdict export, eval-loopback net
economics, GoPuff multi-drop repro) is tracked under epic #505.

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
pile up on the remote.

```bash
gh pr merge <NUMBER> --merge --delete-branch
```

(The `gh` binary path and all project/field IDs are workstation-specific — they live in
`CLAUDE.local.md`, which is gitignored. Never hardcode workstation paths in this file.)

**Branch deletion is workstation-only. Field/remote agents MUST NOT attempt to delete their own
branch.** Agents in the remote execution environment (Claude Code on web/mobile) reach GitHub
through a local auth proxy that **refuses ref deletions** — every form of the delete hangs the
connection, then reports a misleading `Everything up-to-date`. The GitHub MCP server exposes no
branch/ref-delete tool and `merge_pull_request` has no delete-branch option, so there is **no
method available to a remote agent to delete a branch**. Do not run the delete in
any form and do not retry it — repeated failing attempts are just noise. After merging via MCP,
leave the branch and note in your final reply that it needs manual cleanup (the PR page's "Delete
branch" button, or the workstation agent's `gh pr merge … --delete-branch`).

**Every PR ships with context updates — no exceptions.** As part of preparing/merging ANY PR:

1. **Memories** — update the persistent memory (see *Claude Memory Upkeep*) to record what the
   PR changed and what it means for project status. Every merged PR changes status by
   definition, so this step always applies.
2. **CLAUDE.md** — re-check the sections the change touches (architecture, modules, workflows,
   commands, labels, principles); if the PR makes any statement in this file stale, fix it
   **in the same PR**.
3. **`docs/architecture/`** — the five per-layer reference files hold the receipt-level detail the
   §1–§5 summaries point at. A PR that changes an invariant, adds a rule, or retires one records it
   there (the summary here only if it goes stale). Keep this file's summaries SHORT — the context
   budget is why the detail moved (trim v3, 2026-09-07).

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

This loop moves detection from post-hoc audit to merge time — the audits kept finding drift PR
review could have caught (#356, #585).

**Then an adversarial review — an independent reviewer, not self-attestation (#589).** The
design-goal review above is the *author* walking their own diff, and self-review shares the
author's blind spots. So after it, and before
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
   the security property/fuzz suite (#590). **That suite is seeded (#878)** — every kotest property
   pins an explicit `PropSeeds.config(SEED)`, so PR CI draws the same samples on every run and a red
   property is a reproducible finding, never a re-run-and-move-on flake. Bump a site's `SEED`
   **deliberately** to explore new samples, NEVER to turn a red run green; the
   unseeded breadth lives behind `-Ddashbuddy.propExplore=true` (10× samples, off the PR path).

**Same-tier rule:** the adversarial reviewer runs at the **session model tier** (Mythos/Fable
class) — never downgraded to a lower tier. An adversary weaker than the author is theater; this is
the Subagent Model Policy applied to review. Record the outcome in a `### Adversarial review` block
in the PR description; fix findings in-PR or file + link them. A PR merges only once the adversarial
pass is clean or its findings are triaged.

**Docs-only / non-code PRs can skip CI.** The `pr-check.yml` workflow skips the
`build-and-test` job when the **PR description (body)** contains the literal
string **`[skip ci]`**. So for a PR that touches no compiled code (only
Markdown/docs), put `[skip ci]` in the PR body to avoid a pointless ~6-minute
build. Note the exact token is `[skip ci]` in the **body** — not `[no-ci]`, not a
comment, not a label. Only use it when the diff genuinely has no code; when in
doubt, let CI run. **Footgun (#902/#928):** the check is a bare `contains()`, so
a code PR whose body merely *describes* the token skips its own CI — either
paraphrase ("the skip-ci token") or add the override token **`[force ci]`** to
the body, which forces the run back on. And if Actions ever **refuses to
dispatch** runs for a PR's tree (the 2026-07-27 class — no run object across
shas/branches), use the `workflow_dispatch` escape hatch: Actions → PR Check →
Run workflow → enter the PR number; it builds that PR's merge ref and reports a
real `build-and-test` commit status onto the PR head, satisfying branch
protection without an admin override.

**PR CI gates `:app:lintVitalRelease` (#907).** `pr-check.yml` used to build/test only the
**debug** variant, so a release-only break (`ExtraTranslation` errors from the #98
`:feature:setup` extraction orphaning `values-es-rUS` translations in `:app`) shipped invisibly
and broke `./gradlew :app:build` on master. The `build-and-test` job now runs
`./gradlew :app:lintVitalRelease` after the unit-test step, and a source-scan guard
(`LocaleAllowlistGuardTest`, the `TimberTagGuardTest`/`IcuRegexGuardTest` doctrine)
enforces the dev's locale allowlist — translate into `en` (default)/`es`/`fr` only,
language-level, never a regional variant (`es-rUS`) — so the orphaned-translation class
can't recreate itself silently. `values-es` is intentionally incomplete (~84/393 keys)
until the translation-completion work lands; that completeness gate is deferred, not
forgotten.

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
planning over cheap plans), and the PR checks close the loop at the top tier. A well-vetted
plan with sharp boundaries is precisely what makes the build safely delegable, and the fable
check is what makes a cheap build trustworthy. **"Reserve fable" therefore means one thing:
don't burn the scarce tier on mechanical execution in the middle.** It does NOT mean skimping
on planning, design, vetting, or review. Never dip into usage credits or pay-by-token on the
developer's behalf (the 2026-06-25 mid-workflow session-limit collapse — 36 agents dead, all
spend wasted — is the receipt for budget awareness).

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
   *might* be is welcome inside the log entry. **What's not welcome is framing any of it as
   a concluded answer or a fix to apply.** Phrase everything as a **hypothesis**: "likely
   cause", "one possibility", "would need to confirm X by capturing Y". Never write
   "Proposed fix:" followed by a concrete action and never push code changes. The developer
   decides what's actually wrong and what to do about it; the log entry feeds that decision,
   it does not pre-empt it.
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