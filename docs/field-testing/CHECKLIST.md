# Pre-dash field-test checklist

Read §1 must-watch first, then §2. This page is the index: one line per item, in section
order. Each item is its own file under `checklist/open/` or `checklist/closed/`.
Dated field reports are indexed in [README.md](README.md).
Extracted on 2026-10-06: 203 items; counters retained (121 at 0/2, 42 at 1/2, 1 at 2/2, 39 without a whole-item counter).
Age uses the original README item's **first line**, via `git blame --date=short -L 49,3130 2ae6a04b3d4d -- docs/field-testing/README.md`; later sub-line notes do not reset it.
The only description correction is #1135's quoted callout, verified against `app/src/main/res/values/strings.xml`, `money_tab_unattributed_callout_format`.

## §0 Policy

2026-10-06 — seeds approved by the dev; 2026-10-07 — the five must-watch picks and the four supersessions confirmed by the dev

1. Prune superseded items when replacement evidence supports the dev's decision; `[superseded?]` below proposes pruning and preserves the original item pending that decision.
2. The pre-dash agent reads a MUST-WATCH list of ≤ 5 money / redaction / Pledge / live-UI tap items first; the agent proposes and the dev picks.
3. Long-tail desk closure is PROPOSED by agents only (fable's seed threshold — 1/2 desk-verified + 3 clean incidental dashes — is the proposed bar, pending the dev's confirmation); agents never record a confirmation they did not witness. Closures are the dev's.
4. An item untouched for 6 weeks at `Confirmed: 0/2` moves to `[review]`, never auto-retired; on 2026-10-06 the inclusive first-line blame cutoff is 2026-08-25.
5. The checklist's home is `checklist/`: one file per item. `checklist/open/` holds `active`, `must-watch`, `review` and `desk-verified` items; `checklist/closed/` holds `field-validated`, `superseded` and `retired-unvalidated` ones. Each file starts with a `status:` line and an `issues:` line, a blank line, then the item text. One owner per fact: `status:` owns the status, the item's own `Confirmed: N/2` line owns the count, and the section an item's index line sits under owns its section. File names are `<first issue number, else noissue>-<slug>.md`. This page indexes them; README indexes the dated log under `sessions/`.
6. A new item is a new file in `checklist/open/` plus a `- [title](checklist/open/<file>.md)` line under its section here. A confirmation edits only the item's `Confirmed: N/2` line and adds a dated sub-line. An item is closed by `git mv` from `checklist/open/` to `checklist/closed/`, its `status:` line changed (two confirmations → `status: field-validated`), and its line here moved to §5 with the link changed to `checklist/closed/`. Must-watch items: `grep -l '^status: must-watch' checklist/open/*`; everything in §1 is the §1 list below.

Vocabulary (one status per item): `active` · `must-watch` · `review` (aged) · `desk-verified` · `field-validated` · `superseded` · `retired-unvalidated`.

A `?` suffix on a status (`status: must-watch?`, `status: superseded?`) marks a proposal, not an additional status or a dev decision; no item is newly desk-verified, confirmed or retired here.

## §1 Must-watch (dev-confirmed 2026-10-07 — read these first)

- [🆕 NEW — the bubble headline is the shift's earnings (#1443)](checklist/open/1443-the-bubble-headline-is-the-shift-s-earnings.md)
- [Odometer capability (#1361)](checklist/open/1361-odometer-capability.md)
- [Receipt vehicle (#1388)](checklist/open/1388-receipt-vehicle.md)
- [Vehicle editor (#1385)](checklist/open/1385-vehicle-editor.md)
- [CSV vehicle column (#1387)](checklist/open/1387-csv-vehicle-column.md)
- [Vehicle details (#1383)](checklist/open/1383-vehicle-details.md)
- [🆕 NEW — Dash vehicle (#1360)](checklist/open/1360-dash-vehicle.md)
- [Offer heads-up (#1333 S2g)](checklist/open/1333-offer-heads-up-s2g.md)
- [🆕 NEW — Vehicle chip (#1379)](checklist/open/1379-vehicle-chip.md)
- [🆕 NEW — Settings → Garage lists the car](checklist/open/1359-settings-garage-lists-the-car.md)

**S2 HUD (#1333), one item per slice; each needs its own two confirmations:**

- [🆕 NEW — the bubble HUD shell (#1333 S2a)](checklist/open/1333-the-bubble-hud-shell-s2a.md)
- [🆕 NEW — the state cards (#1333 S2b)](checklist/open/1333-the-state-cards-s2b.md)
- [🆕 NEW — the offer card (#1333 S2c)](checklist/open/1333-the-offer-card-s2c.md)
- [🆕 NEW — the log (#1333 S2d)](checklist/open/1333-the-log-s2d.md)
- [🆕 NEW — the goal-met sequence (#1333 S2e)](checklist/open/1333-the-goal-met-sequence-s2e.md)
- [🆕 NEW — font-scale tiers (#1333 S2e)](checklist/open/1333-font-scale-tiers-s2e.md)
- [🆕 NEW — cash tip sheet and undo strip (#1333 S3a)](checklist/open/1333-cash-tip-sheet-and-undo-strip-s3a.md)
- [🆕 NEW — vehicles as types (#1358)](checklist/open/1358-vehicles-as-types.md)
- [🆕 NEW — expense subcard and keypad (#1333 S3b)](checklist/open/1333-expense-subcard-and-keypad-s3b.md)
- [🆕 NEW — the garage is the vehicle list (#1355)](checklist/open/1355-the-garage-is-the-vehicle-list.md)
- [🆕 NEW — daily goal sheet (#1333 S3c, panel 13b)](checklist/open/1333-daily-goal-sheet-s3c-panel-13b.md)
- [🆕 NEW — odometer and vehicle (#1333 S2f)](checklist/open/1333-odometer-and-vehicle-s2f.md)
- [🆕 NEW — Settings → Export Data writes expenses.csv and fuel.csv with today's entries…](checklist/open/1333-settings-export-data-writes-expenses-csv-and-fuel.md)
- [🆕 NEW — S2 overall (#1333)](checklist/open/1333-s2-overall.md)
- [🆕 NEW — unassigning the LAST order of a job (DoorDash returns to "Finding offers") closes…](checklist/open/1312-unassigning-the-last-order-of-a-job-doordash.md)
- [🆕 NEW — accepting an ORDINARY offer card while the app still shows a job open closes that…](checklist/open/1119-accepting-an-ordinary-offer-card-while-the-app.md)
- [🆕 NEW — the HUD earnings reads "—" until the first parsed total of the dash, never…](checklist/open/1041-the-hud-earnings-reads-until-the-first-parsed.md)

Five items the dev confirmed on 2026-10-07. Each full item appears once, with its original counter. #1149 reached 2/2 on the 10-26 desk pass and moved to §5, so four remain.

- [Name the money (#1135 PR 1)](checklist/open/1135-name-the-money-pr-1.md)
- [Session report correction (#1134)](checklist/open/1134-session-report-correction.md)
- [Chat compose box never persists raw (#919)](checklist/open/919-chat-compose-box-never-persists-raw.md)
- [🆕 NEW — #1058 — the two dropoff sheets that were shipping addresses and door codes to…](checklist/open/1058-the-two-dropoff-sheets-that-were-shipping.md)

## §2 Active

- [One offer story, no score or verdict (#1442)](checklist/open/1442-one-offer-story-no-score-or-verdict.md)

- [🆕 NEW — after a GPS dash, the delivery drill-down's rows carry an arrival odometer and…](checklist/open/1310-after-a-gps-dash-the-delivery-drill-down-s-rows.md)
- [🆕 NEW — a 2-drop job's rows never exceed its odometer span and never show negative…](checklist/open/1108-a-2-drop-job-s-rows-never-exceed-its-odometer-span.md)
- [🆕 NEW — no 5-second ghost dash after End dash (#1311)](checklist/open/1311-no-5-second-ghost-dash-after-end-dash.md)
- [🆕 NEW — same-store second order gets its own pickup; a drop confirmed through the…](checklist/open/1315-same-store-second-order-gets-its-own-pickup-a-drop.md)
- [🆕 NEW — gift order drop-off sheet recognized + masked (#1314)](checklist/open/1314-gift-order-drop-off-sheet-recognized-masked.md)
- [#254 — verify defaults through 9 eligible deliveries; at 10, Economy shows the platform…](checklist/open/254-verify-defaults-through-9-eligible-deliveries-at.md)

### 0/2 or 1/2 younger than 6 weeks — newest first

- [#1407 the rewritten copy reads right on a shift](checklist/open/1407-plain-copy-reads-right-on-a-shift.md)
- [#1411 the renamed app, gig.log debug, carries on where DashBuddy left off](checklist/open/1411-renamed-app-gig-log-debug.md)
- [#1425 a reopened odometer sheet stays open while the last save finishes](checklist/open/1425-odometer-sheet-reopen-during-save.md)
- [#1002 short "Tip Update" push recognized](checklist/open/1002-short-tip-update-push-recognized.md)
- [#1222 offer_accepting stays off a live offer card](checklist/open/1222-offer-accepting-stays-off-a-live-offer-card.md)
- [#1291 shop-order arrival bubble only when the count changed](checklist/open/1291-shop-order-arrival-bubble-only-when-the-count.md)
- [#1116 timeline order-detail sheet (privacy, debug build)](checklist/open/1116-timeline-order-detail-sheet-privacy-debug-build.md)
- [#1255 analytics](checklist/open/1255-analytics.md)
- [#1254 heads-up offer card: Decline is now on the RIGHT, Accept on the LEFT (24dp gap)](checklist/open/1254-heads-up-offer-card-decline-is-now-on-the-right.md)
- [#1189 notification census: with debug census enabled, an admitted UNKNOWN push produces…](checklist/open/1189-notification-census-with-debug-census-enabled-an.md)
- [8.95.6 sheets (#1079)](checklist/open/1079-8-95-6-sheets.md)

Ordered by first-line blame date; source order breaks ties. The five §1 proposals are not repeated here.

- [Trip Radar board (#856)](checklist/open/856-trip-radar-board.md)
- [Per-order handling (#1113 slice 1)](checklist/open/1113-per-order-handling-slice-1.md)
- [Consent receipts (#170)](checklist/open/170-consent-receipts.md)
- [Arrival re-eval (#823 P2)](checklist/open/823-arrival-re-eval-p2.md)
- [Bubble lifecycle is visible in the shareable stream (#916)](checklist/open/916-bubble-lifecycle-is-visible-in-the-shareable.md)
- [Resolved offer cards no longer wear a verdict banner (#864)](checklist/open/864-resolved-offer-cards-no-longer-wear-a-verdict.md)
- [Desk-first — #1133 (PR #1219): pay-less rows carry their car cost](checklist/open/1133-desk-first-pr-pay-less-rows-carry-their-car-cost.md)
- [8.99.20 privacy rules (#1127 / #1128 / #1139) + the store-signature block (#1022)](checklist/open/1127-8-99-20-privacy-rules-the-store-signature-block.md)
- [Classify & draft a real UNKNOWN cluster (#1188, server v0.11.0)](checklist/open/1188-classify-draft-a-real-unknown-cluster-server-v0-11.md)
- [Trusted UNKNOWN captures (#1200)](checklist/open/1200-trusted-unknown-captures.md)
- [Daily recognition health reaches the server (#1197, PR #1198)](checklist/open/1197-daily-recognition-health-reaches-the-server-pr.md)
- [Census developer status line + Reset census identity (#1185)](checklist/open/1185-census-developer-status-line-reset-census-identity.md)
- [🆕 NEW — one consent decision per rule action; the prompt re-collects once (#1167)](checklist/open/1167-one-consent-decision-per-rule-action-the-prompt-re.md)
- [🆕 NEW — chat header and chat preview are masked in captures (PR #1160 / #1145)](checklist/open/1160-chat-header-and-chat-preview-are-masked-in.md)
- [🆕 NEW — wide event receipt is an opt-in consent, asked BEFORE the accessibility grant…](checklist/open/1151-wide-event-receipt-is-an-opt-in-consent-asked.md)
- [🆕 NEW — Uber offers drawn as an overlay are captured, whatever has focus (#1152)](checklist/open/1152-uber-offers-drawn-as-an-overlay-are-captured.md)
- [🆕 NEW — the dash-controls sheet recognizes as `timeline` on a per-offer dash with no task…](checklist/open/1165-the-dash-controls-sheet-recognizes-as-timeline-on.md)
- [🆕 NEW — Compose ADD-ON recognition and honest marginal figures (#1121)](checklist/open/1121-compose-add-on-recognition-and-honest-marginal.md)
- [🆕 NEW — confirm-pickup sheet masks the customer (#1309)](checklist/open/1309-confirm-pickup-sheet-masks-the-customer.md)
- [🆕 NEW — add-on Accept mints one dropoff, not one per listed row (#1304)](checklist/open/1304-add-on-accept-mints-one-dropoff-not-one-per-listed.md)
- [🆕 NEW — the 8.98.5 drop-off sheet masks on every render that keeps a stable row (#1122 +…](checklist/open/1122-the-8-98-5-drop-off-sheet-masks-on-every-render.md)
- [🆕 NEW — the 8.97.8 "Drop off steps" wrapper masks the customer's instruction body (#1107…](checklist/open/1107-the-8-97-8-drop-off-steps-wrapper-masks-the.md)
- [🆕 NEW — a return order must record its return pay (#998)](checklist/open/998-a-return-order-must-record-its-return-pay.md)
- [🆕 NEW — a stacked job's two drops must not be charged the same miles twice (#1108)](checklist/open/1108-a-stacked-job-s-two-drops-must-not-be-charged-the.md)
- [🆕 NEW — unassigning at the store must not record a pickup (#301)](checklist/open/301-unassigning-at-the-store-must-not-record-a-pickup.md)
- [🆕 NEW — the offer card's Decline target must stay bound on 8.97.8](checklist/open/1115-the-offer-card-s-decline-target-must-stay-bound-on.md)
- [🆕 NEW — a dash you start while the last dash's receipt is still on screen must not…](checklist/open/1103-a-dash-you-start-while-the-last-dash-s-receipt-is.md)
- [🆕 NEW — a decline you confirm fast must still be recorded as a decline, not a timeout](checklist/open/1104-a-decline-you-confirm-fast-must-still-be-recorded.md)
- [🆕 NEW — #1054 — a pause survives a relaunch: the HUD stays PAUSED, and the dash still…](checklist/open/1054-a-pause-survives-a-relaunch-the-hud-stays-paused.md)
- [🆕 NEW — #1054 — a grace that lapses while nothing is on screen still commits, and…](checklist/open/1054-a-grace-that-lapses-while-nothing-is-on-screen.md)
- [🆕 NEW — #1063 — an offer is recognized from its FIRST frame, before the Decline button…](checklist/open/1063-an-offer-is-recognized-from-its-first-frame-before.md)
- [🆕 NEW — #1059 — the Persona verification flow, the Red Card wallet and the passport…](checklist/open/1059-the-persona-verification-flow-the-red-card-wallet.md)

### Counter unspecified — retained active, newest first

These 39 items lack a whole-item `Confirmed: N/2` counter. Some record a desk-half count or narrative sightings; neither establishes a whole-item count. No zero counter or aged-review status is inferred. The dev can resolve these separately.

- ["Dash paused" push is recognized (#1090)](checklist/open/1090-dash-paused-push-is-recognized.md)
- [Census deferral lines name their cause (#1210)](checklist/open/1210-census-deferral-lines-name-their-cause.md)
- [Desk-first — #965 / #1126 (PR #1213)](checklist/open/965-desk-first-pr.md)
- [🆕 NEW — #967 / PR #968 — ratings stamp during an Offline browse (the early-return fix)](checklist/open/967-pr-ratings-stamp-during-an-offline-browse-the.md)
- [🆕 NEW — identity-less completions now firewalled at PostTask exit (#653 / PR #673): watch…](checklist/open/653-identity-less-completions-now-firewalled-at.md)
- [🆕 NEW — driving/glance-mode HUD font-scale toggle (#318)](checklist/open/318-driving-glance-mode-hud-font-scale-toggle.md)
- [🔧 FIX SHIPPED — a stacked job's DELIVERY_COMPLETED rows now carry per-drop realized pay…](checklist/open/528-fix-shipped-a-stacked-job-s-delivery-completed.md)
- [🔧 FIX SHIPPED — offer outcome cards now derive from the committed outcome, not the tap…](checklist/open/601-fix-shipped-offer-outcome-cards-now-derive-from.md)
- [🔧 FIX SHIPPED — pausing during/after a delivery shows exactly one "Dash Paused!", no…](checklist/open/605-fix-shipped-pausing-during-after-a-delivery-shows.md)
- [🔧 FIX SHIPPED — one DashSummary screenshot per session end, and offer screenshots are…](checklist/open/606-fix-shipped-one-dashsummary-screenshot-per-session.md)
- [🔒 FIX SHIPPED — recognized capture envelopes now redact customer PII at the device edge…](checklist/open/598-fix-shipped-recognized-capture-envelopes-now.md)
- [🔧 FIX SHIPPED — automated taps now rank click candidates by evidence instead of a dead…](checklist/open/600-fix-shipped-automated-taps-now-rank-click.md)
- [🔧 FIX SHIPPED — a shade-tapped Accept/Decline now retries instead of dying on "No live…](checklist/open/602-fix-shipped-a-shade-tapped-accept-decline-now.md)
- [🔧 FIX SHIPPED — receipt-skipped deliveries still close + log a completion, and the next…](checklist/open/596-fix-shipped-receipt-skipped-deliveries-still-close.md)
- [🔧 FIX SHIPPED — click captures record every rule-matched tap + UNKNOWN cap re-arms after…](checklist/open/597-fix-shipped-click-captures-record-every-rule.md)
- [🔧 FIX SHIPPED — a committed decline can't be un-declined by a later Accept (#594).…](checklist/open/594-fix-shipped-a-committed-decline-can-t-be-un.md)
- [🔧 FIX SHIPPED — post-accept teardown frame no longer mints a ghost offer (#595). CONFIRM…](checklist/open/595-fix-shipped-post-accept-teardown-frame-no-longer.md)
- [🔒 FIX SHIPPED — Crimson Savings Jar balance notification is now pledge-BLOCKED (#599).…](checklist/open/599-fix-shipped-crimson-savings-jar-balance.md)
- [👁 VISUAL-ONLY — rich offer notification looks right: gauge ring, countdown, badges…](checklist/open/578-visual-only-rich-offer-notification-looks-right.md)
- [🔧 FIX SHIPPED — dropoff customer reads "\\<store\\>'s customer", not a 6-char hash (#568…](checklist/open/568-fix-shipped-dropoff-customer-reads-store-s.md)
- [🔬 FIX SHIPPED (recognize-only) — dropoff-arrived 'Leave it at the door' card now…](checklist/open/549-fix-shipped-recognize-only-dropoff-arrived-leave.md)
- [🔧 FIX SHIPPED — no more double fly-away bubble on a new/stacked pickup (#566, PR #573).…](checklist/open/566-fix-shipped-no-more-double-fly-away-bubble-on-a.md)
- [🔧 FIX SHIPPED — a pickup no longer borrows a customer identity (#548, PR #572). CONFIRM…](checklist/open/548-fix-shipped-a-pickup-no-longer-borrows-a-customer.md)
- [🔧 FIX SHIPPED — dropoff customer fills in the card instead of re-minting it (#565, PR…](checklist/open/565-fix-shipped-dropoff-customer-fills-in-the-card.md)
- [🔧 FIX SHIPPED — add-on offer no longer fabricates a $0 "completion" (#564, PR #570).…](checklist/open/564-fix-shipped-add-on-offer-no-longer-fabricates-a-0.md)
- [🔧 MERGED — realistic $/hr + score on Shop & Deliver offers (#556). CONFIRM ON DASH](checklist/open/556-merged-realistic-hr-score-on-shop-deliver-offers.md)
- [🔧 MERGED — dropoff store recognition + pickup-matched resolution (#526/#553). CONFIRM ON…](checklist/open/526-merged-dropoff-store-recognition-pickup-matched.md)
- [🔧 FIX SHIPPED — offer card icon badges + co-icon-text shop badge \[cart N\] (#461, PR…](checklist/open/461-fix-shipped-offer-card-icon-badges-co-icon-text.md)
- [🔧 FIX IN FLIGHT — phantom + over-minted dropoff tasks cleaned up (#498, PR #521). CONFIRM…](checklist/open/498-fix-in-flight-phantom-over-minted-dropoff-tasks.md)
- [🔧 FIX IN FLIGHT — a delivery can't be logged/counted twice (#518, PRs #520 + #522).…](checklist/open/518-fix-in-flight-a-delivery-can-t-be-logged-counted.md)
- [🔧 FIX IN FLIGHT — multi-drop stack: Job owns ordered dropoffs, routed by customer name…](checklist/open/503-fix-in-flight-multi-drop-stack-job-owns-ordered.md)
- [🔧 FIX SHIPPED — live $/mi on the task card, read off the Job (#503 deliverable 2, PR…](checklist/open/503-fix-shipped-live-mi-on-the-task-card-read-off-the.md)
- [🔧 FIX SHIPPED — GoPuff (Drive) bin-scan mints the pickup arrival (#501, PR #530). CONFIRM…](checklist/open/501-fix-shipped-gopuff-drive-bin-scan-mints-the-pickup.md)
- [✅ FIX SHIPPED — same-store add-on no longer re-mints the task (#499 / #503 slice 2).…](checklist/open/499-fix-shipped-same-store-add-on-no-longer-re-mints.md)
- [✅ FIX SHIPPED — dropoff created from the offer; the premature "Customer" card root (#503…](checklist/open/503-fix-shipped-dropoff-created-from-the-offer-the.md)
- [📸 CAPTURE NEEDED — GoPuff (Drive) screens, to finalize the #501 rules](checklist/open/501-capture-needed-gopuff-drive-screens-to-finalize.md)
- [✅ FIX SHIPPED — "ghost offer" with EMPTY parse logged as a card (#498). CONFIRM ON DASH](checklist/open/498-fix-shipped-ghost-offer-with-empty-parse-logged-as.md)
- [Receipt grace — delivery completion is now deferred ~2.5s (#431 pt 2)](checklist/open/431-receipt-grace-delivery-completion-is-now-deferred.md)
- [⚠️ WATCH FOR RECURRENCE — mid-dash "Done Dashing!" + odometer reset (2026-06-06 #5, root…](checklist/open/5-watch-for-recurrence-mid-dash-done-dashing.md)

<details>
<summary>Migrated historical checklist context (preserved source notes)</summary>

These notes describe the old workflow and past movements; §0 governs current triage and closures. References to dated entries “below” now mean entries in [README.md](README.md). No historical retirement instruction below authorizes a new retirement.

**Living checklist (not a session entry).** Recently-merged changes (and open
PRs / closed issues) that were validated only against captured data and need
eyes on a live dash. A field-testing agent reads this section at the start of a
session and reports it to the developer.

**Desk-first:** most items below are resolvable (fully or half) from a post-dash
data pull alone — the per-item SQL/grep/capture checks live in
[`desk-validation-playbook.md`](desk-validation-playbook.md) (audit 2026-07-12).
Run the playbook against the pull before burning dev-eyes on anything.

**Each item needs two independent field confirmations before it's considered
validated** — one dash can pass by luck or miss the edge case. Track progress
with a `- Confirmed: N/2` sub-line (note the date/conditions of each sighting).
On the **second clean** confirmation, move the item into that session's log
entry and delete it here. If an item is found **broken**, move it to the log
immediately (no second pass needed) so it gets triaged.

_(The #110 Stage 2a auto-expand + Stage 2b Accept/Decline items were found **broken** on the
2026-06-09 dash — moved to that session's log entry below for triage.)_

_(#577 quick-decline auto-confirm and #457 notification Accept/Decline buttons were **validated**
on the 2026-06-24 dash — moved to that session's log entry below. #577 carries a follow-up:
the auto-confirm works but feels slow.)_

_(The 06-25→06-30 field-week entry below **validated and retired**: the #583/PR #584 in-card
heads-up **buttons** (≥16 clean fires from the floating banner — the field gate passes), the #578
card's **mechanical** half, #577 (re-confirmed, 24/24, ~0.55 s — with a new posture caution, see
that entry's Bug #1), the #457 path, and #554 ShadowProjector (2/2). The #462/#460 dropoff item
was found **broken-in-part** (raw PII in capture envelopes) and moved to that entry's Bug #7.)_

_(The 2026-09-16→19 entry below **validated and retired** the #1078 + #1095 dash-end item at 2/2
(three more drops recorded at the `DASH_STOP` edge, one of them after a ten-minute idle), and moved
the #985 timeline order-detail item there **broken**: the rule claims the first frame and masks it,
but the sheet's fully-inflated SECOND frame drops `Copy address`, falls to UNKNOWN and shipped a
street line, a city/ST/ZIP and a customer note raw — now #1116.)_

_(The 2026-09-20 entry below **validated and retired** two items at 2/2 — **#1102**'s receipt
auto-expand (5 receipts presented, 5 expanded, **zero** "could not find any live node", and both
`Throttled action` lines landing after a tap that had already succeeded) and **#987**'s
earnings-deposit mask (both envelopes plain-masked across all four flat fields) — and moved the
**#1033 layer-2** item there **broken**: its first-ever *applied* re-price split a single-drop
receipt across a stacked job's two drops and destroyed $28.51, now #1120.)_

_(The 2026-09-21→24 entry below **validated and retired** the **#1114** Compose offer-card item at 2/2 —
67 cards → 67 `OFFER_RECEIVED` on both DoorDash 8.98.5 and 8.99.20, zero parse nulls, both decline binds at 0
shortfall; the one lost card was the #1069 presentationKey merge, a separate defect.)_

_(The 2026-09-26 entry below **validated and retired** the **#1118** transition-outcomes item at 2/2 — two
clean runs (10/10 + 5/5 accepts inferred from the task surface, 44/44 + 12/12 declines from the confirm sheet, zero
over-inference); the remaining accept losses are #1119 and the merged card is #1069.)_

_(The "Delivery for \<name\>" dropoff / alcohol item (#462/#460) left the checklist on the
06-25→30 week: the alcohol half got one clean sighting (06-30 CVS — ID-check recognized, scanner
capture instruction-text-only, events hashed), but the item's broken-criterion — "raw recipient
name/address appears anywhere" — was tripped by raw PII found in recognized/UNKNOWN capture
envelopes. See that entry's Bug #7.)_

_(The #425 **in-bubble** Accept/Decline item was **VALIDATED** (2/2) on the 2026-06-14 dash — both
Accept and Decline registered on DoorDash — and moved to that session's entry below. The
**notification-shade** buttons remain broken and are tracked separately by **#457** / 2026-06-12 #11.)_

</details>

### Re-aged by whole-item blame (a sub-line note after 2026-08-25 counts as touched) — newest first

- [🆕 NEW — #1034 — a negative dollar reads `-$12`, never `$-12`](checklist/open/1034-a-negative-dollar-reads-12-never-12.md)
- [🆕 NEW — #428-B / PR #845 — multi-language TTS (system locale + settings override)](checklist/open/428-b-pr-multi-language-tts-system-locale-settings.md)
- [🆕 NEW — #859 (H4 + placeholder filenames) — one offer screenshot per presentation, and no…](checklist/open/859-h4-placeholder-filenames-one-offer-screenshot-per.md)
- [🆕 NEW — unassign an order AFTER pickup (dropoff phase) also produces NO paid artifact…](checklist/open/752-unassign-an-order-after-pickup-dropoff-phase-also.md)

## §3 Review (aged ≥ 6 weeks at 0/2)

95 aged zero-confirmation items: 91 `[review]` and 4 `[superseded?]` proposals. Every `desk:` line is a proposal only. A replay/check proposed here has not been run; missing field exposure is not a pass. The four supersession proposals leave the routine active queue but remain here for the dev to decide.

### recognition

- [🆕 NEW — #895 / PR #927 — fused `Apt <n>` masks are plain on all 8 uber+doordash surfaces…](checklist/open/895-pr-fused-apt-n-masks-are-plain-on-all-8-uber.md)
- [Ratings-hub family no longer falls to UNKNOWN (2026-07-30 corpus pass)](checklist/open/noissue-ratings-hub-family-no-longer-falls-to-unknown-2026.md)
- [🆕 NEW — #888 — eight DoorDash screens that used to fall to UNKNOWN are now recognized](checklist/open/888-eight-doordash-screens-that-used-to-fall-to.md)
- [🆕 NEW — #882 / #881 — Uber stacked offers speak the STORE, and a "Match" is recorded as a…](checklist/open/882-uber-stacked-offers-speak-the-store-and-a-match-is.md)
- [🆕 NEW — #858 / PR #876 — expiring Uber cards no longer mint offers](checklist/open/858-pr-expiring-uber-cards-no-longer-mint-offers.md)
- [🆕 NEW — #861 / PR #868 — Uber selfie ID-verification camera is sensitive-blocked](checklist/open/861-pr-uber-selfie-id-verification-camera-is-sensitive.md)
- [🆕 NEW — #860 / PR #871 — Building Name value masked on both dropoff workflow sheets](checklist/open/860-pr-building-name-value-masked-on-both-dropoff.md)
- [🆕 NEW — #865 / PR #877 — performance-hub residual gaps recognized](checklist/open/865-pr-performance-hub-residual-gaps-recognized.md)
- [🆕 NEW — #825 / PR #833 — Uber recognized-surface customer redact wave](checklist/open/825-pr-uber-recognized-surface-customer-redact-wave.md)
- [🆕 NEW — #795 / PR #834 — PIN-required delivery confirmation flow + plainMask](checklist/open/795-pr-pin-required-delivery-confirmation-flow.md)
- [🆕 NEW — #809 / PR #820 + #803 / PR #821 — pickup/dropoff PII redacts (desk-resolvable)](checklist/open/809-pr-pr-pickup-dropoff-pii-redacts-desk-resolvable.md)
- [🆕 NEW — GoPuff / multi-order drop-off confirm card recognized (#501 items 1-2 / PR #743)](checklist/open/501-gopuff-multi-order-drop-off-confirm-card.md)
- [🆕 NEW — GoPuff zone-arrival screens recognized (recognize-only, no state effect) (#501…](checklist/open/501-gopuff-zone-arrival-screens-recognized-recognize.md)
- [🔒 PLEDGE FIX — recognized-notification captures now mask customer PII (#620)](checklist/open/620-pledge-fix-recognized-notification-captures-now.md)
- [🔒 PLEDGE FIX — dropoff-reminder screen capture now masks the address (#624)](checklist/open/624-pledge-fix-dropoff-reminder-screen-capture-now.md)
- [🔧 FIX SHIPPED — rule effects from notifications key per-arrival, not per-install (#604)…](checklist/open/604-fix-shipped-rule-effects-from-notifications-key.md)
- [7-Eleven / alcohol "Verify items" pickup screen now recognized (#462, first slice)](checklist/open/462-7-eleven-alcohol-verify-items-pickup-screen-now.md)
- [Batch-1 recognition gaps from 2026-06-12 now recognized (#462)](checklist/open/462-batch-1-recognition-gaps-from-2026-06-12-now.md)
- [Batch-2 recognition gaps — idle/lifecycle (#462, now CLOSED)](checklist/open/462-batch-2-recognition-gaps-idle-lifecycle-now-closed.md)
- [Order-ready push notification now recognized (#462)](checklist/open/462-order-ready-push-notification-now-recognized.md)
- [Sensitive model corrected — block the DASHER's data + ID/signature IMAGES; HASH customers…](checklist/open/463-sensitive-model-corrected-block-the-dasher-s-data.md)
- [Engine latency + dedupe pack (#436)](checklist/open/436-engine-latency-dedupe-pack.md)
- [Per-offer dedupe now engages (#427)](checklist/open/427-per-offer-dedupe-now-engages.md)
- [Uber sensitive screens now blocked + UNKNOWN-capture scrub (#432)](checklist/open/432-uber-sensitive-screens-now-blocked-unknown-capture.md)
- [Timeline storeHint now parses + pickup_picked_up rule newly matchable (#433)](checklist/open/433-timeline-storehint-now-parses-pickup-picked-up.md)
- [Tree ingestion now bounded — confirm no real screen trips the caps (#363, PR #391)](checklist/open/363-tree-ingestion-now-bounded-confirm-no-real-screen.md)
- [UNKNOWN capture volume should drop materially (#360, PR #388)](checklist/open/360-unknown-capture-volume-should-drop-materially-pr.md)
- [Captures still write on dev builds (#346)](checklist/open/346-captures-still-write-on-dev-builds.md)
- [Self-recognition fixed: our own bubble is no longer parsed as a DoorDash offer (#4, PR…](checklist/open/4-self-recognition-fixed-our-own-bubble-is-no-longer.md)
- [Screenshots settle before capture (PR #325)](checklist/open/325-screenshots-settle-before-capture-pr.md)
- [`nav_arriving` screen now recognized — confirm it fires + gauge frequency (PR #312)](checklist/open/312-nav-arriving-screen-now-recognized-confirm-it.md)
- [Try EXITING the map with the Exit button (not the back gesture) — capture the click](checklist/open/noissue-try-exiting-the-map-with-the-exit-button-not-the.md)
- [Alcohol delivery ID-verification flow recognized + arrival timing (#149)](checklist/open/149-alcohol-delivery-id-verification-flow-recognized.md)
- [Offers behind a loading overlay (#275, merged 2026-06-02)](checklist/open/275-offers-behind-a-loading-overlay-merged-2026-06-02.md)
- [Cashout / transfer screens blocked (#275, merged 2026-06-02)](checklist/open/275-cashout-transfer-screens-blocked-merged-2026-06-02.md)

### state machine

- [🆕 NEW — #874 / #875 — the Uber HOME screen stopped guessing "offline", and the nav-less…](checklist/open/874-the-uber-home-screen-stopped-guessing-offline-and.md)
- [🆕 NEW — #857 / PR #872 — Uber offline detection is now positive-evidence (idle_map…](checklist/open/857-pr-uber-offline-detection-is-now-positive-evidence.md)
- [🆕 NEW — #786 / PR #869 — Uber decline click rule (the X is recognized)](checklist/open/786-pr-uber-decline-click-rule-the-x-is-recognized.md)
- [🆕 NEW — Uber active-job skeleton: accepts become costed jobs at trip start; honest "ON…](checklist/open/762-uber-active-job-skeleton-accepts-become-costed.md)
- [🆕 NEW — WATCH (accepted residual): coarse-only Uber trip may leave its job open past the…](checklist/open/762-watch-accepted-residual-coarse-only-uber-trip-may.md)
- [🆕 NEW — "Shopping off" declines shop offers at the verdict edge (#762 D12 / PR #778)](checklist/open/762-shopping-off-declines-shop-offers-at-the-verdict.md)
- [🆕 NEW — multi-pickup stack: symmetric pickup placeholders + store re-attribution (#526 /…](checklist/open/526-multi-pickup-stack-symmetric-pickup-placeholders.md)
- [🔧 FIX SHIPPED — dropoff handoff waits for the completion CTA + stacked leg-2 drops get…](checklist/open/603-fix-shipped-dropoff-handoff-waits-for-the.md)
- [Event log reworked: domain AppEvent + transactional insert + obs-derived timestamps…](checklist/open/354-event-log-reworked-domain-appevent-transactional.md)
- [Paused dash auto-expires when the pause clock runs out (#342)](checklist/open/342-paused-dash-auto-expires-when-the-pause-clock-runs.md)
- [App-switch mid-dash → "Session resumed (grace)" → dash + task continuity (2026-06-03 #3)](checklist/open/3-app-switch-mid-dash-session-resumed-grace-dash.md)

### analytics

- [🆕 NEW — #1024 part 1 (PR #1025) — the Playbook destination](checklist/open/1024-part-1-pr-the-playbook-destination.md)
- [🆕 NEW — #1024 part 2 (PR #1026) — Money tab: one number, one place](checklist/open/1024-part-2-pr-money-tab-one-number-one-place.md)
- [🆕 NEW — #983 — Time tab: the two hourly rates, your typical online hour, gap stats…](checklist/open/983-time-tab-the-two-hourly-rates-your-typical-online.md)
- [🆕 NEW — #981 — Weekly Plan + the Sunday notification (redesign stage 6)](checklist/open/981-weekly-plan-the-sunday-notification-redesign-stage.md)
- [🆕 NEW — #979 — Patterns: ALL-TIME badge, heatmap Rate/Hours toggle, store leaderboard…](checklist/open/979-patterns-all-time-badge-heatmap-rate-hours-toggle.md)
- [🆕 NEW — #975 — Analytics → Offers tab (redesign stage 3)](checklist/open/975-analytics-offers-tab-redesign-stage-3.md)
- [🆕 NEW — #810-B2 / PR #847 — orphan offer resolution (inference + attestation)](checklist/open/810-b2-pr-orphan-offer-resolution-inference.md)
- [🆕 NEW — multi-store-from-one-receipt keying: both stores keyed from a single end-of-job…](checklist/open/159-multi-store-from-one-receipt-keying-both-stores.md)
- [🆕 NEW — Patterns tab store cards: glanceable face + detail bottom sheet (#765 / PR #799)](checklist/open/765-patterns-tab-store-cards-glanceable-face-detail.md)
- [🆕 NEW — categorize a "(No session)" orphan delivery into its real dash (#660 piece 2)](checklist/open/660-categorize-a-no-session-orphan-delivery-into-its.md)
- ["(No session)" bucket keeps gross ≥ net when a delivery lands without a session (#660…](checklist/open/660-no-session-bucket-keeps-gross-net-when-a-delivery.md)
- [🆕 NEW — edit a delivery directly + cash tips (#688 phase A / PR)](checklist/open/688-edit-a-delivery-directly-cash-tips-phase-a-pr.md)
- [🆕 NEW — receipt-less shop delivery shows est. offer pay, not $0-unattributed (#691 / PR)](checklist/open/691-receipt-less-shop-delivery-shows-est-offer-pay-not.md)
- [🆕 NEW — Money tab 4-step waterfall: Fuel vs Non-fuel, with a clean fallback on mixed…](checklist/open/659-money-tab-4-step-waterfall-fuel-vs-non-fuel-with-a.md)
- [🆕 NEW — the analytics read-model projector now folds `app_events` into durable records…](checklist/open/314-the-analytics-read-model-projector-now-folds-app.md)

### HUD

- [🆕 NEW — #1024 part 3 (PR #1027) — Home is four blocks](checklist/open/1024-part-3-pr-home-is-four-blocks.md)
- [🆕 NEW — #936 / PR #952 — an offer with an unreadable distance says so instead of guessing](checklist/open/936-pr-an-offer-with-an-unreadable-distance-says-so.md)
- [🆕 NEW — #942 / PR #953 — UI-edge SSOT batch (four quick eyeball checks, one dash)](checklist/open/942-pr-ui-edge-ssot-batch-four-quick-eyeball-checks.md)
- [🆕 NEW — #867 write-side / PR #873 — bubble chat messages carry their own session](checklist/open/867-write-side-pr-bubble-chat-messages-carry-their-own.md)
- [🆕 NEW — #801 / PR #817 — bubble session-earnings freshness on 0.230.0](checklist/open/801-pr-bubble-session-earnings-freshness-on-0-230-0.md)
- [🆕 NEW — idle bubble: gas + vehicle are now two separate full-width cards (#728 / PR #767)](checklist/open/728-idle-bubble-gas-vehicle-are-now-two-separate-full.md)
- [No transient double drop-off card at the door (#458)](checklist/open/458-no-transient-double-drop-off-card-at-the-door.md)
- [Post-dash HUD: frozen summary + consistent chat (#367, PR pending)](checklist/open/367-post-dash-hud-frozen-summary-consistent-chat-pr.md)
- [HUD numbers/timers re-plumbed through one shared format/time kit (#358, PR #386)](checklist/open/358-hud-numbers-timers-re-plumbed-through-one-shared.md)
- [Offer evaluation always matches the offer on screen (#345)](checklist/open/345-offer-evaluation-always-matches-the-offer-on.md)
- [Deadline countdowns still correct under the new transform clock (#343)](checklist/open/343-deadline-countdowns-still-correct-under-the-new.md)
- [Notification text now formatted (verdict bold/colored/larger, headline bold) (#110, PR…](checklist/open/110-notification-text-now-formatted-verdict-bold.md)
- [Shop-for-items offer card shows ONE pickup (repro watch; #338)](checklist/open/338-shop-for-items-offer-card-shows-one-pickup-repro.md)
- [Screenshots still save + no offer-time jank (#349)](checklist/open/349-screenshots-still-save-no-offer-time-jank.md)
- [Offer heads-up notification with Accept/Decline (#110 surface pivot, PR pending)](checklist/open/110-offer-heads-up-notification-with-accept-decline.md)
- [Offer TTS now speaks the EVALUATION, not the raw offer (#110 step ii, PR pending)](checklist/open/110-offer-tts-now-speaks-the-evaluation-not-the-raw.md)
- [Bubble Accept/Decline now click DoorDash + collapse (re-test of 2026-06-09 #1 + #3, PR…](checklist/open/1-bubble-accept-decline-now-click-doordash-collapse.md)
- [Offer card redesign — visuals (#110 Stage 1, PR pending)](checklist/open/110-offer-card-redesign-visuals-stage-1-pr-pending.md)
- [Brand theme — HUD legibility & numerals (#94 / #313, PR pending)](checklist/open/94-brand-theme-hud-legibility-numerals-pr-pending.md)

### census

No items in this age/counter group.

### other

- [🆕 NEW — #944 / PR #957 — permission gate re-hoisted (UDF)](checklist/open/944-pr-permission-gate-re-hoisted-udf.md)
- [🆕 NEW — #938 / PR #954 — English-locale boundary notice](checklist/open/938-pr-english-locale-boundary-notice.md)
- [🆕 NEW — Capability consent surface: honest copy + revoke aborts automation to manual…](checklist/open/422-capability-consent-surface-honest-copy-revoke.md)
- [🆕 NEW — PII-safe bug-report log export (#551 P2): Data & Privacy → Export Data → Export…](checklist/open/551-pii-safe-bug-report-log-export-p2-data-privacy.md)
- [Evidence Locker settings are now real (#426)](checklist/open/426-evidence-locker-settings-are-now-real.md)
- [Platform toggles now take effect live — no app restart (#356, PR #384)](checklist/open/356-platform-toggles-now-take-effect-live-no-app.md)
- [Economy fields keep the decimal point while typing (#350; desk-verifiable, no dash needed)](checklist/open/350-economy-fields-keep-the-decimal-point-while-typing.md)

## §4 1/2 items older than 6 weeks

32 items retain `[active]`: the aging rule applies only to 0/2. Dates below summarize existing confirmation notes, including partial checks; this extraction adds no evidence and proposes no automatic closures. Groups are ordered newest first by first-line blame.

### recognition

- [🆕 NEW — #796 / PR #837 — DoorDash recognition-gap batch (11 rules)](checklist/open/796-pr-doordash-recognition-gap-batch-11-rules.md)

### state machine

- [🆕 NEW — #1000 (PR #1013) — a blown-through pickup still links its offer to the job](checklist/open/1000-pr-a-blown-through-pickup-still-links-its-offer-to.md)
- [Drop-off GPS runs while parked at the door (#294 — CONFIRMED at desk 08-09, dev-eyes half…](checklist/open/294-drop-off-gps-runs-while-parked-at-the-door.md)
- [🆕 NEW — #830 / PR #839 — presentation-scoped offer identity (+ the #826 accept chain)](checklist/open/830-pr-presentation-scoped-offer-identity-the-accept.md)
- [🆕 NEW — #810 B1 / PR #818 — JOB_ACCEPT_MISMATCH close tripwire](checklist/open/810-b1-pr-job-accept-mismatch-close-tripwire.md)
- [🆕 NEW — offer lifecycle unchanged single-platform after the platform-owned-offers move…](checklist/open/438-offer-lifecycle-unchanged-single-platform-after.md)
- [Session-end grace — summary no longer ends the dash instantly (#431)](checklist/open/431-session-end-grace-summary-no-longer-ends-the-dash.md)
- [New dash right after ending one starts fresh (#286 / #279-B / #290)](checklist/open/286-dash-right-after-ending-one-starts-fresh-b.md)

### analytics

- [🆕 NEW — #996/#997 (PR #1012) — per-offer pay attribution on a receipt-less dash](checklist/open/996-pr-per-offer-pay-attribution-on-a-receipt-less.md)
- [🆕 NEW — Analytics → Patterns tab: store report cards + net-$/hr heatmap (#315 H5 / PR)](checklist/open/315-analytics-patterns-tab-store-report-cards-net-hr.md)
- [🆕 NEW — per-dash drill-down (#650 PR A): tap a recent dash on the Money tab](checklist/open/650-per-dash-drill-down-pr-a-tap-a-recent-dash-on-the.md)
- [🆕 NEW — user corrections as events (#650 PR B): add a missed delivery, adjust a pay](checklist/open/650-user-corrections-as-events-pr-b-add-a-missed.md)
- [🆕 NEW — CSV data export (#319)](checklist/open/319-csv-data-export.md)
- [🆕 NEW — Analytics tile now opens the Money tab (#315 H1 / Money tab v1)](checklist/open/315-analytics-tile-now-opens-the-money-tab-h1-money.md)
- [🆕 NEW — Analytics Decisions tab: offer funnel + value-of-saying-no + score-vs-outcome…](checklist/open/315-analytics-decisions-tab-offer-funnel-value-of.md)
- [🆕 NEW — Analytics Time tab: time split, deadhead, on-time gauge, mileage & tax (#315 H4)](checklist/open/315-analytics-time-tab-time-split-deadhead-on-time.md)
- [🆕 NEW — Analytics Money tab: earnings-by-day chart + header CSV export icon (#315 H6)](checklist/open/315-analytics-money-tab-earnings-by-day-chart-header.md)

### HUD

- [🆕 NEW — #867 (display side) — the bubble says WHICH dash it is showing, and you can…](checklist/open/867-display-side-the-bubble-says-which-dash-it-is.md)
- [🆕 NEW — bubble gas quick-edit is mode-adaptive: refresh (AUTO) vs. Resume auto (MANUAL)…](checklist/open/722-bubble-gas-quick-edit-is-mode-adaptive-refresh.md)
- [🆕 NEW — bubble post-dash card shows the TRUE last session + gas/vehicle quick actions…](checklist/open/693-bubble-post-dash-card-shows-the-true-last-session.md)
- [Offer card surfaces Shop & Deliver: item count in the hero row + a SHOP badge (#461 a/b)](checklist/open/461-offer-card-surfaces-shop-deliver-item-count-in-the.md)
- [Pickup/Delivery task cards redesigned to the co-hero design (#460/#324)](checklist/open/460-pickup-delivery-task-cards-redesigned-to-the-co.md)
- [Bubble keeps showing the last dash after it ends / after a crash (#459)](checklist/open/459-bubble-keeps-showing-the-last-dash-after-it-ends.md)
- [Pickup/Delivery card deadline reads cleanly — no double "by" (#460)](checklist/open/460-pickup-delivery-card-deadline-reads-cleanly-no.md)
- ["Saved: $X" bubble shows the dollar sign now (#456)](checklist/open/456-saved-x-bubble-shows-the-dollar-sign-now.md)
- [Bubble HUD no longer crashes on arrival-bearing dropoffs (#297)](checklist/open/297-bubble-hud-no-longer-crashes-on-arrival-bearing.md)
- [Shop & Deliver items/min (#276, merged 2026-06-02)](checklist/open/276-shop-deliver-items-min-merged-2026-06-02.md)
- [End-of-dash summary attribution (#279)](checklist/open/279-end-of-dash-summary-attribution.md)

### census

No items in this age/counter group.

### other

- [🆕 NEW — #991 (P0) — the spoken offer verdict went silent and stayed silent](checklist/open/991-p0-the-spoken-offer-verdict-went-silent-and-stayed.md)
- [🆕 NEW — #843 — prompted per-capability automation consent (no auto-grant)](checklist/open/843-prompted-per-capability-automation-consent-no-auto.md)
- [🆕 NEW — Principle-7 logging phase 1: INFO+ log is PII-safe, `SCREEN:` spam gone, real…](checklist/open/551-principle-7-logging-phase-1-info-log-is-pii-safe.md)

## §5 Field-validated / retired

- [Shop & Deliver items/min reaches `total/total` at the end (#302)](checklist/closed/302-shop-deliver-items-min-reaches-total-total-at-the.md)
- [🆕 NEW — the receipt's auto-expand tap re-finds the row by its labels, and every tap lands…](checklist/closed/1149-the-receipt-s-auto-expand-tap-re-finds-the-row-by.md)
- [🆕 NEW — a live drop-off sheet never shows as a receipt (#935)](checklist/closed/935-a-live-drop-off-sheet-never-shows-as-a-receipt.md)
- [🆕 NEW — a same-customer double-order job closes at its receipt; the next offer is its OWN…](checklist/closed/749-a-same-customer-double-order-job-closes-at-its.md)
- [Same-store consecutive offers are two offers (#1069)](checklist/closed/1069-same-store-consecutive-offers-are-two-offers.md)
- [Restaurant pickup wait records the real dwell (#1141)](checklist/closed/1141-restaurant-pickup-wait-records-the-real-dwell.md)

### Superseded (dev-confirmed 2026-10-07 — the replacing redesign is named on each; the #657 earnings-freshness check is retained in §2)

- [🆕 NEW — #977 — Home is now "Today" (redesign stage 4)](checklist/closed/977-home-is-now-today-redesign-stage-4.md)
- [🆕 NEW — the main dashboard is now a REVIEW surface, not a live bubble mirror (#657 / PR…](checklist/closed/657-the-main-dashboard-is-now-a-review-surface-not-a.md)
- [🆕 NEW — the home screen's top glance is now REAL "Today" totals from the read model (#314…](checklist/closed/314-the-home-screen-s-top-glance-is-now-real-today.md)
- [✨ NEW — the home screen now shows a live "This dash" glance + entry tiles (#320/#316)](checklist/closed/320-the-home-screen-now-shows-a-live-this-dash-glance.md)
