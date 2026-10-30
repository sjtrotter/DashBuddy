# ADR-0012: The Dasher's Ledger (Cash Tips, Expenses, Fuel Fill-Ups)

**Status:** Accepted; slice 1 (the data layer) built; slices 2 and 3 to come
**Issue:** #1333
**Date:** 2026-10-12
**Builds on:** #314 (CQRS read model), #650 (corrections family),
[`docs/architecture/05-analytics-read-model.md`](../architecture/05-analytics-read-model.md)
**Related:** #659 (frozen fuel/non-fuel split), #1134 (most recent correction event, same shape),
#688 (cash tips outside realized pay)

---

## Context

The new events, folds, tables, repository APIs, and preferences described here landed in #1333 slice 1.
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
through `appendUserEvent`. `appendUserEvent`, `logExpense`, and `logFuel` return the appended
sequence id, the target of a later `void`; `void` returns Unit.
`BubbleViewModel` intents call repositories; composables do not write.

`BOOKKEEPING_EVENTS` gains all three new types. An odometer stamp on any of them never feeds
odometer measurement, session mileage, or job mileage budgets.

### Categories

`ExpenseCategory { FOOD, PARKING, SUPPLIES, OTHER }` lives in `:domain`.
Room stores the enum name. `ExpenseCategorySerializer` also encodes the payload category as its
name and decodes with `ExpenseCategory.fromStorage`. Unknown names in Room or payload JSON read
as `OTHER`, preserving the expense amount and note, following the `MilesBasis` fallback pattern.

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
`CorrectionFolds.foldLedgerVoid` produce `ExpenseLoggedFold`, `FuelFillupFold`, and `LedgerVoidFold`
through `FoldOutcome`. All three folds carry no session context, including on skipped payloads:
returning context would mark the session touched and re-upsert its whole row from hydration.
`AnalyticsProjector` collects them as `Adjustment` decisions and calls the `LedgerDao` extensions
in `LedgerApply.kt` by primary key in event order inside the batch transaction, following the
`SessionReportApply.kt` pattern. The projected rows and watermark commit together.

`pricePerGallon` is derived at read time as the total divided by gallons. It is null when gallons
is null or zero, and is never stored. `odometerMiles` comes from
`OdometerRepository.totalMiles(): Double?`, gig.log's own GPS odometer total at the fill, null
until the persisted total has loaded. That odometer runs only while a dash is tracked, so it is not
the car's odometer: a cost per mile between two fills built on it covers dash miles only, and a
reader must say so. User events carry no event metadata (`appendUserEvent` passes none), so the
payload field is the only odometer reading on a fill-up.

### Undo is a second event

The write history is append-only. No entry is edited in place or deleted by the user.
Expense and fuel undo append `LEDGER_ENTRY_VOIDED(targetEventSequenceId)`.
Its projection stamps the target row's `voidedAt` with the first void event's timestamp.
One void per entry: the first wins; a repeat void changes zero rows and is a counted skip.
The original event and the projected row remain.

A void of a void, of an unknown sequence id, or of a later event is a counted skip.
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
carries null. The hub does not render ledger rows yet (see Consequences). The HUD's log reads a dash's events
(`getEventsForSession`, which matches on the session id), so an off-dash entry is stored but shown
nowhere until a later slice adds a read over null-session ledger rows.
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

`LedgerRepository.sessionNet(sessionId): Flow<SessionNet?>` reads the dash's projected rows.
It combines `sessionDetail` from its injected `AnalyticsRepository` with the two `LedgerDao`
totals. `AnalyticsRepository` depends on neither `LedgerRepository` nor `LedgerDao`.
Its terms are the hub's per-dash net, read from `SessionDetail`: delivered net is the sum of
`netProfit` over `SessionDetail.deliveries`, with a null net counting as zero exactly as the hub's
per-dash net does (`COALESCE(SUM(netProfit), 0)` in `AnalyticsDao`), plus `SessionDetail.cashTips`
and `SessionDetail.unattributedPay`. Keeping the hub's rule means the HUD and the hub never show two
different nets for one dash.

`SessionNet.expenses` carries the dash's non-voided expense total and `SessionNet.fuel` carries
its non-voided fuel total, kept apart. `SessionNet.kept(countExpenses)` adds the three money terms
and subtracts `expenses` only when `countExpenses` is true; `fuel` never enters kept (decided
2026-10-12, item 3 below; implemented in slice S2a). The HUD supplies `count_real_expenses_in_kept`,
default off.

The flow is null when `sessionDetail` is null, or when there are no delivery rows and both
`cashTips` and `unattributedPay` are zero. Expenses alone do not make it non-null.
Projector lag is accepted and disclosed on the HUD. The disclosure states
"after car cost, before tax; expenses not counted" or
"expenses counted", according to the preference.

### Where this ADR updates design.md

The S0 review changed two rules in `design.md`, which is updated to match: `totalMiles()` returns
`Double?` (null until the persisted total loads, so a fill logged at process start never stores 0),
and the pending active-drop tip outlives the dash end until its row appears or a bound passes.

### Open before slice 3 (developer decisions)

These come from the S0 review and change existing semantics or the design, so the developer decides.
Slice 1 does not depend on them; slice 3 does not start until they are answered.

1. A cash tip blocks the receipt re-price. `applyDeliveryAdjustment` stamps `driverAdjustedAt` on a
   cash-only edit, and `applyReceiptReprice` refuses any row with that stamp. A tip logged within
   90 s of the drop, before a collapsed receipt is expanded, leaves the delivery on its offer-pay
   estimate for good, and undoing the tip does not clear the stamp. Options: a cash-only edit stops
   stamping `driverAdjustedAt` (the re-price never touches `cashTip`), or cash tips get their own event.
2. A cash-tip undo cannot restore "no cash tip". A null `newCashTip` means unchanged, so the undo
   writes `0.0`. The drill-down already hides a zero cash line, but the projector's unassign guard
   (`row.cashTip != null` in `AnalyticsProjector`) then refuses to move that delivery out of its dash.
   Options: the guard and readers treat `0.0` as no cash, the payload gains an explicit clear, or the
   same own-event option as item 1.
3. Fuel may be counted twice in kept. Delivered net already subtracts the frozen cost per mile,
   which includes an estimated fuel cost, so subtracting fill-up totals as well charges fuel twice
   when the preference is on, and charges a whole tank to the dash it was logged on.
   **Decided 2026-10-12: kept subtracts expenses only; a fill-up stays a log.** Shipped in S2a
   (`SessionNet.kept`, `realExpenses` removed).
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
stay on the device. gig.log sends none of it anywhere. The rows live in the Room database,
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
migration. It adds `LedgerRepository`, `LedgerRepository.sessionNet`, `SessionNet`,
`OdometerRepository.totalMiles`, and the two preferences. Schema export and migration checks
accompany the stored fields, along with the `PRIVACY.md` row.

The hub does not render expense or fuel rows yet, on a dash or off one, and its money bar does
not include them. Adding them there needs a later decision.

Slice 3 waits for the answers to "Open before slice 3".
