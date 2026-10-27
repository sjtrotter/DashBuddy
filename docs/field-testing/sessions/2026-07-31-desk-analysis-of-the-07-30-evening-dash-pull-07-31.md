## 2026-07-31 — DESK ANALYSIS of the 07-30 evening dash (pull 07-31)

**Date:** 2026-07-31 (dash 2026-07-30 18:16–20:53) · **Platform(s):** DoorDash only (one dash) ·
**Branch under test:** `master` @ `3bff50dd`/`d2cfbc44` device build (07-30 ~14:22–14:23) · **Field
conditions:** one clean dash, 6 offers → 4 accepts / 2 declines / 0 timeouts, 4 complete job chains,
`reportedEarnings` $57.55, Evidence capture ON. Pull: `~/dashbuddy/logs/2026/07/31/`; device purged
post-pull.

### Headline: build vintage — predates the redesign entirely

First launch/install shows in `app_log_rotated_20260730_195727.log` at 14:50:48/51 (a fresh
install: `INFO/Consent: reconciled 4 capabilit(ies) from rule load (none granted — awaiting
consent)` at 14:50:52.611). `doordash.screen.overall_rating_explainer` recognized at 14:52:51
(a PR #963 rule, merged 07-30 14:22) proves the build is `master` @ ~14:22–14:23 — i.e. **the very
last commit before #968** (merged 07-30 15:14, ~1 hour later) and **before every redesign stage**
(#970/#972 pager, #973/#974 money tab, #975/#976 offers tab, #977/#978 Today home, and everything
after). No redesign UI evidence is obtainable from this pull; #970 and #973 never got their own
checklist items in the first place (no entry for either exists in this file), so there's nothing to
amend for those two beyond noting it here. `platformAppVersion: 8.91.7` rides both envelope
metadata and `PipelineStats`, confirming #937's stamp is live.

**Bonus: #967 fielded again, independent of its own fix.** The dasher browsed the Ratings hub
14:51–14:52 while DoorDash mode = Offline; `app_state_snapshots.ratings.capturedAt` never advanced
past the 07-29 all-null legacy stamp (the first fresh stamp lands 18:24:33, Online). That is exactly
the Offline-early-return bug #968 fixed — fielded on the one build old enough to still have it,
which is independent corroboration #968 was the right fix. New checklist item added (#967/#968,
0/2) to validate the fix once a build carrying it is on the phone.

### #914 (effect engine) — SECOND CONFIRMATION, retired at 2/2

Evidence ON, 12 screenshots (7 offer) spanning the whole dash 18:16:57–20:53:0x — past the arming
condition that killed 07-28, with the last effect firing after the dash ended. `app_events`
continuous seq 1326–1368, zero cliff, every milestone reconciles 1:1. Zero `ERROR`, zero
`PatternSyntaxException`, zero `Effect drain worker died unexpectedly`, zero filenames containing
`{`. This is the second independent clean pass (after 07-30's), so **#909/#914 retires from the
checklist.** Caveat carried forward, now permanently: nothing has ever actually thrown, so the
`Throwable`-isolation and drain-supervisor layers still have zero field evidence of catching a real
failure — that's a standing property of a healthy engine, not a gap in this closeout.

### Money: exact reconciliation

`reportedEarnings` $57.55 == Σ(`realizedPay`+`cashTip`) $57.55 exactly, zero unattributed. All four
`realizedMiles` == `milesToStore`+`milesToDropoff` to 3dp; Σ 22.444mi ≤ odometer span 22.461mi
(tightest undershoot yet, 0.017mi). Two jobs (H-E-B $9.95, Target $11.45) folded on `PayBasis.
OFFER_PAY` — **legitimately receipt-less**, not a missed receipt: DoorDash rendered no
`delivery_summary_*` at all for either job, and DoorDash's own reported total (57.55) equals the
four rows' sum to the cent, proving no post-delivery tip landed unaccounted. Job 274 shows the
fold's other arm working — a real receipt (13.70) beating its offer estimate (12.45) by +1.25. Store
resolution (#887), the orphan-offer half (#810 B1/B2), and the evaluator's fail-closed null-distance
path (#936) all read clean/unexercised this dash (see checklist sub-lines for the numbers).

### WARN/ERROR census

Zero `ERROR`. 12 WARN, all defended invariants: 5× `GRACE_COMMIT` timers, 5× `#843`/`#417`
fail-closed denials of `confirm_decline` (no granted capability — the clearest single-log evidence
yet that the two gates compose), and — the headline finding — 5× `Capture backstop`/`Capture
scrubbed` privacy hits: 4× the `De11` name-prefix backstop, and **1× the FIRST LIVE FIRE of #910's
`ID_MARKERS` node-ID backstop** (`textMarker=- nodeId=address_line_1` @18:30:50.332), on the exact
split-node `Delivery for` pre-render sheet the issue's marquee case describes. All 12 scrub/backstop
WARNs appear verbatim in `shareable.log` with marker ids intact and zero `[scrubbed:…]`
placeholders — **#862's first actual confirmed pass** (bumped to 1/2; 07-29 was un-exercised, not a
pass). Also incidentally corroborates #835's `scrubbableStrings()` enumeration — no raw sibling
field survived next to the masked node.

### Checklist sweep

**Retired (2/2, deleted from the checklist, receipts above/below):** #909/#914 (second clean pass,
above). #885/#886 (a third straight textbook pass — all four customers masked, maneuver cluster
masked, `pickup_navigation` stayed raw). #889 (a second clean pass with zero short-token hex
leaks; the customer-name exemption held). #887 (a second clean incremental-path pass — zero orphan
`stores` rows out of 29, zero `superseded store entity kept:` WARNs).

**Bumped to 1/2:** #910/PR#931 (the ID-backstop first fire, above), #937/PR#960 (version stamp
confirmed live + false-positive half clean), #962/PR#963 (recognition confirmed + the #967 bonus
finding), #884 (true-positive DasherDirect visit — `Tr12`/`sh30` fired, zero raw banking terms
anywhere in the pull), #862 (above).

**Held, note appended (not bumped):** #895 (a NEW residual — `dropoff_photo` is a 9th fused-`Apt`
surface PR #927's 8-surface list missed, filed #986), #936 (clean null across all 383 lifetime
`offer_records`, not a confirmation), #938/#942/#944 (un-exercised — UI/locale checks a desk pull
can't answer), #888 (a third family confirmed — the full geofence help flow — but its own
`Continue / Go back` sheet is a new UNKNOWN residual, filed #989), #843 (a fourth mechanism
corroboration, this time a genuinely fresh install), #810 B1 (a fourth clean false-positive pass),
#810 B2 (same shape — zero input, correctly nothing resolved), #859 (still Uber-gated/supporting
only; one minor near-duplicate screenshot noted).

**Amended (redesign items, build predates them):** #977 and #975 marked NOT TESTABLE on this
build. #973 and #970 were never added to the checklist by their stage PRs — nothing to amend.

### PII sweep findings (the Pledge section) — three new, all filed

- **F1 / #985 (P1, Pledge):** the Timeline order-detail address sheet — an id-less **and**
  marker-less block (street address, city/ST/ZIP, a gate-access free-text note naming a
  building+floor, a bare 3-digit likely entry code) reaches two UNKNOWN envelopes unmasked. The
  sibling `Deliver to <name>` node in the SAME tree WAS scrubbed (the `De11` backstop fired) — this
  is the documented #806 residual (no prefix scan or ID_MARKERS can reach an id-less,
  marker-less block), now fielded with a gate code attached. Needs a recognizing rule + `redact`;
  a scrub layer structurally cannot own it. Debug-only exposure.
- **F2 / #986 (P2):** `dropoff_photo` — a 9th fused-`Apt` surface #895/PR#927's 8-surface list
  didn't cover; three captures show `Apt/Suite [redacted:<4hex>]` (hex, not plain) while the same
  dash's `dropoff_handoff` correctly shows plain `Apt [redacted]`.
- **F3 / #987 (P2/P3, dev ruling queued):** the recognized `earnings_deposit` notification persists
  "…deposited to your DoorDash Crimson account" verbatim — `Crimson` is in `SensitiveTextMarkers`
  and the same push class was correctly dropped twice when UNRECOGNIZED, but a rule recognizing the
  surface bypasses that backstop entirely. Either give the rule a notif-level `redact` or document
  the exemption (it is the dasher's own earnings figure).
- Dasher-banking sweep (`transfer +\$[0-9]`, `transfer in`, `available balance`, `routing number`,
  `cash out`, `instant pay`): **zero** hits. Customer-name masks: zero raw, all four stable across
  surface forms. Maneuver cluster (#886): fully masked on dropoff/generic, raw by design on
  `pickup_navigation`. `shareable.log` (220 lines): zero raw tokens, zero `[scrubbed:…]`.

### UNKNOWN census (47 screen / 40 click / 4 notification) → two more filed

**#988:** ratings-redesign residuals post-#963 — the rating-FACTOR expanded drill-down and the
rewards-spend screen, both still UNKNOWN (the top-level explainer itself IS recognized). **#989:**
the geofence help-flow's own `Continue / Go back` confirmation sheet, a residual inside the
now-3-of-8-confirmed #888 family. Offer-card inflation (#922) still open, 3 more frames — no new
issue, existing one covers it. Ratings/shop-error/task-detail chrome otherwise unremarkable.

### Session work log (this desk session)

Issues filed: **#985** (P1 Pledge — Timeline address sheet, F1), **#986** (F2, #895 residual),
**#987** (F3, Crimson notification, dev ruling queued), **#988** (ratings-redesign UNKNOWN
residuals), **#989** (geofence Continue/Go back residual). Checklist: 4 items retired (#909,
#885/#886, #889, #887), 5 items bumped to 1/2, 10 items held with an appended dated note, 2 items
amended NOT TESTABLE, 1 new item added (#967/#968).
