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

## 2. Type Definitions

Types referenced throughout this document. Defined in the `:domain` module.

### `Flow`

The primary state-machine input axis. Rules declare `"flow"` to signal which
lifecycle phase the driver is in.

| Enum | Wire | Description |
|---|---|---|
| `Idle` | `"idle"` | Waiting for offers on the idle map |
| `OfferPresented` | `"offer:presented"` | An offer popup is on screen |
| `TaskPickupNavigation` | `"task:pickup:navigation"` | Navigating to pickup location |
| `TaskPickupArrived` | `"task:pickup:arrived"` | Arrived at the store, waiting for order |
| `TaskDropoffNavigation` | `"task:dropoff:navigation"` | Navigating to delivery location |
| `TaskDropoffArrived` | `"task:dropoff:arrived"` | Arrived at customer, completing delivery |
| `PostTask` | `"post:task"` | Viewing delivery completion summary / payout screen |
| `SessionEnded` | `"session:ended"` | End-of-shift summary screen |

Shapes that don't map to flows (`paused`, `timeline`, `ratings`) are classified
without a `flow` value and contribute `modeHint` or contextual data without
changing the flow region.

| Flow | TaskPhase | TaskSubFlow | Associated Shape |
|---|---|---|---|
| `Idle` | — | — | `idle` |
| `OfferPresented` | — | — | `offer` |
| `TaskPickupNavigation` | `PICKUP` | `NAVIGATION` | `task` |
| `TaskPickupArrived` | `PICKUP` | `ARRIVED` | `task` |
| `TaskDropoffNavigation` | `DROPOFF` | `NAVIGATION` | `task` |
| `TaskDropoffArrived` | `DROPOFF` | `ARRIVED` | `task` |
| `PostTask` | — | — | `post_task` |
| `SessionEnded` | — | — | `session_ended` |

### `Mode`

The orthogonal availability axis. Mode lives on the platform region (R2+), not
globally. Mode is **inferred** from flow + modeHint, never declared directly by rules.

| Enum | Wire | Description |
|---|---|---|
| `Offline` | `"offline"` | Not logged in or session ended |
| `Online` | `"online"` | Actively working — waiting, delivering, post-task |
| `Paused` | `"paused"` | Explicitly paused by the worker |

### `Platform`

Each platform gets its own R2+ region with independent mode, session, and healing.

| Enum | Wire | Package | Rule Coverage |
|---|---|---|---|
| `DoorDash` | `"doordash"` | `com.doordash.driverapp` | Full (screens, clicks, notifications) |
| `Uber` | `"uber"` | `com.ubercab.driver` | Partial (offer screen, clicks) |
| `Instacart` | `"instacart"` | `com.instacart.shopper` | Declared, no rules yet |
| `WalmartSpark` | `"walmart_spark"` | `com.walmart.spark` | Declared, no rules yet |
| `Unknown` | `"_unknown"` | — | Fallback for unrecognized sources |

Platform is derived from `ruleId` — e.g., `doordash.screen.offer` → `DoorDash`.

### `TaskPhase`

Which phase of a task the worker is in. A Task is a single segment: one
location, one purpose.

| Enum | Description |
|---|---|
| `PICKUP` | Heading to or at the merchant to collect the order |
| `DROPOFF` | Heading to or at the customer to deliver the order |

### `TaskSubFlow`

Whether the worker is navigating to or has arrived at the task location.

| Enum | Description |
|---|---|
| `NAVIGATION` | In transit to the task location |
| `ARRIVED` | Physically at the task location |

### `SessionType`

The earning mode for a session.

| Enum | Display Name | Description |
|---|---|---|
| `PerOffer` | `"Earn per Offer"` | Standard pay-per-delivery mode |
| `ByTime` | `"Earn by Time"` | Hourly/time-based earning mode |

### `ParsedTime`

A time value extracted from the UI, preserving both the display text and
the computed epoch milliseconds.

| Field | Type | Description |
|---|---|---|
| `text` | `String` | Raw display text (e.g., `"8:52 PM"`, `"34:15"`) |
| `time` | `Long?` | Computed epoch milliseconds. Null if parsing failed |

### `ParsedOffer`

The full parsed representation of a delivery offer. Wrapped by `OfferFields`.

| Field | Type | Description |
|---|---|---|
| `offerHash` | `String` | SHA-256 hash of core offer details for identity/dedup |
| `itemCount` | `Int` | Total items across all orders. Default `1` |
| `payAmount` | `Double?` | Guaranteed pay amount (e.g., `7.75`) |
| `payTextRaw` | `String?` | Raw pay text (e.g., `"$7.75 Guaranteed (incl. tips)"`) |
| `distanceMiles` | `Double?` | Delivery distance in miles |
| `distanceTextRaw` | `String?` | Raw distance text (e.g., `"2.8 mi"`) |
| `dueByTimeText` | `String?` | Deadline display text (e.g., `"Deliver by 8:52 PM"`) |
| `dueByTimeMillis` | `Long?` | Deadline as epoch milliseconds |
| `timeToCompleteMinutes` | `Long?` | Estimated minutes to complete |
| `badges` | `Set<OfferBadge>` | Offer-level badges (see `OfferBadge`) |
| `initialCountdownSeconds` | `Int?` | Acceptance countdown timer (e.g., `36`) |
| `orders` | `List<ParsedOrder>` | Individual orders within this offer |
| `rawExtractedTexts` | `String?` | Full extracted text for later review |

### `ParsedOrder`

A single order within an offer. An offer may contain 1-3 orders (stacked).

| Field | Type | Description |
|---|---|---|
| `orderIndex` | `Int` | Position in the offer (0-based) |
| `orderType` | `OrderType` | What kind of order this is |
| `storeName` | `String` | Merchant name |
| `itemCount` | `Int` | Number of items in this order |
| `isItemCountEstimated` | `Boolean` | True if item count was inferred, not explicit |
| `badges` | `Set<OrderBadge>` | Order-level badges (see `OrderBadge`) |

### `OrderType`

The type of order within an offer.

| Enum | Type Name | Shopping? | Description |
|---|---|---|---|
| `PICKUP` | `"Pickup"` | No | Standard pickup from an establishment |
| `RESTAURANT_PICKUP` | `"Restaurant Pickup"` | No | Explicitly labeled restaurant pickup |
| `SHOP_FOR_ITEMS` | `"Shop for items"` | Yes | Dasher shops for items at the store |
| `RETAIL_PICKUP` | `"Retail pickup"` | No | Pre-packaged retail store pickup |

### `OfferBadge`

Offer-level badges/indicators on the offer screen.

| Enum | Match Strategy | Description |
|---|---|---|
| `HIGH_PAYING` | Regex: `High-paying (shopping )?offer!` | DoorDash-flagged high pay |
| `PRIORITY_ACCESS` | Regex: `your (Platinum\|Gold\|Silver) status\|Pro Shopper` | Status-based priority |
| `ALL_ORDERS_SAME_STORE` | Exact: `All orders are from the same store` | Stacked orders, one store |
| `BOTH_ORDERS_SAME_CUSTOMER` | Exact: `Both orders go to the same customer` | Stacked orders, one customer |
| `ITEMS_CAN_BE_ADDED` | Exact: `Items can be added before checkout` | Customer can add items |
| `SHARPIE_RECOMMENDED` | Exact: `Black marker or Sharpie recommended` | Shop & Deliver hint |
| `CONTAINS_RESTRICTED_ITEMS` | Exact: `Contains restricted items` | Restricted items present |
| `AGE_RESTRICTED_18_PLUS` | Exact: `Must be 18+ to accept order` | Age gate |
| `AGE_RESTRICTED_21_PLUS` | Exact: `Must be 21+ to accept order` | Age gate (alcohol) |
| `CHECK_RECIPIENT_ID` | Exact: `Check recipient's ID` | ID check required |
| `INCLUDES_ALCOHOL` | Contains: `including alcohol` | Alcohol in order |
| `COLLECT_CASH` | Exact: `Collect cash from customer` | Cash on delivery |
| `MAY_NEED_RETURNS` | Exact: `May need returns` | Returns possible |

### `OrderBadge`

Order-level badges that apply to a specific order within an offer.

| Enum | Badge Text | Description |
|---|---|---|
| `RED_CARD` | `"Red Card required"` | Red Card needed for payment at the store |
| `LARGE_ORDER` | `"Large Order - Catering"` | Large catering order |
| `PIZZA_BAG` | `"Pizza bag required"` | Pizza bag equipment needed |
| `ALCOHOL` | `"Alcohol"` | This order contains alcohol |

### `ParsedPay`

A pay breakdown from the delivery completion screen. Separates platform pay
from customer tips.

| Field | Type | Description |
|---|---|---|
| `appPayComponents` | `List<ParsedPayItem>` | Platform pay items (Base Pay, Peak Pay, etc.) |
| `customerTips` | `List<ParsedPayItem>` | Per-store customer tips |
| `totalBasePay` | `Double` | Derived: sum of `appPayComponents` amounts |
| `totalTip` | `Double` | Derived: sum of `customerTips` amounts |
| `total` | `Double` | Derived: `totalBasePay + totalTip` |

### `ParsedPayItem`

A single line item in a pay breakdown.

| Field | Type | Description |
|---|---|---|
| `type` | `String` | Label (e.g., `"Base Pay"`, `"Peak Pay"`, or store name for tips) |
| `amount` | `Double` | Dollar amount |

### `TimelineTaskEntry`

A single entry in the timeline/dash-controls task chain overlay.

| Field | Type | Description |
|---|---|---|
| `taskType` | `String` | Type identifier (e.g., pickup, dropoff) |
| `nameHash` | `String?` | Hashed name for identity without PII |
| `deadline` | `ParsedTime?` | Task deadline |
| `storeHint` | `String?` | Store name hint |
| `isCurrent` | `Boolean` | Whether this is the currently active task. Default `false` |

### `RatingsSnapshot`

A point-in-time snapshot of driver performance metrics, captured when the
Ratings screen is observed. Stored on `PlatformRegion` so the bubble HUD can
display it without re-observing the ratings screen.

| Field | Type | Description |
|---|---|---|
| `capturedAt` | `Long` | When the snapshot was taken |
| `acceptanceRate` | `Double?` | Offer acceptance rate |
| `completionRate` | `Double?` | Delivery completion rate |
| `onTimeRate` | `Double?` | On-time or early rate |
| `customerRating` | `Double?` | Customer rating (out of 5.0) |
| `deliveriesLast30Days` | `Int?` | Deliveries in the last 30 days |
| `lifetimeDeliveries` | `Int?` | Total lifetime deliveries |
| `originalItemsFoundRate` | `Double?` | Shopping: original items found rate |
| `totalItemsFoundRate` | `Double?` | Shopping: total items found rate |
| `substitutionIssuesRate` | `Double?` | Shopping: substitution issues rate |
| `itemsWithQualityIssuesRate` | `Double?` | Shopping: quality issues rate |
| `itemsWrongOrMissingRate` | `Double?` | Shopping: wrong/missing items rate |
| `lifetimeShoppingOrders` | `Int?` | Total lifetime shopping orders |

### `ModeConfidence`

Tracks confidence for implausible mode transitions. When the pipeline observes
a flow that implies a mode change that doesn't make sense given the current
state, the stepper accrues confidence instead of immediately transitioning.

| Field | Type | Description |
|---|---|---|
| `pendingMode` | `Mode?` | The mode we're trying to transition to |
| `pendingFlow` | `Flow?` | The flow that triggered the pending transition |
| `supportingObservations` | `Int` | How many observations support this transition |
| `firstSeenAt` | `Long?` | When the first supporting observation arrived |

| Constant | Value | Description |
|---|---|---|
| `DEFAULT_OBSERVATION_THRESHOLD` | `2` | Observations needed to heal |
| `DEFAULT_TIME_WINDOW_MS` | `10_000` | Time window for accrual (10s) |

### `TimeoutType`

Timer types used by the state machine's internal timer system.

| Enum | Description |
|---|---|
| `SESSION_PAUSED_SAFETY` | Force offline after extended pause |
| `RETRY_CLICK` | Retry an automated click action |
| `SETTLE_UI` | Wait for UI to stabilize after interaction |
| `DECLINE_POPUP_WAIT` | Wait for decline confirmation popup |
| `SCREENSHOT_WAIT` | Delay before capturing screenshot |

### `ChatPersona`

Typed author identity for bubble HUD messages.

| Persona | ID | Display Name | Use |
|---|---|---|---|
| `Dispatcher` | `bot_dispatcher` | "Dispatch" | General state updates |
| `System` | `bot_system` | "System" | System-level messages |
| `Dasher` | `dasher_self` | "You" | User's own actions echoed back |
| `Merchant(name)` | `merchant_*` | Store name | Store-related updates |
| `Customer(name)` | `customer_*` | Customer name | Customer-related updates |
| `GoodOffer` | `good_offer` | "Good Offer" | Offer above acceptance thresholds |
| `BadOffer` | `bad_offer` | "Bad Offer" | Offer below acceptance thresholds |
| `Inspector` | `inspector` | "Inspector" | Debug/diagnostic messages |
| `Navigator` | `navigator` | "Navigator" | Navigation updates |
| `Shopper` | `shopper` | "Shopper" | Shopping task updates |
| `Earnings` | `earnings` | "Earnings" | Earnings summaries |

---

## 3. Supported Shapes

Shapes are the typed output of rule classification. Each rule declares a `"shape"`
string that `ParsedFieldsFactory` maps to a `ParsedFields` subtype. The state
machine consumes these via `Observation.FlowObservation.parsed`.

All shapes inherit a common field:

| Field | Type | Description |
|---|---|---|
| `activity` | `String?` | Free-typed platform tag for sub-classification within a flow (e.g., `"shopping"`, `"scanning_card"`) |

All shapes expose `dedupeHash(): Int` for post-classification deduplication.
Identity fields are included; transient/ticking fields are excluded.

### 3.1 Gate Shapes (Dropped Before State Machine)

These shapes are intercepted by pipeline gates and **never reach the state machine**.

#### `"sensitive"` — `SensitiveFields`

**Pipelines:** screen, notification  
**Purpose:** PII/security screens (banking, identity, payment). Pledge: never stored or forwarded.

No additional fields beyond `activity`.

#### `"noise"` — `NoiseFields`

**Pipelines:** screen, notification  
**Purpose:** Known-irrelevant signals. Surgically suppressed to reduce processing noise.

No additional fields beyond `activity`.

### 3.2 Null Shape

#### `"none"` — `None`

**Pipelines:** any  
**Purpose:** Explicit no-data. Also the fallback when `shape` is `null` or when
the declared shape has empty fields.

No fields (singleton object).

### 3.3 Screen Shapes

#### `"idle"` — `IdleFields`

**Pipelines:** screen  
**Purpose:** Driver waiting for offers on the idle map.  
**Dedup identity:** `zoneName` + `sessionType` + `sessionPay`

| Field | Type | Required | Description |
|---|---|---|---|
| `zoneName` | `String?` | No | Current delivery zone name (e.g., `"Downtown"`) |
| `sessionType` | `SessionType?` | No | Current earning mode (`PerOffer` or `ByTime`) |
| `sessionPay` | `Double?` | No | Running session earnings displayed on idle screen |
| `waitTimeEstimate` | `String?` | No | Estimated wait time for next offer (e.g., `"2-5 min"`) |
| `isHeadingBackToZone` | `Boolean` | No | Whether the driver is returning to their zone. Default `false` |
| `spotSaveDeadline` | `Long?` | No | Epoch millis deadline to return before losing the spot |

#### `"offer"` — `OfferFields`

**Pipelines:** screen  
**Purpose:** Offer popup presented to the driver.  
**Dedup identity:** `parsedOffer.offerHash`

| Field | Type | Required | Description |
|---|---|---|---|
| `parsedOffer` | `ParsedOffer` | Yes | The full parsed offer. See `ParsedOffer` in Section 2 for all fields |

#### `"task"` — `TaskFields`

**Pipelines:** screen  
**Purpose:** Active delivery segment. A single shape covers all four task flows
via `phase`/`subFlow` discriminators.  
**Dedup identity:** `phase` + `subFlow` + `storeName` + `arrivalConfirmed`

| Field | Type | Required | Description |
|---|---|---|---|
| `phase` | `TaskPhase` | Yes | `PICKUP` or `DROPOFF` |
| `subFlow` | `TaskSubFlow` | Yes | `NAVIGATION` or `ARRIVED` |
| `storeName` | `String?` | No | Merchant name (authoritative — from task screen, not offer) |
| `storeAddress` | `String?` | No | Merchant address |
| `customerNameHash` | `String?` | No | One-way hash of customer name (PII never stored in cleartext) |
| `customerAddressHash` | `String?` | No | One-way hash of customer address |
| `deadline` | `ParsedTime?` | No | Delivery deadline (display text + epoch millis) |
| `itemCount` | `Int?` | No | Number of items to pick up or deliver |
| `redCardTotal` | `Double?` | No | Red Card payment amount (pay-at-store orders) |
| `arrivalConfirmed` | `Boolean` | No | Whether the platform has confirmed arrival. Default `false` |

#### `"post_task"` — `PostTaskFields`

**Pipelines:** screen  
**Purpose:** Delivery completion summary. Pay breakdown available when the
payout card is expanded.  
**Dedup identity:** `totalPay` + `appPay` + `customerTips`

| Field | Type | Required | Description |
|---|---|---|---|
| `totalPay` | `Double` | **Yes** | Total payout for this delivery |
| `appPay` | `Double?` | No | Platform's share (base pay + bonuses) |
| `customerTips` | `Double?` | No | Customer tip total |
| `parsedPay` | `ParsedPay?` | No | Structured pay breakdown with line items. Only populated when `isExpanded = true` |
| `isExpanded` | `Boolean` | No | Whether the pay detail card is expanded. Default `false` |
| `expandButtonId` | `String?` | No | View ID of the expand button (for ADR-0006 auto-click) |
| `sessionEarnings` | `Double?` | No | Running session total shown on this screen |
| `offersAccepted` | `Int?` | No | Offers accepted this session |
| `offersTotal` | `Int?` | No | Total offers received this session |

#### `"session_ended"` — `SessionEndedFields`

**Pipelines:** screen  
**Purpose:** End-of-shift summary screen.  
**Dedup identity:** `totalEarnings`

| Field | Type | Required | Description |
|---|---|---|---|
| `totalEarnings` | `Double` | **Yes** | Total session earnings |
| `sessionDurationMillis` | `Long?` | No | How long the session lasted |
| `offersAccepted` | `Int?` | No | Offers accepted during the session |
| `offersTotal` | `Int?` | No | Total offers received during the session |
| `weeklyEarnings` | `Double?` | No | Running weekly earnings total |

#### `"paused"` — `PausedFields`

**Pipelines:** screen  
**Purpose:** Session paused with countdown timer. The driver has a limited
window to resume before being taken offline.  
**Dedup identity:** Fixed — all paused observations are the same identity.

| Field | Type | Required | Description |
|---|---|---|---|
| `remainingText` | `String` | **Yes** | Countdown display text (e.g., `"34:15"`) |
| `remainingMillis` | `Long` | **Yes** | Remaining pause time in milliseconds |

#### `"timeline"` — `TimelineFields`

**Pipelines:** screen  
**Purpose:** Timeline/dash-controls overlay showing the current task chain.  
**Dedup identity:** `sessionEarnings` + `tasks.size`

| Field | Type | Required | Description |
|---|---|---|---|
| `sessionEarnings` | `Double?` | No | Session earnings shown in the overlay |
| `offerEarnings` | `Double?` | No | Earnings for the current offer/job |
| `endsAtText` | `String?` | No | Scheduled end time display text |
| `endsAtMillis` | `Long?` | No | Scheduled end time as epoch millis |
| `tasks` | `List<TimelineTaskEntry>` | No | The task chain. See `TimelineTaskEntry` in Section 2 |

#### `"ratings"` — `RatingsFields`

**Pipelines:** screen  
**Purpose:** Driver ratings/performance view. Includes both delivery and
shopping metrics.  
**Dedup identity:** `customerRating` + `lifetimeDeliveries`

| Field | Type | Required | Description |
|---|---|---|---|
| `acceptanceRate` | `Double?` | No | Offer acceptance rate (0.0 - 1.0) |
| `completionRate` | `Double?` | No | Delivery completion rate |
| `onTimeRate` | `Double?` | No | On-time or early delivery rate |
| `customerRating` | `Double?` | No | Customer rating (0.0 - 5.0) |
| `deliveriesLast30Days` | `Int?` | No | Deliveries completed in the last 30 days |
| `lifetimeDeliveries` | `Int?` | No | Total lifetime deliveries |
| `originalItemsFoundRate` | `Double?` | No | Shopping: original items found rate |
| `totalItemsFoundRate` | `Double?` | No | Shopping: total items found rate |
| `substitutionIssuesRate` | `Double?` | No | Shopping: substitution issues rate |
| `itemsWithQualityIssuesRate` | `Double?` | No | Shopping: quality issues rate |
| `itemsWrongOrMissingRate` | `Double?` | No | Shopping: wrong/missing items rate |
| `lifetimeShoppingOrders` | `Int?` | No | Total lifetime shopping orders |

### 3.4 Click Shape

#### `"click"` — `ClickFields`

**Pipelines:** click  
**Purpose:** Classified UI tap/click event.  
**Dedup identity:** None — every click is unique.

| Field | Type | Required | Description |
|---|---|---|---|
| `intent` | `String` | Yes | Semantic meaning. Values: `"accept_offer"`, `"decline_offer"`, `"arrived_at_store"`, `"go_online"`, `"go_offline"` |
| `nodeId` | `String?` | No | Accessibility view ID of the clicked element |
| `nodeText` | `String?` | No | Display text of the clicked element |

### 3.5 Notification Shape

#### `"notification"` — `NotificationFields`

**Pipelines:** notification  
**Purpose:** Classified status bar notification with extracted data.  
**Dedup identity:** `intent` + `amount` + `storeName`

> **Design note:** This is currently a generic carrier shape — all notification
> rules extract into these same fields. As notification coverage expands, this
> will likely evolve toward intent-specific shapes that derive richer semantic
> meaning from each notification type (e.g., a dedicated tip event, a surge
> alert, a schedule reminder, etc.) rather than flattening everything into the
> same five fields.

| Field | Type | Required | Description |
|---|---|---|---|
| `intent` | `String` | Yes | Semantic classification. Values: `"additional_tip"`, `"new_order"`, `"scheduled_dash_expired"` |
| `amount` | `Double?` | No | Monetary amount if applicable (e.g., tip amount) |
| `storeName` | `String?` | No | Associated store name |
| `deliveredAt` | `String?` | No | Delivery timestamp text (e.g., `"5/7, 2:30 PM"`) |
| `rawText` | `String?` | No | Original notification text for fallback parsing |

---

## 4. Observation Types (State Machine Inputs)

### 4.1 Flow Observations (Carry State Contributions)

| Type | Source | Carries | Description |
|---|---|---|---|
| `Observation.Screen` | Accessibility window pipeline | `flow`, `modeHint`, `parsed`, `target`, `actions` | Classified screen from the UI accessibility tree. |
| `Observation.Click` | Accessibility click pipeline | `flow`, `modeHint`, `parsed`, `target`, `actions`, `screenTarget` | Classified tap/click event. Includes the screen context. |
| `Observation.Notification` | Notification pipeline | `flow`, `modeHint`, `parsed`, `target`, `actions` | Classified status bar notification. |

### 4.2 Control Observations (No Flow/Mode Contribution)

| Type | Source | Payload | Description |
|---|---|---|---|
| `Observation.Timeout` | Side-effect engine timer | `type: TimeoutType` | Timer expiration event. |
| `Observation.UiInput` | Bubble HUD | `action: String`, `payload: Map` | Manual user interaction from the overlay. |
| `Observation.Loopback` | Side-effect engine | `effect: String`, `payload: Map` | Feedback from effect execution (e.g., `"offer_evaluated"`). |

### 4.3 Timeout Types

| TimeoutType | Purpose | Default Duration |
|---|---|---|
| `SESSION_PAUSED_SAFETY` | Force offline after extended pause | Configured per-platform |
| `RETRY_CLICK` | Retry an automated click action | Short delay |
| `SETTLE_UI` | Wait for UI to stabilize after interaction | Short delay |
| `DECLINE_POPUP_WAIT` | Wait for decline confirmation popup | Short delay |
| `SCREENSHOT_WAIT` | Delay before capturing screenshot | Short delay |

---

## 5. AppState Structure (Region Data)

### 5.1 Top-Level Container

```
AppState
├── regions: Regions
│   ├── flow: FlowRegion              (R0)
│   ├── crossPlatform: CrossPlatformRegion  (R1)
│   └── platforms: Map<Platform, PlatformRegion>  (R2+)
├── timestamp: Long
└── correlationVersion: Long          (monotonic, incremented per observation)
```

### 5.2 Region 0 — FlowRegion

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

### 5.3 Region 2+ — PlatformRegion

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

### 5.4 Region 1 — CrossPlatformRegion

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

## 6. Mode Transition Table

### 6.1 Plausibility Rules

| From | To | Plausible? | Condition |
|---|---|---|---|
| `Online` | `Paused` | Yes | Explicit pause screen observed |
| `Online` | `Offline` | Only via `SessionEnded` | Requires `flow == SessionEnded` |
| `Paused` | `Online` | Yes | Any active screen observed |
| `Paused` | `Offline` | Yes | Session timeout/end |
| `Offline` | `Online` | **No** (heal) | Could be app restart mid-task |
| `Offline` | `Paused` | **No** (heal) | Inconsistent — shouldn't happen |

### 6.2 Healing Policy

Implausible transitions are **not rejected** — they accrue confidence and heal:

- **Threshold:** 2 supporting observations within 10s time window
- **OR** 1 high-weight signal (explicit mode-defining screen like offer or pause)
- When threshold is met: force the transition and synthesize missing state
  (e.g., create Job + Task with `recovered = true`)

### 6.3 Mode Inference from Flow

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

## 7. Effects (State Machine Outputs)

Effects are produced by `EffectMap.diff()` which diffs before/after state per region.

### 7.1 Persistence Effects

| Effect | Fields | Key | Description |
|---|---|---|---|
| `LogEvent` | `event: AppEventEntity` | `"log:{type}:{time}"` | Append event to database. |

### 7.2 UI Effects

| Effect | Fields | Key | Description |
|---|---|---|---|
| `UpdateBubble` | `text`, `persona: ChatPersona`, `expand: Boolean` | — | Post message to bubble HUD notification. |
| `PlayNotificationSound` | — | — | Play alert sound. |

### 7.3 Capture Effects

| Effect | Fields | Key | Description |
|---|---|---|---|
| `CaptureScreenshot` | `filenamePrefix`, `metadata?` | — | Take screenshot via accessibility service, save to gallery. |

### 7.4 Timer Effects

| Effect | Fields | Key | Description |
|---|---|---|---|
| `ScheduleTimeout` | `durationMs`, `type: TimeoutType` | — | Schedule a timer that produces `Observation.Timeout` on expiry. |
| `CancelTimeout` | `type: TimeoutType` | — | Cancel a scheduled timer. |

### 7.5 Odometer Effects

| Effect | Fields | Key | Description |
|---|---|---|---|
| `StartOdometer` | — | — | Begin GPS mileage tracking. Creates foreground notification. |
| `StopOdometer` | — | — | Stop GPS tracking. Removes notification. |
| `PauseOdometer` | — | — | Pause GPS while stationary. Session total preserved. |
| `ResumeOdometer` | — | — | Resume GPS after stationary pause. |

### 7.6 Offer Effects

| Effect | Fields | Key | Description |
|---|---|---|---|
| `EvaluateOffer` | `parsedOffer: ParsedOffer` | — | Run offer through evaluator. Result loops back as `Loopback("offer_evaluated")`. |
| `SpeakOffer` | `parsedOffer`, `platformName` | — | TTS announcement: "{platform} offer. ${pay}. {store}. {distance} miles. {dueBy}." |

### 7.7 Automation Effects (ADR-0006)

| Effect | Fields | Key | Description |
|---|---|---|---|
| `ClickNode` | `node: UiNode`, `description` | — | Click a UI element by resolving against live accessibility tree. |
| `RequestAction` | `action: RequestedAction` | `"action:{ruleId}:{dedupeKey}"` | Rule-originated click with throttling and gate conditions. |

### 7.8 Notification Effects

| Effect | Fields | Key | Description |
|---|---|---|---|
| `ProcessTipNotification` | `amount`, `storeName`, `deliveredAt` | — | Post tip notification to bubble. |

### 7.9 Session Effects

| Effect | Fields | Key | Description |
|---|---|---|---|
| `StartDash` | `dashId: String` | `"start_dash:{id}"` | Mark session start. |
| `EndDash` | — | — | Mark session end. |

### 7.10 Composition Effects

| Effect | Fields | Key | Description |
|---|---|---|---|
| `Delayed` | `delayMs`, `effect: AppEffect` | — | Execute wrapped effect after delay. |
| `SequentialEffect` | `effects: List<AppEffect>` | — | Execute effects in order. |

### 7.11 Idempotency

Effects with a non-null `effectKey` are checked against the `effects_fired` table during
crash-recovery replay to prevent duplicate execution. External effects (`UpdateBubble`,
`CaptureScreenshot`, `ClickNode`, etc.) are unconditionally suppressed during recovery.
Loopback effects are replayed deterministically.

---

## 8. Effect Emission Triggers

When `EffectMap.diff()` detects a region change, it emits the following effects:

### 8.1 Flow Region Triggers

| Trigger | Effects Emitted |
|---|---|
| Offer presented (new hash) | `LogEvent(OFFER_RECEIVED)`, `CaptureScreenshot`, `EvaluateOffer`, `SpeakOffer` |
| Offer replaced (different hash) | Resolve old offer + all "presented" effects for new |
| Offer resolved (left OfferPresented) | `LogEvent(ACCEPTED/DECLINED/TIMEOUT)`, conditional `UpdateBubble` |
| Click on offer button | `UpdateBubble("Offer Accepted"/"Offer Declined")` |

### 8.2 Platform Region Triggers

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

### 8.3 PostTask Triggers

| Trigger | Effects Emitted |
|---|---|
| Expanded pay data | `UpdateBubble` with receipt text and tip breakdown |
| Leaving PostTask | `LogEvent(DELIVERY_COMPLETED)` |

### 8.4 Notification Triggers

| Trigger | Effects Emitted |
|---|---|
| Tip notification (intent: `additional_tip`) | `ProcessTipNotification`, `LogEvent(NOTIFICATION_RECEIVED)` |
| Other classified notification | `LogEvent(NOTIFICATION_RECEIVED)` |

### 8.5 Rule-Originated Actions

| Trigger | Effects Emitted |
|---|---|
| Observation carries `actions` list (ADR-0006) | `RequestAction` per action that passes its gate |

---

## 9. Rule Types

### 9.1 Screen Rules

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

### 9.2 Click Rules

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

### 9.3 Notification Rules

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

## 10. Rule-Originated Actions (ADR-0006)

Rules can declare `"actions"` blocks that ride on observations through the state machine
and are executed as effects by the side-effect engine.

### 10.1 Supported Verbs

| Verb | Description |
|---|---|
| `"click"` | Click a UI element identified by `targetRef` |

(Only verb currently supported.)

### 10.2 Gate Conditions

Actions fire only if their `onlyIf` gate passes against the observation's parsed fields:

| Gate Type | Description |
|---|---|
| `FieldEquals(field, value)` | Field equals expected value |
| `FieldNotEquals(field, value)` | Field does not equal value |
| `FieldNotNull(field)` | Field is present and non-null |

### 10.3 Throttling

- Default throttle: 500ms per `effectKey`
- `dedupeKey` can be specified per-action to customize the throttle key
- Without `dedupeKey`, the `targetRef.pathFingerprint` is used

---

## 11. Logged Event Types

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

## 12. Pipeline Configuration

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
