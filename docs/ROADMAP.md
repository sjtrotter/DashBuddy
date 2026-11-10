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
was triaged on 2026-11-10; the verdicts below come from that pass.

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


## 1. Now: privacy and money a tester would see

The Play build changes who can be hurt by a bug, so Pledge breaks in stored data and money that is lost or wrong come
first. All are small rule or code fixes with captures already in hand unless noted.

1. **#1396** a pickup frame rendered customer-card-first saves the customer's name as `storeName` in `app_events`
   (release builds too). Then **#1397** (merchant instruction naming the customer, debug captures).
2. **Offer recognition losing money:** #1343 a boost badge drops the whole card to UNKNOWN (accepted offer lost);
   #1528 a two-order shop offer is scored as a quick pickup; #1399 DoorDash 8.104.3's new offer push channel.
3. **#1506** the 2026 mileage rate changes on July 1 (shape the table for the #695 constants feed). Tax season.
4. **#1507** online hours double-count two apps and include paused time. It blocks #1518 and #1519.
5. Then tag **v0.232.0**: the first release that uploads itself.

## 2. Next: finish the look-back redesign (#1510)

#1507 → #1518 (Est. Profit/hr + Waiting) → #1519 (Est. Profit/mi) → #1520 (Spending) → #1521 (Home week card) →
#1513 (Plan opens on best hours) → #1514 (Miles and taxes, after #1506) → #1484 (stores ranked by profit/delivery).
Fold #1240 (every hourly figure names its denominator) into #1518. #1509 (ratings say when they were read) is a
one-line companion.

## 3. Alongside: tester polish (#1441 children, no decisions needed)

#1473 setup says saved after it saved · #1478 gas price source + failed lookup · #1482 cash-tip buttons always add ·
#1483 Settings counts · #1449 confirm "Reset to defaults" · #1498 Home tiles at 200 % text · #1472 48 dp targets ·
#1474 setup asks which vehicle · #1448 loading/empty/failed states (re-audit first) · #1417 README "Supported apps"
and its stale "Planned" list. Needs a design call: #1446 theme setting, #1479 offer-picking choices.

## 4. Correctness backlog with no decision needed (small, contained)

- **Money folds:** #1049 parse `optional` → #1051 label-based receipt rows (absorbs #921) · #1428 legacy pay
  correction guard · #1432 early-end receipt shares · #1490/#1491 stacked-job re-price · #1084 owed tests.
- **State:** #1140 End dash then backgrounded leaves the shift open (phantom online minutes) · #1056 settle gate
  re-admission on resume · #1347 waiting time re-anchors on resume · #1331 swap guard on shared store tokens ·
  #1335 JOB_ABANDON residuals · #1083 recovery clamp · #1097 double CONFIRM_DECLINE.
- **Recognition:** #1342 the side-nav drawer rule hides new DoorDash screens from UNKNOWN and the census
  (prerequisite for recognition health, #1571) · #1217 picker-row redact suffix · #1322 `CLICK_INTENTS` contract.
- **App and CI:** #1336 heads-up tap opens the full screen · #1351 chat line kinds · #1494 deep-link pop race ·
  #1308 SnapshotLibrarian cap · #1465 `.dirty` CI version · #917 odometer heartbeat + engine-death alarm (parts 1-2).

## 5. The signed-feeds and rules program (#1559, approved 2026-11-09)

Feeds: #1561 ADRs → #1562 accounts and keys (developer) → #1563 feed-contract → #1564 fuel publisher and #1565
transport → #1566 fuel prices from the feed (the EIA key leaves the app) → #1567 custom feed. Rules: #1568 batch
loader → #640 phone fetch and #641 tag-sign publishing → #695 constants feed → #1569/#1570. Census editor and health:
#1571, #1572, #1573 (each blocked by a census-repo issue), #1209. #192 stays the rules parent.

## 6. Needs a developer decision first

- **#1326** receipt→offer identity (options i-iii). Unblocks **#1120** (under-recorded pay on 09-20 and 10-11) and
  probably #1101.
- **#1119** link an offer to its job by evidence: accepts are still logged as timeouts. A spec with defaults exists.
  #1398 (late-confirmed decline recorded as a timeout) rides with it.
- **#1320 + #1309 slice 2** (Pledge): a rule-independent belt for recognized frames (you leaned to option 1).
- #1400 store an additional tip (and trace the double run) · #1401 vs #1529 Mark-as-delivered timing · #1316 does an
  unassigned accept count · #1354 correcting a typed odometer reading (then #1386) · #1076 restored offer re-arm or
  time out · #1081 deferred completion · #1337 TTS stream vs DoorDash's alarm chime · #1228 composite text inputs ·
  #1193 census admission figures · #1505 (ADR-0012 revision) · #1508 store wait on the offer card · #527 job
  lifetime · #964 ratings history · #1035 the running-total wheel · #998 the return-order flow · #1373 car-care
  receipts.

## 7. Gated on field data

- **One re-census pass** over the latest pull replaces six stale UNKNOWN batches from July to September (DoorDash
  8.93-8.97; the field now runs 8.104+): #912, #922, #988, #989, #1047, #1088, #1089, #1313, #550.
- **#301** support-chat unassigns leave phantom pickups (six dashes since September): frames are in the 10-05 and
  10-21 pulls, so desk triage, not a new dash.
- Uber: #826 (one Uber dash with a real accept), then #249, #250, #785, #1402, #1271's last journey; #251 board
  parse is designed and buildable.
- #1489 (needs the #1458 DEBUG line in a pull), #1529, #985 remainder, #897 pin chip, #741 zones, #816.

## 8. Long-term or parked

#193/#194 academic RFCs → #1137 and #1194 (#252 likely superseded by #1194's region cells) · #1113 evaluator
redesign and auto-accept (#1238, #1239; months of labelled data) · #579 voice accept · #1338 Android Auto · #1504
measured offer rate · #883 pixel-redacted screenshots · #1386/#1389 fuel economy and EV charging · #1204 on-device
drafter (mostly overtaken by #1559 track E) · #1138 census community half · #214 DSL onboarding · #505 replay
frontier · #590 phase 2 (after #1568) · #245 re-scope to the `contains("pay")` heuristic · #428 es/fr consent strings
and tap labels · #1349 accessibility description (after the Play review) · #1580 precise-location declaration, due
2027-01-27 · #246 counsel review (external) · #1271/#1272/#1276 test-suite tail · #1364 economy fields.

## Housekeeping from the 2026-11-10 triage

Likely done or superseded, pending the developer's OK to close: #84 (Play internal track live), #636 (no consumer),
#921 (into #1051), #823 (phases 1-2 shipped), #1177 (done in the census repo), #1488 (`AnalyticsRepository` is 703
lines). Close or rewrite after a ruling: #1135, #1272, #1289, #723. #1364's `in-progress` label is stale.

## Recently resolved (2026-10-05 → 2026-11-10)

- **Play:** #1411 rename to gig.log; #1413/#170 non-tool disclosure; #1414 build types; #1415 release pipeline;
  #1416 listing; #1582 privacy policy site; v0.231.0; internal track live 2026-11-10.
- **Look-back redesign:** #1511, #1512, #1516 shift timeline, #1517 checks and repair, #1515 Recap week + date picker
  + Month + Shifts list, #1540 store search, #1587 money thousands separator.
- **Earlier:** #1188/#1200 census review and envelopes, #1333 HUD slices S0-S3, #1357 vehicles (#1358-#1361),
  #1441 design decisions (#1442-#1447), #1547 CI speed.
