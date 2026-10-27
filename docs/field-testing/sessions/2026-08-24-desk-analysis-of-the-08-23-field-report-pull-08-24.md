## 2026-08-24 — DESK ANALYSIS of the 08-23 field report (pull 08-24): nothing was lost — gross was zeroed by a fake $0 report meeting one COALESCE; DoorDash 8.93.7 removed every money id anchor

**Date:** 2026-08-24 (dashes 2026-08-13 ×6, 08-14, 08-18, 08-23 ×2) · **Platform(s) tested:**
DoorDash · **Branch under test:** `master` @ `e903a6e2` (post-#1028 — CONFIRMED from the device:
installed 2026-08-12 08:40, after the last master merge; one uninterrupted app process from then
through 08-24 05:48) · **Field conditions:** desk analysis of pull
`~/dashbuddy/logs/2026/08/24/` — 689 captures (688 DoorDash / 1 Uber), 5 logs (79,779 lines),
DB + WAL, DataStore prefs. Clean-slate slice 08-10→08-24 (device purged after the 08-09 pull,
and purged again after this one). Issues filed this analysis: **#1029–#1036**.

One correction to the 08-23 entry's framing up front: the "Monday 08-17" dash was **Tuesday
2026-08-18** (18:38–20:15, $42.10) — no session exists on 08-17.

### Bugs

**1. RESOLVES 08-23 item 1 (the $0 week) — both hypotheses refuted; the read model was complete
and correct the whole time. Gross alone was $0, from two stacked defects → #1030 (+ #1029).**
- *Hypothesis (a) wipe-without-refold:* **refuted.** The v9→v10 wipe ran once, 08-09 23:40:10, and
  completed 5.1 s later (`Analytics backfill complete: 1538 events → 83 deliveries, 72 sessions,
  409 offers`). No wipe at the 08-12 install (version unchanged).
- *Hypothesis (b) dead projector:* **refuted.** The projector drains continuously in-process; all
  five of the week's deliveries folded within ~50 ms of their events (seq 1628, 1637, 1656, 1657,
  1668), watermark `(1, 1669, 10)` = fully drained, **zero ERROR lines in 79,779**, one process for
  11.5 days, zero restarts.
- The actual chain, verified end to end: DoorDash 8.93.7 renders money as id-less digit wheels, so
  every money parse died silently (**#1029** — `final_value`/`earnings_ticker`/`running_total_pay`
  all 0 hits across 689 fielded files vs 10–15/15 in the corpus, whose newest receipt fixture is
  2026-04-19); `Session.runningEarnings` therefore never left its non-nullable `0.0` default; every
  `early_offline` DASH_STOP stamped that as `totalEarnings: 0.0` (seq 1638/1658/1669); the fold
  stored it as `reportedEarnings = 0.0`, **not NULL**; and `AnalyticsDao`'s
  `COALESCE(s.reportedEarnings, d.deliveredPay, 0)` (three sites) let the stored 0.0 win, so the
  `deliveredPay` fallback built for exactly this case never fired (**#1030**, with the two-part
  fix — null stamp + `NULLIF`; the DAO half alone heals all history read-side). 41 of 42
  `early_offline` sessions all-time carry the hard 0.0; this was simply the **first
  all-`early_offline` week**, which took gross to exactly $0 and tripped the severe
  `over-attributed $78.50` review flag.
- What the screen actually showed Sunday evening, reproduced from the pulled DB: recap hero
  **"You kept $65.94"** (correct), money card **"$0.00 came in. $-65.94 went to the car."**,
  pay-mix "not recorded", gross $0 on every day bar. Not transient — that window still renders
  this today. The `$-65.94` glyph order is its own cosmetic bug → **#1034**.

**2. RESOLVES 08-23 item 2 — the delivery DID land (seq 1668, H-E-B, OFFER_PAY $16.70, net
$15.36). What was lost is the receipt's real pay, by two INDEPENDENT misses → #1029 + #1033.**
The receipt sheet appeared (the slice's only occurrence), was **recognized** — and (i) the
completion's 2.5 s `GRACE_COMMIT` fired 17:35:19.877, 1.3 s **before** the expand tap
(17:35:21.191), and (ii) even the expanded frame parsed nothing because the anchors are gone
(`DELIVERY_COMPLETED` carried `totalPay: 0.0, payBasis: null`; the #859 filename fail-safe saved
`Delivery.png` — the `{totalPay}` token was null). The 08-23 entry's instinct to replay this is
right, and the fixture set is named in #1033 — but the replay only reproduces the timing seam;
the parse seam is #1029.

**3. Pledge leak — `pickup_issue_menu` has no `redact` block; a recognized envelope ships
`"For Doug P. • Sukhothai Restaurant"` raw → #1031.** 1 of 6 fielded frames. Its four sub-flow
siblings already mask this exact shape (`hasTextStartsWith: "For "` + `keepPrefix`), so the fix is
copy-paste. Everything else in the PII sweep came back clean: all 12 `Apt/Suite` masks are plain
`[redacted]` (no 4-hex — #986/#934 working), all `Deliver to` masks carry hashes, raw names on
`pickup_navigation` are merchants (the documented #886 exception), zero PIN/gate-code/
`Customer Notes` hits, zero dasher-banking hits anywhere including clicks. Known residual
unchanged: `maneuverView` click envelopes on UNKNOWN screens carry raw road names.

**4. The dash-end summary sheet (`EarningsBottomSheetWorkflowActivity`) — the SOLE source of
`reportedEarnings` — recognized once in 15 days, and its misses leave no envelope → #1032.**
On 08-18 20:15 and 08-23 15:36 its frames fell to UNKNOWN with `captured=false` (FrameGate
content-hash suppression); on 08-23 17:35:32 it produced no snapshot at all. A recognition rot on
this surface is currently undiagnosable by construction.

### Field UX context

**5. REFINES 08-23 item 3 (and retires checklist item #999 as broken).** The receipt absence is
real — one `delivery_summary_*` pair and one `dropoff_completed_confirm` in 15 days — but the
one time the receipt DID appear (in-zone, 08-23 17:35) **we recognized it and still parsed
nothing** (#1029). So "DoorDash stopped showing it out of zone" is true and is still only half
the story; the in-zone half is our defect, not theirs. #999's two-halves verification is
superseded: the in-zone half is now #1029's field validation, the out-of-zone absence got its
second sighting (08-14 / 08-18 / 08-23-early all receipt-less), and the item leaves the
checklist.

### Research / design

**6. ANSWERS 08-23 item 4 (the running-total oracle) — the data is there, and it reconciles TO
THE CENT → filed as #1035 (blocked by #1029).** The `earnings_pill` wheel appears in 51 captures;
at every settled checkpoint DoorDash's figure equals our Σ `realizedPay` exactly (5/5, e.g.
`$61.80 This week` = $42.10 + $19.70). The oracle would have corrected nothing this slice except
`reportedEarnings` — precisely the $0 gross. **Mandatory caution, now quantified:** 5 of 24 wheel
reads are mid-spin garbage and three are well-formed-but-wrong (`$470.00`, `$580.00`, `$70.00`) —
any consumer needs a settle gate, not just a currency regex. Also free today: `running_total_pay`
still lives on `pickup_pre_arrival`/`dropoff_pre_arrival` (8 captures, correct values) but is
parsed-then-discarded, and `TimelineFields` still falls to `updateSessionFields`' `else` — both
noted in #1035.

### Money reconciliation

Attribution was **perfect** all slice — the failure was purely the gross side (#1030):

| Dash | Drops | Σ realized | reportedEarnings | DoorDash's own figure | Verdict |
|---|---|---|---|---|---|
| 08-13 15:43 | 1 | $21.45 | **$21.45** (summary_screen) | $21.45 wk | exact |
| 08-14 17:03 | 2 | $48.34 | 0.0 (early_offline) | $69.79 wk (= 21.45+48.34) | attribution exact, gross lost |
| 08-18 18:38 | 2 | $42.10 | 0.0 | $42.10 wk | attribution exact, gross lost |
| 08-23 14:46 | 2 | $19.70 | 0.0 | $61.80 wk (= 42.10+19.70) | attribution exact, gross lost |
| 08-23 16:56 | 1 | $16.70 | 0.0 | $16.70 dash | attribution exact, gross lost |

All five week drops stamped `offerPayAttribution: PER_OFFER_STORE` (the #997 ladder's best arm,
zero degrades this week; the slice's one degrade — 08-14 `STAMP_FALLBACK` on a store-less drop —
still reconciled exactly). Σ accepted quotes = Σ realizedPay = $78.50. No null-session
deliveries, no orphan offers, one edge-gated D6 join miss (08-14, as designed).

### Verification TODOs / system health

**7. WARN/ERROR census: 66 WARN, 0 ERROR, 0 restarts.** All five WARN families known-benign or
the privacy layers working. 38× `Denied confirm_decline … (fail closed)` — the quick-decline
capability is STILL ungranted on the device, so #577's auto-confirm was inert all week (the
standing to-do before the next dash). #909 family all clear; TTS 28/28 utterances succeeded
(#991's recovery ladder never exercised — count as no-regression, not validation); recognition
health #937 never tripped (UNKNOWN ratio 14.4–16.7%, at baseline); version stamping working
(`8.93.7` on 651 envelopes — which is how #1029 is datable to the 08-09→08-12 window).

**8. UNKNOWN census: 175 of 689 (25.4%).** Families worth rules batched into #1036 (pause menu —
which renders the running total, "Drop off steps", photo-zoom viewer, uncollared offer card,
47-item shop pre-arrival variant, ~14 notification wording variants), alongside #1036's primary
ask: a rule that MATCHES but parses all-null must be loud — the #1029 class was silent by
construction and the frozen corpus structurally cannot see it.

**9. Checklist outcomes this pull:** #1005 **VALIDATED 2/2 → retired** (8 clean `<STORE> Order`
rows across 4 dashes, incl. the bare `Dropoff` fallback on the store-less drop, exactly as
specified); #999 **BROKEN → retired to this entry** (item 5 above); #1000 → 1/2 (the v10 refold
healed the 08-08 Zaxbys accept: `linkedJobId` set, `storeKey` NULL — the correct fail-null
shape); #996/#997 → 1/2 (shape (a) confirmed; (b)/(c) unseen); #986/#934 → 1/2 (12/12 clean);
#991 noted, stays 0/2; #985/#992-#995/#924 no evidence (no such surfaces fielded — and the
`doordash.screen.earnings` zero-corpus concern from the 08-23 entry's item 6 remains untested:
no Earnings-tab capture came home; `dxdr_nav_host_fragment` 0 hits anywhere).
