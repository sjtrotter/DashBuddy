# gig.log Roadmap (living doc)

**Updated:** 2026-11-10, from a review of all 164 open issues against master `dfb9a52eb`. When an issue's state
changes, move it here in the same PR. Companions: `CLAUDE.md` (architecture summary), `docs/architecture/0N-*.md`,
`docs/field-testing/CHECKLIST.md`, `docs/RELEASE.md`.

## Where the project is

gig.log is on Google Play: the internal-testing track is live and the closed (alpha) track is in Google review.
`PLAY_PUBLISH` is on, so a `v*` tag on master builds and uploads to internal testing by itself. The app reads
DoorDash (fielded daily) and Uber (thin: accepts are not detected, #826). The look-back redesign (#1510) is half
built: Recap is the week, with a date picker, a Month screen, the Shifts list, the shift timeline and store search;
the per-figure screens and Plan remain. The signed-feeds epic (#1559) is approved and not started. Every open issue
was triaged on 2026-11-10; the order below was reviewed by the council (Claude, Sol, Fable) the same day.

**Dated:** #1580 the Play precise-location declaration is due **2027-01-27** (about an hour of Console work). #1506
(the July 1 IRS rate change) must ship **before 2027-01-01**, when 2026 mileage gets filed. #1349 rewrites the
accessibility description once the closed-track review settles. #1412 (the Play epic) closes when #1417 and #1580 do.

## Strategic pillars (detail)

`CLAUDE.md` keeps the mission and one line per pillar; this is the detail (moved from `CLAUDE.md`, trim v4,
2026-11-06). Each pillar is defended by a separate architectural and licensing posture, so neutralizing one does not
collapse the others.

1. **Driver Sovereignty (the product).** Free local tier (True Net Profitability, bubble HUD, on-device session
   tracking, CSV export). Paid managed-cloud tier ($5/mo, $49/yr, $99 lifetime for the first 500 users) for sync and
   aggregation-derived market context. Source-available licence; auditable byte-for-byte.
2. **Distributed Integrity (the matchers).** The recognition rules, licensed Apache-2.0 (`matchers/LICENSE`) so they
   can be forked. They stay in-tree in the `matchers/` included build; the separate repo (#637) was closed as not
   planned on 2026-10-04. Signed remote rule delivery is supported by the in-tree `RulesetVerifier` (#416); the design keeps rules forkable, so if an upstream source is ever compromised, drivers can switch sources via configuration (ADR-0009). RFC #192.
3. **Academic Federation (the research).** Opt-in (k=10 cohort, on-device DP budget, edge PII scrub) anonymized
   aggregation across university-hosted instances under IRB review. No central corpus; only aggregate query results
   returned. AGPL-3.0 aggregator. RFCs #193 (aggregation) and #194 (academic federation).

Related: the UNKNOWN-screen census epic #1138 (privacy model ADR-0011: hash-only skeletons, k-anonymous vocabulary,
trusted installs; server #1157). The cloud-data-platform RFC #141 was closed 2026-07-12 with its un-built cloud half
folded into the pillar epics.


## 1. Now: release v0.232.0 to testers

The Play build changes who a bug can hurt. This milestone is what a tester's install needs; tag right after it.
Each line is one PR unless noted.

1. **Privacy, one PR (`pickup.json5` + Uber sensitive rules):** **#1396** a pickup frame rendered customer-card-first
   saves the customer's name as `storeName` in `app_events` (release builds too; the fix is a parse change that moves
   the parse golden) · **#1397** a merchant instruction naming the customer (debug captures) · **#1402 rule half**
   Uber's Driver's License screens have no sensitive rule (the block side of the Pledge; a text-anchored rule and a
   sanitized SENSITIVE fixture, no new capture needed).
2. **Gas prices on Play:** release builds read `EIA_API_KEY` only from `local.properties`, so every CI-built APK
   (v0.231.0 included) has a blank key and the automatic gas price fails for every tester. Gradle reads the key from
   an environment variable too, and the release workflow passes an `EIA_API_KEY` secret (**needs your OK to add the
   secret**), with **#1478** (a failed lookup says so instead of showing the old figure as "Regional average"). #1566
   later takes the key out of the app.
3. **Offers lost, one PR (`offer.json5` + `chrome.json5`):** **#1343** a "Very busy" boost badge drops the whole card
   to UNKNOWN and the accepted offer is lost, with **#1342** the drawer rule that copies the same reject and claims
   every closed-drawer frame (one byte-pin edit, not two; council default: option 1, `hasClickAction`). Confirm a real
   add-on card fixture exists for the negative corpus first.
4. **#1528** a two-order shop offer is scored as a quick pickup (11-01 and 11-05 captures).
5. **#1473** setup says "saved" before it saves, so first-install answers can be lost.
6. **Decision checkpoint (yours, no build):** #1326 receipt-to-offer identity and #1119 linking an offer to its job by
   evidence. Both lose real pay today ($21.40 on 10-26; $28.51 on 09-20); a choice here ranks their build ahead of
   the later look-back screens.
7. **Tag v0.232.0**, the first release that uploads itself.

## 2. Next: the look-back redesign (#1510), heaviest last

- Unblocked now, any order: **#1520** Spending · **#1521** Home week card · **#1513** Plan opens on best hours ·
  **#1484** stores ranked by profit per delivery.
- **#1507 online hours**, in two parts. (a) The overlap union (two apps at once count once) is a query change in
  `sessionTotals`; `ShiftPageModel.onlineMillis` must use the same rule. (b) Paused time needs a resume event (there
  is only `DASH_PAUSED`), a pause column, a schema bump and a refold, and a rule for old shifts with no resume marks:
  a design pass first, and **split `AnalyticsProjector` (1,020 lines, new issue) before (b) adds to it**. **#1140**
  (End dash then backgrounded leaves the shift open: 31.7 phantom online minutes) inflates the same figure and has
  its own open decisions (which end signal, what back-dating threshold); decide it with (b).
- Then **#1518** Est. Profit/hr + Waiting and **#1519** Est. Profit/mi (both blocked by #1507). #1518 adopts #1240's
  wording for its own figures; #1240 stays open for the HUD, offer card and voice.
- **#1506** the July 1 mileage rate, before 2027-01-01: no screen shows the deduction today (only the CSV export reads
  it; `MileageTaxModel` has no caller since the Time tab went), so it ships with or just before **#1514** Miles and
  taxes, which reuses `MileageTaxModel` or deletes it. Fix the stale path in #1506's body.
- #1509 ratings say when they were read (one line, any time).

## 3. Alongside: tester polish (#1441 children)

#1482 cash-tip buttons always add · #1483 Settings counts · #1449 confirm "Reset to defaults" · #1498 Home tiles at
200 % text · #1472 48 dp targets · #1474 setup asks which vehicle · #1448 loading/empty/failed states (re-check the list
against today's screens first; no explaining footers) · #1417 README "Supported apps" and its stale "Planned" list ·
**#917 part 2** a dasher-visible "stopped recording, tap to restart" alarm (OEM battery killers on testers' phones make
a silently dead recorder the likeliest data loss). Needs a design call: #1446 theme setting, #1479 offer-picking
choices. #1579 one-tap wipe (not a Play requirement; also the blunt answer to #1396's leftover raw name).

## 4. Correctness backlog (each has a council default so a builder can start)

- **Money folds:** #1049 parse `optional` (default: `optional: true`) → #1051 label-based receipt rows (absorbs #921)
  · **#1330** an unanchored job budget folds a false 0.0 mi into a frozen row (items 1 and 3; default: fold null, never
  wrong; item 2's recovery behaviour goes to §6) · #1428 legacy pay correction guard · #1432 early-end receipt shares
  · #1490/#1491 stacked-job re-price · #1084 owed tests.
- **State:** #1056 settle gate re-admission on resume · #1347 waiting time re-anchors on resume · #1331 swap guard on
  shared store tokens · #1335 JOB_ABANDON residuals · #1083 recovery clamp · #1097 double CONFIRM_DECLINE.
- **Recognition:** #1399 DoorDash 8.104.3's new offer push channel (log noise only; offers are unaffected) · #1322
  `CLICK_INTENTS` contract · #1217 picker-row redact suffix (rides with the #1320 choice) · **#301** support-chat
  unassigns leave phantom pickups on six dashes: frames are in the 10-05 and 10-21 pulls, so desk triage, not a dash.
- **App and CI:** #1336 heads-up tap opens the full screen · #1333 HUD S4 (prune chat lines older than 30 days, dead
  code, docs) · #1351 chat line kinds · #1494 deep-link pop race · #1308 SnapshotLibrarian cap (default: option 1, a
  loud drop) · #1465 `.dirty` CI version · #1549 parallel CI jobs and a golden-regeneration job (sets every PR's wall
  time) · #917 part 1 odometer heartbeat line · #1493 the offer-crediting shadow audit (log-only; must be in a build
  before a dash for a pull to carry it, like #1489).
- **Structure:** #1364 PR 2, the flat economy fields derived from the typed vehicle inputs (spec agreed, unblocked).

## 5. The signed-feeds and rules program (#1559, approved 2026-11-09)

Feeds: #1561 ADRs → #1562 accounts and keys (developer) → #1563 feed-contract → #1564 fuel publisher and #1565
transport → #1566 fuel prices from the feed (the EIA key leaves the app) → #1567 custom feed. Rules: #1568 batch
loader → #640 phone fetch and #641 tag-sign publishing → #695 constants feed → #1569/#1570. Census editor and health:
#1571, #1572, #1573 (each blocked by a census-repo issue), #1209; #1530 a census batch of 30 lost to `bad_request`
(needs the server log). #192 stays the rules epic, nested under #1559. #1561's ADR-0009 update also retires the
pinned-artifact production path.

## 6. Needs a developer decision first

- **#1326** and **#1119** (the §1 checkpoint). #1120 (under-recorded pay) waits on #1326; #1101 and #1398 ride along.
- **Privacy, two separate paths:** **#1320** a rule-independent belt for RECOGNIZED combined frames (you leaned to
  option 1) and **#1309 slice 2** rules for three UNKNOWN sheets (a belt for recognized frames does not reach them);
  #985's remaining "Switch to pick up at" sheet is one of #1309's three (10-09 pull). #1402's click half (a tap on a
  blocked screen reaching capture as an UNKNOWN click). Decide with #1397 so they ship close behind §1.
- #1507 (b) with #1140, in §2 · #1330 item 2 recovery · #1400 store an additional tip (and trace the double run) ·
  #1401 vs #1529 Mark-as-delivered timing · #1316 does an unassigned accept count · #1354 correcting a typed
  odometer reading (then #1386) · #1076 restored offer re-arm or time out · #1081 deferred completion · #1337 TTS
  stream vs DoorDash's alarm chime · #1228 composite text inputs · #1193 census admission figures · #1505 (ADR-0012
  revision) · #1508 store wait on the offer card · #527 job lifetime · #964 ratings history · #1035 the running-total
  wheel · #998 the return-order flow · #1373 car-care receipts · **#1135** a weekly total the dasher types in, with
  Recap naming the difference (wanted at all?) · **#1289** close the snapshot-durability gap as documented, or keep it
  open as you ruled on 10-07 · crash reporting beyond what Play Console's Android vitals already gives with no SDK
  (#84's never-built prerequisite; a privacy call).

## 7. Gated on field data

- **One re-census pass** over the latest pull replaces six stale UNKNOWN batches from July to September (DoorDash
  8.93-8.97; the field now runs 8.104+): #912, #922, #988, #989, #1047, #1088, #1089, #1313, #550.
- Uber: #826 (one Uber dash with a real accept), then #249, #250, #785 and #1271's last journey. #251's board parse
  is designed, but it still has a `blockedBy #245` edge that looks obsolete (the list-shaped offer state exists):
  remove the edge or say what #245 still gives it.
- #1489 and #1493 (need a build carrying their log lines, then a pull) · #1529 · #897 pin chip · #741 zones · #816.

## 8. Long-term or parked

#193/#194 academic RFCs → #1137 and #1194 · #1113 evaluator redesign and auto-accept (#1238, #1239; months of labelled
data) · #528 per-drop GPS $/mi · #579 voice accept · #1338 Android Auto · #1504 measured offer rate · #883
pixel-redacted screenshots · #1386/#1389 fuel economy and EV charging · #1138 census community half · #214 DSL
onboarding · #505 replay frontier · #590 phase 2 (after #1568) · #245 re-scope to the `contains("pay")` heuristic ·
#428 es/fr consent strings and tap labels · #246 counsel review (external) · #1271/#1272/#1276 test-suite tail (four
of #1272's five missing journeys can be built from existing captures; the Uber one waits on #826) · #1221 the
restaurant-wait replay fixture.

## Housekeeping decided by the council (2026-11-10)

Claude, Sol and Fable reviewed the triage's close list; majority decides, with the developer's own rulings kept.
Closed as done: #84, #823, #1177 (census follow-up filed there), #1488 (the projector split filed instead), #1357.
Closed as superseded: #636 (→ #640/#641), #723 (→ the Garage), #252 (→ #1194), #1204 (→ #1559 track E; reopen if
wanted). Folded: #921 into #1051. Rewritten: #1135 (weekly total the dasher types in; the ledger card dropped, the
pill read moved to #1035), #1441 (checklist mapped to its issues), #1333 (now the S4 cleanup; no longer blocks #1347
and #1351), #1364 (PR 2, unblocked). Kept: #1272, #1289 (developer's call), #192 (nested under #1559).

## Recently resolved (2026-10-05 → 2026-11-10)

- **Play:** #1411 rename to gig.log; #1413/#170 non-tool disclosure; #1414 build types; #1415 release pipeline;
  #1416 listing; #1582 privacy policy site; v0.231.0; internal track live 2026-11-10.
- **Look-back redesign:** #1511, #1512, #1516 shift timeline, #1517 checks and repair, #1515 Recap week + date picker
  + Month + Shifts list, #1540 store search, #1587 money thousands separator.
- **Earlier:** #1188/#1200 census review and envelopes, #1333 HUD slices S0-S3, #1357 vehicles (#1358-#1361),
  #1441 design decisions (#1442-#1447), #1547 CI speed.
