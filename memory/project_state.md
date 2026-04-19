# DashBuddy Project State

## Current Focus: Bubble UI

The bubble UI (`BubbleScreen.kt`, 621 lines) has been wired to `StateManagerV2` and is receiving
live app state. The next UI work planned is in two parts — see the design notes below.

---

## Planned: Bubble UI Toolbar + History Navigation

### Context

The `TopAppBar` in the bubble currently just holds a title and two icon buttons — wasted real
estate. The `MetricsStrip` composable (status badge + session metrics) lives at the top of the
*content* area, crowding `ModeCard`. Two improvements were scoped out together:

---

### 1. Promote Status + Metrics into the Toolbar

**Goal:** Free up `ModeCard` height by moving status and metrics into the `TopAppBar`.

**Design:**
- Use the `title` composable slot in `TopAppBar` to host a `Column`:
  - Line 1: status badge chip (WAITING / PICKUP / DELIVERING / etc.) — already built in
    `statusBadge()` (BubbleScreen.kt ~line 209)
  - Line 2: current step label as a short text (e.g., "Waiting for orders", "On Pickup")
- Session metrics (`$X.XX · Y.Z mi`) move to the same toolbar area, either as a trailing element
  or a second line
- Remove `MetricsStrip` from the content area once toolbar carries this info
- This is pure layout reorganization, no new state needed

**Files to touch:**
- `app/src/main/java/cloud/trotter/dashbuddy/ui/bubble/BubbleScreen.kt` — restructure
  `MetricsStrip` and the `TopAppBar` composables

---

### 2. Historical Step Navigation in ModeCard

**Goal:** Let a Dasher review the previous delivery's details even after transitioning to a new
state (e.g., PostDelivery → AwaitingOffer happens fast; don't want to lose the pay summary).

**Design:**
- `BubbleViewModel` maintains a `List<FrozenModeDisplay>` — a simple data class holding the
  rendered strings (not the full state object) captured at the moment of state transition
- Snapshot is taken when transitioning *away* from meaningful states: `OnPickup`, `OnDelivery`,
  `PostDelivery`, `OfferPresented`
- Idle/offline states (`IdleOffline`, `Initializing`, `PostDash`) are not snapshotted
- `ModeCard` becomes a `HorizontalPager` (or shows left/right chevron buttons) over
  `[...history, currentLive]`
- Live state is always the rightmost/last page — shown by default
- When browsing history, a subtle `← HISTORY` label (or dimmed overlay) signals non-live view
- Paging back to live clears the history indicator

**Key decision already made:** snapshot *rendered strings* at transition time, not the state object
itself. This keeps history decoupled from state model evolution.

**Files to touch:**
- `app/src/main/java/cloud/trotter/dashbuddy/ui/bubble/BubbleViewModel.kt` — add
  `FrozenModeDisplay` data class + snapshot list + scan/transition logic
- `app/src/main/java/cloud/trotter/dashbuddy/ui/bubble/BubbleScreen.kt` — wrap `ModeCard` in
  a `HorizontalPager` or add navigation chevrons

---

### Implementation Order

1. Toolbar reorganization first (lower risk, immediate visual payoff, no new state logic)
2. History navigation second (requires ViewModel changes + pager wiring)

---

---

## Planned: Full Operating Cost in Offer Evaluation

### Problem

`OfferEvaluator` currently only deducts **fuel cost** from gross pay to compute net pay:

```
fuelCostPerMile = gasPricePerGallon / vehicleMpg   // UserEconomy.kt
netPay = grossPay - (distanceMiles * fuelCostPerMile)
```

This means $/mi and $/hr are both overstated — they don't account for maintenance, depreciation,
or insurance (the other ~60% of true per-mile cost for a car).

### Fix

Rename `fuelCostPerMile` → `operatingCostPerMile` in `UserEconomy` and feed it a full per-mile
figure. Because all downstream math in `OfferEvaluator` already flows through that one field, no
scoring logic needs to change.

### Design Philosophy: Personalized to the Dasher

Gig driving is classified as **"severe service"** by vehicle manufacturers — lots of short trips,
frequent cold starts, stop-and-go — so normal consumer assumptions about maintenance intervals and
costs don't apply. The goal is a model that starts with reasonable gig-work defaults and lets the
Dasher tune it to their actual situation.

### Cost Model: Two Layers

**Layer 1 — Fuel (live, auto-fetched)**
- `gasPricePerGallon / combinedMpg` — already working, pulls from EIA + EPA
- Stays separate so the live gas price feature remains useful

**Layer 2 — Non-fuel operating cost (personalized, per-mile)**
- Default: **$0.70/mile** (user-confirmed starting point, conservative/protective)
- This is intentionally on top of gas, not replacing it — gig depreciation + insurance + wear
  is genuinely higher than typical consumer use
- User tunes this down over time as they learn their actual costs

**Total: `operatingCostPerMile = fuelCostPerMile + wearCostPerMile`**

### Personalization Inputs (wizard / settings screens)

#### Oil Changes (gig-adjusted interval)
- Gig driving = severe service = shorter intervals than the sticker in the door jamb
- Default assumption: **3,500 miles** between changes (vs. 5,000-7,500 for normal driving)
- Inputs: oil change cost + interval miles (both editable)
- Computed contribution: `oilChangeCost / intervalMiles` → per-mile cost
- This figure should start higher and the Dasher can adjust based on their actual mechanic bills

#### Insurance
- Inputs: monthly (or annual) premium + number of vehicles on the policy
- Per-vehicle annual cost = `annualPremium / vehicleCount`
- Per-mile contribution = `perVehicleAnnualCost / dasherAnnualMiles`
- `dasherAnnualMiles` = estimated or tracked (can default to 20,000 for a full-time dasher)
- Note: dashers with a rideshare/gig endorsement on their policy pay more — worth a flag

#### Other wear (tires, brakes, misc)
- Gig driving burns through tires and brakes faster than consumer use (stop-and-go)
- Could offer a simple "other wear" $/mile field that defaults to something like $0.08/mile
- Or break out: tire cost / expected tire life miles + brake cost / expected brake life miles
- Keep this simple for v1 — single editable field with a sensible default

### UI Approach

Show the breakdown transparently in settings so the Dasher understands what they're paying:

```
Fuel:        $0.14/mi  (live from EIA + your MPG)
Oil changes: $0.02/mi  (based on your interval + cost)
Insurance:   $0.07/mi  (your premium ÷ vehicles ÷ annual miles)
Other wear:  $0.08/mi  (tires, brakes, misc)
─────────────────────
Total:       $0.31/mi  ← this is what OfferEvaluator deducts
```

Default total lands around $0.84/mi ($0.14 fuel + $0.70 wear) until they personalize.
As they update inputs, the number adjusts live.

### Files to Touch

| File | Change |
|---|---|
| `domain/.../evaluation/UserEconomy.kt` | Add `wearCostPerMile` computed from components; `operatingCostPerMile = fuel + wear` |
| `domain/.../evaluation/OfferEvaluation.kt` | Rename `fuelCostEstimate` → `operatingCostEstimate`; add breakdown fields |
| `core/datastore/.../AppPreferencesDataSource.kt` | Add keys: oilChangeCost, oilChangeIntervalMiles, insuranceMonthlyPremium, vehicleCount, otherWearPerMile, dasherAnnualMiles |
| `core/data/.../settings/AppPreferencesRepository.kt` | Build `wearCostPerMile` flow from component inputs |
| Settings UI (wizard/prefs screens) | New "Operating Costs" section with breakdown + live total |
| `domain/.../OfferEvaluatorTest.kt` | Update field names + test cases for full cost model |

---

## Key Files Quick Reference (Bubble UI)

| File | Role |
|---|---|
| `app/.../ui/bubble/BubbleScreen.kt` | All bubble composables (621 lines) |
| `app/.../ui/bubble/BubbleViewModel.kt` | State collection, session earnings scan |
| `app/.../ui/bubble/BubbleActivity.kt` | Hosts the Compose bubble |
| `app/.../ui/bubble/BubbleManager.kt` | Notification/bubble lifecycle |
| `app/.../state/AppStateV2.kt` | Sealed class — 11 app states |
| `app/.../state/StateManagerV2.kt` | Central state machine |

### Status Badge States (BubbleScreen.kt ~line 209)
`Initializing`→STARTING, `IdleOffline`→OFFLINE, `AwaitingOffer`→WAITING, `OfferPresented`→OFFER,
`OnPickup`→PICKUP, `OnDelivery`→DELIVERING, `PostDelivery`→DELIVERED, `DashPaused`→PAUSED,
`PausedOrInterrupted`→PAUSED, `PostDash`→DONE
