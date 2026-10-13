# ADR-0012: The Dasher's Ledger (Cash Tips, Expenses, Fuel Fill-Ups)

**Status:** Accepted; to be built in #1333 slices 1 to 3 (nothing here exists in code yet)
**Issue:** #1333
**Date:** 2026-10-12
**Builds on:** #314 (CQRS read model), #650 (corrections family),
[`docs/architecture/05-analytics-read-model.md`](../architecture/05-analytics-read-model.md)
**Related:** #659 (frozen fuel/non-fuel split), #1134 (most recent correction event, same shape),
#688 (cash tips outside realized pay)

---

## Context

The new events, folds, tables, repository APIs, and preferences described here land in #1333 slice 1.
The HUD consumes them in the later slices. This ADR records the 2026-10-12 design decisions.

The dasher has nowhere to log expenses or fuel fills. A cash tip can be set only from the hub's
delivery drill-down (`AdjustDeliveryDialog` in `SessionDetailScreen`).

The bubble needs entries for cash tips, expenses, and fuel without a trip through the hub.
Each money fact belongs in the append-only `app_events` history. `AnalyticsProjector` projects
that history into Room, using pure `RecordFolds` and `CorrectionFolds` in `:domain`.

Economics stay frozen per record. Cash tips stay outside `realizedPay` and `netProfit`, and enter
totals at read time. The projector drops a cash-tip adjustment on a delivery with null `sessionId`.
The HUD must choose a delivery from the focused dash.

## Decision

### Events

The three new `AppEventType` values belong to the "User corrections" group.

| Event | Payload or write path |
| --- | --- |
| `EXPENSE_LOGGED` | `ExpenseLoggedPayload(sessionId: String?, category: ExpenseCategory, amount: Double, note: String?)` |
| `FUEL_FILLUP` | `FuelFillupPayload(sessionId: String?, totalAmount: Double, gallons: Double?, odometerMiles: Double?)` |
| `LEDGER_ENTRY_VOIDED` | `LedgerEntryVoidedPayload(targetEventSequenceId: Long)` |
| `DELIVERY_ADJUSTMENT` (cash tip, existing) | `targetEventSequenceId`, `sessionId`, `newCashTip` through `CorrectionRepository.adjustDelivery` |

`LedgerRepository.logExpense`, `LedgerRepository.logFuel`, and `LedgerRepository.void` write
through `appendUserEvent`. `BubbleViewModel` intents call repositories; composables do not write.

`BOOKKEEPING_EVENTS` gains all three new types. An odometer stamp on any of them never feeds
odometer measurement, session mileage, or job mileage budgets.

### Categories

`ExpenseCategory { FOOD, PARKING, SUPPLIES, OTHER }` lives in `:domain`.
Room stores the enum name. An unknown stored name reads as `OTHER`, following the `MilesBasis`
name-storage and unknown-value fallback pattern.

### Read model

`expense_records` stores one row per expense event.

| Column | Meaning |
| --- | --- |
| `eventSequenceId` | Source event sequence id; primary key |
| `sessionId` | Focused dash id; nullable |
| `occurredAt` | Event timestamp |
| `category` | `ExpenseCategory` name |
| `amount` | Expense amount |
| `note` | Dasher's free-text note; nullable |
| `voidedAt` | Void event timestamp; nullable |

`fuel_records` stores one row per fill event.

| Column | Meaning |
| --- | --- |
| `eventSequenceId` | Source event sequence id; primary key |
| `sessionId` | Focused dash id; nullable |
| `occurredAt` | Event timestamp |
| `totalAmount` | Fill total |
| `gallons` | Fill volume; nullable |
| `odometerMiles` | Cumulative odometer total at the fill; nullable |
| `voidedAt` | Void event timestamp; nullable |

Room moves from version 19 to 20 by `AutoMigration`. Slice 1 includes the exported schema,
a `MigrationTestHelper` case, and the `SchemaVersionGuardTest` migration chain.

`CorrectionFolds.foldExpenseLogged`, `CorrectionFolds.foldFuelFillup`, and
`CorrectionFolds.foldLedgerVoid` produce `Adjustment` decisions. `AnalyticsProjector` applies
them by primary key in event order inside the batch transaction, like `applyDeliveryAdjustment`.
The projected rows and watermark commit together.

`pricePerGallon` is derived at read time as the total divided by gallons. It is null when gallons
is null or zero, and is never stored. `odometerMiles` comes from
`OdometerRepository.totalMiles(): Double?`, DashBuddy's own GPS odometer total at the fill, null
until the persisted total has loaded. That odometer runs only while a dash is tracked, so it is not
the car's odometer: a cost per mile between two fills built on it covers dash miles only, and a
reader must say so. User events carry no event metadata (`appendUserEvent` passes none), so the
payload field is the only odometer reading on a fill-up.

### Undo is a second event

The write history is append-only. No entry is edited in place or deleted by the user.
Expense and fuel undo append `LEDGER_ENTRY_VOIDED(targetEventSequenceId)`.
Its projection stamps the target row's `voidedAt` with the void event's timestamp.
The original event and the projected row remain.

A void of a void, or of an unknown sequence id, is a counted skip.
Its `WARN` carries the sequence id only, with no payload text.

A cash-tip undo appends another `DELIVERY_ADJUSTMENT`. Its `newCashTip` is the value before
the tip, or `0.0` when there was none. `DELIVERY_ADJUSTMENT` remains the owner of delivery cash.
Two parts of this are open for the developer before slice 3 (see "Open before slice 3").

A write happens on the tap that commits. The 10 second undo window is UI state: the committed
sequence id and time. It survives a mode switch and ends with the activity. One window is open
at a time: a new commit closes the previous one, so only the newest entry can be undone. The value
a cash-tip undo restores is captured at commit time as the newest value the ViewModel knows for that
row: its own last committed tip for the row if the projector has not folded it yet, else the row's
`cashTip`. Undoing an older tip after a newer one is never possible, so an undo cannot bring back a
tip the dasher already replaced.
A held write is lost on process death; an appended event survives it.
The pending active-drop tip below is the exception because its target row does not yet exist.

### Fuel never changes the economy

A fill-up never changes `UserEconomy.fuelCostPerMile`, the gas-price preference, `NetProfit`,
or any `frozen*` column on an existing record. This is the developer decision of 2026-10-12.
It is a log the dasher reads. Realized cost per mile between fills is a read-time figure only.

### Session attribution and cash

The HUD stamps the focused dash's `sessionId` on every entry. An off-dash expense or fill-up
carries null. The hub does not render ledger rows yet (see Consequences); the HUD's log shows them.
Cash tips target only delivery rows of the focused
dash. The resolver refuses a null-session row because the projector drops its cash adjustment.

`TipTargetResolver.resolve` uses `JUST_COMPLETED_WINDOW_MS = 90_000`:

1. A delivery completed within the window is the default.
2. Otherwise, an active `DROPOFF`, navigating or arrived, is the default, identified by `taskId`.
3. Otherwise, the most recent completed delivery is the default.

The default and the other candidate from the first two rules appear first. "Another delivery"
shows the dash's deliveries, newest first, with store, time, and pay. Resting uses the last dash.

For an active drop whose row has not folded, `BubbleViewModel` holds a pending tip keyed by
`taskId`. It writes the adjustment when `deliveriesForSession` emits that task's row.
A dash's last drop can fold after the dash ends (an absorbed retire completes at the end), so
the end alone does not drop the tip. The tip is dropped when the task is unassigned, or when no row
for that `taskId` appears within a bound after the dash ends (slice 3 sets the bound and tests it),
with a `WARN` carrying ids only and a log line to the dasher, such as
"Tip $5 for Store A could not be attached". The pending tip lives in memory; a process death before
the row appears loses it.

### Kept

`AnalyticsRepository.sessionNet(sessionId): Flow<SessionNet?>` reads the dash's projected rows.
Its terms are the hub's per-dash net, read from `SessionDetail`: delivered net is the sum of
`netProfit` over `SessionDetail.deliveries`, with a null net counting as zero exactly as the hub's
per-dash net does (`COALESCE(SUM(netProfit), 0)` in `AnalyticsDao`), plus `SessionDetail.cashTips`
and `SessionDetail.unattributedPay`. Keeping the hub's rule means the HUD and the hub never show two
different nets for one dash.

`SessionNet` carries the dash's non-voided expense total and non-voided fuel total as separate
terms; `realExpenses` is their sum. `SessionNet.kept(countExpenses)` adds the three money terms and
subtracts `realExpenses` only when `countExpenses` is true. The HUD supplies
`count_real_expenses_in_kept`, default off. Whether the fuel term belongs in that subtraction is
open (see "Open before slice 3").

The flow is null until the first delivery row folds. Projector lag is accepted and disclosed
on the HUD. The disclosure states "after car cost, before tax; expenses not counted" or
"expenses counted", according to the preference.

### Open before slice 3 (developer decisions)

These come from the S0 review and change existing semantics or the design, so the developer decides.
Slice 1 does not depend on them; slice 3 does not start until they are answered.

1. A cash tip blocks the receipt re-price. `applyDeliveryAdjustment` stamps `driverAdjustedAt` on a
   cash-only edit, and `applyReceiptReprice` refuses any row with that stamp. A tip logged within
   90 s of the drop, before a collapsed receipt is expanded, leaves the delivery on its offer-pay
   estimate for good, and undoing the tip does not clear the stamp. Options: a cash-only edit stops
   stamping `driverAdjustedAt` (the re-price never touches `cashTip`), or cash tips get their own event.
2. A cash-tip undo cannot restore "no cash tip". A null `newCashTip` means unchanged, so the undo
   writes `0.0`; the row stays cash-bearing, which blocks unassigning it from its dash and shows a
   `$0.00` cash line. Options: readers treat `0.0` as no cash, the payload gains an explicit clear,
   or the same own-event option as item 1.
3. Fuel may be counted twice in kept. Delivered net already subtracts the frozen cost per mile,
   which includes an estimated fuel cost, so subtracting fill-up totals as well charges fuel twice
   when the preference is on, and charges a whole tank to the dash it was logged on. Options: kept
   subtracts expenses only (fuel stays a log), or it subtracts fills and adds back the dash's frozen
   fuel estimate.
4. The day's dollar goal for the kept-deduction headline (design.md section 2.3).

### Preferences

Both preferences live in `app_prefs`, following the `analytics_show_more` pattern.

| Key | Type | Default | Effect |
| --- | --- | --- | --- |
| `count_real_expenses_in_kept` | `Boolean` | `false` | Real expenses deducted from kept |
| `hud_pill_shows_earnings` | `Boolean` | `true` | Earnings figure on collapsed icon; phase colour only when off |

### No refold

There is no `PROJECTOR_VERSION` bump. The new event types are absent from all existing history,
so the new tables start empty with or without a refold. A later wipe rebuilds them from
`app_events`, like every other projection. This is why they may live in Room.
The weekly plan stays in DataStore because nothing in the event log can rebuild that user artifact.

## Privacy

Amounts, category, gallons, an odometer total, and an optional free-text note typed by the dasher
stay on the device. DashBuddy sends none of it anywhere. The rows live in the Room database,
which Android backup and device transfer include like the rest of the history (`PRIVACY.md` §6), so
a typed note goes wherever the dasher's backup goes.
The note is never written to a log at `INFO` or above. A decode failure logs the exception class
only, following #1134; the exception message can contain the payload and note.

The HUD shows store names and the dasher's own figures, never a customer name or address.
`PRIVACY.md` gains its row in slice 1, in the same PR as the stored fields.

## Alternatives considered

**Fill-ups updating cost per mile.** This breaks frozen-per-record economics and makes a past
offer's verdict move. Fill totals remain observations for the dasher to read.

**Editing or deleting a ledger row in place.** This breaks append-only history and refold
faithfulness. A void event preserves the entry and the reason its amount leaves the total.

**Holding the write for the 10 second undo window.** Process death loses the held entry.
Commit appends the event; undo appends its reversal.

**A DataStore file for the ledger.** The entries are events, so Room projections rebuild them.
The hub also needs SQL sums by session.

**Voiding cash with `LEDGER_ENTRY_VOIDED`.** A cash tip is a column on a delivery row,
already owned by `DELIVERY_ADJUSTMENT`. A reversing adjustment keeps one owner.

## Consequences

Slice 1 builds the event types and payloads, codec support, pure folds, tables, DAO, and Room
migration. It adds `LedgerRepository`, `AnalyticsRepository.sessionNet`, `SessionNet`,
`OdometerRepository.totalMiles`, and the two preferences. Schema export and migration checks
accompany the stored fields, along with the `PRIVACY.md` row.

The hub does not render expense or fuel rows yet, on a dash or off one, and its money bar does
not include them. Adding them there needs a later decision.

Slice 3 waits for the answers to "Open before slice 3".
