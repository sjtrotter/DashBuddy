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
5. The checklist's home is `checklist/`: one file per item. `checklist/open/` holds `active`, `must-watch`, `review` and `desk-verified` items; `checklist/closed/` holds `field-validated`, `superseded` and `retired-unvalidated` ones. Each file starts with four header lines (`status:`, `section:`, `issues:`, `confirmed:`) and a blank line, then the item text. File names are `<first issue number, else noissue>-<slug>.md`. This page indexes them; README indexes the dated log under `sessions/`.
6. A new item is a new file in `checklist/open/` plus its line here. A confirmation updates the item's `Confirmed: N/2` line, its `confirmed:` header and its line here. An item is closed by `git mv` from `checklist/open/` to `checklist/closed/` with its `status:` line changed (two confirmations → `status: field-validated`), and its line here moved to §5. Must-watch items: `grep -l '^status: must-watch' checklist/open/*`; everything in §1: `grep -l '^section: §1' checklist/open/*`.

Vocabulary (one status per item): `active` · `must-watch` · `review` (aged) · `desk-verified` · `field-validated` · `superseded` · `retired-unvalidated`.

The `?` suffix in `[must-watch?]` / `[superseded?]` marks a proposal, not an additional status or a dev decision; no item is newly desk-verified, confirmed or retired here.

## §1 Must-watch (dev-confirmed 2026-10-07 — read these first)

- [active] [Odometer capability (#1361)](checklist/open/1361-odometer-capability.md) — Confirmed: 0/2
- [active] [Receipt vehicle (#1388)](checklist/open/1388-receipt-vehicle.md) — Confirmed: 0/2
- [active] [Vehicle editor (#1385)](checklist/open/1385-vehicle-editor.md) — Confirmed: 0/2
- [active] [CSV vehicle column (#1387)](checklist/open/1387-csv-vehicle-column.md) — Confirmed: 0/2
- [active] [Vehicle details (#1383)](checklist/open/1383-vehicle-details.md) — Confirmed: 0/2
- [active] [🆕 NEW — Dash vehicle (#1360)](checklist/open/1360-dash-vehicle.md) — Confirmed: 0/2
- [active] [Offer heads-up (#1333 S2g)](checklist/open/1333-offer-heads-up-s2g.md) — Confirmed: 0/2
- [active] [🆕 NEW — Vehicle chip (#1379)](checklist/open/1379-vehicle-chip.md) — Confirmed: 0/2
- [active] [🆕 NEW — Settings → Garage lists the car](checklist/open/1359-settings-garage-lists-the-car.md) — Confirmed: 0/2

**S2 HUD (#1333), one item per slice; each needs its own two confirmations:**

- [active] [🆕 NEW — the bubble HUD shell (#1333 S2a)](checklist/open/1333-the-bubble-hud-shell-s2a.md) — Confirmed: 0/2
- [active] [🆕 NEW — the state cards (#1333 S2b)](checklist/open/1333-the-state-cards-s2b.md) — Confirmed: 0/2
- [active] [🆕 NEW — the offer card (#1333 S2c)](checklist/open/1333-the-offer-card-s2c.md) — Confirmed: 0/2
- [active] [🆕 NEW — the log (#1333 S2d)](checklist/open/1333-the-log-s2d.md) — Confirmed: 0/2
- [active] [🆕 NEW — the goal-met sequence (#1333 S2e)](checklist/open/1333-the-goal-met-sequence-s2e.md) — Confirmed: 0/2
- [active] [🆕 NEW — font-scale tiers (#1333 S2e)](checklist/open/1333-font-scale-tiers-s2e.md) — Confirmed: 0/2
- [active] [🆕 NEW — cash tip sheet and undo strip (#1333 S3a)](checklist/open/1333-cash-tip-sheet-and-undo-strip-s3a.md) — Confirmed: 0/2
- [active] [🆕 NEW — vehicles as types (#1358)](checklist/open/1358-vehicles-as-types.md) — Confirmed: 0/2
- [active] [🆕 NEW — expense subcard and keypad (#1333 S3b)](checklist/open/1333-expense-subcard-and-keypad-s3b.md) — Confirmed: 0/2
- [active] [🆕 NEW — the garage is the vehicle list (#1355)](checklist/open/1355-the-garage-is-the-vehicle-list.md) — Confirmed: 0/2
- [active] [🆕 NEW — daily goal sheet (#1333 S3c, panel 13b)](checklist/open/1333-daily-goal-sheet-s3c-panel-13b.md) — Confirmed: 0/2
- [active] [🆕 NEW — odometer and vehicle (#1333 S2f)](checklist/open/1333-odometer-and-vehicle-s2f.md) — Confirmed: 0/2
- [active] [🆕 NEW — Settings → Export Data writes expenses.csv and fuel.csv with today's entries…](checklist/open/1333-settings-export-data-writes-expenses-csv-and-fuel.md) — Confirmed: 0/2
- [active] [🆕 NEW — S2 overall (#1333)](checklist/open/1333-s2-overall.md) — Confirmed: 0/2
- [active] [🆕 NEW — unassigning the LAST order of a job (DoorDash returns to "Finding offers") closes…](checklist/open/1312-unassigning-the-last-order-of-a-job-doordash.md) — Confirmed: 0/2
- [active] [🆕 NEW — accepting an ORDINARY offer card while the app still shows a job open closes that…](checklist/open/1119-accepting-an-ordinary-offer-card-while-the-app.md) — Confirmed: 0/2
- [active] [🆕 NEW — the HUD earnings reads "—" until the first parsed total of the dash, never…](checklist/open/1041-the-hud-earnings-reads-until-the-first-parsed.md) — Confirmed: 0/2

Five items the dev confirmed on 2026-10-07. Each full item appears once, with its original counter. #1149 reached 2/2 on the 10-26 desk pass and moved to §5, so four remain.

- [must-watch] [Name the money (#1135 PR 1)](checklist/open/1135-name-the-money-pr-1.md) — Confirmed: 0/2
- [must-watch] [Session report correction (#1134)](checklist/open/1134-session-report-correction.md) — Confirmed: 0/2
- [must-watch] [Chat compose box never persists raw (#919)](checklist/open/919-chat-compose-box-never-persists-raw.md) — Confirmed: 1/2
- [must-watch] [🆕 NEW — #1058 — the two dropoff sheets that were shipping addresses and door codes to…](checklist/open/1058-the-two-dropoff-sheets-that-were-shipping.md) — Confirmed: 0/2

## §2 Active

- [active] [🆕 NEW — after a GPS dash, the delivery drill-down's rows carry an arrival odometer and…](checklist/open/1310-after-a-gps-dash-the-delivery-drill-down-s-rows.md) — Confirmed: 0/2
- [active] [🆕 NEW — a 2-drop job's rows never exceed its odometer span and never show negative…](checklist/open/1108-a-2-drop-job-s-rows-never-exceed-its-odometer-span.md) — Confirmed: unspecified
- [active] [🆕 NEW — no 5-second ghost dash after End dash (#1311)](checklist/open/1311-no-5-second-ghost-dash-after-end-dash.md) — Confirmed: 0/2
- [active] [🆕 NEW — same-store second order gets its own pickup; a drop confirmed through the…](checklist/open/1315-same-store-second-order-gets-its-own-pickup-a-drop.md) — Confirmed: 0/2
- [active] [🆕 NEW — gift order drop-off sheet recognized + masked (#1314)](checklist/open/1314-gift-order-drop-off-sheet-recognized-masked.md) — Confirmed: 0/2
- [active] [#254 — verify defaults through 9 eligible deliveries; at 10, Economy shows the platform…](checklist/open/254-verify-defaults-through-9-eligible-deliveries-at.md) — Confirmed: 0/2

### 0/2 or 1/2 younger than 6 weeks — newest first

- [active] [#1002 short "Tip Update" push recognized](checklist/open/1002-short-tip-update-push-recognized.md) — Confirmed: 0/2
- [active] [#1222 offer_accepting stays off a live offer card](checklist/open/1222-offer-accepting-stays-off-a-live-offer-card.md) — Confirmed: 0/2
- [active] [#1291 shop-order arrival bubble only when the count changed](checklist/open/1291-shop-order-arrival-bubble-only-when-the-count.md) — Confirmed: 0/2
- [active] [#1116 timeline order-detail sheet (privacy, debug build)](checklist/open/1116-timeline-order-detail-sheet-privacy-debug-build.md) — Confirmed: 0/2
- [active] [#1255 analytics](checklist/open/1255-analytics.md) — Confirmed: 0/2
- [active] [#1254 heads-up offer card: Decline is now on the RIGHT, Accept on the LEFT (24dp gap)](checklist/open/1254-heads-up-offer-card-decline-is-now-on-the-right.md) — Confirmed: 0/2
- [active] [#1189 notification census: with debug census enabled, an admitted UNKNOWN push produces…](checklist/open/1189-notification-census-with-debug-census-enabled-an.md) — Confirmed: 0/2
- [active] [8.95.6 sheets (#1079)](checklist/open/1079-8-95-6-sheets.md) — Confirmed: 0/2

Ordered by first-line blame date; source order breaks ties. The five §1 proposals are not repeated here.

- [active] [Trip Radar board (#856)](checklist/open/856-trip-radar-board.md) — Confirmed: 0/2
- [active] [Per-order handling (#1113 slice 1)](checklist/open/1113-per-order-handling-slice-1.md) — Confirmed: 0/2
- [active] [Consent receipts (#170)](checklist/open/170-consent-receipts.md) — Confirmed: 0/2
- [active] [Arrival re-eval (#823 P2)](checklist/open/823-arrival-re-eval-p2.md) — Confirmed: 0/2
- [active] [Bubble lifecycle is visible in the shareable stream (#916)](checklist/open/916-bubble-lifecycle-is-visible-in-the-shareable.md) — Confirmed: 1/2
- [active] [Resolved offer cards no longer wear a verdict banner (#864)](checklist/open/864-resolved-offer-cards-no-longer-wear-a-verdict.md) — Confirmed: 0/2
- [active] [Desk-first — #1133 (PR #1219): pay-less rows carry their car cost](checklist/open/1133-desk-first-pr-pay-less-rows-carry-their-car-cost.md) — Confirmed: 0/2
- [active] [8.99.20 privacy rules (#1127 / #1128 / #1139) + the store-signature block (#1022)](checklist/open/1127-8-99-20-privacy-rules-the-store-signature-block.md) — Confirmed: 0/2
- [active] [Classify & draft a real UNKNOWN cluster (#1188, server v0.11.0)](checklist/open/1188-classify-draft-a-real-unknown-cluster-server-v0-11.md) — Confirmed: 0/2
- [active] [Trusted UNKNOWN captures (#1200)](checklist/open/1200-trusted-unknown-captures.md) — Confirmed: 1/2
- [active] [Daily recognition health reaches the server (#1197, PR #1198)](checklist/open/1197-daily-recognition-health-reaches-the-server-pr.md) — Confirmed: 1/2
- [active] [Census developer status line + Reset census identity (#1185)](checklist/open/1185-census-developer-status-line-reset-census-identity.md) — Confirmed: 1/2
- [active] [🆕 NEW — one consent decision per rule action; the prompt re-collects once (#1167)](checklist/open/1167-one-consent-decision-per-rule-action-the-prompt-re.md) — Confirmed: 1/2
- [active] [🆕 NEW — chat header and chat preview are masked in captures (PR #1160 / #1145)](checklist/open/1160-chat-header-and-chat-preview-are-masked-in.md) — Confirmed: 0/2
- [active] [🆕 NEW — wide event receipt is an opt-in consent, asked BEFORE the accessibility grant…](checklist/open/1151-wide-event-receipt-is-an-opt-in-consent-asked.md) — Confirmed: 1/2
- [active] [🆕 NEW — Uber offers drawn as an overlay are captured, whatever has focus (#1152)](checklist/open/1152-uber-offers-drawn-as-an-overlay-are-captured.md) — Confirmed: 0/2
- [active] [🆕 NEW — the dash-controls sheet recognizes as `timeline` on a per-offer dash with no task…](checklist/open/1165-the-dash-controls-sheet-recognizes-as-timeline-on.md) — Confirmed: 1/2
- [active] [🆕 NEW — Compose ADD-ON recognition and honest marginal figures (#1121)](checklist/open/1121-compose-add-on-recognition-and-honest-marginal.md) — Confirmed: 0/2
- [active] [🆕 NEW — confirm-pickup sheet masks the customer (#1309)](checklist/open/1309-confirm-pickup-sheet-masks-the-customer.md) — Confirmed: 0/2
- [active] [🆕 NEW — add-on Accept mints one dropoff, not one per listed row (#1304)](checklist/open/1304-add-on-accept-mints-one-dropoff-not-one-per-listed.md) — Confirmed: 0/2
- [active] [🆕 NEW — the 8.98.5 drop-off sheet masks on every render that keeps a stable row (#1122 +…](checklist/open/1122-the-8-98-5-drop-off-sheet-masks-on-every-render.md) — Confirmed: 1/2
- [active] [🆕 NEW — the 8.97.8 "Drop off steps" wrapper masks the customer's instruction body (#1107…](checklist/open/1107-the-8-97-8-drop-off-steps-wrapper-masks-the.md) — Confirmed: 0/2
- [active] [🆕 NEW — a return order must record its return pay (#998)](checklist/open/998-a-return-order-must-record-its-return-pay.md) — Confirmed: 0/2
- [active] [🆕 NEW — a stacked job's two drops must not be charged the same miles twice (#1108)](checklist/open/1108-a-stacked-job-s-two-drops-must-not-be-charged-the.md) — Confirmed: 0/2
- [active] [🆕 NEW — unassigning at the store must not record a pickup (#301)](checklist/open/301-unassigning-at-the-store-must-not-record-a-pickup.md) — Confirmed: 0/2
- [active] [🆕 NEW — the offer card's Decline target must stay bound on 8.97.8](checklist/open/1115-the-offer-card-s-decline-target-must-stay-bound-on.md) — Confirmed: 0/2
- [active] [🆕 NEW — a dash you start while the last dash's receipt is still on screen must not…](checklist/open/1103-a-dash-you-start-while-the-last-dash-s-receipt-is.md) — Confirmed: 0/2
- [active] [🆕 NEW — a decline you confirm fast must still be recorded as a decline, not a timeout](checklist/open/1104-a-decline-you-confirm-fast-must-still-be-recorded.md) — Confirmed: 1/2
- [active] [🆕 NEW — #1054 — a pause survives a relaunch: the HUD stays PAUSED, and the dash still…](checklist/open/1054-a-pause-survives-a-relaunch-the-hud-stays-paused.md) — Confirmed: 0/2
- [active] [🆕 NEW — #1054 — a grace that lapses while nothing is on screen still commits, and…](checklist/open/1054-a-grace-that-lapses-while-nothing-is-on-screen.md) — Confirmed: 0/2
- [active] [🆕 NEW — #1063 — an offer is recognized from its FIRST frame, before the Decline button…](checklist/open/1063-an-offer-is-recognized-from-its-first-frame-before.md) — Confirmed: 1/2
- [active] [🆕 NEW — #1059 — the Persona verification flow, the Red Card wallet and the passport…](checklist/open/1059-the-persona-verification-flow-the-red-card-wallet.md) — Confirmed: 0/2

### Counter unspecified — retained active, newest first

These 39 items lack a whole-item `Confirmed: N/2` counter. Some record a desk-half count or narrative sightings; neither establishes a whole-item count. No zero counter or aged-review status is inferred. The dev can resolve these separately.

- [active] ["Dash paused" push is recognized (#1090)](checklist/open/1090-dash-paused-push-is-recognized.md) — Confirmed: unspecified
- [active] [Census deferral lines name their cause (#1210)](checklist/open/1210-census-deferral-lines-name-their-cause.md) — Confirmed: unspecified
- [active] [Desk-first — #965 / #1126 (PR #1213)](checklist/open/965-desk-first-pr.md) — Confirmed: unspecified
- [active] [🆕 NEW — #967 / PR #968 — ratings stamp during an Offline browse (the early-return fix)](checklist/open/967-pr-ratings-stamp-during-an-offline-browse-the.md) — Confirmed: unspecified
- [active] [🆕 NEW — identity-less completions now firewalled at PostTask exit (#653 / PR #673): watch…](checklist/open/653-identity-less-completions-now-firewalled-at.md) — Confirmed: unspecified
- [active] [🆕 NEW — driving/glance-mode HUD font-scale toggle (#318)](checklist/open/318-driving-glance-mode-hud-font-scale-toggle.md) — Confirmed: unspecified
- [active] [🔧 FIX SHIPPED — a stacked job's DELIVERY_COMPLETED rows now carry per-drop realized pay…](checklist/open/528-fix-shipped-a-stacked-job-s-delivery-completed.md) — Confirmed: unspecified
- [active] [🔧 FIX SHIPPED — offer outcome cards now derive from the committed outcome, not the tap…](checklist/open/601-fix-shipped-offer-outcome-cards-now-derive-from.md) — Confirmed: unspecified
- [active] [🔧 FIX SHIPPED — pausing during/after a delivery shows exactly one "Dash Paused!", no…](checklist/open/605-fix-shipped-pausing-during-after-a-delivery-shows.md) — Confirmed: unspecified
- [active] [🔧 FIX SHIPPED — one DashSummary screenshot per session end, and offer screenshots are…](checklist/open/606-fix-shipped-one-dashsummary-screenshot-per-session.md) — Confirmed: unspecified
- [active] [🔒 FIX SHIPPED — recognized capture envelopes now redact customer PII at the device edge…](checklist/open/598-fix-shipped-recognized-capture-envelopes-now.md) — Confirmed: unspecified
- [active] [🔧 FIX SHIPPED — automated taps now rank click candidates by evidence instead of a dead…](checklist/open/600-fix-shipped-automated-taps-now-rank-click.md) — Confirmed: unspecified
- [active] [🔧 FIX SHIPPED — a shade-tapped Accept/Decline now retries instead of dying on "No live…](checklist/open/602-fix-shipped-a-shade-tapped-accept-decline-now.md) — Confirmed: unspecified
- [active] [🔧 FIX SHIPPED — receipt-skipped deliveries still close + log a completion, and the next…](checklist/open/596-fix-shipped-receipt-skipped-deliveries-still-close.md) — Confirmed: unspecified
- [active] [🔧 FIX SHIPPED — click captures record every rule-matched tap + UNKNOWN cap re-arms after…](checklist/open/597-fix-shipped-click-captures-record-every-rule.md) — Confirmed: unspecified
- [active] [🔧 FIX SHIPPED — a committed decline can't be un-declined by a later Accept (#594).…](checklist/open/594-fix-shipped-a-committed-decline-can-t-be-un.md) — Confirmed: unspecified
- [active] [🔧 FIX SHIPPED — post-accept teardown frame no longer mints a ghost offer (#595). CONFIRM…](checklist/open/595-fix-shipped-post-accept-teardown-frame-no-longer.md) — Confirmed: unspecified
- [active] [🔒 FIX SHIPPED — Crimson Savings Jar balance notification is now pledge-BLOCKED (#599).…](checklist/open/599-fix-shipped-crimson-savings-jar-balance.md) — Confirmed: unspecified
- [active] [👁 VISUAL-ONLY — rich offer notification looks right: gauge ring, countdown, badges…](checklist/open/578-visual-only-rich-offer-notification-looks-right.md) — Confirmed: unspecified
- [active] [🔧 FIX SHIPPED — dropoff customer reads "\\<store\\>'s customer", not a 6-char hash (#568…](checklist/open/568-fix-shipped-dropoff-customer-reads-store-s.md) — Confirmed: 1/2
- [active] [🔬 FIX SHIPPED (recognize-only) — dropoff-arrived 'Leave it at the door' card now…](checklist/open/549-fix-shipped-recognize-only-dropoff-arrived-leave.md) — Confirmed: unspecified
- [active] [🔧 FIX SHIPPED — no more double fly-away bubble on a new/stacked pickup (#566, PR #573).…](checklist/open/566-fix-shipped-no-more-double-fly-away-bubble-on-a.md) — Confirmed: unspecified
- [active] [🔧 FIX SHIPPED — a pickup no longer borrows a customer identity (#548, PR #572). CONFIRM…](checklist/open/548-fix-shipped-a-pickup-no-longer-borrows-a-customer.md) — Confirmed: unspecified
- [active] [🔧 FIX SHIPPED — dropoff customer fills in the card instead of re-minting it (#565, PR…](checklist/open/565-fix-shipped-dropoff-customer-fills-in-the-card.md) — Confirmed: unspecified
- [active] [🔧 FIX SHIPPED — add-on offer no longer fabricates a $0 "completion" (#564, PR #570).…](checklist/open/564-fix-shipped-add-on-offer-no-longer-fabricates-a-0.md) — Confirmed: unspecified
- [active] [🔧 MERGED — realistic $/hr + score on Shop & Deliver offers (#556). CONFIRM ON DASH](checklist/open/556-merged-realistic-hr-score-on-shop-deliver-offers.md) — Confirmed: 1/2
- [active] [🔧 MERGED — dropoff store recognition + pickup-matched resolution (#526/#553). CONFIRM ON…](checklist/open/526-merged-dropoff-store-recognition-pickup-matched.md) — Confirmed: 1/2
- [active] [🔧 FIX SHIPPED — offer card icon badges + co-icon-text shop badge \[cart N\] (#461, PR…](checklist/open/461-fix-shipped-offer-card-icon-badges-co-icon-text.md) — Confirmed: unspecified
- [active] [🔧 FIX IN FLIGHT — phantom + over-minted dropoff tasks cleaned up (#498, PR #521). CONFIRM…](checklist/open/498-fix-in-flight-phantom-over-minted-dropoff-tasks.md) — Confirmed: 2/2
- [active] [🔧 FIX IN FLIGHT — a delivery can't be logged/counted twice (#518, PRs #520 + #522).…](checklist/open/518-fix-in-flight-a-delivery-can-t-be-logged-counted.md) — Confirmed: 2/2
- [active] [🔧 FIX IN FLIGHT — multi-drop stack: Job owns ordered dropoffs, routed by customer name…](checklist/open/503-fix-in-flight-multi-drop-stack-job-owns-ordered.md) — Confirmed: unspecified
- [active] [🔧 FIX SHIPPED — live $/mi on the task card, read off the Job (#503 deliverable 2, PR…](checklist/open/503-fix-shipped-live-mi-on-the-task-card-read-off-the.md) — Confirmed: unspecified
- [active] [🔧 FIX SHIPPED — GoPuff (Drive) bin-scan mints the pickup arrival (#501, PR #530). CONFIRM…](checklist/open/501-fix-shipped-gopuff-drive-bin-scan-mints-the-pickup.md) — Confirmed: unspecified
- [active] [✅ FIX SHIPPED — same-store add-on no longer re-mints the task (#499 / #503 slice 2).…](checklist/open/499-fix-shipped-same-store-add-on-no-longer-re-mints.md) — Confirmed: unspecified
- [active] [✅ FIX SHIPPED — dropoff created from the offer; the premature "Customer" card root (#503…](checklist/open/503-fix-shipped-dropoff-created-from-the-offer-the.md) — Confirmed: unspecified
- [active] [📸 CAPTURE NEEDED — GoPuff (Drive) screens, to finalize the #501 rules](checklist/open/501-capture-needed-gopuff-drive-screens-to-finalize.md) — Confirmed: unspecified
- [active] [✅ FIX SHIPPED — "ghost offer" with EMPTY parse logged as a card (#498). CONFIRM ON DASH](checklist/open/498-fix-shipped-ghost-offer-with-empty-parse-logged-as.md) — Confirmed: unspecified
- [active] [Receipt grace — delivery completion is now deferred ~2.5s (#431 pt 2)](checklist/open/431-receipt-grace-delivery-completion-is-now-deferred.md) — Confirmed: 2/2
- [active] [⚠️ WATCH FOR RECURRENCE — mid-dash "Done Dashing!" + odometer reset (2026-06-06 #5, root…](checklist/open/5-watch-for-recurrence-mid-dash-done-dashing.md) — Confirmed: unspecified

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

- [active] [🆕 NEW — #1034 — a negative dollar reads `-$12`, never `$-12`](checklist/open/1034-a-negative-dollar-reads-12-never-12.md) — Confirmed: 0/2
- [active] [🆕 NEW — #428-B / PR #845 — multi-language TTS (system locale + settings override)](checklist/open/428-b-pr-multi-language-tts-system-locale-settings.md) — Confirmed: 0/2
- [active] [🆕 NEW — #859 (H4 + placeholder filenames) — one offer screenshot per presentation, and no…](checklist/open/859-h4-placeholder-filenames-one-offer-screenshot-per.md) — Confirmed: 0/2
- [active] [🆕 NEW — unassign an order AFTER pickup (dropoff phase) also produces NO paid artifact…](checklist/open/752-unassign-an-order-after-pickup-dropoff-phase-also.md) — Confirmed: 0/2

## §3 Review (aged ≥ 6 weeks at 0/2)

95 aged zero-confirmation items: 91 `[review]` and 4 `[superseded?]` proposals. Every `desk:` line is a proposal only. A replay/check proposed here has not been run; missing field exposure is not a pass. The four supersession proposals leave the routine active queue but remain here for the dev to decide.

### recognition

- [review] [🆕 NEW — #895 / PR #927 — fused `Apt <n>` masks are plain on all 8 uber+doordash surfaces…](checklist/open/895-pr-fused-apt-n-masks-are-plain-on-all-8-uber.md) — Confirmed: 0/2
- [review] [Ratings-hub family no longer falls to UNKNOWN (2026-07-30 corpus pass)](checklist/open/noissue-ratings-hub-family-no-longer-falls-to-unknown-2026.md) — Confirmed: 0/2
- [review] [🆕 NEW — #888 — eight DoorDash screens that used to fall to UNKNOWN are now recognized](checklist/open/888-eight-doordash-screens-that-used-to-fall-to.md) — Confirmed: 0/2
- [review] [🆕 NEW — #882 / #881 — Uber stacked offers speak the STORE, and a "Match" is recorded as a…](checklist/open/882-uber-stacked-offers-speak-the-store-and-a-match-is.md) — Confirmed: 0/2
- [review] [🆕 NEW — #858 / PR #876 — expiring Uber cards no longer mint offers](checklist/open/858-pr-expiring-uber-cards-no-longer-mint-offers.md) — Confirmed: 0/2
- [review] [🆕 NEW — #861 / PR #868 — Uber selfie ID-verification camera is sensitive-blocked](checklist/open/861-pr-uber-selfie-id-verification-camera-is-sensitive.md) — Confirmed: 0/2
- [review] [🆕 NEW — #860 / PR #871 — Building Name value masked on both dropoff workflow sheets](checklist/open/860-pr-building-name-value-masked-on-both-dropoff.md) — Confirmed: 0/2
- [review] [🆕 NEW — #865 / PR #877 — performance-hub residual gaps recognized](checklist/open/865-pr-performance-hub-residual-gaps-recognized.md) — Confirmed: 0/2
- [review] [🆕 NEW — #825 / PR #833 — Uber recognized-surface customer redact wave](checklist/open/825-pr-uber-recognized-surface-customer-redact-wave.md) — Confirmed: 0/2
- [review] [🆕 NEW — #795 / PR #834 — PIN-required delivery confirmation flow + plainMask](checklist/open/795-pr-pin-required-delivery-confirmation-flow.md) — Confirmed: 0/2
- [review] [🆕 NEW — #809 / PR #820 + #803 / PR #821 — pickup/dropoff PII redacts (desk-resolvable)](checklist/open/809-pr-pr-pickup-dropoff-pii-redacts-desk-resolvable.md) — Confirmed: 0/2
- [review] [🆕 NEW — GoPuff / multi-order drop-off confirm card recognized (#501 items 1-2 / PR #743)](checklist/open/501-gopuff-multi-order-drop-off-confirm-card.md) — Confirmed: 0/2
- [review] [🆕 NEW — GoPuff zone-arrival screens recognized (recognize-only, no state effect) (#501…](checklist/open/501-gopuff-zone-arrival-screens-recognized-recognize.md) — Confirmed: 0/2
- [review] [🔒 PLEDGE FIX — recognized-notification captures now mask customer PII (#620)](checklist/open/620-pledge-fix-recognized-notification-captures-now.md) — Confirmed: 0/2
- [review] [🔒 PLEDGE FIX — dropoff-reminder screen capture now masks the address (#624)](checklist/open/624-pledge-fix-dropoff-reminder-screen-capture-now.md) — Confirmed: 0/2
- [review] [🔧 FIX SHIPPED — rule effects from notifications key per-arrival, not per-install (#604)…](checklist/open/604-fix-shipped-rule-effects-from-notifications-key.md) — Confirmed: 0/2
- [review] [7-Eleven / alcohol "Verify items" pickup screen now recognized (#462, first slice)](checklist/open/462-7-eleven-alcohol-verify-items-pickup-screen-now.md) — Confirmed: 0/2
- [review] [Batch-1 recognition gaps from 2026-06-12 now recognized (#462)](checklist/open/462-batch-1-recognition-gaps-from-2026-06-12-now.md) — Confirmed: 0/2
- [review] [Batch-2 recognition gaps — idle/lifecycle (#462, now CLOSED)](checklist/open/462-batch-2-recognition-gaps-idle-lifecycle-now-closed.md) — Confirmed: 0/2
- [review] [Order-ready push notification now recognized (#462)](checklist/open/462-order-ready-push-notification-now-recognized.md) — Confirmed: 0/2
- [review] [Sensitive model corrected — block the DASHER's data + ID/signature IMAGES; HASH customers…](checklist/open/463-sensitive-model-corrected-block-the-dasher-s-data.md) — Confirmed: 0/2
- [review] [Engine latency + dedupe pack (#436)](checklist/open/436-engine-latency-dedupe-pack.md) — Confirmed: 0/2
- [review] [Per-offer dedupe now engages (#427)](checklist/open/427-per-offer-dedupe-now-engages.md) — Confirmed: 0/2
- [review] [Uber sensitive screens now blocked + UNKNOWN-capture scrub (#432)](checklist/open/432-uber-sensitive-screens-now-blocked-unknown-capture.md) — Confirmed: 0/2
- [review] [Timeline storeHint now parses + pickup_picked_up rule newly matchable (#433)](checklist/open/433-timeline-storehint-now-parses-pickup-picked-up.md) — Confirmed: 0/2
- [review] [Tree ingestion now bounded — confirm no real screen trips the caps (#363, PR #391)](checklist/open/363-tree-ingestion-now-bounded-confirm-no-real-screen.md) — Confirmed: 0/2
- [review] [UNKNOWN capture volume should drop materially (#360, PR #388)](checklist/open/360-unknown-capture-volume-should-drop-materially-pr.md) — Confirmed: 0/2
- [review] [Captures still write on dev builds (#346)](checklist/open/346-captures-still-write-on-dev-builds.md) — Confirmed: 0/2
- [review] [Self-recognition fixed: our own bubble is no longer parsed as a DoorDash offer (#4, PR…](checklist/open/4-self-recognition-fixed-our-own-bubble-is-no-longer.md) — Confirmed: 0/2
- [review] [Screenshots settle before capture (PR #325)](checklist/open/325-screenshots-settle-before-capture-pr.md) — Confirmed: 0/2
- [review] [`nav_arriving` screen now recognized — confirm it fires + gauge frequency (PR #312)](checklist/open/312-nav-arriving-screen-now-recognized-confirm-it.md) — Confirmed: 0/2
- [review] [Try EXITING the map with the Exit button (not the back gesture) — capture the click](checklist/open/noissue-try-exiting-the-map-with-the-exit-button-not-the.md) — Confirmed: 0/2
- [review] [Alcohol delivery ID-verification flow recognized + arrival timing (#149)](checklist/open/149-alcohol-delivery-id-verification-flow-recognized.md) — Confirmed: 0/2
- [review] [Offers behind a loading overlay (#275, merged 2026-06-02)](checklist/open/275-offers-behind-a-loading-overlay-merged-2026-06-02.md) — Confirmed: 0/2
- [review] [Cashout / transfer screens blocked (#275, merged 2026-06-02)](checklist/open/275-cashout-transfer-screens-blocked-merged-2026-06-02.md) — Confirmed: 0/2

### state machine

- [review] [🆕 NEW — #874 / #875 — the Uber HOME screen stopped guessing "offline", and the nav-less…](checklist/open/874-the-uber-home-screen-stopped-guessing-offline-and.md) — Confirmed: 0/2
- [review] [🆕 NEW — #857 / PR #872 — Uber offline detection is now positive-evidence (idle_map…](checklist/open/857-pr-uber-offline-detection-is-now-positive-evidence.md) — Confirmed: 0/2
- [review] [🆕 NEW — #786 / PR #869 — Uber decline click rule (the X is recognized)](checklist/open/786-pr-uber-decline-click-rule-the-x-is-recognized.md) — Confirmed: 0/2
- [review] [🆕 NEW — Uber active-job skeleton: accepts become costed jobs at trip start; honest "ON…](checklist/open/762-uber-active-job-skeleton-accepts-become-costed.md) — Confirmed: 0/2
- [review] [🆕 NEW — WATCH (accepted residual): coarse-only Uber trip may leave its job open past the…](checklist/open/762-watch-accepted-residual-coarse-only-uber-trip-may.md) — Confirmed: 0/2
- [review] [🆕 NEW — "Shopping off" declines shop offers at the verdict edge (#762 D12 / PR #778)](checklist/open/762-shopping-off-declines-shop-offers-at-the-verdict.md) — Confirmed: 0/2
- [review] [🆕 NEW — multi-pickup stack: symmetric pickup placeholders + store re-attribution (#526 /…](checklist/open/526-multi-pickup-stack-symmetric-pickup-placeholders.md) — Confirmed: 0/2
- [review] [🔧 FIX SHIPPED — dropoff handoff waits for the completion CTA + stacked leg-2 drops get…](checklist/open/603-fix-shipped-dropoff-handoff-waits-for-the.md) — Confirmed: 0/2
- [review] [Event log reworked: domain AppEvent + transactional insert + obs-derived timestamps…](checklist/open/354-event-log-reworked-domain-appevent-transactional.md) — Confirmed: 0/2
- [review] [Paused dash auto-expires when the pause clock runs out (#342)](checklist/open/342-paused-dash-auto-expires-when-the-pause-clock-runs.md) — Confirmed: 0/2
- [review] [App-switch mid-dash → "Session resumed (grace)" → dash + task continuity (2026-06-03 #3)](checklist/open/3-app-switch-mid-dash-session-resumed-grace-dash.md) — Confirmed: 0/2

### analytics

- [review] [🆕 NEW — #1024 part 1 (PR #1025) — the Playbook destination](checklist/open/1024-part-1-pr-the-playbook-destination.md) — Confirmed: 0/2
- [review] [🆕 NEW — #1024 part 2 (PR #1026) — Money tab: one number, one place](checklist/open/1024-part-2-pr-money-tab-one-number-one-place.md) — Confirmed: 0/2
- [review] [🆕 NEW — #983 — Time tab: the two hourly rates, your typical online hour, gap stats…](checklist/open/983-time-tab-the-two-hourly-rates-your-typical-online.md) — Confirmed: 0/2
- [review] [🆕 NEW — #981 — Weekly Plan + the Sunday notification (redesign stage 6)](checklist/open/981-weekly-plan-the-sunday-notification-redesign-stage.md) — Confirmed: 0/2
- [review] [🆕 NEW — #979 — Patterns: ALL-TIME badge, heatmap Rate/Hours toggle, store leaderboard…](checklist/open/979-patterns-all-time-badge-heatmap-rate-hours-toggle.md) — Confirmed: 0/2
- [review] [🆕 NEW — #975 — Analytics → Offers tab (redesign stage 3)](checklist/open/975-analytics-offers-tab-redesign-stage-3.md) — Confirmed: 0/2
- [review] [🆕 NEW — #810-B2 / PR #847 — orphan offer resolution (inference + attestation)](checklist/open/810-b2-pr-orphan-offer-resolution-inference.md) — Confirmed: 0/2
- [review] [🆕 NEW — multi-store-from-one-receipt keying: both stores keyed from a single end-of-job…](checklist/open/159-multi-store-from-one-receipt-keying-both-stores.md) — Confirmed: 0/2
- [review] [🆕 NEW — Patterns tab store cards: glanceable face + detail bottom sheet (#765 / PR #799)](checklist/open/765-patterns-tab-store-cards-glanceable-face-detail.md) — Confirmed: 0/2
- [review] [🆕 NEW — categorize a "(No session)" orphan delivery into its real dash (#660 piece 2)](checklist/open/660-categorize-a-no-session-orphan-delivery-into-its.md) — Confirmed: 0/2
- [review] ["(No session)" bucket keeps gross ≥ net when a delivery lands without a session (#660…](checklist/open/660-no-session-bucket-keeps-gross-net-when-a-delivery.md) — Confirmed: 0/2
- [review] [🆕 NEW — edit a delivery directly + cash tips (#688 phase A / PR)](checklist/open/688-edit-a-delivery-directly-cash-tips-phase-a-pr.md) — Confirmed: 0/2
- [review] [🆕 NEW — receipt-less shop delivery shows est. offer pay, not $0-unattributed (#691 / PR)](checklist/open/691-receipt-less-shop-delivery-shows-est-offer-pay-not.md) — Confirmed: 0/2
- [review] [🆕 NEW — Money tab 4-step waterfall: Fuel vs Non-fuel, with a clean fallback on mixed…](checklist/open/659-money-tab-4-step-waterfall-fuel-vs-non-fuel-with-a.md) — Confirmed: 0/2
- [review] [🆕 NEW — the analytics read-model projector now folds `app_events` into durable records…](checklist/open/314-the-analytics-read-model-projector-now-folds-app.md) — Confirmed: 0/2

### HUD

- [review] [🆕 NEW — #1024 part 3 (PR #1027) — Home is four blocks](checklist/open/1024-part-3-pr-home-is-four-blocks.md) — Confirmed: 0/2
- [review] [🆕 NEW — #936 / PR #952 — an offer with an unreadable distance says so instead of guessing](checklist/open/936-pr-an-offer-with-an-unreadable-distance-says-so.md) — Confirmed: 0/2
- [review] [🆕 NEW — #942 / PR #953 — UI-edge SSOT batch (four quick eyeball checks, one dash)](checklist/open/942-pr-ui-edge-ssot-batch-four-quick-eyeball-checks.md) — Confirmed: 0/2
- [review] [🆕 NEW — #867 write-side / PR #873 — bubble chat messages carry their own session](checklist/open/867-write-side-pr-bubble-chat-messages-carry-their-own.md) — Confirmed: 0/2
- [review] [🆕 NEW — #801 / PR #817 — bubble session-earnings freshness on 0.230.0](checklist/open/801-pr-bubble-session-earnings-freshness-on-0-230-0.md) — Confirmed: 0/2
- [review] [🆕 NEW — idle bubble: gas + vehicle are now two separate full-width cards (#728 / PR #767)](checklist/open/728-idle-bubble-gas-vehicle-are-now-two-separate-full.md) — Confirmed: 0/2
- [review] [No transient double drop-off card at the door (#458)](checklist/open/458-no-transient-double-drop-off-card-at-the-door.md) — Confirmed: 0/2
- [review] [Post-dash HUD: frozen summary + consistent chat (#367, PR pending)](checklist/open/367-post-dash-hud-frozen-summary-consistent-chat-pr.md) — Confirmed: 0/2
- [review] [HUD numbers/timers re-plumbed through one shared format/time kit (#358, PR #386)](checklist/open/358-hud-numbers-timers-re-plumbed-through-one-shared.md) — Confirmed: 0/2
- [review] [Offer evaluation always matches the offer on screen (#345)](checklist/open/345-offer-evaluation-always-matches-the-offer-on.md) — Confirmed: 0/2
- [review] [Deadline countdowns still correct under the new transform clock (#343)](checklist/open/343-deadline-countdowns-still-correct-under-the-new.md) — Confirmed: 0/2
- [review] [Notification text now formatted (verdict bold/colored/larger, headline bold) (#110, PR…](checklist/open/110-notification-text-now-formatted-verdict-bold.md) — Confirmed: 0/2
- [review] [Shop-for-items offer card shows ONE pickup (repro watch; #338)](checklist/open/338-shop-for-items-offer-card-shows-one-pickup-repro.md) — Confirmed: 0/2
- [review] [Screenshots still save + no offer-time jank (#349)](checklist/open/349-screenshots-still-save-no-offer-time-jank.md) — Confirmed: 0/2
- [review] [Offer heads-up notification with Accept/Decline (#110 surface pivot, PR pending)](checklist/open/110-offer-heads-up-notification-with-accept-decline.md) — Confirmed: 0/2
- [review] [Offer TTS now speaks the EVALUATION, not the raw offer (#110 step ii, PR pending)](checklist/open/110-offer-tts-now-speaks-the-evaluation-not-the-raw.md) — Confirmed: 0/2
- [review] [Bubble Accept/Decline now click DoorDash + collapse (re-test of 2026-06-09 #1 + #3, PR…](checklist/open/1-bubble-accept-decline-now-click-doordash-collapse.md) — Confirmed: 0/2
- [review] [Offer card redesign — visuals (#110 Stage 1, PR pending)](checklist/open/110-offer-card-redesign-visuals-stage-1-pr-pending.md) — Confirmed: 0/2
- [review] [Brand theme — HUD legibility & numerals (#94 / #313, PR pending)](checklist/open/94-brand-theme-hud-legibility-numerals-pr-pending.md) — Confirmed: 0/2

### census

No items in this age/counter group.

### other

- [review] [🆕 NEW — #944 / PR #957 — permission gate re-hoisted (UDF)](checklist/open/944-pr-permission-gate-re-hoisted-udf.md) — Confirmed: 0/2
- [review] [🆕 NEW — #938 / PR #954 — English-locale boundary notice](checklist/open/938-pr-english-locale-boundary-notice.md) — Confirmed: 0/2
- [review] [🆕 NEW — Capability consent surface: honest copy + revoke aborts automation to manual…](checklist/open/422-capability-consent-surface-honest-copy-revoke.md) — Confirmed: 0/2
- [review] [🆕 NEW — PII-safe bug-report log export (#551 P2): Data & Privacy → Export Data → Export…](checklist/open/551-pii-safe-bug-report-log-export-p2-data-privacy.md) — Confirmed: 0/2
- [review] [Evidence Locker settings are now real (#426)](checklist/open/426-evidence-locker-settings-are-now-real.md) — Confirmed: 0/2
- [review] [Platform toggles now take effect live — no app restart (#356, PR #384)](checklist/open/356-platform-toggles-now-take-effect-live-no-app.md) — Confirmed: 0/2
- [review] [Economy fields keep the decimal point while typing (#350; desk-verifiable, no dash needed)](checklist/open/350-economy-fields-keep-the-decimal-point-while-typing.md) — Confirmed: 0/2

## §4 1/2 items older than 6 weeks

32 items retain `[active]`: the aging rule applies only to 0/2. Dates below summarize existing confirmation notes, including partial checks; this extraction adds no evidence and proposes no automatic closures. Groups are ordered newest first by first-line blame.

### recognition

- [active] [🆕 NEW — #796 / PR #837 — DoorDash recognition-gap batch (11 rules)](checklist/open/796-pr-doordash-recognition-gap-batch-11-rules.md) — Confirmed: 1/2

### state machine

- [active] [🆕 NEW — #1000 (PR #1013) — a blown-through pickup still links its offer to the job](checklist/open/1000-pr-a-blown-through-pickup-still-links-its-offer-to.md) — Confirmed: 1/2
- [active] [Drop-off GPS runs while parked at the door (#294 — CONFIRMED at desk 08-09, dev-eyes half…](checklist/open/294-drop-off-gps-runs-while-parked-at-the-door.md) — Confirmed: 1/2
- [active] [🆕 NEW — #830 / PR #839 — presentation-scoped offer identity (+ the #826 accept chain)](checklist/open/830-pr-presentation-scoped-offer-identity-the-accept.md) — Confirmed: 1/2
- [active] [🆕 NEW — #810 B1 / PR #818 — JOB_ACCEPT_MISMATCH close tripwire](checklist/open/810-b1-pr-job-accept-mismatch-close-tripwire.md) — Confirmed: 1/2
- [active] [🆕 NEW — offer lifecycle unchanged single-platform after the platform-owned-offers move…](checklist/open/438-offer-lifecycle-unchanged-single-platform-after.md) — Confirmed: 1/2
- [active] [Session-end grace — summary no longer ends the dash instantly (#431)](checklist/open/431-session-end-grace-summary-no-longer-ends-the-dash.md) — Confirmed: 1/2
- [active] [New dash right after ending one starts fresh (#286 / #279-B / #290)](checklist/open/286-dash-right-after-ending-one-starts-fresh-b.md) — Confirmed: 1/2

### analytics

- [active] [🆕 NEW — #996/#997 (PR #1012) — per-offer pay attribution on a receipt-less dash](checklist/open/996-pr-per-offer-pay-attribution-on-a-receipt-less.md) — Confirmed: 1/2
- [active] [🆕 NEW — Analytics → Patterns tab: store report cards + net-$/hr heatmap (#315 H5 / PR)](checklist/open/315-analytics-patterns-tab-store-report-cards-net-hr.md) — Confirmed: 1/2
- [active] [🆕 NEW — per-dash drill-down (#650 PR A): tap a recent dash on the Money tab](checklist/open/650-per-dash-drill-down-pr-a-tap-a-recent-dash-on-the.md) — Confirmed: 1/2
- [active] [🆕 NEW — user corrections as events (#650 PR B): add a missed delivery, adjust a pay](checklist/open/650-user-corrections-as-events-pr-b-add-a-missed.md) — Confirmed: 1/2
- [active] [🆕 NEW — CSV data export (#319)](checklist/open/319-csv-data-export.md) — Confirmed: 1/2
- [active] [🆕 NEW — Analytics tile now opens the Money tab (#315 H1 / Money tab v1)](checklist/open/315-analytics-tile-now-opens-the-money-tab-h1-money.md) — Confirmed: 1/2
- [active] [🆕 NEW — Analytics Decisions tab: offer funnel + value-of-saying-no + score-vs-outcome…](checklist/open/315-analytics-decisions-tab-offer-funnel-value-of.md) — Confirmed: 1/2
- [active] [🆕 NEW — Analytics Time tab: time split, deadhead, on-time gauge, mileage & tax (#315 H4)](checklist/open/315-analytics-time-tab-time-split-deadhead-on-time.md) — Confirmed: 1/2
- [active] [🆕 NEW — Analytics Money tab: earnings-by-day chart + header CSV export icon (#315 H6)](checklist/open/315-analytics-money-tab-earnings-by-day-chart-header.md) — Confirmed: 1/2

### HUD

- [active] [🆕 NEW — #867 (display side) — the bubble says WHICH dash it is showing, and you can…](checklist/open/867-display-side-the-bubble-says-which-dash-it-is.md) — Confirmed: 1/2
- [active] [🆕 NEW — bubble gas quick-edit is mode-adaptive: refresh (AUTO) vs. Resume auto (MANUAL)…](checklist/open/722-bubble-gas-quick-edit-is-mode-adaptive-refresh.md) — Confirmed: 1/2
- [active] [🆕 NEW — bubble post-dash card shows the TRUE last session + gas/vehicle quick actions…](checklist/open/693-bubble-post-dash-card-shows-the-true-last-session.md) — Confirmed: 1/2
- [active] [Offer card surfaces Shop & Deliver: item count in the hero row + a SHOP badge (#461 a/b)](checklist/open/461-offer-card-surfaces-shop-deliver-item-count-in-the.md) — Confirmed: 1/2
- [active] [Pickup/Delivery task cards redesigned to the co-hero design (#460/#324)](checklist/open/460-pickup-delivery-task-cards-redesigned-to-the-co.md) — Confirmed: 1/2
- [active] [Bubble keeps showing the last dash after it ends / after a crash (#459)](checklist/open/459-bubble-keeps-showing-the-last-dash-after-it-ends.md) — Confirmed: 1/2
- [active] [Pickup/Delivery card deadline reads cleanly — no double "by" (#460)](checklist/open/460-pickup-delivery-card-deadline-reads-cleanly-no.md) — Confirmed: 1/2
- [active] ["Saved: $X" bubble shows the dollar sign now (#456)](checklist/open/456-saved-x-bubble-shows-the-dollar-sign-now.md) — Confirmed: 1/2
- [active] [Bubble HUD no longer crashes on arrival-bearing dropoffs (#297)](checklist/open/297-bubble-hud-no-longer-crashes-on-arrival-bearing.md) — Confirmed: 1/2
- [active] [Shop & Deliver items/min (#276, merged 2026-06-02)](checklist/open/276-shop-deliver-items-min-merged-2026-06-02.md) — Confirmed: 1/2
- [active] [End-of-dash summary attribution (#279)](checklist/open/279-end-of-dash-summary-attribution.md) — Confirmed: 1/2

### census

No items in this age/counter group.

### other

- [active] [🆕 NEW — #991 (P0) — the spoken offer verdict went silent and stayed silent](checklist/open/991-p0-the-spoken-offer-verdict-went-silent-and-stayed.md) — Confirmed: 1/2
- [active] [🆕 NEW — #843 — prompted per-capability automation consent (no auto-grant)](checklist/open/843-prompted-per-capability-automation-consent-no-auto.md) — Confirmed: 1/2
- [active] [🆕 NEW — Principle-7 logging phase 1: INFO+ log is PII-safe, `SCREEN:` spam gone, real…](checklist/open/551-principle-7-logging-phase-1-info-log-is-pii-safe.md) — Confirmed: 1/2

## §5 Field-validated / retired

- [field-validated] [Shop & Deliver items/min reaches `total/total` at the end (#302)](checklist/closed/302-shop-deliver-items-min-reaches-total-total-at-the.md) — Confirmed: 2/2
- [field-validated] [🆕 NEW — the receipt's auto-expand tap re-finds the row by its labels, and every tap lands…](checklist/closed/1149-the-receipt-s-auto-expand-tap-re-finds-the-row-by.md) — Confirmed: 2/2
- [field-validated] [🆕 NEW — a live drop-off sheet never shows as a receipt (#935)](checklist/closed/935-a-live-drop-off-sheet-never-shows-as-a-receipt.md) — Confirmed: 2/2
- [field-validated] [🆕 NEW — a same-customer double-order job closes at its receipt; the next offer is its OWN…](checklist/closed/749-a-same-customer-double-order-job-closes-at-its.md) — Confirmed: 2/2
- [field-validated] [Same-store consecutive offers are two offers (#1069)](checklist/closed/1069-same-store-consecutive-offers-are-two-offers.md) — Confirmed: 2/2
- [field-validated] [Restaurant pickup wait records the real dwell (#1141)](checklist/closed/1141-restaurant-pickup-wait-records-the-real-dwell.md) — Confirmed: 2/2

### Superseded (dev-confirmed 2026-10-07 — the replacing redesign is named on each; the #657 earnings-freshness check is retained in §2)

- [superseded] [🆕 NEW — #977 — Home is now "Today" (redesign stage 4)](checklist/closed/977-home-is-now-today-redesign-stage-4.md) — Confirmed: 0/2
- [superseded] [🆕 NEW — the main dashboard is now a REVIEW surface, not a live bubble mirror (#657 / PR…](checklist/closed/657-the-main-dashboard-is-now-a-review-surface-not-a.md) — Confirmed: 0/2
- [superseded] [🆕 NEW — the home screen's top glance is now REAL "Today" totals from the read model (#314…](checklist/closed/314-the-home-screen-s-top-glance-is-now-real-today.md) — Confirmed: 0/2
- [superseded] [✨ NEW — the home screen now shows a live "This dash" glance + entry tiles (#320/#316)](checklist/closed/320-the-home-screen-now-shows-a-live-this-dash-glance.md) — Confirmed: 0/2
