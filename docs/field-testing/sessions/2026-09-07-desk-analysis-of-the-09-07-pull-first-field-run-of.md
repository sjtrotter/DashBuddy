## 2026-09-07 (desk analysis of the 09-07 pull — first field run of #1033; #1029's money re-anchoring meets the `799` code and a mid-spin wheel in the wild)

**Date:** 2026-09-07 (the pull covers dashes on 09-06 evening and 09-07 morning) · **Platform(s) tested:**
DoorDash only (app **8.95.6**), Uber enabled but idle · **Branch under test:** `master` at `aabb56d0` (the
PR #1072 merge — #1033; also the first build carrying #1058/#1059/#1063), read straight off the logs
(`DashBuddy 0.230.0+aabb56d0 starting`, `app=0.230.0+aabb56d0` on every `PipelineStats` summary). NOT on
this build: #1054 (PR #1074), #1073 (PR #1075), #1053 (PR #1085) · **Field conditions:** four dashes —
two productive (09-06 18:48→20:11, one H-E-B shop-and-deliver, out-of-zone "Dash Along the Way";
09-07 07:17→08:22, one H-E-B shop-and-deliver) and two short offer-browsing dashes (09-07 08:37→08:44 with
a pause, 08:47→08:54). 9 offers, 2 accepted, 7 declined. A DasherDirect cash-out on 09-06 21:48. 314
captures (32 UNKNOWN screens, 27 UNKNOWN clicks, 3 UNKNOWN notifs), zero `ERROR` lines, `restarts=0`,
11.7 % UNKNOWN ratio, `shareable.log` PII-free. Issues filed this analysis: **#1088**, **#1089**,
**#1090**; evidence comments on #1051, #745, #599. *Meta: the session that pulled this slice OOM-crashed
the laptop by launching the install build and the analysis agent together — the pull survived, the
analysis was re-run serially (rule recorded in `CLAUDE.local.md`).*

### Verification (desk)

1. **#1033 layer 1 confirmed on its first field run — and the margin is exactly the one the issue
   predicted.** The 09-07 receipt appeared collapsed at 08:20:05.311 and expanded at 08:20:08.176 —
   **2.865 s**, i.e. 365 ms past the old 2.5 s `authoritativeGraceMs`. The mint at the PostTask exit
   (08:20:09.895) therefore carried the itemization, and the row folded `payBasis = DROP_SHARE`,
   `basePay 14.20`, `tip 26.37`, `realizedPay 40.57` — **the first `DROP_SHARE` row since DoorDash
   flattened the receipt**. The 09-05 pull measured 2.56–3.00 s intervals on 8.95.6 and called the old
   grace "always late"; this is the same interval on the far side of the fix. *(Hypothesis, not proof:
   master cannot be replayed against this sequence, so "it would have been `OFFER_PAY`" is an inference
   from the interval plus the 09-06 delivery on the same build, whose mint WAS grace-driven.)*
   - **Status:** Open — checklist item at 1/2; needs a deliberate >8 s late expansion for layer 2's real path.

2. **#1033 layer 2 fired in the field for the first time — and correctly did nothing.**
   `08:20:09.904 DEBUG/StateMachine: #1033 receipt re-price: job job-doordash-1788783523929-435,
   1 drop(s), receipt 4057¢` → `08:20:10.032 DEBUG/Analytics: DELIVERY_RECEIPT_REPRICE: row 1971
   already holds 4057¢ — no-op`. One `DELIVERY_RECEIPT_REPRICE` event (seq 1972), Σ `dropRealizedPay` ==
   `totalPay` to the cent, `receiptRepricedAt` untouched, no `no delivery row` WARN. The emission +
   idempotency + projector-no-op path is proven; the re-price-an-estimate path is not.
   - **Status:** Open.

3. **#1029: the `$799 tip` bug met its real-world source and lost.** The 8.95.6 expanded receipt renders
   `Customer tips ⁄ 799 ⁄ $26.37` — the type CODE really does sit between the label and the money,
   exactly as the issue described. The parse took **$26.37**. Three more #1029 mechanisms fired in the
   same delivery: the two collapsed frames 55 ms apart read `$40.597` then `$40.57`, so
   `parseGlyphCurrency` refused a live mid-spin wheel and accepted the settled one; `payLineItems`
   parsed EMPTY and the scalar bridge synthesised `"Platform pay" $14.20`, which equals the
   `$13.20 base + $1.00 peak` on screen; and the collapsed frame correctly kept `parsedPay == null`.
   NOT exercised: the settle park/commit itself — the receipt set `runningEarnings` first, every pill
   read agreed and parked nothing, and no `SESSION_PAY_SETTLE` ever fired (the path emits no log line
   of its own, so there is no desk trace either way).
   - **Status:** Shipped in #1050 (2026-09-05) — field-confirmed 2/2, retired below.

4. **#1030 confirmed on fresh writes.** Both `early_offline` dashes wrote `"totalEarnings": null`
   (DASH_STOP seq 1987/1989) and both `session_records` rows carry `reportedEarnings NULL`, while the two
   `summary_screen` dashes carry real `$40.57` / `$31.72`. No hard zero anywhere.
   - **Status:** Shipped in #1040 — field-confirmed 2/2, retired below.

5. **Money reconciles to the cent on every session.** `Σ (realizedPay + cashTip)` == `reportedEarnings`
   on all four recent `summary_screen` dashes (40.57, 31.72, 48.38, 22.25). The 09-06 out-of-zone delivery
   never rendered a receipt at all (the #999 shape: `dropoff_photo` → `dash_along_the_way` →
   `end_dash_confirm`, zero `delivery_summary_*` frames), so it folded a `PER_OFFER_STORE` `OFFER_PAY`
   estimate — **which matched the platform's own reported total exactly, $31.72 = $31.72**.
   - **Status:** Open (as a standing check).

6. **The Pledge layers fired four times and held every time.** (a) The 09-06 cash-out: ~30 DasherDirect
   frames blocked by `sensitive.dasher_direct`, and the two CLICK envelopes — which the window-level rule
   structurally cannot see — were dropped before any disk write by `Tr12` ("Transfer out") and **`sh30`**
   (= `shape:transfer {0,4}\$ {0,4}\d`, the #884 shape, on the exact path it was written for). No file
   exists at either timestamp; the whole-tree banking sweep is clean. (b) Two UNKNOWN screens carrying
   customer PII were scrubbed by the #910 backstop — one on `nodeId=address_line_2`, one on the
   `"Pickup for "` text marker (both now **#1088**). (c) `dropoff_pre_arrival` frames are fully masked
   with the right GRADES: `Deliver to [redacted:7201]` + a hashed street line + **plain** `[redacted]` for
   the unit and the customer note. (d) The `[redacted:7201]` / `[redacted:eba3]` masks equal the first 4
   hex of the persisted `customerHash` on two different surfaces — the #623/#733 invariant, fielded.
   - **Status:** Open (as a standing check); #884 retired below.

7. **#1057's odometer gate: 1,149 fixes, 51 jitter-ignored, ONE rejected in 20 hours.** The single
   rejection (`POOR_ACCURACY`, accuracy 110 m, 08:54:19) logged the episode-gated *entry* line, not a
   burst. Per-session spans 8.5 / 10.4 / 4.7 / 4.3 mi; `Σ realizedMiles ≤ span` on every session.
   - **Status:** Shipped in #1065 — field-confirmed 2/2, retired below.

8. **#1063's desk half passes 3/3.** 9 offers → 9 `OFFER_RECEIVED` → 9 resolutions → 9 TTS utterances →
   9 bubble cards, a perfect 1:1. Zero `Replaced by new offer`. The three offer-shaped UNKNOWN frames left
   are all store-leg-LESS half-renders (only `accept_button` + an assignment UUID; one adds
   `"High paying offer!"`) — the #595 family the check permits.
   - **Status:** Open (dev-eyes half; checklist at 1/2).

9. **#1036 root-caused, all benign.** `waiting_for_offer=27` shortfalls are the 8.95.6 `earnings_pill`
   **carousel**: it alternates `$X.XXThis dash` with `N%Weekly goal` (and a fused `This dash10%Weekly
   goal`), so `sessionPay` is correctly null on the weekly renders, and the `Zone offer wait` chip's
   sibling value node is text-free so `waitTimeEstimate` is null too. `pickup_shopping` tripped once in
   ~87 frames on a pre-render. Both added to the benign baseline in `docs/architecture/01-sensor-pipelines.md`.
   - **Status:** Shipped in #1046 — field-confirmed 2/2, retired below.

10. **#1032 confirmed again.** Both `summary_screen` dashes ended through a recognized `dash_summary`
    frame (09-06 20:11:18 captured with `$31.72`; 09-07 08:22:54 recognized with the capture
    FrameGate-suppressed); zero `Dash summary` frames in UNKNOWN.
    - **Status:** Shipped in #1044 — field-confirmed 2/2, retired below.

### Bugs

1. **`payoutStoreForms` is NULL on every 8.95.6 receipt, so store keys can never leave address tier.**
   #1029 restored per-drop *pricing*, but the per-store forms come from the line-item titles, which the
   flattened receipt does not carry: seq 1971 is a correctly-priced `DROP_SHARE` row with
   `payoutStoreForms IS NULL` and `storeKey = doordash|h-e-b|@7330`. Consequence: the #653 reconciliation
   guard stays off, and lifetime the table now sits at 82 address-tier vs 22 receipt-tier keys.
   *Hypothesis:* this is the live shape of **#1051**'s ask (label-based `payLineItems each`), and the 09-07
   expanded-receipt capture is the fixture it needs.
   - **Status:** Open (comment on #1051).

2. **A customer's chat-PUSH mask hex differs from their screen/persisted hex.** The four `customer_message`
   envelopes mask their title `Message from [redacted:902e]`; the `chat_conversation` screen captured 28 s
   later (subtitle `Your Customer`) masks its title `[redacted:7201]`, which equals the persisted
   `customerHash`. `doordash.screen.chat_conversation` declares `normalize: customerName`; **no
   notification redact anywhere does** — `doordash.notification.order_ready`'s comment records that as a
   deliberate **#745** residual. *Hypothesis:* the reasoning holds for `order_ready` but is weaker for
   `customer_message`, since a chat push has a 1:1 screen counterpart to join to. Nothing leaks — a
   fidelity gap, not a privacy one. *(Cannot prove the two masked strings were the same name; the raw text
   is correctly gone.)*
   - **Status:** Open (comment on #745).

3. **Two UNKNOWN families carry customer PII and were saved only by the rules-independent backstop.**
   (a) the pickup nav→arrival transition sheet (`UNKNOWN/2026-09-06_19-07-08-235`, 48 ids,
   `going_to_store_map` + `user_name_label:'Delivery for'` + bare `user_name` + `address_line_1/2`), caught
   by the `address_line_2` ID_MARKER; (b) a `Current task` timeline card variant
   (`UNKNOWN/2026-09-06_19-37-13-932`) captured 1.0 s after a *recognized* `timeline` frame, caught by
   `"Pickup for "`. The UNKNOWN-path scrub is the documented debug-only exception with a residual (an
   id-less address line with no lead-in), so both want rules with their own `redact`.
   - **Status:** Open (**#1088**).

4. **Three more recognition gaps, no PII:** the "Couldn't add substitution" barcode sheet (4 frames,
   `Scan again` / `Refund item`); the dash-control bottom sheet (`Pause orders / Earnings / This dash /
   $0.00 / End dash`, 2 frames, immediately before a real dash end — a second `This dash` pill render, so a
   rule there must never feed `sessionPay`); and the first frame of the delivery receipt
   (`UNKNOWN/2026-09-07_08-20-05-003`, the sheet mid-inflation 308 ms before the recognized
   `delivery_summary_collapsed` — the receipt-card analogue of #1063).
   - **Status:** Open (**#1089**).

5. **The DoorDash paused-dash PUSH is unrecognized** (`"Your current dash has been paused"`, 08:44:25) and
   arrives **8 s before the screen-derived `DASH_PAUSED`** — corroborating lifecycle evidence that #1054's
   pause-safety net currently lacks.
   - **Status:** Open (**#1090**).

6. **`earnings_deposit` pushes persist the payout amount + "DoorDash Crimson account" on a recognized
   rule.** Two fresh envelopes (`2026-09-06_20-11-21-672`, `2026-09-07_08-22-55-070`). The
   `crimson_balance` rule's own comment already parks this as **#599**; recording it because #599 now has
   fielded evidence, and the standing dev feedback is "payment screens blocked, not recognized — even
   their own earnings".
   - **Status:** Open — dev call, #599.

### Field UX context

- **DoorDash 8.95.6's `earnings_pill` is a carousel.** Across 17 `waiting_for_offer` frames it cycles
  `$0.00This dash` → `10%Weekly goal` → `$40.57This dash` → the fused `This dash10%Weekly goal`. The
  `hasAnyText: "This dash"` conjunct on the `sessionPay` extractor — added precisely so a weekly figure
  could never be read as a dash total — is doing exactly that work in the field, and the fused render is
  refused a second time by `parseGlyphCurrency` (strips to `"1000"`, no `$`).
- **The expanded receipt itemization now reads `Base pay ⁄ $13.20 ⁄ Peak pay ⁄ $1.00 ⁄ Customer tips ⁄
  799 ⁄ $26.37`** — labels are present as *text*, but the `pay_line_item_title` **ids** are still gone,
  which is why the scalar bridge (not the line-item path) is what priced the drop.
- **Automation consent remains ungranted** (`INFO/Consent: reconciled 4 capabilit(ies) … none granted`),
  so the fail-closed gate denied `confirm_decline` 13 times across 7 declines. Correct, but it WARNs per
  *frame* rather than per decision (~2× per decline) — a candidate for edge-gating per offer hash, the way
  the odometer rejection streak is gated (principle 7).
- **The #1058 checklist's check-1 grep returns two false positives** on text-free
  `drop_off_workflow_host_fragment` loading skeletons — tighten it to require a co-present text node.

### Meta / architecture

- **The build-identity work (#1066) removed all guesswork from this analysis.** Step 0 of the playbook was
  one grep, and every claim above is anchored to `aabb56d0` rather than to an inference from which log
  lines were absent.
- **This slice is unusually high-signal for its size.** Two deliveries produced field receipts for five
  distinct mechanisms (#1033 layer 1, #1033 layer 2, #1029 ×4, #1030, #1057, #884) because the 09-07
  delivery happened to exercise a mid-spin wheel, a type-code sibling, an empty `payLineItems`, and a
  2.865 s expansion all at once. Worth keeping the two `delivery_summary_collapsed` frames and the
  `delivery_summary_expanded` frame as corpus fixtures — the collapsed pair is a rare captured
  mid-spin/settled adjacency (next intake pass).
- **Every delivery in the last three pulls has been an H-E-B shop-and-deliver.** All the receipt,
  store-key and `payoutStoreForms` findings therefore rest on one merchant shape. A non-shop delivery would
  materially widen the evidence base.

### Retired from the checklist this analysis

- **#1029 (PR #1050) — money reads re-anchored on 8.93.7+.** Confirmed 2/2 (desk 09-06 + desk 09-07 — the
  `799` code met and defeated, a mid-spin wheel refused, the scalar bridge priced the drop). Still
  unexercised: the settle park/commit itself and the #1052 pause/resume-mid-spin case.
- **#1030 (PR #1040) — the `early_offline` fake $0 report.** Confirmed 2/2 (desk 09-06 refold + desk 09-07
  fresh writes: `totalEarnings: null`, `reportedEarnings NULL`).
- **#1032 (PR #1044) — the dash-end summary sheet recognized again.** Confirmed 2/2 (desk 09-06 ×3 +
  desk 09-07 ×2, all `endSource=summary_screen` with real totals).
- **#1036 (PR #1046) — matched-but-parsed-nothing is loud.** Confirmed 2/2 (desk 09-06 + desk 09-07;
  benign baseline = `waiting_for_offer` carousel/pre-render, `dash_along_the_way`, `pickup_shopping`
  pre-render, recorded in `docs/architecture/01-sensor-pipelines.md`).
- **#1057/#918 (PR #1065) — every odometer fix gated.** Confirmed 2/2 (desk 09-06: 2,310 fixes / 0
  rejected; desk 09-07: 1,149 / 1 `POOR_ACCURACY` rejected, episode-gated, spans ≥ Σ realizedMiles).
- **#884 — the amount-bearing transfer button.** Confirmed 2/2 (desk 07-31 + desk 09-07: a `Transfer
  $<amt>` CLICK on DasherDirect caught by `sh30` and dropped before disk).
