# 2026-06-11 — Post-campaign principles re-review (SSOT lens)

Re-review of the full codebase against the five Development Principles immediately after
the 27-issue audit campaign (#341–#367, PRs #368–#395), prompted by the addition of
**principle 5: Single Source of Truth** (PR #397). Seven module-scoped review agents,
findings verified against real file:line sites. Decided exceptions from the campaign
(documented in the relevant PRs/KDocs) were excluded by instruction.

**Headline: the campaign held.** No UDF, structured-concurrency, or architectural
regressions anywhere; `:core:location` and `:core:database` came back clean. What remains
is almost entirely the new SSOT lens finding what the other four principles couldn't see:
**~24 findings — 1 P1, 14 P2, 9 P3** — nearly all "two owners for one value" drift risks
rather than live bugs.

## P1 — fix promptly

| # | Site | Finding |
|---|------|---------|
| 1 | `NotificationPipeline.output()` | **Missing sensitive-fields gate.** AccessibilityPipeline drops `ParsedFields.SensitiveFields` before capture; the notification chain has no symmetric gate. No current rule emits a sensitive notification shape, but the pledge ("sensitive screens blocked at the matcher layer") should not depend on that staying true. One filter + one test. |

## P2 — SSOT drift risks (grouped by suggested fix)

**Scoring thresholds (one owner: `OfferEvaluator`)**
- `ScoringUtils.kt:9` `AWESOME = 70.0` must stay equal to `OfferEvaluator.ACCEPT_THRESHOLD = 70.0` — separate constants, no cross-reference.
- `FlowCardItem.kt:308-309` score-ring colors hardcode 70/30, duplicating `ACCEPT_THRESHOLD`/`DECLINE_THRESHOLD`.

**Config defaults (one owner: the domain config class)**
- `StrategyDataSource.kt:52-57` ↔ `OfferAutomationConfig.kt:12-18`: four threshold defaults (10.0/2.0/3.50/0.50) duplicated as literals.
- `StrategyDataSource.kt:44-47` ↔ `EvidenceConfig.kt:4-7`: four evidence flags duplicated.
- `AppPreferencesRepository.kt:107` `?: 3.50` ↔ `UserEconomy` gas-price default.
- `UserEconomy.kt:24` `vehicleMpg = 30.0` hardcodes `VehicleClass.SEDAN.defaultMpg`.

**Wire/sentinel tokens (need named constants)**
- `"offer_evaluated"`: written in `StateManagerV2.kt:178`, read in `FlowRegionStepper.kt:132` — no shared const.
- `"UNKNOWN"` target: 6+ raw literals across classifier, FrameGate, both pipelines.
- `"no_id"`: produced by `UiNode.kt:302`, filtered by `ObservationClassifier.kt:176` — undeclared contract.

**Display fallbacks (UI copy with multiple owners)**
- `"Unknown"` store sentinel: 6 EffectMap sites + `PlatformRegionStepper.kt:515` guard.
- `"Customer"` placeholder: `FlowCardItem.kt:220,476` + `EffectMap.kt:489,817`.
- `LiveCardBuilder.kt:70` vs `FlowCardMapper` Pickup path: live card falls back to "Unknown", completed card passes blank through — visible divergence.

**Duplicated logic**
- `RuleCompiler.deriveTargetFromId` ≡ `Ruleset.deriveIntentFromId` (character-identical).
- `TransformRegistry`: assertion names listed twice (`validate()` when-branches + `validateAssertionName` set).
- `SideEffectEngine`: LAZY-timer pattern hand-rolled twice (`ScheduleTimeout` branch + `scheduleTimeoutFromArgs`).
- `FlowCardItem.kt:287-290` ≡ `:414-417`: offer-expiry color tiers written twice in one file.

**Policy bypass**
- `PlatformRegionStepper.kt:607` reads `TransitionPolicy.DEFAULT_GRACE_MS` statically while `:188` uses the injected `policy` — an override would silently apply to one grace window and not the other.

**Locale (missed by #362's sweep)**
- `EpaVehicleDataSource.kt:61` `lowercase(Locale.getDefault())` and `VClassMap.kt:25` bare `lowercase()` normalize EPA wire strings — Turkish-I class.

**Shim**
- `OfferEvaluationEvent` is translated 1:1 into `Observation.Loopback(EvaluationResult)` by the bridge — the engine could emit the Loopback directly and the type + branch deleted.

## P3 — nits and stragglers

- **#364 leftovers**: dead `Dispatchers` imports in 5 data files; `TipEffectHandler` and `BubbleManager` still hardcode dispatchers/scopes (the audit's five-singleton list missed them); `LogRepository` still on `SimpleDateFormat` (consistency with DiskCaptureBus).
- **#361 leftovers**: dead coroutine imports in both pipeline orchestrators.
- Dead imports: `SettingsHomeScreen` `collectAsState`, `FlowCardItem` `sp` (verify at fix time).
- `completeActiveJob` dead `obs` param; `OdometerEffectHandler` notification id/channel as instance vals; EIA base URL inline vs EPA's named-const pattern; `BubbleScreen` hardcoded `10.sp`/`RoundedCornerShape(12.dp)` bypassing tokens.

## Suggested issue grouping (8 issues, not yet filed)

1. **fix(pipeline): sensitive gate on NotificationPipeline** — P1, tiny, test included.
2. **chore(ssot): scoring thresholds single-owner** — ScoringUtils + FlowCardItem ring reference evaluator consts.
3. **chore(ssot): config defaults single-owner** — DEFAULT_* companions on OfferAutomationConfig/EvidenceConfig/UserEconomy, datasources reference them.
4. **chore(ssot): wire-token constants** — `offer_evaluated`/`UNKNOWN`/`no_id` named consts in domain.
5. **chore(ssot): display-fallback unification** — "Unknown" store + "Customer" + the LiveCardBuilder/Mapper divergence.
6. **chore(rules): dedupe id-derivation + assertion list** — could ride #293.
7. **fix(network): Locale.ROOT on EPA normalization** (+ base-url const).
8. **chore: post-campaign stragglers sweep** — engine timer helper, TipEffectHandler/BubbleManager injection, TransitionPolicy threading (+dead param), dead imports, token nits.

## Verdict

Grade **A−** (was B at the 2026-06-10 audit). The four original principles are now
genuinely held across the repo; the SSOT additions are all small, mechanical, and
mostly about promoting literals to named, single-owner constants before they drift.
Nothing found contradicts a campaign fix; three findings are explicit misses of campaign
sweeps (#362 locale, #364 singleton list) — the same defect classes, new sites.
