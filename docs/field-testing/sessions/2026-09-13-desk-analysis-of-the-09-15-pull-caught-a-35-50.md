## 2026-09-13 → 2026-09-15 (desk analysis of the 09-15 pull — #1078 caught a $35.50 drop with 0.4 s to spare; a return order walked off with $10.93; a gate code reached disk)

**Date:** 2026-09-13 → 2026-09-15 · **Platform(s) tested:** DoorDash (app **8.97.8**, all 1 427
envelopes — a new platform version; the corpus is 8.93.7/8.95.6/8.96.8-era) · **Branch under test:**
`master` at `8a5ea7c3` (post-#1100; read off the logs — `DashBuddy 0.230.0+8a5ea7c3 starting (built
2026-09-13T14:14:09.261Z)`, 63 `PipelineStats` lines all `app=0.230.0+8a5ea7c3`). **First field run of
#1078/#1095.** · **Field conditions:** fourteen DoorDash dashes over three days — 09-13: 492
(09:36–11:36 $58.27), 499 ($50.26), 507 ($60.67), 514 ($62.14); 09-14: 521 ($32.75), 529 ($62.50), 542
($32.78), 547 ($15.75), 551 ($77.26), 564 ($21.25); 09-15: 568 (`early_offline`, one accept unassigned
at the store), 572 ($18.05), 576 (`early_offline`, 11 offers in 9 min), 577 (a 4-second dash). Mostly
H-E-B shop-and-deliver plus McDonald's and Shipley Donuts; one alcohol delivery refused for an underage
ID and converted to a **return order**; one stacked 2-drop job; 82 offers (23 accepted / 47 declined /
12 timed out); Uber Offline throughout; no pause, no force-stop, one process for the whole window.
1 427 captures (172 UNKNOWN screens, 207 UNKNOWN clicks, 5 UNKNOWN notifications; `unknownDropped` 502
/ `forwarded` 3 150 ≈ 15.9 %), **185 WARN / 0 ERROR**, no `RecognitionHealth` / `LocaleBoundary` /
`TtsHealth` notice, `shareable.log` PII-free. One capability granted (the #1093 receipt-expand, carried
across the reinstall); `confirm_decline` still denied fail-closed 94×. Pull dir
`~/dashbuddy/logs/2026/09/15`.

### Bugs

1. **The "Drop off steps" wrapper shipped a customer's gate code, cross streets and a landmark raw to
   UNKNOWN capture.** Three envelopes (`UNKNOWN/2026-09-13_11-57-01-419…__b25762`, `…11-57-02-476…__93686e`,
   `…11-57-15-266…__01807f`), node `description_text_view` under
   `drop_off_step_instructions_activity_host_fragment`, page title `Drop off steps`, subtitle
   `Leave it at the door`. No rule, no backstop: the id is not in `ID_MARKERS` and the value carries no
   `CustomerTextMarkers` prefix — the string *is* the instruction, unlabelled, the #1058-leak-B / #803
   shape. The same customer's instruction body is correctly `[redacted]` on the recognized
   `dropoff_pre_arrival` four minutes earlier in the same delivery. *Hypothesis:* 8.97.8 routes
   leave-at-door through this wrapper page in addition to the pre-arrival card; #912 item 2 already lists
   the surface as unruled but never graded it as PII-bearing.
   - **Status:** Open (#1107 — Pledge; #912 item 2 is the recognition half).

2. **A return order walked off with $10.93 — a third of the dash — and the whole flow is UNKNOWN.**
   Session 542: an alcohol delivery was refused for an underage ID, DoorDash converted it to a return,
   and paid `Original order (Base) $21.85 + Return pay $10.93 = $32.78`
   (`UNKNOWN/2026-09-14_13-03-24-474…`). The state machine closed the job at 13:03:23 on the $21.85
   estimate; the return leg's **own** receipt arrived 10 minutes later
   (`delivery_summary_expanded/2026-09-14_13-13-49-895…`, `This offer $10.93 / Base pay $10.93`) with no
   open job — no `Saved:` bubble, no `#1033 receipt re-price`, no `DELIVERY_RECEIPT_REPRICE`. The session
   total is right (read off the summary); the row is short by exactly the return pay. Six UNKNOWN frames
   carry the `Return to store` card and the `Return by 13:04` chrome; thirteen of the pull's nineteen
   backstop scrubs come from them (all scrubbed — nothing leaked). *Hypothesis:* #998 recurring, now with
   a one-drop, one-figure instance that is trivially replayable.
   - **Status:** Open (#998; evidence comment posted).

3. **Nine of twelve `OFFER_TIMEOUT`s were declines — #1104's second sighting, and the dev flagged it
   independently the same day.** Per-timeout table in the desk report. The discriminator is unchanged and
   now measured nine times: the dasher's confirm tap lands **24–229 ms** (median 145) *before* the
   `offer_popup_confirm_decline` frame is admitted, so `doordash.click.decline_offer`'s
   `screenIs` gate reads `offer_popup` and the click falls to UNKNOWN. **Sharpening:** the confirm SHEET
   was recognized in time in every case — the `Denied confirm_decline` WARN 200–500 ms later proves the
   screen rule matched and reached the capability gate. So this is a race *between the click sub-pipeline
   and the window sub-pipeline*, not a recognition failure, which constrains the fix to the click's
   screen context. Counts: 58 `initial_decline` vs 47 `decline_offer` clicks; 94 `Denied confirm_decline`
   across **57 episodes** (37 double + 20 single — the pairs are the throttled retry, not one per
   decline). The three non-#1104 timeouts are genuine: two cards left to run 47.2 s and 48.0 s with no
   decline action at all, and one (seq 2275) where the decline was pressed at +44.7 s and the card
   expired 1.5 s later before any confirm frame.
   - **Status:** Open (#1104; second sighting, counts commented).

4. **A stacked job whose second drop never ARRIVED mixes mileage bases — Σ `realizedMiles` 24.32 mi
   against an 18.70 mi odometer span (session 499), and the sibling carries `realizedMinutes = −7.1`.**
   Job-…500's drop …501 has `milesToDropoff = 5.998` so it folds the leg basis (6.00 mi); drop …503 has
   no arrival and no `milesToDropoff` so it keeps the job/odometer basis (8.82 mi), and the shared leg is
   counted twice. Session/period/IRS totals are odometer-anchored and unaffected, but each row's frozen
   `netProfit` uses its own `realizedMiles`, so …503 is charged $3.20 of operating cost for a leg it
   shared. The negative-minutes row is the same drop's sibling; all **three** negative-minute rows in the
   lifetime database are the first drop of a two-drop job. *Hypothesis:* the per-drop minutes anchor
   belongs to the other drop of the stack, and the mileage basis is chosen per row instead of per job.
   - **Status:** Open (#1108).

5. **An order unassigned at the store through the pickup issue flow is recorded as a `PICKUP_CONFIRMED`,
   with no `TASK_UNASSIGNED` and an orphan accept.** Session 568: accepted $14.50, arrived 10:29:44, went
   `pickup_issue_menu → pickup_select_issue → pickup_resolution_options`
   (`pickup_resolution_options/2026-09-15_10-46-16-807…` reads `Unassign with no pay` / `Unassign order`),
   chatted with support, then `waiting_for_offer` at 10:47:09 — which the stepper read as an ordinary
   "left the task", armed a retire, and committed `PICKUP_CONFIRMED` seq 2529 with a **17.4-minute dwell
   sample** for a visit that ended in an unassign. Everything downstream behaved correctly given that
   input (no completion, no row, `storeKey` NULL, correctly no `JOB_ACCEPT_MISMATCH` because the drop was
   never ARRIVED — the documented #1101 limit), and `reportedEarnings` is correctly NULL. Only the pickup
   confirmation and its dwell are wrong. *Hypothesis:* #301 — the unassign flow is recognize-only, so the
   abandon is invisible to the lifecycle.
   - **Status:** Open (#301; evidence comment).

6. **#1102 is better but not fixed: 2 of 18 auto-taps still resolve against mid-slide bounds**
   (`top=4347` on 09-13 12:59:45, `top=4382` on 09-14 16:16:40, both far below a 2 400 px screen).
   16/18 clean = 89 %, against 6/11 = 55 % on 09-13 — the same defect, a luckier draw. No data lost:
   #1033 layer 1 caught both manual recoveries, one of them at **7.477 s**, the closest the 8 s window has
   ever come to expiring.
   - **Status:** Open (#1102).

7. **8.97.8 rotted the offer card's decline target anchors.** `bindShortfall{offer_popup#declineButton=8,
   offer_popup_confirm_decline#confirmDeclineButton=3}` plus three
   `No 'declineButton' target bound for doordash — decline_offer unavailable` (09-14 14:02:52, 09-15
   11:41:30, 09-15 11:46:53) — the #457 notification Decline button went unavailable three times and the
   dasher declined by hand each time. Separately, `parseShortfall{waiting_for_offer=180}` is the
   **loading** variant of the idle screen (`Looking for offers` + `loading_view`, no earnings pill at
   all), i.e. the benign baseline #1049 exists to declare — now the dominant census entry and swamping the
   signal.
   - **Status:** Open (logged; #1049 for the baseline — the decline-bind rot is not filed yet).

8. **A `navigation_generic` frame mid-route armed a premature retire and emitted a duplicate
   `DELIVERY_CONFIRMED` 13 minutes early** (session 564, seq 2511 at 18:23:45 vs the real arrival at
   18:24:47 and completion at 18:37:05). Row-inert, money correct — and a nice demonstration of #1078's
   round-4 arrival gate, which is exactly what stops a dash end honoring a retire like this one. It is
   also the source of the 25-vs-24 `DELIVERY_CONFIRMED`/`DELIVERY_COMPLETED` asymmetry.
   - **Status:** Open (logged, low — not filed).

9. **`earnings_deposit` persisted the DasherDirect/Crimson clause twelve more times** (09-13 ×4, 09-14
   ×7, 09-15 ×1), each on a RECOGNIZED envelope, while the identical marker on the UNKNOWN path was
   scrubbed (`Cr7` ×3). Same asymmetry, seventh pull. They are the **only** hits of the dasher-banking
   sweep over the whole tree.
   - **Status:** Open (#987, dev ruling queued).

10. **The `Tip Update` push is recognized now, but the $2.00 still lands nowhere.**
    `notification/additional_tip/2026-09-14_09-55-52-079…` names the amount, the merchant and the delivery
    time; `delivery_records` seq 2355 (McDonald's, 09:41:10) still reads `$8.25 OFFER_PAY, tip NULL`, and
    session 521's summary had already been read 13 minutes earlier. #1002's recognition half is done; the
    reconciliation half is the open one.
    - **Status:** Open (#1002; evidence comment).

### Verification (desk)

11. **#1078/#1095 CONFIRMED on its first field run, on a 0.385-second margin.** Session 492's last drop:
    `DELIVERY_ARRIVED` 11:36:14.381 → `dash_along_the_way` **11:36:22.296** (the `TASK_RETIRE` arm,
    deadline 11:36:32.296) → `dash_summary` **11:36:31.911** (the `SESSION_END` arm, **385 ms** before
    that deadline — the 09-08 loss had 1 614 ms) → `GRACE_COMMIT` 11:36:34.415 → `DELIVERY_COMPLETED`
    seq 2240 stamped `completedAt = 11:36:22.296`, i.e. the absorbed retire's own `since`, exactly as
    §Graces specifies. **$35.50 that the pre-#1100 build would have lost without a WARN is on the books.**
    The commit matured past the absorbed floor (34.417 ≥ 32.296) and `DASH_STOP`'s `endedAt` is unchanged
    at `since`. Across the whole pull: 24 delivered drops → 24 `DELIVERY_COMPLETED` → 24 rows, **zero
    `JOB_ACCEPT_MISMATCH`** (so #1095's false-positive half also passes), eleven summary ends at
    2.501–2.508 s and three early-offline ends at 10.002–10.003 s. The deliberate **< 10 s** end is still
    not done (tightest arrival→end 17.5 s — which happened to be this one).
12. **#1096 measured a second time and retired at 2/2.** Eleven receipt first beats, gap to the first
    recognized `delivery_summary_collapsed` **169–785 ms** (median ≈ 450 ms) — never near the ~2 s that
    would let the expand tap be scheduled off one. The wheel on those beats never reaches
    `sessionEarnings` because an UNKNOWN frame is never parsed; one of them
    (`UNKNOWN/2026-09-14_17-00-09-795…`) read `This dash so far $368.35` on a dash whose real total was
    $77.26. The `Send thanks to the customer for the tip!` chrome survives into 8.97.8.
13. **#992 CONFIRMED — the last unseen surface of the Pledge redact batch finally rendered.**
    `pickup_wait_survey/2026-09-14_09-32-36-330…` carries `customer_name = [redacted:3aa3]` with the order
    number, item list and store chrome intact. With #993/#994/#995/#920 already at 2/2, the batch can
    close.
14. **#1033 layer 1 set a new record and layer 2's stacked half is finally exercised.** One receipt
    expanded **7.477 s** after its collapsed frame (09-14 16:16:40 → 16:16:47.57) — 0.5 s inside the 8 s
    window, folded `DROP_SHARE $22.50`; the pre-#1033 2.5 s window would have lost it. Layer 2 fired 18×
    and no-op'd 18× (`row NNNN already holds N¢`), `receiptRepricedAt` NULL on all 24 rows, zero
    `no delivery row` WARN, `rows_total == receipt_total` on all 16 receipt-priced jobs. The stacked job
    emitted `2 drop(s), receipt 1315¢` and its two `dropRealizedPay` (657¢ + 658¢) sum to `totalPay` to
    the cent. The deliberate **> 8 s** expansion remains undone.
15. **Also working:** #1063's desk half a fourth time (82 offers → 82 `OFFER_RECEIVED` → 82 TTS → 82 chat
    cards → 82 screenshots → 82 resolutions, zero "Replaced by new offer" / "Superseded by direct
    offer"); #1103 no-regression (all three `early_offline` sessions have NULL `reportedEarnings`, and
    exactly one `SESSION_PAY_SETTLE` fired in three days — correctly inside its own session); #991
    no-regression (82/82 `speak()`, zero `-1`); #438 B4 (31 `OfferActionReceiver` taps, every hash
    matching its resolved row); #859 (82 offer screenshots 1:1, zero `{` filenames); #731 (one connect,
    zero disconnects, one process, three days); #159/#773/#1000 (six H-E-B running keys plus McDonald's
    `@5503`, zero `D6 join miss`, 23 of 24 accepted offers linked — the 24th is the unassigned one,
    fail-null); #315 H5 (23 dwells 0.0–42.8 min); #588 (`0.79/min n=104`, `items:units 0.78 n=51`, all
    `[doordash]`-tagged); #843's eighth corroboration (94 fail-closed `confirm_decline` denials beside a
    granted `expand_earnings`); #967's desk half a third time (`ratings.capturedAt` advanced to
    12:01:31 on an `[DoorDash:Offline]` browse); #986/#1031/#1039 (zero hex on any subpremise, 20 plain
    `Apt/Suite [redacted]`); #1057/#918 (four `POOR_ACCURACY` rejections, each a one-fix streak recovered
    in 1–7 s, 11 160 accepted / 837 ignored / 3 rejected, +379 km); #910 (dropoff-phase Google-Nav
    maneuvers masked on click envelopes too, while `pickup_navigation` stays raw by design);
    `IcuRegexGuard`-adjacent health — zero ERROR, zero pipeline restarts, zero mapping failures.
16. **Not exercised:** both #1054 items for a **fourth** pull (one process for the entire three days —
    startup 09-13 09:14:53 replayed 2 observations with no session and correctly logged no
    `Recovery re-armed`; zero `DASH_PAUSED`/`DASH_RESUMED`/`MODE_RESUME_COMMIT`/`SESSION_PAUSED_SAFETY`);
    #1058's workflow sheet a fourth time (`grep -c dropoff_workflow_sheet` = 0; four of the five check-1
    hits are the documented text-free skeletons, and the fifth is a real alcohol pre-arrival card the
    #1058 arm did not claim on 8.97.8 — no PII on that render); #1059 a fourth time (zero ids tree-wide,
    and the over-match check passes a fourth time: ~20 Red-Card shop orders, 211 `pickup_shopping` + 80
    `shopping_item` frames, zero `sensitive.red_card` drops — note the 16 `sensitive.id_verification`
    drops on 09-14 are the *pre-existing* licence-scanner anchors, which the log cannot distinguish from
    #1059's passport arm); #985 (the timeline order-detail sheet was never opened); #1034 (nothing
    negative); #936 (0 null distances, 0 `quality='UNKNOWN'`); #660 (zero null-session rows); #736/#752
    (zero `TASK_UNASSIGNED` — see Bug 5); #1000; every Uber item; every dev-eyes UI item.

### Open questions

17. **The `waiting_for_offer` loading variant is now 180 of the pull's parse shortfalls** — a screen that
    legitimately renders `Looking for offers` and nothing else. Until #1049 lands an `optional` field
    declaration, the #1036 rot signal for the idle earnings pill is unreadable: a genuine 8.97.8 wheel rot
    would be indistinguishable from this baseline.
18. **Every delivery completed into "Dash Along the Way" renders no receipt at all**, and that is where
    100 % of this pull's unexplained money sits: all six `OFFER_PAY` drops, and both the $3.71 (a
    46-item mid-shop pay adjustment DoorDash explicitly said it would only show "when the order is
    delivered") and the $3.75 deltas. #1035's idea — reconcile against DoorDash's own weekly Earnings
    screen — would close exactly this class, and so would folding the `additional_tip` push (#1002).
19. **Three new no-PII UNKNOWN families worth a cheap rule on 8.97.8:** the `Merchant delay protection`
    education sheet (14 frames), the `Couldn't scan ID / Scan again` alcohol rescan sheet (2), and the
    `Can't hand order to customer` contact-CX steps page (2). The `Underaged ID. Do not hand over any
    items.` HELP page (1) belongs with the return-order family in #998.
