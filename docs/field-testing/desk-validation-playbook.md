# Desk-validation playbook

**Purpose.** Resolve as many `## Next field test` checklist items as possible from a
post-dash data pull alone — no dev eyes needed while driving. Run these checks against
the pull under `~/dashbuddy/logs/YYYY/MM/DD/`: the Room DB (`sqlite3` on the pulled db —
remember the WAL sidecar), `app.log` (DEBUG firehose), `shareable.log` (INFO+ only), and
the capture envelope tree.

Classification of the current 0/2–1/2 items (audit 2026-07-12):
**fully desk-resolvable:** #749, #660p2, #688B, #630, #736, #752, #733, #501 (items 1-2
and 3), #159, #691-mechanism. **desk-partial** (data half here; UI half needs dev eyes):
#588, #315 H5, #438 B3/B4/B5, #688A, #693, #701-positive. **dev-eyes only:** #722
(bubble gas control has no durable trace — pure UI/DataStore surface).

> `app.log` matters: some decision logs are DEBUG by design (reducer steps). Pull it,
> not just the exported bug report.

> **SSOT caution:** the item list above is a dated snapshot (the README checklist is the
> live SSOT), and the grep strings below are literal copies of log messages verified
> against the code on 2026-07-12. A later rename of a log string breaks its grep
> *silently* — if a grep returns nothing where a hit was plausible, verify the string
> against the code before reading "no hits" as "invariant held".

## Step 0 — identify the build

**Do this first.** Every other reading in this playbook is a reading *of a specific build*, and
until PR #1066 the build had to be inferred (the 2026-09-05 pull inferred a pre-#1044 build from the
ABSENCE of log lines, because `:app:installDebug` reported the same `versionName=0.230.0` for two
different commits).

```bash
# the startup line — one per process, in app.log AND shareable.log (INFO, tag `App`)
grep -h -m1 -E 'DashBuddy .* starting' "$D"/app.log "$D"/app_log_rotated_*.log "$D"/shareable.log

# or off ANY periodic summary line — the build id leads it
grep -h -o -m1 'app=[^ ]*' "$D"/*.log
```

Both render `<semver>+<8-hex sha>` with `.dirty` appended when the tree had uncommitted changes.
The sha names the commit — resolve it:

```bash
git log -1 <sha>
git log --oneline <sha>..master   # what the phone was MISSING
```

A `nogit` sha or a `.dirty` suffix means the build is not reproducible from a commit; treat any
finding against it as provisional.

## SQL checks

```sql
-- #749  same-customer double-order job closes; next offer is its OWN job
SELECT eventSequenceId, jobId, taskId, storeName, payBasis, realizedPay
FROM delivery_records ORDER BY eventSequenceId;
-- expect: one drop per customer for the double job; the NEXT offer's rows carry a DIFFERENT jobId;
-- no unattributed spike for that stretch (see the #701 query).
-- also: grep '#749 coverage arm closed' app.log   (DEBUG, tag StateMachine — jobId + counts)

-- #660p2  orphan delivery categorized into its real dash
SELECT eventSequenceId, sessionId, sessionAssigned, netProfit, realizedPay, payBasis
FROM delivery_records WHERE sessionAssigned = 1;
-- expect: sessionId now non-null = the picked dash; frozen economics unchanged vs pre-assign.
-- guard rejections: grep 'DELIVERY_SESSION_ASSIGN' shareable.log  (WARN = a skip; INFO = the append)

-- #688B  per-leg mileage + the ONE-SIDED Σ invariant
SELECT jobId, taskId, milesToStore, milesToDropoff, realizedMiles
FROM delivery_records WHERE sessionId = :sid;
SELECT (SELECT SUM(realizedMiles) FROM delivery_records WHERE sessionId = s.sessionId) AS drop_miles,
       (s.lastOdometer - s.startOdometer) AS span
FROM session_records s WHERE sessionId = :sid;
-- expect: stacked drops each nonzero (not one lump + 0.0); drop_miles <= span ALWAYS
-- (undershoot expected; exceed = the mixed-basis double-count, a #688 Fix-1 regression).
-- one-time refold on first post-install launch: grep 'projector version' shareable.log

-- #630  stacked receipts split exactly
SELECT jobId, SUM(realizedPay) FROM delivery_records
WHERE payBasis IN ('DROP_SHARE','RECEIPT_TOTAL') GROUP BY jobId;
-- expect: = the receipt total per job; no $0/NONE row where a receipt existed.
-- tripwire (also the #756 promotion trigger):
--   grep '#630 mid-stack non-final receipt exit' shareable.log   → a hit = grab that capture session

-- #736 / #752  unassign produces NO paid/confirmed artifacts (either phase)
SELECT eventType, json_extract(eventPayload,'$.phase') AS phase,
       json_extract(eventPayload,'$.taskId') AS tid
FROM app_events WHERE eventType = 'TASK_UNASSIGNED';
-- expect: exactly one per abandon; phase PICKUP (#736) or DROPOFF (#752); then verify NO
-- PICKUP_CONFIRMED / DELIVERY_CONFIRMED / DELIVERY_COMPLETED shares that taskId, and no
-- delivery_records row for it. (A prior-frame DELIVERY_CONFIRMED before a dropoff-phase
-- unassign may exist — expected, row-inert.)

-- #733  multi-pickup job lands under a REAL store
SELECT jobId, storeName, storeKey FROM delivery_records;
-- expect: the multi-pickup drop carries a real storeName/storeKey, not NULL/Unknown.
-- grep -c 'D6 join miss' shareable.log   → <= 1 per drop (ideally 0), never the old x23 storm

-- #159  store entity resolution
SELECT storeKey, runningKey, chainDisplay FROM stores;
-- expect: runningKey non-empty (…|target|02426, not …|target|); BOTH stores of a stack keyed.
-- guard held on a payout-less close: grep 'downgrade averted' shareable.log

-- #691  receipt-less shop folds est. offer pay (mechanism half)
SELECT jobId, taskId, payBasis, realizedPay FROM delivery_records WHERE payBasis = 'OFFER_PAY';
-- expect: real $ (not 0); a stack shows ~equal halves summing to the offer total.

-- #701  over-attribution (negative path) — also the #749 unattributed check
SELECT s.sessionId, s.reportedEarnings,
       (SELECT SUM(realizedPay + COALESCE(cashTip,0)) FROM delivery_records d
        WHERE d.sessionId = s.sessionId) AS delivered
FROM session_records s;
-- expect: delivered <= reported on every session → no over-attribution callout.

-- #315 H5  Patterns data sanity (render half is dev-eyes)
SELECT taskId, (confirmedAt - arrivedAt)/60000.0 AS dwell_min FROM pickup_records;
-- expect: minutes-scale dwell values, not seconds/hours artifacts.

-- #438 B3 / B5  offer lifecycle + odometer arbitration (data halves)
SELECT eventType, COUNT(*) FROM app_events WHERE eventType LIKE 'OFFER_%' GROUP BY eventType;
SELECT taskId, realizedMiles FROM delivery_records;
-- expect: outcomes incl. OFFER_TIMEOUT coherent; accepts minted jobs WITH economics; mileage
-- deltas sane (no stuck-0 runs, no mid-dash reset shape).
```

## Log greps (shareable.log unless noted)

| Item | Grep | Expect |
|---|---|---|
| #749 | `grep '#749 coverage arm closed' app.log` | the coverage-arm close (DEBUG, jobId+counts) |
| #630/#756 | `grep '#630 mid-stack non-final receipt exit'` | ideally none; a hit = pull that capture session (and it's the #756 promotion trigger) |
| #733 | `grep -c 'D6 join miss'` | ≤1 per drop, ideally 0 |
| #688B | `grep 'projector version'` | the one-time 5→6 refold line on first post-install launch |
| #588 | `grep 'ShopRate'` | every line tagged `[<platform.wire>]`; the trailing `→ learned …/min (n=…)` shows the relearn trajectory (seed-reset visible as n resetting). `learned ?/min (n=0)` = nothing learned yet (an out-of-band sample folds nothing) — NOT a zeroed mean |
| #438 B4 | `grep 'OfferActionReceiver:'` | each tap line carries `(offer=<hash>)` (full hash, same rendering as OfferEffects) — exact-match it to the resolved `OFFER_*` event's hash; a mismatch = acted on the wrong offer |
| #731 | `grep -i 'notification listener'` | the per-event lines are the PRIMARY record: first disconnect per process at WARN, the rest (and all connects) at INFO, each with a running count. `grep -c` them for the flap rate; per process, `connects − disconnects ≈ process deaths` (itself a diagnostic — a kill never logs its disconnect). The `PipelineStats` summary fields (`notifListenerConnects=`/`Disconnects=`) are corroboration ONLY — the summary emits per 50 forwarded observations, i.e. only while actively sensing, so it is blind exactly when the idle-time flap is worst |
| #159 | `grep 'downgrade averted'` / `grep 'store-chain resolved'` | monotonic backstop held / shadow resolution milestones |
| #862 | `grep -e 'Capture scrubbed:' -e 'Capture backstop:'` | the privacy layer firing, now readable IN `shareable.log` (pre-#862 these lines redacted themselves to `[scrubbed:<marker>]`). Each names the marker by **id** — first two alphanumerics + length (`Tr12` = `Transfer out`, `Tr11` = `Transfer in`, `De11` = `Deliver to `); decode against `SensitiveTextMarkers.KEYWORDS` / `CustomerTextMarkers.MARKERS` (ids are pinned collision-free). A `[scrubbed:…]` line remaining in the export is now a REAL upstream leak, not our own diagnostic |

## Capture-tree checks

- **#501 items 1-2:** multi-order dropoff confirm frames sort into
  `captures/**/dropoff_multi_order_confirm/`, none in `**/UNKNOWN/`; envelope JSON shows the
  customer line masked `[redacted:xxxx]`, store + item count kept.
- **#501 item 3:** zone-arrival frames in `captures/**/pickup_zone_arrival/`, not UNKNOWN;
  spot-check labeled frames are REAL zone screens (over-match check); exactly ONE
  `PICKUP_ARRIVED` per warehouse visit in `app_events` (the rule is recognize-only and must
  not steal the bin-scan anchor).

### Dasher-banking sweep over the pull (#794 / #884)

Run this on EVERY pull — the Pledge check that no dasher banking text reached disk. Deliberately
outside the table above: these are raw copy-paste greps, and a `|` alternation inside a Markdown
table cell has to be escaped, which silently corrupts the copied command (the #870 lesson). Use
repeated `-e` instead of alternations, and run from the pull dir:

```bash
grep -rli -e 'available balance' -e 'dasherdirect' -e 'crimson' -e 'routing number' captures/
grep -rli -e 'transfer out' -e 'transfer in' -e 'cash out' -e 'instant pay' captures/
grep -rliE 'transfer +\$[0-9]' captures/      # the #884 amount-bearing button class
```

Expect **zero** hits in `captures/`. Hits in `shareable.log`/`app.log` are a different read: a
`[scrubbed:Transfer out]` placeholder there is the sink DOING its job (the marker name is our own
constant, not leaked text) — see the `#862` row above for how to tell that apart from a real leak.
`Transfer $<amt>` and `Transfer in` are the shapes that evaded the sweep before #884 — the class
leaks on the **click** path (the button node is serialized in isolation, so the window-level
sensitive rule never fires), so check `captures/**/click/**` specifically, not just screen frames.

## Known non-desk residuals

- **#722** (bubble gas control): no durable trace — dev-eyes only.
- Render halves of #315 H5 / #693 / #691-qualifier / #688A dialog / #438 B3 card visuals:
  inherently dev-eyes.
- **#588**: the persisted learned mean lives in the strategy DataStore (not pulled); the
  `→ learned` INFO suffix is the desk window into it.
