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
