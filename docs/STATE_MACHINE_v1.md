# State Machine v1.0 Reference

**Version:** 1.0  
**Date:** 2026-05-07  
**Baseline commit:** `eca14dd`  
**Rule format version:** 2  
**Engine version:** 1.0.0  
**Builds on:** ADR-0001, ADR-0002, ADR-0003, ADR-0004, ADR-0005, ADR-0006

This document is a point-in-time snapshot of everything the state machine supports:
every shape it can consume, every effect it can produce, every transition it can make.
Future versions will be tracked as `STATE_MACHINE_v{major}.{minor}.md`.

---

## 1. Architecture Overview

The state machine is a Harel-style multi-region statechart with three region tiers
that step independently per observation.

```
Layer 1 — Pipelines (accessibility, notification)
    │ emit Observation
    ▼
Layer 2 — Rule Engine (classify → flow + shape + actions)
    │ emit FlowObservation
    ▼
Layer 3 — State Machine (multi-region stepping)
    │ emit AppEffect[]
    ▼
Layer 4 — Side-Effect Engine (execute effects, loopback events)
```

### Region Architecture

```
Region 0 (Flow)          — Ground-truth screen interpretation
    ↓
Region 2+ (Platforms)    — Per-platform mode/session/job/task (with healing)
    ↓
Region 1 (CrossPlatform) — Derived aggregation across all platforms
```

**Stepping order:** R0 → R2+ → R1. No region writes into another.

### Event Merge

Three input streams are merged into a single processing loop:

1. **Pipeline events** — `Observation.Screen`, `Click`, `Notification` from
   accessibility and notification pipelines
2. **Engine loopback** — `Observation.Timeout`, `Loopback` from the side-effect
   engine (timer expiry, offer evaluation results)
3. **UI input** — `Observation.UiInput` from the bubble HUD

---

## 2. Supported Shapes

Shapes are the typed output of rule classification. Each rule declares a `"shape"`
string that `ParsedFieldsFactory` maps to a `ParsedFields` subtype. The state
machine consumes these via `Observation.FlowObservation.parsed`.

### 2.1 Gate Shapes (Dropped Before State Machine)

These shapes are intercepted by pipeline gates and never reach the state machine.

| Shape | Type | Pipelines | Fields | Purpose |
|---|---|---|---|---|
| `"sensitive"` | `SensitiveFields` | screen, notification | `activity?` | PII/security screens (banking, identity, payment). Pledge: never stored or forwarded. |
| `"noise"` | `NoiseFields` | screen, notification | `activity?` | Known-irrelevant signals. Surgically suppressed to reduce processing noise. |

### 2.2 Null Shape

| Shape | Type | Pipelines | Fields | Purpose |
|---|---|---|---|---|
| `"none"` | `None` | any | (none) | Explicit no-data. Also the fallback when shape is `null` or fields are empty. |

### 2.3 Screen Shapes

| Shape | Type | Fields | Purpose |
|---|---|---|---|
| `"idle"` | `IdleFields` | `zoneName: String?`, `sessionType: SessionType?`, `sessionPay: Double?`, `waitTimeEstimate: String?`, `isHeadingBackToZone: Boolean`, `spotSaveDeadline: Long?` | Driver waiting for offers on the idle map. |
| `"offer"` | `OfferFields` | `parsedOffer: ParsedOffer` (contains `offerHash`, `payAmount`, `distanceMiles`, `dueByTimeText`, `dueByTimeMillis`, `itemCount`, `orders[]`) | Offer popup presented. Each order carries `storeName`, `itemCount`, `orderType`, `badges[]`. |
| `"task"` | `TaskFields` | `phase: TaskPhase`, `subFlow: TaskSubFlow`, `storeName: String?`, `storeAddress: String?`, `customerNameHash: String?`, `customerAddressHash: String?`, `deadline: ParsedTime?`, `itemCount: Int?`, `redCardTotal: Double?`, `arrivalConfirmed: Boolean` | Active delivery segment. Covers all four task flows via phase/subFlow discriminators. |
| `"post_task"` | `PostTaskFields` | `totalPay: Double`, `appPay: Double?`, `customerTips: Double?`, `parsedPay: ParsedPay?` (line items), `isExpanded: Boolean`, `expandButtonId: String?`, `sessionEarnings: Double?`, `offersAccepted: Int?`, `offersTotal: Int?` | Delivery completion summary. Pay breakdown available when expanded. |
| `"session_ended"` | `SessionEndedFields` | `totalEarnings: Double`, `sessionDurationMillis: Long?`, `offersAccepted: Int?`, `offersTotal: Int?`, `weeklyEarnings: Double?` | End-of-shift summary screen. |
| `"paused"` | `PausedFields` | `remainingText: String`, `remainingMillis: Long` | Session paused with countdown timer. |
| `"timeline"` | `TimelineFields` | `sessionEarnings: Double?`, `offerEarnings: Double?`, `endsAtText: String?`, `endsAtMillis: Long?`, `tasks: List<TimelineTaskEntry>` | Timeline/dash-controls overlay. Each task entry has `taskType`, `nameHash`, `deadline`, `storeHint`, `isCurrent`. |
| `"ratings"` | `RatingsFields` | `acceptanceRate: Double?`, `completionRate: Double?`, `onTimeRate: Double?`, `customerRating: Double?`, `deliveriesLast30Days: Int?`, `lifetimeDeliveries: Int?`, `originalItemsFoundRate: Double?`, `totalItemsFoundRate: Double?`, `substitutionIssuesRate: Double?`, `itemsWithQualityIssuesRate: Double?`, `itemsWrongOrMissingRate: Double?`, `lifetimeShoppingOrders: Int?` | Driver ratings view. Includes both delivery and shopping metrics. |

### 2.4 Click Shape

| Shape | Type | Fields | Purpose |
|---|---|---|---|
| `"click"` | `ClickFields` | `intent: String`, `nodeId: String?`, `nodeText: String?` | Classified UI tap. Intent values: `"accept_offer"`, `"decline_offer"`, `"arrived_at_store"`, etc. |

### 2.5 Notification Shape

| Shape | Type | Fields | Purpose |
|---|---|---|---|
| `"notification"` | `NotificationFields` | `intent: String`, `amount: Double?`, `storeName: String?`, `deliveredAt: String?`, `rawText: String?` | Classified notification event. Intent values: `"additional_tip"`, `"new_order"`, `"scheduled_dash_expired"`. |

### 2.6 Common Fields

All shapes inherit `activity: String?` — a free-typed platform tag for sub-classification
within a flow (e.g., `"shopping"`, `"scanning_card"`).

All shapes expose `dedupeHash(): Int` for post-classification deduplication. Identity fields
are included; transient/ticking fields are excluded.

---

## 3. Supported Flows

The `Flow` enum is the primary state-machine input axis. Rules declare `"flow"` to signal
which lifecycle phase the driver is in.

| Flow Enum | Wire Value | TaskPhase | TaskSubFlow | Associated Shape |
|---|---|---|---|---|
| `Idle` | `"idle"` | — | — | `idle` |
| `OfferPresented` | `"offer:presented"` | — | — | `offer` |
| `TaskPickupNavigation` | `"task:pickup:navigation"` | `PICKUP` | `NAVIGATION` | `task` |
| `TaskPickupArrived` | `"task:pickup:arrived"` | `PICKUP` | `ARRIVED` | `task` |
| `TaskDropoffNavigation` | `"task:dropoff:navigation"` | `DROPOFF` | `NAVIGATION` | `task` |
| `TaskDropoffArrived` | `"task:dropoff:arrived"` | `DROPOFF` | `ARRIVED` | `task` |
| `PostTask` | `"post:task"` | — | — | `post_task` |
| `SessionEnded` | `"session:ended"` | — | — | `session_ended` |

Shapes that don't map to flows (`paused`, `timeline`, `ratings`) are classified without
a `flow` value and contribute `modeHint` or contextual data without changing the flow
region.

---

## 4. Supported Modes

The `Mode` enum is the orthogonal availability axis. Mode lives on the platform region
(R2+), not globally. Mode is **inferred** from flow + modeHint, never declared directly
by rules.

| Mode | Wire | Meaning |
|---|---|---|
| `Offline` | `"offline"` | Not logged in or session ended |
| `Online` | `"online"` | Actively working — waiting, delivering, post-task |
| `Paused` | `"paused"` | Explicitly paused by worker |

---

## 5. Supported Platforms

Each platform gets its own R2+ region with independent mode, session, and healing state.

| Platform | Wire | Package Name | Rule Coverage |
|---|---|---|---|
| `DoorDash` | `"doordash"` | `com.doordash.driverapp` | Full (screens, clicks, notifications) |
| `Uber` | `"uber"` | `com.ubercab.driver` | Partial (offer screen, clicks) |
| `Instacart` | `"instacart"` | `com.instacart.shopper` | Declared, no rules yet |
| `WalmartSpark` | `"walmart_spark"` | `com.walmart.spark` | Declared, no rules yet |
| `Unknown` | `"_unknown"` | `null` | Fallback for unrecognized sources |

Platform is derived from `ruleId` — e.g., `doordash.screen.offer` → `DoorDash`.

---

## 6. Observation Types (State Machine Inputs)

### 6.1 Flow Observations (Carry State Contributions)

| Type | Source | Carries | Description |
|---|---|---|---|
| `Observation.Screen` | Accessibility window pipeline | `flow`, `modeHint`, `parsed`, `target`, `actions` | Classified screen from the UI accessibility tree. |
| `Observation.Click` | Accessibility click pipeline | `flow`, `modeHint`, `parsed`, `target`, `actions`, `screenTarget` | Classified tap/click event. Includes the screen context. |
| `Observation.Notification` | Notification pipeline | `flow`, `modeHint`, `parsed`, `target`, `actions` | Classified status bar notification. |

### 6.2 Control Observations (No Flow/Mode Contribution)

| Type | Source | Payload | Description |
|---|---|---|---|
| `Observation.Timeout` | Side-effect engine timer | `type: TimeoutType` | Timer expiration event. |
| `Observation.UiInput` | Bubble HUD | `action: String`, `payload: Map` | Manual user interaction from the overlay. |
| `Observation.Loopback` | Side-effect engine | `effect: String`, `payload: Map` | Feedback from effect execution (e.g., `"offer_evaluated"`). |

### 6.3 Timeout Types

| TimeoutType | Purpose | Default Duration |
|---|---|---|
| `SESSION_PAUSED_SAFETY` | Force offline after extended pause | Configured per-platform |
| `RETRY_CLICK` | Retry an automated click action | Short delay |
| `SETTLE_UI` | Wait for UI to stabilize after interaction | Short delay |
| `DECLINE_POPUP_WAIT` | Wait for decline confirmation popup | Short delay |
| `SCREENSHOT_WAIT` | Delay before capturing screenshot | Short delay |

---

## 7. AppState Structure (Region Data)

### 7.1 Top-Level Container

```
AppState
├── regions: Regions
│   ├── flow: FlowRegion              (R0)
│   ├── crossPlatform: CrossPlatformRegion  (R1)
│   └── platforms: Map<Platform, PlatformRegion>  (R2+)
├── timestamp: Long
└── correlationVersion: Long          (monotonic, incremented per observation)
```

### 7.2 Region 0 — FlowRegion

What the driver is looking at right now. Updates immediately on every observation.

| Field | Type | Description |
|---|---|---|
| `flow` | `Flow` | Current flow (default: `Idle`) |
| `pendingOffer` | `PendingOffer?` | Offer stack automaton — set when offer presented, cleared on resolution |
| `sourceRuleId` | `String?` | Rule that classified the current observation |
| `activePlatform` | `Platform?` | Platform of the current observation |
| `lastObservedAt` | `Long` | Timestamp of last observation |

**PendingOffer:**

| Field | Type | Description |
|---|---|---|
| `offerHash` | `String` | Unique offer identity |
| `offerFields` | `ParsedFields.OfferFields` | Parsed offer data |
| `presentedAt` | `Long` | When the offer was first seen |
| `evaluation` | `OfferEvaluation?` | Filled async by the offer evaluator |
| `returnFlow` | `Flow` | Flow to restore on decline/timeout |
| `lastClickIntent` | `String?` | `"accept_offer"` or `"decline_offer"` |

### 7.3 Region 2+ — PlatformRegion

Per-platform durable state. One instance per active platform.

| Field | Type | Description |
|---|---|---|
| `platform` | `Platform` | Which platform |
| `mode` | `Mode` | Current mode (default: `Offline`) |
| `session` | `Session?` | Active session (null when offline) |
| `activeJob` | `Job?` | Current job (offer → tasks → completion) |
| `activeTask` | `Task?` | Current task segment |
| `recentTasks` | `List<Task>` | Completed tasks in current session |
| `confidence` | `ModeConfidence` | Healing tracker for implausible transitions |
| `zoneName` | `String?` | Current zone |
| `sessionType` | `SessionType?` | `PerOffer` or `ByTime` |
| `ratings` | `RatingsSnapshot?` | Driver ratings snapshot |
| `surgeMultiplier` | `Double?` | Surge pricing |
| `lastPostTaskPayHash` | `Int?` | Dedup for payout screen |
| `lastObservedAt` | `Long` | Timestamp |

**Session:**

| Field | Type | Description |
|---|---|---|
| `sessionId` | `String` | Unique session identity |
| `startedAt` | `Long` | When session began |
| `earningMode` | `SessionType?` | Per-offer or by-time |
| `runningEarnings` | `Double` | Accumulated earnings |
| `runningMiles` | `Double` | Accumulated miles |
| `accumulatedDeliveryPay` | `Double` | Sum of delivery pay |

**Job:**

| Field | Type | Description |
|---|---|---|
| `jobId` | `String` | Unique job identity |
| `offerStoreHint` | `List<String>` | Store names from the offer (hints, not authoritative) |
| `parentOfferHash` | `String?` | Null if recovered without seeing offer |
| `tasks` | `List<Task>` | Tasks within this job |
| `startedAt` | `Long` | When job began |

**Task:**

| Field | Type | Description |
|---|---|---|
| `taskId` | `String` | Unique task identity |
| `jobId` | `String` | Parent job |
| `phase` | `TaskPhase` | `PICKUP` or `DROPOFF` |
| `storeName` | `String?` | Authoritative store name (from task observation) |
| `customerNameHash` | `String?` | Hashed customer name |
| `customerAddressHash` | `String?` | Hashed customer address |
| `deadlineMillis` | `Long?` | Delivery deadline |
| `activity` | `String?` | Platform-specific sub-activity |
| `itemCount` | `Int?` | Number of items |
| `redCardTotal` | `Double?` | Red Card payment amount |
| `arrivedAt` | `Long?` | Set once on arrival, never overwritten |
| `odometerAtEntry` | `Double?` | Miles at task start |
| `odometerAtArrival` | `Double?` | Miles at arrival |
| `startedAt` | `Long` | When task began |
| `completedAt` | `Long?` | When task completed |
| `recovered` | `Boolean` | True if synthesized by healing (app launched mid-task) |

### 7.4 Region 1 — CrossPlatformRegion

Derived, read-only. Recomputed from R2+ after every step.

| Field | Type | Description |
|---|---|---|
| `anyPlatformOnline` | `Boolean` | Any platform has mode != Offline |
| `activeSessionCount` | `Int` | Count of platforms with active session |
| `totalsToday` | `PeriodTotals` | Aggregated earnings/miles/deliveries/jobs today |
| `totalsThisWeek` | `PeriodTotals` | Aggregated this week |
| `totalsLifetime` | `PeriodTotals` | Aggregated lifetime |
| `mostRecentActivityAt` | `Long` | Timestamp of latest activity |
| `mostRecentActivityPlatform` | `Platform?` | Platform with latest activity |

**PeriodTotals:**

| Field | Type | Description |
|---|---|---|
| `earnings` | `Double` | Total earnings |
| `miles` | `Double` | Total miles |
| `deliveries` | `Int` | Number of deliveries |
| `jobs` | `Int` | Number of jobs |
| `onlineDuration` | `Long` | Total time online (ms) |

---

## 8. Mode Transition Table

### 8.1 Plausibility Rules

| From | To | Plausible? | Condition |
|---|---|---|---|
| `Online` | `Paused` | Yes | Explicit pause screen observed |
| `Online` | `Offline` | Only via `SessionEnded` | Requires `flow == SessionEnded` |
| `Paused` | `Online` | Yes | Any active screen observed |
| `Paused` | `Offline` | Yes | Session timeout/end |
| `Offline` | `Online` | **No** (heal) | Could be app restart mid-task |
| `Offline` | `Paused` | **No** (heal) | Inconsistent — shouldn't happen |

### 8.2 Healing Policy

Implausible transitions are **not rejected** — they accrue confidence and heal:

- **Threshold:** 2 supporting observations within 10s time window
- **OR** 1 high-weight signal (explicit mode-defining screen like offer or pause)
- When threshold is met: force the transition and synthesize missing state
  (e.g., create Job + Task with `recovered = true`)

### 8.3 Mode Inference from Flow

| Flow | Implied Mode |
|---|---|
| `OfferPresented` | `Online` |
| `TaskPickupNavigation` | `Online` |
| `TaskPickupArrived` | `Online` |
| `TaskDropoffNavigation` | `Online` |
| `TaskDropoffArrived` | `Online` |
| `PostTask` | `Online` |
| `SessionEnded` | `Offline` |
| `Idle` | Ambiguous (no mode signal) |

Explicit `modeHint` from rule always takes precedence over flow-inferred mode.

---

## 9. Effects (State Machine Outputs)

Effects are produced by `EffectMap.diff()` which diffs before/after state per region.

### 9.1 Persistence Effects

| Effect | Fields | Key | Description |
|---|---|---|---|
| `LogEvent` | `event: AppEventEntity` | `"log:{type}:{time}"` | Append event to database. |

### 9.2 UI Effects

| Effect | Fields | Key | Description |
|---|---|---|---|
| `UpdateBubble` | `text`, `persona: ChatPersona`, `expand: Boolean` | — | Post message to bubble HUD notification. |
| `PlayNotificationSound` | — | — | Play alert sound. |

### 9.3 Capture Effects

| Effect | Fields | Key | Description |
|---|---|---|---|
| `CaptureScreenshot` | `filenamePrefix`, `metadata?` | — | Take screenshot via accessibility service, save to gallery. |

### 9.4 Timer Effects

| Effect | Fields | Key | Description |
|---|---|---|---|
| `ScheduleTimeout` | `durationMs`, `type: TimeoutType` | — | Schedule a timer that produces `Observation.Timeout` on expiry. |
| `CancelTimeout` | `type: TimeoutType` | — | Cancel a scheduled timer. |

### 9.5 Odometer Effects

| Effect | Fields | Key | Description |
|---|---|---|---|
| `StartOdometer` | — | — | Begin GPS mileage tracking. Creates foreground notification. |
| `StopOdometer` | — | — | Stop GPS tracking. Removes notification. |
| `PauseOdometer` | — | — | Pause GPS while stationary. Session total preserved. |
| `ResumeOdometer` | — | — | Resume GPS after stationary pause. |

### 9.6 Offer Effects

| Effect | Fields | Key | Description |
|---|---|---|---|
| `EvaluateOffer` | `parsedOffer: ParsedOffer` | — | Run offer through evaluator. Result loops back as `Loopback("offer_evaluated")`. |
| `SpeakOffer` | `parsedOffer`, `platformName` | — | TTS announcement: "{platform} offer. ${pay}. {store}. {distance} miles. {dueBy}." |

### 9.7 Automation Effects (ADR-0006)

| Effect | Fields | Key | Description |
|---|---|---|---|
| `ClickNode` | `node: UiNode`, `description` | — | Click a UI element by resolving against live accessibility tree. |
| `RequestAction` | `action: RequestedAction` | `"action:{ruleId}:{dedupeKey}"` | Rule-originated click with throttling and gate conditions. |

### 9.8 Notification Effects

| Effect | Fields | Key | Description |
|---|---|---|---|
| `ProcessTipNotification` | `amount`, `storeName`, `deliveredAt` | — | Post tip notification to bubble. |

### 9.9 Session Effects

| Effect | Fields | Key | Description |
|---|---|---|---|
| `StartDash` | `dashId: String` | `"start_dash:{id}"` | Mark session start. |
| `EndDash` | — | — | Mark session end. |

### 9.10 Composition Effects

| Effect | Fields | Key | Description |
|---|---|---|---|
| `Delayed` | `delayMs`, `effect: AppEffect` | — | Execute wrapped effect after delay. |
| `SequentialEffect` | `effects: List<AppEffect>` | — | Execute effects in order. |

### 9.11 Idempotency

Effects with a non-null `effectKey` are checked against the `effects_fired` table during
crash-recovery replay to prevent duplicate execution. External effects (`UpdateBubble`,
`CaptureScreenshot`, `ClickNode`, etc.) are unconditionally suppressed during recovery.
Loopback effects are replayed deterministically.

---

## 10. Effect Emission Triggers

When `EffectMap.diff()` detects a region change, it emits the following effects:

### 10.1 Flow Region Triggers

| Trigger | Effects Emitted |
|---|---|
| Offer presented (new hash) | `LogEvent(OFFER_RECEIVED)`, `CaptureScreenshot`, `EvaluateOffer`, `SpeakOffer` |
| Offer replaced (different hash) | Resolve old offer + all "presented" effects for new |
| Offer resolved (left OfferPresented) | `LogEvent(ACCEPTED/DECLINED/TIMEOUT)`, conditional `UpdateBubble` |
| Click on offer button | `UpdateBubble("Offer Accepted"/"Offer Declined")` |

### 10.2 Platform Region Triggers

| Trigger | Effects Emitted |
|---|---|
| Session start (Offline/Paused → Online, new session) | `LogEvent(DASH_START)`, `StartOdometer`, `StartDash` |
| Session resume (Paused → Online) | `CancelTimeout(SESSION_PAUSED_SAFETY)` |
| Session pause (Online → Paused) | `ScheduleTimeout(SESSION_PAUSED_SAFETY)`, `UpdateBubble("Dash Paused!")` |
| Session end (→ Offline) | `LogEvent(DASH_STOP)`, `CaptureScreenshot`, `StopOdometer`, `UpdateBubble`, `EndDash` |
| Task started (PICKUP) | `LogEvent(PICKUP_NAV_STARTED)`, `ResumeOdometer`, `UpdateBubble` |
| Task phase change (PICKUP → DROPOFF) | `LogEvent(PICKUP_CONFIRMED)`, `ResumeOdometer`, `UpdateBubble` |
| Arrival detected | `PauseOdometer`, `LogEvent(PICKUP_ARRIVED)` (if pickup phase) |
| Store/activity update | `UpdateBubble` with new info |

### 10.3 PostTask Triggers

| Trigger | Effects Emitted |
|---|---|
| Expanded pay data | `UpdateBubble` with receipt text and tip breakdown |
| Leaving PostTask | `LogEvent(DELIVERY_COMPLETED)` |

### 10.4 Notification Triggers

| Trigger | Effects Emitted |
|---|---|
| Tip notification (intent: `additional_tip`) | `ProcessTipNotification`, `LogEvent(NOTIFICATION_RECEIVED)` |
| Other classified notification | `LogEvent(NOTIFICATION_RECEIVED)` |

### 10.5 Rule-Originated Actions

| Trigger | Effects Emitted |
|---|---|
| Observation carries `actions` list (ADR-0006) | `RequestAction` per action that passes its gate |

---

## 11. Rule Types

### 11.1 Screen Rules

Classify the accessibility UI tree into a flow + shape.

```json
{
  "id": "doordash.screen.offer",
  "priority": 20,
  "shape": "offer",
  "state": { "flow": "offer:presented", "modeHint": "online" },
  "require": { /* predicate tree */ },
  "parse": { /* field extraction */ },
  "actions": [ /* optional ADR-0006 actions */ ]
}
```

**Current DoorDash screen rules:** sensitive, timeline, offer, post_task (2 variants),
paused, task (8 variants covering pickup/dropoff nav/arrived), ratings, idle (4 variants),
session_ended, sensitive (earnings).

**Current Uber screen rules:** offer.

### 11.2 Click Rules

Classify tap events into an intent.

```json
{
  "id": "doordash.click.accept_offer",
  "priority": 10,
  "if": { "hasIdSuffix": "accept_button" }
}
```

**Current DoorDash click rules:** `accept_offer`, `decline_offer`, `arrived_at_store`.

**Current Uber click rules:** `accept_offer`, `go_online`, `go_offline`.

### 11.3 Notification Rules

Classify status bar notifications with optional field extraction.

```json
{
  "id": "doordash.notification.additional_tip",
  "priority": 10,
  "if": { "anyFieldMatchesRegex": "..." },
  "extract": { "amount": { "fromGroup": 1, "transform": "parseDouble" } }
}
```

**Current DoorDash notification rules:** `additional_tip` (with extraction),
`new_order`, `scheduled_dash_expired`.

**Current Uber notification rules:** none.

---

## 12. Rule-Originated Actions (ADR-0006)

Rules can declare `"actions"` blocks that ride on observations through the state machine
and are executed as effects by the side-effect engine.

### 12.1 Supported Verbs

| Verb | Description |
|---|---|
| `"click"` | Click a UI element identified by `targetRef` |

(Only verb currently supported.)

### 12.2 Gate Conditions

Actions fire only if their `onlyIf` gate passes against the observation's parsed fields:

| Gate Type | Description |
|---|---|
| `FieldEquals(field, value)` | Field equals expected value |
| `FieldNotEquals(field, value)` | Field does not equal value |
| `FieldNotNull(field)` | Field is present and non-null |

### 12.3 Throttling

- Default throttle: 500ms per `effectKey`
- `dedupeKey` can be specified per-action to customize the throttle key
- Without `dedupeKey`, the `targetRef.pathFingerprint` is used

---

## 13. Logged Event Types

All database-persisted events via `LogEvent`:

### Session Lifecycle
`DASH_START`, `DASH_PAUSED`, `DASH_STOP`, `ZONE_SWITCH`, `NOTIFICATION_RECEIVED`

### Offer Phase
`OFFER_RECEIVED`, `OFFER_ACCEPTED`, `OFFER_DECLINED`, `OFFER_TIMEOUT`

### Pickup Phase
`PICKUP_NAV_STARTED`, `PICKUP_ARRIVED`, `PICKUP_CONFIRMED`

### Delivery Phase
`DELIVERY_NAV_STARTED`, `DELIVERY_ARRIVED`, `DELIVERY_COMPLETED`

### System
`SCREEN_VIEWED`, `ERROR_OCCURRED`

---

## 14. Bubble HUD Personas

Effects that post to the bubble use typed personas:

| Persona | ID | Display Name | Use |
|---|---|---|---|
| `Dispatcher` | `bot_dispatcher` | "Dispatch" | General state updates |
| `System` | `bot_system` | "System" | System messages |
| `Dasher` | `dasher_self` | "You" | User actions |
| `Merchant(name)` | `merchant_*` | Store name | Store-related updates |
| `Customer(name)` | `customer_*` | Customer name | Customer-related updates |
| `GoodOffer` | `good_offer` | "Good Offer" | Recommended offers |
| `BadOffer` | `bad_offer` | "Bad Offer" | Below-threshold offers |
| `Inspector` | `inspector` | "Inspector" | Debug/diagnostic messages |
| `Navigator` | `navigator` | "Navigator" | Navigation updates |
| `Shopper` | `shopper` | "Shopper" | Shopping task updates |
| `Earnings` | `earnings` | "Earnings" | Earnings summaries |

---

## 15. Pipeline Configuration

Rule files declare pipeline capabilities in their header:

```json
{
  "format_version": 2,
  "engine_version": "1.0.0",
  "platform_id": "doordash.driver",
  "pipelines": {
    "accessibility.window": { "version": 1 },
    "accessibility.click": { "version": 1 },
    "notification": { "version": 1, "allowOngoing": true }
  },
  "state_machine": {
    "api_version_major": 1,
    "api_version_minor": 0
  }
}
```

| Pipeline | Version | Description |
|---|---|---|
| `accessibility.window` | 1 | Screen classification from UI tree |
| `accessibility.click` | 1 | Click/tap classification |
| `notification` | 1 | Notification classification |

**`allowOngoing`**: When `true`, notifications with `isOngoing=true` (e.g., Uber trip
notifications) are captured and forwarded instead of being filtered as foreground-service
spam.

---

## Cross-References

- **ADR-0001** — Matcher rule format, predicate vocabulary, parse DSL
- **ADR-0002** — Cross-platform state taxonomy, flow/mode vocabulary
- **ADR-0003** — Four-layer versioning, loader compatibility checks
- **ADR-0004** — Canonical pipeline architecture
- **ADR-0005** — Pipeline-driven multi-region state architecture (this document's design spec)
- **ADR-0006** — Rule-originated UI actions
- **ADR-0007** — Canonical domain schema for offer evaluation

## Source Files

| Area | Path |
|---|---|
| State machine | `app/.../state/StateMachine.kt` |
| Flow stepper | `app/.../state/FlowRegionStepper.kt` |
| Platform stepper | `app/.../state/PlatformRegionStepper.kt` |
| Cross-platform stepper | `app/.../state/CrossPlatformRegionStepper.kt` |
| Healing policy | `app/.../state/HealingPolicy.kt` |
| Effect map | `app/.../state/EffectMap.kt` |
| AppEffect | `app/.../state/AppEffect.kt` |
| StateManagerV2 | `app/.../state/StateManagerV2.kt` |
| ParsedFields | `domain/.../state/ParsedFields.kt` |
| ParsedFieldsFactory | `app/.../rules/ParsedFieldsFactory.kt` |
| Flow enum | `domain/.../state/Flow.kt` |
| Mode enum | `domain/.../state/Mode.kt` |
| Platform enum | `domain/.../state/Platform.kt` |
| AppState | `domain/.../state/AppState.kt` |
| FlowRegion | `domain/.../state/FlowRegion.kt` |
| PlatformRegion | `domain/.../state/PlatformRegion.kt` |
| CrossPlatformRegion | `domain/.../state/CrossPlatformRegion.kt` |
| Observation | `domain/.../pipeline/Observation.kt` |
| RequestedAction | `domain/.../pipeline/RequestedAction.kt` |
| DoorDash rules | `app/src/main/assets/rules/doordash.json` |
| Uber rules | `app/src/main/assets/rules/uber.json` |
