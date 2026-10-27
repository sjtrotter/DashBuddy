## 2026-09-26 (desk analysis of the 09-27 pull — #1118 retires at 2/2; money to the cent; a decline merged into an ACCEPT; a receipt skipped on the ordinary photo path; the dasher's own Crimson transfer in the DEBUG log)

**Date:** 2026-09-26 · **Platform(s) tested:** DoorDash (app **8.99.20** on every envelope) · **Branch
under test:** `master` at `1b596f74` (build `0.230.0+1b596f74`, read off the logs per PR #1066) — the
**first field run of PR #1125** (#1122/#1123 drop-off sheet redactions) and the second of PR #1118. ·
**Field conditions:** one long dash — session **754** 10:43:27 → 18:09:06 (`summary_screen`, $209.22
reported, 7 drops, 75.85 mi span). **20 offer cards: 7 accepts (DoorDash's own `Offers accepted 7 out
of 20`), 12 declines, 1 untouched** — our ledger says 19 received / 5 accepted / 12 declined / 2
timeouts, and the two gaps are Bugs 2 and 3. No pause, no force-stop, no unassign, no return order,
no stacked job, no support removal. One process (startup 10:38:43), `restarts=0`, `mappingFailures=0`,
`notifListenerDisconnects=0`, **zero ERROR lines**, 35 WARN, no `RecognitionHealth` /
`LocaleBoundary` / `TtsHealth` notice. 529 capture envelopes; log coverage continuous 09-26 10:38 →
09-27 11:07 (4 rotations + `app.log`). Pull dir `~/dashbuddy/logs/2026/09/27`, device purged after
verification; one raw-PII envelope purged from the pull (pseudonymized copy at
`~/dashbuddy/fixtures-pending/2026-09-27/`), and one DEBUG log line holding the dasher's own Crimson
transfer purged from this pull AND the previous one (Bug 4). Desk report:
`~/dashbuddy/logs/2026/09/27/DESK-ANALYSIS.md`. **Money:** $209.22 reported = $209.22 recorded (6
drops `DROP_SHARE`, 1 `OFFER_PAY`), to the cent.

### Bugs

1. **#1116 recurred a third time — now a per-dash leak on 8.99.20.** `timeline_task_detail` claimed
   the sheet's first frame at 13:13:34.215; its second frame (no `Copy address`) fell UNKNOWN 249 ms
   later with the street line, city/ST/ZIP and a **bare 3-digit TextView** (*hypothesis:* unit or gate
   code) raw; the `Deliver to ` backstop scrubbed only the name (WARN 13:13:34.443). Shape: `Close
   sheet` (desc) + `[redacted]` + `<N> <Street> Rd` + `<City>, TX <ZIP>` + `Leave it at my door` +
   `<NNN>`. Raw file purged; comment on #1116. The anchor needs the `Close sheet`-only render.

2. **#1069 fielded again — this time a DECLINE merged into an ACCEPT.** Offer `321c4cf0` ($10.86,
   H-E-B) was HUD-declined (`OfferActionReceiver: decline_offer` 14:05:54.546, tap 14:05:55.502, the
   confirm sheet at 14:05:56.063); every frame after it was flow-less (`side_nav_drawer` 14:05:58.8,
   `ratings` 14:05:59.741, `side_nav_drawer` 14:06:10.870), so it stayed pending. The next card
   `c78f7cdb` ($25.50, 14:06:11.876) merged into it as a variant — same `presentationKey` `e30e80e8…`,
   shared by **14 of this dash's 19 offers**. Ledger: `OFFER_RECEIVED 321c4cf0` (seq 3295) →
   `OFFER_ACCEPTED c78f7cdb` (seq 3296, `presentedAt` = the FIRST card's 14:05:51.433, dt 55.1 s). The
   $10.86 decline is gone, the accepted row carries a presentation time it never had, and the $25.50
   card was not spoken (19 TTS for 20 cards). Money unaffected (job …-764 priced from its receipt).
   *Hypothesis to check in the fix:* a variant that inherits the first card's `declineSheetSeenAt`
   would judge a click-less decline of the SECOND card against the first card's sighting.

3. **#1119 — 2 of 7 accepts died, both the HYBRID `waiting_for_offer` shape, no money lost.** The leave
   edge was the "Looking for offers" header over an already-painted pickup card (`user_name_label
   'Delivery for'` / `user_name '[redacted:…]'` / `Arrived at store` or `Directions`): `c24998f7`
   ($51.64) → job …-755 at +12.2 s (countdown 43 s — nowhere near expiry; `waiting_for_offer/
   2026-09-26_11-05-58-816…64940a`), `72a7769c` ($24.63) → …-773 at +40.2 s (`…17-11-31-387…84e8c3`).
   Both rescued by their receipts (`DROP_SHARE`). No `OFFER_EXPIRY` fired this dash, so the
   expiry-beats-pickup sub-shape from 09-24 did not recur. Comment on #1119.

4. **The dasher's own Crimson transfer amount sat in the DEBUG firehose (#1131, new).**
   `ObservationClassifier.kt:317` logs `UNKNOWN notification — $rawText` at DEBUG **before** the
   sensitive gate runs; at 18:10:12.612 DoorDash Crimson's "Transfer complete … $<amount> has been
   transferred out of your DoorDash Crimson account" push was logged whole, and four ms later the gate
   correctly dropped the capture (`Capture scrubbed: … 'Cr7'`). `shareable.log` is clean — the INFO+
   sink never saw it — but `app.log` is stored on device and pulled on every import, and the Pledge
   says the dasher's banking is never stored. The previous pull's logs held four more of the same
   class; all purged by hand. Adjacent noise: the same line fires ~1 Hz on the empty ongoing-dash
   notification (10 435 empty lines this dash, 26 894 the previous pull). Dev ruling on #1131.

5. **A receipt skipped on the ORDINARY photo path — the 09-24 receipt-less shape is not path-specific.**
   Drop …-758 (offer `6ddac9fe`, $31.44) completed through `dropoff_photo` 12:51:59 → `take_photo` →
   `dropoff_photo` 12:52:08 → `side_nav_drawer` 12:52:10 → `waiting_for_offer` 12:52:11, and **no
   `delivery_summary_*` frame and no `This dash so far` sheet ever appeared**; the next offer card
   arrived at 12:52:19; the idle wheel at 12:52:11 still read the pre-drop total. `DELIVERY_COMPLETED`
   came from the `GRACE_COMMIT` at 12:52:21.5 and the money survived only because the accept WAS
   recorded (`OFFER_PAY` $31.44; DoorDash's next counter read $107.63 = 51.64 + 31.44 + 24.55
   confirms it). This **contradicts the 09-21→24 entry's hypothesis** that the receipt is skipped on
   the geofence / `Mark as delivered` path: both of those paths DID show their receipt this dash
   (16:27 …-767 after a geofence override; 17:10 …-770 after `Mark as delivered`). *New hypothesis,
   one sample:* DoorDash skips the receipt when the next offer is imminent. Either way, a receipt-less
   drop behind an unlinked accept loses its money (the 09-24 $17.85 shape) — comment on #1119.

### Field UX context

- **The Dash Control dwell recorded as a decline this time.** One along-the-way decline landed on
  `on_dash_map` again and resolved at 9.8 s after the sheet — inside the 15 s window — so the
  characterization in the previous entry holds: it is a race against the window, not a broken path.
- **The confirm-decline capability is still un-granted:** 14 × `Denied confirm_decline … (fail
  closed)`; `expand_earnings` IS granted (6 receipts → 6 auto-expanded). The dev has not re-Allowed
  confirm-decline since the 09-26 reinstall.
- **The dev's weekly-earnings question (asked 09-27 while dashing):** DoorDash's app shows $601.02
  for the week; this Monday-week's nine dash summaries in `session_records` sum to **$575.57** and
  `Σ delivery_records.realizedPay` to **$557.72** — the $17.85 gap is the 09-24 lost drop (job …-740,
  `payBasis NONE`), and the remainder of DoorDash's figure is presumably the 09-27 dash in progress
  (not pulled). The "reported as bonuses & other" impression is the `PayMix` residue by construction:
  `bonuses & other = gross − Σ basePay − Σ tip`, and `basePay`/`tip` are stamped only on a sole drop
  with an itemized receipt, so every `OFFER_PAY` row (707's two drops $45.50, …-758's $31.44) and every
  collapsed-receipt row lands in that bucket whole. A design pass on making GROSS the hero of the
  historical record was launched at the dev's request (`~/dashbuddy/design/2026-09-27-gross-hero/`).

### Verification (desk)

- **#1118 / #1104 transition outcomes — CONFIRMED 2/2, RETIRED (moved here).** 5/5 `OFFER_ACCEPTED`
  are task-surface inferences, each with a job (no over-inference); 12/12 `OFFER_DECLINED` are
  confirm-sheet inferences, sheet→exit 1.75–11.37 s; 0 lost declines, 0 genuine timeouts, 0 accept /
  decline click envelopes. Residuals belong to #1119 (Bug 3) and #1069 (Bug 2). Two clean runs:
  09-21→24 (10/10 accepts, 44/44 declines) and 09-26 (5/5, 12/12).
- **#1122 + #1123 (first run of PR #1125) — CLEAN, not a confirmation:** check 1 passes (4 UNKNOWN
  `drop_off_workflow_host_fragment` hits, all text-free loading skeletons 0.3–0.6 s before a recognized
  `dropoff_pre_arrival`); checks 2–3 UNEXERCISED — on all 14 `dropoff_pre_arrival` + 2
  `dropoff_handoff` envelopes the bottom-bar slot between `Settings` and `Safety` is an empty View
  (no name rendered), and there were 0 `dropoff_workflow_sheet` frames and no header-less render.
  Every pre-arrival grades correctly (hashed `Deliver to` + street, plain city / unit / note, the same
  hex per customer across frames). No `Apt/Suite`-only UNKNOWN, 0 `Building Name`.
- **#1102 (retired) — re-observed clean:** 6 receipts → 6 expanded; one `Could not find any live node`
  followed 0.8 s later by a successful tap (the throttle restore's second field sighting); 2 benign
  `Throttled` after a success.
- **#1120 / #1033 L2 — not exercised, no damage:** 6 `DELIVERY_RECEIPT_REPRICE`, all single-drop
  no-ops, `receiptRepricedAt` NULL everywhere, no stacked job.
- **#1103 — clean negative (4th):** 0 `receipt running-total read refused`; the dash started from
  `waiting_for_offer` with no receipt on screen; 5 `SESSION_PAY_SETTLE` fires all inside 754.
- **#1108 — clean, not a confirmation:** `Σ realizedMiles` 75.83 ≤ span 75.85 mi, zero negative
  `realizedMinutes`, no stacked job.
- **Decline target bound — bind half holds a third time** (6 × `Single verified candidate for
  decline_offer` for 6 HUD declines, 0 `No 'declineButton' target bound`, no bindShortfall at all);
  confirm half unmeasured (un-granted).
- **#1063 — desk half clean a 4th time** (20 cards → 20 `Rule LOG` → 20 screenshots → 20 chat cards; 0
  `Replaced by new offer` / `Superseded`; the 14 offer-shaped UNKNOWN frames are Accept-less
  half-renders; the one card without its own row/voice is Bug 2, a different class).
- **Unexercised:** #1121 (0 `+$` / `+N stop`), #1107 (0 `description_text_view` / "Drop off steps"),
  #998 (no refused or returned delivery), #301 (0 `TASK_UNASSIGNED`, 7 `PICKUP_CONFIRMED` = 7 real
  jobs), #1054 ×2 (one process, startup replayed 1 obs with no session; 0 `DASH_PAUSED`; summary end
  at 2.501 s), #1059 (the four ids 0 tree-wide; `sensitive.red_card` 0 over 141 `pickup_shopping` +
  51 `shopping_item` H-E-B frames — over-match passes a 7th time; `dasher_direct` dropped 74 incl. a
  mid-dash visit at 14:05:41), #1058 (0 `alcohol_dropoff_ic_scan`, 0 `dropoff_workflow_sheet`).
- **Censuses.** WARN by tag (35): `Effects` — 14 confirm-decline denials, `GRACE_COMMIT` by design, the
  expand-tap lines above; `Pipeline` — `Capture backstop` hits (`Deliver to ` on the Bug 1 frame) and
  `Capture scrubbed` (`Cr7` ×1, DasherDirect); `Odometer` 0 rejections. parseShortfall all benign
  baseline, no bindShortfall. PII sweep over all 529 envelopes: 79 `Stephen T` (the dasher's own
  first + last-initial, allowed), merchant addresses on `offer_popup` (raw by design, #886),
  `Customer Notes: [redacted…]` ×2 masked, all 11 `customer_message` pushes `Message from
  [redacted:<4hex>]`, `earnings_deposit` masked (#987 holds); banking sweep over `captures/` 0.
  UNKNOWN families (53 window / 58 click / 1 notification): 14 offer half-renders (#595), the id-less
  `This dash so far / Total online time / Offers accepted N out of M / Continue dashing` sheet (4 +
  inside the receipt captures), the task-less Dash Control sheet (`Pause orders` / `Earnings` / `End
  dash` / `Go to home screen`), a 200 ms pickup-card pre-render at store arrival (3, name scrubbed by
  the `user_name` backstop), `Couldn't add substitution` / weight-mismatch shopping dialogs, and Bug 1.

### Open questions

- **#1131** — move the DEBUG line behind the sensitive gate, or log a marker-scrubbed preview? (And
  gate the ~1 Hz empty line on non-blank text.)
- **The `This dash so far` sheet** carries DoorDash's own running `Offers accepted N out of M` counter.
  Recognizing it (recognize-only, `parseGlyphCurrency` for the wheel — 8 of 19 reads were mid-spin)
  would give an in-dash accept oracle for reconciling #1119 / #1069 misses per receipt. Hypothesis.
- **Receipt-less completions** — three sightings on three different paths now (Bug 5). Is the trigger
  the next offer's arrival? Worth watching whether the `This dash so far` sheet appears instead.
- **Rule candidates without PII:** the task-less Dash Control sheet, the pickup pre-render (needs the
  `user_name` redact), the two shopping dialogs.

### Meta

- The one Pledge-class finding this dash was in the LOG, not a capture (Bug 4) — the desk PII sweep
  now greps the DEBUG firehose for the banking markers too, not just `captures/` and `shareable.log`.
- DoorDash 8.99.20 for a whole dash: recognition steady (53 window UNKNOWN of 529 envelopes).
