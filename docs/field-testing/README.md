# DashBuddy Field Testing Log

Running log of observations made while actively dashing in the field, captured
in real time during sessions. Each session is its own entry, **newest first**.

This is a freeform capture log — a mix of bugs, open questions, meta
observations about platform UI/UX, design proposals, and verification TODOs
that were noticed during a session. The intent is to preserve raw context
*before* it gets distilled into focused work items. Items here are not yet
triaged; the developer triages to the project board manually using the
Android Studio plugin or `gh` CLI.

## Format

Each session entry has:

- **Date** — YYYY-MM-DD
- **Platform(s) tested** — DoorDash, Uber, etc.
- **Branch under test** — the git branch the build came from
- **Field conditions** — anything that affects interpretation (offers
  accepted vs declined, weather, multi-app testing, etc.)
- **Observations** — grouped by kind:
  - **Bugs** — reproducible defects
  - **Field UX context** — what the platform's UI actually looks like in
    the wild; helps explain why a matcher behaves the way it does
  - **Open questions / investigations** — things to look at back at the desk
  - **Meta / architecture** — broader concerns that aren't single-bug shaped
  - **Research / design** — speculative or strategic proposals
  - **Verification TODOs** — items the session itself produced ground-truth
    for, but which need cross-referencing against captured data

Item numbers are **session-local** (reset each session) and intended for
cross-referencing within a single session entry, not across sessions.

Each item also carries a **Status** line — added when the item is logged
and updated as it lands. Three shapes:

- `- **Status:** Open.` — not yet addressed.
- `- **Status:** Shipped in #NNN (YYYY-MM-DD).` — closed by a merged PR.
  Use `#NNN + #MMM` when multiple PRs were needed.
- `- **Status:** Wontfix — <one-sentence reason>.` — investigated, no
  change needed. Inline the reason so the log is self-explanatory.

For items with multiple sub-concerns at different statuses, use one
`Partially closed —` line and describe each sub-concern inline.

---

## Next field test — things to look for

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

- **🆕 NEW — #1033 — a collapsed delivery receipt now gets 8 s to be expanded, and an expansion
  that lands too late re-prices the delivery anyway.** DoorDash's post-delivery receipt renders
  COLLAPSED (a total, no breakdown). A completion committed off that shape has no itemization, so
  the drop is priced by the #691 `OFFER_PAY` **estimate** rather than the receipt. On 08-23 the
  expansion landed 3.9 s after the collapsed frame — 1.3 s past the old 2.5 s retire grace, so the
  delivery was recorded on the estimate with the real receipt on screen a second later. **Layer 1**
  widens the retire window to 8 s for a COLLAPSED receipt only (an expanded one still commits in
  2.5 s, and an expanded frame tightens a widened deadline the moment it arrives). **Layer 2**
  appends a `DELIVERY_RECEIPT_REPRICE` when the expansion still arrives after the completion.
  **On-dash:** on a couple of deliveries, let the receipt sit collapsed and expand it deliberately
  LATE (say 5–10 s after it appears); on others, expand it immediately. Two things to watch: the
  "Saved: $X" bubble should still fire at the same moment it always has (it fires on the receipt
  frame, not the commit), and the next offer should not feel delayed. **Desk, after the pull:**
  1. `SELECT payBasis, COUNT(*) FROM delivery_records GROUP BY 1` — `DROP_SHARE` should now
     dominate where `OFFER_PAY` used to, on ordinary (non-shop) deliveries.
  2. The per-dash drill-down: a deliberately-late expansion should show
     **"re-priced from the receipt"** on that row (and `receiptRepricedAt` non-null in
     `delivery_records`); a normally-expanded one should show neither that nor "est. offer pay".
  3. `SELECT eventType, COUNT(*) FROM app_events WHERE eventType='DELIVERY_RECEIPT_REPRICE'` —
     one event per drop per DISTINCT itemization decision (a receipt that legitimately changes
     — a tip landing late — decides again, so X→Y→X is three per drop, each under its own
     revision key); the drill-down row follows the LATEST, and a redundant event whose values
     the row already holds is a projector no-op counted at DEBUG. For a stacked job the Σ of the
     payload `dropRealizedPay` must equal the payload `totalPay` to the cent.
  4. No `DELIVERY_RECEIPT_REPRICE: no delivery row` WARN in the log (that would mean the event is
     firing for a drop whose completion was never folded).
  - Confirmed: 0/2 (desk 09-06, the 09-05 dashes on `38036999` — #1033 NOT on that build: its PREMISE confirmed 3/3 — every 8.95.6 receipt auto-expanded 41 / 172 / 463 ms AFTER the 2.5 s `GRACE_COMMIT`, at a tight 2.56–3.00 s collapsed→expanded interval, so the old grace is ALWAYS late on 8.95.6, not occasionally; all three drops folded `RECEIPT_TOTAL` with no tip/base and empty `payoutStoreForms`. First real test is the 09-06 dash on `aabb56d0`.)

- **🆕 NEW — #1063 — an offer is recognized from its FIRST frame, before the Decline
  button inflates.** DoorDash lands the offer card in two beats: the collar animation drops the
  sheet (store leg, pay, distance, deadline, live Accept + countdown) and the decline control
  inflates a beat later. `offer_popup` required a literal `Decline` node, so that first beat fell
  to UNKNOWN — six real offers in the 09-05 slice, three of which produced no settled sibling at
  all (one was ACCEPTED off a card the app had never presented). The Decline conjunct is now
  `Decline OR the card's own accept_button id`; the #595 store-leg guard is untouched.
  **On-dash:** every offer must produce exactly ONE bubble/voice — watch for a
  "(offer replaced)" flicker or a doubled narration right at presentation, which would mean the
  early frame is being read as a DIFFERENT offer instead of the same one. Offers should also feel
  like they arrive slightly sooner (the app now sees the card on the animation frame).
  **Desk, after the pull:**
  1. `OFFER_PRESENTED` count in `app_events` == the number of offers you actually saw — no
     offer missing, none doubled.
  2. No `OFFER_TIMEOUT` carrying `Replaced by new offer` within ~2 s of an `OFFER_PRESENTED`
     (that is the #830 replace-instead-of-enrich failure this change could cause).
  3. No offer-shaped frame left in `captures/**/UNKNOWN/`: `grep -rl 'accept_decline_footer_container' captures/**/UNKNOWN/`
     should return only store-leg-LESS half-renders (the #595 family), never a card with a real
     `display_name` store row.
  - Confirmed: 0/2

- **🆕 NEW — #1059 — the Persona verification flow, the Red Card wallet and the passport
  scanner are now blocked at the matcher layer.** Three of the dasher's OWN surfaces were
  reaching UNKNOWN capture: the embedded Persona selfie / ID-verification camera (12 envelopes
  on 08-27), the "Your Red Card" wallet screen (3), and the PASSPORT variant of the ID-scan
  camera (1 — the two existing scanner anchors were both driver's-licence specific). None of
  them leaked customer PII, but the Pledge blocks a document-image capture surface as a CLASS,
  and the Red Card screen is the dasher's own payment-card management surface. Each now has an
  id-anchored `sensitive.known` arm (locale-immune), plus four marker keywords as the
  rules-independent backstop.
  **On-dash:** nothing to watch while driving, and nothing should CHANGE — the block is
  capture-side. If you happen to hit an ID-verification prompt or open the Red Card screen,
  the app must behave exactly as before (no bubble, no narration, no state move).
  **Desk, after the pull:**
  1. `grep -rlE 'persona_container|personaComposeView|activate_physical_card_button|id_type_selector' captures/**/UNKNOWN/`
     must return **nothing** — a hit means the frame still fell to UNKNOWN capture.
  2. The same grep across the whole `captures/` tree should return nothing either: a sensitive
     frame is dropped at the content gate BEFORE any envelope is written, so it must not appear
     in a recognized folder either.
  3. `grep 'Sensitive gate: dropped' app.log` (DEBUG) — `sensitive.selfie_verification` /
     `sensitive.red_card` / `sensitive.id_verification` lines are the positive signal the new
     arms fired on the device build.
  4. The over-match check: an ordinary shopping-order pickup that says "pay with your Red Card"
     must still classify `pickup_*` normally — grep `app.log` for a `sensitive.red_card` drop
     with no wallet screen visit around it.
  - Confirmed: 0/2

- **🆕 NEW — #1058 — the two dropoff sheets that were shipping addresses and door codes to
  UNKNOWN captures are now recognized and redacted.** Leak A is the ALCOHOL variant of the drop-off
  arrival card (the one that asks you to scan an ID and collect a signature): it renders no
  "Delivery for" line, so no rule matched it and the unit number + the customer's instruction body
  went to disk raw. Leak B is DoorDash 8.93.7's id-less "Leave it at the door" workflow sheet
  (Call · Message · Directions · Continue), which shipped the street line, city/ST/ZIP, the unit,
  the quoted customer note (with a code in it) and a bare 3-digit code. Both now have a rule with a
  full redact; the two node ids also joined the UNKNOWN-path id backstop.
  **On-dash:** nothing to watch — the fix is capture-side only. The one thing to NOTICE is that
  nothing changed in behaviour: an alcohol drop-off and an ordinary "leave it at the door" drop-off
  must still narrate, bubble and complete exactly as before (the new sheet rule declares no flow on
  purpose).
  **Desk, after the pull:**
  1. `grep -rl 'drop_off_workflow_host_fragment\|alcohol_dropoff_ic_scan' captures/**/UNKNOWN/`
     must return **nothing** — a hit means the frame still fell UNKNOWN and the rule missed it.
  2. In the recognized folders for those two surfaces
     (`captures/doordash/accessibility.window/dropoff_pre_arrival/` and
     `.../dropoff_workflow_sheet/`), every address line, unit/`Apt` value, instruction body, quoted
     note and bare code must read `[redacted]` or `[redacted:<4hex>]` — the codes, unit numbers,
     ZIPs and notes must be **plain** `[redacted]` (no hex), the street lines and any bare
     first-name + last-initial keep the hex.
  3. `grep -c dropoff_workflow_sheet app.log` — a non-zero count on a dash where you took a
     leave-at-door drop is the positive signal that the new rule is live on the device build.
  - Confirmed: 0/2

- **🆕 NEW — #1057/#918 — every odometer fix is now gated (the +905-mile fault, and the
  parked-at-the-desk phantom miles).** The gate rejects a fix whose accuracy is worse than 50 m,
  whose implied speed tops ~150 mph, or (with no timestamps) that jumps more than 2 km; a rejected
  or jitter-ignored fix never becomes the reference, so slow creep still accumulates.
  **On-dash:** nothing to watch while driving. **After a dash:** open the dash's drill-down — no
  single dash may gain implausible miles, and one delivery reading hundreds of miles is the fault
  back. Also worth a look **before you leave the house**: start a dash, sit still for a few
  minutes, and session miles must stay at 0.00 (that is the #918 half).
  **Desk, three greps:**
  1. `grep 'Odometer fix rejected' app.log` — a WARN line here is the gate **working**, not a bug.
     Each carries `delta`/`dt`/`impliedSpeed`/`accuracy` and a reason
     (`INVALID_FIX`/`POOR_ACCURACY`/`IMPLAUSIBLE_SPEED`/`IMPLAUSIBLE_JUMP`/`NON_MONOTONIC_TIME`) —
     no coordinates, by design. A `IMPLAUSIBLE_SPEED` reject with a huge delta IS the 09-03 fault
     being caught. **The WARNs are episode-gated: a WARN opens a rejection streak, an INFO
     (`Odometer reception recovered after N rejected fixes over M s`) closes it — so a FLOOD of
     WARNs is the bug, not the gate.** Inside a streak only every 100th repeat is re-logged
     (`Odometer rejection streak: …`), but a rejection with a *different* reason still gets its own
     line. What would be a **failure**: a burst of rejects during ordinary highway
     driving (the bound is too tight) or during a normal parking-lot crawl; a `NON_MONOTONIC_TIME`
     reject at all (elapsed time now comes off the monotonic clock, so it means a real ordering
     fault rather than a clock correction); or any `INVALID_FIX` line (the fused provider should
     never hand us a malformed fix — worth capturing if one appears).
  2. `grep 'Odometer fixes:' app.log` — one DEBUG summary per 100 judged fixes
     (`accepted=… ignored=… rejected=… +N m`). On a normal drive `rejected` should be ≈ 0 and
     `accepted` should dominate; a large `ignored` count while parked is expected and correct.
  3. The largest per-leg `metadata.odometer` delta in the pull must be plausible for that leg, and
     no `delivery_records` row may carry a `realizedMiles` far beyond its own job's drive (the
     09-03 fault was +905.37 mi in 18.4 min, freezing a −$302.73 net on row 1801).
  **Known, deliberate residual:** the gate is forward-looking only. The existing +905 mi already
  baked into the cumulative total (and row 1801's frozen economics) is NOT repaired by this —
  lifetime/IRS mileage stays 905 mi high until the dev picks a repair shape (open on #1057).
  - Confirmed: 1/2 (desk 09-06, the 09-05 dashes: 2,310 fixes judged, 190 ignored (the 5 m jitter floor), **0 rejected** over ~4 h — no WARN burst on ordinary driving; per-session spans 11.7 / 17.6 / 11.7 / 16.2 mi, Σ realizedMiles never exceeds the span; the 09-03 +911 mi baseline is still in the cumulative total by design)

- **🆕 NEW — #1029 — the money reads are re-anchored on DoorDash 8.93.7 (and the $799 tip is
  gone).** Two things to watch, one while driving and one at the desk.
  - **On-dash, glanceable:** the bubble's **"This dash"** figure. It should track the DoorDash
    earnings pill and land on the real running total within a couple of frames of the wheel
    settling. What would be a **failure**: it shows a number that never appeared on the pill (a
    mid-spin value like `$470.00` on a $16 dash), or it shows your **weekly** total instead of the
    dash's. Both are worse than it showing nothing, so report either immediately. A figure that
    lands ~3 s after the wheel stops is EXPECTED — that is the settle gate (a read commits only
    once it has stood unchallenged **on that screen** for the settle window). Two more EXPECTED
    behaviours, not bugs: if you leave the waiting-for-offer screen mid-spin (an offer pops, you
    tap into something), the **old figure stands until you come back** — the half-read is thrown
    away rather than guessed at; and a `$0.00` on the pill while it loads never wipes a total you
    already had. **Switching to the other platform** mid-spin is the same
    thing (#1052): the half-read is dropped and coming back re-reads it from scratch, so a figure
    appearing right after an Uber screen is a failure to report. **Pausing or going offline
    mid-spin behaves differently on purpose** (#1052 round 3): the figure is FROZEN, not thrown
    away — nothing may land while you are paused, and about **3 s after you resume** the figure
    lands (the window restarts on the resume). So: a number appearing DURING a pause is a failure;
    a number that appears a few seconds AFTER you resume is the fix working. And if you pause
    mid-spin, resume, and the pill's figure NEVER lands, report that too — that is the stranding
    round 3 fixed.
  - **On-dash, after a delivery:** the receipt sheet ("This offer", with the pay breakdown). The
    bubble's "Saved: $X" should quote the receipt's real total, and expanding the breakdown should
    NOT produce anything wild.
  - **Desk:** `SELECT payBasis, realizedPay, tip, cashTip FROM delivery_records` for the dash —
    a **single-drop** delivery, and any drop whose receipt you EXPANDED, must come back on the
    **`DROP_SHARE`** basis with the receipt's real tip, not `OFFER_PAY`. (A **stacked** job still
    even-splits its receipt across the drops until #1051 lands the per-store tip read, so equal
    shares on a stack are expected, not a miss.) And **no row anywhere may carry `799`** (that was
    a DoorDash type code being read as a $799 tip). Then `grep -o 'parseShortfall{[^}]*}' app.log | tail -1` and
    `grep 'parse shortfall' app.log`: **`delivery_summary_expanded` / `_collapsed` must no longer
    appear for `required [totalPay]`**, and `waiting_for_offer` should have dropped off too.
  - Confirmed: 1/2, HALF (desk 09-06: totals parsed on **8.95.6** (the app moved; the corpus is 8.93.7-era), exactly 3 `runningEarnings` commits, all correct, no `799`, no mid-spin figure, the `$0.00` placeholder guard exercised 4×, the receipt bubbles quote the real receipt. NOT yet exercised: pause/resume mid-spin (#1052), and `parsedPay` was null on 3/3 receipts because of the #1033 timing above — so `DROP_SHARE`/tips are still unproven)
    - desk 09-05: NOT TESTABLE — device ran the pre-#1044 build.

- **🆕 NEW — #1036 — "matched, but parsed nothing" is now loud.** Purely a **desk** item — nothing
  to watch for while driving; just dash normally and check the log afterwards. A rule that matches a
  frame while its declared parse yields nothing usable now WARNs once per rule per process and rides
  the periodic `PipelineStats` summary. Two triggers: **every** evidence field unresolved (an empty
  `each`/`findAll` list counts as unresolved), or a **shape-required** field null while others
  parsed. Check: `grep 'parse shortfall' app.log` and
  `grep -o 'parseShortfall{[^}]*}' app.log | tail -1`.
  - **Should trip:** `timeline` (`all 5 null`) — still un-re-anchored. (The rest of the original
    #1029 list — `delivery_summary_expanded`/`_collapsed` `required [totalPay]` and
    `waiting_for_offer` `all 2 null` — was the rot #1029 has since FIXED. They are now in the
    "must not appear" column; see the #1029 item above.)
  - **Benign baseline (not a find):** `dash_along_the_way` (no spot deadline shown), `idle_map`
    (neither Time-mode chip on), `set_dash_end_time` — each has ONE evidence field that is
    legitimately optional.
  - **`dash_summary` must NOT appear** — #1032 re-anchored it. If it does, that fix didn't take on
    the installed build. Same for `delivery_summary_expanded`/`_collapsed` and `waiting_for_offer`
    after #1029.
  - **Any OTHER rule id is a new anchor-rot find** — capture the frame and file it. #1029 has since
    landed, so the `delivery_summary_*` pair and `waiting_for_offer` should now be OFF the list;
    their reappearance is a regression, not the known rot.
  - Confirmed: 1/2 (desk 09-06: `parseShortfall{…}` present on every summary; the only tripping rule was `waiting_for_offer` ×5 and those are BENIGN pre-render frames (a 17-node tree with no `earnings_pill`), not rot — add it to the benign baseline named in CLAUDE.md §1)
    - desk 09-05: NOT TESTABLE — device ran the pre-#1044 build.

- **🆕 NEW — #1032 — the dash-end summary sheet is recognized again (DoorDash 8.93.7).** End a dash
  and stay ON the summary sheet — the one headlined **Dash summary** with the big total, "Total
  online time" and "Offers accepted" — then tap **Done**. 8.93.7 re-rendered that sheet with no view
  ids, so it had been falling to UNKNOWN and the dash was ending with **no reported total at all**.
  How to tell it worked: open **Analytics → Money → the dash's drill-down** afterwards; it should
  show a real **Gross (reported)** matching the sheet's total (not delivered-pay fallback, not a
  `$0.00`/em-dash). Also worth a glance mid-dash: the **"This dash so far"** sheet (the one with
  *Continue dashing*) must NOT end your dash — if the HUD flips to offline / a dash summary the
  moment you peek at it, that is the bug and it is serious. And once, mid-dash, browse **Earnings
  history → a past dash**: it must NOT end the live dash either — that surface is uncaptured, so it
  could carry the same labels the new text-only anchor keys on and we have no fixture to prove it
  doesn't.
  Desk checks on the next pull: `session_records.endSource = 'summary_screen'` with a non-null
  `reportedEarnings` for that dash, and **no** `Dash summary`-shaped frame left in
  `captures/.../UNKNOWN/` (`grep -l 'Dash summary' captures/**/UNKNOWN/*.json` → empty).
  - Confirmed: 1/2 (desk 09-06: `dash_summary` classified 3×; all three dashes `endSource=summary_screen` with real totals $34.60 / $22.25 / $48.38; zero `Dash summary` frames in UNKNOWN)
    - desk 09-05: NOT TESTABLE — device ran the pre-#1044 build.

- **🆕 NEW — #1034 — a negative dollar reads `-$12`, never `$-12`.** `Formats.money`/`money0`/
  `money3` put the sign before the `$` now, so this shows up anywhere a figure can go negative.
  Two places to glance at: (a) **the bubble HUD's `$/hr` hero** on a bad offer, or on an overdue
  task's running-at line — when the verdict is "drop it" and the hero is red, it should read
  `-$12/hr`; (b) **the Money card headline** on a losing window (Analytics → Money):
  `$X came in. -$65.94 went to the car.` A stray `$-` anywhere is the bug back. Also worth a
  glance: a **sub-cent** negative must read a plain `$0.00` with **no** minus in front — but a
  real negative that just rounds small still keeps its sign (`-$0/hr` is correct, not a defect).
  Desk-checkable in part: `grep -c '\$-' shareable.log` over the pull should be 0.
  - Confirmed: 0/2
    - desk 09-05: NOT TESTABLE — device ran the pre-#1044 build.

- **🆕 NEW — #1031 + #1039 — the redact pair (two Pledge leaks, envelope-only).** **Nothing visible
  changes on-dash** — this is capture-envelope masking, so the only in-app tell is a negative one:
  recognition must be unchanged (pickup issue menus and dropoff cards still recognize normally, no
  new UNKNOWN family on the pickup-issue or dropoff surfaces). Both are **desk-gated on the next
  pull**: (a) #1031 —
  `grep -rhoE '"text": "For [^"]*"' captures/*/accessibility.window/{pickup,dropoff}_issue_menu/ …/dropoff_help_menu/`
  returns only `For [redacted:<4hex>]` (no name, no ` • <store>` tail); (b) #1039 —
  `grep -rhoE '"text": "[^"]*(Unit|Apt|Bldg|#) ?[0-9]+[^"]*"' captures/` over **every**
  `dropoff_*` / `delivery_summary_*` / `alcohol_*` surface folder plus `navigation_generic`
  (round 2 blanket-declared the subpremise pair on all 24 dropoff-section rules + the nav
  catch-all, so any of them can be the rule that wins an address-block frame — checking only
  the four originally-patched surfaces would re-create the gap the review found) returns
  **nothing raw**: every subpremise value reads plain `[redacted]` with **no** `:<4hex>`
  suffix, whichever spelling the app rendered. Expect the bare `Apt/Suite` LABEL to read
  `[redacted]` too — that is documented, accepted collateral of the fused entry, not a
  regression. A raw `Unit <n>` (or any other spelling) surviving on a *recognized* dropoff
  envelope means the label-sibling anchor missed and the issue reopens.
  Residual to watch, not a fix: **uber** got `plainMask` on its four id-less digit-shape
  entries (round 2) but no label-sibling anchor — if an uber trip surface renders the
  `<label><value>` split, it is a separate finding, file it.
  - Confirmed: 0/2
- **🆕 NEW — #1030 — the `early_offline` fake $0 report is gone.** Dash and go offline **without**
  the dash-summary screen (the normal `early_offline` shape), then check: (a) Home / the Money tab's
  `$X came in.` headline shows the dash's **delivered pay**, not `$0.00` — and the "where it went"
  clause is not a negative number; (b) the **"attributed exceeds reported"** severe review flag is
  gone for the healed history (the v11 refold runs once on the first launch of this build — give it a
  moment before reading); (c) a dash's drill-down shows **Gross (reported)** as `—`, never `$0.00`,
  for a receipt-less dash. A dash that DID end on the summary screen must still show its real
  reported total. Also glance at the bubble HUD's last-dash figure after a summary-less dash — it
  should read `—`, not `$0.00`. Desk check on the next pull:
  `SELECT sessionId, reportedEarnings FROM session_records WHERE endSource='early_offline';` → **no
  row reads exactly 0.0** after the v11 refold (a positive early_offline total is kept by design;
  41 of 42 rows were a hard `0.0` before the fix).
  - Confirmed: 1/2 (desk 09-06: projectorVersion 11; 49/50 `early_offline` rows `reportedEarnings` NULL, zero hard-zeros; the morning 07:45 early_offline session shows NULL, not $0)
    - desk 09-05: NOT TESTABLE — device ran the pre-#1044 build.
- **🆕 NEW — #1024 part 1 (PR #1025) — the Playbook destination.** Open Home → **Playbook** tile.
  Check: (a) *This week's plan* shows `Xh worked in your windows · $Y kept of the $Z you planned
  for` and each window row flips to **Done** after its end hour (leave the screen open across an
  hour boundary — it should re-render without leaving); (b) the heatmap outlines exactly the hours
  the saved plan picked, and the Rate/Hours toggle still works; (c) the store leaderboard matches
  what the old Patterns tab showed; (d) Analytics now has three tabs. With **no** plan saved the
  card says so and offers *Build a plan →*, never an empty plan. The screen's footer disclosure
  includes the plan-projection line (lifetime, not a guarantee).
  - Confirmed: 0/2
- **🆕 NEW — #1024 part 2 (PR #1026) — Money tab: one number, one place.** Open Analytics on a
  dashed week. Working: kept appears exactly once (the big number), gross exactly once (`$X came
  in.`), deliveries/miles only in the grey facts line; five bordered cards at most (money story,
  rates+day chart, by platform, needs a look, recent sessions) then ONE `How these numbers work`
  row at the bottom of every tab. Broken: a `TOP STORES` card, a second disclosure mid-scroll, or
  `Stayed with you 63%` in the legend (the percentage fallback — the note went null instead of
  blank). Rates survive a single-day/Lifetime window (`—` where a denominator is missing, chart
  simply absent). Two-platform week (needs an Uber dash): one row per platform with chip, bar,
  `$X kept`, count.
  - Confirmed: 0/2
- **🆕 NEW — #1024 part 3 (PR #1027) — Home is four blocks.** One **Today** card (kept big → net/hr
  online · drops · miles · On dash/Online → plan strip), one **This week** card (net + delta +
  sparkline + `Recap →`, the plan row if one is saved, `NEEDS A LOOK` if the week flagged), one row
  of four equal-height tiles, one footer. Working if: net appears once per scope; `Recap →` opens
  the hub already on THIS week; **On dash ticks live while dashing** (starts at `0s`, never `—`,
  on a fresh dash) and reads the settled `Online` total otherwise; the plan strip re-dims across an
  hour boundary; a day with no dash shows `—`, never `$0.00`/`0s`; Settings is reachable even from
  the permissions-missing and first-run states (the small Settings link under those cards).
  - Confirmed: 0/2
- **🆕 NEW — #985 (PR #1014) — the Timeline order-detail sheet is recognized and masked.** Open
  the timeline mid-dash and tap a task row (both a pickup row and a dropoff row). Working = the
  frame no longer lands in `captures/…/UNKNOWN/`; it lands under `timeline_task_detail/`, and
  inside it the street line, the city/ST/ZIP line, any customer note and any bare entry code are
  `[redacted…]` while `Copy address` and the store name are intact. Broken = an UNKNOWN envelope
  with a raw address, or a recognized envelope with any raw address line. Also confirm nothing
  about the dash lifecycle changes when the sheet is opened (no phantom pause/resume, no
  offer/task churn) — the rule is lifecycle-neutral by test. (#985 itself stays OPEN for the
  capture-gated "Switch to pick up at <store>" sheet re-homed from #806.)
  - Confirmed: 0/2
- **🆕 NEW — #996/#997 (PR #1012) — per-offer pay attribution on a receipt-less dash.** On any
  dash with no post-drop receipt (an out-of-zone "Dash Along the Way" start, or a shop order),
  watch three shapes. (a) **Multi-accept job at DIFFERENT stores:** each drop's drill-down shows
  its own store's offer pay as the "est. offer pay", not all drops showing one averaged number.
  (b) **Multi-accept job at the SAME store:** those drops show the same sub-pooled number — that
  is correct, not the old bug. (c) **Same-customer multi-order job:** the single physical drop
  carries the FULL quoted offer pay and the Money tab's unattributed remainder for that dash is
  $0.00 — pre-fix it was exactly half the quote. **Desk:**
  `SELECT d.jobId, COUNT(*), SUM(d.realizedPay) FROM delivery_records d WHERE d.payBasis='OFFER_PAY' GROUP BY d.jobId;`
  — each job's Σ ≤ its accepted offers' Σ `payAmount`, equal whenever every offer's store matched
  a drop. `DELIVERY_COMPLETED` payloads now carry `offerPayAttribution`
  (`PER_OFFER_STORE`/`SUB_POOLED_STORE`/`STAMP_FALLBACK`/`CONSOLIDATED_CUSTOMER`/`JOB_POOLED`/
  `INLINE_POOLED`). Log greps: `#997 offer-pay attribution degraded to` (DEBUG, per-degrade with
  counts — a `JOB_POOLED` on a job whose drops all had stores means a store failed to reconcile);
  `#997 offer pay unattributed at close` (WARN — accepted money that found no drop);
  the `#691` WARN now reports `denominator=N of M owed` + `ownOfferPay=present|null`.
  - Confirmed: 1/2 (desk 08-24: shape (a) confirmed — 5/5 week drops `PER_OFFER_STORE`, the
    Michaels+Petsmart job split $19.70 → $9.85/$9.85 across its two stores, Σ stamped = Σ quotes
    to the cent, zero degrades; the slice's one `STAMP_FALLBACK` (08-14, store-less drop) also
    reconciled exactly. Shapes (b) sub-pooled and (c) consolidated-customer still unseen.)
- **🆕 NEW — #1000 (PR #1013) — a blown-through pickup still links its offer to the job.** Watch
  for a dash where a pickup is arrived-but-never-confirmed followed by a normal delivery
  completion. **Desk:** `SELECT linkedJobId, storeKey FROM offer_records WHERE offerHash =
  '<the accepted offer's hash>'` — non-null `linkedJobId` with a **null** `storeKey` is correct
  (the fail-null residual: no store leaderboard entry, dwell sample, or `milesToStore` from that
  job, by design). The PROJECTOR_VERSION 10 refold should have healed the 08-08 Zaxbys accept on
  first launch — check it shows linked in the next pull.
  - Confirmed: 1/2 (desk 08-24: the v10 refold healed the 08-08 Zaxbys accept on first launch —
    `anchorless job link: jobId=job-doordash-…-322 offerSeq=1521` in the 08-09 23:40 refold, and
    the pulled DB shows that offer with `linkedJobId` set and `storeKey` NULL, the correct
    fail-null shape. A LIVE blown-through pickup on-dash is the remaining half.)
- **🆕 NEW — #992 / #993 / #994 / #995 / #920 — the Pledge redact batch: five recognized surfaces
  that were still shipping raw customer PII.** All five were found in the 2026-08-09 desk analysis
  and are rule-layer fixes (envelope masking only — nothing about recognition, parsing, state or
  economics moves). What now masks: `pickup_wait_survey`'s `customer_name` node (#992 — the rule had
  no `redact` block at all); `dropoff_navigation`'s `arriving_at_title` banner, which restates the
  customer's full street address when DoorDash's arrival banner inflates over the dropoff sheet
  (#993); the timeline's fourth conjugation `Return <name> to <store>`, which a **return order**
  renders and which matched none of the three enumerated prefixes (#994); the whole receipt-scan
  camera surface, which had no rule at all and persisted the name twice per frame on id-less nodes
  (#995 — now recognized as `pickup_receipt_scan`, recognize-only, no flow); and `shopping_item`'s
  customer-authored **Customer Notes** free text (#920, plain-masked — it can carry a gate code).
  **What to watch (on-dash):** nothing should look different — these surfaces render exactly as
  before and the HUD/verdicts are untouched. The one visible change is that the receipt-scan
  screen now classifies as a known screen instead of falling to UNKNOWN. If a **return order**
  comes up, note it: that flow is still unmodelled, and #994 only closes its capture leak.
  **Desk (the real gate) — on the next pull, grep the new captures:**
  `grep -rl "arriving_at_title" captures/.../dropoff_navigation/` then confirm every hit reads
  `[redacted:<4hex>]`, never a street; `grep -rho '"text": "Return [^"]*"' captures/` must return
  only `Return [redacted:…]`; `grep -rho 'Focus on [^"]*' captures/` likewise; the
  `pickup_wait_survey` folder's `customer_name` nodes must all be `[redacted:…]`; and
  `grep -rho 'Customer Notes[^"]*' captures/` must show only `Customer Notes: [redacted]` plus the
  bare `Customer Notes` label. Also confirm a `pickup_receipt_scan/` folder now exists in the pull
  (frames that used to land in `UNKNOWN/`). **Any raw value surviving any of those greps is the
  item failing.**
  - Confirmed: 0/2

- **🆕 NEW — #991 (P0) — the spoken offer verdict went silent and stayed silent.** From 08-06
  16:28 onward every single `speak()` call returned `-1` (15 of 15 across three dashes; 11 of 11
  had succeeded on 08-01/08-02), each one logging `WARN/Tts: speak returned -1 — abandoning audio
  focus`. Offers were still evaluated, carded and notified the whole time — **only the voice
  died**, with no user-visible signal that it had. The app process had been alive for 8 days at
  that point and the TTS engine was only ever initialized once, at install.
  **FIXED — PR for #991 (needs field validation).** The handler now rebuilds the engine instead of
  latching dead: a failed `speak()` tears the `TextToSpeech` down and constructs a new one
  immediately (the same #428-B language wiring is re-applied), further failures are gated by a
  linear 30 s → 60 s → … → 5 min backoff, and after 3 consecutive lost utterances a notification
  appears on the shared **App notices** channel saying the voice stopped and that reopening the app
  restores it. A successful utterance resets the whole ladder; the notice fires at most once per
  process.
  **What to watch:** if the offer read goes quiet mid-dash, it should come back **by itself within
  roughly a minute** — the next offer or two after the silent one should speak again. If the engine
  is genuinely dead (e.g. the TTS package is mid-update), you should instead get the "DashBuddy has
  stopped reading offers aloud" notification rather than open-ended silence; force-stopping and
  reopening should then restore it. Note whether the notice appeared, and whether the voice
  self-recovered without any intervention. **Desk:** `grep 'Tts' app.log` — the fix working looks
  like `WARN … speak returned -1` immediately followed by `WARN … re-initializing the engine` and
  then `INFO Tts: engine re-initialized after failure`; an unrecoverable engine looks like
  `WARN … speech still failing after 3 consecutive losses — notifying the dasher`. A `speak
  returned -1` run with NO re-init line after it means the fix did not engage.
  - Confirmed: 1/2
    - (desk 09-05: the recovery seam fired live 08-30 15:44:52 — one `speak()` loss →
      rebuild → engine re-initialized in 417 ms; 34/35 utterances spoke.)
    - (desk 08-24: voice healthy — 28/28 `speak()` succeeded across four dashes — but the
      engine never failed, so the ladder was unexercised; no-regression, not a confirmation.)

- **🆕 NEW — #967 / PR #968 — ratings stamp during an Offline browse (the early-return fix).**
  The pre-fix bug: `updateLifecycle`'s Offline early-return sat ABOVE the opportunistic ratings
  stamp, so browsing the Ratings hub while DoorDash mode = Offline silently produced NO fresh
  `ratings.capturedAt` stamp — opportunistic capture only worked mid-dash. Fielded on the very
  build that predates the fix (desk 07-31, the 07-30 dash: master @ 3bff50dd browsed Ratings
  14:51–14:52 while Offline and got no fresh stamp, corroborating #967 independently of the fix).
  **What to watch (next install, once #968 is on the phone):** browse DashBuddy's Ratings screen
  while DoorDash mode is Offline (not dashing) — the ratings figures (points/tier/acceptance rate)
  should populate immediately, not stay blank/stale until the next Online dash. **Desk:** in
  `app_state_snapshots`, `ratings.capturedAt` should advance on an Offline-mode browse, not only on
  an Online one.
  - Confirmed: 1/2 (desk 08-09, the 08-01→08-08 window: DESK HALF PASS — first pull whose device
    build actually carries #968, and `ratings.capturedAt` advanced on five separate Offline
    browses: 08-07 20:31 plus 08-08 10:40 / 10:41 / 15:09 / 15:10, every one stamped while the
    mode snapshot read `[DoorDash:Offline]` (the two same-minute pairs are two browses each).
    Pre-#968 that combination produced no stamp at all, so this is the fix working. The dev-eyes
    half — the Ratings screen visibly populating during an Offline browse instead of reading
    blank/stale — still wants a look on the phone.)

- **🆕 NEW — #983 — Time tab: the two hourly rates, your typical online hour, gap stats
  (redesign stage 7/7 — the epic's last build).** Analytics → **Time**, after a dash or two.
  Four things to check.
  (a) **NET PER HOUR** shows two tiles: *While working* and *Whole shift*, each with its own
  duration underneath. **While working must never be lower than whole shift** — same money over a
  smaller denominator. If they are identical, read the line under them: it should say no gap was
  measured in this window (which is itself a finding — see (c)). The sentence between them explains
  the difference; the last line must still say net is frozen at accept-time costs.
  (b) **YOUR TYPICAL ONLINE HOUR** is a 3-segment bar (driving & other / at stops / waiting) whose
  three chunks add up to one hour. Sanity-check it against the dash you just did: if you spent most
  of an hour parked at a Walmart, *at stops* should be the fat segment. Under it, a dollar line —
  `Waiting cost you about $X in this window` — and a coverage line saying how many of your stops
  were timed. **The coverage line is the one to watch**: if it says "no stop recorded an arrival",
  the arrival stamps aren't landing and the segment is meaningless (report it).
  (c) **GAPS BETWEEN JOBS** — typical / 9-in-10-under / longest, plus `N gaps measured across M
  drops`. Compare *typical* against your gut for that dash. Two failure shapes to catch: a gap that
  looks like it spans **two different dashes** (impossible by design — the fold refuses it, so
  seeing one means the session ids are wrong), and `0 gaps measured` on a dash where you clearly
  waited between orders (means accepts or completions aren't being recorded in-session).
  (d) **Page the window** (‹ ›) and confirm every figure on the tab moves together — no tile left
  quoting last week's number under this week's label.
  - Confirmed: 0/2 (desk 08-09, the 08-01→08-08 window: the tab itself is a UI check no pull can
    answer, but the **gap fold underneath (c) was traced against the log** and behaves as designed:
    4 of the 5 dashes measure coherently — 7 gaps spanning 0.6–2.7 min, 5 dash-final drops
    correctly excluded as tails, zero cross-dash and zero cross-day pairings. The fifth (08-01)
    measures **0 gaps despite a real ~8-minute wait**, and the mechanism is now understood: that
    dash's close-out sweep appended every `DELIVERY_COMPLETED` at `DASH_STOP`, so every completion
    sequences AFTER every accept and no completion has a "next accept in the same dash" to pair
    with. That is exactly the `0 gaps measured` failure shape named in (c) — but on a late-sweep
    dash it is **expected by mechanism, not a fold bug**, so read that line as "no accept followed
    a completion in sequence order", not "your accepts weren't recorded". Worth a dev-eyes pass to
    decide whether the card should say so.)

- **🆕 NEW — #981 — Weekly Plan + the Sunday notification (redesign stage 6).** A whole new screen,
  reached two ways: the Sunday-evening notification, and (once you save a plan) a row on Home.
  Six things to check.
  (a) **Getting there:** Home shows no plan row until you save one. Reach the screen the first time
  by waiting for the Sunday ~6 PM notification ("Your weekly plan") and tapping it — it must open
  the Weekly Plan screen, not the home screen. Its notification channel is its own: check
  Settings → Apps → DashBuddy → Notifications lists a **Weekly plan** channel separate from
  **App notices**, and muting one must not mute the other.
  (b) **The numbers are two, always:** the headline `N hours on your best windows ≈ $X kept` must
  ALWAYS be followed by the comparison line `The same N hours placed at random ≈ $Y — the plan is
  worth about $Z`. A headline with no comparison under it is the bug to report. If your record is
  too thin for a baseline it must say so in words instead of showing the headline alone. The line
  `From your own record, lifetime — a projection of what you have earned, not a promise…` must be
  on the card in every state.
  (c) **The windows are evidence-backed:** each row reads `Fridays 5–9 PM`, an evidence line
  `your Fridays: $26.40/hr over 6 Fridays`, and a projected figure. The count is **days, not
  dashes** — that's deliberate. One row carries a `BEST` chip. Below the picked rows, every weekday
  that did NOT make the plan appears dimmed with `NOT PICKED` and a reason
  ("only 2 Sundays on record", "your target filled up with better-paying windows", …). A weekday
  silently missing from both lists is a bug.
  (d) **Editing:** swipe a row left to drop it — the plan must re-fill from *different* hours, not
  put the same window back, and that weekday should now say "you dropped this one". The `‹ ›`
  arrows nudge a window an hour earlier/later, and the evidence line + projected figure must
  **change to the new hours' own numbers** (moving onto hours you've never worked must show
  "no record of these hours — this window projects nothing" and a `—`, never carry the old rate
  along). `Undo` reverses the last change only.
  (e) **Where the plan came from:** the lifetime heatmap (same grid as Analytics → Patterns) with
  the picked cells **outlined** in accent. Cross-check one outlined cell against the window list —
  they must agree.
  (f) **Save + the loop:** tap `Save this plan`. Go Home: a `Your week is planned · Nh across M
  windows · $X projected` row must now appear (and its numbers must match what you saved). The
  following Sunday's notification should lead with the grade —
  `Last week: 7.5 of 12 planned hours worked, $180.00 kept of $280.00 projected` — and the screen
  should show the same four numbers in a card at the top. Skipping the week entirely must read
  "You didn't work any of your N planned hours", not "0 of 12".
  Also check: the two locked rows at the bottom (**PLUS** area demand, **NEEDS A YEAR** year-over-
  year) are dimmed, dashed and carry **no numbers at all** — a fabricated figure there would be the
  worst bug on the screen.
  - Confirmed: 0/2

- **🆕 NEW — #979 — Patterns: ALL-TIME badge, heatmap Rate/Hours toggle, store leaderboard
  (redesign stage 5).** Open Analytics → Patterns. Three things to check.
  (a) **ALL TIME badge:** an `All time` chip sits at the very top with the caption "patterns need
  history — this tab always reads your whole record" — and paging the header `‹ ›` pager above must
  NOT move anything on this tab (Patterns always reads your whole history regardless of the
  selected window; that's the point of the badge).
  (b) **Heatmap Rate/Hours toggle:** a segmented control (Rate / Hours) sits above the grid. Rate is
  the pre-existing net-$/hr coloring (unchanged). Hours re-colors the SAME grid by how many hours
  you've spent online in each slot — an hour you've genuinely never worked should render as the same
  dim "no data" swatch as an under-covered Rate cell (there's no red/bad state in Hours mode, only
  more-or-less green). The legend and title/caption below the grid must switch with the toggle.
  (c) **Store leaderboard:** the store cards are now dense ranked rows — `#1`, `#2`, … at the left,
  name + location chip, a horizontal net bar, `N deliveries · <time> usual wait` underneath, the net
  figure and chevron at the right. Sort chips **By net / By wait / Recent** re-rank the rows (rank
  numbers should update; the bar lengths should NOT rescale when you switch chips — they stay
  relative to your #1 store by net). A store whose usual wait is notably worse than everywhere else
  you go should render that wait figure in amber/warn color; check it stays plain-colored on stores
  with only 1-2 total stores having a wait sample (thin data must never manufacture an outlier).
  Tapping a row must still open the same detail sheet as before. The "manually-added deliveries…"
  footnote must still be there.
  - Confirmed: 0/2

- **🆕 NEW — #977 — Home is now "Today" (redesign stage 4).** Open the app's home screen (not the
  bubble). Top to bottom it should read: **date + a live clock + a status pill** (the pill's word is
  the same status vocabulary the old card showed — Ready / Looking for offers / Heading to Pickup…),
  then **TODAY'S PLAN**, **SO FAR TODAY**, **THIS WEEK**, any review chores, then the four entry
  tiles + Show Bubble. Four things to check.
  (a) **The clock actually ticks** — watch it roll a minute without leaving the screen. A frozen
  clock is the defect.
  (b) **The plan strip:** 24 little cells = *this weekday's* hours across your whole history, green
  where you've earned well. Every hour before the current one must be visibly **dimmed**, and the
  dimming must advance on its own when the hour rolls over (park on the screen across e.g. 5:59 →
  6:00). The headline reads `Best bet tonight: 5–8 PM · your Mondays run $X/hr` with the
  recommended cells **outlined**, and the line under it must ALWAYS read
  `from your own <weekday>s, lifetime — not a guarantee`. On a weekday you've barely worked it must
  say so ("Not enough Mondays on record yet — only N hours…") and show **no rate at all** — a rate
  on a thin weekday is the bug to report. It should never recommend a window that has already
  passed.
  (c) **This week:** kept money for the pay week, a `▲/▼ X% vs last week` line (or "About the same",
  or "Up from nothing" — never a percentage against an empty week), and a 7-point sparkline. Tap it:
  `Recap →` must land in Analytics **already showing this week** — if the hub opens on some older
  window you paged to earlier, that's the bug.
  (d) **Review chores:** if the week has any (unattributed pay, "(No session)" drops, orphan
  offers), they appear as one **NEEDS A LOOK** card, each row ending in `Review →` that also lands
  in Analytics on this week. A clean week must render no card at all.
  Also confirm nothing was lost: the old Today/Week/Month/Lifetime selector is gone on purpose —
  those windows live on the Analytics pager now.
  - Confirmed: 0/2 (desk 07-31, the 07-30 dash: NOT TESTABLE on this build — the pull's device is
    master @ 3bff50dd (07-30 ~14:22), which predates PR #978 (merged after this build). No redesign
    UI evidence obtainable from this pull.)

- **🆕 NEW — #975 — Analytics → Offers tab (redesign stage 3).** Open Analytics. The second tab is
  now **Offers** (order: Money · Offers · Time · Patterns). On it, check four things.
  (a) **Top pair:** acceptance rate on the left, `~$X` "said no to" on the right, with the
  accept/decline/timeout bar under both. The said-no caption must name its population —
  `est. net across N of M declines with estimates` — and if the verdicts didn't price ANY decline
  it should read as an em-dash plus "none of them priced by a verdict", never `$0.00`.
  (b) **Estimate vs reality:** two bars (Est. / Realized $/hr) + a line reading "Accepted offers
  realized about N% of their decision-time estimate", plus a caption saying how many of the
  window's accepted offers were matched. With <5 matched it should ALSO say "Thin data". A
  realized bar wildly above the estimate one (roughly 2×) on a day you ran **stacked** orders is
  the bug to report — stacked jobs are supposed to be excluded from both sides.
  (c) **The list:** your actual offers, newest first, All/Accepted/Declined/Timed-out chips,
  declined and timed-out rows visibly dimmer, and a `See all N offers` footer that expands in place
  (it should re-collapse to 10 rows when you page the window or change the chip).
  (d) **Cross-check:** the `N of M accepted offers` on the est-vs-reality card and the `Accepted`
  count in the funnel legend must be the SAME M for the same window.
  - Confirmed: 0/2 (desk 07-31, the 07-30 dash: NOT TESTABLE on this build — the pull's device is
    master @ 3bff50dd (07-30 ~14:22), which predates PR #976 (merged after this build). No redesign
    UI evidence obtainable from this pull.)

- **🆕 NEW — #944 / PR #957 — permission gate re-hoisted (UDF).** The permission bottom sheet's
  behavior should be unchanged: it opens when permissions are missing, advances card-by-card as
  each is granted, closes cleanly when all are granted — and, the edge the refactor specifically
  defends, RE-OPENS with a fresh (not stale) card queue after revoking a permission from system
  Settings and returning to the app.
  - Confirmed: 0/2 (desk 07-31, the 07-30 dash: un-exercised — this is a UI interaction test that
    needs dev eyes revoking a permission from system Settings mid-session; nothing in a desk pull
    bears on it either way. Stays at 0/2.)

- **🆕 NEW — #936 / PR #952 — an offer with an unreadable distance says so instead of guessing.**
  If a card's mileage ever fails to parse, the HUD/notification should show `no verdict` +
  `distance didn't parse` (no `$0/hr`, no score gauge) and the voice should say *"No verdict — the
  distance didn't parse"*. Rare in the field; the desk check is `offer_records` rows with
  `quality = 'UNKNOWN'` and a null `distanceMiles` — their `score`/`estDollarsPerHour` columns
  must be **null**, never `0`.
  - Confirmed: 0/2 (desk 07-31, the 07-30 dash: the whole `offer_records` table — 383 rows,
    lifetime — shows 0 null `distanceMiles`, 0 `quality='UNKNOWN'`, 0 `score=0`. The failure mode
    never occurred on this device; a clean null result, not a confirmation of the fallback path.
    Stays at 0/2 pending an offer that actually fails to parse distance.)

- **🆕 NEW — #942 / PR #953 — UI-edge SSOT batch (four quick eyeball checks, one dash).**
  1. **Store-join parity:** on a stacked offer, the heads-up notification and the bubble card name
     the stores identically (`Store A & Store B`) — the notification used to say `+`.
  2. **Verdict word:** a manual-review verdict reads `REVIEW` on the bubble offer card (was
     `MANUAL REVIEW`), matching the notification.
  3. **Badge pills:** text badges read their full names (`All Orders Same Store`, `Items Can Be
     Added`) — check they don't overflow/truncate badly in the HUD pill row; if they do, shorten
     the enum `displayName` (one owner now).
  4. **Chat clock:** flip the phone's 24-hour setting with the bubble chat open; existing
     timestamps re-render in the new format without reopening.
  - Confirmed: 0/2 (desk 07-31, the 07-30 dash: un-exercised — all four sub-checks are eyeball/UI
    checks a desk pull can't answer (no stacked offer this dash to check store-join parity against
    either, all four accepted offers single-store). Needs dev eyes.)

- **🆕 NEW — #938 / PR #954 — English-locale boundary notice.** Briefly switch the phone's
  language to Spanish (Settings → System → Languages), then toggle DashBuddy's accessibility
  service off and back on. Expect: exactly **one** system notification titled "DashBuddy solo lee
  pantallas en inglés" under a new "Avisos de la aplicación" channel, and a `WARN/LocaleBoundary`
  line naming `'es'` (and **not** a region) in the log. Toggle the service again — the WARN
  repeats, the notification does **not**. Switch back to English and toggle once more — neither
  appears.
  - Confirmed: 0/2 (desk 07-31, the 07-30 dash: un-exercised — the device stayed on English
    throughout; the notice/WARN path needs the language-switch-and-toggle exercise on the phone.)

- **🆕 NEW — #895 / PR #927 — fused `Apt <n>` masks are plain on all 8 uber+doordash surfaces
  (desk-only).** The `Apt [redacted:<4hex>]` form (brute-recoverable ~10⁴ alphabet) is gone;
  the fused subpremise masks as `Apt [redacted]` with no hex on
  `dropoff_pin_entry`/`dropoff_handoff`/`dropoff_pre_arrival`/`dropoff_pre_arrival_completion` +
  the uber #825 family. **Desk:** `grep -r "Apt \[redacted:" captures/` → zero; names/streets on
  the same surfaces must still carry their 4 hex (the exemption/large-alphabet masks are
  untouched).
  - Confirmed: 0/2 (desk 07-31, the 07-30 dash: NEW RESIDUAL, not a bump — `dropoff_photo` is a 9th
    fused-`Apt` surface PR #927's 8-surface list didn't cover: three captures on this dash show
    `Apt/Suite [redacted:<4hex>]` (hex, not plain) while the SAME dash's `dropoff_handoff` correctly
    shows `Apt [redacted]` (plain) — the fix works everywhere it was applied, `dropoff_photo` was
    simply left off the list. Filed as #986; stays at 0/2 pending that fix.)

- **🆕 NEW — #888 — eight DoorDash screens that used to fall to UNKNOWN are now recognized.**
  All eight are **recognize-only** (no state claim, no parse), so the only intended change is that
  the frames stop landing in the UNKNOWN capture pile. The list: the post-accept **"accepting"
  spinner** (every accept was losing its own follow-frame), the mid-shop **pay-adjustment sheet**
  ("N items have been added / We have adjusted your pay"), the dropoff **geofence-warning help flow**
  (warning card mid-inflation → Help menu → "Continue to complete delivery"), **"Confirm you handed
  order directly to customer"**, the **pizza-bag verification** flow (instruction → uploading →
  result), the **shelf-photo substitution** sheet, the **"Confirm order was picked up"** dialog, and
  the **wait-survey sheet variant**.
  **What to watch (DoorDash dash) — this is a NEGATIVE test; nothing new should appear:**
  1. **Nothing changes visibly.** No new bubble cards, no new TTS, no "Declined/Expired" chatter
     around an accept. If any of these screens now makes the HUD say or do something, that is the
     bug — note which screen.
  2. **Accepting an offer still behaves exactly as before** — the accept lands, the pickup card
     comes up, no ghost offer, no "(offer replaced)". The accept spinner is the highest-frequency
     of the eight, so it is the one to watch. The rule deliberately does NOT re-assert
     `offer:presented` (that is the #595 ghost-accept class), but the field is the proof.
  3. **A pizza order and a shop-with-substitutions order** exercise two of the eight; if you get
     either, note whether the pickup/dropoff lineage stayed correct through them.
  **Desk (next pull):** the UNKNOWN pile should no longer contain these eight families — check the
  UNKNOWN census for `progress_message` offer frames, `pizza_bag_*`, `wait_survey_container`,
  `geofence_warning_map`, "items have been added", "photo of the shelf", "handed order directly",
  "Confirm order was picked up". Recognized captures for these intents should be PII-clean (they
  carry no customer nodes at all; the geofence card's `address_line_*` are covered by that rule's
  existing redact).
  - Confirmed: 0/2 (desk 07-30, the 07-29 dash: PARTIAL — 2 of 8 families confirmed recognized
    (`offer_accepting` ×1, `shopping_pay_adjustment` ×1), the other 6 never occurred; the negative
    test passes (no new bubble/TTS chatter around 4 accepts, no ghost offers). BUT the
    accept-spinner rule covered only 1 of 4 accepts and `progress_message` frames still land
    UNKNOWN — the offer-card inflation family is filed as #922; hold at 0/2 pending it.
    Desk 07-31, the 07-30 dash: a THIRD family confirmed — the full geofence help flow
    (`dropoff_geofence_warning` → `dropoff_issue_menu` → `dropoff_issue_resolution` →
    `dropoff_geofence_warning`, 19:29:23-33) recognized clean, zero new bubble/TTS chatter
    (`offer_accepting` ×2 of 4 accepts, `shopping_pay_adjustment` ×1 also this dash — same ~50%
    accept-spinner coverage as 07-29). NEW RESIDUAL inside the now-recognized geofence family: the
    flow's own `Continue / Go back` confirmation sheet (19:29:31) is still UNKNOWN — filed as #989.
    Still holding at 0/2 pending #922 and #989; 5 of 8 families remain unexercised.
    Desk 08-09, the 08-01→08-08 window: a FOURTH family sighted and the negative test holds across
    five dashes — recognized this window: `offer_accepting` ×7, `shopping_pay_adjustment` ×1,
    `shopping_shelf_photo` ×1 (new), `dropoff_multi_order_confirm` ×1 (new), with zero new
    bubble/TTS chatter around any of 26 offers and no ghost offers. The blocker is unchanged: the
    offer-card **inflation** frames still land UNKNOWN (~29 of them this window, #922, including
    two fully-populated offer cards), so accept-spinner coverage is still partial. Holding at 0/2
    pending #922 and #989.)

- **🆕 NEW — #882 / #881 — Uber stacked offers speak the STORE, and a "Match" is recorded as a match.**
  Two fixes on the same card. **#882:** a multi-order Uber card ("Delivery (2)") rendered its type
  chip above the one store line it shows, and the chip won the store read — so the bubble/TTS said
  *"Offer. Delivery 2."* and `offer_records.merchantName` recorded "Delivery (2)". The **display**
  now falls through to the visible store ("Sonic (3035 Tpc Pkwy)"); the offer's internal identity
  deliberately still uses the chip, so a stack card can't flicker into looking like a new offer.
  **#881:** the card's CTA ("Match" = a Trip-Radar match, "Accept" = a direct offer) is now recorded
  as `offerKind`, and a real offer arriving over a match is narrated as an expected hand-off instead
  of the loud "(offer replaced)" card.
  **What to watch (Uber dash):**
  1. **A stacked offer speaks a real store name** — never "Delivery 2" / "Shop and Deliver 3". A
     single-store Uber offer must still speak exactly what it did before.
  2. **No store name goes missing.** If an Uber offer suddenly speaks "Unknown Store", that's this
     change over-rejecting — note the store and the card shape.
  3. **A direct offer landing on top of a "Match" card** should NOT pop a "Declined/Expired (offer
     replaced)" chat card any more; the new offer just takes the surface.
  4. **Trip time (desk-only — nothing to see while driving).** An Uber card that also shows
     "Arrive around 8:39 PM • 1 min away" was recording the 1-minute ETA as the trip time instead
     of the *"14 min (3.1 mi) total"* line. This never touched the verdict or the spoken $/hr —
     the evaluator derives its own estimate from distance + handling — so there is **no on-dash
     tell**; it corrupted the recorded time, the offer's identity hash, and the accept-time
     estimate fallback. Check it in the pull, not in the car (below).
  **Desk (next pull):** `offer_records` for Uber should carry real merchant names (zero rows where
  `merchantName LIKE 'Delivery (%'`); `OFFER_TIMEOUT` payload descriptions should show
  `Superseded by direct offer` wherever a direct offer preempted a match; and in the
  `OFFER_RECEIVED`/closing payloads, `parsedOffer.timeToCompleteMinutes` should equal the card's
  own *"N min (M.M mi) total"* number — never a 1–2 minute value that matches an "N min away"
  ETA banner on the same frame.
  **Still wanted:** multi-frame captures of ONE lingering stack card — if Uber CYCLES which store a
  stack shows across re-renders, the desk can finally settle whether identity may follow the store
  (today it deliberately does not).
  - Confirmed: 0/2
- **🆕 NEW — #867 (display side) — the bubble says WHICH dash it is showing, and you can switch it.**
  With two platforms online the HUD used to follow whichever app produced the last frame, unlabeled
  — a DoorDash "Declined" chip read as the Uber offer you had just acted on. Now: platform chips on
  every card + the chat header whenever ≥2 dashes are live, a manual **Showing DD | UBER** chip row
  above the stack, and a Dev-settings switch (Settings → Developer Options → **Session
  presentation**) picking between **Follow** (default), **Pin**, and **Merge**.
  **What to watch (needs a genuine multi-app dash — both apps online at once):**
  1. **Chips appear** the moment the second dash starts (cards + chat header), and DISAPPEAR when
     you drop back to one platform. A chip must never name the wrong app.
  2. **Follow (default):** the stack tracks whichever app you last touched. Tap the other chip in
     the switcher — the stack + chat swap to that dash and STAY there while you use the other app.
     The live (bottom, expanded) card disappears while you're looking at the non-active platform —
     that's intentional (there is no live screen for it), and the Accept/Decline buttons go with it.
     End the dash you pinned → the HUD goes back to following on its own.
  3. **Pin:** same, except the pick survives that dash ending and re-takes the surface when that
     platform dashes again. Nothing is persisted across an app restart — a pin is per-session.
  4. **Merge:** one stack with both dashes' cards interleaved chronologically, each chipped; the
     switcher row is hidden and chat stays on the followed dash. This is the mode to judge — it's
     the one the dev was unsure about.
  **Which mode felt right while driving** is the actual deliverable — the winner becomes the
  shipped default and the other two get deleted.
  **Desk:** the two dashes' chat histories should now be cleanly separated per session (the #873
  write-side fix); confirm no line from one platform sits in the other's `chat_messages` rows.
  - Confirmed: 1/2 (desk 07-30, the 07-29 dash — the DESK/write-side half only: `chat_messages`
    groups cleanly per session (49 / 2 / 8 rows across the three sessions, zero cross-session
    bleed). Single-platform evening, so the display half — chips, switcher, the three modes —
    remains untested; the second confirmation needs a genuine multi-app dash with dev eyes.)
- **🆕 NEW — #874 / #875 — the Uber HOME screen stopped guessing "offline", and the nav-less
  offline card is now recognized.** Sibling of #857/PR #872, one rule down: `home_dashboard`
  branch 3 claimed offline whenever the bottom nav existed and the words "You're online" did not
  — so a half-drawn online dashboard, or a *Trip Details* screen stacked over it, forged the same
  destructive Online→Offline edge. It now requires the offline card's own headline
  (`header_text_view` = "You're offline"), which also recognizes the fielded card-without-nav
  variant (#875) that previously landed UNKNOWN.
  **What to watch (Uber dash, multi-apping):** no phantom "Done Ubering!"/"Started Ubering!" churn
  while you're bouncing between apps; the dash-count at the end of the session still equals the
  number of times you actually pressed GO / went offline; and going offline from the home screen
  is still *noticed* (the HUD should flip within a frame or two of the card appearing, not stay
  stuck Online for the rest of the dash).
  **Desk (next pull):** in `captures/uber/accessibility.window/`, frames now folder-ing as
  `home_dashboard` should each carry either `go_online_button` or `header_text_view` =
  "You're offline"; the 68–69-node nav-bar-only frames and any "Trip Details" frame should land in
  `UNKNOWN` (that is the fix, not a regression). Online→Offline edges in `app_events` should be
  1:1 with `uber.click.go_offline` clicks or with an evidence-bearing home/idle_map frame.
  - Confirmed: 0/2
- **🆕 NEW — #884 — the amount-bearing transfer button no longer evades the marker backstop.**
  `SensitiveTextMarkers` gained `Transfer in` plus a `transfer $<digit>` amount-adjacency shape.
  The leak channel was the CLICK envelope (the tapped button is serialized in isolation, so the
  window-level AND-pair sensitive rule can't fire); `Transfer $45.66`/`$83.65`/`$68.52` and
  `Transfer in` reached disk on build `ddd9e7ff`.
  **What to watch:** nothing on-dash — but if you happen to open DasherDirect and tap a transfer
  button, that's the exercise. **Desk:** run the new *Dasher-banking sweep* block in
  `desk-validation-playbook.md` over the pull — `grep -rliE 'transfer +\$[0-9]' captures/` and the
  `-e 'transfer in'` sweep must both return ZERO. Corroborate in `shareable.log`:
  `grep 'Capture scrubbed:'` should show the drop with marker id `Tr11` (`Transfer in`) or
  `sh30` (the amount shape) when the surface was touched. Over-match watch: no benign
  delivery/offer frame should go missing from `captures/` (the shape needs the literal word
  `transfer` adjacent to a `$`, so an ordinary money label must still capture).
  - Confirmed: 1/2 (desk 07-31, the 07-30 dash: TRUE-POSITIVE half exercised and PASSED — the
    dasher opened DasherDirect twice (14:47, 21:38). Both button classes fired: `Capture scrubbed:
    UNKNOWN click hit sensitive marker id 'Tr12'` (Transfer out, ×2) + `'sh30'` (the amount-adjacency
    shape, ×2) + one screen-level `'Tr12'` hit. The dasher-banking sweep (`transfer +\$[0-9]`,
    `transfer in`, `available balance`, `routing number`, `cash out`, `instant pay`) returns ZERO
    raw hits across the whole pull. Marker observed was `Tr12`/"Transfer out" rather than the item's
    named `Tr11`/"Transfer in" — same backstop family, different button on this visit; no over-match
    (no benign delivery/offer frame missing from `captures/`). Second confirmation wants the
    `Transfer in` button specifically, to round out the pair.
    Desk 08-09, the 08-01→08-08 window: SAME SHAPE AGAIN, still not the missing button — five more
    marker fires (`Tr12`/Transfer out ×3, `sh30`/the amount-adjacency shape ×2), the full
    dasher-banking raw sweep (`transfer +\$[0-9]`, `transfer in`, `available balance`, `routing
    number`, `cash out`, `instant pay`) returns ZERO across the whole nine-day pull, and no benign
    delivery/offer frame went missing. Stays 1/2 — the pair still needs the literal `Transfer in`
    button, a deposit-side affordance the dasher simply hasn't tapped in two windows now.)
- **🆕 NEW — #843 — prompted per-capability automation consent (no auto-grant).** Automations are
  no longer pre-granted; each must be consented to individually via a prompt at the app's front
  door (Google Play policy). On upgrade, a one-shot migration clears the old auto-grants, so every
  automation starts OFF.
  **What to watch (first app-open after installing this build):**
  1. On first open (once accessibility/notification permissions are in), a **consent sheet**
     appears listing each automation **individually** — Accept, Decline, Confirm-decline, Open
     pay breakdown — each with its own Allow / Don't-allow buttons and a source line ("Built-in
     DoorDash rules"). There is **no "allow all"** button; "Not now" dismisses and it should
     re-appear next time you foreground the app while anything is still undecided.
  2. Before you grant anything, **quick-decline / auto-expand should do nothing** (stay MANUAL) —
     the automation is off until consented.
  3. Grant **exactly one** automation (e.g. Decline) and leave the others "Not now": on the next
     offer, only that one fires; the un-granted ones still require a manual tap. A "Don't allow"
     choice must never re-prompt.
  - Issue: #843. Confirmed: 1/2 (2026-07-24 install, desk 07-26: the MECHANISM half is proven from
    `app.log` — the one-shot migration cleared prior grants at 17:58:32, the reconcile that followed
    granted NOTHING ("none granted — awaiting consent"), and three grants arrived only as separate
    explicit user acts ~16 s later; the 19:30:17 decline chain fired only after those grants. The UI
    half — sheet layout, "Not now" re-prompt, "Don't allow" durability, item 3's exactly-one-grant
    check — still needs dev eyes. Likely 4th capability left ungranted is accept (no automation
    accept line post-install; hashes-only logs can't prove identity). Desk 07-27 corroboration:
    across a full day the three granted automations fired constantly (confirm_decline ×14,
    decline ×6, expand ×8) while accept NEVER fired automatically over 10 manual accepts —
    second independent support for the ungranted-accept read. Desk 07-30, the 07-29 REINSTALL day:
    third mechanism corroboration — two `Consent: reconciled 4 capabilit(ies)… none granted —
    awaiting consent` lines at install (11:02/11:03), ZERO grant lines all evening, and zero
    automation fired across 8 declines + 4 accepts (every decline a dasher tap — 3 heads-up, 5
    in-app): item 2's before-you-grant-anything check proven negatively. Still 1/2 — the UI half
    (sheet layout, "Not now" re-prompt, "Don't allow" durability, exactly-one-grant) needs dev eyes.
    Desk 07-31, the 07-30 dash: FOURTH mechanism corroboration, this time a genuinely FRESH install
    (not a reinstall) — `INFO/Consent: reconciled 4 capabilit(ies) from rule load (none granted —
    awaiting consent)` at 14:50:52.611, and the fail-closed gate was caught live actually denying an
    automation 5× (`WARN/Effects: Denied confirm_decline — no granted capability for rule
    'doordash.screen.offer_popup_confirm_decline' (fail closed)`), the clearest single-log evidence
    yet that #417's gate and #843's no-auto-grant compose correctly. Still 1/2 — the UI half is
    unchanged and still needs dev eyes.
    Desk 08-09, the 08-01→08-08 window: SIXTH mechanism corroboration, and the longest observation
    window yet — the 07-31 install's two `Consent: reconciled 4 capabilit(ies)… none granted —
    awaiting consent` lines, then **zero** grant lines across nine days and five dashes, with the
    fail-closed gate denying `confirm_decline` **21 times** over that stretch (`WARN/Effects:
    Denied confirm_decline — no granted capability … (fail closed)`). Item 2's
    before-you-grant-anything check is now proven negatively over a full field week rather than a
    single evening. Still 1/2 — the UI half (sheet layout, "Not now" re-prompt, "Don't allow"
    durability, exactly-one-grant) has still never been exercised and needs dev eyes.)

- **🆕 NEW — #859 (H4 + placeholder filenames) — one offer screenshot per presentation, and no
  `{storeName}` filenames.** The Uber offer screenshot now dedupes on the presentation (#830's
  `presentationKey`) instead of the churning quote hash, and a rule filename template whose field
  parsed null saves as `Offer` instead of the literal token.
  **What to watch (Uber dash, evidence capture ON):** Pictures/DashBuddy holds roughly **one
  `Offer - <store>.png` per offer you actually saw** — no runs of 3–6 near-identical shots of the
  same card seconds apart; a *later* offer from the SAME store still gets its own shot (this is the
  regression to watch for — a missing capture for a repeat store means the dedupe went too far).
  **Desk (next pull):** `ls` the pulled `screenshots/` — (a) zero filenames containing `{`, (b)
  offer-screenshot count ≈ offer count for that platform (was 140/112 on 07-25), (c) cross-check a
  repeat-store hour in `offer_records` against the file list to confirm repeats were captured.
  - Issue: #859. Confirmed: 0/2 (desk 07-30, the 07-29 dash — SUPPORTING evidence only, the item
    is Uber-gated and Uber never went online: DoorDash side 12 `Offer - *.png` for 12
    OFFER_RECEIVED, exactly 1:1, repeat-store offers each captured (7 H-E-B offers, 7 shots),
    zero `{` filenames. The churn-dedupe target case and the null-field `{storeName}` fallback
    both remain unexercised.
    Desk 07-31, the 07-30 dash: still Uber-gated and Uber never went online, so still supporting
    evidence only — zero `{` filenames, 6 OFFER_RECEIVED with 7 offer screenshots (one physical
    presentation, offer seq 1338/Target $11.45, produced two identical `Offer - 11.45.png` shots
    5.1 s apart). Not a data-integrity issue (not the churn-dedupe target class), but a minor
    off-by-one worth noting — the 07-29 pull was exactly 12/12. Still needs an Uber dash.)

- **🆕 NEW — #428-B / PR #845 — multi-language TTS (system locale + settings override).**
  **What to watch:** Settings → Voice → Spoken offer language set to Español → the next offer reads
  in Spanish (voice AND words together); System default on an English phone stays English; if the
  es voice pack is missing the read falls back to English (one WARN in the log, never silence).
  **Desk:** grep for the `Tts` tag language-apply lines; no per-utterance WARN spam.
  - Confirmed: 0/2 (desk 07-26: INCONCLUSIVE — 64 post-install utterances all English, zero WARN
    spam, but the Spanish path and the missing-voice-pack fallback were never exercised. CAVEAT:
    the desk grep above has NO corresponding log site in the current code — a no-hit is not
    evidence; needs the Settings→Español toggle actually flipped on a dash. Desk 07-30: same
    null — 14 utterances, all English, zero WARN; the toggle was never flipped.)
- **🆕 NEW — #810-B2 / PR #847 — orphan offer resolution (inference + attestation).**
  **What to watch:** after any dash where the `JOB_ACCEPT_MISMATCH` WARN fires (chat-path unassign
  class), the Money tab shows the review callout; the drill-down lists the job's accepted offers
  and attesting one marks it unassigned (undo stays reachable while the group is listed).
  **Desk:** `SELECT * FROM offer_records WHERE outcomeResolved IS NOT NULL` — cross-store orphans
  auto-resolve as `UNASSIGNED_INFERRED` (projector v8 retro-processes history; the session-114
  same-store orphan must sit UNRESOLVED awaiting attestation, never auto-stamped); Decisions-tab
  accepted counts exclude resolved rows. NOTE: the v14→v15 migration's instrumented test needs the
  standard device run at the next reinstall.
  - Confirmed: 0/2 (desk 07-26: INCONCLUSIVE with one sub-check PASS — the v7→8 refold ran clean
    (979 events, 0 skipped) and `outcomeResolved` is correctly ZERO rows: no `JOB_ACCEPT_MISMATCH`
    has ever been written (the #818 tripwire postdates session-114), so Tier 1 had zero input and
    the session-114 orphan sits correctly UNRESOLVED, never auto-stamped. Needs a dash with a real
    invisible unassign to exercise either tier.
    Desk 07-31, the 07-30 dash: same shape again — `outcomeResolved` non-null count is ZERO (Tier-1
    had no `JOB_ACCEPT_MISMATCH` input this dash, so correctly nothing to resolve). Still needs a
    dash with a real invisible unassign to exercise either tier.)
- **🆕 NEW — #830 / PR #839 — presentation-scoped offer identity (+ the #826 accept chain).** The
  ticking Uber card no longer mints replacement offers: a re-render with the same store/order shape
  ENRICHES the pending offer in place (keeps its presentation epoch and click latches; heads-up
  updates; TTS speaks once).
  **What to watch (Uber dash):** each physical offer is spoken ONCE (no triple reads), no rapid
  "offer replaced" bubble storms, and — the big one — accepting an offer and driving it should now
  produce a costed job (this + #827 unblocks the #762-D2 accept inference that 07-21 proved
  unreachable). **Desk:** `offer_records` shows ~one row per physical offer (07-21 showed 17 rows
  for far fewer offers, every lifetime 3–10 s); Uber `OFFER_TIMEOUT` with description "Replaced by
  new offer" ≈ 0; an accepted trip has `OFFER_ACCEPTED` + non-null economics instead of the
  "Unknown Store uncosted corpse". Churn *rate* post-#827 is also worth noting in the log
  (pay/miles still tick — enrichment should absorb it silently).
  - Confirmed: 1/2 (desk 07-26, post-install window: PASS on the core invariant — ZERO
    same-presentationKey replaces across 130 Uber offers (every "Replaced by new offer" was a
    genuine store change), replace rate 12%→8.5%, and the 07-21 triple-read signature is gone
    (repeated utterances were distinct presentations with identical numbers, i.e. Trip Radar
    re-offering the same trip — sounds like a repeat but isn't churn). The ACCEPT-CHAIN half is
    untested: zero Uber accepts occurred (see #251/#786/#826 — the decline/board problem). Second
    confirmation needs an Uber dash with a real accept.)
- **🆕 NEW — #825 / PR #833 — Uber recognized-surface customer redact wave.** `active_trip`,
  `customer_chat`, `splash`, and `pickup_verification_items` now carry full redact blocks (content
  shapes + uber id anchors incl. the chat `headline_text` "<Name> says:" header and the
  `map_marker_pin_head` address that rides `contentDescription` only).
  **What to watch:** nothing visible on-dash — this is capture-side. **Desk:** in the next pull,
  every RECOGNIZED uber envelope must show `[redacted:<4hex>]`/`[redacted]` on customer
  name/street/gate nodes and zero raw customer tokens (grep the recognized uber captures with the
  CLAUDE.local.md name/address recipe; the 07-21 pull's 4 leaking active_trip frames are the
  before-picture). Chat header masks must be per-customer-stable (same hex as the bare name).
  - Confirmed: 0/2 (desk 07-26: INCONCLUSIVE — zero Uber `active_trip`/`customer_chat`/
    `pickup_verification_items` frames exist in the pull (no trip was ever accepted). The Uber
    surfaces that DID capture show correctly-masked dropoff lines.)
- **🆕 NEW — #795 / PR #834 — PIN-required delivery confirmation flow + plainMask.** The Enter-PIN
  keypad modal and intro sheet now recognize (`dropoff_pin_keypad`/`dropoff_pin_intro`,
  recognize-only), and the entered PIN masks to a **plain** `[redacted]` (no 4-hex suffix — a
  bounded PIN is brute-recoverable from 4 hex).
  **What to watch:** on the next PIN-required delivery (CVS-class), the delivery completes as
  before. **Desk:** those frames land in their intent folders instead of UNKNOWN; in the keypad
  frames' envelopes the PIN digits appear ONLY as plain `[redacted]` — no `[redacted:hhhh]` token
  on any pure-digit node, no raw PIN anywhere.
  - Confirmed: 0/2 (desk 07-26: no PIN-required delivery occurred; global `pin[\s:#]*\d` grep over
    the pull → zero raw hits.)
- **🆕 NEW — #796 / PR #837 — DoorDash recognition-gap batch (11 rules).** Dropoff issue
  menu/resolution, task feedback, receipt photo, barcode confirm/failed, navigate-to-zone loading,
  and the Quality Rate / Dasher Rewards family now recognize (all recognize-only; zero state
  impact expected).
  **What to watch:** nothing on-dash. **Desk:** the UNKNOWN family census should drop these
  families to ~zero; `dropoff_issue_resolution` envelopes must mask the fused header as
  `For [redacted:<4hex>]` (marker kept, name+store masked). The known residual: the
  pickup-card-over-zone COMPOUND frame stays UNKNOWN deliberately (PII-bearing; follow-up gap
  noted on #796) — its customer name is covered by the #806/#815 UNKNOWN scrub, verify it masks.
  - Confirmed: 1/2 (desk 07-26: 8 of the 11 families recognize with ZERO UNKNOWN siblings
    (task_feedback, receipt photo, barcode confirm/failed, zone loading, rate detail, orders list,
    ratings, qr_confirm). Three residual gaps confirmed still UNKNOWN — acceptance-rate detail,
    scrolled last-100 list, customer-rating detail — filed as #865. **Desk 07-30: the residual's
    "verify it masks" check FAILED** — an UNKNOWN pre-render sheet shipped `user_name_label`
    ='Delivery for' + a RAW `user_name` sibling (18:14:06); the #806 prefix scan structurally
    can't reach a bare-name node. Field-confirmed as #910's V5; the #910 build's node-ID backstop
    is the fix. The #865-side trio was separately closed by PR #926's ratings broadenings.)
- **🆕 NEW — #810 B1 / PR #818 — JOB_ACCEPT_MISMATCH close tripwire.** A job closing with more accepted
  offers than accounted physical orders now emits one `JOB_ACCEPT_MISMATCH` event + a `StateMachine` WARN
  (the 07-19 session-114 invisible-unassign class is no longer silent).
  **What to watch:** if a dash includes a chat/support-path unassign (no confirmation screen), expect the
  WARN at job close. **Desk-side:** `SELECT * FROM app_events WHERE eventType='JOB_ACCEPT_MISMATCH'` —
  should be non-empty iff an invisible unassign (or the documented #700 suppressed-arrival residual)
  occurred; a normal dash must produce ZERO rows (false-positive watch).
  - Confirmed: 1/2 (2026-07-21 dash, desk 07-22: FALSE-POSITIVE half — zero rows on a normal 6-delivery
    DoorDash day, correct silence. The true-positive half still needs an invisible-unassign dash.
    Desk 07-26: false-positive half re-confirmed — zero rows across three more normal DoorDash
    dashes, 7 deliveries. Desk 07-27: THIRD clean pass — zero rows on 11 deliveries / 12 pickups
    incl. a two-store one-customer job and a receipt stack. The item stays open solely for the
    true-positive half. Desk 07-31, the 07-30 dash: FOURTH clean pass — zero `JOB_ACCEPT_MISMATCH`
    rows across a textbook 4-job dash (6 offers, 4 accepts, 2 declines, 0 timeouts). Still stays
    open solely for the true-positive half.
    Desk 08-09, the 08-01→08-08 window: FIFTH clean pass, and the widest yet — zero
    `JOB_ACCEPT_MISMATCH` rows across five dashes / 26 offers / 15 drops, *including* two shapes
    that would have been prime false-positive bait: a job that absorbed three separately-accepted
    offers (see the 08-09 entry's item 7) and a two-store stack. The tripwire correctly stayed
    silent on both — neither is an accept-vs-orders mismatch, they are pay-attribution defects
    downstream of it. Still stays open solely for the true-positive half.)
- **🆕 NEW — #809 / PR #820 + #803 / PR #821 — pickup/dropoff PII redacts (desk-resolvable).** New
  redacts: `pickup_select_issue` (+ its issue-list variant, now recognized) masks the fused
  `For <name> • <store>` header to `For [redacted:<4hex>]`; `dropoff_pin_entry`/`dropoff_handoff` mask
  gate-code/PIN-bearing instruction bodies (incl. `PIN: NNNN` / fused `PinNNNN` variants).
  **Desk-side:** in the next pull, every capture of these four surfaces must show the masked forms — grep
  for `For [A-Z][a-z]+\s+[A-Z]\.` and `pin[\s:#]*\d` over the recognized capture tree → zero raw hits.
  (#885: use `\s+` between the name tokens, never a single literal space — the 07-26 render put a
  DOUBLE space before the last initial and a single-space grep reads clean on a real leak.)
  - Confirmed: 0/2 (desk 07-26: both greps → zero raw hits across the whole pull, but none of the
    four specific surfaces occurred, so this is a clean null, not a confirmation.)
- **🆕 NEW — #801 / PR #817 — bubble session-earnings freshness on 0.230.0.** The collapsed receipt
  no longer refreshes session `runningEarnings` (the 0.230.0 digit-wheel is unparseable); the figure now
  rides the dash-control "This dash" label parse alone. After a delivery on 0.230.0, verify the bubble's
  session-earnings figure still updates post-receipt; watch for a stale figure. Desk-side:
  `sessionEarnings=null` on collapsed parses is EXPECTED on 0.230.0; the dash-control parse lines are the
  live source.
  - Confirmed: 0/2 (desk 07-26: the `sessionEarnings=` grep has NO corresponding log site in current
    code — desk hint is dead, a no-hit proves nothing. Indirect evidence only: `[Earnings]: Saved:
    $12.90` + an exact session reconciliation post-install. Needs dev eyes on the bubble figure.)
- **🆕 NEW — Capability consent surface: honest copy + revoke aborts automation to manual (#422 PR 3).**
  Settings → Data & Privacy → **Automation & Consent** now lists, per bundled ruleset source, every
  automation tap the rules enable (Accept, Decline, Confirm a decline, Open the pay breakdown) with a
  Google-Play-consistent disclosure header and one grant/revoke switch each. Bundled (asset) capabilities
  show as on by default; the switch writes through the same grant store the fail-closed engine gate (#417)
  reads at fire time.
  **What to watch:** (1) the screen lists the DoorDash capabilities with plain, accurate copy — each says
  what DashBuddy taps, inside which app, and that it never acts without your go-ahead (no marketing fluff).
  (2) **Revocation is fail-closed:** turn OFF "Confirm a decline" (or "Open the pay breakdown"), then on the
  next dash confirm that automation *aborts to manual* — the quick-decline second tap no longer fires
  (you confirm the decline yourself) / the summary no longer auto-expands — while the same action left ON
  still fires. Turning it back ON restores the tap. **Desk-side:** the `Consent` INFO line
  `consent revoked/granted for capability key <sha256>` on each toggle (PII-safe — hash only), and the
  `Effects` WARN `Denied <action> — no granted capability for rule '…' (fail closed)` when a revoked
  action would have fired.
  - Confirmed: 0/2 (desk 07-26: the desk half-signals showed — `Consent` grant lines are PII-safe
    hashes-only, and the fail-closed abort WARN fired 4× (though from target-resolution failure
    (#863), not revocation). The revoke-toggle round-trip still needs dev eyes.)

- **🆕 NEW — #857 / PR #872 — Uber offline detection is now positive-evidence (idle_map rewrite).**
  A partial render can no longer forge Online→Offline; a genuine offline home still recognizes via
  "You're offline" / "You're ready to go online" / the GO button; deliberate toggles ride the
  go_offline click. Deliberate bias: believe-online (a false offline destroyed sessions; a delayed
  offline costs seconds). **What to watch (Uber, especially multi-apping):** no more spurious
  "Started/Done Ubering!" churn while switching apps or gaming; exactly one session per real
  online/offline pair. **Desk:** `session_records` Uber session count == go_online/go_offline click
  pair count; every Online→Offline edge is ≤4 s from a go_offline click or a settled offline frame;
  known residuals #874 (home_dashboard absence arm) + #875 (the "Ready to go?" variant).
  - Confirmed: 0/2
- **🆕 NEW — #786 / PR #869 — Uber decline click rule (the X is recognized).**
  The offer card's textless dismiss-X now latches `declineCommittedAt` → declines record as
  `OFFER_DECLINED` instead of the timeout ignorance-default. **What to watch (Uber):** the bubble's
  Dispatch line says "Offer Declined" on your X taps; **THE CRITICAL GUARD — an Uber dash with a
  REAL accepted, driven offer must produce `OFFER_ACCEPTED` + a costed job and NEVER a false
  `OFFER_DECLINED`** (the mislatch hazard the review closed with the own-text-empty predicate; if
  an accept ever records as declined, pull the build and report — that's the revert condition).
  **Desk:** `offer_records` Uber outcome census gains OFFER_DECLINED rows ≈ witnessed X taps
  (~claimed 4/5 in the 07-25 pull); unwitnessed disappearances stay TIMEOUT pending #251.
  - Confirmed: 0/2
- **🆕 NEW — #858 / PR #876 — expiring Uber cards no longer mint offers.**
  The "This request is no longer available" dying card falls UNKNOWN instead of matching the offer
  rule. **What to watch (Uber):** an offer that expires on screen produces no new offer row, no TTS
  read of the error text, no "(offer replaced)" churn on the live offer. **Desk:** zero
  `merchantName` containing "no longer available" / "Unknown Store"-via-overlay in `offer_records`;
  the dying frames appear as UNKNOWN captures.
  - Confirmed: 0/2
- **🆕 NEW — #861 / PR #868 — Uber selfie ID-verification camera is sensitive-blocked.**
  **Desk only:** next pull has ZERO selfie-flow UNKNOWN envelopes (`facecamera` ids /
  "Fit your face in the guide"); `sensitiveDropped` increments across that window instead.
  - Confirmed: 0/2
- **🆕 NEW — #860 / PR #871 — Building Name value masked on both dropoff workflow sheets.**
  **Desk only:** any `dropoff_pre_arrival` / `dropoff_pre_arrival_completion` envelope carrying a
  `Building Name` row shows the value as `[redacted:<4hex>]` (label survives). New engine predicate
  `hasPrecedingSiblingText` is the anchor — a completion-sheet fixture is still wanted (none
  fielded yet; the entry is shape-inherited).
  - Confirmed: 0/2
- **🆕 NEW — #867 write-side / PR #873 — bubble chat messages carry their own session.**
  Offer outcomes, session lifecycle, task lines, and the heads-up summary now file into their
  ORIGINATING session's chat even when the other platform's frames flip the active platform
  mid-write. **Desk (multi-app pull):** `chat_messages.dashId` for offer-outcome lines matches the
  offer's own platform session — zero cross-platform mis-files. (Display-side — what the bubble
  SHOWS with two live sessions — is still open on #867, dev call.)
  - Confirmed: 0/2
- **🆕 NEW — #865 / PR #877 — performance-hub residual gaps recognized.**
  Acceptance-rate detail, scrolled last-100 list, customer-rating detail. **Desk only:** those three
  UNKNOWN families → zero in the next pull. Watch item folded in per the review: if "View all
  offers" opens a "Last 100 offers" modal, it will land UNKNOWN (never fielded, deliberately not
  speculatively covered) — grab it if you visit that screen. Compliments-quote residual: a dasher
  WITH compliments renders customer-authored quote text that no redact can scope today — if your
  rating screen shows quotes, flag the pull for a manual check.
  - Confirmed: 0/2

- **🆕 NEW — Patterns tab store cards: glanceable face + detail bottom sheet (#765 / PR #799).**
  The store report cards were redesigned: the card **face** now shows only store name + location chip
  and three plain-language numbers (Net / Usual wait / Deliveries) — no "median"/"p95" vocabulary —
  with a `>` chevron affordance. Tapping a card opens a **bottom sheet** with the full detail (pickups,
  gross, the dwell distribution labeled "Usual wait (median)" / "Longest waits (p95)" / "Average wait",
  and first/last-seen). **What to watch:** open Analytics → Patterns; the store cards read at a glance
  (2–3 numbers, no stats jargon on the face); tapping a card opens the sheet with the fuller breakdown;
  swipe/scrim dismisses it; "usual wait" on the face matches the median in the sheet; a location-unknown
  (chain-only) card still shows its "partial" note inside the sheet.
  - Confirmed: 0/2
- **🆕 NEW — Uber active-job skeleton: accepts become costed jobs at trip start; honest "ON JOB" badge (#762 D2 / PR #784). NEEDS UBER ENABLED.**
  `active_trip` (the coarse `on_job_view` screen) now declares the phase-less `task:active` flow, and the
  accept grace is per-platform (Uber 600s vs the old global 120s). Together: an accepted Uber offer is
  consumed into a fully-costed job within seconds of accept instead of expiring into an uncosted corpse
  on any >2-min drive, and the badge/HUD show "ON JOB" instead of a stale OFFER card for the whole
  pickup drive.
  **How to tell it's working (any Uber trip):** immediately after accepting, the badge flips to ON JOB
  (not stuck on OFFER) and the live offer card clears; the trip's job carries offer economics (desk-side:
  the job's `OFFER_ACCEPTED`/job-mint events appear at accept time, not at the store, and the folded
  delivery is NOT a "no economics" row). Also watch: a **mid-trip stacked offer that you DECLINE or
  ignore** must NOT produce an `OFFER_ACCEPTED` event (desk-side grep) — the ambient-screen guard.
  While parked at the store, the odometer arbiter now sees interleaved `task:active` frames as "moving"
  (leg unknown ⇒ can't claim parked) — watch Uber mileage for dwell-drift inflation vs the odometer span.
  **Capture-first on the same dash** (feeds #785/#786 — see those issues): notification envelopes for a
  full trip incl. every update of the ongoing status notification, one multi-order/stacked job, the
  decline affordance frames, any "Going to <digit-leading store>" push.
  - Confirmed: 0/2. **BLOCKED-as-fielded (desk 07-22, second Uber attempt):** the consume path is
    unreachable because the accept itself is undetectable — the fielded accept-click node is TEXTLESS, so
    `uber.click.accept_offer` never latches and every offer resolves `OFFER_TIMEOUT` before `active_trip`
    appears (one job still minted from ambient frames: store "Unknown", uncosted — the exact corpse shape).
    Filed **#826** (accept detection, the D2 blocker) + **#827** (offer time/miles parse swap — poisons the
    economics this item would validate). Keep the item; it becomes testable when #826 lands.
  - (2026-07-19: first attempt — INCONCLUSIVE: Uber app restart churn; the only accept happened during
    a capture gap so no job minted; the one recognized offer timed out cleanly. Watch: offer-capture
    fragility during Uber app instability.)
  - (2026-07-19: decline affordance capture-first — WARN "No declineButton target bound for uber"
    fired; confirmed still open, feeds #786.)

- **🆕 NEW — WATCH (accepted residual): coarse-only Uber trip may leave its job open past the receipt (#762 D2 / PR #784).**
  A marker-less `on_job_view` frame between the post-trip receipt and idle walks the flow
  PostTask→TaskActive→Idle and suppresses the receipt-exit job close when NO leg screen ever activated
  a task (coarse-only trip). Chosen fail direction is absorption (job stays open; closes at session end)
  — this item quantifies whether the frame shape actually occurs in the field.
  **How to tell (desk-side):** after an Uber dash, any job whose deliveries completed but whose job-close
  event only arrived at `DASH_STOP`; if seen, capture the post-trip → idle frame sequence.
  - Confirmed: 0/2
  - (2026-07-19: N/A — no job existed)

- **🆕 NEW — "Shopping off" declines shop offers at the verdict edge (#762 D12 / PR #778).**
  With `allowShopping` off, a shop-type offer now gets a structural `SHOP_DECLINED` verdict (label
  "Shopping off") from the evaluator itself — full economics still computed and shown, score 0,
  decline recommendation — instead of relying on downstream handling.
  **How to tell it's working (needs the strategy toggle off; watch any shop offer):** the offer
  card/bubble shows the "Shopping off" quality label with the decline recommendation while the pay/
  mileage numbers still render; non-shop offers are completely unaffected. Flip the toggle back on
  and a shop offer scores normally again (the gate reads live strategy prefs).
  - Confirmed: 0/2

- **🆕 NEW — idle bubble: gas + vehicle are now two separate full-width cards (#728 / PR #767).**
  The cramped one-row `JustInTimeActions` (the layout the dev called "a nightmare to try to operate")
  is now a stacked pair of full-width cards on the idle dashboard card: one for the #722 mode-adaptive
  gas control, one for Vehicle — every touch target ≥48dp (stepper −/+, refresh, "Resume auto" chip,
  and the whole vehicle card is the tap surface).
  **How to tell it's working (on-device, no dash needed):** open the bubble while OFFLINE — two
  visually distinct cards instead of one crowded row; every control comfortably tappable with a thumb;
  gas behavior itself unchanged (AUTO refresh / take-manual / Resume auto per the #722 item above);
  Vehicle tap still opens Personal Economy. Watch for: content clipping on the MANUAL gas row (known
  residual on very narrow windows — strictly better than before, but capture it if seen), and any
  visual double-ripple or dead tap zone on the vehicle card.
  - Confirmed: 0/2

- **🆕 NEW — a same-customer double-order job closes at its receipt; the next offer is its OWN job (#749).**
  A job where **both orders go to the same customer** (the offer card literally says so — e.g. Willie's +
  Sonic to one person) mints two dropoff placeholders but only ONE physical drop. Before #749 that leftover
  placeholder kept the job "open" forever, so the **next offer folded into the finished job** and its pay
  showed up as **unattributed** (the job-61 class — $19 of $45.75 swallowed). The fix proves completion from
  the pickup side (`JobCompleteness` per-customer coverage arm).
  **How to tell it's working (needs a same-customer multi-order offer, then a NEXT offer after it):** after
  the single drop completes, the job **closes** (the bubble/HUD returns to idle/waiting, not a lingering
  active task); the **next offer you accept starts a fresh job** (its own store/economics on the card, not
  appended to the finished one); in the Money-tab drill-down the two orders appear under **their own dashes/
  rows** and there's **no unattributed-pay spike** for that stretch. Watch especially the hand-off between a
  same-customer double and the very next accept.
  - Confirmed: 1/2 (desk 07-27: the exact shape fielded on 07-26 — Dunkin' + Taco Palenque, two
    orders one customer, TWO pickups ONE drop. Job closed at its $9.80 receipt, the next offer
    minted its OWN job (13:55:52), session reconciled $80.55 == $80.55 with zero unattributed —
    the job-61 class did not recur. Caveat: it closed via the STRICT completeness arm (no
    "#749 coverage arm closed" line), so #749's own per-customer arm is outcome-validated but not
    path-exercised; the second confirmation should ideally catch the coverage-arm path.)
  - **BROKEN-IN-PART (desk 08-09, the 08-07 dash) — the shape recurred and CLOSURE worked, but
    half the job's pay vanished.** Offer seq 1496, $13.10, two orders to one customer: both pickups
    confirmed, one physical drop, the job closed correctly and the next offer minted its own job —
    #749's own deliverable held. But the single folded delivery took `offerPayShare = 6.55`, i.e.
    the offer total split across BOTH owed drops while only one drop ever exists to claim a share,
    and the session's unattributed remainder is exactly $6.55. #749 fixed *closure* for this shape;
    it did not touch the `owedDropoffs` denominator that prices it. Filed as **#996** and written up
    as item 6 of the 2026-08-09 entry below (per this file's own rule, a broken finding goes to the
    log immediately). The item stays here at 1/2 for the closure half's second confirmation — but
    read it alongside #996, because on a receipt-less job the two halves disagree.

- **🆕 NEW — categorize a "(No session)" orphan delivery into its real dash (#660 piece 2).**
  The Money-tab "(No session): $X across N deliveries" callout is now **tappable** — it opens an
  orphan list; tapping a delivery opens a session picker (ended dashes within ±48 h of the drop, same
  platform, nearest first). Confirming assigns the orphan to that dash. A `DELIVERY_SESSION_ASSIGN`
  correction is written; the projector re-attributes the row (attribution ONLY — pay/net are never
  re-priced) and the read-model refreshes reactively.
  **How to tell it's working (needs a real orphan on-device — a mid-dash service/app restart that
  dropped a delivery's `sessionId` while the dash summary still captured its pay):** the callout shows
  a nonzero "(No session)" amount; tapping it lists the orphan(s); picking the correct dash makes the
  **callout shrink by that delivery's pay** (and empty entirely once all orphans are categorized) with
  no manual refresh; the target dash's **drill-down gains a row tagged "assigned by you"** and its
  **header delivery count goes up by one** (header and list agree — no mismatch); and if the orphan's
  dollars were already inside that dash's reported summary, the Money-tab **gross drops** (the
  double-count heals) rather than staying inflated. Undo path: open the assigned row's Adjust dialog →
  **"Remove from this dash"** returns it to the bucket. Frozen economics must be untouched by all of
  this (the row's net/pay/est-offer-pay disclosure are the same before and after). PR for #660 piece 2.
  - Confirmed: 0/2

- **🆕 NEW — unassign an order AFTER pickup (dropoff phase) also produces NO paid artifact (#752 / PR #757).**
  Companion to #736: when the unassign happens while a **dropoff** is active (or was just grace-retired
  en route to the customer — e.g. a help/idle screen interrupted the drive, the retire grace fired,
  then you unassigned), the app now retro-marks **that drop** as unassigned instead of a sibling
  pickup, so the close-out can't fabricate a `DELIVERY_COMPLETED` for an order you never delivered.
  On the previous build this dropoff-phase cross-frame shape fabricated a paid delivery and suppressed
  the pickup's legitimate confirmation.
  **How to tell it's working (on-dash + desk-side):** unassign an order you've already picked up (mid
  drive to the customer). Expect the **"Unassigned: <store>" bubble**, the card clears, and the next
  offer works normally. Desk-side, the exported log / `app_events` should show **exactly one
  `TASK_UNASSIGNED`** (phase DROPOFF) for that order and **no `DELIVERY_COMPLETED`** for it — no phantom
  "$0 PAID" delivery in the Money tab. (In the cross-frame shape a `DELIVERY_CONFIRMED` from the earlier
  grace retire may already have fired on the prior frame — that's read-model row-inert and expected, so
  don't treat its presence as a failure.) The sibling pickup of a stacked job should still show its
  normal `PICKUP_CONFIRMED`.
  - Confirmed: 0/2
    - desk 09-05: NOT this item's case — the slice's one `TASK_UNASSIGNED` (seq 1806) was
      PICKUP-phase (arrived at the store, never confirmed); it behaved correctly (the $45.45 quote
      stayed unattributed, no paid artifact), but the dropoff-phase retro-mark is still unexercised.

- **🆕 NEW — GoPuff / multi-order drop-off confirm card recognized (#501 items 1-2 / PR #743).**
  The "Confirm you have the correct order before drop-off / Mix-ups frequently occur…" card that
  appears before a drop on a **multi-order Dash** (GoPuff Drive batches AND ordinary multi-merchant
  grocery batches) is now a recognized screen (`dropoff_multi_order_confirm`) instead of falling to
  UNKNOWN. It's recognize-only (no state change) — its only job is to stop hitting the UNKNOWN
  capture folder. **How to tell it's working (desk-side, after a multi-order dash):** the
  `Confirm…correct order before drop-off` frames should NO LONGER appear under
  `captures/.../accessibility.window/UNKNOWN/`; they should sort into
  `accessibility.window/dropoff_multi_order_confirm/`, and the redacted capture must show the
  customer name line masked (`[redacted:...]`), with the store name + item count kept. Completes
  #501 (all 3 items). This is the last recognition piece of #501; watch that it doesn't perturb the
  dropoff flow (no phantom re-mint around the confirm card).
  - Confirmed: 0/2
- **🆕 NEW — multi-store-from-one-receipt keying: both stores keyed from a single end-of-job receipt, no downgrade on payout-less close (#159 / PR #739).**
  Narrowed scope: the basic-keying half retired 2/2 (07-17 + 07-18/19 — all deliveries carry real running
  keys, no more empty `…|target|` segments). What's left is the harder multi-store case. **How to tell it's
  working (desk-side, after a multi-store stack — e.g. the Target+Maple case):** BOTH stores should be
  keyed from the single end-of-job receipt, not one keyed + one chain-only. And a payout-less close
  (`DASH_STOP` with no summary) must NOT downgrade an already-keyed store back to chain-only. No UI yet
  (the #315 Patterns tab is the consumer) — verify via the DB / a CSV-adjacent read.
  - Confirmed: 0/2 (multi-store-from-one-receipt case still unfielded)
- **🆕 NEW — Analytics → Patterns tab: store report cards + net-$/hr heatmap (#315 H5 / PR).**
  The Patterns tab (Analytics hub, no period selector — it's lifetime/rate-based) now renders two real
  sections: (A) **store report cards** newest-visited-first, one per resolved store — chain name + a
  location chip (the running key, or a "location unknown" chip for chain-only entities), pickup/delivery
  counts, cash-inclusive gross/net, avg/median/p95 pickup dwell, and first/last-seen; and (B) a **7×24
  hour×day heatmap** of the driver's OWN realized net $/hr, best-hour callout on top. **How to tell it's
  working (open Analytics → Patterns after a few dashes):** the store cards should list the actual stores
  from recent dashes with running keys that match reality and **sane dwell numbers** (minutes, not
  seconds/hours — dwell = confirm − arrive on real pickups); the heatmap's warmer cells should fall on the
  hours/days you actually earn the most, cells you barely dashed should read as "too little time" (dim,
  distinct from a worked-but-earned-nothing cell), and the "best hour so far" line should feel right.
  Framing check: all copy is "YOUR net $/hr" — flag any wording that reads as a platform-pay claim.
  - Confirmed: 1/2 (the **data half**, desk 08-09, the 08-01→08-08 window: 14 `pickup_records`
    visits, dwell 0.0–71.1 min — all minutes-scale, none negative, none hours-scale — and the
    resolved store keys read as real places rather than placeholders, including the #773
    address-tier form `doordash|h-e-b|@5910`. That is the "running keys match reality + sane dwell"
    correctness half this item was held open for. The **UI half** — cards rendering, the heatmap's
    warm cells falling on hours that feel right, the best-hour line — still needs dev eyes, and
    note #979 has since reshaped this tab into a leaderboard, so the second confirmation should be
    taken against that item's description, not this one's card layout.)
  - **2026-07-13 desk pass:** data half sane — pickup dwell all minutes-scale
    (1.8–53 min across 15 visits). **2026-07-12 partial dev sighting (post-#763 build):** the heatmap section (B)
    renders well and the dev likes it; the store-cards section (A) was flagged for UX polish — too
    word-dense, and "p95" means nothing to a dasher (issue filed from this feedback). Data-correctness
    halves (running keys match reality, sane dwell) still unverified — this item stays open for those.
- **🆕 NEW — GoPuff zone-arrival screens recognized (recognize-only, no state effect) (#501 item 3 / PR #738).**
  The GoPuff "Navigate to zone" / "Arrived at store" screens (the `go_to_store_action_view` CTA card)
  are now recognized as `pickup_zone_arrival` instead of landing in UNKNOWN. **How to tell it's working
  on a GoPuff (DoorDash Drive) run:** after the dash, the zone-arrival frames should NOT appear in the
  UNKNOWN capture folder (they were ~15 frames/session of UNKNOWN noise on 06-14), AND the bin-scan
  "Pickup steps" screen must still be the one-and-only `PICKUP_ARRIVED` anchor (exactly one arrival per
  warehouse visit — the new rule is recognize-only and must not steal or duplicate it). A zone-arrival
  frame still hitting UNKNOWN, or a second/missing pickup arrival, is a regression. Rule was built from
  hand-cited anchors (the 06-14 captures were never committed), so a live GoPuff sighting doubles as the
  first real-frame validation — grab a capture either way. **Also spot-check the captures LABELED
  `pickup_zone_arrival` are actually zone screens** (the anchors are shared with regular pickups; a
  regular pre-arrival or map frame tagged as zone-arrival is the over-match regression, not a pass).
  - Confirmed: 0/2
- **🆕 NEW — bubble post-dash card shows the TRUE last session + gas/vehicle quick actions (#693 / PR).**
  The idle bubble card now reads the analytics read-model (`recentSessions(1)`) instead of an in-memory
  capture that could miss a dash. **Primary check — the $0 repro:** end a dash that earned nothing (accept
  one order then unassign, or just go online→offline with no completions), collapse the bubble, then reopen
  it. The idle card AND the dimmed top-bar "LAST SESSION" must reflect **that** dash (earnings, miles,
  duration, deliveries, acceptance %), **not** an older moneyed dash. The "ended Xm ago" caption should tick
  up live. A live dash shows full-opacity "THIS SESSION". **Vehicle:**
  tap Vehicle → the main app opens directly on the Personal Economy screen. Anything stale, or a $0 dash
  getting skipped, is a regression — capture it. (The gas quick-edit sub-check is superseded by the
  mode-adaptive #722 item below — the plain stepper-tap-flips-mode UI this item originally described no
  longer exists in AUTO mode.)
  - Confirmed: 1/2 (07-07 on-device screenshot, post-reachability-fix: the idle card showed the TRUE
    last session — the 07-05 **$0** dash, `$0.00 · 6.9 mi · ended 43h ago`, exactly matching
    `session_records` — i.e. the primary $0-repro check passed: a $0 dash was NOT skipped for an
    older moneyed one.)
  - Desk replay fixture: `~/dashbuddy/logs/2026/07/06/` (the 07-05 $0 session `session-doordash-1783294721320-15`).
- **🆕 NEW — bubble gas quick-edit is mode-adaptive: refresh (AUTO) vs. Resume auto (MANUAL) (#722 / PR).**
  **Where to find it:** the idle dashboard card, shown whenever OFFLINE with no active dash (the
  #722/#693 reachability fix — a completed session's timeline no longer hides it; the full timeline
  now appears only while actively dashing, plus in the analytics drill-down).
  The idle card's gas control now shows ONE control set per mode instead of a stepper+refresh combo, because
  each action's meaning flips with mode. **AUTO mode:** price + "AUTO" caption + a refresh icon (no stepper
  visible) — tapping refresh fetches today's EIA price and stays auto (price updates in place, no mode
  change); tapping the price itself (or its small pencil) is the "take manual control" gesture — the
  stepper should appear and the caption should flip to MANUAL. **MANUAL mode:** stepper + "MANUAL" caption +
  a labeled **"Resume auto"** chip (never a bare refresh icon) — tapping it should re-enable auto AND pull a
  fresh price in one action, flipping the caption back to AUTO and hiding the stepper. Watch for: a spinner
  during the fetch, a small transient error indicator if the fetch fails (offline/no location — no toast
  spam), and that **Settings → Personal Economy** always agrees with whatever the bubble shows afterward.
  Frozen-economics invariant: any of this only changes FUTURE offer $/hr, never a past delivery's recorded
  net. Anything that shows a bare icon changing modes silently, a stepper visible in AUTO mode, or a refresh
  icon visible in MANUAL mode is a regression — capture it.
  - Confirmed: 1/2 (2026-07-12, dev exercised the idle-card gas control on the post-#763 build:
    "works fine" — first live exercise of the interactions after two dashes with no bubble-initiated
    fetch in any log. Second confirmation should re-touch the full cycle: AUTO refresh → take-manual
    → Resume auto, plus the Settings → Personal Economy agreement check.)
- **🆕 NEW — offer lifecycle unchanged single-platform after the platform-owned-offers move (#438 B3 / PR).**
  Offers moved off the shared global screen slot onto each platform's own region (the concurrency
  fix), and the whole accept-stash mechanism was replaced by an owned accepted-pending-consumption
  survivor. Single-platform behavior must be **indistinguishable**. On a normal DoorDash dash watch the
  full offer surface end-to-end: (1) the **offer card** pops with correct $/hr, $/mi, and the expiry
  bar; (2) the **heads-up notification** posts once the eval lands, with working Accept/Decline; (3) the
  **spoken read** (TTS) fires; (4) accepting **mints the job with economics** (offer $ on the job/receipt)
  and the right pickup/dropoff placeholders for a stack; (5) declining/timing-out clears the card and
  logs the right outcome (Declined/Timed Out), and the #594 "Review offer→Accept after a committed
  decline" still stands as Declined. Any offer that fails to card/speak/notify, an accept that mints a
  **bare** job (no offer pay), or a wrong/absent outcome card is a B3 regression — capture it.
  - Confirmed: 1/2 (07-07 + 07-08 desk analysis: the machine half — (4) accepts mint jobs WITH
    economics (every delivery costed OFFER_FROZEN), (5) outcomes correct incl. a first-fielded
    OFFER_TIMEOUT, and the Sonic+Willie's two-pickup stack minted both pickup placeholders —
    clean on BOTH dashes. 2026-07-18: 25 offers across the day all resolved coherently — data half
    reinforced. Second confirmation should be dev-eyes on (1)–(3): card visuals, heads-up
    notification, TTS.
    Desk 08-09, the 08-01→08-08 window: the machine half stays clean at scale — 26 offers across
    five dashes, every one carded and evaluated, **zero** `OFFER_TIMEOUT`, outcomes coherent
    throughout. Two notes that bear on sub-check (3) and on (4)'s completeness rather than on B3
    itself: the **spoken read stopped firing entirely from 08-06 16:28 onward** (every `speak()`
    returned −1 — that is #991, a TTS-handler death, not an offer-lifecycle defect: the offers
    still carded and notified), and one accepted offer never linked to its job
    (`offer_records.linkedJobId` null on the 08-08 Zaxbys accept, because the job had no pickup
    phase at all — #1000). Stays 1/2; sub-checks (1)–(3) still need dev eyes, and (3) can't be
    judged at all until #991 is fixed.)
- **🆕 NEW — edit a delivery directly + cash tips (#688 phase A / PR).** In **Analytics → a dash →
  the delivery drill-down**, **tap a delivery row** (or its pencil) → the **Adjust delivery** dialog:
  Store name / Pay / Tip / Cash tip / Miles / Note. This replaces the pay-only editor and is the real
  fix for the 07-05 "bill millers" workaround (editing the store name into a note).
  How to tell it's working: (1) **Edit a store name directly** — change a drop's store to the correct
  merchant, Save; the row's store name updates and the per-store breakdown (Money tab → Top stores)
  re-buckets it under the corrected name. The "est. offer pay" qualifier on an estimate row must
  **survive a store-name-only edit** (a non-pay edit must NOT flip it to a corrected/plain row). (2)
  **Add a cash tip** to a delivery (or on Add-missed-delivery): the dash header gains a separate
  **"+$X cash tips"** line, the row shows a **"+$X cash"** line and its net rises by the cash — BUT the
  **"Gross (reported)" tile and the unattributed callout do NOT shrink** (cash adds to gross/net, it is
  NOT reconciled against reported). (3) Re-price a captured drop's Pay: net recomputes and (on a
  machine row) the basis reads corrected; a MANUAL row stays MANUAL. (4) Nothing is destroyed — the
  original event stays; a projector rebuild reproduces the edited rows. **Phase B (per-leg mileage) is
  deferred.** (#688 phase A / PR)
  - Confirmed: 0/2

- **🆕 NEW — receipt-less shop delivery shows est. offer pay, not $0-unattributed (#691 / PR).**
  Do a **DoorDash shop order** (grocery/convenience — the kind that shows NO per-delivery receipt at
  the end; pay lives only on the offer + the running dash total). After it completes, open **Analytics
  → the dash → the delivery drill-down**.
  How to tell it's working: (1) the delivery row shows a **real pay figure with an "est. offer pay"
  qualifier** under it, NOT a `$0` / "Unknown"-style row, and the Money-tab unattributed callout
  shrinks accordingly. (2) On a **receipt-less shop STACK** (2+ orders, no receipt), the two drop rows
  are **≈ equal halves of the offer pay shown at accept** (e.g. a $12.95 stack → ~$6.48 / ~$6.47),
  summing to the offer total — not one drop taking the whole thing and the other $0. (3) A shop order
  that DID show a receipt still uses the receipt (basis DROP_SHARE/RECEIPT_TOTAL), no "est." qualifier.
  **NOTE (expected, not a bug):** net for a receipt-less shop is now **pay − mileage cost**, which is
  **LOWER** than the old unattributed-callout number (that number was raw pay with no cost) — a lower,
  more honest figure is correct. (#691 / PR)
  - Confirmed (mechanism): 2/2 — RETIRED for the mechanism half. 07-08: first firing (OFFER_PAY
    $14.25, session reconciled exactly). 07-12 (2026-07-13 desk pass): second independent firing —
    receipt-less H-E-B shop folded OFFER_PAY $10.75 alongside a RECEIPT_TOTAL sibling in the same
    session, reconciled to the cent; regression watch (5) also clean (no $0-coerced rows despite
    collapsed-summary frames). Item stays open ONLY for the dev-eyes halves: (1) the "est. offer
    pay" qualifier display, (2) a receipt-less stack's equal halves, (4) Uber-scope sanity.
  - **(4) 🆕 UBER SCOPE (PR #702 round 2): Uber deliveries now show est. offer pay platform-wide.**
    Uber has no post-trip receipt rules at all (`uber.screen.post_trip` has no parse), so EVERY Uber
    delivery now folds an OFFER_PAY estimate (the platform-agnostic mechanism working as designed, P8).
    This changes Uber analytics **wholesale** — **sanity-check the est. offer pay against your actual
    Uber earnings** (the offer's guaranteed quote vs what Uber actually paid, incl. surge/tips added
    after). Flag any drift.
  - **(5) 🆕 REGRESSION WATCH (PR #702 round 2): a collapsed/transient summary must NOT force $0.**
    On a receipt-less drop, watch that the row shows the **est. offer pay**, NOT a `$0.00` receipted
    row — a transient `delivery_summary_collapsed` frame used to coerce a `$0` pseudo-receipt that
    masked the estimate. If any receipt-less drop reads `$0.00` (basis RECEIPT_TOTAL) instead of an
    est. figure, capture the session and flag it.
  - Confirmed: 0/2

- **🆕 NEW — multi-pickup stack: symmetric pickup placeholders + store re-attribution (#526 / PR).**
  Accept a **multi-store stack** (two+ orders from DIFFERENT stores in one offer — e.g. the 07-05
  Bill Miller BBQ + Mama Margies). Watch the whole run: both pickups AND both drops.
  How to tell it's working: (1) **both pickups get confirmed** — each store's pickup shows a
  completed pickup card / PICKUP_CONFIRMED, not just the last one (Bug10a). (2) **per-store delivery
  rows are correct in analytics** — each drop is attributed to its OWN store (the drop's customer is
  hash-joined to its pickup), with **no "Unknown store" $0 row** for a drop whose card shows no store
  (F2). (3) Store names are right on BOTH drops, not swapped. (4) The job's economics/pay are present
  even if a `waiting_for_offer` flash appeared right after accept (F3 — the accept stash recovers it).
  **Watch the D5c residual:** if a pickup is retired via the grace timer (pickup → idle/offer → next
  pickup, rather than pickup → next pickup directly), that displaced pickup may still get NO confirm —
  note if a pickup card never completes. Also watch: with drop cards that parse no ADDRESS, two drops
  at different customers may fold onto one delivery card (a separate #565 stacked-dropoff limitation,
  noted in the replay test). (#526 / PR)
  - Confirmed: 0/2

- **🆕 NEW — PII-safe bug-report log export (#551 P2): Data & Privacy → Export Data → Export log.**
  After a real dash (with at least one recognized offer/delivery so INFO milestones exist), go to
  Settings → Data & Privacy → Export Data, scroll to "Export a bug report", pick a folder, export.
  How to tell it's working: (1) open `dashbuddy-log.txt` and **grep your shop's merchant name — ZERO
  hits** (it's INFO+ milestones only, scrubbed at the sink); any `[scrubbed:<marker>]` lines are the
  gate catching a leak. (2) The success line's auto-scrub count is sane (usually 0, non-zero means an
  upstream site leaked and the sink caught it — worth reporting). (3) The firehose (`app.log` in the
  app's files dir) is still **full fidelity** — raw store names present there, as expected (on-device
  only, never exported). (PR #551-P2)
  - Confirmed: 0/2

- **🆕 NEW — per-dash drill-down (#650 PR A): tap a recent dash on the Money tab.** Analytics → Money
  tab → scroll to RECENT DASHES → tap any dash row. It should open a read-only "Dash detail" screen.
  How to tell it's working: the header figures (date, start–end clock times, duration, gross, miles,
  deliveries) match that dash's summary, and the per-delivery rows list each drop (store, completion
  time, pay, tip, net, miles/min) matching what you actually delivered. When the platform-reported
  total exceeded the captured delivery pay, an "unaccounted on this dash" callout appears. (PR #650-A)
  - Confirmed: 1/2 — 2026-07-05 (DoorDash, 5 deliveries): used live — header + per-delivery rows matched the dash; the unaccounted callout showed the real $39.45 gap.

- **🆕 NEW — user corrections as events (#650 PR B): add a missed delivery, adjust a pay.** On a dash
  that has an **"unaccounted on this dash"** callout (Analytics → Money → tap the dash), tap **Add
  missed delivery**, enter a pay (optionally store/tip/note), confirm. How to tell it's working: within
  a moment the callout **shrinks or disappears** and a new delivery row appears (store you typed, the
  pay, marked as a manual/driver entry). Then tap the **edit (pencil) icon** on any delivery row,
  change the pay, save: the row **re-prices** and the header gross / period totals follow. On a dash
  with **no** callout, the **Add missed delivery** button still appears at the bottom of the deliveries
  card. Cross-check nothing was destroyed: the original captured rows are still present (a correction is
  an added event, not an overwrite). How to tell it's broken: the callout doesn't move after adding, the
  new row never appears, the re-price doesn't stick (or reverts on the next screen refresh), or a
  previously captured delivery vanishes. (PR #650-B)
  - Confirmed: 1/2 — 2026-07-05: exercised heavily — 5 PAY_ADJUSTMENTs across 3 rows; callout reconciled to $0 exactly (104.47 = 104.47); re-price stuck (incl. a two-try edit); nothing destroyed (all original events in the log). UX gaps → #688.

- **🆕 NEW — identity-less completions now firewalled at PostTask exit (#653 / PR #673): watch a
  no-name drop for a MISSING completion.** The PostTask-exit `DELIVERY_COMPLETED` mint now mirrors
  the close-out path's #498 identity firewall: a dropoff task that never acquired a customer
  name/address hash mints no completion. Field history says identity-less dropoff tasks are
  phantoms, but the known residual is a REAL delivery whose only name-bearing screen is still
  unruled — e.g. a GoPuff last drop via the multi-order-confirm surface (#501 deferred). On any
  GoPuff batch (or other drop where the customer name never appeared on a recognized screen),
  check afterwards that the delivery still shows in the session's deliveries/earnings. How to tell
  it's broken: a real, physically-delivered drop is absent from the completion list / Money tab
  while its siblings are present.

- **🆕 NEW — CSV data export (#319).** Settings → Data & Privacy → "Export Data (CSV)" → tap
  "Choose folder & export" and pick a folder (e.g. Downloads). Three files should appear:
  `deliveries.csv`, `sessions.csv`, `summary.csv`. Open each in a spreadsheet app: deliveries should
  have one row per completed drop (date/time, platform, store, pay, tip, miles, minutes, net…),
  sessions one row per dash (start/end, duration, odometer start/end, miles, offer counts), and
  summary a totals block with **one `tax_year` group per year present** (miles × the IRS standard
  business rate for that year via the per-year lookup — 2025 = $0.70, 2026 = $0.725; a future year
  with no published rate falls back to the latest rate + a `rate_note` disclaimer). **How to tell it's working:** values are sane (money looks like `8.50` not `0.00`
  everywhere; store names with commas like "Chili's, Cedar Park" stay in one cell, not split), and
  NO customer names/addresses or hashes appear anywhere. All-time export (v1 has no date-range
  picker). Screens with no dashes yet just yield header-only files — that's fine.
  - Confirmed: 1/2 — 2026-07-05: exported via the hub header icon; files sane; found the stale-2025-rate bug → #689 (the export itself worked as built).

- **🆕 NEW — Principle-7 logging phase 1: INFO+ log is PII-safe, `SCREEN:` spam gone, real WARNs
  visible (#551 PR #551-P1).** After a dash (ideally one with a **grocery shop**, so the TTS/ShopRate
  paths fire), export/inspect the log (Settings → the log/bug-report export, or pull the on-device
  file). How to tell it's working: **(a)** grep the log for your shop's merchant name (e.g. `H-E-B`,
  `Target`) — it must appear ONLY on `DEBUG` lines, NEVER on `INFO`/`WARN`/`ERROR` (the shareable
  stream). The INFO milestones should read counts-only, e.g. `INFO/Tts: speaking (NN chars)`,
  `INFO/ShopRate: recorded NN items / M.M min = R.RR/min`, `INFO/Chat: offer posted [Persona] (NN
  chars)`. **(b)** The per-frame `SCREEN: <intent>` lines are gone from INFO (now `VERBOSE/Classifier`)
  and the `👻 NULL CHILDREN` noise is gone from WARN (now `VERBOSE/Mapper`). **(c)** The real WARNs —
  `GRACE_COMMIT`/grace-timer wakes, fail-closed gate denials — are now legible in the INFO+ slice
  instead of drowned. How to tell it's broken: any raw store/customer/address text on an INFO+ line,
  `SCREEN:` still at INFO, or WARN still buried under ghost-child noise. (PR #551-P1)
  - Confirmed: 1/2 — 2026-07-05/06 log pull: post-install log has merchant names ONLY at DEBUG, `SCREEN:` gone from INFO (INFO fell to ~1% of lines), real WARNs legible. Residual untagged sites → #692.

- **🆕 NEW — driving/glance-mode HUD font-scale toggle (#318).** Flip "Driving glance mode" on in
  Settings → General while a dash is running (or the bubble is up). The bubble HUD's text — the
  hero $/hr, task timers, captions, everything — should visibly grow (~12%) immediately, with no
  restart of the app or the dash. Toggle it back off and the text should shrink back immediately.
  Throughout, the **main app window** (Dashboard, Settings, etc. — outside the floating bubble)
  should look completely unchanged at every step. How to tell it's broken: HUD text doesn't
  change size on toggle, only *some* of the HUD text scales (e.g. the hero number but not the
  captions/labels), the toggle needs an app restart to take effect, or the main app window's text
  size changes too.

- **🆕 NEW — Analytics tile now opens the Money tab (#315 H1 / Money tab v1).**
  From the home screen tap the **Analytics** tile: it should open a real hub (back arrow) with a
  **Money / Patterns / Decisions / Time** tab bar (the last three show a "coming soon" card). On
  **Money**, pick **Today / Week / Month / Lifetime** and confirm the figures re-anchor: the earnings
  hero (gross + True-Net / Net-hr chips), the **gross → −cost step(s) → net** waterfall (3- or 4-step
  per #659 — see the dedicated item below), the
  2×2 tiles ($/hr · $/mi · Miles · Deliveries), top stores, and recent dashes. Cross-check: for the
  **same period** the Money numbers should match the dashboard's tiles (both read the same frozen
  read-model). An **unattributed-pay callout** appears only when that period has bonuses/adjustments.
  How to tell it's broken: the old "Construction Area 🚧" placeholder still shows, figures don't change
  with the period, Money ≠ dashboard for the same window, a crash on an empty period, or a "$0.00"
  unattributed callout on a period with none.
  - Confirmed: 1/2 — 2026-07-05: all tabs viewed post-dash; Money figures re-anchored and matched reality (incl. the unattributed callout). Copy vocabulary note → #694.
- **🆕 NEW — Money tab 4-step waterfall: Fuel vs Non-fuel, with a clean fallback on mixed periods
  (#659).** After the v10 refold, open **Analytics → Money** on a period whose deliveries all carry
  the frozen fuel/non-fuel split (a period entirely dashed after the #668 data-side merge should
  qualify): the waterfall should show **4 rows** — Gross → −Fuel → −Non-fuel → Net — and Fuel +
  Non-fuel should visually sum to the old "Operating cost" gap. Then check a period that **mixes**
  pre-split (fallback) deliveries with frozen ones (e.g. Lifetime, or a week straddling the merge):
  the waterfall should **silently fall back to the 3-step** Gross → −Operating cost → Net shape — no
  broken numbers, no partial-coverage row, no crash. How to tell it's broken: a 4-step render on a
  mixed period (Fuel+Non-fuel not summing to Gross−Net), a 3-step render on an all-frozen period
  (coverage guard too strict), or Fuel/Non-fuel bars rendering negative/nonsensical.
  - Confirmed: 0/2
- **🆕 NEW — Analytics Decisions tab: offer funnel + value-of-saying-no + score-vs-outcome (#315 H3).**
  In the Analytics hub tap the **Decisions** tab and pick a period (Today / Week / Month / Lifetime).
  Three sections should appear: an **offer funnel** (a stacked Accepted / Declined / Timed-out bar +
  legend, with the **acceptance rate** as the big headline and the offer count under it), a **value of
  saying no** card ("~$X est. net skipped across N declined offers"), and a **score vs outcome**
  comparison (avg score + avg **est.** $/hr for Accepted vs Declined). How to tell it's working: the
  Accepted/Declined/Timed-out counts and the acceptance rate should match what you actually did on the
  dash; value-of-saying-no ≈ the sum of the estimated net of the offers you declined; every economic
  figure is labelled **"est."** (these are the offer's frozen decision-time estimates, not realized
  pay). How to tell it's broken: the "coming soon" card still shows, counts don't match the dash,
  figures don't re-anchor on a period switch, a "$0.00"/blank where an em-dash should be on an empty
  period, or a crash opening the tab with no offers yet.
  - Confirmed: 1/2 — 2026-07-05: viewed post-dash; funnel counts matched the dash (5 accepted / 3 declined, no timeouts).
- **🆕 NEW — Analytics Time tab: time split, deadhead, on-time gauge, mileage & tax (#315 H4).**
  In the Analytics hub tap the **Time** tab and pick a period (Today / Week / Month / Lifetime). Four
  cards should appear: a **time split** (hero online duration + "online across N dashes", an
  On-delivery / Unattributed stack bar with durations, and Dashes / Avg-dash tiles), a **deadhead**
  card (a % headline + an On-delivery / Deadhead miles bar — deadhead = miles not attached to any
  delivery), an **on-time** gauge ("NN% on time" over the deliveries that carried a deadline, "N of M
  … with a deadline", plus a "typically Xm early/late" margin line), and a **mileage & tax** card
  (period miles + the est. IRS standard-mileage deduction at the current year's rate via the per-year
  lookup — 2026 = $0.725/mi; Lifetime adds a "may span tax years — see the CSV export" note, and a
  Monday-anchored week straddling Jan 1 gets a "spans tax years" note). How to tell it's working:
  the online split ≈ the time you spent online vs on drops for the dash, deadhead miles look sane
  (small on a busy dash, larger if you drove a lot between/after drops), the on-time % and margin match
  how you did against the app's deadlines, and the mileage matches the odometer miles for the period.
  How to tell it's broken: the "coming soon" card still shows, figures don't re-anchor on a period
  switch, a negative deadhead/unattributed value, an on-time gauge counting deliveries that had no
  deadline, or a crash opening the tab with no dashes yet.
  - Confirmed: 1/2 — 2026-07-05: viewed post-dash; no anomalies reported. (Mileage & tax card carries the #689 stale-rate issue.)
- **🆕 NEW — Analytics Money tab: earnings-by-day chart + header CSV export icon (#315 H6).**
  Open **Analytics → Money** and pick **Week** or **Month**: a new **"EARNINGS BY DAY"** card should
  appear as the **second** card (right under the gross hero), a bar per day of the period — 7 bars for
  Week (labelled M/T/W…), ~28–31 for Month (only the 1/5/10/15/20/25/30 labelled). The **best-earning
  day** should be highlighted in the accent color, gap days you didn't dash show as empty/zero bars,
  and a "gross per day · dashes count on their start day" caption sits below. Cross-check: each bar's
  height should track that day's take, and the sum across the week/month should roughly match the gross
  hero. Then switch to **Today** and **Lifetime** — the chart card should be **absent** on both (one bar
  adds nothing; Lifetime is unbounded). Separately, tap the **download icon in the hub's top bar** — it
  should open the same **Data & Privacy → Export Data (CSV)** screen the Settings row reaches. How to
  tell it's broken: the chart shows on Today/Lifetime, a day's bar doesn't match its earnings, no day
  highlighted on a period where you did earn, missing gap days (a squished <7 bar week), or the header
  icon doesn't open the CSV export screen. (PR #315-H6.)
  - Confirmed: 1/2 — 2026-07-05: header icon used for the CSV export (opens the same Data & Privacy screen); Week chart viewed.
- **🆕 NEW — the main dashboard is now a REVIEW surface, not a live bubble mirror (#657 / PR #658).**
  Open the app **after a dash** (not while on a task): the **Today** tiles (True Net / Net $/hr /
  Miles) should already reflect the just-completed dash with no manual refresh (the read-model folds
  each delivery as it completes). Tap the **Today / This week / Lifetime** selector and confirm the
  three tiles switch to each window's totals. The old live "This dash" ticking hero is **gone** —
  there should be **no** per-second $/hr counter on this screen. While you're online, a slim
  "🟢 Dashing — tap for the bubble" row appears above the tiles; tapping it should re-show the bubble.
  How to tell it's broken: tiles frozen/stale after a dash, the segmented selector not changing the
  numbers, a live ticking counter still present, or the dashing row showing while offline.
  - Confirmed: 0/2
- **🆕 NEW — the analytics read-model projector now folds `app_events` into durable records (#314 PR2). READ THE DB / LOG AFTER THE FIRST DASH POST-UPDATE.**
  The projector runs on `DashBuddyApplication` startup (not debug-gated) and event-sources the
  `app_events` log into `delivery_records` / `session_records` / `offer_records`. On the **first
  launch after installing this build** it backfills the entire existing log; watch the INFO log for a
  single line tagged `Analytics`: `Analytics backfill complete: N events → D deliveries, S sessions,
  O offers` (counts only — it must carry **no** store/customer names). How to tell it's working:
  after a dash, read the db — `delivery_records` should have one row per completed delivery with
  `realizedPay`, `realizedMiles` (odometer partition delta), `frozenCostPerMile`/`costBasis`
  (`OFFER_FROZEN` when the offer was evaluated, else `CURRENT_FALLBACK`), and `netProfit`;
  `session_records` should have one row per dash whose `deliveries`/`jobsCompleted`/offer counts and
  `lastOdometer − startOdometer` miles match your memory of the dash. Editing the economy settings
  must **not** change any already-stored `frozenCostPerMile`/`netProfit` (they're immutable facts).
  (#314 PR2 — projector/backfill/frozen-economy.)
  - Confirmed: 0/2

- **🆕 NEW — the home screen's top glance is now REAL "Today" totals from the read model (#314 PR3, completes #314).**
  Open the DashBuddy main app (not the bubble). **Working looks like:** the top row of three stat
  tiles — **True Net · Net/hr · Miles**, each sub-labelled **"Today"** — shows your **whole day's**
  frozen net (Σ each completed delivery's frozen net + any unattributed pay), not just the current
  dash, and it **grows within a few seconds of each delivery receipt** (the projector folds the
  completed delivery → Room re-emits the flow → the tile updates, no app restart, no state
  transition). While a dash is running a second **"This dash"** row appears below it (the live
  per-second ticking glance from #320). How to tell it's right: at end of day the Today **True Net**
  ≈ your DoorDash app's earnings for the day minus your operating costs, and **editing the Economy
  settings (gas price etc.) must NOT change a past day's Today number** — historical net is frozen.
  At local **midnight** the Today figures should reset to the new day without reopening the app.
  Broken = a Today that only reflects the current dash, a number that changes when you edit economy,
  a Today that never grows after a delivery completes, or one that doesn't roll over at midnight.
  - Confirmed: 0/2

- **🔧 FIX SHIPPED — a stacked job's DELIVERY_COMPLETED rows now carry per-drop realized pay (#528 Slice A). READ THE DB AFTER A DASH.**
  Before, on a multi-store/multi-drop stack, the single combined receipt was attached to just ONE
  drop (it absorbed the whole total) and every other drop's `DELIVERY_COMPLETED` row recorded null
  pay. Now each row carries `dropRealizedPay` = the exact per-store tip matched to that drop + an
  **equal-split** share of the lump base; when the stack settles with a single end-of-job receipt
  (the normal DoorDash shape) the job's drops sum EXACTLY (to the cent) to the receipt total.
  Same-store batches and blank/duplicate store names fall back to a pure
  equal-split, so no drop double-counts a tip line. Tips are exact; **the base split is a v1
  estimate** (neither offer nor receipt breaks out per-order base). (A mid-stack/per-drop receipt
  can under-attribute — never double-count — tracked as a #528 follow-up.) This is a data-fidelity change
  only — no bubble/HUD copy changed (the card still shows the blended job $/mi; consuming the new
  field in the UI is a later slice). **Confirm after dash: 0/2 —** run a **multi-store stack**
  (2+ drops from different stores in one job) to completion, then read the db `app_events`: the
  `DELIVERY_COMPLETED` rows for that job should each have a non-null `dropRealizedPay`, and summing
  them should equal the combined receipt total (the `parsedPay.total` on the announced row).
  Broken = a drop with null `dropRealizedPay` on a receipted stack, or the per-drop shares not
  summing to the receipt total.
- **🔒 PLEDGE FIX — recognized-notification captures now mask customer PII (#620).**
  Send yourself a customer chat ("Message from ‹name›" + a message body) and trigger an order-ready
  push ("‹name›'s order is ready for pickup at ‹store›"), then pull the capture envelopes off-device.
  **Working looks like:** the chat capture shows the sender masked (`Message from [redacted:‹4hex›]`)
  and NO message body; the order-ready capture shows the customer name masked (`[redacted:‹4hex›]`)
  but **keeps the store name** (merchants aren't PII). If any raw customer name/body survives in a
  notification envelope, it's broken.
  - Confirmed: 0/2
- **🔒 PLEDGE FIX — dropoff-reminder screen capture now masks the address (#624).**
  Hit the "Deliver to door of ‹address›" reminder card on a dropoff and capture it. **Working looks
  like:** the envelope shows `Deliver to door of [redacted:‹4hex›]` — the street/apt gone. (This was
  a live raw-address leak the #598 sha256 gate couldn't catch.) A defense-in-depth backstop also
  scrubs any recognized screen that ships a "Deliver to "/"Order for " customer line a rule forgot
  to redact — if you see a `redactBackstopScrubs=` count climb in the PipelineStats log line, note
  which screen tripped it.
  - Confirmed: 0/2
- **✨ NEW — the home screen now shows a live "This dash" glance + entry tiles (#320/#316).**
  Open the DashBuddy main app (not the bubble) **while a dash is running**. **Working looks like:**
  the "Ready to Dash" area shows three stat tiles — **True Net** (green when positive), **Net/hr**,
  **Miles** — and the Net/hr + its sub-timer **tick up every second** without needing a state change
  (that's the reactive glance). True Net should equal session earnings minus miles × your operating
  cost/mi (same math as an offer's net verdict), and Miles should track the GPS session odometer.
  Below the tiles is a 2×2 grid — **Analytics · Ratings · Strategy · Economy**: tapping **Ratings**
  opens a screen showing your real customer-rating / on-time / completion gauges + acceptance /
  delivery-count / shopping-quality tiles (empty-state message if you haven't opened the platform's
  Ratings screen yet this run); **Strategy** and **Economy** open their existing editors; **Analytics**
  is a "Construction Area" placeholder for now. Broken = frozen Net/hr (doesn't tick), True Net that
  disagrees with the offer-card net math, a Ratings screen that's blank when the platform ratings
  screen was seen, or a tile that navigates nowhere.
  - Confirmed: 0/2
- **🔧 FIX SHIPPED — offer outcome cards now derive from the committed outcome, not the tap (#601).
  DELIBERATE UX CHANGE — CONFIRM ON DASH.**
  Before, tapping Accept/Decline printed "Offer Accepted"/"Offer Declined" immediately, from the
  click alone — a second, independent code path from the one that logs the outcome to the ledger
  (`resolveOfferOutcome`). They only ever agreed because the click handler happened to thread the
  same intent into both; the #594 decline-latch race showed they *can* diverge (a card claiming an
  outcome the ledger didn't record). Now a tap shows an instant **ack** only ("Accepting…" /
  "Declining…" — the #594 race warning still shows here instead, unchanged), and the real outcome
  card ("Offer Accepted" / "Offer Declined" / "Offer Timed Out!") fires later, at the resolution pop,
  off the SAME value that gets logged — card and ledger can no longer disagree by construction. A
  replaced offer (one that silently vanished under a new one) now also gets its own outcome card,
  suffixed `(offer replaced)` (e.g. "Offer Accepted (offer replaced)"), which it never got before.
  **Deliberate UX cost:** the outcome card now pops ~seconds after the tap (BK field timings put the
  resolution frame ~7-8s behind the click) instead of instantly — evaluate whether that delay feels
  acceptable on-dash. **Confirm on dash: 0/2 —** tap Accept: "Accepting…" should show instantly, then
  "Offer Accepted" when the screen actually advances past the offer. On Decline, the ack appears
  at the **confirm-sheet** tap (the first Decline tap classifies as `initial_decline` and is
  silent by design — don't score that as broken): confirm-sheet tap → "Declining…" → "Offer
  Declined" at resolution. Re-run the #594 race (decline → confirm → "Review offer" →
  Accept): the race warning ("Decline already submitted — Accept won't take") should show at tap
  time (not "Accepting…"), then "Offer Declined" at resolution. Broken = an ack that never resolves
  into a matching outcome card, or any card text that doesn't match what the db logs for that offer.
- **🔧 FIX SHIPPED — dropoff handoff waits for the completion CTA + stacked leg-2 drops get their own nav (#603). CONFIRM ON DASH.**
  Two coupled fixes for the false-early "arrived" + silent second-drop pair:
  (a) **No more false ARRIVED at the start of the drive.** `dropoff_handoff` used to fire
  `task:dropoff:arrived` the instant nav started, because it keyed only on the "Hand it to
  customer" instruction — which is on screen the ENTIRE drive. It now also requires the completion
  CTA ("Mark as delivered" / "Continue" / "Complete Delivery" / the complete-delivery button), which
  only shows once you're at the door. The en-route frame now recognizes as `dropoff_pre_arrival`
  (correct nav card). **How to tell it works:** on a drop with a real drive, the arrival card /
  `DELIVERY_ARRIVED` should appear only when you actually reach the customer, NOT the moment you
  start driving. In `app_events` the drop's `arrivedAt` should be meaningfully later than its
  `phaseStartedAt` (not the same second). Broken = the "arrived"/hand-off card popping up while
  you're still driving, or `arrivedAt == phaseStartedAt`.
  (b) **Leg-2 of a stack gets its own "Heading to" bubble.** A stacked multi-drop's second (and
  later) dropoff previously got no nav event — no `DELIVERY_NAV_STARTED`, no odometer resume, no
  bubble — because the mint only fired on pickup→dropoff, and leg-2 is dropoff→dropoff. **How to
  tell it works:** on a stack with 2+ drops, after finishing drop 1 you should see a fresh "Heading
  to …" bubble for drop 2 and a `DELIVERY_NAV_STARTED` row for it in `app_events` (and the odometer
  resumes for that leg). Broken = the second drop starting silently with no bubble / no
  `DELIVERY_NAV_STARTED`. (Note: leg-2's store label may read the generic "the customer" until #526
  widens store re-attribution — that's expected, not a regression.)
  - Confirmed: 0/2
- **🔧 FIX SHIPPED — pausing during/after a delivery shows exactly one "Dash Paused!", no phantom "resumed" flap (#605). CONFIRM ON DASH.**
  DoorDash's pause sheet is a modal on top of the just-completed delivery summary, so accessibility
  frames alternate `dash_paused` (paused) ↔ `delivery_summary_collapsed` (online) for a few seconds.
  Before #605 the state machine flipped mode on every edge — re-minting `DASH_PAUSED` + "Dash Paused!"
  on each online→paused and firing a spurious "Session resumed (grace)" card on each paused→online,
  while the dasher was still paused (06-28 15:04:32–38 receipt). Now a screen-implied resume OUT of
  Paused is **graced** (8 s): an online frame while paused arms a pending resume instead of flipping;
  a paused frame within the window cancels it; only sustained online past the grace (or an incoming
  offer) actually commits the resume. **Confirm on dash: 0/2 —** pause once during/right after a
  delivery and watch the bubble/notification stream: it should show **exactly one** "Dash Paused!"
  and **no** "Session resumed (grace)" card while the pause sheet is still up. Then tap **Resume** for
  real — the resume should surface within ~8 s (a known ≤8 s lag; if it feels too slow on-dash, the
  follow-up is wiring the `resume_dash` click to commit instantly). Broken = two or more "Dash Paused!"
  in a row, or a "resumed" card appearing while you're still paused.
- **🔧 FIX SHIPPED — rule effects from notifications key per-arrival, not per-install (#604), AND
  FrameGate no longer collapses two distinct notification arrivals sharing a contentless identity
  (#619). CONFIRM ON DASH.**
  A rule-declared log effect attached to a notification with no `dedupeKey` (e.g.
  `doordash.notification.new_order`) built its `effects_fired` idempotency key from the rule id
  alone — a GLOBAL forever-key. The first `new_order` notification of the week fired it; every
  later `new_order` notification all week silently no-opped as "already fired" (the
  `effects_fired` table is only pruned at engine init, so a long-lived process never recovered).
  The key now includes the notification's own timestamp (postTime, replay-stable), so each
  distinct arrival gets its own key; an identical repost (same postTime) still dedups correctly.
  Screen-effect dedup is unchanged (intended cross-frame behavior, e.g. `offer-ss-{parsedHash}`).
  **SECOND LAYER — NOW ALSO FIXED (#619):** parse-less notification rules (e.g. `new_order`) have
  a CONSTANT `ObservationIdentity` (no parsed fields to hash), so the pipeline's identity-dedup
  layer (`FrameGate`) could still suppress a CONSECUTIVE `new_order` arrival even with #604's
  per-arrival effect key fixed. `FrameGate` now mixes the notification's content hash into the
  identity comparison for recognized notifications ONLY (screens are unaffected — pinned by a new
  test); two observably-distinct arrivals (different store/text) both admit, while an identical
  repost (same content) still dedups. **Accepted residual (documented, not a bug):** two
  same-store distinct offers back-to-back have identical notification text, hence identical
  content hash, and still dedup — the only full separator would be `postTime`, which risks turning
  every re-render/repost into a fresh forward, so it was deliberately not used. **Precaution:** the
  `dash_status_ongoing` "still dashing" heartbeat notification (reposts constantly; whether its
  body churns per repost is unconfirmed against real captures) is excluded from content-mixing at
  the call site and keeps the old pure-identity dedup, so it can't regress into per-repost spam.
  **Confirm on dash: 0/2 —** two **different-store** `new_order` notifications in one dash with
  **nothing recognized in between** (previously required something else between them to prove
  #604 alone) should now BOTH produce a `NOTIFICATION_RECEIVED`/`NEW_ORDER` row in `app_events`.
  Also watch that the `dash_status_ongoing` heartbeat does NOT spam `NOTIFICATION_RECEIVED` rows
  once per repost. Broken = a later distinct-store arrival missing from `app_events` with no
  "Skipping already-fired effect" line for it (a FrameGate regression), or a flood of
  `DASH_STATUS_ONGOING` log rows (the precaution failed to hold).
  - Confirmed: 0/2
- **🔧 FIX SHIPPED — one DashSummary screenshot per session end, and offer screenshots are named
  with real pay (#606). CONFIRM ON DASH.** Two independent owners were both saving a
  "DashSummary - \<earnings\>" screenshot at session end: the `dash_summary` rule effect
  (deduped + throttled, fires on recognition) and EffectMap's own SESSION_ENDED commit — the
  commit-side add had a null `effectKey`, bypassing both `effects_fired` and the throttle, so
  every dash produced two near-identical dash-summary captures ~2.5s apart (the
  `AUTHORITATIVE_GRACE_MS` window). The commit-side add is now deleted; the rule owns the shot.
  Separately, all 46 offer screenshots that week saved as the literal filename
  `Offer - {storeName}.png` — the prefix template referenced `storeName`, which lives inside the
  offer's per-order `orders[]` array, never at the rule's top level where `{field}` templates
  resolve, so it never interpolated. The prefix now uses `{payAmount}` (a top-level, always-parsed
  field), so filenames read e.g. `Offer - 26.75.png`. A new lint (`ParseOutputGoldenTest`, the
  #433 family) now catches this whole class going forward: any `{field}` template in a rule's
  screenshot/bubble/log effect args that leaves a literal `{field}` in the saved string on ≥1
  corpus frame (i.e. fails to interpolate) fails the build. **Confirm on dash: 0/2 —** after a
  session ends, `Pictures/DashBuddy` should contain exactly **one** `DashSummary - <earnings>.png`
  for that end (not two ~2.5s apart), and offer screenshots from that dash should be named
  `Offer - <pay>.png` (a dollar amount), never the literal `Offer - {storeName}.png`. (Trailing
  zeros drop — `Offer - 26.7.png` for a $26.70 offer, `Offer - 5.0.png` for $5.00, is correct;
  only a literal `{storeName}`/`{payAmount}` token is broken.) Broken = a duplicate dash-summary
  pair, or any offer screenshot with a literal `{storeName}` in the filename.
- **🔧 FIX SHIPPED — automated taps now rank click candidates by evidence instead of a dead exact-bounds check (#600).**
  Every automated Accept/Decline/Confirm tap re-resolves its target against the live accessibility
  tree, then disambiguates among label-verified candidates. The old disambiguator compared
  `getBoundsInScreen()` for exact equality against the bounds captured at recognition time — that
  match died to TEMPORAL drift (an animating confirm sheet moves between recognition and re-resolve),
  so it silently fell back to "clicking first" on effectively every automated click (~40+/week),
  landing right only by luck of enumeration order. Now it ranks by exact stored text first, then max
  bounds overlap (IoU) — the WARN only fires on a genuine unresolvable tie. **Confirm on dash: 0/2 —**
  after an automated Accept/Decline/quick-decline-confirm tap, the log should show a DEBUG
  `Resolved click target for … via EXACT_TEXT/BOUNDS_OVERLAP tier` line **or**
  `Single verified candidate … clicking it` (a 1-candidate pool — also success), **not** the old
  `No exact bounds match … — clicking first` WARN on every fire; the tap should still land on the
  correct button (behavioral no-change on the happy path). Broken = the WARN reappearing on routine
  taps, or a tap landing on the wrong control (e.g. Accept firing when Decline was intended).

- **🔧 FIX SHIPPED — a shade-tapped Accept/Decline now retries instead of dying on "No live windows" (#602).**
  A notification-action tap (heads-up Accept/Decline) can reach the click handler while a SystemUI
  takeover (notification shade, lock screen) is still covering DoorDash — the field receipt showed
  a tap landing ~16ms after the press, with the window reappearing ~0.5-1s later once the shade
  collapsed. The old code failed closed on the very first empty read (1 of 18 receiver taps died
  this way, a $13.50 near-miss). Now that one read is retried up to 3 times over delays of 300/500/700ms
  (≤1.5s total) before giving up — every other check (candidates found, label verification) is still
  single-pass, not retried. **Confirm on dash: 0/2 —** tap a heads-up Accept/Decline while the
  notification shade is open or the screen just woke: the action should still land within ~1.5s (log
  shows a DEBUG `Live window for … reappeared after a …ms retry` line), no manual tap needed. **Watch
  the control case:** a tap while DoorDash is genuinely closed/backgrounded for real (not a transient
  shade) should still fail closed with the WARN (`No live windows for package … after 3 retries …`)
  — not hang or silently succeed. Broken = a shade-tapped action failing closed despite the window
  returning within the budget, or the retry firing/looping on a genuinely-gone app.

- **🔒 FIX SHIPPED — recognized capture envelopes now redact customer PII at the device edge (#598, incl. audit F1/F2 coverage extension).**
  Pre-#598 the recognized-screen captures on disk carried raw `Deliver to ‹name›`, full street
  addresses, and gate codes — PII was hashed only in the parse output, raw on disk. Now rules
  DECLARE a `redact` block and the capture stage masks those node texts in the serialized envelope
  only (recognition/parse/state unchanged); a screen rule that hashes PII (`sha256`) fails compile
  without a `redact` block. The audit F1/F2 pass extended coverage from the first 7 rules to **all**
  recognized customer-PII surfaces: `pickup_pre_arrival` (customer name + store address),
  `pickup_verify_items` (`Verify items for ‹name›`), `dropoff_photo` (free-form instruction / gate
  codes / apt — all id-less text), `chat_conversation` (customer name + every chat message body),
  `camera_capture` (`…name: ‹name›` / `…Apt/Suite: ‹apt›` + a stale bin-scan bare name),
  `nav_arriving` (arrival street address), `dropoff_geofence_warning` (address/apt lines),
  `waiting_for_offer` (a stale prior-delivery customer-name bleed), plus a bare-unit-number entry on
  the id-less dropoff cards (`dropoff_pre_arrival` / `_completion` / `dropoff_handoff`) — on top of
  the original `dropoff_handoff/navigation/pre_arrival`, `pickup_arrival`, `pickup_resolution_options`.
  **Confirm on next pull: 0/2 —** pull the on-device captures after a dash with real deliveries and
  grep the recognized envelope JSON for the surfaces above: every customer name reads `[redacted]`
  (or `Deliver to [redacted]` / `Verify items for [redacted]` where a marker is kept), addresses,
  apt/unit numbers, gate codes, and chat bodies read `[redacted]`, and NO raw recipient name / street
  / apt / gate-code / chat text survives. Markers (`Deliver to `, `Order for`, `Delivery for`,
  `Hand it to customer`, `Your Customer`, `Arriving at`, step titles) must remain so recognition is
  unchanged — verify the same screens still classify + drive state exactly as before. Broken = any
  raw customer name/address/apt/gate-code/chat text in a recognized envelope, OR a screen that
  stopped recognizing. **Documented exceptions (NOT defects):** UNKNOWN frames + UNKNOWN clicks are
  debug-only (release `NoOpCaptureBus` #346 + the `SensitiveTextMarkers` backstop, not name
  redaction); the id-less building-NAME line on dropoff cards and the merchant-name-as-product rows
  are structural residuals (#623/#624); the notification-capture customer-name path is #620; a nav
  `roadNameView` road-name residual is accepted (F7).
- **🔧 FIX SHIPPED — receipt-skipped deliveries still close + log a completion, and the next offer starts a NEW job (#596).**
  DoorDash routinely skips the post-delivery receipt (the next offer chains straight over the drop);
  pre-#596 that was the machine's ONLY job-exit, so the delivery never logged `DELIVERY_COMPLETED`
  and the never-closed job absorbed later independent offers (06-29 Pizza Hut job swallowed the next
  H-E-B $13.50; 06-30 job-61 spanned three offers). Now a *physically-complete* job (final drop
  delivered, nothing outstanding) closes on its next exit signal even with no receipt: the drop logs
  one `DELIVERY_COMPLETED` and the next accepted offer mints a fresh jobId.
  **Confirm on next pull: 0/2 —** after a delivery where DoorDash skips the receipt (you go from the
  drop's handoff/nav straight to waiting-for-offer or the next offer, no earnings summary), the db
  should still show a `DELIVERY_COMPLETED` for that drop, and the next accepted offer should carry a
  **distinct jobId** (no multi-offer job unless it's a genuine mid-route add-on you accepted while
  still delivering). Broken = a receipt-skipped drop with no completion, or two independent offers
  sharing one jobId. **Watch the guard:** a genuine mid-shop/mid-route add-on (accepted while a drop
  is still undelivered) must STILL fold into the same job — it must not regress into a new jobId
  (#499/#503).
- **🔧 FIX SHIPPED — click captures record every rule-matched tap + UNKNOWN cap re-arms after quiet (#597). READ THE PULL AFTER A DASH.**
  The week-long a11y process turned two per-process guards into forever-guards: click captures
  deduped to zero by day 3 (repeat taps hash identically), and the UNKNOWN cap (200) went blind
  for the rest of the week once hit. Now: **rule-matched** clicks (accept/decline/confirm…) are
  never deduped — every physical tap persists an envelope (UNKNOWN clicks stay bounded by the
  shared UNKNOWN budget, and now pass the sensitive-marker backstop) — and the UNKNOWN cap is
  **per burst**, re-arming after a 30-min gap with no UNKNOWN frames on that pipeline.
  **Confirm on next pull: 0/2 —** `captures/doordash/accessibility.click/` should contain
  envelopes for that day's accepts/declines/confirms (`captured=true` on the `Captured click:`
  log lines), even for buttons tapped on prior days; if an UNKNOWN-flood day happens, later
  dashes should still capture UNKNOWNs. Broken = `captured=false` on a rule-matched click, or a
  capped day staying blind on the next dash. **Calibration:** opening DoorDash casually between
  dashes resets the quiet gap (correct behavior, not a failure), and the `UNKNOWN capture cap
  re-armed` DEBUG line fires after any burst, capped or not — it alone doesn't mean the cap was
  hit.

- **🔧 FIX SHIPPED — a committed decline can't be un-declined by a later Accept (#594). CONFIRM ON DASH.**
  Field 06-30 16:59 (BK $6.25, seq 226/227): declined the offer, confirmed the decline on the sheet
  (committed server-side), then hit DoorDash's "Review offer" → Accept ~1.2 s later — the app logged
  **OFFER_ACCEPTED** and flashed both "Offer Declined" and "Offer Accepted", while DoorDash's own dash
  summary showed the decline stood (3 accepts, $45.75, no $6.25; no job/pickup ever formed). Now a
  confirm-sheet decline **latches** the outcome: any later Accept still records **OFFER_DECLINED** and
  the bubble shows a **"Decline already submitted — Accept won't take"** card instead of "Offer
  Accepted". **Confirm on dash: 0/2 —** decline an offer, confirm it, then tap **Review offer →
  Accept**: the db must record **OFFER_DECLINED** (no OFFER_ACCEPTED, no PICKUP_* / job), and the
  bubble shows the "Decline already submitted" card — not "Offer Accepted". **Watch the control
  case:** a normal accept with NO decline first must **still record ACCEPTED** and form the job.
  Broken = an OFFER_ACCEPTED after a confirmed decline, or a normal accept that fails to record
  ACCEPTED. Tag #594.

- **🔧 FIX SHIPPED — post-accept teardown frame no longer mints a ghost offer (#595). CONFIRM ON DASH.**
  The collapsing offer card after an accept (pay/distance/Accept/Decline chrome, no store rows, a
  raw UUID where the store name goes) re-parsed as a NEW "Unknown Store" offer that REPLACED the
  real accept — bogus TTS ("Accept. Unknown Store. 56/hr…"), a bogus [Good Offer] chat card, then
  "Offer Timed Out!" seconds after "Offer Accepted" (both 06-28 + 06-30). The offer rule now
  requires at least one STORE leg, so the teardown frame is UNKNOWN and never forwarded.
  **Confirm on dash: 0/2 —** accept a few offers (especially SHOP offers, where both field ghosts
  fired): right after tapping Accept there should be NO second offer announcement — no "Unknown
  Store" TTS, no extra [Good Offer] card, no "Offer Timed Out!" right after "Offer Accepted"; the
  db should show zero orphan OFFER_TIMEOUTs. **Watch for over-rejection:** a REAL offer failing to
  announce/card (its frame would land in UNKNOWN captures as an offer-looking screen with store
  rows) — grab the capture if an offer ever silently fails to appear.

- **🔒 FIX SHIPPED — Crimson Savings Jar balance notification is now pledge-BLOCKED (#599). READ THE PULL AFTER A DASH.**
  The dasher's Crimson/DasherDirect balance notification was being recognized and captured raw
  (9 files found on the 06-25→30 pull — deleted from the device). The rule is now sensitive
  (priority 0, `parse: sensitive`) — the shared content gate drops it before capture and the
  state machine. **Confirm on next pull: 0/2 —** after a dash where the Savings Jar notification
  arrived: `captures/doordash/notification/` must contain **no** `crimson_balance` (or
  `sensitive.*`) folder/files, and the log shows `Sensitive gate: dropped sensitive.crimson_balance`
  at DEBUG. Broken = any capture file containing "Savings Jar balance". (Note: `earnings_deposit`
  and `transfer_complete` notifications still capture — their sensitive-vs-keep fate is an open
  dev call on #599's siblings.)

- **👁 VISUAL-ONLY — rich offer notification looks right: gauge ring, countdown, badges (#578/#583). CONFIRM BY EYE.**
  The mechanical halves are validated (buttons fire from the floating heads-up; the card posts
  with live PendingIntents; zero RemoteViews errors across a full week) — what desk data cannot
  see is the **rendering**. **Confirm on dash: 0/2 —** (a) the score shows as a **filled circular
  gauge ring** with the number centered (not a flat bar, not blank/garbled/missing when the offer
  scored); (b) the **countdown ticks** on the collapsed banner; (c) expanding shows the verdict
  banner + **colored** (not black) badges + store. If anything looks off, screenshot collapsed +
  expanded — that screenshot is the whole test.

- **🔧 FIX SHIPPED — dropoff customer reads "\<store\>'s customer", not a 6-char hash (#568, PR #575). CONFIRM ON DASH.**
  The dropoff bubble/card used to show the raw 6-char hash prefix (e.g. "Heading to 45ceda") or "the
  customer". Now it's store-flavored — "Heading to H-E-B's customer" / the card reads "Maple Street's
  customer" — which also tells apart a multi-store stack's drops. **Confirm on dash: 0/2 —** on a
  delivery, the dropoff bubble/card should name the **store's** customer (e.g. "Wendy's customer"),
  never a hex string; on a multi-store stack each drop should read its **own** store. If the store
  hasn't resolved yet it falls back to "the customer" (acceptable, brief). Privacy unchanged (still a
  hash under the hood; the store name isn't customer PII).
  - Confirmed: **1/2** (06-25→30 week — works on single-store jobs, e.g. "Flying Tiger Thai
    Restaurant's customer" 06-26; but fold-in/leg-2 drops stick at "the customer" and never
    resolve — that half is the week entry's Bug #10b, #526-widened scope).

- **🔬 FIX SHIPPED (recognize-only) — dropoff-arrived 'Leave it at the door' card now recognized (#549, PR #574). READ THE LOG / WATCH UNKNOWNs.**
  A dropoff-arrival card whose instruction is *not* "Hand it to recipient" (e.g. "Leave it at the
  door", a refined map pin, "Complete delivery steps") was falling to **UNKNOWN** — the state machine
  got no clean dropoff signal and leaned on the grace window. Now recognized (customer name + address
  **hashed**; the gate-code/instruction text is never stored). **Confirm: 0/2 —** after a dash with a
  "leave at door" / gate-code delivery, grep the captures/log: that arrival screen should recognize as
  a dropoff (not pile into UNKNOWN), and INFO/db must show **no** raw address/gate-code (hashes only).
  This is recognize-only — it does **not** yet flip an "arrived" state (deferred, needs a fresh
  capture to decide), so don't expect a behavior change, just cleaner recognition.

- **🔧 FIX SHIPPED — no more double fly-away bubble on a new/stacked pickup (#566, PR #573). CONFIRM ON DASH.**
  When a pickup started (or a stacked pickup handed off), the "Pickup: <store>" heads-up bubble flew
  out **twice** in quick succession (the second often icon-less). Fixed with a per-task dedupe key.
  **Confirm on dash: 0/2 —** on each new pickup (especially the second store of a stack), the
  "Pickup: <store>" notification should fly out **once**, not twice. A genuine change should still
  show: e.g. heading-to-store → start-shopping may update the bubble (that's expected, different
  state), and a later separate trip to the same store should still announce. If you still see an
  immediate identical double, note the store + whether it was the first or a stacked pickup.

- **🔧 FIX SHIPPED — a pickup no longer borrows a customer identity (#548, PR #572). CONFIRM ON DASH.**
  On a **multi-store stack**, a restaurant pickup screen could bleed the *other* drop's customer onto
  the pickup task (latent — no visible bubble effect, but a data-model defect). The pickup screen no
  longer parses a customer at all. **Confirm on dash: 0/2 —** on a stacked/multi-store order, the
  **pickup** bubble should read the **store/merchant** (never a 6-character customer fragment), and no
  customer should bleed onto the first dropoff. Hard to see directly; mainly verified in the db/log —
  but flag anything where a pickup card shows a customer-looking label.

- **🔧 FIX SHIPPED — dropoff customer fills in the card instead of re-minting it (#565, PR #571). CONFIRM ON DASH.**
  06-21 Walgreens: at the dropoff the bubble showed **"the customer"** for a bit, then when you
  started navigating the card **re-minted** (a fresh card appeared) instead of just filling in the
  name — leaving a dead blank task behind. Fixed so the customer **resolves onto the same dropoff
  card**. **Confirm on dash: 0/2 —** on a normal single delivery, when the dropoff begins it may
  briefly read "the customer" (that's expected until the nav screen carries the name), but it should
  then **fill in the real customer on the same card** — you should **not** see the card visibly
  re-create itself. On a genuine multi-customer stack, each customer should still get its **own**
  card. If you see a re-mint or a leftover "the customer" card that never resolves, note the store +
  grab the dropoff capture sequence. (Earnings were never affected by this — it was cosmetic/ledger
  hygiene.)

- **🔧 FIX SHIPPED — add-on offer no longer fabricates a $0 "completion" (#564, PR #570). CONFIRM ON DASH.**
  06-21 seq98: accepting a **mid-stack add-on** offer (a new order added while a pickup was in
  flight) misrecognized the offer's transient frame as a delivery summary and logged a **fake $0,
  customer-less completion** of the not-yet-picked-up store — corrupting earnings. Fixed two ways
  (recognition rejects offer markers; the state machine only completes a task that reached the
  **dropoff**). **Confirm on dash: 0/2 —** when you **accept an add-on/stacked offer while still at
  or heading to a store** (esp. a "High paying offer!" add-on), watch that **no** bogus completion
  pops (no "$0.00" delivery, no premature "delivered" for a store you haven't dropped), the earnings
  total **doesn't jump then stay wrong**, and the original pickup keeps its identity (no duplicate
  store task). If a phantom completion still appears, note the add-on's store + the store you were
  working, and grab the capture sequence around the accept.

- **🔧 MERGED — realistic $/hr + score on Shop & Deliver offers (#556). CONFIRM ON DASH.**
  The time model now estimates a shop by its item count at the dasher's shopping pace (seed 0.8
  items/min, learned from your own completed shops) instead of a flat 7-min overhead — so a grocery
  run no longer reads a wildly inflated `$/hr` / "AWESOME" score. **This is a verdict-mover** (it
  changes accept/decline rankings, not just the readout). **Confirm on dash: 0/2 —** on a Shop &
  Deliver / grocery / ACV offer (e.g. H-E-B, Sprouts) the card `$/hr` should look **realistic**
  (~$25–40/hr range, not $100+), the score should be sane, and a normal restaurant **pickup** offer
  should read the **same as before** (only shops changed). After a few shop dashes, the learned pace
  kicks in — grep the log for `ShopRate` to see it recording (`N items / M min = X/min`). If a shop
  still reads inflated, note the offer's item count + quoted miles + the `$/hr` shown.
  - Confirmed: **1/2** (06-25→30 week — 06-30's 41-item H-E-B priced $9.94/hr, a sane decline;
    shops all week in a $14–27/hr band; `ShopRate` learning lines ×11. Caveat: a 44-item shop
    estimated ~83 min vs ~2.5 h actual — seed still optimistic on giant shops).

- **🔧 MERGED — dropoff store recognition + pickup-matched resolution (#526/#553). CONFIRM ON DASH.**
  A dropoff shows the store **resolved from the job's pickups** (single → the pickup's store;
  multi-store stack → matched per drop), not inherited from the last active pickup.
  - Confirmed: **1/2** (2026-06-20 — singles + **same-store** double stacks resolved correctly
    [Panda Express, Parry's Pizzeria]; verified in the db + `ShadowProjector` log).
  - **Multi-store stack** (different stores): the 06-20 Peng's+Little Caesars stack left both drops
    `None` (safe, not wrong) — **now fixed by #557 (branch `feature/557-multistore-dropoff-store`,
    pending merge):** the dropoff running-key forms (`Little Caesars (0164-0045)`, place-name parens)
    now parse + resolve to their pickup. On a multi-store stack each drop should show its **own**
    store, **not both the same** and **not the other stop's**. If a drop shows a wrong store or `None`,
    capture the dropoff frames + note the stack's stores.
    - Confirmed: **1/2 for the #557 fix** (06-29 Sally Beauty + Panda Express stack — all four
      tasks correctly store-attributed, distinct customer hashes; the remaining fold-in-drop gap
      is the week entry's Bug #10b, not a #557 regression).

- **🔧 FIX SHIPPED — offer card icon badges + co-icon-text shop badge [cart N] (#461, PR #531). CONFIRM ON DASH.**
  The offer card's badges are now **icons** (red card, alcohol, large order, priority, etc., tinted by
  brand color), and the **Shop & Deliver** badge is the shopping-chat **cart icon + the item count**
  (`[🛒 N]`) — the count moved off the $/hr hero onto the badge. **Confirm on dash: 0/2 —** on a
  **shop** offer (e.g. Sprouts/CVS) the card shows the cart badge with the **true item count** (a
  25-item shop reads **25**, not 26), it shows **while the offer is live** (not just after), and a
  pure-pickup (or pickup stack) shows **no** cart/count. Other badges should render as their icons. If
  a count is off by the number of pickups in a stack, or the shop badge doesn't show live, note the
  offer's order mix.

- **✅ FIX SHIPPED — same-store add-on no longer re-mints the task (#499 / #503 slice 2). CONFIRM ON DASH.**
  The task lifecycle now **resumes a prior subtask** instead of re-minting on a phase switch / after an
  offer interlude (re-match by store for pickups, customer address for dropoffs). **Confirm on dash:
  0/2 —** on a same-store shopping add-on (e.g. an offer accepted mid-shop at the same store), the shop
  task should **keep its identity and fold the add-on in** (combined item count bumps on the *same*
  card), not spawn a second/fresh task. Also watch a stacked order with *different* stops: those must
  still be **distinct** tasks. If a same-store add-on still re-mints, capture the shop→offer→shop frame
  sequence + note the timing.

- **✅ FIX SHIPPED — dropoff created from the offer; the premature "Customer" card root (#503 slice 3). CONFIRM ON DASH.**
  The drop-off is now a known subtask created at **offer-accept** (customer TBD), and the dropoff screen
  RESOLVES the real customer onto it — instead of a phantom dropoff minted before its customer is known.
  **Confirm on dash: 0/2 —** a drop-off card should show the **real customer** (a short 6-char hash
  code); an unresolved one shows **"the customer"** (lowercase, briefly, never the name-like "Customer")
  and must not **linger** or appear as a **premature/duplicate** drop-off card. If a phantom/duplicate
  dropoff or a stuck "the customer" card shows, capture the dropoff frame sequence + note the timing.
  (Single-order this build; **multi-drop is slice 3b, not yet shipped** — a stacked/GoPuff multi-drop
  may still mis-handle the extra dropoffs.)

- **🔧 FIX IN FLIGHT — phantom + over-minted dropoff tasks cleaned up (#498, PR #521). CONFIRM ON DASH.**
  Two state-layer guards from the 06-17 capture investigation: (a) a dropoff frame that parses **no
  customer at all** (a transient confirm/arriving screen) no longer mints a fresh **identity-less**
  dropoff — the "the customer" card that immediately completes (06-17 task-9 on a **single H-E-B**
  order); (b) a **drifting dropoff address** with the **same customer name** no longer splits one drop
  into two tasks (06-17 task-39/-40). **Confirm on dash: 0/2 —** on a **single** order, the dropoff
  card should resolve to the **real 6-char hash** with **no** brief "the customer" card flashing/
  completing alongside it, and exactly **one** dropoff card per real stop (no duplicate). The phantom
  also silently fired spurious `DELIVERY_COMPLETED`s — watch for any **$0.00 PAID** card that mints
  with no real delivery. If a phantom/duplicate dropoff still shows, capture the dropoff frame sequence
  + the `app_state_snapshots` for that order. (Stacks are still #503 slice 3b — extra dropoffs on a
  multi-drop may still mis-handle, separate from this.)
  - Confirmed: **2/2 ✅ VALIDATED** (2026-06-19 + 2026-06-20 desk analyses — both dashes: 0
    null-customer/$0.00 dropoffs; every completion has a distinct customer hash + non-null pay).
    Safe to retire from this checklist.

- **🔧 FIX IN FLIGHT — a delivery can't be logged/counted twice (#518, PRs #520 + #522). CONFIRM ON DASH.**
  Two halves of the spurious-`DELIVERY_COMPLETED` bug: PR #520 stopped a **prior job's** task
  re-completing under a new job (cross-job leak); PR #522 makes `DELIVERY_COMPLETED` **idempotent per
  task** so a re-shown receipt (`PostTask → nav → PostTask → nav`) can't fire the **same** delivery
  twice. 06-17 evidence: two **real** deliveries ($23.62, $11.20) were each logged twice → doubled
  earnings. **Confirm on dash: 0/2 —** each completed delivery should produce **exactly one** "Saved:
  $X" bubble and **one** completed card, and the **session-earnings total must match the sum of the
  real deliveries** (no doubling). Watch especially when the receipt screen is shown, dismissed, and
  re-shown. If a delivery double-counts, capture the `app_events` (`DELIVERY_COMPLETED` rows) +
  `app_state_snapshots` for that order.
  - Confirmed: **2/2 ✅ VALIDATED** (2026-06-19 + 2026-06-20 — both dashes: exactly 1
    `DELIVERY_COMPLETED` per job, accepts pair 1:1 with completions, earnings reconcile to the cent
    (06-20: $130.23 = $130.23); each stack's drops → 1 combined receipt, expected, not a double-count).
    Safe to retire. (Also covers the #517 ghost-offer guard — all offers carried non-null pay both dashes.)

- **🔧 FIX IN FLIGHT — multi-drop stack: Job owns ordered dropoffs, routed by customer name (#503 slice 3b, PR #523). CONFIRM ON DASH.**
  The structural multi-drop fix: an offer pre-creates **one dropoff placeholder per order**, and each
  dropoff screen routes to its own customer subtask by the **stable name hash** (addresses drift).
  **Confirm on a STACKED / multi-drop dash: 0/2 —** a 2-order stack should show **exactly two** dropoff
  cards, each resolving to the **right customer** (6-char hash), and the counts/earnings should match
  **two** deliveries (not 4, the 06-17 Jim's-stack symptom; not 1). Returning to an earlier drop (or
  the app re-showing it) should **resume** that drop, not spawn a duplicate. This is the real test of
  the 06-17 dropoff thread end-to-end — needs an actual stacked order (or a GoPuff multi-drop, though
  GoPuff recognition is still #501). If a drop duplicates, mis-resolves, or a phantom appears, capture
  the dropoff frame sequence + `app_state_snapshots` for the stack.

- **🔧 FIX SHIPPED — live $/mi on the task card, read off the Job (#503 deliverable 2, PR #525). CONFIRM ON DASH.**
  The live pickup/delivery card's "Running at $X/hr" co-hero now shows a **$Y/mi** sub line (fixed
  efficiency off `Job.blendedDistanceMiles`; "—" when no offer distance is known, never `$X/0mi`).
  **Confirm on dash: 0/2 —** while driving a delivery the card should show a sensible **$/mi** under the
  $/hr (e.g. `$1.85/mi`), it should **persist** through nav→arrival (distance doesn't erode like the
  $/hr does), and on a **stacked/add-on** it should reflect the **summed** job distance (not a single
  offer's). If $/mi shows "—" on a normal offer (that carried a distance), or looks wrong on a stack,
  note the offer's quoted miles + net pay.

- **🔧 FIX SHIPPED — GoPuff (Drive) bin-scan mints the pickup arrival (#501, PR #530). CONFIRM ON DASH.**
  The warehouse leg used to be all-UNKNOWN with **no `PICKUP_ARRIVED`** (the machine jumped
  nav→confirmed). Now the GoPuff **bin-scan steps** screen ("Pickup steps" / "Scan barcodes on" /
  "Complete pickup") is recognized as a branch of `pickup_steps` and declares `task:pickup:arrived`;
  the wait-survey + barcode-scan screens are recognized too. **Confirm on a GoPuff dash: 0/2 —** the
  bubble should show the pickup ARRIVING at the warehouse (a Dwell timer at store), exactly **one**
  pickup arrival for the batch (not zero, not two), and the bin-scan/scan-fail screens shouldn't sit
  on a stale card. If the arrival never fires or fires twice, capture the bin-scan frame sequence +
  `app_state_snapshots`. (Still UNKNOWN, deferred to a follow-up: the zone-arrival "Navigate to zone"
  card and the dropoff multi-order-confirm — those are noise, the arrival is the load-bearing fix.)

- **📸 CAPTURE NEEDED — GoPuff (Drive) screens, to finalize the #501 rules.** The 06-14 deep-dive
  enumerated the GoPuff flow (all inside the DoorDash app — there is no separate GoPuff app) from real
  captures, but three things would help finalize the rules. **On the next GoPuff dash, drop these into
  `snapshots/INBOX/`:**
  1. **The GoPuff bin/scan-steps screen** ("Pickup steps" / "Scan barcodes on N items" / "Pick up
     order in Bin #N") — confirm it always follows an explicit "Arrived at store" tap (so #501 mints
     `PICKUP_ARRIVED` from the right screen, not twice).
  2. **A GoPuff offer card** — does it ever show an **Accept** button, or is acceptance always the
     `accept_constraint_layout` control? (affects whether the Accept RuleAction can be aimed).
  3. **A 3+ order GoPuff batch's bin screen** — does every order carry a customer name
     (`order_cx_name`)? The per-drop dedupe depends on a stable per-order hash.
  - **(Low-priority curiosity, NOT a known issue):** we saw a click labeled "Open digital Red Card"
    but never captured what it opens. If it's easy, capture that screen once just to *see* what's on
    it — there is **no evidence it exposes anything sensitive**; this is only to confirm, not because
    a leak is known. (#504 was filed on a bad assumption about this and has been closed.)
  - Captured: 0/1 each (these are capture asks, not pass/fail validations).

- **✅ FIX SHIPPED — "ghost offer" with EMPTY parse logged as a card (#498). CONFIRM ON DASH.**
  Fixed by gating the `offer_popup` rule on a parsed `payAmount` (a blank/chrome-only frame with no
  pay is no longer recognized as an offer; proven by `GhostOfferReplayTest` replaying the real
  16:31:52 ghost capture — now classifies UNKNOWN, emits no OFFER_RECEIVED). **Confirm on dash: 0/2 —**
  no blank-store / `$-2/hr` Offer card should appear (mid-offer, between offers, or right after an
  accept), while **real offers still recognize normally**. If a blank card still appears, capture the
  `offer_popup` frame + the offer event. *(Below is the original watch context, kept for reference.)*
  A phantom Offer card appeared in the stack (between Mello Mushroom and Pei Wei) with **no store, no
  pay, no miles** — Score 24, `$-2/hr`, Net `-$0.36`, outcome **Timed out**. Hypothesis: a partial
  `offer_popup` frame whose chrome (Decline + Accept/footer id) satisfied `require` before the content
  (store `display_name` / pay `$`) rendered → empty parse, still scored + logged as `OFFER_TIMEOUT`.
  The morning's dedupe/self-recognition fixes wouldn't catch it (distinct empty hash; real DD popup,
  not our overlay). **What to watch:** any Offer card (live or in the last-dash stack) that shows a
  **blank store and no pay/miles** — note when it appears (mid another offer? between offers?) and
  **grab the `offer_popup` capture + `OFFER_TIMEOUT` event** so we can confirm the partial-render tree
  and decide a validity/settle gate. (See 2026-06-13 log entry #1.)
  - Sightings: 1 blank-offer (2026-06-13, desk/screenshot). **2026-06-14 (dash #1):** no blank-store
    *offer* card recurred — but the sibling **premature drop-off card** (2026-06-13 #1, same
    unsettled-frame class) DID recur, so the partial-render root is real. **2026-06-14 (dash #2,
    ~16:35–16:36 UTC):** the **blank-offer card itself RECURRED** — a preframe/chrome of the offer
    recognized as an Offer with **no value**, again **`$-2/hr`** (same Net `-$0.36`-class figure as
    the 2026-06-13 sighting — i.e. the all-zeros economics fallback). So now **2 separate
    blank-offer sightings** on top of the recurring premature-dropoff sibling — the empty-offer
    variant is confirmed reproducible, not a one-off. Grab this dash's `offer_popup` capture +
    `OFFER_TIMEOUT`/offer event near 16:35 UTC. **2026-06-15 (~00:33–00:34 UTC, dash #2 cont.):**
    **new trigger — a ghost offer fired immediately AFTER the dasher ACCEPTED an offer.** The app
    "acted like it got a new offer" right on accept, again blank / **`$-2/hr`** all-zeros. So the
    blank-offer card isn't only a pre-render of an *incoming* popup — it can also spawn on the
    **post-accept transition** (a 3rd blank sighting, new trigger). Hypothesis extension: the
    accept→job transition may re-emit/observe a stale or chrome-only `offer_popup` frame that
    re-satisfies `require` with no content → a phantom "new offer." Grab the `offer_popup` +
    offer/accept events near 00:33 UTC to see whether the ghost frame is a leftover of the
    just-accepted offer or a genuinely new partial popup.
  - **Triaged 2026-06-15 → [#498](https://github.com/sjtrotter/DashBuddy/issues/498)** (recognition
    rejects incomplete frames — the `offer_popup` rule must `require` the scored fields and an empty
    parse must not become an offer; the all-zeros economics is `sha256("null|null|null|")`). The
    post-accept ghost-offer trigger also feeds **#503** (Job container — the accept→job transition
    shouldn't re-observe a chrome-only offer frame).

- **Offer card surfaces Shop & Deliver: item count in the hero row + a SHOP badge (#461 a/b).**
  The item count moved from a small footer caption up to the hero row (beside the score ring /
  $/hr), and a Shop & Deliver offer now shows a "Shop & Deliver" badge pill. On a shop offer:
  working = the item count is prominent in the hero and a "Shop & Deliver" pill shows; a plain
  pickup offer shows NO shop pill. Broken = item count missing/duplicated, or the shop pill on a
  non-shop offer. (**#461 stays open** for part (c) — the finished/PostTask card showing the
  order type, which needs offer→job→delivery data flow.)
  - Confirmed: 1/2 (single-order). **2026-06-14 (DoorDash):** the Shop & Deliver badge shows and the item
    count is up in the hero on a **single** order. **FOUND BROKEN on STACKED orders:** the hero shows the
    **# of stacked orders, not the # of items** (logged 2026-06-14 #1). Single-order half advanced to
    1/2; the stacked-count is a tracked bug. (See also the design rethink, 2026-06-14 #2.)

- **7-Eleven / alcohol "Verify items" pickup screen now recognized (#462, first slice).**
  The store "Verify items for <name>" screen (with "Do not open sealed bags" / "Can't verify
  items" / the item list) classified UNKNOWN — the 7-Eleven alcohol pickup from the 2026-06-12
  dash (field-log #12). It's now a recognized `pickup_verify_items` screen mapped to
  pickup-arrived (no customer-name parsing). On a retail/alcohol pickup: working = the bubble/log
  shows a pickup screen (not UNKNOWN) on the verify-items step and the flow stays on pickup.
  Broken = still UNKNOWN, or it mis-steps the flow. (**#462 stays open** — this is one of ~30
  recognition gaps from that dash; the rest are a larger effort.)
  - Confirmed: 0/2

_(The "Delivery for \<name\>" dropoff / alcohol item (#462/#460) left the checklist on the
06-25→30 week: the alcohol half got one clean sighting (06-30 CVS — ID-check recognized, scanner
capture instruction-text-only, events hashed), but the item's broken-criterion — "raw recipient
name/address appears anywhere" — was tripped by raw PII found in recognized/UNKNOWN capture
envelopes. See that entry's Bug #7.)_

- **Batch-1 recognition gaps from 2026-06-12 now recognized (#462).** Twelve more screens that
  fell to UNKNOWN are now recognized (mostly recognize-only — no flow change): pickup steps
  (`Pickup steps` / `Take receipt photo`), pickup "what's causing your wait" survey, pickup
  "Select an issue" + "Resolution options" menus, shopping intro-message / item-status /
  wrong-item-scanned, post-delivery "How did this delivery go?" + "Feedback about your safety",
  the alcohol ID-verify instruction checklist + "4 of 4" complete step, and the
  "You're all set to receive offers" account-checkup. Also the delivery-complete dialog now
  matches "Confirm **delivery** was completed" (it only matched "Confirm order was completed"
  before). On a dash: working = hitting any of these screens shows a recognized screen (not
  UNKNOWN) and the pickup/dropoff flow does NOT mis-step (these are recognize-only, so the
  task state should be unchanged). Broken = still UNKNOWN, or the flow jumps/regresses when one
  appears.
  - Confirmed: 0/2

- **Batch-2 recognition gaps — idle/lifecycle (#462, now CLOSED).** The last UNKNOWN screens are
  recognized: the **"Navigate to zone / We'll look for orders along the way / Spot saved until …"**
  repositioning card (now a recognized idle screen, and the "Spot saved until HH:MM" countdown
  should populate); the **scheduled-dash slot picker** ("Start time / End Time"); the **dropoff
  reminder** ("Deliver to door of … / Got it"); the **pickup QR-confirm** ("Confirm that the code
  was scanned"); and the **help/support menu** ("Get an account checkup / Dashing FAQs"). Working =
  these show recognized (not UNKNOWN); the repositioning card shows a spot-save countdown. Broken =
  any still UNKNOWN, or the navigate-to-zone card mis-reads the idle state.
  - Confirmed: 0/2

- **Order-ready push notification now recognized (#462).** The "‹name›'s order is ready for pickup
  at ‹store›" push arrives on the `dasher-notification-background` channel (it was UNKNOWN before).
  Working = when DoorDash sends the order-ready notification, the log shows it classified
  (`ORDER_READY`), not UNKNOWN — and the customer name is never stored (the rule logs a constant).
  Broken = still UNKNOWN, or a customer name shows up parsed.
  - Confirmed: 0/2

- **Pickup/Delivery task cards redesigned to the co-hero design (#460/#324).** The task cards
  no longer show a single countdown + caption — they now have the **dual co-hero**: LEFT = the
  phase timer (counts DOWN to the deadline as "To go", then flips to "Dwell" counting UP once you
  arrive, with "at store"/"at door"), RIGHT = **"Running at $X/hr"** — the live realized rate from
  the accepted offer's net pay ÷ time, which **holds until the deadline then erodes** (shows a ↓
  and "dropping"). Below: an "arrived N early/late · deliver by H:MM" caption, a red **"Below your
  floor"** banner once overdue + the rate drops under $12/hr, the shop pace block (Shop & Deliver),
  and the store/customer detail line. On a dash: working = during pickup/dropoff the live card
  shows both heroes ticking; the $/hr roughly matches the accepted offer's $/hr and drops if you
  run late; "Running at —" only when the offer had no economics. Broken = $/hr shows "—" on a
  normal accepted offer, the timer doesn't flip to Dwell on arrival, values clip/overflow the
  bubble width, or the $/hr doesn't erode past the deadline.
  - Confirmed: 1/2 (pickup card). **2026-06-14 (DoorDash, 1 dash):** the **pickup** co-hero rendered
    (timer + "Running at $/hr") — dasher flagged it "maybe not wired right" though. The **drop-off**
    `$/hr` still reads nil (FOUND BROKEN — the blend doesn't survive into the dropoff leg, 2026-06-13 #2).
    Pickup half advanced to 1/2; drop-off half tracked as a bug.

- **Bubble keeps showing the last dash after it ends / after a crash (#459).**
  The bubble's chat + card stack used to go EMPTY after a dash ended (8b: collapse it >5s then
  reopen) or after a crash with no active dash (8a) — the fallback dash id was a volatile
  in-memory latch. It's now sourced durably from the event log (most-recent dash). On a dash:
  end a dash, collapse the bubble for >5s, reopen → working = the chat + completed cards of the
  just-finished dash are still shown (not empty); start the next dash → it switches to the new
  dash. Also force-stop/crash right after a dash and reopen → still shows the last dash. Broken =
  empty chat/cards after dash-end-then-reopen, or the wrong dash shown.
  - Confirmed: 1/2. **2026-06-14 (DoorDash):** after the dash ended the bubble **kept showing the last
    dash** (chat + completed cards), not empty — clean confirmation of the #473 durable fix. One more.

- **Pickup/Delivery card deadline reads cleanly — no double "by" (#460).**
  The deadline caption read `till pickup-by · by 17:10` (two "by"s); now `till pickup · by 17:10`
  / `till deliver · by 17:10`. Desk- or dash-verifiable on any pickup/delivery card. (The
  separate pickup/delivery card visual-parity redesign stays tracked in #460.)
  - Confirmed: 1/2. **2026-06-14 (DoorDash):** caption reads **fully fixed/different from before** — no
    double "by". One more to validate.

- **No transient double drop-off card at the door (#458).**
  On an arrival-bearing dropoff (hand-it-to-customer / photo / PIN) the same delivery briefly
  rendered as TWO cards during the at-door window (a frozen completed copy + the live one). The
  stack now drops the frozen twin when it shares the active card's id. On a dash: working = at
  the customer's door you see exactly ONE delivery card (then the single frozen card + the live
  "Saved" receipt after you complete). Broken = two identical delivery cards stacked at the door.
  - Confirmed: 0/2. **2026-06-14 (DoorDash):** an extra drop-off card DID appear this dash — but it's the
    **premature/unsettled-frame** class (2026-06-13 #1), *not* the frozen-twin overlap this #458 fix
    targets, so this stays 0/2 (the frozen-twin case wasn't disambiguated). The recurrence is tracked
    under 2026-06-13 #1 / the ghost-frame watch — pull the dropoff capture to tell the two apart.

- **"Saved: $X" bubble shows the dollar sign now (#456).**
  The post-delivery earnings bubble rendered `Saved: 5.50` (no `$`) because the state layer had
  its own money formatter that omitted it. Both local formatters are gone — money now formats
  through one `:domain` `Formats.money` SSOT. On a dash, after a delivery: working = the "Saved"
  bubble reads `Saved: $5.50` (with the `$`); the dash-summary "Session Ended. Total: $X" and
  the offer notification's `$/hr`/`$/mi` should all still read correctly. Broken = a missing or
  doubled `$`, or a wrong decimal.
  - Confirmed: 1/2. **2026-06-14 (DoorDash):** the "Saved" bubble shows `$X.XX` (with the `$`, 2 decimals)
    on **all** of them now — confirmed. (The separate "tip added" bubble is still a raw float — 2026-06-13
    #3.) One more to validate.

- **Sensitive model corrected — block the DASHER's data + ID/signature IMAGES; HASH customers (#463/#485).**
  The privacy rule is now: block the **dasher's own** sensitive screens (DasherDirect Savings /
  banking — plaintext balances) and the **document-image capture surfaces** (the license-SCAN
  camera + the SIGNATURE pad/handoff), regardless of whose; but **recognize** the alcohol
  **ID-CHECK instruction** ("Identity verification … matches the recipient") and the alcohol
  **arrival card**, with the customer name/address **hashed** (we hash customers, we don't block
  them). On a dash:
  - **Banking:** DasherDirect → Savings, small transfer → NO capture (log shows the sensitive gate).
  - **Alcohol delivery (21+):** the license-SCANNER and the SIGNATURE pad screens produce **NO
    capture**; but the ID-check instruction + the arrival card + the verify-step screens **recognize
    normally**, and the customer name appears only as a HASH (never raw) in any log/capture.
  Broken = a Savings/Transfer balance OR a license-scan/signature screen shows in captures/; OR the
  alcohol arrival/ID-check stays UNKNOWN / mis-steps the flow; OR a raw customer name/address
  appears anywhere.
  - Confirmed: 0/2

- **Engine latency + dedupe pack (#436).**
  Four behaviors to watch: (a) accepting/declining an offer FAST (inside ~1s of the verdict
  landing) should no longer pop a stale Accept/Decline heads-up afterwards; (b) offer verdicts
  should land a touch quicker (config no longer read cold per offer); (c) relaunching the app
  mid-dash (non-crash restart) should NOT duplicate session-start bubbles or re-log events on
  the next screen; (d) nothing else regresses — notifications still post normally when the
  offer is left alone. Broken = stale heads-up after resolving an offer, duplicated chat
  entries after an app restart, or a missing offer notification.
  - Confirmed: 0/2. **2026-06-12 (DoorDash, partial):** dasher reports offer Accept/Decline feels
    **fully quick — no perceptible delay** (loosely supports (b) "verdicts land a touch quicker").
    The (a) stale-heads-up-after-fast-resolve, (c) restart-dedupe, and (d) sub-cases were NOT
    deliberately exercised — so this stays 0/2 until those are checked. (See 2026-06-12 log entry #10.)
    **2026-06-14 (DoorDash):** no stale Accept/Decline heads-up observed after resolving offers (loose
    support for (a)); (c)/(d) still not deliberately exercised. Stays 0/2 pending a clean (a)/(c) check.

- **Per-offer dedupe now engages (#427).**
  Offer screenshot/log dedupe keys used to resolve to one shared literal, so a second distinct
  offer within 60s was silently swallowed. Now keyed per-offer via `{parsedHash}`. Watch on a
  busy dash (with Evidence master + offers enabled, see #426 item): two different offers
  arriving close together should BOTH capture; the same offer re-rendering (collapse/expand,
  re-observation) should still capture only once. Broken = missing capture for a distinct
  second offer, or duplicate captures of one offer inside the same minute.
  - Confirmed: 0/2. **2026-06-14 (DoorDash):** dasher believes it's working but couldn't verify in the
    field — needs a desk check of this dash's `captures/` (two distinct offers close together → both
    captured?). Stays 0/2 until the log confirms.

- **Evidence Locker settings are now real (#426).**
  Screenshots (offer / delivery / dash-summary PNGs in Pictures/DashBuddy) previously fired
  unconditionally; they are now gated on the Evidence settings, whose master toggle defaults
  OFF. On a dash with settings untouched: working = NO new PNGs appear at all. Then flip
  Master Record + a category on mid-dash: working = only that category's screenshots appear.
  Broken = PNGs appear with master off, or an enabled category stops capturing (look for
  "Evidence capture suppressed" in logs with an unexpected category).
  - Confirmed: 0/2

- **Receipt grace — delivery completion is now deferred ~2.5s (#431 pt 2).**
  The delivery-summary (receipt) screen no longer retires the task instantly; it arms a short
  authoritative grace exactly like the dash summary. Watch: (a) the "Saved: $X" receipt bubble
  fires exactly ONCE per delivery (the expanded re-observation used to be able to double-fire
  it); (b) stacked orders still split cleanly — receipt → next pickup must show the new task
  immediately with the old one logged; (c) a receipt that flashes mid-dropoff (misrecognition)
  no longer kills the live task — the task card should survive. Broken = double "Saved" bubble,
  a delivery missing from the log, or the bubble's task card stuck on the finished delivery
  well past ~3s after the receipt.
  - Confirmed: **2/2 for sub-case (a)** — once-per-delivery receipt — **VALIDATED** (moved to the
    2026-06-14 entry). **2026-06-12:** two deliveries → one "Saved" each. **2026-06-14 (DoorDash):** no
    double "Saved" receipt anywhere across the dash. ⏳ **Still open:** (b) stacked-order
    receipt→next-pickup split and (c) receipt-flash-mid-dropoff survival weren't exercised on either
    dash — keep watching those two on the next stacked/edge dash. (The `$`-sign bug noted here on 06-12
    is fixed — #456/#466, confirmed 2026-06-14.)

- **Uber sensitive screens now blocked + UNKNOWN-capture scrub (#432).**
  Uber finally has matcher-layer sensitive rules (wallet / Instant Pay / cash-out / bank /
  identity). On a dash, briefly open Uber's earnings/wallet area: working = the app treats it
  as sensitive (no capture, no state change, log shows the sensitive gate) and normal offer
  recognition is unaffected. Also new: UNKNOWN screens whose text contains sensitive markers
  are no longer captured for triage (PipelineStats logs `unknownScrubbed`), and the pipeline
  drops all frames until rulesets finish loading at startup. Broken = an Uber wallet screen
  shows up in captures/, or offer screens misclassify as sensitive (keywords too broad —
  capture the screen text).
  - Confirmed: 0/2

- **Session-end grace — summary no longer ends the dash instantly (#431).**
  The dash-summary screen now arms a ~2.5s authoritative grace (cancellable by a task-flow
  frame) instead of ending the session on the spot, and grace commits fire on a timer instead
  of waiting for the next event. Watch: (a) the post-dash summary still attributes to the
  right session (chat/cards/totals) — just ~2.5s later; (b) NO spurious mid-dash session
  splits (the old failure was one misrecognized frame = split); (c) leaving the app right
  after going offline still logs DASH_STOP promptly (timer-driven) with endedAt ≈ when you
  went offline. Broken = duplicate DASH_START/STOP pairs, summary attributed to a new empty
  session, or a session lingering long after the dash.
  - Confirmed: 1/2. **2026-06-14 (DoorDash):** dash ended cleanly — summary on the right session, no
    spurious mid-dash splits, no lingering session (supports (a)/(b); (c) leave-app-after-offline not
    explicitly checked). One more to validate.

- **Timeline storeHint now parses + pickup_picked_up rule newly matchable (#433).**
  Two rule fixes from mojibake literals: (a) timeline task rows should now carry store names
  (watch the dash-controls overlay's task chain — logs/cards referencing timeline tasks should
  name the store, not blank); (b) the `pickup_picked_up` screen rule could NEVER match before
  (its require contained a mangled literal) — on the confirm-pickup/loading screen, watch
  whether it now classifies (bubble/log shows pickup_picked_up instead of UNKNOWN) and
  **capture it** — this intent has zero corpus snapshots.
  - Confirmed: 0/2

_(The #425 **in-bubble** Accept/Decline item was **VALIDATED** (2/2) on the 2026-06-14 dash — both
Accept and Decline registered on DoorDash — and moved to that session's entry below. The
**notification-shade** buttons remain broken and are tracked separately by **#457** / 2026-06-12 #11.)_

- **Post-dash HUD: frozen summary + consistent chat (#367, PR pending).**
  Two visible fixes after a dash ends: (a) the "Last session" Duration on the idle bubble is
  now FROZEN (it used to keep growing while you sat idle — check it shows the real dash length
  and stays put); (b) the chat ticker and the card stack now both show the finished dash
  (the ticker used to go empty the moment the dash ended while cards stayed). Also: platform
  toggles/screens now stop collecting flows while the app is backgrounded — no user-visible
  change expected, just confirm nothing looks stale when foregrounding.
  - Confirmed: 0/2.
- **Tree ingestion now bounded — confirm no real screen trips the caps (#363, PR #391).**
  Accessibility trees deeper than 60 levels or larger than 4,000 nodes truncate with a loud
  log line. The caps carry 2×/10× margin over the corpus, so a normal dash should NEVER hit
  them. Post-dash: grep the log for "Tree ingestion truncated" — any hit means a real DoorDash
  screen is bigger than the corpus suggested and the caps need raising (file it).
  - Confirmed: 0/2.
- **UNKNOWN capture volume should drop materially (#360, PR #388).**
  UNKNOWN frames now dedup by content hash in a rolling seen-set (animations/list churn
  capture once instead of per frame), with a 200-per-process cap (logged loudly when hit).
  Post-dash check: count files in the capture INBOX vs a May session of similar length —
  the May baseline was ~66% UNKNOWN; expect a large drop. Also confirm genuinely NEW
  unknown screens (any screen you visited that DashBuddy doesn't know) still produce a
  capture, and grep the log for the cap warning — hitting 200 on a normal dash would mean
  the suppressor is too weak.
  - Confirmed: 0/2.
- **HUD numbers/timers re-plumbed through one shared format/time kit (#358, PR #386).**
  All bubble-card money, distance, countdown, and duration strings now come from
  `:core:designsystem` helpers, and the phase chip switched to brand tokens. On a normal dash,
  glance-check: (a) card money/mi/min strings look exactly as before (en-US should be visually
  unchanged); (b) the offer countdown and elapsed timers still tick per second; (c) the phase
  chip color now MATCHES the card's status colors (OFFER chip = same blue family as the offer
  status badge, PAID = green) instead of the old purple/teal M3 roles.
  - Confirmed: 0/2.
- **Platform toggles now take effect live — no app restart (#356, PR #384).**
  All notification/accessibility gating now reads one shared enabled-platforms state. To check:
  mid-session, toggle a platform OFF in DashBuddy settings — its notifications should stop
  reaching the HUD/log immediately (next notification, not next restart); toggle back ON and
  they resume. If convenient, also note whether gating still works after Android kills/rebinds
  the notification listener (e.g. after a long screen-off period) — the old code froze gating
  at the last value when that happened.
  - Confirmed: 0/2.
- **Event log reworked: domain AppEvent + transactional insert + obs-derived timestamps (#354/#300/#119, PR #382).**
  The bubble HUD's completed-card stack now renders from payloads decoded at the repository
  (was: Gson inside the mapper), `app_events.occurredAt` is the observation timestamp (was: wall
  clock at execution), and each event row + its idempotency mark commit in one transaction. To
  check during a normal dash: (a) the **completed cards** (Awaiting → Offer → Pickup → Delivery →
  PostTask) still populate with store names, pay, and evaluation chips exactly as before;
  (b) card **timestamps/durations** look right (obs-derived times should match what you saw on
  screen, not when the DB write happened); (c) after any crash/restart mid-dash, **no duplicate
  events** — the card stack shouldn't show a phase twice (this was #300's duplicate
  DELIVERY_CONFIRMED). Post-dash, a quick `app_events` query confirming one row per phase
  boundary seals it.
  - Confirmed: 0/2.
- **Notification text now formatted (verdict bold/colored/larger, headline bold) (#110, PR pending).**
  The heads-up offer notification's text is now an Android `SpannableString` — verdict word (ACCEPT /
  DECLINE / REVIEW) bold, ~1.2× size, colored good/warn/bad; the `$X/hr net` headline bold. To check:
  on an offer, **look at the heads-up notification** and note what actually renders — (a) is the
  verdict **bold + larger**? (b) is it **colored** (green/amber/red)? `MessagingStyle` on Android 12+
  may re-theme/strip the **color** even when bold survives — so report specifically whether the color
  shows. If color is stripped, the line still reads fine; we'd then weigh a `BigTextStyle` variant
  (more reliable spans, but can't coexist with the bubble's MessagingStyle).
  - Confirmed: 0/2.
- **Self-recognition fixed: our own bubble is no longer parsed as a DoorDash offer (#4, PR pending).**
  Root cause of the 2026-06-09 offer flip-flop: when the bubble was the active window over DoorDash,
  our own overlay got snapshotted, mislabeled `doordash`, and matched `offer_popup` → a phantom
  re-eval (the spurious DECLINE-6 / "22.5 mi"). Now active-window snapshots are attributed to the
  window's real package (our overlay is dropped), and the offer rule demands the `accept_button`
  structure our overlay lacks. To test: on an offer, **open the bubble** over the DoorDash offer →
  confirm the verdict / notification / spoken read **stay stable** (no flip to DECLINE, no re-eval)
  while the bubble is up. Watch the log for `🚫 Skip active window: non-target pkg=cloud.trotter.dashbuddy`
  (proof our overlay is being dropped) and **no** second `offer_popup` classification. Real orders.
  - Confirmed: 0/2.
- **Offer heads-up notification with Accept/Decline (#110 surface pivot, PR pending).** Since the
  bubble can't auto-expand from the background, an offer now fires a **heads-up notification** showing
  the condensed card (`ACCEPT · $22/hr net` / `Net $22 · 12.9 mi · $1.74/mi · Score 74 · H-E-B`) with
  **Decline** + **Accept** action buttons. Confirm: (1) the notification **pops as a heads-up** while
  you're in DoorDash (it should, unlike the bubble — it's `IMPORTANCE_HIGH`); (2) the summary numbers
  are right; (3) tapping **Accept**/**Decline** from the notification actually performs it on DoorDash
  (same click path as the bubble, now fixed); (4) it lands **after** the offer screenshot (clean
  frame). The bubble is still there to tap open for the full card. Watch for `OfferActionReceiver: …`
  then `Performing offer action …` in the log. Real orders — watch carefully.
  - Confirmed: 0/2.
- **Offer TTS now speaks the EVALUATION, not the raw offer (#110 step ii, PR pending).** The spoken
  read used to announce the parsed offer (`DoorDash offer. $7.50. <store>. 3.2 miles.`); it now speaks
  the verdict + headline economics: e.g. **"Accept. H-E-B. 22 dollars an hour net. Net 22.48, 12.9
  miles, score 74."** Confirm on an offer: (1) it speaks the **verdict word** (Accept/Decline/Review)
  first; (2) the numbers match the card; (3) it fires **once**, right after the eval (≈ in sync with
  the heads-up notification, after the screenshot settle) — not before the eval, not twice. Watch the
  log for `TTS speaking: Accept. …`. Real orders — listen on a quiet leg.
  - Confirmed: 0/2.
- **Bubble Accept/Decline now click DoorDash + collapse (re-test of 2026-06-09 #1 + #3, PR pending).**
  The click was searching the wrong window (`rootInActiveWindow` = the bubble); now it searches **all**
  windows, and the collapse cast was fixed (`findActivity`). To test: open the bubble (tap its head,
  since auto-expand is still off — that's the separate notification work), then on an offer tap
  **Accept** or **Decline** → confirm DashBuddy actually taps DoorDash's button (Accept accepts;
  Decline opens DoorDash's confirm), **and** the bubble **collapses to its head** afterward (vs
  dismiss — note which). Watch the log for `Performing offer action …` *without* a following
  `Could not find any live node`. Real orders — watch carefully.
  - Confirmed: 0/2.
- **Screenshots settle before capture (PR #325).** Captures saved to `Pictures/DashBuddy` should
  be **clean / fully-rendered** (UI settled), not grabbed mid-transition or half-drawn — there's now
  a 500ms settle before every screenshot. Spot-check the offer + post-task captures after a dash.
  - Confirmed: 0/2.
- **Offer card redesign — visuals (#110 Stage 1, PR pending).** When an offer arrives, glance
  at the bubble offer card and confirm the new layout reads at a glance: a **score ring** (green/
  amber/red by score) beside the **net $/hr** hero; a **verdict banner** (ACCEPT / DECLINE /
  MANUAL REVIEW + one-line reason + quality chip, tinted to match); **badge pills** when present
  (e.g. High pay / Red Card / Alcohol); and a **live expiry countdown** ticking in the header with
  a depleting progress bar. (No Accept/Decline buttons yet — Stage 2.) Watch for: missing/garbled
  values, countdown not ticking or width-jittering, ring color not matching the verdict.
  - Confirmed: 0/2.
- **Brand theme — HUD legibility & numerals (#94 / #313, PR pending).** With the
  new fixed brand palette + Hanken/Space Grotesk fonts, glance at the bubble HUD
  while driving: confirm it's **legible at a glance** in the dark, the phase/status
  colors read correctly (WAITING green · OFFER blue · PICKUP/DELIVERING green ·
  PAUSED amber · OFFLINE/DONE grey), and that **live-ticking numbers** (offer
  countdown, task timers, $/hr) render in the tabular-figure font and **don't
  jitter / shift width** as they tick. Colors should now be identical regardless of
  phone wallpaper (dynamic color is gone).
  - Confirmed: 0/2.
- **Shop & Deliver items/min (#276, merged 2026-06-02).** On a real Shop &
  Deliver, open the bubble pickup card and confirm it shows
  `shop {shopped}/{total} · {N.N}/min` (not a bare item count), that the pace
  **ticks** while shopping, and that on the DoorDash screen
  `total == "Done (x)" + "To shop (y)"`. **Add-on case:** if you accept an
  add-on / second order at the same store mid-shop, confirm "To shop" jumps up,
  the total grows, and the pace keeps counting on the *same* card (no reset).
  - Confirmed: 1/2. **Partial — 2026-06-03 (DoorDash):** the live pace *did*
    render and **tick** on the pickup/shop card during the shop. **Not** seen
    this dash: the finalized/frozen card, the `total == "Done (x)" + "To shop
    (y)"` cross-check, and the add-on case. Counting this as one clean
    live-ticking sighting; the next dash should confirm finalization + add-on
    before retiring the item. (See 2026-06-03 log entry.)
- **Offers behind a loading overlay (#275, merged 2026-06-02).** When an offer
  briefly shows a spinner (on present, or right as you tap), confirm it stays
  recognized as an offer — the bubble shouldn't flicker out of the offer view
  or drop to a blank/idle state mid-offer.
  - Confirmed: 0/2.
- **Cashout / transfer screens blocked (#275, merged 2026-06-02).** Open the
  DasherDirect/Crimson balance, a card-details screen, or initiate an instant
  transfer and confirm the bubble does **nothing** (sensitive → skipped),
  rather than reacting to it.
  - Confirmed: 0/2.
- **End-of-dash summary attribution (#279).** End a dash and watch the bubble:
  the **dash summary** (total earnings / duration) should land and attribute to
  the just-ended dash — whether the summary shows BEFORE or AFTER the
  idle/offline screen (the after-idle ordering was the bug). It must NOT finalize
  as a thin "early offline" the instant the idle/offline screen appears; the rich
  total should reach the HUD.
  - Confirmed: 1/2. **2026-06-14 (DoorDash):** dash summary landed on the just-ended dash
    (totals/duration correct), not a thin early-offline finalize. One more to validate.
- **New dash right after ending one starts fresh (#286 / #279-B / #290).** End a
  dash, then start a new one within ~10s. The bubble should treat it as a
  **brand-new dash** (fresh session / earnings reset), not "Session resumed
  (grace)". Cover **both** start paths, because they emit the fresh-dash signal
  from different screens:
    - **On-demand** start (tap Dash → the set-end-time screen) — the original
      `startingDash` carrier.
    - **Scheduled** start (#290): in your zone with a scheduled block, the idle
      map reads **"Start your scheduled dash"** and tapping Dash auto-starts with
      *no* set-end-time screen. This is the path that previously resumed the old
      session. Confirm the new dash is fresh, and that "You have another dash
      starting soon" (when you're *not* starting) does **not** reset anything.
  - Also regression-watch the grace refactor: backing out of the app mid-pickup
    and returning still **keeps the active task**; a brief offline blip mid-dash
    still **resumes the same** dash (no spurious new session).
  - Confirmed: 1/2. **Partial — 2026-06-03 (DoorDash):** the *brief-offline-blip
    resumes same dash* sub-case was seen — an app-switch return fired
    "Session resumed (grace)" (same session, no fresh start).
    **2026-06-07 (desk review):** two more sub-cases landed — (a) **resume-same-dash**
    seen again at 16:30:59 ("Session resumed (grace)", same session `9072f690`); and
    (b) the **on-demand fresh start** path confirmed at 11:24 — `DASH_STOP(summary_screen)`
    → 8 s later `DASH_START` with a **new** sessionId (not a grace resume). Still
    unconfirmed: that the **active task** survived the blip (no event proves task
    retention either way), and the **scheduled** fresh-start path. See 2026-06-07 log
    entry #4/#5.
    **2026-06-14 (DoorDash): not exercised** — only one dash this session, so the
    end-then-start-fresh path (esp. the scheduled-start variant) couldn't be tested. Stays 1/2.

- **Alcohol delivery ID-verification flow recognized + arrival timing (#149).**
  On an alcohol dropoff, the ID-check flow is now recognized (previously
  UNKNOWN). Two things to confirm:
    - The flow screens are recognized (no longer UNKNOWN): the intro/legal screen
      ("Scan and verify the recipient's ID" / "Agree and continue") and the scan
      screen ("ID barcode scan" / "Start scan").
    - **Arrival fires on the SCAN screen, not the intro.** Tapping into the flow
      and landing on the intro should *not* mark the dropoff arrived (guards an
      accidental tap); advancing to the barcode-scan screen *should* mark arrival.
    - Watch that no screen in this flow exposes the customer's actual ID data
      (name/DOB/license #). If one does, it must be blocked as **sensitive**, not
      recognized — flag it for a redaction + sensitive rule.
  - Confirmed: 0/2.

- **App-switch mid-dash → "Session resumed (grace)" → dash + task continuity
  (2026-06-03 #3).** The bubble's `"Session resumed (grace)"` message
  (`EffectMap.kt:319`) fires when a region goes Offline then back Online within
  ~10s on the **same** session. An app-switch return can trip this (DashBuddy
  stops seeing DoorDash → reads Offline → resumes on return). When it appears,
  confirm DashBuddy **kept the same in-progress dash AND the active task** with
  earnings intact — it must **not** start a fresh dash, double-start, or forget
  the task (cross-refs #286/#290 grace and 2026-05-29 #2). Also a UX read: is
  showing this internal-sounding message useful, or should it be reworded/demoted?
  - Confirmed: 0/2.

- **Bubble HUD no longer crashes on arrival-bearing dropoffs (#297).** Complete a
  dropoff with an explicit arrival step — a **photo**, **PIN entry**,
  **hand-it-to-customer**, or **alcohol ID-scan** delivery (these fire both
  `DELIVERY_ARRIVED` and `DELIVERY_CONFIRMED`). The bubble must **not** crash, and
  the completed-card stack should show exactly **one** delivery card for that stop
  (no duplicate). This was the fatal `LazyColumn` duplicate-key crash from the
  2026-06-03 session (#297).
  - Confirmed: 1/2. **2026-06-07 (desk review):** 3 arrival-bearing dropoffs fired
    both `DELIVERY_ARRIVED` and `DELIVERY_CONFIRMED` (in fact duplicated — see
    2026-06-07 log #1) and the app had **zero crashes** all day. The dedup held: no
    `LazyColumn` duplicate-key crash. Needs one more clean sighting (ideally
    confirming exactly one card per stop visually in the bubble).
    **2026-06-12 (DoorDash):** crash-free still holds, but the **"exactly one card"**
    sub-claim got a transient counter-example — a Great Greek (hand-to-customer)
    dropoff showed **two** delivery cards while at-door, collapsing to one after the
    paid card. Hypothesis: frozen `completed` Delivery (closed on `DELIVERY_ARRIVED`)
    + the still-`active` Delivery card overlap, keyed `delivery:id` vs `live:delivery:id`
    so no crash but two visible cards. Logged as 2026-06-12 entry #5 for investigation;
    keep watching arrival-bearing dropoffs for the visible duplicate.

- **Shop & Deliver items/min reaches `total/total` at the end (#302).** On a
  Shop & Deliver order, when you finish shopping (add the last item / reach
  "To shop (0)") the bubble shop card should read **`shop total/total`** — not
  `total−1/total` — and the items/min pace should reflect the full count. This was
  the off-by-one from the 2026-06-05 session, caused by the terminal frame being
  deduped away; the fix makes each shopping count change a distinct observation.
  - Confirmed: 2/2. **2026-06-06 (DoorDash):** developer reported "the item counts
    are working" (no longer freezing one short). **2026-06-12 (DoorDash):** pickup/shop
    card read **`shop 25/25 · 0.6/min`** at end of shop — the terminal `total/total`
    frame, no longer `total−1/total`. Two clean sightings → **validated** (the add-on
    case is tracked separately under the #276 watch item). (See 2026-06-12 log entry #4.)

- **⚠️ WATCH FOR RECURRENCE — mid-dash "Done Dashing!" + odometer reset (2026-06-06 #5, root cause confirmed, fix NOT yet shipped).** Confirmed once on 06-06: a
  transient **"Start your scheduled dash"** (`idle_scheduled_dash_ready`,
  `modeHint:offline`) frame seen **during an active pickup/delivery** armed the 10s
  `SESSION_END` grace; after an app-switch the dash **ended** (`DASH_STOP early_offline`)
  and **restarted fresh, resetting the session odometer** mid-dash. **How to tell it
  recurred:** the bubble flashes **"Done Dashing!"** then **"Started Dashing!"** and
  the **session miles/earnings reset to 0** while a delivery is still on screen — most
  likely when you have a **next dash scheduled** *and* you switch apps mid-task.
  **What to capture if it happens:** note the **time** (so we can pull the
  `idle_scheduled_dash_ready` / offline frame + the `DASH_STOP(early_offline)`), and
  whether a **scheduled dash was queued**. Goal: confirm recurrence + see whether any
  screen *other* than `idle_scheduled_dash_ready` ever triggers it — that decides the
  fix direction (narrow rule-gate **A** vs. the broad "never end a dash with an active
  task" guard **C**).
  - Sightings: 1 (2026-06-06). Gathering more before implementing.
    **2026-06-07 (desk review): did NOT recur** — all 4 `DASH_STOP` were
    `source:summary_screen` (authoritative); zero `early_offline`. **But the
    discriminating case never ran:** all 4 `DASH_START` were `source:interaction`
    from `WaitingForOffer` — no `idle_scheduled_dash_ready` start path this session,
    so still **no second data point** on whether another screen can trigger it. Fix
    stays held; keep watching (esp. dashes with a scheduled block queued).
    **2026-06-14 (DoorDash): did NOT recur** — no mid-dash "Done Dashing!" / odometer reset. Single dash,
    and the discriminating `idle_scheduled_dash_ready` start path wasn't confirmed, so still no second
    data point on the trigger. Fix stays held.

- **`nav_arriving` screen now recognized — confirm it fires + gauge frequency
  (PR #312).** The in-app nav "Arriving at \<destination\>" / "Arriving soon"
  overlay was UNKNOWN; it now classifies as `nav_arriving` (neutral — no behavior
  change yet). On a dash, glance at whether this screen actually appears as you
  reach a stop, for **both pickups and dropoffs**, and roughly how often (the
  capture corpus only caught it ~5/50 times — we need to know if that's
  capture-cadence or it genuinely doesn't show every approach). This decides
  whether "Arriving" can be the **arming** signal for the nav-exit arrival model
  ([design](../capture-analysis/2026-06-task-arrival-navexit-model.md)). What to
  watch: does "Arriving at …"/"Arriving soon" reliably show on final approach?
  - Confirmed: 0/2.
- **Try EXITING the map with the Exit button (not the back gesture) — capture the
  click.** The dev currently exits nav with the system **back gesture**, so no
  `exit_button` tap is ever captured (0 in all June). The nav screen *does* have an
  explicit **Exit** button (`id=exit_button`). On a dash, deliberately tap that
  **Exit** button a few times (pickup and dropoff) so we capture the click — it may
  give a cleaner, explicit "left navigation" signal than inferring it from the
  window transition. What to watch/capture: that tapping Exit produces a click
  capture (and note whether back-gesture vs button changes what DoorDash shows next).
  - Confirmed: 0/2.
- **Drop-off GPS runs while parked at the door (#294 — CONFIRMED at desk 08-09, dev-eyes half
  only).** The mechanism is now known and the old item text was inverted: post-#438 B5 the
  odometer keys on `lastActedFlow ∈ STATIONARY_FLOWS` (transient — the dropoff-completion
  screens photo/PIN/confirm are NOT in the set, so they read *moving* and resume GPS) while the
  HUD's AT DOOR keys on the sticky `arrivedAt`. The 08-09 pull showed the resume firing 10–20 s
  after arrival on ~every dropoff (pickups hold correctly). Desk half is DONE; what remains is
  dev eyes on the fix once one of the three candidate directions on the issue ships — until
  then, expect session-miles to creep at the door (it pairs with #918's jitter). No action
  needed on-dash beyond awareness.
  - Confirmed: 1/2 (desk 08-09: mechanism confirmed with per-dropoff timings; awaiting the fix
    + one field confirmation of the fixed behavior).
- **Shop-for-items offer card shows ONE pickup (repro watch; #338).** On the next HEB/grocery
  shop-for-items offer, screenshot the bubble offer card: it must list the store once. The
  2026-05-17 #5 duplicate's parse-layer cause was ruled out 2026-06-10 (every captured HEB offer
  frame parses exactly one order), and the card was redesigned in PR #324 — so this watches for
  recurrence. If it shows two pickups again, grab the `offer_popup` capture + a screenshot pair so
  parse vs render can be split.
  - Confirmed: 0/2.
- **Screenshots still save + no offer-time jank (#349).** Screenshot saving moved fully off the
  main thread (the PNG compress + MediaStore write used to run on main, right when the offer
  card/notification renders). Confirm: (a) offer + dash-summary screenshots still land in
  `Pictures/DashBuddy`; (b) no visible stutter the moment an offer arrives (should be same or
  smoother than before).
  - Confirmed: 0/2.
- **Paused dash auto-expires when the pause clock runs out (#342).** The pause-safety timer
  used to fire into a void (routed to no platform region) — now it reaches the paused region.
  If you pause mid-dash and deliberately DON'T resume: once the pause duration lapses, the HUD
  should flip out of PAUSED on its own (offline with grace) without needing a DoorDash screen
  change. Also regression-watch the normal path: resuming before expiry must NOT flash offline
  (the timer is cancelled on resume).
  - Confirmed: 0/2.
- **Offer evaluation always matches the offer on screen (#345).** Evaluations are now
  hash-correlated, so a rapidly replaced offer can't inherit the previous offer's verdict —
  the heads-up notification + spoken read should always describe the CURRENT offer's economics.
  Watch for any mismatch between the card's numbers and what's spoken/notified, especially when
  offers arrive back-to-back.
  - Confirmed: 0/2. **2026-06-14 (DoorDash):** dasher believes the eval matched the on-screen offer but
    couldn't verify in the field — needs a desk check of this dash's heads-up/TTS vs. the card numbers.
    Stays 0/2 until the log confirms.
- **Deadline countdowns still correct under the new transform clock (#343).** Time parsing
  (`parseDeadline`/`parseTime`) is now anchored to the observation's instant instead of the
  wall clock at evaluation time (replay determinism; the 05-19 "1434:38 ghost countdown" class).
  Live behavior should be identical — confirm pickup/dropoff "by HH:MM" countdowns on the cards
  match DoorDash's stated times, and no absurd ~24h countdowns appear (especially around
  midnight or just-past deadlines).
  - Confirmed: 0/2.
- **Economy fields keep the decimal point while typing (#350; desk-verifiable, no dash
  needed).** In Settings → Personal Economy (or the wizard's costs step), type `12.5` into any
  cost field — the `.` must survive (the value round-trip used to reset the text mid-typing and
  eat the separator). Also confirm: the live `$ /mi` footer still updates per keystroke, and the
  field normalizes (e.g. `012.50` → `12.5`) when you tap away.
  - Confirmed: 0/2.
- **Captures still write on dev builds (#346).** Capture persistence is now bound per build
  variant (debug → disk, release → none). Your field builds are debug, so nothing should change —
  sanity-check after a dash that the session's `captures/` folder is non-empty. If it's ever
  empty, check logcat for "Capture persistence disabled (release build)" — that means a release
  build got dashed by mistake.
  - Confirmed: 0/2.
- **"(No session)" bucket keeps gross ≥ net when a delivery lands without a session (#660 piece
  1).** Only reproducible if a dash produces a `sessionId IS NULL` delivery row (e.g. a straggler
  DELIVERY_COMPLETED after the app/service restarted mid-dash, or any other path that loses
  session context) — may not fire on a normal dash. If it does happen: on the Money tab for the
  period containing that delivery, a new **"(No session): $X across N deliveries not tied to a
  dash"** callout should appear (same style/placement as the existing unattributed/over-attributed
  flags), the hero **Gross Earnings** figure should include that delivery's pay (no longer
  possible for the True-Net chip to show more than Gross), and the per-day chart's bar for that
  delivery's own completion day should include its pay. If you can't force this edge case, this
  item can be validated desk-side by inspecting `delivery_records` for any `sessionId IS NULL` row
  after a dash and confirming the Money tab reflects it as above.
  - **Known caveat (desk-verifiable, not a bug to report):** if the orphan delivery's pay was
    ALSO already inside a surviving session's captured `reportedEarnings` (e.g. the restart
    happened mid-dash and the dash's summary screen still got captured afterward), gross will
    double-count those dollars — expect to see them flagged in BOTH the unattributed callout
    and the "(No session)" callout at once. This is a known, documented overstatement (mirrors
    the pre-existing net-side overlap) that piece 2 (categorizing an orphan into its real
    session) is the actual fix for — no action needed beyond noting it if seen.
  - Confirmed: 0/2.
- **Ratings-hub family no longer falls to UNKNOWN (2026-07-30 corpus pass).** Three recognition
  gaps in DoorDash 0.230.0's redesigned Ratings area were closed against the 07-29 pull. All three
  are **desk-verifiable from the next pull alone** — no dev-eyes needed on the road. Open the
  in-app Ratings area during a dash (or any time the app is running with capture on), tap into a
  couple of the rating factors, and then check the pull:
  1. **The hub itself** (`Overall rating <N>` + the Silver/Gold/Platinum gem ladder + "Your rating
     factors") must land in `captures/doordash/accessibility.window/ratings/` **with the side-nav
     drawer CLOSED**. Before this change it only classified when the drawer happened to be open —
     the rule was matching the drawer's "Ratings" menu row, not the screen.
  2. **The "Last 30-day orders" drill-down** (the count factor, the one with no "Last 100 …" list)
     must land in `performance_rate_detail/`, not `UNKNOWN/`.
  3. **The Customer-rating drill-down with the band table EXPANDED** (tap the "Points toward your
     overall rating" row to expand) must stay in `customer_rating_detail/` — the hero-score
     container leaves the tree in that render and used to drop the frame to UNKNOWN.
  Anything still landing in `UNKNOWN/` from that area is the item failing — grab the capture id.
  Known, deliberate residual (NOT a bug to report): the hub's parsed rate fields all read `null` on
  the redesign, because the parse is still anchored on the old `textView_title` layout. That was
  already true before this change; re-anchoring the parse is separate, data-enrichment work.
  - Confirmed: 0/2.

---

## 2026-09-05 (desk analysis of the 09-06 pull — first field run of #1029/#1030/#1032/#1036/#1052/#1057/#1066)

**Date:** 2026-09-05 · **Platform(s) tested:** DoorDash (app **8.95.6** — moved from 8.93.7; every one of the 493
envelopes) · **Branch under test:** `master` at `38036999` (read straight off the logs — `DashBuddy 0.230.0+38036999
starting`, #1066's first pull), installed 14:35; the 07:45–09:19 `early_offline` session predates the install and
is old-build data · **Field conditions:** three afternoon/evening dashes (16:19–17:46 $34.60 · 17:46–18:46 $22.25 ·
18:54–20:37 $48.38), all ended on the summary screen; 6 deliveries; Uber Offline the whole slice; no pause/resume
mid-spin, no unassign, no orphan. 493 captures (48 UNKNOWN screens, 63 UNKNOWN clicks, 5 UNKNOWN notifs), zero
`ERROR` lines, `restarts=0`, 16 % UNKNOWN ratio, no `RecognitionHealth` alarm, `shareable.log` PII-free.
Issues filed this analysis: **#1078**, **#1079**. Not on this build: #1058/#1059/#1063/#1064/#1033.

### Bugs

**1. Session 409 silently lost a delivered drop — $9.95 unattributed → #1078.** Job −413 had two dropoffs; `DASH_STOP`
(seq 1881) sequenced AHEAD of the second drop's own `DELIVERY_CONFIRMED` (1882) on the same 17:46:24 step, and only
one `DELIVERY_COMPLETED` (1883) emitted although the shadow projector logged both customer hashes. The offer quoted
$19.90, the surviving drop was stamped $9.95 (`PER_OFFER_STORE`), and $34.60 − $24.65 = $9.95 closes the session's
gap to the cent. *Hypothesis:* End Dash tapped on the second doorstep; the SESSION_END grace committed with the
close-out sweep completing only the already-retired task and `endSession`'s bail force-stamping the other. Nothing
WARNed and `session_records.deliveries` still reads 2. Would need the 17:44–17:47 sequence replayed through
`SessionReplay.reduceMixed` to confirm.
- **Status:** Open (#1078).

**2. Every 8.95.6 receipt auto-expands just past the 2.5 s grace — 3 for 3.** Collapsed→`GRACE_COMMIT`→expanded:
+172 ms (job −418), +463 ms (−424), +41 ms (−427); the collapsed→expanded interval is a tight 2.56–3.00 s, i.e. an
animation, not a tap. Consequence right through the read model: `parsedPay` null on all three → `RECEIPT_TOTAL`, no
tip/base, empty `payoutStoreForms` (#653's guard silently off); on the stacked job −418 one drop took the whole
$22.25 and its sibling folded `NONE` with NULL pay (no receipt AND `offerPayShare` null — the #691 FIX-1 residual).
This is #1033's premise, confirmed: the old grace is not occasionally late on 8.95.6, it is always late. #1033
(8 s + re-price) is on the 09-06 build.
- **Status:** Shipped in #1072 (2026-09-06) — first field test pending; the `NONE` sibling stays the #691 FIX-1 residual.

**3. Two 8.95.6 sheet families still unruled + one pickup-side redact asymmetry → #1079.** `alcoholWarningBottomSheet`
(not #1058's leak A — a separate warning sheet, no PII in this capture); the id-less `address_instructions_view`
"Delivery for <customer>" sheet ×4, two of which the #910 `user_name` backstop masked (the backstop acting as the
primary control, which #1058 argued it must not); and `address_subpremise_line` rendering the MERCHANT's suite raw on
6 recognized `pickup_*` frames while the same id is in `ID_MARKERS` — `pickup.json5` has no `Apt` entry, so #986's
scan cannot see it. Needs a ruling (mask, or document the pickup exemption beside #886).
- **Status:** Open (#1079).

### Verification (desk)

**4. Working:** #1030 (no fake $0 — 49/50 early_offline rows NULL), #1032 (summary recognized 3/3), #1036 (census
present; `waiting_for_offer` ×5 is the benign pre-render class), #1057/#918 (2,310 fixes, 0 rejected, 190 ignored,
plausible spans), #1066 (build read from the logs), #159/#773/#1000 store + offer linkage, #688B (Σ realizedMiles ≤
span everywhere), #588 shop rate learning (`0.76/min n=70`), #731 (no listener flaps), #924 (DasherDirect: 217
`sensitive.dasher_direct` drops, zero `dxdr_*` UNKNOWN — **2/2, item retired below**), the #992–#995/#920 redact
batch (zero raw `For …`/`Return …`/`Focus on …`/`Customer Notes` hits across `captures/`).
**Half:** #1029 (totals + settle commits correct on 8.95.6; `DROP_SHARE`/tips unproven because of item 2), #1034
(no negative window occurred), #996/#997 (two jobs match their quote to the cent; job −413 is item 1).
**Not exercised:** #1052 (no pause/resume mid-spin, no platform switch), #1059 (no Persona/Red Card/passport surface;
the existing licence anchors dropped 17 `id_verification` frames), #736/#752, #660p2, #810 B2, #991 (24 utterances,
0 failures — ladder untouched).
**Recurring, not on device:** #1063 (4 more offer cards with a real store row, no functional loss), #1058 leak B ×2.

### Open questions

**5. #843 consent never answered → #577 quick-decline is OFF.** `Consent: reconciled 4 capabilit(ies) … none granted —
awaiting consent` on all three process starts and 34 × `Denied confirm_decline (fail closed)`. Correct behaviour; the
log cannot tell "never saw the prompt" from "Not now". Dev-eyes: Settings → Data & Privacy → Automation & Consent.

**6. DoorDash 8.95.6.** The #1029 re-anchors held on the bump, but the frozen corpus is 8.93.7-era and the new
id-less sheets (item 3) are the first 8.95.6-only shapes. Worth a corpus intake from this slice.

### Retired from the checklist this analysis

- **#924 (PR #1014) — DasherDirect is blocked from the first frame.** Confirmed 2/2 (desk 09-05 + desk 09-06).

---

## 2026-08-28 → 2026-09-05 (desk analysis of the 09-05 pull)

**Date:** 2026-09-05 · **Platform(s) tested:** DoorDash (app 8.93.7) · **Branch under test:**
pre-#1044 `master` (the 08-24 daytime build — the logs carry no `parseShortfall` lines anywhere
and `reportedEarnings` is still stamped `0.0`, both of which place the install before #1044 landed) ·
**Field conditions:** desk analysis of the 09-05 pull — 8 dashes, **every one ended
`early_offline`**; 13 deliveries, $220.09 folded, every row on the `OFFER_PAY` basis;
shopping-heavy (126 `pickup_shopping` and 38 `shopping_item` recognized frames); 744 captures,
236 of them UNKNOWN. Issues filed this analysis: **#1057**, **#1058**, **#1059**.

### Bugs

**1. The odometer gained 905.37 miles in 18.4 minutes on 09-03, and the gain is now permanent →
#1057.** Between `PICKUP_CONFIRMED` at 17:25 (odometer 1771.16) and `DELIVERY_ARRIVED` at 17:43
(odometer 2676.54) the reading moved +905.37 mi. Delivery row 1801 froze `realizedMiles` 907.41
and a `netProfit` of **−$302.73**; because the odometer total is persisted cumulatively, every
lifetime mileage figure now carries the +905 forward. There is **zero log signal** — no WARN, no
ERROR, nothing in 120,217 lines. *Hypothesis:* `OdometerRepository.processLocation` gates a fix on
`distanceMeters > 5` alone, with no accuracy or implied-speed bound, so a single bad location (a
cold re-acquire, a tower-derived fix) would be admitted as real distance; confirming it would mean
checking the accuracy/speed fields on the fix that produced the jump against the delta that was
admitted. Filed as #1057.

**2. Two UNKNOWN dropoff sheets shipped the customer's address and gate codes verbatim →
#1058.** Both are envelope-only leaks on the UNKNOWN path, and both are the #985 class recurring
on new surfaces. (a) An **alcohol pre-arrival variant** — 4 envelopes, 08-28 16:58
(`…UNKNOWN__172c76.json` and siblings) — where `address_line_1`/`address_line_2` masked correctly
but `address_subpremise_line` and `dasher_instruction_content_collapsed` did not; the redact
entries for that shape already exist, so this reads as a **recognition miss**, not a missing
mask. (b) An **id-less "Leave it at the door" sheet** — 2 envelopes, 08-30 16:30:48
(`…__1c7706`, `…__52eccf`) — with no view ids to anchor on at all, the same shape #985 closed for
the Timeline detail sheet. Filed together as #1058.

**3. The Persona selfie / ID-verification camera flow reaches UNKNOWN → #1059.** 11 envelopes on
08-27 (`…__36476b`). `sensitive.json5` covers the flow's **entry** screen only, so the capture
surfaces behind it fall through to the UNKNOWN path.

**4. The "Your Red Card" wallet screen reaches UNKNOWN → #1059.** 4 envelopes. Filed with item 3
as one identity-surfaces issue.

**5. `earnings_deposit` pushes store the "DoorDash Crimson account" clause — already #987.** No
new filing; recorded here as a second sighting.

**6. The 09-05 09:19 H-E-B dash under-reported by $6.75.** The receipt and the deposit both read
$42.45; the fold used the $35.70 offer quote. This is **expected on this build** — the money
parses were dead (#1029) and every row fell to `OFFER_PAY` — and is noted here so the post-#1050
pull can confirm the recovery on the same shape.

### Field UX context

**7. 37 × `Denied confirm_decline … (fail closed)`.** The quick-decline capability is *still*
ungranted on the device — three consecutive pulls now — so #577's auto-confirm remains inert in
the field. The gate is doing exactly what it should; the grant is the standing to-do before the
next dash.

### Open questions / investigations

**8. The 09-03 evening dash produced TWO deposits for ONE delivery** — $20.72 at 17:53 and $22.95
at 18:13. If the $20.72 was the settlement, then the `OFFER_PAY` estimate over-reported that drop
by $2.23 — #756 in miniature, on a single delivery rather than a stack. Would need the receipt (or
a driver attestation) to say which figure is the settlement.

**9. Is `earnings_deposit` an acceptable authoritative attestation, or does it sit behind the
payment-surface block?** Across the 8 dashes the deposit pushes matched the folded per-dash total
**to the cent on 7**, and the eighth is exactly the miss in item 6 — i.e. the surface both agrees
with us and catches us when we are wrong. The open question is whether reading it is compatible
with the Pledge's payment-surface block (#987 owns that boundary). Noted on #1035, which owns the
oracle question.

### Verification TODOs / system health

**10. Census: zero ERROR in 120,217 log lines.** `restarts=0`, `mappingFailures=0`,
`notifListenerDisconnects=0`. The #937 recognition-health signal never tripped — the UNKNOWN rate
sat at ≈18 %, at baseline — and all 136 UNKNOWN clicks carried a `screenTarget`. `shareable.log`
(705 INFO+ lines) came back clean on the PII scan.

**11. Recognized-surface redaction held.** 388 masks across the pull, and **zero** hex-suffixed
masks after `Apt`/`Suite`/`Unit` — #986/#934 and #1039 both working on fielded renders.
`sensitiveDropped=546` with **no** `dxdr_*` frame anywhere on the UNKNOWN path, so #924's
id-anchored arm held too.

**12. #991's recovery seam fired for real — 08-30 15:44:52.** A `speak()` returned −1, the
handler rebuilt the engine, and the engine re-initialized **417 ms** later; 34 of 35 utterances
spoke that day. This is the first live exercise of the ladder (the 08-24 pull only established
no-regression).

**13. Checklist outcomes this pull.** **#986/#934 VALIDATED 2/2 → retired** (item 11 above is its
second clean confirmation, on top of the 08-24 pull's 12/12 — the item leaves the checklist with
this entry). #991 → 1/2 (item 12). #924 → 1/2 (item 11 — the desk half). The unassign item
(#752/#736) → 1/2 (one `TASK_UNASSIGNED` at seq 1806, the $45.45 quote stayed unattributed, no
paid artifacts). Every #1029 / #1030 / #1032 / #1034 / #1036 / #1052 item is **not testable from
this pull** — the device ran the pre-#1044 build — and each keeps its count with that noted.

---

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

---

## 2026-08-23 — the whole week reads as $0 on the Money screen; Dash Along the Way has stopped showing the delivery receipt out of zone

**Date:** 2026-08-23 · **Platform(s) tested:** DoorDash · **Branch under test:** `master` @ `e903a6e`
(post-#1028, the #1024 close-out — inferred from the most recent merge, dev to correct) ·
**Field conditions:** narrated live from the phone immediately after ending a dash, no data pull yet.
At least two dashes in the window (today plus one on Monday 08-17); one of today's ran as **Dash Along
the Way**, out of zone. Captures to follow.

### Bugs

**1. Nothing at all was recorded — the Money screen reports no earnings for the entire week (P0).**
After ending today's dash the dev opened the app and the Money screen said they had not earned
anything this week, despite having also dashed **Monday**. This is the headline: it is not one missing
receipt, it is a whole week absent from the read model.
- **Status:** Open.
- **Desk-side, hypotheses only — the shape of the symptom matters.** "A whole week vanished at once"
  is far more consistent with a **read-model** failure than with a per-dash capture failure, because
  capture failures are per-frame and would not retroactively take Monday with them. Two candidates,
  in order:
  - *(a) A `PROJECTOR_VERSION` wipe whose refold did not complete.* `PROJECTOR_VERSION` is currently
    **10** (`AnalyticsProjector.kt:942`), bumped recently by #1013. On the next start after a version
    change the projector **wipes `delivery_records`/`session_records`/`offer_records`/`stores`/
    `pickup_records` and resets the watermark to 0** (`AnalyticsProjector.kt:609–624`), then refolds
    the whole log. If the dev installed a build carrying that bump and the refold then died or never
    ran to completion, the tables would be **empty while `app_events` is fully intact** — which would
    present exactly as "no earnings this week" with no on-dash symptom whatsoever. This hypothesis is
    attractive precisely because it explains Monday.
  - *(b) The projector never ran / crashed at start.* It is launched from `DashBuddyApplication`
    off-main and supervised; a supervised failure would leave a stale-but-partial read model. Would
    also take the week, but should leave an ERROR line where (a) may not.
  - Both are distinguishable from a genuine capture loss **before** any rule work, and the check is
    cheap — see item 5.
- If the events **are** in `app_events` and only the projection is missing, this is recoverable
  (rebuild ≡ backfill by design) and the bug is in the refold path, not in sensing. If `app_events`
  is *also* empty for the week, this is the #909 family again and a much worse finding.

**2. Ended the dash right after tapping expand on the post-delivery receipt; that delivery did not
land either.** Sequence as narrated: delivered → the post-delivery summary appeared **collapsed** →
dev tapped **expand** → dev then **ended the dash**. Nothing recorded.
- **Status:** Open.
- **Desk-side hypothesis.** If item 1 turns out to be the read-model failure, this item is most likely
  *swallowed by* item 1 rather than being its own defect, and should be re-judged after the pull —
  do not chase it first. If item 1 resolves and this delivery is still missing, the interesting seam
  is the expand-then-immediately-end ordering: the collapsed→expanded receipt path carries a
  deliberate same-task guard (`PlatformRegionStepper.kt:537–551`, #630 R3) and the retire/close-out
  runs behind a **grace timer**, so a dash ended within the grace window is the shape worth replaying
  (`SessionReplay.reduceMixed` — real screens + the expand click + synthetic `GRACE_COMMIT` timers is
  precisely what that harness exists for). Unconfirmed either way without the capture sequence.

### Field UX context

**3. Dash Along the Way no longer shows the post-delivery summary screen at all while out of zone.**
Dev-observed and dev-attributed to DoorDash, not to us: on a Dash Along the Way, completing a drop
**outside** the zone produces **no** post-delivery summary. Once DoorDash recognizes the dasher has
arrived **in** the zone, the summary screens start appearing again for subsequent drops. So it is
conditional on in-zone/out-of-zone, not on the dash type as such.
- **Status:** Open — platform-side behavior, nothing for us to fix; it is an **input** to how we price
  those drops.
- This sharpens the standing **#999** checklist item ("is DoorDash still SHOWING the per-delivery
  earnings receipt at all?", README:188), which had the disappearance recorded but not the
  **out-of-zone condition**. Worth folding the condition into that item so the next test knows to
  check in-zone and out-of-zone separately rather than reading one null as the whole answer.
- Consequence, stated plainly: for out-of-zone Dash Along the Way drops there is **no receipt to
  parse**, so those drops can only ever be priced from the offer quote (`PayBasis.OFFER_PAY`, #691/
  #999). That is the estimate path working as designed — but it also means item 4 below is the only
  route to a *real* number for them.

### Research / design

**4. Dev proposal — treat DoorDash's own running dash total as the reconciliation oracle.** In the
dev's words: if we can parse what DoorDash says we've earned on the active dash screen, and it does
not match what we have recorded, **DoorDash's figure is the source of truth** — set to it, flag it for
review, *and* attempt to resolve it ourselves. The worked example given: an accepted offer quoted
\$5, we have not recorded the delivery, and the dash screen now reads \$5 — that is enough to conclude
the \$5 came from that job and attribute it.
- **Status:** Open — proposal, not yet an issue.
- **Desk-side: the parse ingredient already exists, unused.** Two rules already read DoorDash's own
  running total off the live dash surfaces:
  - `doordash.screen.waiting_for_offer` parses **`sessionPay`** from the id-anchored
    `running_total_pay` node (`dash-lifecycle.json5:762`) — id-anchored, so **locale-immune** (#938/
    #910), which is the good kind of anchor.
  - `doordash.screen.timeline` (the "Current dash" control sheet) parses **`sessionEarnings`** from
    `This dash`, plus **`offerEarnings`** from `This offer` (`dash-lifecycle.json5:116–125`).
  - `IdleFields.sessionPay` **is** folded today, into `PlatformRegion.session.runningEarnings`
    (`PlatformRegionStepper.kt:~511`) — but that is the **bubble HUD figure only**.
  - **`TimelineFields` is not handled by `updateSessionFields` at all** — its `when` covers `Idle`,
    `PostTask`, `SessionEnded`, `Paused`, then `else -> no session updates`. So the timeline's
    `sessionEarnings`/`offerEarnings` are parsed on every dash and **dropped on the floor**. That is a
    free, already-fielded signal, and the `offerEarnings` half is exactly the per-offer granularity the
    dev's \$5 example needs.
- **The real gap is not the parse, it is that `runningEarnings` never reaches the money.** The HUD
  figure lives in state; the Money screen is projected from `app_events` → `delivery_records`. Nothing
  currently carries "DoorDash says the dash total is X" into the read model, which is why a drop with
  no receipt can be invisible in the analytics even while the bubble shows the right running number.
- **Design note, offered as a caution, not a verdict.** The proposal's "set it to that" half is in
  tension with the **frozen-economics doctrine** (a `delivery_record` is an immutable historical fact;
  `AnalyticsRepository` is DAO-only *specifically* so historical net cannot be recomputed). The shape
  that appears to fit the existing architecture is the **correction-event** path already built for
  #650/#688/#810 — i.e. a new reconciliation event appended to the log and folded, exactly as
  `DELIVERY_ADJUSTMENT` and `OFFER_OUTCOME_CORRECTION` are, rather than a mutation of existing rows.
  The dev's "flag for review, and also try to resolve it ourselves" maps cleanly onto the **two-tier**
  pattern #810 B2 already established (Tier 1 automatic inference where the evidence is unambiguous,
  Tier 2 driver attestation where it is not), and the `unattributedPay` review flag is already the
  surface for the flagging half. Whether the auto-resolve tier should fire on a single-candidate match
  only (one unrecorded job, one exact delta) or something broader is a real design question and the
  dev's call — noting only that #745's *fail-null beats fail-wrong* rule and the ambiguity ladder in
  #997 are the nearest precedents for where to draw that line.
- Worth filing as an issue once item 1 is resolved, since item 1 may change what problem this is
  actually solving.

### Open questions / investigations

**5. What the pull needs to answer, cheapest first.**
- **Is `app_events` populated for 08-17 and 08-23?** This single question splits item 1 into
  "recoverable projection bug" vs "catastrophic sensing/write loss", and everything else waits on it.
- **What is the stored `projectorVersion` and watermark** in the projection-state row, and does the
  log carry the `AnalyticsProjector` version-change / wipe line and an ERROR after it?
- **When was the current build installed**, relative to Monday's dash — a wipe at install time would
  date the disappearance.
- **The capture sequence around the expand tap** (item 2): the collapsed receipt frame, the expand
  click, and every frame through end-of-dash, so it can go through `SessionReplay.reduceMixed`.
- **A `waiting_for_offer` / `timeline` frame from the Dash Along the Way dash** (item 3), out of zone,
  to confirm `running_total_pay` / `This dash` still parse when the receipt sheet does not exist — that
  is the precondition for item 4 being worth anything on exactly the dashes that need it most.

### Meta / architecture

**6. Carried forward from earlier today (pre-dash desk look, same session): `doordash.screen.earnings`
has zero DoorDash corpus.** The only fixture in `snapshots/earnings_activity/` is an **Uber** one, and
`doordash.screen.earnings` appears nowhere in `approved-parse-output.json` — so that rule has never
been validated against a real DoorDash frame. Separately, the #924 DasherDirect door
(`hasIdSuffix: "dxdr_nav_host_fragment"`, priority 0, `overrideable: false`, merged in #1014) would
**structurally out-rank** the earnings rule on any frame that hosts the Crimson card, since the
non-overrideable partition is evaluated first (#419). Both remain hypotheses pending the Earnings-tab
captures the dev is bringing home. Noting it here so it is not lost between sessions.
- **Status:** Open.

---

## 2026-08-09 — DESK ANALYSIS of the 08-01→08-08 field week (pull 08-09): the voice died on 08-06 and nothing said so; four new Pledge leaks; two pay-split defects; the delivery receipt stopped appearing

**Date:** 2026-08-09 (dashes 2026-08-01, 08-02, 08-06, 08-07, 08-08) · **Platform(s) tested:**
DoorDash only — **zero** Uber sessions all window (11 Uber promo notifications and nothing else, so
every Uber checklist item is NO-SIGNAL, not a null result) · **Branch under test:** `master` @
`bbc36913` (post-#984 — every redesign stage #970–#983 aboard), app 0.230.0, installed 2026-07-31
11:42 · **Field conditions:** five moneyed dashes over nine days, **one single app process for the
entire window** (never restarted, never updated), 127,024 log lines, **0 ERROR**. Pull:
`~/dashbuddy/logs/2026/08/09/`; the device was purged 07-31, so this is a clean non-overlapping
slice.

### Session inventory and the money verdict

| Dash | Window | Drops | Reported | Attributed | Δ |
|---|---|---|---|---|---|
| 08-01 | 11:17–13:59 | 3 | $58.41 | $38.04 | −$20.37 |
| 08-02 | 15:49–18:43 | 3 | $68.63 | $68.63 | **exact** |
| 08-06 | 16:22–18:45 | 3 | $49.45 | $47.20 | −$2.25 |
| 08-07 | 18:13–20:30 | 3 | $52.85 | $46.30 | −$6.55 |
| 08-08 | 12:30–13:54 | 3 | $41.76 | $39.60 | −$2.16 |

(Plus one 2-minute $0 session on 08-06.) Aggregate **$271.10 reported / $239.74 attributed**,
−$31.36 (11.6 %), and **no session over-attributed** in either direction. Every one of those five
deltas is now explained by name: 08-02 reconciles to the cent; 08-01's whole −$20.37 is the
unrecognized return-order pay screen (item 9); 08-06 is mostly a $2.00 post-delivery tip push
(item 14); 08-07's −$6.55 is *exactly half* of a two-order offer (item 6, a defect); 08-08 is
post-offer tip drift on estimate-priced rows. The window's headline is not the money, though — it is
that the app spent its last three dashes silent, and the delivery receipt sheet stopped existing.

### Headline: the spoken verdict has been dead since 08-06, and nothing anywhere said so (item 1)

**1. Offer TTS returns −1 on every call from 08-06 16:28 onward → #991 (P0).** This is the dev's
own fielded complaint, and the log carries it exactly: 15 of 15 `speak()` calls on 08-06/07/08
returned `-1`, each logging `WARN/Tts: speak returned -1 — abandoning audio focus`; 11 of 11 on
08-01/08-02 had succeeded. `engine initialized` appears **once** in the whole 127k-line log, at the
07-31 install — the process never restarted, so the `TextToSpeech` instance in play on 08-08 was
eight days old. Working hypothesis: the engine's binder died (a Google TTS / Speech Services package
update between 08-02 18:06 and 08-06 16:28 would be the ordinary way for that to happen), and the
`@Singleton` `TtsEffectHandler` has no re-init path — `isReady` is set at init and never reset, so
every subsequent utterance is dispatched into a dead binding forever. Would need confirming by
checking whether a force-stop restores the voice (hence the checklist item asking exactly that).

What makes this the entry's worst finding is not the silence itself — offers were still evaluated,
carded and posted as heads-up notifications the whole time, and the dasher's money was never at risk
— it is that this is the **same silent-death family as #909/#914 (effect engine), #916 (bubble) and
#917 (odometer)**: a `@Singleton` whose one job stops happening inside a live process, with no
alarm, no counter, and no self-heal. The quadrant that #937 filled for recognition is exactly the
quadrant TTS is missing. Nothing in the app noticed for three dashes; the dev did.

### The Pledge section: four new leaks, all filed (items 2–5)

**2. `pickup_wait_survey` is RECOGNIZED and ships a raw `customer_name` → #992 (P1).** The rule has
no `redact` block at all (`matchers/rules/doordash/pickup.json5:445`); two envelopes on 08-07 carry
the customer's name verbatim. The structural point: the #910 `ID_MARKERS` node-id scan is
**UNKNOWN-only by design** (a recognized frame keeps its rule's deliberate decisions — that's what
keeps `pickup_navigation`'s merchant address raw), so a recognized rule that simply forgets `redact`
has *no* backstop underneath it. Every one of the last few Pledge findings has been an id-less or
marker-less shape; this one is the opposite and arguably worse — a surface we recognize, whose PII
we could have masked with one declaration.

**3. `dropoff_navigation`'s `arriving_at_title` ships the raw customer street address → #993 (P1).**
One envelope, 08-02 18:00. The mask for this exact node exists on `nav_arriving`
(`nav-comms.json5`) but not on the dropoff-phase rule that wins when the arrival banner inflates
over the dropoff sheet — and the giveaway is that the **same frame** masks
`bottom_sheet_address_line_1` correctly. The raw copy and the masked copy of the same address sit
side by side in one envelope. Sibling of #886's maneuver-cluster work, which covered the nav
maneuver nodes on this rule but not the arrival title.

**4. A FOURTH timeline conjugation, `Return <customer> to <store>` → #994 (P1).** Three recognized
`timeline` envelopes on 08-01. It escapes both layers at once: the rule's own `redact` enumerates
`Pickup for` / `Deliver to` / `Pickup from`, and `CustomerTextMarkers.MARKERS` doesn't carry it
either. Same class as #962's third conjugation (`Delivery to`) — DoorDash keeps inventing sentence
forms for "this order belongs to this person", and each one is a separate enumeration miss. Whether
the answer is a fourth literal or a shape-based marker is a design question, not a desk one.

**5. The receipt-scan camera persists the customer's name on id-less nodes (`Focus on <name>`)
→ #995 (P1, DEV RULING WANTED).** Five UNKNOWN envelopes, 08-07 19:46:54–19:47:05. No view id, no
marker prefix, no rule — nothing in any scrub layer can currently reach it. The open question is
which Pledge family it belongs to: a camera pointed at a paper receipt bearing a third party's name
reads as the **document-image capture** family (the license scanner, the signature pad — *blocked*,
never parsed), but it is also plainly a customer surface, which the model says we *recognize and
hash*. The two rules give opposite answers, and the answer decides whether the fix is a sensitive
rule or a recognizing rule with a `redact`. Dev call.

### Money and lineage (items 6–11)

**6. A receipt-less same-customer two-order job loses half its pay → #996 (P1).** 08-07, offer seq
1496: $13.10, two orders, one customer. Both pickups confirmed; ONE physical drop, which folded
`offerPayShare = 6.55`. The session's shortfall is $6.55 to the cent. The mechanism is that
`OfferPayFallback.owedDropoffs` splits the offer total across both owed drops, but only one drop
ever exists to claim a share, so the other half is simply never attributed to anything. #749 fixed
**closure** for this exact shape (the job closes, the next offer gets its own job — that half worked
perfectly here); it did not touch the denominator that prices it. Note this only bites on a
receipt-less job — which, per item 8, is now every job.

**7. A job that absorbed three separately-accepted offers pools their pay into an equal split
→ #997 (P1).** 08-06: offers of $10.45, $16.55 and $20.20 folded as $15.73 / $15.73 / $15.74. The
session Σ is exact, so nothing is *lost* — but every per-drop figure is wrong by up to $5.28 (50 %
error on the smallest), and per-drop figures are precisely what the store leaderboard, the earnings
heatmap, `DayPlanner`/`WeeklyPlanner`, and #975's estimate-vs-reality card are all built on. A dash
like this teaches the planner that a $10.45 order and a $20.20 order are the same order. The
encouraging part is that the mapping looks **recoverable rather than lost**: the accept →
`PICKUP_NAV_STARTED` lineage and `jobOfferHashes` both survive in the log, so this may be an
attribution defect rather than a missing-data one — would need someone to actually walk that join to
be sure.

**8. The delivery receipt sheet stopped appearing after 08-02; 9 of 9 deliveries on 08-06/07/08 were
priced by the `OFFER_PAY` estimate → #999.** Zero receipt-shaped frames — recognized *or* unknown —
exist after 08-02, and the post-drop frame sequence changed shape with them: it used to run
`dropoff_photo` → `dropoff_completed_confirm` → `delivery_summary_collapsed/expanded`, and now runs
`dropoff_photo` → *nothing* → the next `offer_popup`. Because the frames are absent from the UNKNOWN
pile too, **this is not a recognition regression** — you cannot fail to recognize a screen that was
never rendered. Hypotheses, unresolved: DoorDash changed the post-delivery flow in an app update; or
the sheet still renders but so briefly that no frame is admitted; or something about these
particular orders (all three dashes were ordinary food deliveries) skips it. It matters because
`OFFER_PAY` is an *estimate* basis — items 6 and 7 are both estimate-path defects, and an
all-estimate world is where they bite. The frustrating part is that #937's version stamp **can't
test the app-update hypothesis here** (item 12). This needs a field observation, not more desk work;
a checklist item is added.

**9. The return-order flow is entirely unmodelled → #998.** ~27 UNKNOWN frames on 08-01: return
timeline variants (19), a pay-breakdown screen reading `Original order (Base) $20.37 / Return pay
$10.19 / Total $30.56` (1), and a store-signature flow (4 — which contains a literal **signature
pad**, i.e. Pledge-sensitive surface area). The money consequence is exact: $20.37 of a $58.41 dash
(34.9 %) is unattributed, and $38.04 attributed + $20.37 unrecognized = $58.41 reported, to the
cent — the flow is the *whole* delta. #985 (the Timeline order-detail address sheet, filed 07-31) is
this same flow's already-filed sibling and re-fielded three more times this window; its two fixture
files in this pull are corpus-EXCLUDED pending that fix.

**10. A pickup-less job loses its storeKey and its offer→job link → #1000.** 08-08, Zaxbys: the
event chain runs `PICKUP_NAV_STARTED` → (no `PICKUP_ARRIVED`, no `PICKUP_CONFIRMED`) →
`DELIVERY_NAV_STARTED`. Downstream, that job has no `pickup_records` row, an empty `storeKey`, a
null `milesToStore`, and `offer_records.linkedJobId` null — it is the only unlinked accept in the
window — so it drops out of #975's estimate-vs-reality join entirely. The proximate question is why
the arrival never fired (geofence? a fast in-and-out? a missed frame?), but the interesting bit is
downstream: one missing lifecycle edge silently removes a real delivery from three separate
analytics reads at once.

**11. A D6 join miss left one drop storeless — $15.74 attributed to no merchant (08-06 third
drop).** `WARN/StateMachine: D6 join miss … 0 of 0 … across 2 stores`, fired **once** and correctly
edge-gated (the ×23 storm shape from earlier windows did not recur — that gate is doing its job).
Same job as item 7's absorbed H-E-B add-on, which is presumably not a coincidence: a job assembled
from three offers is exactly where the customer-hash join has the least to work with. That drop's
`DELIVERY_ARRIVED` also never fired, so `arrivedAt` is null and its `realizedMiles` reads 0.0 — the
same missing-edge shape as item 10, on a different job.

### Observability, log hygiene, and one fold interaction (items 12–15)

**12. #937's version stamp is blind on a long-lived process → #1003 (documented, low severity).**
`platformApps=com.doordash.driverapp@8.91.7` is constant across all nine days — which is what a
**per-process cache** does when the process spans a nine-day window that straddles the exact app
update item 8 wants to test. The cache-forever decision was deliberate and documented ("a process
restart follows an update in practice"), and this window is the case where that premise doesn't
hold: the process never restarted, so a genuine DoorDash update would be invisible in exactly the
stamp built to make platform-app change visible. Filed as a documented limitation, not a bug.

**13. A text-less ongoing-dash notification is 13.8 % of the entire log → #1001.** 17,605 lines of
`DEBUG/Classifier: UNKNOWN notification — ` (nothing after the em dash) at roughly 1 Hz. The capture
layer handles it correctly — the content-hash dedup means it produced exactly **one** file — so this
is pure firehose volume, not a data problem. It is still worth fixing: it's shortening how far back
`app.log` retention reaches before rotation, which is the diagnostic surface every one of these
analyses runs on.

**14. The Tip Update push is unrecognized → #1002.** `"A customer added $2.00 tip on a past Target
order."` at 08-06 16:34 — which names most of that dash's $2.25 residual. A post-completion tip is
real money arriving after the record froze; recognizing the push is at least a way to *see* it,
whatever the eventual answer is on whether a frozen record should learn about it.

**15. #983's gap fold and the late-sweep dash (informational, no defect).** 08-01 measures **0 gaps**
despite a real ~8-minute wait between orders, and the mechanism is now understood: that dash's
close-out sweep appended every `DELIVERY_COMPLETED` at `DASH_STOP`, so every completion sequences
*after* every accept, and no completion has a "next accept in the same dash" to pair with. That is
precisely the `0 gaps measured` failure shape the #983 checklist item tells the dev to watch for —
so it's worth recording that on a late-sweep dash it is **expected by mechanism, not a fold bug**.
The other four dashes are coherent: 7 gaps spanning 0.6–2.7 min, 5 dash-final drops correctly
excluded as tails, zero cross-dash and zero cross-day pairings.

### Re-confirmed known issues (item 16)

**16.** #985 re-fielded ×3 (the Timeline address sheet; envelopes purged from the pull dir,
corpus-excluded). **#986 is wider than filed** — the `Apt/Suite: ` **colon** form also hex-masks, 7
envelopes; comment posted on the issue. #987 ×5 more Crimson deposit notifications (the dev ruling
is still queued). #922 ~29 frames including two fully-populated offer cards; #912 ~11; #988 ×1;
#801's digit-wheel ×3 (corroborated again). **#935 did not recur** — all three collapsed-summary
captures this window are genuine receipts, correctly classified. **#731: zero flap in nine days**
(1 connect / 0 disconnects across a nine-day single process — the longest clean observation the
issue has ever had); comment posted, and it now looks like a close candidate.

### PII sweep — the rest of the tree (item 17)

**17.** Beyond items 2–5 the pull is clean, and the sweep is worth stating positively because the
new findings are narrow. Only **three** customer-name strings exist in the entire capture tree, and
all three are items 2, 4 and 5. Five raw addresses: four are **merchant** addresses (raw by design,
#886) and the fifth is item 3. Zero PINs, zero gate codes, zero `Customer Notes:` free text, zero
banking hits. `shareable.log`: zero `[scrubbed:…]` placeholders with 28 scrub-diagnostic lines fully
legible — **#862 PASS**, second clean pass. And #910 got its receipts: **mechanism 2 (a click
envelope inheriting the screen rule's redact) fired live for the first time**, on a dropoff-nav
click that arrived masked, while a pickup-nav click on the same window correctly stayed raw — the
scope discriminator holding in the field rather than only in tests. **Mechanism 3 fired 17 times**
(`user_name` ×9, `address_line_1` ×7, `address_line_2` ×1).

### Checklist sweep

**Retired (2/2, deleted from the checklist, receipts above):**

1. **#962 — points-based ratings parse → 2/2.** Second confirmation: `overallRatingPoints` 82→83,
   `tierLabel` Gold, `qualityRate` 100.0, `acceptanceRate` 40→43 all populated across snapshots,
   with `ratings` ×4 and `performance_rate_detail` ×9 recognized. The parse re-anchor holds on real
   fielded data over a nine-day span, not just the discovery browse.
2. **#910 — the five customer-PII leak sites → 2/2.** Mechanism 2's first live fire plus mechanism
   3 ×17, with `pickup_navigation`'s merchant address staying raw (no scope leak). The new leaks
   found this window (#992, #993) are **rule-side omissions, not #910 regressions** — #910 built the
   UNKNOWN-side backstop and it works; a recognized rule with no `redact` is a different gap, which
   is now written down as item 2's structural point.
3. **#937 — recognition-health liveness, the version-stamp + false-positive halves → 2/2.** Zero
   `RecognitionHealth` WARNs across five dashes, the stamp present in every envelope. Standing
   caveat carried forward: the **true-positive** half (recognition actually going quiet mid-dash)
   has still never been exercised, and the per-process cache caveat is recorded as #1003.
4. **#862 — scrub diagnostics survive the shareable-log sink → 2/2.** 28 diagnostic lines verbatim
   with their marker ids intact, zero `[scrubbed:…]` placeholders anywhere in the export.
5. **#701 — over-attribution flag, negative path → 2/2.** Delivered ≤ reported on all five moneyed
   sessions, so no false callout was possible and none appeared. The positive-fire path stays
   unobserved by nature (it needs a genuinely over-attributed dash).
6. **#438 B5 — odometer arbitration, data half → 2/2.** Σ drop-miles ≤ odometer span on every
   session (25.9/23.42, 22.0/21.98, 18.68/9.62, 9.97/8.79, 21.58/21.57), no zeros, no mid-dash
   reset. The 08-06 pair is the loose one and it's loose in the safe direction.

**Bumped to 1/2:** #967/#968 (five Offline ratings stamps — first pull whose build carries the fix),
#315 H5 (the **data** half: 14 `pickup_records`, dwell 0.0–71.1 min all minutes-scale, real store
keys including the #773 address-tier `doordash|h-e-b|@5910`).

**Held, note appended:** #884 (5 more marker fires, still Transfer **out**), #843 (sixth mechanism
corroboration — zero grants in nine days, gate denied `confirm_decline` 21×), #810 B1 (fifth clean
zero, including a three-offer merged job and a two-store stack), #438 B3 (machine half clean over 26
offers / 0 timeouts, but flagged with #991 and #1000), #888 (a fourth family sighted; #922 still
blocks), #983 (item 15's mechanism recorded on the item).

**Broken-in-part:** the **#749** same-customer item — the shape recurred on 08-07 and the closure
half worked exactly as designed, but half the job's pay vanished (item 6 / #996). Per this file's
own rule a broken finding goes to the log immediately, so it is written up above; the checklist item
stays at 1/2 for the closure half with a pointer to #996.

**Added:** **#999** (does DoorDash still show the per-delivery earnings receipt at all — the one
question this desk pass cannot answer) and **#991** (does the spoken verdict still fire, and does a
force-stop bring it back).

**No signal:** every Uber item. Zero Uber sessions in nine days — that is an absence of testing, not
a clean result, and #882/#881, #874/#875, #857, #786, #858, #861, #825, #830 and the #762 D2 family
have now gone three consecutive windows without input.

### Session work log (this desk session)

Issues filed: **#991** (P0 — TTS dead since 08-06), **#992** / **#993** / **#994** / **#995** (four
P1 Pledge leaks; #995 carries a dev ruling), **#996** / **#997** (two P1 pay-attribution defects),
**#998** (return-order flow unmodelled), **#999** (the receipt sheet's disappearance), **#1000**
(pickup-less job loses storeKey + offer link), **#1001** (text-less notification log volume),
**#1002** (Tip Update push unrecognized), **#1003** (#937 version-stamp blindness on a long-lived
process). Comments posted on **#986** (wider than filed — the colon form) and **#731** (nine days
clean; close candidate). Checklist: 6 items retired, 2 bumped to 1/2, 6 held with a dated note, 1
marked broken-in-part, 2 new items added.

### Dev follow-up (same day, after reading the report)

### Same-day campaign addendum (desk, evening — the dev's "get all issues closed" directive)

An autonomous close-out campaign ran the rest of 08-09. Recorded here because several checklist
items changed hands; the PR/issue record carries the detail.

- **Shipped and merged (all through the adversarial loop):** PR #1009 (#991 TTS re-init + liveness
  — the voice fix), PR #1010 (redact batch A: #992–#995, #920), PR #1012 (#996/#997 per-offer pay
  attribution — built to a REVISED design after the first review proved mint-time slot lineage
  untrustworthy; the amendment lives on #997), PR #1013 (#1000 anchorless offer link,
  PROJECTOR_VERSION 10), PR #1014 (redact batch B: #986/#934/#924 + the #985 sheet), PR #1016
  (#1015 — a deterministic UTC-Monday CI failure found when it blocked #1014: the window≡enum test
  pinned a fixed zone against the enum path's systemDefault).
- **Desk-audit closures (evidence on each issue):** #806 (superseded — 303-envelope sweep clean),
  #337 (field-validated 10/10 incl. the original decoy string; item retired below), #863 (44/44
  clean resolutions post-fix), #911 (root-caused to the by-design structural-hash dedup).
  **#294 CONFIRMED as a live defect** (dropoff-completion flows resume GPS at the door — item
  rewritten below), **#827 rescoped** (the "Unknown Store" null-parse is a desk-fixable
  `hasTextContaining: "min"` self-shadow, no longer capture-gated).
- **Security-review catch worth remembering:** batch B's first cut quoted REAL fielded customer
  PII (street+apt, a verbatim gate note, entry codes) in the new rules' own `comment` fields —
  and rule comments ship in the canonicalized APK asset. Sanitized pre-merge to invented
  same-shape equivalents; the raw 07-31 source envelopes were purged after the merge. Rule
  comments are now part of the PII-sweep surface, by precedent.
- **Checklist mutations in this pass:** #337 retired 2/2; #294 rewritten to the confirmed
  mechanism at 1/2; six new items added for PRs #1011–#1014 (above).
- **Standing note:** the dasher's quick-decline automation has been consent-gate-denied since the
  07-31 install (52/52, zero grants) — working as designed, but the #843 consent-prompt UI half
  has still never been exercised by the dev. Grant the capability (or report the prompt never
  appearing) on the next app open.


1. **Item 8's mystery answered from the field:** the receipt sheet doesn't show when the dash was
   started **out-of-zone** in the **"Dash Along the Way"** status — all three receipt-less dashes
   were that shape. Dev reads it as a Dasher-app regression. #999 re-scoped to verification (both
   halves: in-zone receipt returns; out-of-zone absence reproduces — checklist item updated), and
   #996/#997 promoted in practical priority: receipt-less settlement is a routine mode now, so the
   estimate-path split defects are the numbers that stick.
2. **#995 ruled:** the receipt-scan camera is *not* document-image family — capturing the screen
   text is fine — but the customer name on it is a customer name like any other: **redact it just
   like the rest** (recognizing rule + `redact` on the two name nodes; issue retitled to the
   decided direction).
3. **New dev request → #1005:** the dropoff bubble notification rendered "Dropping off for HEB
   Customer" (DoorDash's persona/placeholder name walked straight into our copy). Requested copy:
   title **"Dropoff"**, body **"\<STORE\> Order"** — which also stops putting customer-name-shaped
   strings into system notifications at all.
4. **New dev feedback → #1006:** the redesigned analytics screens carry **too much text copy** —
   distracting. A copy-density audit + trim is filed, with the §9 honesty semantics explicitly
   preserved (condense the rendering, never delete the disclosure).
5. **New dev direction (undecided) → #1007:** pull the lifetime-scoped **Patterns** tab out of the
   windowed analytics hub — either a Home entry tile to a standalone screen or integration into
   the dashboard; shape TBD by dev or a design pass.

---

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

---

## 2026-07-30 (afternoon) — BROWSE-SESSION pull (pull2): DoorDash's NEW points-based rating system discovered, recognized, and re-parsed same-day

**Date:** 2026-07-30 · **Platform(s):** DoorDash (browse only — NO dashes in this slice) · **Branch
under test:** master @ `ff99eb8f` device build · **Field conditions:** the dev deliberately browsed
the redesigned Ratings area to capture the new system. Pull:
`~/dashbuddy/logs/2026/07/30/pull2/` (75 captures, 54 UNKNOWN windows); device purged post-pull.

**The finding:** DoorDash replaced its ratings layout with a **points-based overall rating** —
`Overall rating <N>` (e.g. 73/100 pts), Silver/Gold/Platinum tiers at 55/75/85 pts, and six factor
rows each rendering `label → value → "N of M points" → band` (incl. a NEW **Quality rate** factor),
all **id-less Compose** — the old `textView_title`-anchored parse read null on every field (the
residual PR #926 documented became the fielded reality).

**Design rulings (dev, recorded on #962):** (1) tiers are NOT modeled — DoorDash's gamification is
platform vocabulary and mission-inverted to mirror; two plain FACTS only (`overallRatingPoints`,
`tierLabel`). (2) **No code may ever DEPEND on ratings data** — capture is opportunistic (only
updates when the dasher browses Ratings; may be absent/stale forever); protect-stats is a
*behavior*, not a calculation, and reads no rating. Enforced, not aspirational:
`RatingsSnapshotIsDisplayOnlyTest` source-scans both consumption routes. (3) Trend history — the
one version of ratings data the platform doesn't show the dasher — filed dev-gated as **#964**.

**Shipped same-day (PR #963, merged):** parse re-anchored via legacy-first coalesce (legacy goldens
byte-identical; the drawer-vs-hub tier trap closed with a scoped bind + negative test), 3 additive
`RatingsSnapshot` facts + a plain display row, 4 recognize-only rules (rewards board,
overall-rating explainer, per-order feedback drill-down, pause-offers sheet), 10 corpus fixtures.
**Ride-along Pledge fix:** the sweep caught a fielded leak — the on-time per-order drill-down
renders `Delivery to <customer first name>`, a THIRD conjugation missing from
`CustomerTextMarkers` — added to the SSOT (zero corpus hits; the surface stays deliberately
UNKNOWN and is now scrubbed). Follow-up filed: **#965** (the legacy `deliveriesLast30Days` anchor
never matched — parenthesized label vs fielded plain).

---

## 2026-07-30 — DESK ANALYSIS of the 07-29 evening dash (pull 07-30): first dash on the #914 build — the engine LIVED, money reconciles to the cent

**Date:** 2026-07-30 (dash 2026-07-29 ~15:40–19:27) · **Platform(s):** DoorDash only (Uber never
online; 2 Uber promo pushes all evening) · **Branch under test:** `master` @ `ff99eb8f`
(post-#914/#915, reinstalled 07-29 ~11:02) · **Field conditions:** 3 sessions (one 3.4 h main dash
+ two short early-offline stubs), 12 offers → 4 accepts (3× H-E-B shop, 1× Home Depot) / 8
declines / 0 timeouts, Evidence capture ON (master + Offer). Pull: `~/dashbuddy/logs/2026/07/30/`
(358 captures, 3 logs, shareable.log, db v15 + WAL); device purged post-pull.

### Headline: #909/#914 verdict — PASS, unambiguously

The exact arming condition that killed 07-28 (an evidence-enabled screenshot) fired 21 times and
the engine survived them all: `app_events` continuous seq 1268–1325 with zero cliff, 0 ERROR / 0
`PatternSyntaxException` anywhere, every log-observed milestone 1:1 with events, and the last
drained effect landed 07-30 07:33 — ~12 h past the first screenshot. The #914 defense layers
(Throwable isolation, restart supervisor) went **untriggered** — right outcome (nothing throws
now), but they still have zero field evidence. Checklist → 1/2.

### Money: exact reconciliation

Σ delivery `realizedPay` (27.37 + 23.45 + 13.95 + 8.70) = **$73.47 = `reportedEarnings`** — zero
unattributed, zero orphans, all four rows fully legged (`milesToStore`+`milesToDropoff` =
`realizedMiles` to 6 dp; Σ 44.66 mi ≤ odo span 44.93). Frozen economics uniform
(`fuel+nonfuel == cpm` ✓); pickup dwells sane (3.9–29.1 min). One parse wobble: seq 1289 folded
`RECEIPT_TOTAL` (no per-drop pay) because the expanded summary rendered its pay rows label-first —
harmless single-drop, would defeat a #630 stacked split → filed **#921**.

### WARN/ERROR census

9 WARN / 0 ERROR, all defended invariants (7 `GRACE_COMMIT` timers at exactly the destructive
commits, 2 first-per-process notif-listener disconnects per #731 policy). The sensitive gate
dropped 21 banking frames across three DasherDirect visits (incl. one `crimson_balance`) — zero
banking text reached disk.

### Checklist sweep (details live in the mutated items above)

Bumped to 1/2: #909/#914, #885/#886 (textbook, incl. the pickup-navigation-stays-raw
discriminator), #889 (with the F4 fused-Apt residual → fixed same-day, PR #927), #887 (proven on
the incremental path), #867/#873 (write side). Amended in place: #843 (third mechanism
corroboration; zero automation across 12 offers pre-consent), #888 (2 of 8 families sighted;
accept-spinner covered 1 of 4 accepts → #922), #862 (un-exercised; F1 is a shape it can't see),
#859 (DoorDash 12/12 supporting evidence, Uber-gated), #428-B (still un-flipped), #796 (the
"verify it masks" residual FAILED → #910 V5). No-evidence: all Uber-gated items + #884/#795/#749/
#501/#630/#736/#752/#660p2/#810-B2 (triggers absent; negatives recorded).

### Validated & retired this pass (second clean confirmation)

1. **#823 Phase 1 / PR #848 — units-denominated shop time estimate → 2/2.** Second abundant
   units day: 6 platform-tagged `ShopRate` learns (items/min n=35→38 @ 0.79–0.80, items:units
   n=7→8 @ 0.84–0.86), units offers throughout, and estimate sanity on all 4 accepted jobs (est
   vs actual +2% to +23%, no 2×-class inflation). #588's sibling learner got a clean second data
   point the same evening.
2. **Heads-up banner acts on the RIGHT offer (#438 B4) → 2/2 (desk half; retired).** Third
   consecutive clean pull: 3/3 `OfferActionReceiver` decline taps exact-match their resolved
   events by full hash (8/8 across three pulls total, zero mis-aims, incl. rapid-succession
   shapes). The dev-eyes banner-UX half never surfaced a complaint across five dashes; re-add a
   targeted item if banner UX ever misbehaves.

### PII sweep findings (the Pledge section)

- **F1 (HIGH): #910 field-confirmed live.** The UNKNOWN pre-render workflow sheet shipped a raw
  customer name as `user_name_label`='Delivery for' + bare `user_name` sibling (18:14:06, 34 ms
  before its correctly-masked recognized twin). Split-node: the marker backstop structurally
  cannot reach it (`"Delivery for "` has been in the SSOT since #624 — #910's V5 premise was
  corrected on the issue). Recurs once per job. The #910 build (in flight this session) closes it
  with the node-ID backstop.
- **F1b (MED): chat compose box** (`message_input` EditText) serialized verbatim in an UNKNOWN
  click envelope — dasher-authored free text naming customer order contents → **#919**.
- **F2 (MED): `shopping_item` "Customer Notes"** value raw on a recognized surface (benign this
  dash; the field can carry gate codes) → **#920**.
- **F4 (LOW-MED): fused `Apt <n>` kept its 4-hex** on two envelopes while the split form
  correctly plained → fixed same-day: **PR #927** (plainMask on all 8 uber+doordash fused-Apt
  entries, merged).
- **F3 (pending dev ruling): `earnings_deposit`** notification retains "…deposited to your
  DoorDash Crimson account" + amount on the recognized path — second field instance; the ruling
  on this class is still queued for the dev.
- **F6 (LOW, hardening): DasherDirect entry skeleton** (`dxdr_nav_host_fragment` + loading)
  reaches disk before banking text renders — no leak occurred; id-anchored sensitive arm proposed
  → **#924**.
- shareable.log: fully PII-safe (296 lines, zero merchant/customer/address tokens, zero
  `[scrubbed:…]`); good tag hygiene across 11 tags.

### UNKNOWN census (86 files) → recognition work

Offer-card inflation family 6/evening incl. two fully-populated Decline-less cards (one a rare
Chili's+Pizza Hut two-store stack) → **#922**; dash-control sheet with live "This dash · $50.82"
(== deliveries 1+2 exactly; also missed by `timeline`'s both arms — no scheduled end time) →
**#923**; ratings/rewards drill-down variants ×8 → closed by **PR #926** (corpus pass: 5
fixtures, 3 recognize-only broadenings, additive-only golden); nav skeletons / drawer skeletons /
#801-class transients — left alone.

### Dev field observations relayed this session (recorded, then filed)

1. **Stationary odometer drift:** 2+ phantom miles accrued before leaving the house on 07-28.
   Desk trace: `processLocation` accrues any inter-fix displacement > 5 m with no accuracy gate
   (and `Coordinates` drops `Location.accuracy` entirely) — indoor GPS jitter compounds at 2–5 s
   cadence; not crash-related. → **#918** (with candidate gating directions; interacts with
   #917's heartbeat observability).
2. **"Could we auto-recover / stop losing mileage in the 07-28 engine-death state?"** → **#917**:
   odometer heartbeat log line (engine-independent, reconstructable from logs), dasher-visible
   engine-death alarm (#913's user-facing half), state-observed odometer arbitration (the
   StartOdometer-is-an-effect gap is why post-death dashes measured nothing).

### Session work log (this desk session)

Issues filed: #917–#925 (two dev observations, five desk findings, two recognition
opportunities), #929 (corpus-pruner eviction sort footgun, from the #926 pass), #934
(`address_subpremise_line` plainMask — the #895 class's id-anchored sibling, flagged by the #931
build), #935 (`delivery_summary_collapsed` misclassifies live drop-off sheets — the leak half
closed by #931, the flow-claim half filed). PRs, all MERGED: **#926** (corpus, +5 fixtures/3
ratings broadenings; adversarial review found + fixed one committed `Apt [redacted:<hex>]` token —
the F4 class — before merge), **#927** (#895 fused-Apt plainMask ×8), **#928** (#907 release lint:
es-rUS→es fold per the locale allowlist, `LocaleAllowlistGuardTest`, lintVitalRelease gated in PR
CI — which surfaced its own footgun: a PR body *describing* the skip token skips its own CI),
**#931** (#910: the three-mechanism privacy build — rule redacts, click envelopes inherit the
screen rule's redact via `ScreenContext`, `CustomerTextMarkers.ID_MARKERS` UNKNOWN backstop;
line-by-line adversarial review + a dedicated fable `/security-review` both CLEAN — "monotonically
privacy-tightening on every traced path"), **#932** (#900 matchFirst wire-scoping), **#930** (#902
workflow_dispatch escape hatch + `[force ci]` override — which hit the very dispatch-refusal class
it mitigates; the issue's tree-nudge recipe cleared it), and **#933** — the dev-commissioned
**ground-up adversarial review** (docs-only; `docs/adversarial-review/2026-07-30-ground-up-review.md`,
verdict "viable with binding constraints"). On the dev's go its 12 recommended issues were
validated against source and filed as **#936–#947** (evaluator null-distance fail-open →
no-verdict; recognition-health liveness; en-locale boundary of the marker layers; instrumented-CI
nightly; propExplore nightly; negative corpus + intake hardening ⊃ #929; UI-edge SSOT batch;
FlowCardItem split; UDF cleanups; checklist triage policy [dev-gated]; ROADMAP retire-or-refresh
[dev-gated]; test hardening batch) — builds launched same-session for #936/#947 (+ PR #948 for
#940). #835 (`stateDescription` scrub SSOT) build launched on the post-#931 tree.
`CLAUDE.local.md` updated: GitHub network fixed (dev), retry loops retired.

---

## 2026-07-29 — DESK ANALYSIS of the 07-28 dash (pull 07-29): the mobile bug report ROOT-CAUSED — a P0 silent effect-engine death, not a reinstall bug

**Pull:** `~/dashbuddy/logs/2026/07/29/` (586 captures, 5 logs, db v?, logcat_crash, install_times). Device
purged post-pull; PII files kept for #910 fixtures pending the redacts. **Verdict: the mobile report below
(kept verbatim for the reasoning trail) was right in FEELING and wrong in every specific hypothesis — every
one of H1–H5 + #8 was REFUTED.** Root cause (PROVEN, device stack trace in shareable.log):

**A single unescaped `}` in a regex (`EvidenceFilename.kt:27`, `\{\w*}`, from #859) is valid on the host JVM
and REJECTED by Android's ICU engine.** The class-init failure came back as `ExceptionInInitializerError` — an
`Error`, not an `Exception` — so it bypassed the effect engine's `catch (e: Exception)` and killed the single
serialized queue-drain worker. `process()` kept queuing into an unlimited channel with no consumer: **every
side effect, including `AppEffect.LogEvent` (the ONLY writer of `app_events`), was silently swallowed for the
rest of each process lifetime.** It arms on the first evidence-enabled screenshot per process (gated behind
`EvidenceConfig`), so boot looks healthy then the engine dies mid-dash. Fired twice on 07-28 (14:56, 17:17).
The bubble froze / TTS stopped / odometer never got its Stop → the dev's "the app crashed" (Bug #2) was the
engine dying while the PROCESS lived on, which is exactly why nothing recovered.

**Damage: $89.54 earned across SIX dashes, $7.44 recorded (one delivery) — 91.7% / $82.10 lost.** Only the
48-minute window between the two deaths (bracketing the 16:29 in-place UPDATE, which accidentally HELPED by
restarting the process) recorded anything. session_records: 2 rows for 6 dashes, both unclosed; the
`_unknown`-platform session is the projector's documented fail-soft on a DASH_START that was never written
(NOT a new bug). Zero null-session orphans (#8 refuted). Snapshot recovery + the v8→v9 refold both ran
PERFECTLY (H3 refuted — it's a success story). Listener/a11y/ruleset/pipeline all healthy (H1/H2/H4 refuted).

**FIXED same-session: PR #914 (merged, master @ 570bfaca)** — three layers: the escaped brace; the drain
worker hardened to isolate `Throwable` per-effect + a restart-supervisor (#430 precedent); and an
`IcuRegexGuardTest` source-scan that makes the ICU-incompatible-regex class build-red (JVM tests structurally
can't catch it — the CI blind spot that let this ship). Reviewer proved bare-`}` was the UNIQUE silent
divergence in the whole codebase. Fast-follow #913 (wedged-worker shape).

### Issues filed this analysis
- **#909 (P0) FIXED** — the engine death (above).
- **#910 (Pledge)** — FIVE raw-customer-PII sites incl. a full street address: `delivery_summary_collapsed`
  has NO redact block; `"Delivery for"` missing from `CustomerTextMarkers`; bare `customer_name` on the
  multi-order pickup surfaces. NEXT after #909.
- **#911** — 3 of 4 `dash_summary` frames logged `captured=false` (money-bearing; verify vs FrameGate on a
  CLEAN pull — this pull is #909-contaminated).
- **#912** — recognition batch (Dash-Now loading, dropoff steps, 2-Orders sheet, self-unassign confirm).
- **#913** — wedged-worker liveness (fast-follow of #909).

### Wave first-signals the pull COULD see (build boundary 16:29:29; discriminator = ruleset screen count 90→98)
- **#904 projector v8→v9**: ran + completed clean (1261 events → refold in 3.4s, 0 skipped). ✅
- **#905 accept-spinner**: A/B validated across the boundary (UNKNOWN×2 pre → offer_accepting×5 post). ✅
- **#901 redacts**: 279 masks / 16 rule ids; live-confirmed on Deliver-to/Pickup-for/Message-from/nav. ✅
- **#891 banking marker**: zero Transfer/DasherDirect/Balance hits across 586 files. ✅
- **#872/#869/#876 (uber), #896 (3-mode HUD)**: UNEXERCISED (all 07-28 captures doordash; the one-card HUD is
  explained by the effect blackout, not the resolver — Bug #4). ⚪

### Standing note
The lost 07-28 data is NOT recoverable by the fix (events were never written) — but recognition never broke,
so the five lost jobs' store chains / customer hashes / tips / a 2-store stack are in the surviving snapshots
(cv 8903→9467) + shadow-projector logs; `SessionReplay` reconstruction is an option if the dev wants the
evening back. **DO NOT DASH until the device is reinstalled with the #914 build (Evidence capture ON to
validate the fix).**

---

_The original mobile bug report (record-not-fix, logged remotely) is preserved below for the reasoning trail;
its hypotheses are superseded by the analysis above._

## 2026-07-29 — bug report: mid-dash app update/reinstall breaks dash tracking (logged remotely via chat; no pull yet)

**Platform(s) tested:** Not stated (DoorDash assumed — developer to correct).
**Branch under test:** The post-reinstall build — not stated; presumably `master` at/near
`49380e9` (post-#901 merge) or the 07-26 build wave the 07-27 reinstall was staged for.
Developer to correct.
**Field conditions:** The app was updated (reinstalled) **while a dash was in progress**. The
developer reports two distinct symptoms: (a) the mid-update dash's tracking "completely broke",
and (b) **every dash started since the reinstall** has been tracked incompletely / incorrectly —
i.e. the breakage is *persistent across dashes*, not confined to the interrupted one. No data
pull or log export yet; this entry is the raw report plus desk-side code exploration done
remotely (record-not-fix protocol — no code changes made).
**Correction (later same session):** symptom (b) retracted — the tracking break was **only the
first (mid-update) dash**. Subsequent dashes tracked fine; what they showed instead was a HUD
display behavior: **the bubble displayed only one card, whichever was active** (Bug #4 below,
developer-rated low severity — "not a huge deal"). Items drafted before the correction are
annotated inline rather than deleted, so the reasoning trail stays honest.
**Second clarification (same session):** the developer walked the correction back partway —
"I don't know exactly what was going on tbh so I can't claim that they were tracking
everything." Subsequent dashes are **unverified, not confirmed clean**. And a recalled prior
symptom may fit: **"we've seen the symptom before where the events were just not getting a
dash id assigned"** — i.e. the null-`sessionId` attribution class (#655/#660, the
"(No session)" bucket). That recall makes open question #8 the **leading candidate** for
whatever wrongness the subsequent dashes have: events captured intact but not attributed to a
dash. The pull decides.

### Bugs

1. **Dash tracking broken on the dash that was in progress during the app update/reinstall.**
   ~~All subsequent dashes incompletely/incorrectly tracked~~ — **rescoped by the same-session
   correction: the first (mid-update) dash is the confirmed break**; per the second
   clarification, subsequent dashes are **unverified** (not confirmed clean — the developer
   recalls the events-without-dash-id symptom, see open question #8). Exact failure shape for
   the first dash (missing session? missing deliveries? missing miles? wrong attribution?)
   not yet characterized — needs the verification pull below before triage.
   - **Status:** Open.
2. **Suspected app crash mid-dash, around delivering or accepting an offer** (developer:
   "the app like crashed I think as I delivered or accepted an offer" — not certain it was a
   crash vs. a system kill vs. the bubble/HUD just vanishing). Unconfirmed and
   uncharacterized; needs crash evidence from the device (Verification TODO #6). Two readings
   to keep apart at triage: (a) a one-off crash whose recovery machinery then explains that
   dash's gaps, or (b) a *recurring* crash on the accept/delivery path in the new build —
   which, if it fires every dash, would by itself explain Bug #1's "every dash since is
   incompletely tracked" (each crash loses the in-flight frames and any un-persisted effects
   between the last snapshot and the kill). See H5. *(Post-correction: the "recurring every
   dash" reading (b) is moot — subsequent dashes tracked fine — leaving reading (a), a one-off
   death during the first dash.)*
   - **Status:** Open.
4. **HUD showed only ONE card — whichever was active — on the dashes after the reinstall**
   (from the same-session correction; developer-rated **low severity**, "not a huge deal").
   Not yet clear whether this is (a) the *intended* presentation of the new #867
   follow-active session resolver (the HUD deliberately follows the active dash — and with
   one platform running, one session's card set is correct), (b) a card-stack regression
   (`CardStackAssembler` / `FlowCardMapper` rendering a single card where a stack of
   status+offer+chat cards should interleave), or (c) a #867 Dev-settings presentation
   experiment toggle left in a non-default position after the reinstall wiped/reset prefs.
   Needs a dev glance at the Dev settings toggle state plus one field look at whether
   multiple cards return during an offer-over-active-task moment.
   - **Status:** Open.

### Open questions / investigations (hypotheses — none concluded)

Context that matters for all of these: the 07-26 entry above records **"Device purged + cleared
for reinstall post-pull"** (2026-07-27). So the likely shape is a *full data purge + reinstall*,
not an in-place `install -r` update — which hypothesis family applies depends on which it
actually was, and that's Verification TODO #1.

*Post-correction triage note (amended by the second clarification):* H1/H2/H4 were drafted
against the "every dash since" symptom; the correction deflated them, but with subsequent
dashes now **unverified** they're demoted rather than dismissed (kept below for the trail; the
cheap TODO #2 settings sweep still costs nothing to confirm — note the recalled symptom is
*attribution*, events present but dash-id-less, which points **away** from dead-sensor
hypotheses like H1/H4 and toward #8). Leading candidates: **open question #8** (null-session
attribution — the developer has seen this exact symptom before) for the subsequent dashes, and
**H3** (mid-dash state loss/resurrection across the update) + **H5's one-off reading** (the
Bug #2 crash killing that dash's tail) for the first-dash break.

3. **H1 — listener rebind loss after package update/reinstall.** Android's
   `NotificationListenerService` is notorious for silently not rebinding after its package is
   updated/reinstalled until notification access is toggled off/on (or the device reboots);
   accessibility services can behave similarly after a reinstall. If either listener is dead,
   one whole sensor pipeline is missing — which would look exactly like "every dash since is
   partially tracked" and would persist until manually re-toggled. Would need to confirm by
   checking both toggles on-device and looking for a total absence of notification-sourced
   events in the pull.
4. **H2 — incomplete re-setup after the purge.** A purge+reinstall revokes *everything* the app
   was granted: accessibility, notification access, overlay, location (odometer dead → no
   miles), the battery-optimization exemption (process free to be killed mid-dash → per-dash
   gaps), and — by design — all #843 capability consents (all capabilities return to
   *undecided*; the consent prompt must be re-answered; automation staying off is expected
   behavior, not a tracking break, but it changes what "correct" looks like). If any one item
   of the re-setup chain was missed mid-dash, its absence would produce a *persistent* partial
   tracking failure with a per-item signature (no miles vs. no notifications vs. mid-dash
   process deaths).
5. **H3 — resurrected mid-dash snapshot (in-place-update shape only).**
   `SnapshotStore.restoreLatest()` has **no age guard** — it restores the latest decodable
   snapshot regardless of how old it is (`core/state/.../SnapshotStore.kt:70`; pruning runs
   only on the *write* path, `SnapshotStore.kt:56`). If the process died mid-dash with a live
   Online session and data survived, the next launch resurrects that session as live. The
   stale-grace-end + fresh-mint arm (`core/state/.../JobCloseEffects.kt:39`) covers the
   killed-while-offline-grace-pending shape, but if the session restores as plain Online with
   no pending grace, one possibility is that the *next* dash's Online frames read as a
   continuation of the resurrected session (no new `DASH_START`), mis-attributing the new
   dash. On a purged device this hypothesis is moot (no snapshot survives) — hence
   Verification TODO #1 first.
6. **H4 — fail-closed ruleset gate eating all frames.** If the new build's ruleset load fails
   for a platform (compile reject, duplicate-id skip), the #432 gate drops every frame
   (`core/pipeline/.../AccessibilityPipeline.kt:105` — "Dropping … rulesets not loaded") —
   which would look like *total* non-tracking on that platform, every dash, until fixed. The
   shareable.log check below would show this immediately.
7. **H5 — crash on the accept/delivery path (Bug #2).** *(Post-correction: the recurring
   reading is moot; only the one-off reading below stands.)* A single crash during the first
   dash — around an accept landing or a delivery confirm — would explain that dash's lost
   tail, and it exercises exactly the recovery seams the architecture defends (snapshot every
   5 obs + journal tail-replay, recovery-suppressed externals, `effects_fired` dedup), so
   post-crash state may *look* subtly wrong (e.g. dead GPS until the #438 B5 reconcile fires)
   even when recovery "worked". Needs the crash evidence (TODO #6); the logcat stack decides
   where it goes.
8. **Open question — is there a known post-#660 signature for this? (LEADING CANDIDATE per
   the second clarification** — the developer recalls this exact symptom from before: "events
   were just not getting a dash id assigned".) The "(No session)" bucket + orphan-delivery
   machinery (#655/#660) exists precisely because mid-dash restarts produce null-session
   deliveries; if the pull shows the since-reinstall deliveries landing in that bucket, the
   breakage is *attribution*, with the underlying events intact and recoverable via the
   existing `DELIVERY_SESSION_ASSIGN` correction path (Money-tab callout → orphan list →
   session picker). Worth also checking whether a *session* record exists at all for each
   affected dash (no `DASH_START` observed → nothing to attribute to) vs. exists but the
   deliveries missed it — the two shapes have different causes.

### Verification TODOs

1. **Establish the reinstall shape first** — was it the planned purge+reinstall from the 07-27
   note (data wiped) or an in-place update (data preserved)? This decides whether H2 or H3/H4
   is even in play.
2. **On-device settings sweep** (5 toggles): Accessibility service ON; Notification access ON
   (toggle off/on regardless, per H1); overlay permission; Location "Allow all the time";
   battery optimization = Unrestricted. Note which, if any, were found off.
3. **Export shareable.log** (Settings → Data & Privacy → Export Data) and grep for:
   "Dropping … rulesets not loaded" (H4), "Restored from snapshot at cv=" / "Replaying N
   observations after snapshot" / "State recovery failed — starting fresh" (H3), the periodic
   `PipelineStats` lines (which gate is eating frames), and any ERROR lines.
4. **Data pull:** `session_records` since the reinstall (open sessions with no end, session
   count vs. real dash count), `delivery_records` with `sessionId IS NULL` (the "(No session)"
   bucket — open question #8), and the `app_events` sequence around the **first (mid-update)
   dash** specifically (is the event log itself complete and only the fold wrong, or are
   events missing at the source?).
5. **Consent state:** did the #843 consent prompt re-fire post-reinstall, and what was
   answered? (Explains any "automation stopped working" component of "not tracked correctly".)
6. **Crash evidence for Bug #2:** `adb logcat -b crash -d` (or `adb bugreport`) for a DashBuddy
   stack trace near the accept/delivery moment; on-device Settings → Apps → DashBuddy can also
   show a recent-crash marker. Cross-check the DEBUG firehose `app.log` for an abrupt cut and
   the next launch's recovery lines ("Restored from snapshot at cv=" / "Replaying N
   observations"), which timestamp the death precisely. Note whether it was a real crash
   (stack trace) vs. a system/OOM kill (no trace) — the triage differs. If it reproduces,
   note the exact screen it fires on.
7. **Bug #4 (one-card HUD):** check the #867 Dev-settings presentation toggle's state
   post-reinstall; then, on the next dash, note whether the card stack comes back when an
   offer lands over an active task (multiple cards expected) — that one moment distinguishes
   intended follow-active display from a stack-assembly regression.

### Meta

- Logged remotely by the mobile agent under the record-not-fix protocol — desk-side code
  exploration only, no fixes applied, no issue filed (developer triages).

## 2026-07-26 — five-session day, heavy shopping (pull 2026-07-27, desk-analyzed 2026-07-27)

**Platform(s) tested:** DoorDash (4 sessions, $151.25 total) + one 3-minute Uber window.
**Branch under test:** `ddd9e7ff` (installed 07-24 17:58) — the 07-26 build wave (#868–#879) is NOT
on this device; wave items scored as BASELINE only. Device purged + cleared for reinstall post-pull.
**Field conditions:** San Antonio; units-heavy shop day (7 units-denominated offers); the Uber
window was crippled by Uber's own overlay permission being OFF ("Unable to go online / Turn on
overlay permissions" — 3 offers in 3 min, weight conclusions accordingly).
**Headline: cleanest pull on record.** 0 ERROR, 19 WARN (15 = normal grace timers, 4 = the privacy
layer working) across 11.2 MB; money EXACT on all four sessions (80.55 / 48.40 / 22.30 + a $0);
zero orphans, zero mismatch tripwires, zero D6, zero #863 recurrences (14/14 confirm_declines
clean), zero pipeline restarts; projector fully drained at v8.

### Validated / advanced this entry
- **#827-P1 + #813 — VALIDATED 2/2, retired.** Second pass: both Uber card receipts exact
  (`14 min (3.1 mi)` → 3.1; `39 min (14.0 mi)` → 14.0); 0/2 raw intersection lines (both
  `[redacted:*]`); zero `{` filenames in 46 screenshot saves.
- **#733 — VALIDATED 2/2, retired (real positive).** The two-store single-customer job finally
  fielded (Dunkin' + Taco Palenque, 2 pickups → 1 drop): landed under a REAL store
  (`doordash|dunkin'|357430`) via the earliest-confirmed lineage arm, zero D6 WARNs pull-wide.
- **#630 — VALIDATED 2/2, retired.** Pizza Hut + CVS stack: 6.55 + 6.55 = 13.10 exactly, both
  DROP_SHARE, no $0/NONE row, zero mid-stack tripwires.
- **#823-P1 → 1/2** (first real trigger: 7 units offers, learner 0→n=5 @ 0.83, estimates sane).
  **#749 → 1/2** (the job-61 shape did not recur; closed via the strict arm — coverage-arm path
  itself still unexercised). **#810-B1** third clean false-positive. **#843** ungranted-accept
  corroboration (3 automations fired all day; accept never auto-fired over 10 manual accepts).
  **#588** learner n=26→33 @ 0.79. **#688B** invariant hairline-tight on all sessions (43.47 ≤
  43.48). **#691** OFFER_PAY fallback minted real dollars on 2 receipt-less shop drops.

### Bugs / new findings (issues filed 2026-07-27)
1. **Raw customer name on a RECOGNIZED surface** — `dropoff_multi_order_confirm` redact
   enumeration gap (+ the double-space `Firstname␣␣I.` shape evades the single-space desk recipe)
   → **#885** (HIGH, Pledge). Only raw name in the pull.
2. **Raw customer address in nav maneuver text** — `dropoff_navigation` masks the sheet but not
   the embedded `primaryManeuverText` full-address restatement → **#886** (HIGH, Pledge).
3. **Dasher-banking click leaks** — `Transfer $<amt>` ×2 + `Transfer in` evade the "Transfer out"
   marker (window side held; click envelope is the channel) → on **#884**; fielded files purged
   per the standing procedure.
4. **`stores` orphan rows** — address-tier key left behind when the receipt-tier key supersedes
   (2 phantom zero-visit stores from one stack) → **#887**.
5. **Recognition batch** — accept-spinner (every accept drops its follow-frame), the
   economics-bearing mid-shop pay-adjustment sheet, geofence help flow, handed-directly confirm,
   pizza-bag, substitution sheet, picked-up confirm, wait-survey variant → **#888**.
6. **Single-glyph hash-mask brute-forceable** (`[redacted:5fec]` on a 1-char node) → **#889**.
7. **Uber Match anatomy nailed (feeds #251/#881/#882):** both captured cards CTA=`Match`
   (byte-identical bounds; no Accept counterexample in-window); `Matching may take longer` is
   per-card match chrome; the `Delivery (2)` stack's `orders[]` holds ONE order (chip-anchored
   `presentationKey` — #882's defect currently SHIELDS #830 from store-cycling churn; fix must
   pair display + identity). A live X-tap decline during offer B recorded as TIMEOUT with its
   click envelope on disk — the cleanest #786 before-picture fixture yet.
8. **#867 before-picture:** "Done Ubering!" filed into the DoorDash session's chat during the
   dual-online overlap — exactly the `EndSession` shape #873's review caught; offer lines all
   filed correctly. **#857 baseline:** 1 forged offline in 176 s Uber-online (healed by grace,
   375 ms); even the deliberate stop rode the offline frame, not the go_offline click.
9. **Awaiting dev ruling (unclassified, reported):** the recognized `earnings_deposit`
   notification stores the dasher's own deposit amounts; `shopping_item` stores `Customer Notes:`
   free text raw (no PII in these instances; historically the gate-code carrier class).

### Meta
- One `DELIVERY_COMPLETED` without `DELIVERY_ARRIVED` (the #700 suppressed-arrival residual class)
  → that drop's per-leg trail blank, legacy-delta fallback correct per spec.
- Pull sanitized in place same-day (standing decision): 07-25 dir (98 files, 155 occurrences,
  stable fakes, zero residuals; 1 banking file purged) + 07-27 dir (run in progress at entry
  time). shareable.log: 0 PII across all recipes; hash stability held cross-surface.
- Dev decisions recorded on-issue this session: #251 (offerKind match|direct → #881; batch-eval
  helper; best-match announcement, ordinal-disambiguated, NO new permissions), #867 (follow-active
  + manual switcher; debug presentation toggle incl. merged-stack test mode; platform labels in
  every mode), evidence PNGs stay as-is (pixel-redact answer shaped as #883).

## 2026-07-22→24 — DoorDash + Uber multi-app week (pull 2026-07-25, desk-analyzed 2026-07-26)

**Platform(s) tested:** DoorDash + Uber, heavily multi-apped (both online simultaneously, frequent
app-switching, some idle/gaming stretches).
**Branch under test:** TWO builds — Build A (through 07-24 17:58:30; carried #830/#827/#825/#795/#796,
proven by `presentationKey` present on 07-23 events) and Build B = `master` @ `ddd9e7ff` (installed
07-24 17:58:30 exactly; adds the #843/#845/#847/#848 wave + #416/#590 + the Phase-6 feature modules).
`versionName` stayed 0.230.0 across both — partition by the install wall-clock, not version or date.
**Field conditions:** San Antonio, evening dashes 07-23 and 07-24; 1273 captures, 4 log rotations +
`app.log`, DB at v15/projector v8. Device purged post-pull (standing step 3b).
**Money:** exact to the cent on all three money-bearing sessions ($47.08 / $26.78 / $12.90 — Σ
attributed == reported, zero orphans, zero unattributed). Zero ERROR lines in ~87k; zero pipeline
restarts; `notifListener` flap still gone.

### Bugs / root causes (all desk-confirmed from this pull; issues filed 2026-07-26)

#### 1. Uber declines recorded as timeouts — recording defect, not display (#786/#826/#251)
Dasher observation was "we record the decline, the bubble displays timeout" — desk shows the
**inverse**: the ledger has 162/162 Uber offers `OFFER_TIMEOUT` (lifetime, zero `OFFER_DECLINED`),
resolving in 0.4–18 s vs genuine DoorDash expiries at 45–120 s. `OFFER_TIMEOUT` is the
`resolveOfferOutcome` fall-through default ("no click evidence"), not an expiry finding. What read
as "declined" inside the bubble is the **evaluation verdict banner** ("DECLINE", red/X — advice),
which visually dominates the small "Timed out" `OutcomeChip` on the same resolved card — display
ambiguity filed as #864. Dev identified the decline control: the anonymous **106×106 textless
Button** at the card's right edge (5 on disk; board variant 80×80). Click-side mechanics: UNKNOWN
clicks dedup on a text-less/bounds-less structural hash, so repeat decline taps collapse to one
LRU entry (62 pre-gate anonymous clicks in Uber-only windows vs 24 on disk) — but a decline click
RULE classifies before the gate and bypasses all of it, so #786 alone recovers every witnessed
decline (~36–40% of resolutions show one; the rest have no witness of any kind — swipe-dismiss is
API-invisible, the board rotates focus, requests get taken). Proven-negative: the card exposes NO
countdown (deadline inference unavailable); the platform's own "not a decline" witness exists
(`This request is no longer available` overlay → #858).

#### 2. Trip Radar: the board is the surface, and it reframes "Uber offers" (#251)
**72 of 114** Uber UNKNOWN window captures (and 14/24 UNKNOWN clicks) are the Trip Radar board —
a scrollable multi-request list over the map, each card carrying full offer anatomy + `Match` +
a textless dismiss-X. `uber.screen.offer` anchors on the single focused card
(`primary_touch_area`); board scroll/rotation re-anchors it → replace-or-leave churn. 144
"offers" in ~2 h of Uber-online is a browse feed, not 144 decisions. Full design analysis posted
to #251, deliberately framed as the generalizable **offer-board pattern** (dev direction
2026-07-26: Walmart Spark / Instacart / Shipt are board-shaped too — the focused-card model is
the special case). Key open design questions on #251: card identity/lifecycle (visibility ≠
liveness), browse-vs-presented semantics, resolution honesty for unwitnessed disappearance,
per-card actions, and privacy riding recognition.

#### 3. Uber online detection: idle_map claims offline from absence (#857)
7 real go_online/go_offline toggle pairs recorded as **13 sessions** (6 spurious splits — one
post-Build-B — plus 14 grace-absorbed false claims). 20 of 27 Online→Offline edges fired on
frames with NO offline evidence (partial renders satisfy the rule's two `notExists` vacuously;
worst specimen 115 nodes, zero text). Multi-apping is the amplifier, not the cause: the bad frame
fires while Uber is foreground; switching to DoorDash starves the 10 s grace of contradicting
frames (4 of 6 fatal splits: 8–22 DD frames, 0 Uber frames in-window). DoorDash contrast: 0/6
spurious (its idle_map requires positive markers + rejects). Correcting evidence was discarded —
Trip Radar frames (online-only proof) landed UNKNOWN inside a fatal grace window.

#### 4. Uber screenshots "fading": exit transition, not entrance (#858/#859)
Entrance grabs are clean (settle timing identical to DoorDash, ~0.9 s median). The faded ones are
the **dying card** — Uber renders the expiry message OVER the dimmed card, the rule keeps
matching (no `validate`), and the storeName parse latched the overlay: `offer_records` got
`merchantName = "This request is no longer available"`, TTS spoke it, a screenshot is named it,
and the poisoned `presentationKey` fired a spurious #830 replace (#858). Evidence-quality facets
(43% stale-layer captures, 140 screenshots for 112 offers via parsedHash re-fire, 331
`Offer - {storeName}.png` placeholder files) → #859.

#### 5. Privacy sweep (Pledge surface)
`shareable.log` CLEAN (0 merchant/customer strings in 1588 lines; #772 holding). Recognized Uber
offer surface CLEAN (0/102 raw dropoff lines — #813 redact working). New findings, all filed:
Trip Radar UNKNOWN captures carry raw customer cross-streets (34/114 window + 8/24 click) → #856
(blocked-by #251, the primary control); two bare-customer-NAME UNKNOWN variants (uncatalogued
"Switch to pick up at <store>" sheet; a `pickup_pre_arrival` partial-render race with label+name
as separate sibling nodes — a per-node prefix scan cannot join them) → posted to #806; recognized
`dropoff_pre_arrival` redact misses the `Building Name` value → #860; Uber selfie ID-verification
camera lands UNKNOWN instead of sensitive-blocked (document-image class; nil actual exposure —
camera preview contributes no a11y pixels) → #861; the sink scrub self-scrubs its own
sensitive-marker WARN in `shareable.log`, losing the diagnostic → #862. Known #806 direction-1
residual (address + gate code ×2 + apt unit on UNKNOWN DoorDash sheets) leaked as documented.
**Standing dev decisions:** the 2302 on-device evidence PNGs are unredacted images (customer
cross-streets visible in pulled samples) — retention policy is the dev's call; ditto purging the
07-25 pull's PII-bearing fixtures once #856/#858/#251 builds no longer need them.

#### 6. Smaller finds
Intermittent `confirm_decline` target-resolution failure (4×, one post-Build-B, fail-closed to
manual, #788-adjacent shape) → #863. Three performance-hub recognition gaps survive #837 → #865.
`idle_map`'s false frames also sat inside 2 of 162 live offer windows (minor churn contributor).
07-23 evening UNKNOWN census is a lower bound (burst cap hit at 20:55).

### Validated / retired this entry
- **#794 DasherDirect Transfer-out block — VALIDATED 2/2, retired.** Second clean pass: zero
  banking/balance text in all 1273 captures; the one post-install Transfer-out UNKNOWN click was
  scrubbed at the marker layer; ~262 DasherDirect frames dropped by the sensitive gate.
- **#806 UNKNOWN customer scrub — fired-half VALIDATED 2/2, retired from checklist.** 10 scrubs,
  prefix-only WARNs, `unknownCustomerScrubs=10`. The item retires; the *residual* work continues
  on #806 itself (new variants posted there, Uber-scale variant in #856).
- **#843 consent 1/2** (mechanism proven from logs: migration cleared → nothing auto-granted →
  3 explicit grants; UI half needs dev eyes). **#830 1/2** (core invariant PASS; accept chain
  untested). **#827-P1/#813 1/2** (mi/mi fix + redact PASS). **#796 1/2** (8/11 families clean;
  gaps → #865). **#810-B1** false-positive half re-confirmed. #845/#823-P1/#825/#795/#810-B2
  INCONCLUSIVE — their trigger conditions never occurred.

### Meta
- Two desk-hint greps in checklist items (#845 `Tts` language-apply, #801 `sessionEarnings=`) have
  NO corresponding log sites in current code — a no-hit is not evidence. The hint or the log site
  needs reconciling whenever those items are next touched (SSOT lesson: a desk hint is a second
  copy of a log contract).
- Crash recovery survived an APK replacement mid-session (session-179 straddled the install:
  replayed 2 observations over the snapshot, suppressed effects during recovery, kept odometer).
  Treat that session as an artifact for offer/delivery counts.
- Uber `high_priority` offer pushes (`Uber Request` / `N new requests just came in!`) are unruled
  and arrive regardless of foreground app — relevant to #785 as a board-arrival witness.

### Close-out addendum (2026-07-26 evening — the build wave shipped)
Same-day autonomous build wave off this entry's findings, every PR through the full fable
adversarial loop (each review caught or verified something real — the #869 HIGH alone would have
shipped false declines on the fielded permission-nag sheet): **PR #868** (#861 selfie
sensitive-block), **#869** (#786 decline X rule + own-text-empty guard), **#870** (#862 scrub
self-scrub via MarkerLogId), **#871** (#860 Building Name redact + the new
`hasPrecedingSiblingText` engine predicate, extended to the completion sheet in review), **#872**
(#857 idle_map positive-offline — follow-ups #874/#875 filed from review), **#873** (#867
write-side chat session threading incl. the review-caught `EndSession` gap), **#876** (#858 expiry
overlay guard), **#877** (#865 perf-hub rules), **#879** (#859 presentationHash dedupe + throttle
engine fix + filename sanitizer). Issues closed: #857 #858 #859 #860 #861 #862 #865 #786; new from
reviews: #874 #875 #878 (unseeded fuzz lost a counterexample). Checklist items for the wave are
above (all 0/2). **Still open / dev-gated:** #251 board design sign-off (D2 promotion-only vs
sighting-mints-record), #867 display-side, #856 (blocked-by #251), #826 (accept chain — needs the
accepted-trip dash), #864, #863, evidence-PNG retention + pull-fixture purge.

## 2026-07-22 — dash in progress; DoorDash + SECOND Uber attempt (logged live from the field via chat; desk-analyzed 2026-07-22, results inline + at entry end)

- **Date:** 2026-07-22 (entry written mid-dash from the developer's live narration; numbers below are
  as-reported from the field, not yet desk-verified against a data pull)
- **Platform(s) tested:** DoorDash + **Uber (second real attempt — two deliveries actually completed this time)**
- **Branch under test:** desk-confirmed the post-#822 build (07-20 install: #802/#805/#815/#817/#818/
  #820/#821 all present — evidenced by the "Transfer out" marker block, the #815 scrub counters, Room v14
  + projector v7). The dash itself was 2026-07-21 (five sessions 13:10–19:15); the entry was live-logged
  under the 07-22 date the analysis ran.
- **Field conditions:** concurrent DoorDash + Uber. On DoorDash, an H-E-B Shop & Deliver ($34.45 / 10 mi)
  surfaced the items-vs-units parse conflation (see Bug #3). On Uber, offers were received and at least
  two were accepted and delivered to completion.

### Bugs

1. **[HIGH, uber offer lifecycle]** **Every Uber offer this session was recorded as a timeout** —
   including at least two offers that were actually accepted and driven to completed deliveries. One
   hypothesis chain (desk-unverified): the accept was never registered, so each offer's per-offer
   `OFFER_EXPIRY` timer lapsed and resolved it as `OFFER_TIMEOUT`. Two candidate gaps, not mutually
   exclusive: (a) `uber.click.accept_offer` (requires text "Accept"/"Match" on the offer screen) may not
   match how the fielded Uber accept control actually presents — if the accept is a swipe/gesture or the
   node text differs, no click latch is ever set; (b) the click-less D2 inference (leaving
   offer-presentation to `task:active`, i.e. `uber.screen.active_trip`'s `on_job_view`) only infers an
   accept from a non-task `returnFlow` — if the offer overlay vanished into an unrecognized frame or a
   splash/restart gap first, the edge never qualifies. Would need to confirm from the capture pull which
   frame followed each offer and whether any accept-click observation exists at all.
   - **Status:** CONFIRMED, hypothesis (a) — desk 07-22: ZERO `uber.click.accept_offer` observations
     exist; the fielded accept control produces a TEXTLESS click node (17 textless UNKNOWN uber clicks,
     several coincident with offer→trip transitions), so the text-gated rule can never latch. (b) also
     failed as a backstop but only because each offer had ALREADY timed out before `active_trip` appeared.
     Filed **#826** (design pass: non-text accept signal / expiry-grace interplay / #785 notification
     corroboration).
2. **[HIGH, analytics — likely downstream of #1, not independent]** **Two completed Uber deliveries
   produced no records at all.** Consistent with Bug #1's hypothesis: no registered accept → no
   job/tasks minted → no `DELIVERY_COMPLETED` events in `app_events` → the projector has nothing to
   fold; the analytics layer is likely behaving correctly on empty input. Desk check to confirm: the
   Uber session's `app_events` should show offers + timeouts and zero task-lifecycle events. If task
   events DO exist and records are still missing, this becomes its own analytics bug.
   - **Status:** CONFIRMED downstream of #1, with one refinement (desk 07-22): NOT zero task events —
     ONE job was minted from ambient task frames (`job-uber-…-3`, PICKUP_NAV→DELIVERY_CONFIRMED) but
     store "Unknown", uncosted, and never DELIVERY_COMPLETED (session ended early_offline mid-delivery),
     so the projector correctly folded nothing. Analytics is NOT independently buggy; rides #826.
3. **[MEDIUM, offer-engine — filed #823 same-dash]** DoorDash offer count conflates items and units:
   the H-E-B offer showed 64 (units) but ~30 unique items; `parseItemCount` grabs the first number with
   no label discrimination, inflating the shop-time estimate (~80 min vs ~37) and roughly halving the
   displayed $/hr. Desk-verified during the dash from the capture corpus (offers render
   `(9 items • 11 units)`, `(4 items)`, and units-only shapes). Also confirmed: the post-arrival shop
   screen's true counts are parsed live (`itemsRemaining`/`itemsShopped`) and displayed, but the frozen
   accept-time `estMinutes` is never corrected from them.
   - **Status:** Filed as #823 (three-phase plan) + a same-day scope pivot recorded on the issue:
     possibly skip the offer-time list-peek capture dependency and instead re-evaluate at store arrival
     (a natural unassign decision point). Stacked-offers edge case (multiple peek lists) noted there.
4. **[MEDIUM, uber offer parse]** **Uber offer TIME and MILES get swapped** — time interpreted as
   miles and vice versa (developer observed live; which offers/values TBD from the pull). A strong
   desk-side hypothesis from reading `uber.screen.offer`'s parse: the `distance` finder's regex
   `\d[\d.]*\s*mi` **also matches "38 min"** ("mi" is a prefix of "min"), and Uber renders time and
   distance fused in one node (e.g. "38 min (6.2 mi) total"). Both fields' transforms take the
   *leading* number of whatever node their finder lands on (`parseDistance` grabs the first numeric,
   `timeToCompleteMinutes` uses `parseLeadingInt`), so on a fused node whichever value is written
   first wins BOTH fields — "38 min (6.2 mi)" parses as distance=38 *and* time=38; a "6.2 mi (25
   min)"-ordered variant would parse time=6. If this holds, the fix direction might be anchoring the
   distance regex against the `min` suffix (e.g. `mi\b` semantics) and/or extracting each value from
   the capture group adjacent to its own unit rather than the node's leading number — to be confirmed
   against the session's offer captures before touching the rule. Note this also poisons the #762 D2
   economics on any Uber offer that DOES get accepted (est time and distance both wrong → garbage
   $/hr and $/mi), independent of the accept-detection Bug #1.
   - **Status:** Open.
5. **[MEDIUM, uber offer UX]** **The Uber offer read differs from the DoorDash one** — developer
   expects the identical terse format ("quick real-numbers stats") regardless of platform. Desk-side
   finding while logging: the composition path is ALREADY platform-agnostic by design — one effect
   site (`OfferEffects.kt` eval-landed edge → `SpeakOffer` + `PostOfferNotification`) and one TTS
   template (`TtsEffectHandler.formatEvaluation`: verdict, merchant, $/hr, net, miles, score) with no
   platform branching. **Field follow-up same session resolved the fork:** the read WAS spoken —
   that's how the developer noticed minutes being parsed as miles — so the eval-landed edge and the
   shared template both fired correctly, and the divergence was entirely **degraded inputs**, not a
   different code path. Bug #5 therefore mostly collapses into Bug #4 (the time/miles swap poisoning
   the spoken miles and $/hr), PLUS one new data point: **at least one Uber offer spoke the
   "Unknown Store" fallback** — the `uber.screen.offer` `storeName` extraction (an exclusion-list
   TextView finder: not price/"+"/min/Accept/Match/"Delivery"/etc.) missed on a real offer shape.
   Desk check: find that offer's capture, see which node held the store name and which exclusion (or
   node structure) blocked it. The TTS/format layer itself needs NO change — fixes land in the Uber
   parse only (Development Principle 8 holds: no platform branch in the formatter).
   - **Status:** RESOLVED as re-scoped (desk 07-22): the spoken template is byte-identical across
     platforms (receipts in the analysis); the divergence was Bug #4's swapped inputs. The storeName
     miss ("Unknown Store", offer seq 615) rides **#827 Part 2** — its frame was recognition-deduped so
     the missed node needs a fresh capture. Raw addendum ANSWERED — with a dev framing correction
     (2026-07-22): Uber presented each offer ONCE; DashBuddy recognized the ticking card as a stream of
     REPLACEMENT offers (content changes each tick → new offerHash → replace → re-eval → re-SPEAK: 18
     reads / 17 recorded "offers", one store spoken 3×; every "offer" lived only 3–10 s before a
     TIMEOUT("replaced")). No second offer, no template overrun — but the churn is OURS, not Uber's:
     filed **#830** (identity stability / TTS dedup / outcome inflation; #827's parse fix is the
     prerequisite — the swapped ticking minutes are the dominant churn driver). Also note the recorded
     offer/timeout COUNTS for this session are inflated by the churn variants.
   - **Raw addendum (field, verbatim uncertainty — deliberately NOT hypothesized):** the Uber
     spoken read also seemed to carry *more data* than the DoorDash one — or a *second offer* was
     being read — or something else; unclear from the driver's seat. Developer's instruction: let
     the captures speak. Desk check: count `SpeakOffer` firings vs offers presented in the session
     window, and diff the actual spoken strings (Tts DEBUG firehose lines) against the template.

### Field UX context

1. **Uber pay processing lags (~1 hour).** The developer is entering the completed Uber deliveries
   manually via the drill-down correction path (`MANUAL_DELIVERY`, #650) as Uber finishes processing
   each one. Desk check afterwards: the manual rows should carry `MANUAL` basis and attach to the Uber
   session correctly, and (per Bug #2) they'll be the ONLY delivery rows for this session.
2. Contrast with the 07-19 first attempt: this time offers were being **recognized and recorded
   consistently** (albeit as timeouts) — the 07-19 app-instability capture fragility (that entry's
   INFO #4) was not the blocker. Accept detection is now the visible frontier for Uber (#762 D2 / #785
   territory).

### Verification TODOs

1. Post-dash pull: per-offer frame sequence around each accept moment — is there ANY
   `uber.click.accept_offer` observation? What flow did each offer resolve to (unrecognized frame,
   `active_trip`, splash)? Did any `on_job_view` frames appear at all during the two real trips?
2. Check the Uber session's `app_events`: expected shape under the Bug #1 hypothesis is
   OFFER_PRESENTED/OFFER_TIMEOUT pairs only, zero OFFER_ACCEPTED/task-lifecycle rows.
3. Confirm the manual `MANUAL_DELIVERY` corrections reconcile the session's money once Uber finishes
   processing (Σ manual rows vs the app's own session total).

### Desk analysis (2026-07-22, pull `~/dashbuddy/logs/2026/07/22/` — receipts for everything above)

1. **Money path: DoorDash exact to the cent again** — $71.66 / $34.95 / $20.70 all reconcile exactly
   (6 deliveries, 6 single-drop jobs, all DROP_SHARE on OFFER_FROZEN cpm ~$0.35); zero orphans, zero
   unattributed. The Uber session's two **manual** rows carry MANUAL basis, attach to the correct
   session, Σ $15.94 (Field-UX #1 confirmed). 0 ERROR, 0 restarts, 0 NLS disconnects (#731 stays
   parked); projector v7 watermark caught up.
2. **Checklist advanced:** #815 UNKNOWN scrub → 1/2; #810-B1 tripwire false-positive half → 1/2 (zero
   rows on a normal day); #794/#802 Transfer-out block → 1/2. #820/#821 redact surfaces not exercised
   (but `dropoff_handoff` rendered fully masked: name/address/apt all `[redacted:*]`). #762-D2 item
   BLOCKED-as-fielded (see the annotated checklist item; #826/#827 filed).
3. **PII sweep:** three customer-PII leaks in debug captures, no dasher-banking anywhere. NEW class
   instance: `uber.screen.active_trip` (RECOGNIZED) persists a raw customer name as a bare node — no
   rule redact, prefix backstop structurally blind → filed **#825** (HIGH, Pledge). Two DoorDash
   UNKNOWN sheets ("Current dash" overview, "Close sheet" dropoff detail) leaked bare-node
   name/address/gate-unit — the documented #806 direction-1 residual, now with fresh fixture frames in
   the pull (recorded on #806). #815's marker-prefixed scrubs fired correctly on the same frames.
4. **One more #811-family sighting:** a single confirm_decline fail-closed abort (label "Decline
   offer" present but verification refused on a stale frame) — consistent with the refuted-premise
   analysis on #811; disposition still pending there.
5. **Manual-row mileage note (open question):** MANUAL delivery rows (miles=null) inherit
   odometer-partition miles asymmetrically (P. Terry's 1.52 mi vs Thai Buri 12.14 mi, denting the
   latter's net) — expected #650-era behavior, but worth a look when Uber manual entry becomes routine.

**Issues filed this pass:** #825 (uber active_trip redact, HIGH), #826 (Uber accept undetectable — the
D2 blocker), #827 (uber offer time/miles swap + storeName miss), and — after the dev's framing
correction on the triple-read — **#830** (Uber offer identity churn: replacement storm → repeated
reads, inflated offer/timeout counts, destroyed accept window; #827 lands first). **#823** got its
desk receipts (the 64-unit H-E-B offer + all fielded `(N items • M units)` shapes confirmed) and a
design recommendation (2026-07-22 comment): Phase 1 + a learned items:units ratio for units-only
offers ships now; Phase 2 grows into the primary deliverable (arrival re-evaluation + advisory,
frozen-economics-preserving); Phase 3 offer-time peek DEMOTED — the pull proves two deliberate manual
peek taps produced zero distinct capturable frames (the surface may expand in-window and stay
classified as offer_popup), on top of the actuation-consent/mis-tap/scroll/stacked-list costs.

---

## 2026-07-19 afternoon/evening — four sessions incl. the FIRST Uber attempt (desk-analyzed 2026-07-20)

- **Date:** 2026-07-19 (13:10–19:20; the morning session was logged in the previous entry)
- **Platform(s) tested:** DoorDash + **Uber (first real drive attempt — brief, concurrent with DoorDash, messy)**
- **Branch under test:** still the 2026-07-17 install (#767–#791)
- **Field conditions:** 4 sessions: Uber 13:10–13:32 ($0, one offer timed out), DoorDash $42.19 (3 del) + $85.82 (3 del) + a $0 early-offline. All 6 deliveries single-drop H-E-B shop orders with receipts. One mid-session chat-driven unassign (the $28.50 60-item shop).

### Verification results (desk pass, receipts in the 2026-07-20 analysis)

1. **Money path: exact again** ($42.19 and $85.82 both reconcile to the cent; independent corroboration from the recognized `earnings_deposit` notifications naming exactly the three moneyed sessions of the day). Zero orphans/over-attribution; frozen-econ invariants exact; 0 ERROR, 0 restarts, 0 NLS disconnects; projector v7 caught up.
2. **#788 held under load** (17+ automation taps incl. one Uber decline; zero tie-aborts; one CORRECT fail-closed abort — see Bug #3). **#773 keyed 3 more H-E-Bs by street number.** Per-leg mileage Σ = odometer span exactly on both moneyed sessions.
3. **Uber D2 (#762/#784): attempted but INCONCLUSIVE.** The Uber app itself was unstable (repeated splash/restarts). The one recognized offer (54th St Grill, $8.01/32mi, −$2.28/hr — correctly unappealing) OFFER_TIMEOUT'd cleanly. A Cheba Hut trip was accepted DURING a restart gap — DashBuddy never saw the offer screen, so no job was minted and the D2 accept-consumption path never ran. Capture wins: first real `trip_en_route_pickup` notification envelopes, active_trip frames, splash corpus (#785 fodder). **#786 field-confirmed still open:** `No 'declineButton' target bound for uber` WARN fired.
4. **Not exercised:** #630, #733, #749, #752 (dropoff-phase), #691, #660p2, #778.

### Bugs

1. **[MEDIUM, state — filed #810]** The chat/resolution-path pickup unassign fired NO `TASK_UNASSIGNED` — #736's shipped vocabulary keys on the confirmation screen, which this path never rendered. The next accept **silently reused the abandoned job/task** (same taskId across both accepts); money reconciled only by same-store luck, and the orphaned accept inflates offersAccepted. Second #736 commit shape; replay captures complete (session 114).
   - **Status:** Partially closed — design pass 2026-07-20 on #810 proved the unassign committed via the support-chat path with NO capturable dasher-side surface (recognition can't be the load-bearing fix) and REJECTED the accept-time store-differs guard. Shipped: B1 `JOB_ACCEPT_MISMATCH` close tripwire (#818, replay-pinned on this session's shape) + B3 issue-list variant recognition/redact (#820). Open: B2 orphan offer_record resolution (dev decision, on #810) + #816 (timeline owed-set reconciliation, capture-first).
2. **[MEDIUM, privacy — extended #806]** "Current task" bottom-sheet fell to UNKNOWN carrying plaintext `Pickup for <FirstName> <I>` — "Pickup for " is missing from the `CustomerTextMarkers` SSOT (which has "Deliver to "). Same id-less UNKNOWN class #806 owns; marker addition + sheet recognition both requested there. File purged.
   - **Status:** Partially closed — "Pickup for " marker + the full UNKNOWN screen/notification/click scrub shipped in #815 (2026-07-20); the sheet/task-detail recognition (direction 1) stays open on #806, capture-gated.
3. **[LOW, actuation — filed #811]** One confirm_decline tap aborted fail-closed on a label variant (`NONE passed label verification`) — the #770 "copy change degrades quick-decline silently to manual" residual, first field sighting. Fail direction correct; allowlist needs the variant.
   - **Status:** Premise REFUTED (2026-07-20 investigation, receipts on #811) — no label variant exists; the WARN was the second fire of a benign teardown double-fire and the offer WAS auto-declined (13:38:12.391 `Offer Declined`). Quick-decline never degraded. Disposition (close as not-a-bug vs re-scope to a teardown-frame effect dedup) pending dev decision on #811.
4. **[INFO]** Uber offer capture is fragile during Uber-app instability (the Cheba Hut accept escaped entirely) — can't be separated from app-restart churn this session; watch on the next (hopefully stabler) Uber dash.

### Open questions / investigations

1. Whether the #810 fix should ride the offer-supersession guard (the #596 T2 accept-time cousin) or a resolution-menu recognition signal — design pass on the issue.
   - **Answered (design pass 2026-07-20, on #810):** NEITHER as the load-bearing fix — the fielded unassign had no capturable commit surface (support-chat path), and the accept-time store-differs guard fails both directions (misses same-store, misfires on genuine cross-store add-ons; rejected permanently). Shipped instead: the B1 close-reconciliation tripwire (#818); the platform-authoritative general fix is #816 (timeline owed-set reconciliation, capture-first).

---

## 2026-07-18 (+ 07-19 morning) — five-session day; second field run of the #767–#791 build (desk-analyzed 2026-07-19)

- **Date:** 2026-07-18 (four sessions) + 2026-07-19 morning (one session)
- **Platform(s) tested:** DoorDash. Uber was opened for ~1 minute on 07-19 (~07:15, home dashboard + go-online screens captured) but **never driven** — zero Uber events/sessions; the standing "drive Uber" want remains unmet.
- **Branch under test:** the 2026-07-17 install (master @ `064b3fd4` era, #767–#791). The 07-18 merge wave (#793/#797/#799/#800/#802/#805) is NOT on this build.
- **Field conditions:** 5 sessions, $133.57 total ($113.07 on 07-18 across 4 sessions + $20.50 on 07-19), 7 deliveries — all single-drop single-store jobs (no stacks). 25 offers → 9 accepted / 14 declined / 2 timeout. Two pickup-phase unassigns (both H-E-B). One session ended early_offline with $0.

### Verification results (desk pass, receipts in the 2026-07-19 analysis)

1. **Money path: exact to the cent on all four earning sessions** ($26.45 / $55.12 / $31.50 / $20.50 = reported exactly). Zero orphans, zero over-attribution, all DROP_SHARE. Frozen-econ invariant exact (fuel+nonfuel == cpm, diff 0.0) on all 7 rows. Event stream: 0 ERROR, 0 restarts, 16 benign WARNs; projector v7 caught up (watermark 398).
2. **VALIDATED 2/2 → retired from the checklist this entry:**
   - **#788** automation taps — 30 clean `Single verified candidate` fires (1 accept, 14 confirm_decline, 6 decline, 9 expand), zero aborts, second consecutive clean dash.
   - **#736** pickup-phase unassign — TWO clean unassigns in one pull (07-18 seq359 + 07-19 seq376): one `TASK_UNASSIGNED` each, zero fabricated confirms/completions, event arithmetic exact (9 accepts − 2 unassigns = 7 completed). The dropoff-phase retro-mark (#752) remains unexercised.
   - **#435** pre-map skip — negative half: `mappingFailures=0` across 23 stats lines, 1500 frames forwarded, all deliveries recognized. (Positive half is device-logcat-only by design — retired on the negative evidence.)
   - **#159** basic store keying — all deliveries carry real running keys; remaining scope narrowed to the multi-store-from-one-receipt case (still unfielded).
3. **#773 second field receipt** (already retired 07-17): SIX distinct H-E-Bs now keyed by street number (`@12125/@7330/@5910/@9255/@5601/@12777`) — the 07-13 "4 H-E-Bs conflate to one chain row" world is fully gone.
4. **#731: fourth consecutive clean night** (connects=1/disconnects=0) — environmental root-cause stands; stays parked.
5. **Not exercised:** #630 (stays 1/2), #733 (1/2), #752, #749, #691, #660p2, #778 (shopping stayed on), #722, and every Uber item (#762 D2 skeleton, coarse-close WATCH, #785/#786 captures).

### Bugs

1. **[HIGH, privacy — second field receipt for #794, fix already merged as PR #802]** The DasherDirect "Transfer out" screen leaked again on this pre-fix build (2 UNKNOWN captures, balance amount masked here). Both frames verified to carry exactly PR #802's anchor pair, so the merged fix covers the shape. Files purged from device + pull. The #794 checklist item's confirmations must come from a post-#802 install.
2. **[MED-HIGH, privacy — filed #806]** Five unrecognized DoorDash **task-detail** screens ("Deliver to"/"Pickup for" detail views) leaked raw customer name, full address, and a gate code to UNKNOWN captures. Structural gap: customer hashing requires recognition, and the `CustomerTextMarkers` backstop covers recognized frames + notifications, not UNKNOWN screens. Files purged; surface is trivially re-capturable when the rule is built.
3. **[LOW, recognition — added to #796]** New "Dasher Rewards / Quality Rate" program family (8+ UNKNOWN frames) — recognize-only candidate.

### Open questions / investigations

1. Whether the #802 sensitive block should be joined by an UNKNOWN-screen `CustomerTextMarkers` scrub (the #806 fix-direction question — probably both, see the issue).

## 2026-07-17 — three-session evening dash; first field run of the #767–#791 build (desk-analyzed 2026-07-18)

- **Date:** 2026-07-17
- **Platform(s) tested:** DoorDash (Uber enabled + ruleset loaded, but never driven — stayed Offline all night)
- **Branch under test:** `master` at `064b3fd4` (post-#790/#791 reinstall — first field data from the new sensing edge)
- **Field conditions:** 3 sessions 18:08–22:04, $60.67 reported, 5 deliveries across notably varied types: two grocery shops (H-E-B, two different locations, 24 & 35 min dwells), a pharmacy shop (CVS — Red Card + PIN-required delivery), and a restaurant 2-order single-pickup stack (Pei Wei). 7 offers → 4 accepted / 3 declined.

### Verification results (desk pass — full receipts in the 2026-07-18 desk analysis)

1. **Money path: CLEAN to the cent, all three sessions** (35.47/13.45/11.75 = reported exactly; no unattributed, no "(No session)", frozen-econ invariants hold on every row). Event stream seq 231–283 has zero ghosts/fabrications, zero ERROR lines, `restarts=0`.
2. **Migration/refold health: PASS.** In-place upgrade to Room v14 + projector v7 refold ran clean on 07-16; full history retained since 07-05 (#690 posture held).
3. **VALIDATED 2/2 → retired from the checklist this entry:**
   - **#772** PII-safe shareable.log — all 07-17 chat labels are abstract roles; zero merchant-name leaks on the new build (the raw labels still visible in shareable.log are pre-07-16 buffer).
   - **#773** address running-keys — two NEW H-E-B locations keyed `@9255`/`@5601` on sight; `stores` now holds six distinct H-E-Bs; CVS/Pei Wei keyed by receipt code. Clean split on a second independent dash.
   - **#688B** per-leg mileage — first LIVE stacked job carried both legs correctly (drop A 0.745+4.508, drop B shared-store claim-once null+3.103), Σ 8.356 ≤ span 8.36 boundary-exact.
   - **#438 B4** (desk half) — three more decline `(offer=<hash>)` lines exact-match their `offer_records`.
4. **Advanced to 1/2:** **#788** (10 clean verified-candidate taps — 3×decline_offer, 3×confirm_decline, 4×expand_earnings — zero tie-aborts; the window-root dedup collapsed the twin upstream, so no "Dropped other-window candidate" line was even needed); **#435** pre-map skip (negative half: 350+ frames forwarded, `mappingFailures=0`, every delivery recognized — see checklist-item note on the VERBOSE caveat); **#630** (Pei Wei stack split 6.72+6.73 exactly, no tripwire); **#159** (all stores keyed with real running keys).
5. **Reinforced (no advance):** #438 B3/B5, #701 (all sessions reconcile → no false callout), #315 H5 (dwell minutes-scale sane), #588 (shop rate 0.68→0.72/min, n=6→8, `[doordash]`-tagged), #733 (0 join-miss WARNs — but no multi-pickup job fielded, hard case still open). **#731: NLS connects=1/disconnects=0 across the night — third clean night; stays parked.**
6. **Not exercised (items stay put):** #749 same-customer double, #736/#752 unassign, #660p2 orphan, #691 receipt-less shop, #778 shop-decline (shopping was ON), #728/#722 dev-eyes halves, and **every Uber item** (D2 skeleton, coarse-close WATCH, #785/#786 captures).

### Bugs

1. **[MED-HIGH, privacy — filed #794]** DasherDirect **"Transfer out"** screen variant evaded BOTH the sensitive rule block and the `SensitiveTextMarkers` backstop → plaintext balance ("$X.XX available", amount masked here) written to two UNKNOWN debug captures. Copy drift is the likely cause (screen says "Transfer out"/"$X available"; markers say "Transfer to bank"/"Available Balance"). ~44 sibling DasherDirect frames in the same window WERE blocked — single-variant coverage gap. Debug-only exposure (release = NoOpCaptureBus), never forwarded to the state machine.
2. **[MED, recognition — filed #795]** CVS **PIN-required delivery** confirmation sub-flow entirely UNKNOWN (~6 frames: "Enter PIN…" + intro). Delivery completed correctly regardless; a whole confirmation surface is invisible to recognition. The PIN itself must be treated as do-not-parse when the rule is written.
3. **[LOW-MED, recognition — filed #796]** Batch of UNKNOWN families: drop-off issue/resolution menu, "How was this task?" feedback, pickup receipt-photo, CVS barcode-scan shopping sub-flow (#550-class), CVS "Navigate to zone".
4. **[LOW — noted on #501]** One `dropoff_multi_order_confirm` variant ("Confirm you have the correct order before drop-off.") slipped to UNKNOWN while its sibling recognized.
5. **[LOW — noted on #785]** Lone Uber capture of the night is an unrecognized earnings-promo push — feeds the #785 hazard list (promo/marketing class).

### Open questions / investigations

1. CVS drop (seq 282) used the legacy partition-delta mileage fallback because `DELIVERY_ARRIVED` never fired (dash paused mid-drop, seq 279/281). Documented expected fallback — worth one more sighting to confirm the pause interplay stays benign.

## 2026-07-16 — DoorDash dash (first field run of the 07-12→07-15 merge train; desk synthesis same evening)

**Platform(s) tested:** DoorDash (Uber installed + enabled in-app but never went Online — 0 Uber sessions/captures this dash, so all Uber checklist items and the #785/#786 capture-first list remain open).
**Branch under test:** `master` at `8b924140` (post-#784 — first field exposure of #767/#768/#769/#770/#778–#784).
**Field conditions:** single ~40-min session (16:24–17:04), one offer timed out, one H-E-B Shop & Deliver (9 items) accepted → shopped → delivered, receipt shown.

### Bugs

1. **#734 tie-abort (PR #770) is BROKEN in the field — fast-decline and post-delivery auto-expand are dead (dev-observed live, desk-confirmed).** Both automation taps fired and aborted fail-closed: `confirm_decline` 16:25:17 and `expand_earnings` 17:04:17, each `No decisive match among 2 verified candidates … refusing to click (fail closed)`. The #770 binding-tightening did NOT reduce the candidate count — the fielded frames are recognition-hash-identical to the pre-#770 07-10/07-12 frames, so the same trees still tie, and the new abort replaced a systematically-correct implicit disambiguator — pre-#770 the candidate list was deterministically active-window-first (`getLiveWindowRoots` returns `rootInActiveWindow` first), so "click the first candidate" landed the topmost window's node (the modal/sheet) every time; #770 removed that ordering guarantee and substituted a fail-closed abort, so a still-tying tree now yields a guaranteed no-click (#788 restores active-window scoping explicitly). Desk root-cause (hypothesis, evidence-backed — filed as **#788**): the tightening was architecturally unreachable — a rule `bind` only picks which node becomes the `NodeRef` fingerprint; at fire time `UiInteractionHandler.findCandidates` rebuilds candidates from `ref.viewIdSuffix` alone **across ALL live windows** and re-filters only by the action's label regex, so the bind's `hasText`/`not:` predicates never reach the candidate filter. Evidence: the confirm sheet's 2nd candidate is the offer window's own "Decline" button **behind** the modal (`textView_prism_button_title` in the 16:25:16 `offer_popup` capture); the summary's 2nd `expandable_view` is off-window (the captured window holds exactly one). The ranker then can't recover: EXPAND_EARNINGS has no label and a **blank `ref.text`**, so disambiguation is bounds-IoU only — against bounds that demonstrably drift 233–2365 px between same-hash frames (and one live zero-area rect mid-animation) → UNRESOLVED → abort, every time. One caveat: by the code trace, confirm_decline's EXACT_TEXT tier *should* have resolved ("Decline offer" ≠ "Decline"); its persistent failure implies a transient live twin at fire time — hypothesis, needs a fire-time capture. **If the hypothesis holds, one direction might be** scoping candidates to the recognized window and re-applying the bind's discriminator at fire time (handler change — rule JSON alone cannot fix it); #770's own test replays a single-window corpus tree so it structurally cannot see either failure. Item moved here from the checklist; triage in #788.

### Verification (validated / progressed this dash)

2. **Money path spotless again:** session reconciles to the cent ($20.26 = $20.26); the drop folded `RECEIPT_TOTAL`, net $18.23 against frozen cpm; legs 2.40 + 3.33 = 5.73 mi = odometer span exactly. `PROJECTOR_VERSION` 6→7 wipe+refold ran once on first launch, all 15 historical delivery rows intact. 0 ERROR, 0 restarts, no WARN storm.
3. **#731 NLS counters — VALIDATED 2/2, retired from the checklist.** Second clean confirmation: 2 connects (running counts render), 0 disconnects across the dash; `PipelineStats notifListenerConnects=1 Disconnects=0`. The flap remains absent post-reinstall (environmental root-cause holds). #731 can move to closed-pending-recurrence; the counters stay as the tripwire.
4. **#588 ShopRate — desk half VALIDATED 2/2, retired from the checklist.** `recorded 9 items / 15.0 min = 0.60/min [doordash] → learned 0.65/min (n=5)` — platform-tagged, n 4→5, converging off seed, sane pricing on the fielded shop.
5. **#773/#159 address keys — first confirmation (→1/2).** The four San Antonio H-E-Bs now key distinctly by street number (@12125/@12777/@5910/@7330); this dash's drop resolved to `@12125`. Patterns-tab render half still needs dev eyes.
6. **#772 chat scrub — first confirmation (→1/2).** All 14 chat INFO lines abstract-labeled, zero raw names.
7. **#688B and #733 held again** on this dash's shapes (legs exact; 0 D6 join-miss) but their second confirmations still need a live stacked / multi-pickup job — unchanged at 1/2.

### Meta

8. **Recognition-hash ≠ geometry.** The capture content hash keys on text, so "identical" frames can drift hundreds of px in bounds — relevant to any future bounds-anchored logic (#788 evidence).
9. **UNKNOWN backlog:** 35 UNKNOWN frames on 07-16, clustered at transition moments of otherwise-recognized flows; candidate for a routine InboxProcessor pass, not a regression.

## 2026-07-13 — desk analysis of the 07-10 & 07-12 dashes (pulled db/logs/captures; playbook-first)

- **Date:** 2026-07-13 (dashes analyzed: 07-10 evening — 2 sessions, first-ever desk pass on them;
  07-12 afternoon/evening — 3 sessions)
- **Platform(s) tested:** DoorDash
- **Branch under test:** 07-10 = the 07-10 morning reinstall (pre-wave); 07-12 = `master` @
  `76966bc1` (post-#763 — carries the whole 07-11→12 wave: #736/#745/#749/#752 unassign+lineage,
  #688B per-leg mileage, #159 stores, #733 hash join, #588 shop-rate reset, #660p2, #630, #763
  observability; does NOT carry #767/#768/#769/#770, which merged after install)
- **Field conditions:** 5 dashes total in pull, $99.80 reported across the two days; heavy H-E-B
  shop volume (4 distinct physical H-E-Bs) + first Parry's Pizzeria sighting; no unassigns, no
  multi-pickup stacks, no GoPuff, no orphaned deliveries this pull. Data at
  `~/dashbuddy/logs/2026/07/13/` (db, 3 rotated app.logs, shareable.log, 1539 captures).
  Desk-validation playbook run FIRST per protocol; checklist confirmations recorded there.

### Bugs

1. **Chat INFO line leaks the raw merchant name into the exportable log (Principle 7).**
   `INFO/Chat: message posted [H-E-B's customer] (27 chars)` — 9 hits across 07-07→07-12 in
   `shareable.log` (also `[Willie's Grill & Icehouse]`, `[Parry’s Pizzeria & Taphouse's customer]`).
   The chat persona label is "<store>'s customer" (#568 vocabulary), so the merchant name rides the
   INFO milestone into the exported bug report. The sink scrubber keys on customer markers
   (`SensitiveTextMarkers`), not store names — so this is a call-site leak of exactly the class the
   #551 campaign cleaned (`Pickup: H-E-B` was the original receipt). Likely fix direction: drop or
   genericize the persona in the INFO line (the char count is the useful part); the DEBUG firehose
   can keep the persona.
   - **Status:** Open — filed #772 (2026-07-13).

### Open questions / investigations

2. **All four physical H-E-Bs conflate into ONE chain-only store entity (`doordash|h-e-b|`).**
   `pickup_records` holds four distinct street addresses (Alamo Ranch / N Loop 1604 W / W I-10 /
   Babcock Rd) but `stores` has a single H-E-B row with an EMPTY running key — the exact
   `…|h-e-b|` shape the #159 checklist item warns about. This looks like the **named
   same-chain-two-locations residual** from the #159 design vet, not a regression: the running key
   comes from payout-receipt store forms, and H-E-B receipts evidently say just "H-E-B" (Parry's,
   by contrast, keyed live with `…|stone oak`; Sonic with `…|5703`). Consequence: the dev's
   DOMINANT store folds 11 deliveries across 4 physical locations into one Patterns report card
   with blended dwell stats. The address evidence to disambiguate already exists in
   `pickup_records.storeAddress`. One possibility: an address-derived running-key fallback when the
   receipt form is chain-bare — needs a design pass against D2/F5/F7 (keys must stay
   refold-deterministic).
   - **Status:** Open — filed #773 (2026-07-13).

3. **#731 NotificationListener flap has VANISHED — environmental root-cause now strongly supported.**
   Flap lines/day: 156 (07-07) → 142 (07-08) → 192 (07-09) → 8 (07-10: two disconnect/connect
   pairs at 02:24/02:55, i.e. BEFORE the 03:19 reinstall, then clean) → **07-12: one connect at
   process start, zero disconnects across ~5.5 h of dashing** (the #763 counter line
   `connected (count=1 this process)` is working). Hypothesis strengthened: the old install's
   battery/standby state drove the flap; the fresh install reset it. No code defect in evidence.
   If the flap returns, the #763 counters will quantify it; until then #731 stays data-gated —
   suggest parking it pending a recurrence rather than building anything.

### Verification TODOs (desk-resolved — checklist updated)

4. **Money path: perfect reconciliation.** Delivered (incl. cash) == reported EXACTLY on **all 13
   lifetime sessions** — zero unattributed, zero over-attribution, no "(No session)" bucket. The
   07-12 mixed-basis session (est. OFFER_PAY $10.75 + RECEIPT_TOTAL $17.00) reconciled to the cent.
5. **#688B per-leg mileage: every invariant held.** All 6 new drops carry both legs summing to
   `realizedMiles`; Σ per-drop ≤ session span on every session (two boundary-exact cases:
   21.80=21.80, 12.75≤12.76); the v5→6 refold retroactively repartitioned history — the 07-05
   Bill Miller/Mama Margies stack now reads 3.20/3.55 mi per drop instead of the old 6.76/0.0.
6. **#733: the D6 storm is gone.** 0 join-miss WARNs on 07-12 (all 23 in the log are dated 07-08,
   pre-fix); every drop landed under a real store. (No multi-pickup job fielded, so the join's
   hard case is still unexercised.)
7. **#588 shop-rate relearn: textbook.** `[doordash]`-tagged, `n` climbing 1→4 over the day's 4
   shops, learned mean 0.49→0.67/min converging off the 0.8 seed toward the dev's real pace.
8. **#438 B4: zero wrong-offer taps.** All 5 notification-tap `(offer=<hash>)` lines exact-match
   their resolved `OFFER_DECLINED` events (3 in rapid succession 19:22–19:23 on distinct offers).
9. **#438 B3 machine half again clean:** all 5 new drops costed `OFFER_FROZEN` at cpm 0.351.
10. **Log health (Principle 7): zero ERROR lines; WARN = 3 shapes only** (16 tie WARNs — see #11 —
   plus 7 normal `GRACE_COMMIT` wakes). The channel is legible. Capture redaction spot-check clean
   (per-customer `[redacted:xxxx]` masks, no raw names in envelopes).
11. **#734 premise re-confirmed a 3rd time (pre-fix build):** 12× `confirm_decline` + 4×
   `expand_earnings` "No decisive match among 2 verified candidates … clicking first" WARNs on
   07-12. The build predates #770 (merged later that evening), so this is the last dataset that
   should ever show "clicking first" — the next pull validates the abort-to-manual flip.

### Field UX context

12. **Recognition gaps, all low-volume/known families:** 69 window UNKNOWNs on 07-12 = 23 empty
   transitional trees + the #550 shopping sub-flow (Scan Failed / Enter PLU), the help/resolution
   flow ("Tell us what happened…"), offer-popup PROMO variants (Silver-priority / "paid more as a
   Pro" banners — the offers themselves still recognized on sibling frames), an earnings-history
   screen, and one regular-dash "Navigate to zone" frame. 59 click UNKNOWNs are the same shopping
   family (Found empty shelf / Add to cart / Confirm) + nav taps. Nothing new worth a rule PR ahead
   of the #550 family work.

---

## 2026-07-12 — post-install device look (no dash; first hands-on with the post-#763 build)

- **Date:** 2026-07-12
- **Platform(s) tested:** none (device UI review only — no dash run)
- **Branch under test:** `master` at `76966bc1` (post-#763 merge; carries the whole 07-11→12
  wave incl. Room v11→v14 migrations + projector v6 refold on first launch)
- **Field conditions:** dev installed the build and reviewed the bubble idle card and the
  Analytics → Patterns tab on-device.

### Field UX context

1. **Patterns tab, heatmap section: liked.** The net-$/hr hour×day graph ("the net$/hr thingy
   graph") reads well as-is. (Partial sighting noted on the #315 H5 checklist item — the
   data-correctness half still needs verification.)
2. **Patterns tab, store-cards section: needs work.** Too word-dense for a glance surface, and
   the statistical vocabulary doesn't land — "p95" means nothing to the dev, and it won't mean
   anything to a dasher either. One possibility: fewer numbers per card with plain-language
   labels (e.g. "usual wait" / "worst waits" instead of avg/median/p95), progressive disclosure
   for the rest. Issue filed from this feedback (dasher-friendly store-card copy + density).
   - **Status:** Open — filed #765 (2026-07-12).
3. **#722 gas control validated (first confirmation).** Dev exercised the idle-card gas control:
   "works fine." Checklist item moved to 1/2.
4. **#728 reconfirmed.** Dev restated the direction: gas control and vehicle control should be
   **separate cards**, and the idle-bubble layout overall "could use some polish." #728 (already
   build-ready) is promoted to the front of the build queue.
   - **Status:** Shipped in #767 (2026-07-12, same day) — two full-width cards, ≥48dp targets;
     checklist item added (0/2).

---

## 2026-07-09 — desk analysis of the 07-07 & 07-08 dashes (pulled db/logs/captures; no in-field narration)

- **Date:** 2026-07-09 (dashes analyzed: 07-07 afternoon/evening — 3 sessions; 07-08 evening — 3 sessions)
- **Platform(s) tested:** DoorDash
- **Branch under test:** 07-07 = `master` post-#726 (mode-adaptive gas build); 07-08 =
  `fix/722-693-idle-gas-reachability` build (same + the idle-card reachability fix, merged as PR #729)
- **Field conditions:** dev reported noticing nothing anomalous either dash; this entry is pure desk
  synthesis of `~/dashbuddy/logs/2026/07/08` + `07/09` (db+WAL, logs, ~900 captures)

### Bugs (all filed 07-09)

1. **#731 — NotificationListener flapping.** 129 disconnect/reconnect cycles on 07-07, 240 on
   07-08; bursts to 6/min. Each rebind is a notification-miss window.
   - **Status:** Open (filed #731).
2. **#732 — graced-commit seq/occurredAt inversion, 2nd sighting.** 07-07 seq 70/71 (DASH_STOP
   appended before an earlier-occurredAt PICKUP_CONFIRMED); 07-08 seq 116/117 (Willie's
   PICKUP_CONFIRMED occurredAt 19:24 appended after Sonic's 19:35 PICKUP_ARRIVED — an 11-min
   inversion from the pickup-confirm grace). Not corrupting today (folds are seq-ordered), a
   latent event-sourcing fidelity trap.
   - **Status:** Shipped in #769 (2026-07-12) — Option B (dev-decided): the invariant is documented
     at the `AppEventEntity` contract (sequenceId authoritative; the sole inversion carrier is
     PICKUP_CONFIRMED via the grace-armed `Task.completedAt`, appended at the close-out sweep),
     consumers audited (all sequenceId-ordered or insensitive), and a characterization test pins the
     shape so a silent re-stamp trips it. No behavior change.
3. **#733 — D6 join miss → NULL-store delivery (TOP finding).** The Sonic Drive-In + Willie's
   Grill & Icehouse two-pickup single-customer job ($19.50): the dropoff's customer hash matched
   **0 of 2** pickup-lineage hashes ("possible pickup/dropoff hash-format drift" per the WARN,
   ×23) → `delivery_records.storeName = NULL` → the $19.50 buckets under "Unknown store" in every
   per-store view. Replay captures on disk (`dropoff_pre_arrival` 19:42:44 + both pickups).
   - **Status:** Open (filed #733).
4. **#734 — actuation 2-candidate label ties.** `confirm_decline` (×4) and `expand_earnings` (×3)
   across the two dashes resolved 2 verified candidates and clicked the first. No observed
   misfire, but a tie should abort to manual per the #425 posture.
   - **Status:** Shipped in #770 (2026-07-12) — bindings tightened from the corpus (the old
     `expandButton` bind was decisively tapping the WRONG node — the stats section) and an
     unresolved tie now aborts to manual per the #425 posture. Checklist item above.

### Validations (data-side)

5. **#438 B1 retired here — 2/2.** Both dashes: accepts minted jobs with economics (every
   delivery costed `OFFER_FROZEN`), exactly one `DELIVERY_COMPLETED` per drop, sessions ended
   normally. Single-platform behavior byte-identical at the event-log level.
6. **#691 OFFER_PAY estimate — first live firing, correct.** 07-08 receipt-less H-E-B shop folded
   `payBasis OFFER_PAY` at $14.25; session reconciled exactly.
7. **Reconciliation exact on all 5 moneyed sessions** across both dashes (Σ delivered = reported:
   $104.47 / $32.24 / $14.25 / $19.50; two $0 sessions clean) — no false #701 callout possible.
8. **#605 MODE_RESUME_COMMIT** debounced a real pause cleanly (07-08 16:25 DASH_PAUSED).
9. **Pipeline health:** 0 restarts, 0 mapping failures, sensitive gate dropped 23–24 frames/dash,
   UNKNOWN captured for triage (163 on 07-08 — ~65 window frames to sort).

### Open questions / investigations

10. **The 07-07 15:43 $21.75 H-E-B order — RESOLVED (dev-answered + capture-verified): it was
    UNASSIGNED via the help workflow, and the logged `PICKUP_CONFIRMED` is FABRICATED.** The
    captures show the full flow: `arrived_at_store` 15:21 → shopping → `pickup_issue_menu` /
    `pickup_resolution_options` (recognized) → support `chat_conversation` 15:24–15:26 → the
    **unassign confirmation surfaces 15:43:03–15:43:17, all UNKNOWN** ("Unassign"/"Wait" tokens,
    two UNKNOWN clicks) → `dash_along_the_way` 15:43:18 — which the state machine recorded as
    `PICKUP_CONFIRMED` (fake 21.5-min `shopping` dwell). No stranded-order gap; instead a
    state-vocabulary gap: unassignment has no representation, the exit fabricates a confirm, and
    a continued dash would carry a ghost job (#596 shape) — plus fake dwell into #159's future
    `pickup_records`. Same flow on 07-05 `320-15` (2nd occurrence, no fabricated confirm there —
    unassigned pre-arrival).
    - **Status:** Open (filed #736 — recognition rules for the unassign surfaces + an enumerated
      unassign/abandon event through `StateMachineContract`; cross-linked #732, the seq-70/71
      inversion instance being this very event).

## 2026-07-05 — DoorDash session (afternoon/evening dash; desk synthesis 07-06 from db/logs/captures)

- **Platform(s) tested:** DoorDash
- **Branch under test:** `master` @ `a9a47e08` (post-#679 — the full 07-05 evening wave through P7 logging phase 1; pre-#680..#686)
- **Field conditions:** ~4.5h afternoon/evening; 8 offers (5 accepted / 3 declined), 5 deliveries — three big H-E-B shops + one two-pickup stack (Bill Miller BBQ + Mama Margies); one final shop accepted then manually unassigned. Reported gross $104.47. Data pulled 07-06 to `~/dashbuddy/logs/2026/07/06/` (46k log lines, 464 captures, db integrity ok). NOTE: app data was manually cleared before the dash, so the on-device event log starts at this session (07-03 backup preserves older history; hardening → #690).

### Bugs

1. **Receipt/pay capture gap — 3 of 5 drops completed with NO pay signal** (payBasis NONE): the first solo H-E-B shop AND both stack drops. $39.45 of the reported $104.47 landed as unattributed until hand-corrected. Hypotheses + triage plan (the missing receipt frames are likely among the 141 UNKNOWN captures) → **#691**. The stack's two completions minted at the same instant (16:08:19) with the Bill Millers drop carrying no store name — the #526/#557 family; the receipt was never captured at all, so the #528 apportioner had nothing to split.
2. **Post-dash bubble showed a STALE dash** — after the $0 unassign session, the bubble fell back to the last dash *with money* instead of the actual last session. Direction (dev): the post-dash bubble should render a last-dash SUMMARY (design handoff) + just-in-time actions (vehicle, gas price) → **#693**.
3. **CSV summary claims tax year 2025 / $0.70** for 2026 driving (found via the export) → **#689** (2026 = $0.725, per-year lookup; Time tab card is a second consumer; unknown-year = latest-known + disclaimer, decided).

### Field UX context

4. **Corrections (#650 PR B) carried the session's accounting.** Adjustment trail (log events 57–61): H-E-B → 26.50; the empty-store stack drop → 11.375 → 7.60 (two tries), re-saved with note "bill millers" as a store-name workaround; Mama Margies → 5.35. Split = the offer's exact $12.95. The missing edit surface (store/tip/miles + cash tips, per-drop splits, per-leg mileage) is **#688** (grounded plan on the issue; per-leg odometer data verified already wired — every lifecycle event carries `metadata.odometer`).
5. **Hub copy says "dashes"** — platform-flavored vocabulary for the generic session concept → **#694**.
6. **Session-2 unassign captured cleanly**: accepted $24.25 H-E-B shop 18:39, arrived 18:52, unassigned (dev-confirmed), session grace-closed 19:10–19:11 with a CORRECT $0.00 reported summary. The unassign-flow frames are in the UNKNOWN captures → **#301** corpus.

### Meta / architecture

7. **The two-pickup stack capture SATISFIES the #526 field gate** (offer frame + both pickup arrivals as separate tasks + per-leg odometer). Evidence posted on #526; build unblocked.
8. Log health: 8 ERROR lines total, all the EIA fetch retry class (succeeded 07-05 morning, failed on 07-06 wifi — WorkManager self-heals); `Timer Expired` WARN spam + EIA-at-ERROR are P7 residuals → **#692**. No pipeline restarts, no recognition crashes, no recovery events.

## Untriaged — carried over from scratch notes

- **Final dash-summary may be unreachable from the idle/offline screen.**
  Hypothesis: the idle-map offline screen shows *before* the dash summary, and
  there may be no way to reach the summary actions once on idle/offline. Needs a
  field repro + capture to confirm.
  - **Status:** Triaged → tracked as #279 (summary attribution fixed in PR; the
    "summary after the idle screen" ordering was the root cause). Field-validate
    via the #279 checklist item above.

---

## 2026-06-25 → 06-30 — DoorDash field week (desk synthesis of five dashes)

- **Date:** 2026-06-25, 06-26, 06-28, 06-29, 06-30 (analyzed 2026-07-01)
- **Platform(s) tested:** DoorDash
- **Branch under test:** `master` at `44e0d0e2` (post-#584 — includes #577 quick-decline
  auto-confirm, #578/#583 rich offer heads-up with in-card Accept/Decline, #517/#518/#498
  ghost/phantom fixes).
- **Field conditions:** Five dashes, San Antonio, 44 offers total (per the db: 19 accepted /
  24 declined / 1 timeout as *recorded* — see Bugs #1/#2 for why recorded ≠ real), heavy H-E-B
  shop-and-deliver mix plus several 2-store stacks, one alcohol drop, one at-store unassignment
  and one order swap. Desk synthesis of the pulled logs (~208k lines), `dashbuddy-v2.db`
  (app_events seq 1–249), and the capture corpus — not in-field narration. **Hypotheses, not
  concluded fixes.** Headline receipts (seq 226/227, 141/142, 241/242, DASH_STOP arithmetic,
  the H1 click-by-click log lines, the PII captures) were independently re-verified against the
  raw db/logs after the multi-agent analysis. Zero ERROR lines and zero pipeline restarts all
  week — the pipeline itself was healthy.

### Verification TODOs (checklist confirmations this week)

1. **✅ #583 / PR #584 — in-card heads-up Accept/Decline buttons — VALIDATED (well past 2/2).**
   `OfferActionReceiver` fired on all five days (≥16 clean end-to-end accepts/declines), each
   resolving to the matching db outcome within seconds. Frame-continuity around the taps shows
   they came **from the floating heads-up, not a shade-pull** — the field gate ("if they only
   work after a shade-pull, revert to the action row") **passes affirmatively**. The one failure
   all week (Bug #5) was the shade/lock case — the inverse, handled fail-closed. **Gauge-ring
   visual is NOT verifiable from desk data** — kept on the checklist as a visual-only item.
2. **✅ #578 / PR #581 — rich offer heads-up card — mechanically confirmed (≥2/2).** The card
   posted with live PendingIntents on every offer (every receiver fire proves an actionable
   rendered card); zero RemoteViews/inflate/fallback errors in ~208k lines. Verdict banner /
   ticking countdown / badge **visuals** stay on the checklist for developer eyes.
3. **✅ #577 quick-decline — re-confirmed mechanically and RETIRED from the checklist** (was
   validated 2/2 on 06-24): 24/24 confirm sheets auto-clicked, latency consistently ~0.52–0.66 s
   from sheet recognition (~1.2–1.6 s tap-to-confirm end-to-end — the 06-24 "feels slow" now has
   numbers), throttle + fail-closed refusals worked. **New posture caution:** it is a causal
   ingredient in Bug #1 (it forecloses the change-of-mind window the confirm sheet provides),
   and every fire rides the Bug #6 tie-break path.
4. **✅ #457 heads-up notification path — RETIRED** (validated 06-24; this week adds ≥16 more
   clean receiver-path confirmations over five days).
5. **✅ #554 ShadowProjector — 2/2, RETIRED.** 06-29/06-30 chains accurate vs the db; it also
   faithfully *exposed* the merged-job defect (Bug #3): `job …-55 store-chain (2): [Pizza Hut] …
   [H-E-B] offer=— dropoff=— payout=— custs=[]`.
6. **#557 multi-store dropoff store — 1/2.** 06-29 Sally Beauty + Panda Express stack: all four
   tasks correctly store-attributed with distinct customer hashes. Remaining gap is fold-in/leg-2
   dropoffs (`storeName=null` → "the customer") — Bug #10b, #526-widened scope, not a #557
   regression.
7. **#556 shop time model — 1/2.** 06-30: a 41-item H-E-B priced at $9.94/hr (a sane decline);
   shop offers all week in a believable $14–27/hr band; `ShopRate` learning lines present (11×).
   Caveat: a 44-item shop estimated ~83 min vs ~2.5 h actual — the seed is still optimistic on
   giant shops.
8. **✅ #517/#518/#498 fixes HELD all week** — zero duplicate-hash re-receives, zero $0/phantom
   completions, zero cross-job completion leaks. (Bug #2 is a *new* ghost variant that slips the
   #498 pay gate — not a regression.)
9. **Alcohol flow (#463 reversal, PR #485) — 1 clean sighting; the sibling #462/#460 dropoff
   item is found BROKEN-IN-PART and moves here.** 06-30 CVS alcohol drop: verify-checklist +
   ID-check screens recognized, the scanner capture contains instruction text only, events
   hashed. But the item's own broken-criterion — "raw recipient name/address appears anywhere" —
   is tripped by Bug #7 (raw PII in recognized/UNKNOWN capture envelopes), so the item leaves
   the checklist as partially broken rather than confirmed.

### Bugs

1. **Decline recorded as OFFER_ACCEPTED — the remembered incident, pinned (06-30 16:59, Burger
   King $6.25, seq 226/227).** Dasher tapped Decline (16:59:14.872,
   `app_log_rotated_20260630_174600.log:6295`); #577 auto-confirmed the confirm sheet correctly
   at 16:59:16.128–16.378 (`:6323–:6328` — the click echo classified as the **confirm sheet's**
   `decline_offer` while the sheet was still on screen, so the "late auto-click retargeted the
   offer screen's Decline" theory is **refuted**); the offer card resurfaced at 16:59:16.969
   (consistent with a "Review offer" tap — no click rule exists for that control) and the dasher
   tapped Accept at 16:59:17.582 — 1.2 s **after** the decline had already gone out. DoorDash
   kept the decline: no job/pickup ever formed (the only accept all week with no immediate
   PICKUP_NAV_STARTED; next event is the next offer), and DASH_STOP seq 249 records
   `offersAccepted=3`, `$45.75 = 9.25 + 9.75 + 26.75` — the $6.25 absent. The app committed
   OFFER_ACCEPTED by last-click-wins at card-vanish (16:59:24.760); the chat surface showed
   "Offer Declined" then "Offer Accepted" 1.2 s apart (Bug #9).
   - **Likely cause (hypothesis):** the pending offer resolves from the last click before the
     card disappears, with no reconciliation against job formation — the machine already had the
     contradicting signal (no task minted, idle screen at 16:59:32). One possibility is a
     job-materialization grace check that flags/downgrades an accept with no ensuing pickup;
     another is latching "decline submitted" once auto-confirm fires. Separately, #577's ~0.55 s
     speed forecloses the change-of-mind window the confirm sheet exists to provide — a product
     posture question beyond the state bug.
   - **Status:** Open — filed **#594** (2026-07-02 triage).
2. **Ghost offer re-mint at accept — the remembered ghost, pinned twice (06-28 seq 141/142,
   06-30 seq 241/242).** After a real accept of a SHOP offer, the post-accept transitional
   `offer_popup` frame (order rows/store gone; one frame carries a raw UUID in the store slot;
   pay + distance persist) re-parses as a **new** offer (`orders=[]`, `itemCount=0`, "Unknown
   Store", **different offerHash**) which **replaces** the just-accepted pending offer — the
   real OFFER_ACCEPTED rows literally carry `description='Replaced by new offer'`. The eval
   loopback then runs on the ghost (0 items zeroes the #556 shop time → inflated $/hr), TTS
   speaks "Accept. Unknown Store. 40/56 dollars an hour net", a bogus `[Good Offer]` chat card
   posts, and 1–2 s later the ghost resolves as a user-visible "Offer Timed Out!" — an **orphan
   OFFER_TIMEOUT with no OFFER_RECEIVED row** (verified: both TIMEOUTs match zero received
   hashes; this is the +1 over-resolution on both days). Ghost frames on disk:
   `offer_popup/2026-06-28_17-16-35-464__…__08da30.json`,
   `offer_popup/2026-06-30_17-20-17-111__…__cf8cc3.json`.
   - **Likely cause (hypothesis):** offerHash is computed over parsed fields that go degenerate
     on the teardown frame, minting a fresh identity the #498 guard can't catch (that guard
     gates on missing pay; this frame *has* pay). One possibility: reject/absorb an offer parse
     with empty orders / UUID-shaped store text when a same-pay/same-distance offer was decided
     <2 s earlier — needs a settle/validity design call. (This is the post-accept trigger
     predicted in the 06-15 checklist note under the old #498 item.)
   - **Status:** Open — filed **#595**.
3. **Receipt-skipped completions: 6 of 21 confirmed dropoffs never got DELIVERY_COMPLETED
   (verified 21 vs 15 week-wide), and the never-closed job absorbs later offers.** 06-29 job-42
   (Sally Beauty + Panda Express, $15.40): both drops DELIVERY_CONFIRMED, **zero** completions.
   06-29 Pizza Hut: `dropoff_completed_confirm` → straight to `waiting_for_offer`, no receipt →
   no completion (~$12.00 actual pay never attributed; internal 29.49 vs summary 41.49) — and
   the still-open job then **absorbed the next accepted H-E-B $13.50 offer** as tasks 58/59
   under the Pizza Hut jobId. 06-30 job-61 spans **three independently accepted offers** (BJ's
   $9.25, CVS $9.75, H-E-B $26.75) with 3 confirms / 1 completion — $19.00 of $45.75
   unattributed per-delivery. Session totals stayed correct everywhere (DASH_STOP is the outer
   truth anchor); the damage is per-job/per-delivery attribution.
   - **Likely cause (hypothesis):** DELIVERY_COMPLETED mints only from the delivery-receipt
     authoritative window (#431); when DoorDash chains the next offer over the receipt or skips
     it entirely, no completion fires and the job never closes, so subsequent accepts look like
     add-ons. One direction might be a job close-out on `waiting_for_offer` / fresh-store
     accept. Beyond the known #528 scope (zero-completion jobs and cross-offer absorption are
     new); touches #527 job-lifetime.
   - **Status:** Open — filed **#596** (blocks #528).
4. **Order unassignment/swap is unmodeled — and can confirm the wrong pickup.** 06-26 Petsmart:
   the order was unassigned-with-no-pay at the store (resolution-sheet capture shows "Unassign
   with no pay / Completion Rate will drop to 97%"), but at the dropoff transition the stepper
   minted PICKUP_CONFIRMED for **Petsmart — the abandoned order** (seq 85) while Panera
   (actually picked up and delivered) never confirmed; the job closed at $8.90 vs $21.07
   offered, the −$12.17 delta uncaptured. 06-28: an $18.50 H-E-B order broke at the store →
   ~30-min support chat → "Missed Delivery" notification → a $22.76 replacement offer folded
   into the same job-38, re-emitting duplicate PICKUP_NAV/ARRIVED (seq 143/144); the $18.50
   accept dangles forever and **DoorDash's own summary says 2/4 accepted vs DashBuddy's 3**
   (DASH_STOP seq 150: $35.80 = 13.04 + 22.76 exactly — the $18.50 absent).
   - **Likely cause (hypothesis):** the event vocabulary has no unassign/cancel/supersede
     terminal for an accepted order, and the issue/resolution screens are recognize-only with
     no state transition; the pickup-confirm inference fires for the *active* task at phase-flip
     regardless of which order was physically abandoned.
   - **Status:** Open — routed to existing **#301** (scope widened with this evidence).
5. **#583 in-card Accept failed once, fail-closed ("No live windows") — 06-25 16:44:38, $13.50
   H-E-B.** The only receiver-path failure of the week (~1/18). The receiver fired
   (`app_log_rotated_20260625_173549.log:7881–7887`) but window enumeration found no DoorDash
   window; a ~5.5 s accessibility-frame gap brackets the tap — consistent with a
   shade-pull/lock-screen press (the SystemUI-takeover class #457 was built around). Dasher
   recovered manually ~25 s later; recorded outcome correct, but the button was visibly dead —
   a near-miss on losing the offer to timeout.
   - **Hypothesis:** one possibility is a short bounded re-resolve retry (~1–2 s) after the
     shade collapses; the fail-closed denial itself behaved correctly.
   - **Status:** Open — filed **#602**.
6. **Verified-click bounds pinning never matches — every automated click rides the tie-break.**
   ~40+ WARNs `No exact bounds match among N verified candidates — clicking first` across
   confirm_decline (21–24×), decline_offer, expand_earnings; bounds pinning matched **zero**
   times all week. It picked the right node every time this week, 5 stale re-fires were
   correctly refused fail-closed ("NONE passed label verification … refusing to click"), and
   there was one throttle escape (06-26 18:32:26 → 18:32:27.311 — 1.29 s apart, outside the
   1000 ms window). But "first of N same-labeled candidates" is #425's exact threat model, one
   DoorDash layout change from a wrong click.
   - **Hypothesis:** recognition-time bounds don't survive to click-time re-resolution
     (drift/coordinate space), so the exact-bounds fast path is dead code in practice; the
     confirm sheet's Button also has no own text (label lives in a child TextView), making
     candidate enumeration ambiguous. Also a frequent-benign WARN drowning the channel
     (#551/Principle 7 concern).
   - **Status:** Open — filed **#600**.
7. **Raw customer PII persists in capture envelopes (the event/log path is clean).** Recognized
   dropoff captures (`dropoff_handoff`, `dropoff_navigation`, `dropoff_pre_arrival`, 06-29/06-30)
   hold plaintext "Deliver to ‹customer name›" + full street address + one gate code; a pickup
   issue screen holds "For ‹first name + last initial› • Petsmart"; 23 `pickup_arrival` captures
   + UNKNOWN captures hold "Order for ‹name›". The `SensitiveTextMarkers` UNKNOWN backstop is
   marker-based and cannot catch bare names. **Contrast (working as pledged):** DELIVERY_*
   payloads carry sha256 hashes only, and full-week INFO+ scans found **zero** raw customer
   strings (personas read "H-E-B's customer"/"the customer"). Debug-only capture binding (#346)
   limits exposure to the dev device, but the pledge is hashed-at-edge-before-persistence —
   today it is hashed-in-parse-while-raw-on-disk.
   - **Status:** Open — filed **#598**. (Trips the #462/#460 checklist item's broken-criterion — moved here.)
8. **Dasher-banking pledge hole: `doordash.notification.crimson_balance` recognizes and stores
   the Crimson/DasherDirect Savings Jar balance raw** — "…balance is now $‹amount›", 9 capture
   files spanning 06-03→06-28. The pledge blocks the dasher's banking/DasherDirect balances at
   the matcher layer (never parsed or stored); a recognize+capture rule is the opposite.
   `earnings_deposit` is the borderline sibling (stores transfer notifications raw).
   - **Hypothesis:** likely added to name/suppress the notification as noise without marking it
     sensitive; the notification capture path stores whatever a rule recognizes.
   - **Status:** Open — filed **#599**.
9. **Chat/bubble outcome cards are click-driven, not committed-outcome-driven (SSOT
   divergence on the trust surface).** "Offer Declined" → "Offer Accepted" 1.2 s apart (Bug #1);
   "Offer Accepted" → "Offer Timed Out!" 1–2 s apart (Bug #2, both instances). The bubble feed
   contradicted itself in every incident window this week.
   - **Hypothesis:** chat effects fire eagerly on click/resolution observations instead of
     deriving from the single committed outcome event (Principle 5).
   - **Status:** Open — filed **#601**.
10. **Smaller items:** (a) stacked 2-pickup jobs: only the **last** store gets PICKUP_CONFIRMED
    (3/3 stacks; N−1 stranded at "arrived" — fully explains 06-26's 5-arrived/3-confirmed
    aggregate); (b) `dropoff_handoff` matches en-route "Deliver to …" frames → false-early
    arrivals (`arrivedAt == phaseStartedAt` on 2 of 3 drops 06-30; the 06-21 Walgreens class)
    and leg-2 drops miss DELIVERY_NAV_STARTED + store attribution (`storeName=null` → "the
    customer"); (c) duplicate DASH_PAUSED via pause→grace-resume→pause (06-28 15:04:32/15:04:38,
    two user-visible "Dash Paused!" cards); (d) **notification effect idempotency keys are
    global, not per-notification** — `effect:doordash.notification.new_order:log` fired once
    on 06-25 16:31 and deduped every subsequent new-order notification all week; (e) all 46
    offer screenshots saved as the literal filename `Offer - {storeName}.png` (template never
    interpolates; same class as the #433 dedupeKey lint, for the screenshot effect) and the
    DashSummary screenshot double-fires at session end; (f) tip parse label junk
    (`customerTips[{type:'799'|'618'}]` — stray UI tokens; amounts correct); (g) the UNKNOWN
    capture cap (200) was hit 25 min into 06-28, suppressing triage captures for ~4 h including
    the entire support-chat flow.
    - **Status:** Open — filed/routed 2026-07-02: (a) → existing **#526** (comment); (b) → **#603**;
      (c) → **#605**; (d) → **#604**; (e) → **#606**; (f) → **#607**; (g) → **#597** (with the
      click-capture starvation below).

### Field UX context

- DoorDash frequently **skips the per-delivery receipt** — chaining the next offer over it or
  returning straight to `waiting_for_offer` — so completion minting can't rely on that surface
  alone (Bug #3).
- The offer card has a **post-accept transitional render** (order rows collapse; one frame shows
  a raw UUID in the store slot; a "Pro / High paying offer" banner variant) that still carries
  pay + distance and the Accept/Decline chrome (Bug #2's trigger).
- The confirm-decline sheet offers **"Review offer" / "View offer details"** back to the offer
  card — with auto-confirm ON that path is a trap: the decline is already committed server-side
  by the time the card returns (Bug #1).
- Store issue → support chat → **"Missed Delivery" → replacement offer** is a real mid-job flow
  (06-28: ~30 minutes in-chat, then a smaller replacement of the same shop).

### Open questions / investigations

1. Bug #1: no click rule exists for "Review offer" — that step is inferred from the screen
   return + the developer's recollection. Would a click rule/capture for that control settle
   the sequence beyond doubt?
2. Bug #2: is the degraded/UUID offer frame **only** an accept-teardown render, or also an
   arrival-time pre-render? (UNKNOWN captures suggest arrival-time UUID frames on 06-25 that
   happened not to match.) Determines where a validity/settle gate would belong and whether it
   risks rejecting real offers.
3. Bug #3: Pizza Hut's ~$12.00 is inferred by subtraction (41.49 − 29.49) — no receipt ever
   rendered. Is any DoorDash surface (earnings tab) worth capturing to attribute it?
4. Bug #5: the shade/lock tap is inferred from a 5.5 s frame gap — is there a cheap
   screen-state/SystemUI signal worth logging at fire time to distinguish shade taps?
5. 06-28 DoorDash summary "2/4 accepted": does the platform count the swapped order as a
   decline, or exclude it? Affects what an unassign/supersede terminal should reconcile against.
6. Click envelopes were not persisted this week (`captured=false` on click lines) — intended?
   It cost forensic corroboration for both headline incidents. **RESOLVED 07-02: not intended**
   (dev: clicks should record on debug builds). Root cause found at desk: `DiskCaptureBus`
   in-memory contentHash dedup + the a11y process living for days → repeat-tap clicks dedupe
   forever (37→17→1→2→0 captures across the week); same family as `FrameGate`'s per-process
   UNKNOWN cap (200). Filed **#597**.
7. #583 gauge ring and #578 countdown/badge **visuals**: desk data can't see them — developer
   eyes or screenshots next dash (the checklist items are now visual-only).

### Meta / architecture

1. The week's two headline incidents share a root shape: **the machine trusts a single surface
   observation (a click; a re-parsed frame) over corroborating lifecycle evidence it already
   has** (no job formed after an "accept"; a same-pay "new offer" seconds after one resolved).
   A reconciliation-window pattern (accept ⇒ expect job formation; new offer ⇒ suspect
   teardown) may be a general direction — dev's call.
2. Session-level money reconciled to the cent on all five days; every gap was per-job/per-task
   attribution, not totals — the DASH_STOP summary path is doing its job as the outer truth
   anchor.

## 2026-06-24 — DoorDash session (live dash, in-field narration)

- **Platform tested:** DoorDash
- **Branch under test:** `master` at `2fc557b` (post-#581 merge — latest, includes #457/#576
  notification buttons, #577/#580 quick-decline, #578/#581 rich offer notification).
- **Field conditions:** Live dash, narrated in-field while driving. Offers declined via the
  heads-up notification + a couple of accepts, including a **Sizzling Wok same-store double stack**.
  One screenshot of the bubble HUD (the Sizzling Wok delivery card). **Hypotheses, not concluded
  fixes.** No code changes from this session.

### Verification TODOs (confirmations this dash)

1. **✅ #577 / PR #580 — quick-decline auto-confirm of the 2nd ("are you sure?") screen — CONFIRMED 2/2.**
   With quick-declines ON, declined an offer and the second confirm screen **auto-advanced both
   times** (validated twice, independently). Validated — moved off the "things to look for"
   checklist. (Slowness caveat below as item #1.)

2. **✅ #457 / PR #576 — offer Accept/Decline as a separate heads-up notification — CONFIRMED.**
   The notification buttons **work again**: both **Decline** and **Accept** fired correctly from the
   heads-up over DoorDash (the decline button was exercised on both auto-decline runs above; accept
   verified too). Closes the "No live windows" regression that #576 targeted. Moved off the
   checklist.

### Bugs / Field UX context

1. **Auto-decline second-screen confirm feels slow (#577).** When quick-declines is ON, the dasher's
   expectation is that the second ("are you sure?") screen is dismissed near-instantly — "if you've
   said you want to auto-decline the second screen, it should be minimal delay." In the field it
   advances reliably but with a noticeable lag.
   - **Light investigation (hypothesis, not a fix).** The auto-tap is deferred behind a fixed settle
     delay: `EffectMap.diffConfirmDeclineAction` (`core/state/.../EffectMap.kt:908`) schedules the
     `CONFIRM_DECLINE` tap as a `ScheduleTimeout(SETTLE_UI, EXPAND_SETTLE_MS)` where
     `EXPAND_SETTLE_MS = 500L` (`EffectMap.kt:72`) — the **same 500ms constant borrowed from the
     post-delivery `EXPAND_EARNINGS` chevron tap**, whose comment justifies it by "lets the summary
     finish animating." On top of that 500ms, the confirm dialog first has to be *recognized*: the
     "are you sure?" frame clears the content-changed debounce (`ContentChangedPipeline.debounceWithTimeout(150L, 300L)`,
     `core/pipeline/.../content_changed/ContentChangedPipeline.kt:32`) — or the 100ms windows-changed
     debounce — before it classifies and the effect can even be emitted. So perceived delay ≈
     ~150–300ms (recognition debounce) + 500ms (settle) + click execution ≈ ~0.7–1s before the tap,
     before counting DoorDash's own dialog animation.
   - The `diffConfirmDeclineAction` comment notes the 500ms exists because "~half the captured confirm
     frames are transitional, so the settle wait lets the button render." So the delay is intentional
     (avoid tapping a half-rendered button), not a bug — but it's tuned to the earnings-chevron case,
     not the simpler confirm dialog. **If this hypothesis holds**, one direction *might* be a shorter
     dedicated settle for `CONFIRM_DECLINE`, or firing as soon as the `declineButton`/confirm target
     binding is present in the observation (the binding implies the button rendered) rather than
     waiting a fixed 500ms — **would need a capture of the confirm-frame sequence** to see how many
     frames are genuinely transitional and how early the binding appears.
   - **Status:** Open. Behavior is correct (2/2 validation above); only the latency is the concern.

2. **Sizzling Wok same-store double stack ended with only ONE delivery card.** Accepted a double
   stack — two orders, both from Sizzling Wok — but at the end the bubble HUD showed a **single**
   delivery card (screenshot): one OFFER (Sizzling Wok $18.40, Accepted), one PICKUP (Sizzling Wok,
   arrived 19:37), one DROPOFF ("Sizzling Wok's customer", delivered 19:47, $30/hr), Earnings Saved
   $16.75. The second order's card is missing.
   - **Light investigation (hypothesis, not a fix).** This looks like the **known multi-drop frontier**,
     not a fresh regression: per CLAUDE.md and #503, "multi-drop is slice 3b, **not yet shipped** — a
     stacked/GoPuff multi-drop may still mis-handle the extra dropoffs" (epic #505). The current build
     pre-creates **one** dropoff subtask at offer-accept and resolves the customer onto it
     (`JobEconomicsTest`: "accept pre-creates exactly one dropoff subtask", #503 slice 3) — so a stack
     that arrives as a single offer with two customers would only ever materialize one dropoff card.
     A **same-store** stack is doubly suspect: the same-store resume logic (#499/#503 slice 2)
     deliberately *re-matches by store* to avoid re-minting a task, which on two-same-store-orders could
     fold the second order onto the first instead of spawning a distinct dropoff.
   - **Open questions for the desk / next capture:** Was this **one** DoorDash offer covering two
     orders, or **two** offers (an add-on accepted mid-stack)? Did the bubble ever show two cards and
     then collapse to one, or only ever one? Was the $18.40 / $16.75 the pay for **both** orders or just
     one (i.e. is earnings also undercounted, or only the card missing)? Needs the **capture sequence**
     (offer → pickup → both dropoffs → summary) + the job's `app_events` / `app_state_snapshots` to tell
     a missing *card* from a missing *task* from a collapsed-same-store task.
   - **Status:** Open. Likely the unshipped multi-drop slice 3b (#503/#505), but the **same-store**
     variant is worth its own capture — flag for triage.

### Research / design

1. **The "&lt;store&gt;'s customer" dropoff card title (#568) doesn't read well — what should a dropoff
   card be titled?** In the field "Sizzling Wok's customer" felt awkward as a card title. The dasher
   floated options but is **explicitly undecided** — recording the design space, not picking:
   - **(a) Use the real customer name, hash the address instead.** *Tension with the privacy pledge.*
     Today customer name + address are **both** `sha256`'d at the edge in the parse (#463/#362); state
     only ever holds `customerNameHash` — there is no raw name to display. Showing the name would mean
     **not** hashing it, i.e. keeping raw customer PII in on-device state. CLAUDE.md's Pledge: "Customers
     are hashed, not blocked … we can tell customers apart … without ever keeping their actual info."
     So this option isn't a pure UI change — it would reopen a non-negotiable privacy decision (the
     dasher's *own* first + last-initial is the only name that's fine to process). Worth a deliberate
     decision, not a drive-by; flagged because it touches a pledge.
   - **(b) Title the dropoff by the restaurant instead — e.g. "Dropoff: Sizzling Wok".** Privacy-safe
     (store names aren't PII), and it's essentially a **relabel** of the data #568 already resolves
     (the store is matched to the drop). Loses the ability to *distinguish two customers of the same
     store on a stack* by name — but the hash already differentiates them under the hood, and a
     same-store stack would read "Dropoff: Sizzling Wok" twice (could disambiguate by address-hash
     suffix or drop #1/#2).
   - **(c) Keep "&lt;store&gt;'s customer" (status quo, #568).** It does encode the store and stays
     privacy-safe; the complaint is purely that the phrasing reads oddly as a title.
   - **Status:** Open — design question for the developer. No code change this session. (Note: this is
     entangled with Bug #2 above — a same-store stack is exactly the case where any of these titles is
     hardest to disambiguate.)

---

## 2026-06-21 — DoorDash session (live dash, in-field narration)

- **Platform tested:** DoorDash
- **Branch under test:** `claude/field-testing-bubble-notifications-qcaocb` (field-testing build off
  `master` at `20063db`, post-#299 merge).
- **Field conditions:** Live Sunday dash. **Double stack: PetSmart + Target** (two stores, two
  drops). Observation narrated at **~11:38** while transitioning from the completed Target pickup
  toward the PetSmart pickup. No screenshots; in-field marker only. **Hypotheses, not concluded
  fixes.** No code changes from this session.

### Desk review (2026-06-21, verified against the dash db/captures/source — Claude)

Every claim below independently verified against `dashbuddy-v2.db`, the captures, the rotated logs,
and current source (ultracode workflow).

- **Merged build HELD.** **#556 shop `$/hr` WORKING** — all 13 offers recompute within $1/hr of the
  logged bubbles; the two big grocery shops that caused the $116 bug now read **$22/hr** (31 items)
  and **$13/hr** (38 items). **ShopRate learning WORKING** (6 sane samples 0.27–0.88/min; under the
  5-sample gate so the 0.8 seed was correctly in force). **#517/#518 WORKING** (0/13 null pay; one
  real completion/job; sessions 0 & 9 reconcile penny-exact) — **2nd clean confirmation, validated.**
  **#553/#554** single-store **6/6 correct**; the multi-store `store=''` (seq23/seq70) is the gap
  **#557 already fixes (now merged)** — not a regression.
- **Bug #8 (BK add-on) — CONFIRMED, HIGH → filed #564.** seq98 `$0`/null-customer "Smokey Mo's"
  DELIVERY_COMPLETED is a **NEW class, not a #498 regression**: the BK add-on's loading frame was
  misrecognized as `delivery_summary_collapsed`, and a **grace-retired, never-picked-up** pickup task
  was completed via the PostTask-exit path (`EffectMap.kt:304-322`). Smoking gun: TASK_RETIRE
  `since` == BK offer `presentedAt` == seq98 `completedAt` = `1782080300717`. Fabricated $0 row +
  −$8.40 under-capture. (#518 taskId-dedup can't stop a *first* spurious completion; #498's guard is
  in the dropoff-mint path → inapplicable.)
- **Bug #7 (Sprouts unassign) — CONFIRMED gap → #301.** Sprouts was **unassigned before pickup** and
  never formed a task (benign here — no orphan), but confirms the state machine has no
  unassign/remove-task model. Tracked under #301.
- **Bug #3 (Walgreens) — CONFIRMED, HIGH → filed #565.** An en-route frame matched `dropoff_handoff`
  (priority 64, ARRIVED, parse-null) over `dropoff_pre_arrival` (73) → "the customer" + false
  "already arrived" (`arrivedAt` set 4.5 min early) + a **duplicate dropoff task** (job ended with 2).
  The real arrival frame carries "Complete Delivery"; the peek doesn't — the discriminator no rule keys on.
- **Bug #5 (Popeyes+McD) — REFUTED (not a bug).** Both restaurants are **one customer** (single hash)
  → correctly 2 pickups → 1 dropoff → 1 completion (seq70, $10.02). The "never recognized a dropoff"
  was **~4.5 min HUD staleness** (no dropoff frame captured for minutes) — spun out as #567 (flip the
  bubble on the transition) + #568 (raw-hash label). seq70 `store=''` is the #557 gap (now merged).
- **Bug #6 (True Texas BBQ decline → TIMEOUT) — CONFIRMED, MED.** The 2nd-step `decline_offer`
  confirm click classified as `UNKNOWN click id=null text=null`; the confirm *screen* WAS recognized;
  `resolveOfferOutcome` else → TIMEOUT (`EffectMap.kt:934`). Design call (decline-confirm fallback) +
  needs a fresh decline capture with the click frame.
- **Bug #1 (double Navigator fly-away) — CONFIRMED, LOW → filed #566.** `UpdateBubble` has a null
  `effectKey` so two consecutive emissions (Site A `EffectMap.kt:582` + Site B `:691`) don't dedup.
- **INFO PII leak — CONFIRMED, MED.** `ShadowProjector` logs 9 raw merchant names at INFO → folded
  into #551.

**Top priority: #564** (the only ledger-corrupting bug). Fix in flight (recognition + state guard +
replay test). Lower: #565 Walgreens, then the design calls (#6 decline fallback, #301 unassign model).

### Bugs

1. **Double-mint of the fly-away bubble notification on the stacked-pickup hand-off — two
   "Navigator" heads-up flyouts, the first with no icon.** After completing the **Target** pickup
   and turning toward **PetSmart**, the transient heads-up notification (the little fly-away that
   pops off the bubble — **not** the in-bubble card) fired **twice**, both showing the sender
   **"Navigator"**. The first of the two (the one the dasher caught) rendered the **Navigator name
   but no avatar icon**; tapping into the bubble shows the icon correctly, so the drawable itself
   resolves — it's the **flyout** that came up icon-less.
   - **Why "Navigator": it's the heading-to-pickup persona.** `determinePickupPersona`
     (`core/state/.../EffectMap.kt:938-950`) returns `ChatPersona.Navigator` when a PICKUP task is
     active but **not arrived, not shopping, not confirmed** — exactly "driving to the next store."
     So the PetSmart leg correctly wants a Navigator "Pickup: PetSmart" message.
   - **Likely double-mint cause (hypothesis): two independent `UpdateBubble` emission sites both
     fire across the consecutive frames of the stacked hand-off, and nothing dedups them.** In
     `diffTask` there are two Navigator-capable `UpdateBubble("Pickup: …")` sites:
     - **Site A — new PICKUP task minted** (`EffectMap.kt:563-583`): fires when
       `prevTask?.taskId != nextTask.taskId` — i.e. the moment the PetSmart pickup becomes the
       active task after Target.
     - **Site B — same-task store/activity change** (`EffectMap.kt:672-692`): fires when the store
       name or activity changes *within* a PICKUP task (`storeChanged || activityChanged`).
     A plausible sequence: frame N mints the PetSmart pickup task (Site A → "Pickup: …" Navigator),
     then frame N+1 resolves/changes the PetSmart store text (Site B → "Pickup: PetSmart" Navigator
     again). Both effects are `AppEffect.UpdateBubble`, both route through
     `SideEffectEngine.kt:202-204` → `BubbleManager.postMessage` → `showNotification`, and
     **`UpdateBubble` carries no idempotency key** — the `effects_fired` dedup only guards
     `LogEvent` (`SideEffectEngine.kt:187-200`), so two `UpdateBubble`s in a row each call
     `notificationManager.notify(BUBBLE_NOTIFICATION_ID, …)` and each raises its own heads-up
     flyout. Would need the captured frame sequence around 11:38 to confirm which two sites fired
     (and whether the two texts were identical "Pickup: PetSmart" or differed by an unresolved
     store name on the first).
   - **Likely missing-icon cause (hypothesis): rapid re-`notify` of the same notification id drops
     the `Person` avatar on the heads-up.** The flyout is a `MessagingStyle` notification whose
     sender icon is the persona avatar (`BubbleManager.showNotification`, `BubbleManager.kt:180-214`
     — `ic_chat_navigation` via `getIconResId`, `ChatFormatters.kt:16`). The drawable is valid (the
     expanded bubble shows it), so this isn't a missing-resource bug. When two notifications hit the
     **same** `BUBBLE_NOTIFICATION_ID` back-to-back, Android often renders the first heads-up before
     it has loaded/attached the `Person` icon, showing the name without the avatar — consistent with
     "first one had no icon, the bubble has it." If the double-mint (above) is fixed, this likely
     stops being visible; worth confirming whether a single Navigator flyout ever comes up icon-less
     on its own.
   - **Status:** Open — **marker only**, awaiting end-of-dash log/capture upload for the desk
     cross-reference above (which two `UpdateBubble` sites fired, the two texts, the frame
     timestamps around 11:38). Recorded only, no code changes. Note: this is the live specimen the
     `claude/field-testing-bubble-notifications-qcaocb` branch exists to chase.

3. **Walgreens drop-off card cluster: shows "the customer" (unresolved), reads "already arrived",
   and minted a *fresh* drop-off task the moment navigation started (~12:19).** On a single
   **Walgreens** drop-off, three things looked wrong at once on the drop-off card:
   - **a) Card shows "the customer" — the unresolved-recipient sentinel.** "the customer" is
     `CUSTOMER_FALLBACK` (`domain/.../state/DisplayNames.kt:19`, via `customerDisplayName(null)`),
     deliberately lowercase to read as "recipient not yet known." Per #503 slice 3 it's expected
     **briefly** while the dropoff is unresolved, then should resolve to the **6-char hash** once a
     customer-bearing dropoff frame lands. If it **lingered** on the card, the resolve-onto-subtask
     step didn't fire — the customer hash never got written onto the active dropoff task.
   - **b) Card reads "already arrived" when the dasher was just starting to drive there.** A
     drop-off `arrivedAt` is only meant to be set when a frame comes in as `TaskSubFlow.ARRIVED`
     (`PlatformRegionStepper.kt:764,778` — `justArrived = subFlow == ARRIVED && arrivedAt == null`).
     A false "arrived" at nav-start means either (i) an `arrivedAt` was **inherited/carried** onto
     the new dropoff task from a prior leg, or (ii) a frame was mis-classified as a dropoff
     **ARRIVED** when it was really a nav/arriving screen.
   - **c) Starting navigation minted a NEW drop-off task** ("as soon as I started the navigation it
     minted a new dropoff task; it knows I'm navigating now"). The #503-slice-3 design is to
     **resolve onto the offer-spawned, customer-TBD dropoff subtask** (`PlatformRegionStepper.kt:744-783`)
     rather than mint fresh. A new mint at nav-start means the **resolve path's guard didn't match**
     — `expectedDropoff` (`:749-758`) came back null, so it fell through to the new-task mint
     (`:805+`). That guard requires a pending dropoff with `customerNameHash == null &&
     completedAt == null` that isn't the current task and isn't already displaced; if the
     pre-created dropoff was already consumed/displaced, or didn't exist for this Walgreens job, the
     fall-through mints a fresh one.
   - **These three are very likely one cluster, not three bugs.** A fresh-minted dropoff (c) would
     start with **no customer hash** → renders "the customer" (a), and if that same mint or a
     following frame mis-set `arrivedAt`, it reads "already arrived" (b). The single question for the
     capture is: **at nav-start, did the stepper resolve onto the pre-created Walgreens dropoff or
     mint a new one** — and if it minted, why was `expectedDropoff` null (no offer-spawned dropoff
     subtask for this job? already displaced? customer hash already non-null on it?).
   - **What to pull / check at desk (from this dash's `app_events` + `app_state_snapshots` around
     12:19):** the Walgreens dropoff frame sequence — how many dropoff tasks exist for the job
     (want exactly one), whether `customerNameHash` ever resolves off null, the `arrivedAt` value at
     nav-start and which frame set it (a real ARRIVED screen vs nav/arriving), and whether a
     `DELIVERY_NAV_STARTED` / new-task mint fired at the navigation start. Cross-reference the
     #503-slice-3 resolve-onto-subtask path.
   - **Developer hypothesis (post-dash, the likely (b) mechanism): a post-arrival "host" drop-off
     rule fired where the pre-arrival rule should have — "maybe those rules aren't different
     enough."** This grounds out in the ruleset. Three drop-off rules key on the generic
     `drop_off_workflow_host_fragment` container id and all set **`task:dropoff:arrived`** —
     `dropoff_photo`, `dropoff_pin_entry`, and **`dropoff_handoff`** (`doordash.json:2552/2563/2577`,
     handoff at **priority 64**, keyed on host fragment + `"hand it to customer"`) — whereas the
     en-route **`dropoff_pre_arrival`** is **priority 73** and sets `task:dropoff:navigation`
     (keyed on `"Deliver to"`/`"Delivery for"` + `"Hand it to recipient"`). Rules evaluate
     **ascending by priority, first match wins** (`Ruleset.kt:22-43`, "lower = evaluated first"), so
     **64 beats 73**: if an en-route/arriving frame carries the workflow-host fragment *and* trips
     one of those arrived rules' text, the **arrived** rule wins and flips `arrivedAt` before the
     dasher is actually there — exactly the false "already arrived." The discriminators are thin
     ("hand it to **customer**" vs "Hand it to **recipient**"; a host-fragment container that may be
     present pre-arrival too), which is the "not different enough" the dasher flagged. **Confirm
     against the capture:** which drop-off rule id matched the nav-start Walgreens frame — if it's a
     `*_host_fragment` / arrived-flow rule on an en-route screen, that's the bug, and the fix is
     tightening the arrived rules' discriminators (or rejecting the en-route markers) rather than
     anything in the stepper.
   - **Status:** Open — **marker only**, awaiting capture upload. Recorded only, no code changes.

5. **Stacked double (Popeyes + McDonald's, ~15:20): after completing the SECOND pickup, the app
   never recognized the dasher was on a drop-off.** On a two-store stack, both pickups completed,
   but the transition into the **drop-off phase didn't register** after the second pickup — no
   drop-off card / drop-off state. Dasher's read: **"I think it's related to the other one"** (the
   #3 Walgreens drop-off cluster). Very likely the **same drop-off-recognition family**, surfacing
   as a *miss* (no drop-off at all) here rather than a *false arrived* there.
   - **Candidate causes (hypotheses, same family as #3):**
     - **(i) #498 phantom-dropoff guard suppressing the real drop-off.** A transition into a dropoff
       whose frame carries **no `customerNameHash` AND no `customerAddressHash`** is treated as a
       transient confirm/geofence screen and the stepper **returns the region unchanged**
       (`PlatformRegionStepper.kt:798-803`). If the first drop-off frame after pickup 2 didn't parse
       a customer (wrong layout, or a rule that recognizes the screen but doesn't parse name/addr),
       the guard would swallow it and the drop-off never starts. This is the inverse failure mode of
       #3c — there a fresh dropoff minted with no identity; here the guard eats it.
     - **(ii) The pickup→dropoff transition never fired because pickup 2 didn't *confirm*.** The
       `PICKUP_CONFIRMED` → `DELIVERY_NAV_STARTED` hand-off (`EffectMap.kt:601-637`) requires
       `prevTask.phase == PICKUP && nextTask.phase == DROPOFF`. On a stacked double the second
       pickup's confirm screen (often a barcode/QR/handoff variant) may not have been recognized as
       a pickup-confirm, so the task never flipped to DROPOFF — same thin-discriminator risk as the
       drop-off rules in #3.
     - **(iii) Stacked-dropoff resolve mismatch.** With two drops queued, the #503-slice-3 resolve
       (`PlatformRegionStepper.kt:744-783`) picks the first pending customer-TBD dropoff; if both
       drops' subtasks were already consumed/displaced or the second pickup didn't spawn its dropoff
       subtask, there's nothing to resolve onto and (combined with the phantom guard) no drop-off
       surfaces.
   - **What to pull / check at desk (from this dash's `app_events` + snapshots around 15:20):** the
     frame sequence right after the second pickup — did a `PICKUP_CONFIRMED` fire for pickup 2, did
     any frame classify as `task:dropoff:*`, what rule id matched those frames, and did they parse a
     `customerNameHash`/`customerAddressHash` (if both null, suspect the #498 guard). Compare the
     Popeyes and McDonald's legs to see if one resolved and the other didn't. This is a **two-store**
     stack, so it also feeds the multi-store-stack frontier (#557 / the 06-20 markers).
   - **Status:** Open — **marker only**, awaiting capture upload; likely same root family as #3.
     Recorded only, no code changes.

6. **True Texas BBQ offer (~16:19): dasher *declined*, but it was recorded as an `OFFER_TIMEOUT`,
   not `OFFER_DECLINED`.** A real decline logged as a phantom timeout — a data-fidelity bug in the
   offer accounting (declines vs timeouts feed accept-rate / offer stats).
   - **Mechanism (well-grounded in the rules + stepper): a DoorDash decline is two-step, and only
     the SECOND step counts as a decline — if that confirm-step click isn't captured, the outcome
     falls through to TIMEOUT.** `OfferIntent.DECLINE` is the literal wire string **`"decline_offer"`**
     (`domain/.../state/OfferIntent.kt:11`). Only one click rule produces it —
     **`doordash.click.decline_offer`** (`doordash.json:2999-3008`), gated **`screenIs:
     offer_popup_confirm_decline`** and requiring a tap on **"Decline offer"** (the confirmation
     dialog's button). The **first** tap — the X on the offer popup — is
     `doordash.click.initial_decline` with intent **`"initial_decline"`** (`:2987-2996`), which
     **does not equal `"decline_offer"`** and so is **never** treated as a decline outcome. Outcome
     resolution (`EffectMap.kt:918-936`, `resolveOfferOutcome`) checks `prevOffer.lastClickIntent`
     and the resolving click for `OfferIntent.DECLINE`; finding neither (only an `initial_decline`,
     or nothing), it returns **`OFFER_TIMEOUT`** as the `else` branch. So when the offer vanished
     (`prevOffer != null && nextOffer == null`, `EffectMap.kt:172-183`) without a captured
     `decline_offer` confirm click, a genuine decline was logged as a timeout.
   - **Why the confirm click was likely missed (hypotheses to check against the capture):**
     - **(i) The confirm dialog wasn't recognized as `offer_popup_confirm_decline`.** That screen
       rule keys on `"sure you want to decline"` (`doordash.json:305-313`); the click rule's
       `screenIs` gate depends on it. If True Texas BBQ's confirm dialog rendered different text or
       the frame wasn't captured, the `decline_offer` click can't classify → no DECLINE signal.
     - **(ii) The confirm-tap accessibility click event wasn't emitted/captured** (fast dismissal,
       debounce, or the popup tore down before the click frame landed).
     - **(iii) The offer flow went to null off the `initial_decline` step** (or a timeout-looking
       transition) before the confirm click was processed.
   - **Design fragility worth flagging regardless of root cause:** a real decline is only counted if
     the **second-step** confirm click is recognized; a missed confirm silently becomes a phantom
     timeout. The `initial_decline` intent is captured but deliberately not counted (tapping X then
     *cancelling* brings the offer back, so it isn't a decline on its own) — but there's **no
     fallback** for "initial_decline seen, then offer genuinely disappeared," which is exactly a
     confirmed decline with a dropped confirm frame. Whether to treat `initial_decline`-then-vanish
     as a decline is a design call (to be decided, not concluded here).
   - **What to pull / check at desk (`app_events` + snapshots ~16:19, True Texas BBQ):** the click
     sequence on the offer — was an `initial_decline` click captured? a `decline_offer` click? was
     the `offer_popup_confirm_decline` screen ever recognized (any frame with "sure you want to
     decline")? what `lastClickIntent` did the `PendingOffer` carry when it resolved, and what
     emitted the `OFFER_TIMEOUT`? Confirms which of (i)/(ii)/(iii) it was.
   - **Status:** Open — **marker only**, awaiting capture upload. Recorded only, no code changes.

### Field UX context / Open questions

2. **The PetSmart leg of the stack was a barcode-scan "batch"-style pickup — similar in feel to
   GoPuff, but not identical.** The dasher had to **scan a barcode several times** during the
   PetSmart pickup, reminiscent of the GoPuff (Drive) warehouse bin-scan workflow but **not exactly
   the same** flow. Flagged to **review against the capture corpus** when it comes back — specifically
   whether PetSmart's scan screens are **recognized or fall to UNKNOWN**.
   - **Why this matters / hypothesis: the existing barcode-scan recognition is GoPuff-keyed, so
     PetSmart's screens may not match.** The #501 batch-pickup rules in
     `core/pipeline/src/main/assets/rules/doordash.json` are keyed on **GoPuff-specific** text/ids:
     `doordash.screen.pickup_steps` matches `"Scan barcodes on"` and declares
     `task:pickup:arrived` (it's what mints the only clean PICKUP_ARRIVED for an otherwise
     all-UNKNOWN warehouse leg), and `pickup_barcode_scan_issue` / the at-store survey lean on the
     GoPuff Compose container ids. A **PetSmart** batch pickup with different on-screen copy and
     widget ids likely **won't trip those branches**, so its scan steps could land in UNKNOWN and
     **no PICKUP_ARRIVED would fire** for the PetSmart leg — the same gap #501 closed for GoPuff,
     re-opened for a new store. Would need the captured PetSmart frames to confirm what text/ids it
     actually carries before deciding whether it's the same rule family or a new one.
   - **What to pull / check at desk (from this dash's capture corpus):** the PetSmart pickup frame
     sequence — does any frame recognize (intent ≠ UNKNOWN), does a PICKUP_ARRIVED fire for the
     PetSmart leg, and what stable non-PII strings / view ids do the scan screens expose (so a rule
     can be keyed on them if they're UNKNOWN)? Compare against the GoPuff #501 corpus to see how much
     of the workflow is shared vs store-specific.
   - **Status:** Open — **marker only**, awaiting capture-corpus review. Recorded only, no code
     changes.

### Research / design

4. **Offer evaluation: flat per-metric thresholds may be too blunt; the real decision is
   *conditional* (pay × distance). Conjecture / thinking-out-loud, not a work item.** Dasher's
   framing: a rule like "decline anything under $5" is a fine rule of thumb on its own, but the
   actual judgment is **joint** — *"if it's $5, what's the most distance I'll drive for it?"* A $5
   offer that's 1–2 miles to a fast restaurant is a take; the same $5 at a longer distance isn't. So
   a single global floor on one dimension throws away the nuance that lives in the **interaction** of
   pay and distance (and pickup speed). "I feel like there's a lot more nuance we might need to
   capture in the problem."
   - **What plumbing already exists (so this is a refinement, not a greenfield build):** the offer
     engine is already rule-based — `ScoringRule.MetricRule` (sealed type, `metricType` +
     `targetValue`) over `MetricType.{PAYOUT, DOLLAR_PER_MILE, ACTIVE_HOURLY, MAX_DISTANCE,
     ITEM_COUNT}` (`domain/.../evaluation/OfferEvaluator.kt:144-220`). Each metric is scored
     **independently** (`calculateMetricScore`, `:195-220`) and the per-metric scores are folded by
     **rank weight** into a composite (`:143-158`), then cut at `ACCEPT_THRESHOLD` /
     `DECLINE_THRESHOLD` (`:164-165`). `DOLLAR_PER_MILE` already encodes a pay/distance *ratio*, but
     a ratio isn't the same as a conditional curve — $5/1mi and $50/10mi are the same $5/mi yet very
     different takes.
   - **The gap (conjecture): there's no way to express a threshold on one dimension that's a
     *function of* another** — e.g. an acceptance curve `maxMiles = f(payout)` (at $5 → ≤2 mi; at $8
     → ≤4 mi; …), or a small pay×distance accept/decline table. The current model can weight
     "distance matters" and "pay matters" separately but can't say "distance only matters *this much*
     **when** pay is low."
   - **A couple of directions this could go (purely speculative, dasher decides):** (i) a **guided
     rule-builder** that nudges the user toward conditional rules — instead of one "min $5" slider,
     prompt "at $5, what's your max distance? at $8? at $12?" and store the resulting points; (ii)
     model it as a **pay-vs-distance acceptance curve** (a few user-set anchor points, interpolated)
     that becomes a new `ScoringRule` variant or a hard accept/decline gate layered on the existing
     composite. Either would reuse the `ScoringRule` sealed hierarchy and the
     evaluate/score/threshold pipeline rather than replace it. If this hypothesis holds, the design
     question is whether conditional logic lives as a richer `ScoringRule` subtype or as a separate
     gate ahead of the weighted score — to be decided, not concluded here.
   - **Status:** Open — **conjecture / design note only**, no work item filed, no code changes.
     Recorded to feed a later triage/RFC if the dasher wants to pursue it.

9. **Onboarding / setup-wizard / permissions philosophy — prompted by a Reddit "delete your
   onboarding" post. Strategic note, not a work item.** A solo dev's post (built on Claude Code,
   shipped to TestFlight) reported real-user data killing his elaborate onboarding: users walked the
   tutorial/setup/value-props and **bailed at the sign-in wall before reaching the product**. He tore
   it out — drop users into the core action free, no account, ask for sign-in only *after* value is
   felt. Lesson: *"every screen between 'opened the app' and 'felt the value' is a place to lose
   someone."* (Product referenced: War Table, https://wartable.co/ — "five AIs debate your
   decision." The original Reddit post URL wasn't locatable via search at log time; quote is
   paraphrased from the dasher's relay.)
   - **Why DashBuddy is a different archetype (so "delete the wall" doesn't transfer directly):** the
     post's exemplars (ChatGPT/Claude/Perplexity/War Table) deliver value the instant you open them,
     so a sign-in gate is *arbitrary* friction. DashBuddy's permission gate is **load-bearing, not
     arbitrary** — the core value (reading the DoorDash UI via `AccessibilityService`, mileage via
     location) is physically impossible without those grants. You can't "drop the user into the
     value" because the value is recognizing offers on a live dash. So the wall can't simply be
     deleted; the *principle* still applies, on a different surface.
   - **The translation that does apply — split capability from scaffolding:**
     - **Load-bearing (can't defer):** the accessibility-service grant and location permission.
     - **Scaffolding (the post's real target):** the economy editor (MPG/maintenance/depreciation/
       gas), vehicle/strategy config, value-prop or tutorial screens. **None of this should stand
       between "opened" and "saw True Net Profitability."** The code already supports deferring it —
       `UserEconomy` ships sane defaults (`DEFAULT_GAS_PRICE_PER_GALLON`, `DEFAULT_MINUTES_PER_MILE`,
       `DEFAULT_BASE_PICKUP_MINUTES`) and tracks `userSetFields` separately, so the economy editor can
       be **optional refinement**, never a first-run gate. If the wizard currently forces economy
       config before the first dash, that's exactly the "commit before value" wall and it can come out
       without touching the data model.
   - **The one move that *is* the post's lesson, adapted:** sequence value **ahead of** the scary
     accessibility dialog — let the user feel the bubble HUD + net-profit math on a sample/demo offer
     first, *then* request the grant, now motivated ("earn the permission"). Same instinct (no
     commitment before value), applied to a permission that can't be removed.
   - **Stage check (the most important caveat):** per `CLAUDE.md`, **"exactly one user: the
     developer."** The post is a **conversion-funnel** lesson — about not losing *acquired strangers*,
     which DashBuddy doesn't have yet. So building (or tearing out) elaborate onboarding *now* would
     itself be the over-scaffolding the post warns about. Right-now question: *does the wizard slow the
     tester down, and does it bury the two permissions that matter?* Optimize the wizard for the
     tester. **Save the funnel teardown for the first-500 / paid-tier launch (#141)** — that's when
     "every screen to value is a place to lose someone" becomes literally true with money attached.
   - **Status:** Open — **strategic/design note only**, no work item filed, no code changes. Bookmark
     for the #141 monetization launch; near-term, only ensure economy config is deferred behind
     defaults rather than gating the first dash. *[Editor's note 2026-07-12: #141 has since closed —
     it was the cloud-data-platform RFC, not the pricing plan; the paid-tier launch plan lives in
     CLAUDE.md pillar 1. The "funnel teardown at paid-tier launch" bookmark itself still stands.]*

10. **Voice accept/decline of offers — hands-free, on-device. Feasibility / overhead note (dasher
    asked "what would the overhead be").** Let the driver say "accept" / "decline" instead of
    reaching for the bubble button while moving. Estimated overhead: **moderate-small for a v1**,
    because the expensive half is already built.
    - **Already done (the actuation half):** accept/decline is already an intent SSOT
      (`OfferIntent.ACCEPT/DECLINE`) with **multiple front-ends** — the bubble buttons and the
      notification actions both dispatch an `Observation.UiInput` carrying that intent. The app-owned
      tap path (`RuleAction.ACCEPT_OFFER/DECLINE_OFFER` via `UiInteractionHandler`, the only thing
      that clicks the third-party app, #425) and the fail-closed action gates (#417: tier check +
      package-scope + label allowlist + strict click) sit behind it. **Voice is just front-end #3** —
      a spoken word → `UiInput(intent)` → the exact path the button uses. TTS output already exists
      (`TtsEffectHandler`), giving the confirmation/readback half for free.
    - **New work (the input half):** (1) `RECORD_AUDIO` runtime permission (sensitive — same opt-in
      framing as other capabilities); (2) a **bounded** speech handler — Android on-device
      `SpeechRecognizer` armed **only during the offer window** (R0 `OfferPresented`) with a 2-word
      grammar, symmetric to `TtsEffectHandler` (e.g. a `VoiceCommandHandler` that `EffectMap` arms on
      offer-presented / disarms on offer-resolved); (3) a confirm beat (TTS readback + grace, e.g.
      "Declining — say cancel to stop") for noisy-car reliability — dovetails with DoorDash's
      already-two-step decline (cf. this session's #6); (4) trigger classification — a voice accept is
      a **`USER`** `ActionTrigger` (the dasher spoke = its own consent), which already exists, so it
      slots into the #417 consent model without change.
    - **Privacy posture (the real gate, fits the pledges):** on-device recognition only, **no audio
      leaves the device**, mic live **only** in the bounded offer window, opt-in per-feature.
      Explicitly **not** always-on/hotword for v1 (battery + wake-word model + privacy review blow up
      the cost and strain the on-device story); push-to-offer-window keeps it cheap and pledge-clean.
    - **Status:** Open — **design/feasibility note only**, no work item filed, no code changes. Clean,
      well-bounded enhancement with a hands-free field-safety win; candidate to file against the
      effects/offer-action area if the dasher wants to pursue it.

### Verification TODOs — desk-review markers (need captured data)

7. **Partial unassign of a stacked order (~17:16): dasher dropped ONE half of a stack — desk agent,
   trace this whole window for unknown screens and the effect on job/task tracking.** Advanced
   maneuver: an accepted stack of **a regular pickup (Smoky Mo's BBQ) + a Shop & Deliver order
   (Sprouts Farmers Market)**; the dasher **unassigned the Sprouts shopping order** (it was too far)
   and kept the Smoky Mo's pickup. This exercises a path the app has **almost no model for** — pull
   the capture and trace it end to end. Marker only; recorded, not concluded.
   - **Why this is a known-thin area (so the desk review has a starting hypothesis):**
     - **Recognition: only ONE screen of the unassign flow is known.** The single relevant rule is
       `doordash.screen.pickup_resolution_options` (`doordash.json:2802-2811`), keyed on
       `"Resolution options"` + `"Unassign with no pay"`, and it's **recognize-only** (no
       `state.flow`, no parse). The rest of the unassign sub-flow — the issue picker before it
       (`pickup_select_issue`), any "which order do you want to unassign?" stack selector, the
       confirm dialog, and whatever the app shows **after** one order is dropped — is likely
       **UNKNOWN**. **Expect unknown frames in this window**; the X-Ray report on the capture is the
       way to find and rule them.
     - **Task tracking: there is NO unassign/remove-task handling in the state machine.** A repo-wide
       search for `unassign` / `removeTask` / `dropTask` in `core/state` and `domain` returns
       **nothing** — the steppers model task *completion* and *re-mint*, not **removal of a still-open
       task from a multi-task job**. So when the Sprouts task is unassigned, the job very likely still
       carries it as an open subtask that can **never complete** — a candidate **orphan/phantom task**
       that could skew the job's task list, its economics (the dropped order's offer pay still folded
       in?), and any "all drops done?" completion logic. This may also interact with the same-store /
       dropoff-resolution paths flagged in #3 and #5.
   - **What the desk agent should pull / check (`app_events` + `app_state_snapshots`, window ~17:16,
     DoorDash):**
     - **Unknown screens:** run the capture through the X-Ray / inbox sort for this window; list every
       UNKNOWN frame in the unassign sub-flow and note its stable non-PII strings/ids so rules can be
       written (issue picker → resolution options → stack selector → confirm → post-unassign state).
     - **Job/task tracking effect:** how many tasks did the job hold before vs after the unassign?
       Did the **Sprouts** shopping task get removed, left open (orphan), or mistakenly marked
       complete? Did the **Smoky Mo's** pickup survive intact as the sole remaining task? Was there a
       spurious re-mint or a dropoff phantom (cf. #3/#5)?
     - **Economics:** was the dropped Sprouts order's pay still counted in the job/session economics
       after unassign, or correctly removed? ("Unassign with no pay" implies it should contribute
       nothing.)
     - **State integrity:** did the machine end the window in a clean single-task state, or in a
       confused/stuck state (e.g. waiting on a dropoff for the order that no longer exists)?
   - **Status:** Open — **desk-review marker**, awaiting capture upload. Recorded only, no code
     changes; flagged as a likely **new recognition + new task-lifecycle (unassign) frontier**.

8. **Same job got *weirder* (~17:16, continues #7): a Burger King add-on arrived while approaching
   Smoky Mo's, DoorDash forced BK to be picked up FIRST, the dasher reshuffled — and the closeout
   shows an erroneous "paid" PostTask and TWO Smoky Mo's pickups.** This is the high-value specimen
   of the session. Sequence as narrated: stack was Smoky Mo's + (dropped) Sprouts (#7); then a **BK
   add-on** came in en route to Smoky Mo's; DoorDash **re-ordered the route to pick up BK first**;
   the dasher did "shenanigans" to move things around. Two concrete defects observed: **(a) an
   erroneous paid PostTask** (a payout/receipt closeout fired when it shouldn't have), and **(b) two
   Smoky Mo's pickups** (a duplicated same-store pickup task). "I know it's not handling it perfectly
   yet." Marker only — recorded, not concluded.
   - **Why these two are plausible given the code (starting hypotheses for the desk agent):**
     - **(b) Two Smoky Mo's pickups — the #499 same-store re-match was likely defeated by the
       reorder.** The pickup re-match ("fold the add-on into the existing same-store task, don't
       re-mint") only runs **when `!isStackedPickupTransition`** (`PlatformRegionStepper.kt:710-714`):
       it resumes a `recentTasks` pickup with the same `storeName`. But when DoorDash **re-ordered**
       the stack (BK inserted *ahead* of Smoky Mo's), the platform very likely signaled a
       **genuinely-new stacked pickup transition** on returning to Smoky Mo's, so
       `isStackedPickupTransition` was **true** → the re-match branch is skipped → a **second** Smoky
       Mo's pickup task mints. The guard is built for "two distinct orders at the same store stay
       distinct"; a reorder of the *same* order can look identical to that and trip the same path.
     - **(a) Erroneous paid PostTask — a receipt/closeout fired mid-job during the reshuffle.**
       `DELIVERY_COMPLETED` is emitted on **leaving PostTask** (`EffectMap.kt:286-…`), carrying the
       `lastPostTaskFields` pay breakdown (`PlatformRegion.kt:40-65`). The #518 guard keys
       `DELIVERY_COMPLETED` on the *completed task* to stop a re-entered `PostTask→nav→PostTask`
       double-count (`AppEffect.kt:34-35`) — but a forced reorder + manual reshuffle is exactly the
       kind of out-of-order PostTask/nav flapping that stresses that guard. Suspect a **PostTask
       frame got attributed to the wrong leg** (or fired before a leg was really done), emitting a
       spurious paid completion. Cross-references the dropoff-attribution issues in #3/#5.
   - **What the desk agent should pull / check (`app_events` + `app_state_snapshots`, ~17:16
     onward, DoorDash — same job as #7):**
     - **Reconstruct the task timeline:** offer/add-on events for BK; how many pickup tasks the job
       held and their stores (looking for the **duplicate Smoky Mo's**); whether the BK add-on
       folded in vs minted; the order in which pickups/dropoffs minted and completed.
     - **The erroneous PostTask:** find every `Flow.PostTask` entry/exit and each `DELIVERY_COMPLETED`
       — which task each attributed to, the pay on each, and whether one fired for a leg that wasn't
       actually delivered (or fired twice). Check `lastAnnouncedPostTaskTaskId` / the #518 key.
     - **`isStackedPickupTransition` at the Smoky Mo's return:** if logged/derivable, confirm whether
       the reorder set it true and that's what skipped the re-match.
     - **Net integrity:** did the job's economics / completion count end correct despite the duplicate
       pickup and erroneous PostTask, or are there phantom/double legs? Reconcile against actual pay.
     - **Unknown screens:** as with #7, X-Ray the window — the add-on-reorder and reshuffle screens
       may be UNKNOWN.
   - **Status:** Open — **desk-review marker**, awaiting capture upload. Recorded only, no code
     changes. Likely touches the **#499 same-store re-mint guard**, the **#518 PostTask
     double-count guard**, and the add-on/reorder task-lifecycle frontier (cf. #503/#505); a strong
     candidate for a `SessionReplay` Level-B repro once the capture is in hand.

---

## 2026-06-20 — DoorDash session (evening dash, same-store double stack)

- **Platform tested:** DoorDash
- **Branch under test:** `master` (build inferred — developer to correct if running a feature branch).
- **Field conditions:** Live evening dash, separate from the earlier (~17:01) H-E-B dash. **Two
  same-store double stacks:** one at Panda Express (first dropoff ~19:39), one at Perry's Pizzeria.
  In-field markers only; no screenshots. **Markers for desk review, not concluded observations.** No
  code changes from this session.

### Verification TODOs — same-store stacked doubles (markers for desk review)

1. **Same-store double stack at Panda Express — first dropoff completed ~19:39, set as a desk-review
   marker.** Both orders in the stack are from the **same store (Panda Express)** — one pickup location,
   two distinct customer dropoffs. Dasher dropped the **first** of the two at ~19:39 and flagged it live
   so we can pull the captured data later. Stacked + multi-drop handling is a **known frontier** (#503
   slice 3b multi-drop not shipped; the same-store add-on re-mint guard is #499/#503), so this is a
   real-world specimen of exactly that case.
   - **What to pull / check at desk (from this dash's `app_events` + `app_state_snapshots`):**
     - **Offer shape:** did this arrive as **one stacked offer** (two orders in one `ParsedOffer`) or
       two separate `OFFER_RECEIVED`s? Note the offer-accept sequence around the stack.
     - **Same-store pickup identity:** with both pickups at Panda Express, did the task lifecycle keep
       **two distinct orders** but (correctly) **one pickup activity**, or did the same-store re-match
       (#499 pickup re-match by store) **collapse/merge** them or **re-mint**? Want: two orders, not one,
       and not three.
     - **Two distinct dropoffs:** each dropoff should resolve to its **own customer hash** and its **own
       address** — the multi-drop path (slice 3b) is unshipped, so watch for a **dropped, duplicated, or
       mis-ordered** second dropoff after the first completed at ~19:39.
     - **Completion + earnings:** **exactly one** `DELIVERY_COMPLETED` per dropoff (two total), each with
       a distinct customer hash and non-null pay, and **session earnings reconcile** to the sum (no
       double-count, no missing leg).
     - **Bubble/flow cards:** how did the HUD render two same-store orders — two cards, one merged card?
       (FlowCardMapper v1 assumes a single delivery in flight; a stack overwrites the accepted-economics
       accumulator — see the v1 caveat in `FlowCardMapper.kt`.)
   - **Status:** Open — **marker only**, awaiting end-of-dash log upload for the desk cross-reference
     above. Acting as field-testing agent: recorded only, no code changes.

2. **Second same-store double stack — Perry's Pizzeria — set as an additional desk-review marker.**
   A second double stack later in the same evening dash, **both orders at Perry's Pizzeria**. Same shape
   as item #1 (one store, two customer dropoffs), flagged live as another specimen to cross-reference.
   Two same-store stacks in one dash gives the desk review **two independent samples** of the multi-drop
   + same-store-pickup path to compare.
   - **What to pull / check at desk:** identical checklist to item #1 — offer shape (one stacked offer
     vs two `OFFER_RECEIVED`s), same-store pickup identity (two distinct orders, not merged/re-minted),
     two distinct dropoffs (own customer hash + address, none dropped/duplicated/mis-ordered),
     exactly one `DELIVERY_COMPLETED` per leg reconciling to earnings, and how the HUD rendered two
     same-store orders.
   - **Status:** Open — **marker only**, awaiting end-of-dash log upload. Recorded only, no code changes.

---

## 2026-06-20 — DoorDash session (live dash, in-field narration)

- **Platform tested:** DoorDash
- **Branch under test:** `master` (build inferred — developer to correct if running a feature branch).
- **Field conditions:** Live Saturday dash. Single ACV (alcohol) Shop-&-Deliver offer from **H-E-B**
  (grocery). Observation narrated from the bubble HUD while the offer/pickup card was on screen; one
  screenshot captured. Mid-dash, a **real phone power-off at the H-E-B checkout lane (~17:01)** gave a
  live **crash-recovery** test (item #2). **Hypotheses, not concluded fixes.** No code changes from
  this session.

### Bugs

1. **`$/hr` on the offer/active card is wildly inflated for shop-&-wait offers (~$116/hr on a ~1-hour
   $30 grocery run).** Screenshot: H-E-B offer, gross **$30.03**, card hero reads **`$116/hr`**, sub-line
   **`Net $28.89 · 3.2 mi · $9.03/mi`**, score **86 ("AWESOME OFFER")**. The pickup card on the same
   screen shows **`36:08` to go / pickup by 16:53** — i.e. the *pickup deadline alone* is ~36 min, and
   the dasher estimates the whole job at **almost an hour**. So the real rate is ~**$30/hr**, not $116.
   - **The number is internally consistent — the *time model* is the bug (not an arithmetic slip).**
     The hourly is `netPay / estTimeHours` where
     `estTimeMinutes = (dist × avgMinutesPerMile) + basePickupMinutes`
     (`OfferEvaluator.evaluate`, `domain/.../evaluation/OfferEvaluator.kt:24-29`). With the defaults
     `avgMinutesPerMile = 2.5`, `basePickupMinutes = 7.0`
     (`UserEconomy.kt:105-106`): `3.2 × 2.5 + 7 = 15.0 min = 0.25 h` → `28.89 / 0.25 = $115.56/hr` →
     rounds to the `$116/hr` on screen. The estimate is **15 minutes for a job DoorDash itself says is
     36+ minutes to pickup.**
   - **Likely root cause (hypothesis): the time estimate is a pure distance heuristic with no
     shop/wait component and no use of the platform's own timing signals.** `basePickupMinutes` is a
     single flat 7-min overhead for *both* pickup and dropoff — fine for a hand-it-to-me restaurant
     bag, badly wrong for a **Shop & Deliver / grocery / alcohol** order where in-store shopping +
     checkout + ID-check is the dominant time sink and is **independent of drive distance**. A 25-item
     H-E-B shop and a 1-item McDonald's bag get the *same* 7-min overhead. The model also ignores the
     real signals already on the offer/pickup screen — the **pickup-by deadline** and (for shops) the
     **item count** — which we parse and already carry on the cards (`itemsRemaining`/`itemsShopped`,
     `deadlineMillis` in `FlowCardMapper`).
   - **This very likely also inflates the offer *score*, confirming the dasher's worry that "the
     offer logic is off too."** The same `estTimeMinutes` → `activeHourly` feeds the **`ACTIVE_HOURLY`
     scoring metric** (`OfferEvaluator.kt:140` → `calculateMetricScore` → `:197`,
     `(hourly / target).coerceIn(0,1)`). A 4–5× inflated hourly will peg that metric at its max for
     almost any shop offer, dragging the composite score up — plausibly a big part of why a ~$30/hr-real
     grocery run scored **86 / "AWESOME"**. So this is one model error surfacing in two places (the HUD
     number *and* the accept/decline verdict), exactly because `dollarsPerHour` is the SSOT for both.
   - **Desk findings + developer-confirmed direction (06-20):**
     - **Distance is whole-offer.** Developer confirms `offer.distanceMiles` is (should be) the full
       offer route, not a single leg — so the drive-time term isn't the under-count; the missing
       **shop/wait time** is.
     - **We already parse the platform's own deadline — the evaluator just throws it away.**
       `ParsedOffer` carries `dueByTimeMillis` / `dueByTimeText` (the "Deliver by" time) and it's
       **populated on real DoorDash offers** (corpus e.g. `dueByTimeMillis=1780333740000`,
       `dueByTimeText=5:09 PM` in `approved-parse-output.json`). `ParsedOffer` also has
       `timeToCompleteMinutes`, but that's **only parsed for Uber** (`uber.json:160`) and is **null on
       every DoorDash offer** (DoorDash doesn't surface a "time to complete"). Yet `OfferEvaluator`
       reads only `payAmount` / `distanceMiles` / `itemCount` (`OfferEvaluator.kt:12-14`) — it never
       touches `dueByTimeMillis`. **Direction:** the estimate should parse/anchor on the offer's own
       deadline (derive a delivery window from `dueByTimeMillis − now` on DoorDash;
       `timeToCompleteMinutes` directly on Uber) and fall back to our heuristic only when the platform
       gives us nothing.
     - **Shops get their own time model, item-rate based — not a flat constant.** Developer: a Shop &
       Deliver estimate must be distinct from restaurant/retail *pickup* and must be driven by the
       **dasher's own pick rate (items/minute)**, not a magic number. We already parse the shop
       `itemCount` (corpus: CVS=4, Dollar General=9, Michaels=11…), so the shop term is roughly
       `itemCount ÷ itemsPerMinute` (+ a checkout/ID-check overhead), gated on the
       SHOP_FOR_ITEMS / alcohol recognition we already have. Until we can *measure* a given dasher's
       items/min, seed a **sensible default** rate and let it be refined once we have real shop-duration
       data per dasher (an estimated, eventually-learned metric — a new `UserEconomy` field, default
       now, personalized later).
   - **Status:** Open — direction agreed (parse offer deadline as the primary/anchor signal + a separate
     item-rate shop model with a seeded default), implementation deferred. Acting as field-testing agent
     this session: recorded only, no code changes. When implemented this needs field re-validation that a
     grocery/ACV shop now reads a realistic `$/hr` and a non-inflated score — add a "Next field test"
     checklist item at that point.

### Verification TODOs — crash recovery held in the field (confirmation 2/2)

2. **Crash recovery survived a real mid-checkout power-off and resumed the dash on the correct phase.**
   ~17:01, at the **H-E-B checkout lane**, the dasher **dropped the phone and it powered off** mid
   **checkout/pickup flow** (same H-E-B ACV shop as item #1). On restart, the dasher relaunched
   DoorDash/DashBuddy and the **bubble HUD came back up, recognized it was still on a pickup, and the
   prior (pre-crash) dash resumed** — no new dash, no lost session. This is exactly the `StateManagerV2`
   crash-recovery path (replay observations over the last snapshot) working against a **true cold
   power-loss** (not a process kill) — the strongest version of the test.
   - **Why this is a good characterization case:** the crash landed **inside the checkout/pickup
     confirm window**, which is the phase the dropoff/grace machinery is most sensitive to (the
     06-17/06-19 phantom-dropoff and double-complete investigations all clustered around
     pickup-confirm → dropoff transitions). Recovering *onto a pickup* mid-checkout — rather than
     skipping to dropoff or re-minting the task — is the behavior we want to confirm held.
   - **Second confirmation — held through end of dash (2026-06-20).** After recovery the resumed dash
     stayed intact: the prior dash's history was **all still present**, the dasher **finished the
     delivery and ended the dash normally**, and everything persisted as expected (no lost session, no
     duplicate dash). Two independent in-field confirmations: the recovery itself (resumed onto the
     pickup) and the clean end-of-dash teardown.
   - **Still want the db cross-reference at desk (not blocking the 2/2 — confirmatory).** Logs will be
     reviewed anyway; when they are, verify in `app_state_snapshots` / `app_events` around 17:01: (a) the
     recovery restored the **same** job/task identity (no re-mint, no new sessionId for the resumed
     dash); (b) **exactly one** pickup task for the H-E-B shop across the crash boundary (the checkout
     interruption didn't split or double it); (c) the eventual `DELIVERY_COMPLETED` for this order fires
     **once** and reconciles into session earnings (this delivery was the second-to-last of the dash).
   - **Status:** **Validated — 2/2 field confirmations** (recovery onto pickup + clean end-of-dash with
     full history intact, both 2026-06-20). Desk db cross-reference above remains as a confirmatory check
     during the routine log review, not a blocker. Acting as field-testing agent: recorded only, no code
     changes.

---

## 2026-06-19 — DoorDash session (desk analysis of captured dash data)

- **Platform tested:** DoorDash
- **Branch under test:** `master` at `fac5d0d7` (post-#544 SSOT campaign) — **before** the unmerged
  #526 pickup-placeholder work (branch `refactor/526-pickup-placeholders-swap`).
- **Field conditions:** 4 dashes, ~8h. Analysis is a **desk pass over the uploaded data**
  (`~/dashbuddy/logs/2026/06/19/`: db `dashbuddy-v2.db` = 116 `app_events` + 248 `app_state_snapshots`;
  ~110k log lines; 76 UNKNOWN window + 47 UNKNOWN click captures), grounded + adversarially verified.
  **Hypotheses, not concluded fixes.** No code changes from this session.

### Verification TODOs — 06-17 fixes held in the field (confirmation 1/2)

The event stream is **structurally clean** — 18 `OFFER_RECEIVED` (9 ACCEPTED + 9 DECLINED), 9
`DELIVERY_COMPLETED`, every offer carries non-null pay, every completion carries non-null pay + a
distinct customer hash, exactly one completion per job, earnings reconcile to the cent ($152.07
completed vs $145.90 accepted = two legit post-accept tip bumps), and **zero ERROR log lines** across
8h. This is **confirmation 1/2** for each of the following (each still needs a 2nd clean dash):

- **#498** (phantom / "the customer" / $0.00 completion): 0 dropoff tasks with a null `customerNameHash`
  across all 248 snapshots; "the customer" placeholder never stuck; no $0.00 completion.
- **#518** (cross-job leak / double-count): exactly 1 `DELIVERY_COMPLETED` per job for all 9 jobs; the
  #522 idempotency key is firing.
- **#517 / #498 ghost**: all 18 `OFFER_RECEIVED` have a non-null `parsedOffer.payAmount`.

### Bugs / data-quality — the one multi-store stack (job `…210799-1`: Target SHOP + Maple Street PICKUP, $15.15)

This was the **first real multi-store stack on the post-#503 build** and the only rich anomaly of the
day. Two pickups (Target, Maple Street) and two distinct dropoff customers (`8e2dfa`, `e1266f`) formed
correctly — the old heuristic minted both pickups fine and slice-3b kept the drops distinct. But:

#### 1. A PICKUP task acquired a delivery customer hash — *(data-quality, hypothesis → #548)*
The Maple Street **pickup** task carries `customerNameHash=8e2dfa`. It binds on the `pickup_arrival`
frame (`app_events` seq 9; snapshot rowid 45→50). **Likely cause:** the `pickup_arrival` rule
legitimately `sha256`'s the "Order for X" name (`doordash.json:1071`) and the stepper stamps it onto
the active pickup (`PlatformRegionStepper:901`). Privacy is intact (hash at edge); semantically a
pickup shouldn't own a delivery customer, and that hash then bleeds onto the first dropoff. Filed #548.

#### 2. Both dropoffs labelled "Maple Street" — the Target order's drop lost its store — *(bug, hypothesis → folded into #526)*
Final state (snapshot rowid 1005): both DROPOFFs show `store='Maple Street Biscuit Company'`, but the
combined receipt (seq 18 `parsedPay`) proves one is the Target order (`Target (02426) $2.25` alongside
`Maple Street - Alamo Ranch $6.50`), and `offerStoreHint=['Target']`. **Likely cause:** **no dropoff
rule parses a `storeName` at all**, so `storeName = taskFields?.storeName ?: currentTask?.storeName`
(`PlatformRegionStepper:876`) makes each drop inherit the prior pickup's store. There is no
offer-order-index ↔ dropoff binding to recover Target on its drop. **This is exactly the mis-attribution
class #526's swap primitive targets** — and it reshaped #526: the reliable mis-bind signal is *store
divergence* (a dropoff's inherited store ≠ its order's `offerStoreHint`), **not** customer correlation
(which already works), and #526's scope widens to dropoff store re-attribution.

### Field UX context — stacked-drop completion is one combined receipt *(dev clarification, NOT a bug)*

The stack fired 2 `DELIVERY_ARRIVED` + 2 `DELIVERY_CONFIRMED` but only **1** `DELIVERY_COMPLETED`
($15.15 combined). **Dev confirmed in-field (2026-06-19): this is expected** — DoorDash shows **no
PostTask receipt between stacked drops; one combined receipt at the end**. Completion fires on
PostTask-exit (`EffectMap:286`), so one combined completion is correct. The end receipt's `parsedPay`
breaks out per-order pay, which is the data source for **per-drop attribution = #528** (an enhancement,
not a defect).

### Open questions / recognition gaps (lower priority — state stayed clean via grace)

1. **Dropoff-arrived "refined map pin" card → UNKNOWN** (~7 frames; ids `dropping_off_action_view` /
   `complete_delivery_steps_button`). Carries raw name + full address + gate code → any rule MUST
   `sha256` name/address and not store gate-code text. Filed #549.
2. **Shopping sub-flow family → UNKNOWN** (~10 frames): barcode-scan-confirm, substitution / wrong-item
   / shelf-photo / weight-mismatch, and a **"we adjusted your pay" toast** (a mid-shop pay change
   currently invisible to the state machine). Filed #550.
3. **The stack offer's UNKNOWN frames are transient partial renders** of the bottom-sheet (loading →
   Accept-only → Decline-only) — **not** a missed offer; the stable frame parsed the 2-order stack
   correctly. Frame-admission noise, not a recognition gap.

### Meta / architecture — logging is not semantic (→ CLAUDE.md Principle #7, #551)

~110k lines under one Timber tag (`App`), DEBUG 76% / INFO 22% / WARN 1.2% / ERROR 0; INFO is per-frame
`SCREEN:` spam (`dropoff_navigation` ×7,406), WARN is drowned by benign `👻 NULL CHILDREN`. **Privacy
finding:** raw merchant names already leak into INFO+ lines (`INFO/Chat: Pickup: H-E-B`; a TTS line
naming two stores) — a user-shareable bug report from INFO+ would ship third-party identity in
plaintext. Drove the new **Development Principle #7 (Semantic, PII-safe logging)** + implementation
issue #551.

## 2026-06-17 — DoorDash session (live dash, in-field narration)

- **Platform tested:** DoorDash
- **Branch under test:** `master` (field build) — exact SHA not captured in-field; infer from the
  most recent `master` merge if needed. Build is post-#503-slice-3 (the dropoff-from-offer +
  lowercase-"the customer" placeholder is present, see below), but **before slice 3b** (multi-drop
  ownership), so a stacked/multi-drop is expected to still mis-handle extra dropoffs.
- **Field conditions:** narrated live while driving (evening, ~8pm). Recorded for triage —
  **hypotheses, not concluded fixes.** No code changes this session.

### Bugs

#### 1. Single H-E-B order — dropoff card never resolves to the customer hash; stays on "the customer"
> **CORRECTION (2026-06-18, dev):** this was **NOT a stack** — *H-E-B offers are only ever single*
> (right now). The original in-field read of a "double" was wrong; the "second minted task" it
> referred to was the **phantom dropoff** the desk follow-up below identifies, not a real second drop.
> The stacked/multi-drop framing in the rest of this item is superseded by that follow-up.

On a (single) **H-E-B** order, a drop-off card showed the **placeholder "to the customer"** and
**never resolved to a real customer hash**. The dasher's read: the chrome/frame was **recognized but
carried no customer data** — the customer field was **null/empty** in the hash (recognized frame,
empty customer parse), so the card sat on the placeholder instead of the short 6-char hash code.

- **Desk follow-up (2026-06-18, grounded in the 06-17 capture db `app_state_snapshots`):** confirmed
  the mechanism, and it is **not** multi-drop. Dropoff phase is entered from the *flow* (a
  `task:dropoff:*` screen), so a transient confirmation/arriving screen that parses **no customer**
  (`dropoff_completed_confirm`, `dropoff_geofence_warning`, `nav_arriving`) yields `taskPhase=DROPOFF`
  with a null customer, and the stepper's fall-through mint created a fresh **identity-less dropoff**
  (`customerNameHash==null && customerAddressHash==null`) that immediately completed — rendering as
  "the customer". Evidence: the *only* such null/null DROPOFF tasks in the whole ~2-day session were
  **task-9** (this single H-E-B order), **task-13**, and **task-38** (which belongs to a genuine Jim's
  stack — a separate case); every real, resolved dropoff carried a customer hash. A second, distinct
  defect on the Jim's **stack** also split one physical drop into two tasks (task-39/-40 — same name
  hash, drifting address hash). **Fix in flight — PR #521** (`#498`): (a) gate the stacked-dropoff
  mint on the stable customer-**name** hash; (b) suppress the fall-through dropoff mint when the frame
  carries no customer identity at all (resume / resolve-onto-placeholder paths untouched). Needs field
  re-validation that a single H-E-B dropoff now shows the real 6-char hash. The multi-drop *stack*
  ownership itself remains #503 slice 3b.

- **Field UX note — the placeholder copy CHANGED (and this part looks correct):** the placeholder is
  now all-lowercase **"to the customer"**, not the old name-like capital **"Customer."** That matches
  the **#503 slice 3** design exactly ("an unresolved one shows 'the customer' (lowercase, briefly,
  never the name-like 'Customer')"). So the lowercase placeholder is the **shipped fix behaving as
  designed**; the open problem is that on this order it **didn't go on to resolve** to the real
  customer hash.
- **Likely cause (hypothesis):** this is a **stacked / multi-drop** order, and the
  `## Next field test` checklist for **#503** is explicit that **multi-drop is slice 3b and NOT yet
  shipped** — "a stacked/GoPuff multi-drop may still mis-handle the extra dropoffs." So a stuck
  "the customer" card on a *double* is consistent with the known not-yet-shipped multi-drop
  ownership, rather than a new regression in the single-order slice-3 path. One possibility: the
  dropoff subtask created at offer-accept (customer TBD) never gets the real customer **resolved onto
  it** for the second/extra drop in a stack, so the placeholder lingers. A second possibility: the
  dropoff frame for this order genuinely parsed an **empty customer** (null name → empty/sentinel
  hash), in which case the resolve had nothing to bind. These need the capture to tell apart.
- **To confirm (desk, after capture download):** pull this dash's `captures/` + `app_events` for the
  H-E-B double and (a) check whether the dropoff frame(s) actually parsed a customer name/address at
  all (was the `order_cx_name`/customer bind populated, or empty → null hash?); (b) trace whether a
  customer **ever resolved** onto the dropoff subtask(s) or it stayed on the placeholder for the
  whole leg; (c) confirm how many dropoff subtasks the stack minted vs. the two real stops (ties into
  the #503 slice-3b multi-drop work and the earlier 2→4 doubling, 2026-06-14 #2). Desk call — not a
  concluded fix.
- **Relates to:** [#503](https://github.com/sjtrotter/DashBuddy/issues/503) (Job container / dropoff
  ownership — slice 3 shipped the lowercase placeholder + dropoff-from-offer; **slice 3b multi-drop
  not yet shipped**). Also cross-refs the premature/unsettled-frame dropoff class (2026-06-13 #1,
  2026-06-14 #2).
- **Status:** Open (data point on the known-unshipped #503 slice-3b multi-drop case).

#### 2. Premature "$0.00 PAID" card mints IMMEDIATELY on accepting an offer (stacked order)
**Happened a few times today (2026-06-17, this sighting ~20:13 / 8pm).** Right after accepting an
offer, the bubble's completed-card stack shows a **PAID card reading `$0.00` delivery total** —
before any pickup or delivery has occurred. Screenshot evidence (two frames, 20:13): the stack shows,
top to bottom, the just-accepted **Offer** card (Chili's Grill & Bar · $19.10 · score 82 · $37/hr ·
net $16.35 · 7.7 mi · 2 items · **Accepted**), then a **`PAID — $0.00` card** ("$0.00 delivery total,
session $32.05"), then the correct **Pickup** card (Chili's). Header still reads **`AT STORE`** — the
delivery hasn't happened, yet a $0 paid card already minted.

- **Field signal:** it was a **stacked / double offer** — the offer detail line read **"Chili's Grill
  & Bar + Jim's Restaurant"** (two stores). Same stacked-order context as bug #1 (the H-E-B double).
  And the paid figure is **all-zeros ($0.00)** — the same all-zeros/empty-parse signature seen in the
  ghost-offer class.
- **Desk trace (hypothesis, NOT a concluded fix):** the accept *reducer* itself does **not** produce a
  paid signal — the "Saved: $X" bubble (`core/state/.../EffectMap.kt:705-736`) and `DELIVERY_COMPLETED`
  (`EffectMap.kt:286-311`) are both strictly gated on `Flow.PostTask`, which only the **delivery-summary
  (receipt) rules** produce (`core/pipeline/src/main/assets/rules/doordash.json:531-640`
  `delivery_summary_expanded`, `:641-722` `delivery_summary_collapsed`, both `flow: post:task`). So the
  premature paid almost certainly comes from a **post-accept transient frame misrecognized as a
  delivery-summary frame**, not from the accept logic.
  - **Load-bearing detail:** the `delivery_summary_collapsed` rule (priority 31) requires only
    `allTextContainsAny: ["this offer", "delivery complete"]` + a `final_value` currency parse
    (`doordash.json:649-653`). The phrase **"this offer"** is generic offer-context copy that can
    appear on a post-accept/transition frame; if such a frame also carries any `$`-node that parses as
    `final_value`, it classifies as `post:task` → enters PostTask. The **$0.00** we see fits an
    **empty/zero parse** (no real `totalPay`), which is also why the "Saved: $X" *chat* bubble (which
    gates on `totalPay > 0`, `EffectMap.kt:722`) may not have spoken even though the **PAID card**
    rendered from the PostTask entry.
  - **Why the idempotency gate didn't stop it:** `diffPostTask` gates on
    `next.activeTask?.taskId ?: next.recentTasks.lastOrNull()?.taskId` (`EffectMap.kt:718-720`), and the
    #503 dropoff-from-offer change pre-creates a fresh DROPOFF subtask (new, never-announced taskId) at
    accept (`PlatformRegionStepper.kt:502-526`) — so the per-task gate that normally blocks a repeat
    "paid" doesn't protect a *first* spurious fire on that brand-new task identity.
- **Strongly related to prior reports:** this is the same **post-accept unsettled/stale-frame** failure
  class already logged (2026-06-14/-15 ghost-offer "fired right after accept", README #498 watch item)
  — here it lands on the **delivery-summary** rule instead of `offer_popup`. Cross-refs
  [#498](https://github.com/sjtrotter/DashBuddy/issues/498) (recognition must reject incomplete/chrome
  frames) and [#503](https://github.com/sjtrotter/DashBuddy/issues/503) (Job container / accept→job
  transition should not re-observe a chrome-only frame; stacked context).
- **To confirm (desk, after capture download):** pull this dash's `captures/` + `app_events` around
  ~20:13 for the Chili's+Jim's accept; find the frame between `OFFER_ACCEPTED` and the first task flow,
  read its X-Ray for "this offer"/"delivery complete" text + any `final_value` currency node, and
  confirm whether it classified `post:task` with `totalPay == 0`. Also count how many of "a few times
  today" left a $0 PAID card vs. emitted a real `DELIVERY_COMPLETED`/"Saved" bubble.
- **Files:** `EffectMap.kt:705-736` (Saved bubble), `:286-311` (DELIVERY_COMPLETED),
  `doordash.json:531-640` + `:641-722` (post:task receipt rules; the "this offer" trigger),
  `PlatformRegionStepper.kt:366-378` (pay accumulation on PostTask entry), `:502-526` (#503
  dropoff-from-offer), `OfferActionReceiver.kt:32-39` (accept dispatch).
- **Status:** Open — appears repeatable today; needs the capture replay to confirm the misrecognized
  frame. (Recognition/state class — desk fix, not in-field.)

#### 3. End-of-dash earnings/PAID card did NOT render, though the "Saved: $X" chat DID — possible rewards-tier (Silver) parse variant
**~20:48, 2026-06-17.** The dasher **just reached a DoorDash rewards tier — "Silver" status** — and on
ending the dash the **earnings/PAID card did not appear** in the bubble's completed-card stack, **even
though the "Saved: $X" chat message DID fire** (so earnings were captured at least into the chat). The
dasher's own framing: *"we might have to double-check our strategy for parsing"* — i.e. the
rewards-tier UI may be the differentiator.

- **Which card is "the earnings card at the end"?** Ambiguous in-field between (a) the per-delivery
  **PAID/PostTask completed card** and (b) the **end-of-dash summary card**. Logged as both candidates;
  desk to disambiguate from captures. The "Silver status / at the end" framing leans toward the
  **end-of-dash summary** (tier progress usually shows on the dash-summary screen), but the
  per-delivery paid card can't be ruled out yet.
- **Preliminary hypothesis (NOT concluded — desk trace running):** the "Saved: $X" chat fires *while on*
  `Flow.PostTask` (gated on `totalPay > 0`), whereas the completed-card commit / `DELIVERY_COMPLETED`
  fires on the **PostTask→non-PostTask EXIT edge** (`EffectMap.kt:286-311`, per the bug #2 trace). So a
  plausible mechanism is: the receipt parsed enough to fire the Saved chat, but the flow **never cleanly
  left PostTask** to commit the completed card — and a **rewards-tier interstitial** (e.g. "You reached
  Silver!") landing between the receipt and idle is exactly the kind of unrecognized screen that could
  disrupt that exit. Equally, a **tier-variant dash-summary layout** could fail the dash-summary rule's
  field parse so the summary card never builds. Needs the capture to confirm which.
- **To confirm (desk, after capture download):** pull the ~20:48 end-of-dash `captures/` + `app_events`;
  look for (a) a "Silver"/tier interstitial or a tier-variant summary screen classifying UNKNOWN or
  mis-parsing; (b) whether `DELIVERY_COMPLETED` / the summary-card event was ever logged vs. only the
  PostTask-entry "Saved" effect; (c) whether the flow stayed parked in PostTask. **Drop any
  unrecognized Silver-tier / summary screen into `snapshots/INBOX/`** so the rule can be checked against
  the real tree.
- **Desk trace UPDATE (mechanism confirmed; still a hypothesis pending capture):** the PAID card in the
  bubble stack is built **only** from an `AppEventType.DELIVERY_COMPLETED` event
  (`app/.../ui/bubble/cards/FlowCardMapper.kt:299-330`), and `DELIVERY_COMPLETED` is emitted **only** on
  the **PostTask→non-PostTask EXIT edge** (`EffectMap.kt:286-311`). The "Saved: $X" chat, by contrast,
  fires on **PostTask ENTRY/dwell** (`EffectMap.kt:705-736`, only needs one PostTask frame with
  `totalPay > 0`). And an **UNKNOWN / unrecognized screen leaves the flow unchanged** — `flow == null`
  returns `prev.copy(...)` (`FlowRegionStepper.kt:33-36`), with no timer that force-exits PostTask. So a
  **rewards-tier interstitial** ("You reached Silver!") sitting between the receipt and idle keeps the
  flow parked in PostTask → the exit edge never fires → no `DELIVERY_COMPLETED` → **no PAID card**, even
  though the Saved chat already fired. Confirming detail: the ruleset has **NO tier/Silver/Gold/rewards
  handling at all** (grep of `doordash.json`), so a Silver screen is UNKNOWN *by construction*.
  Secondary path (not excluded): a tier-variant **layout** could fail the `delivery_summary_expanded`
  `containsAll`/sum-check (`doordash.json:618-628`) or the `dash_summary` parse (`doordash.json:2320`).
- **Which "earnings card"?** The trace clarifies the **end-of-dash SUMMARY is a SEPARATE surface**
  (`SessionSummary`, `BubbleViewModel.kt:147-173`), **not** a card in the stack — there are only five
  card types (Awaiting/Offer/Pickup/Delivery/PostTask). So "the earnings card at the end" is most
  likely the **per-delivery PAID card** (the one gated on the PostTask exit), which fits Saved-chat-yes,
  card-no exactly.
- **Relates to:** the post-accept/receipt recognition family (#498/#503) and the receipt-grace work
  (#431). Distinct from bug #2 (there a $0 paid card minted with no Saved chat; here the inverse —
  Saved chat with no card).
- **Status:** Open — desk trace done (mechanism above); **capture still needed** (esp. the Silver-tier
  screen → `snapshots/INBOX/`) to confirm the flow parked in PostTask vs. a summary-parse failure.

#### 4. Declined offer logged as TIMED OUT (and may be linked to #3)
**~20:48, 2026-06-17, the next offer right after the missing-earnings-card dash.** The dasher
**DECLINED** an offer, but the app recorded it as a **timeout** (an `OFFER_TIMEOUT`-style outcome, not a
decline). The dasher suspects the missing-earnings-card state (#3) **contributed** to this — *"two
separate but maybe related bugs."*

- **Preliminary hypothesis (NOT concluded — desk trace running):** a decline is likely detected from a
  recognized decline action/confirmation or the offer popup vanishing; if the machine didn't see an
  explicit decline signal it may **fall back to timeout** when the offer expires/disappears. The
  suspected link to #3: if the machine were still **parked in a prior unresolved PostTask/task** (or a
  lingering grace) from the dash that didn't close cleanly, the next offer's resolution could be
  misread. Both could share a root in "the prior task/PostTask never closed cleanly." The background
  desk trace is checking the OFFER_TIMEOUT vs decline trigger conditions and whether stale prior-task
  state can bleed into the next offer's outcome.
- **Desk trace UPDATE (root identified; strong hypothesis):** `resolveOfferOutcome`
  (`EffectMap.kt:888-906`) returns `OFFER_TIMEOUT` as the **default fallback whenever no ACCEPT/DECLINE
  click intent was recorded** — it is **not** driven by any expiry timer; the offer "resolves" simply
  when the popup vanishes (`EffectMap.kt:173`, `FlowRegionStepper.kt:96-100`). The catch: the **first**
  decline tap emits intent **`initial_decline`** (`doordash.json:2901-2911`), which is **neither**
  `OfferIntent.ACCEPT` **nor** `OfferIntent.DECLINE` — so it falls through to the TIMEOUT default.
  `OFFER_DECLINED` is recorded **only** if the **confirmation dialog's** "Decline offer" tap
  (intent `decline_offer`, screen `offer_popup_confirm_decline`, `doordash.json:2912-2923`) is captured.
  So a decline the dasher confirmed in DoorDash's dialog whose **confirm-tap wasn't observed as a Click**
  → defaults to `OFFER_TIMEOUT`. That is exactly "declined but logged as timed out."
- **Re: "maybe related" to #3 — confirmed right instinct, but mechanically INDEPENDENT.** The next
  offer mints a **fresh `PendingOffer` with `lastClickIntent = null`** (`FlowRegionStepper.kt:67-81`); a
  lingering PostTask from #3 does **not** feed `resolveOfferOutcome`, so there's no shared *code* root.
  What they likely share is an **environmental** root: the Silver-tier UI churn / transient unrecognized
  screens that parked #3 in PostTask are the same kind of recognition disruption that can **drop the
  decline-confirm Click capture** on the very next offer (#4). Two distinct code paths, one common
  trigger window.
- **To confirm (desk, after capture download):** pull the ~20:48 offer `captures/` + `app_events` for
  the declined offer; confirm whether the confirm-dialog `decline_offer` Click was captured (→
  DECLINED) or only `initial_decline`/nothing (→ TIMEOUT default), and whether tier-screen churn
  coincided.
- **Status:** Open — desk trace done (root above: decline-confirm click not captured ⇒ TIMEOUT
  default); capture confirms which.

### Open questions / investigations

#### 5. Does the dasher's items/min shop pace feed into offer value? — VALIDATED (desk): NO, not yet
The dasher asked whether their **item-picking speed (items-per-minute / shop pace)** is being used to
**assist the offer-value calculation**, and whether it's "still keeping track." Desk validation over
the code (this session):

- **Still tracked? Yes — but live + display-only.** items/min is computed inline in the shop bubble
  card (`app/.../ui/bubble/cards/FlowCardItem.kt:597-614`) as `shopped / elapsedMinutes`
  (`elapsedMs = now - (arrivedAt ?: phaseStartedAt)`), recomputed each recomposition. The computed
  `pace` is a local `val` — **never stored, emitted, or returned**.
- **Fed into offer value? No.** The evaluator (`domain/.../evaluation/OfferEvaluator.kt`,
  `UserEconomy.kt`) estimates time as `estTimeMinutes = dist * avgMinutesPerMile + basePickupMinutes`
  (`OfferEvaluator.kt:24`) — both **fixed user constants** (defaults 2.5 / 7.0, `UserEconomy.kt:105-106`),
  with **no item-count term and no pace term**. A 3-item vs 40-item shop estimates the same time → same
  $/hr. `offer.itemCount` does reach the evaluator but **only** as an optional "Max Items" cap metric
  (`MetricType.ITEM_COUNT`, `OfferEvaluator.kt:204-206`) — a score nudge, never converted to time, so
  it never moves the $/hr.
- **No historical/aggregated pace exists.** No DataStore source, DB entity, or repository persists
  items/min; there is no stored average pace the evaluator could consult. So even if we wanted a
  pace-adjusted shop-time estimate, the input data isn't being retained yet.
- **Upshot / where this could go (NOT a concluded fix — a direction to weigh):** wiring shop pace
  into offer value would need (a) persisting per-session/aggregated items/min somewhere, and (b) a
  shop-aware time term in `UserEconomy`/`OfferEvaluator` (e.g. estimated shop minutes ≈ `itemCount /
  avgItemsPerMinute`) instead of the flat `basePickupMinutes`. Today neither exists. Possible future
  enhancement; the dasher decides whether to file it.

### Meta / architecture

#### 6. Go Puff offers are RARE for this dasher (context for #501)
For desk awareness: **Go Puff (DoorDash Drive / warehouse) offers are a very rare offer type** for
this dasher — so the #501 Go-Puff recognition work and any Go-Puff capture asks will see **infrequent
field opportunities**. Plan capture collection accordingly (grab everything when a Go Puff order does
land, since the next one may be a while out).

## 2026-06-14 — DoorDash session (live dash #2 — Go Puff QR pickup, post-#495 build)

- **Platform tested:** DoorDash
- **Branch under test:** `master` @ `9240d54` (post-#495 merge; field build on the
  `claude/gopuff-qr-pickup-recognition-2vb5zu` branch, which is even with master — no code
  changes of its own yet).
- **Field conditions:** new live dash narrated in real time while driving. First order is a
  **Go Puff** pickup — a "special" pickup type where the dasher must **scan a QR code at the
  store** to pick the order up. Recorded for triage — **hypotheses, not concluded fixes.** The
  dasher expects **several new Go-Puff-specific screens** that will each need recognition and will
  feed captures separately.

### Bugs

#### 1. Go Puff QR pickup — **post-arrival screen(s) not recognized** (UNKNOWN)
On a Go Puff pickup the **post-arrival** step fell to UNKNOWN. Go Puff differs from a normal store
pickup: instead of (or in addition to) the usual "Pickup from / Confirm pickup" flow, the dasher
arrives and has to **scan a QR code** to claim the order, which appears to introduce one or more
Go-Puff-specific screens between arrival and pickup-complete that the DoorDash ruleset doesn't
cover yet.

- **What's already covered (desk, `core/pipeline/src/main/assets/rules/doordash.json`):** there is
  a `doordash.screen.pickup_qr_confirm` rule (priority 53) keyed on `"Confirm that the code was
  scanned"` + `"Scan code again"` — i.e. the **post-scan confirmation** screen. The standard
  `pickup_arrival` / `pickup_pre_arrival` / `pickup_navigation` screens also exist. So the gap is
  the **Go-Puff arrival / QR-prompt surface(s)** that sit *before* that confirm screen (the screen
  that actually tells the dasher to scan, and possibly a Go-Puff-branded arrival card), which match
  none of the current `require` predicates and so classify UNKNOWN.
- **Likely cause (hypothesis):** the Go Puff arrival/scan-prompt screens carry text/viewIds that
  none of the existing pickup rules' predicates match (the existing pickup rules key on
  "Pickup from"/"Pickup for"; the only scan rule keys on the *confirm* copy). Without a Go-Puff
  arrival/QR-prompt rule, the post-arrival frame has no match → UNKNOWN → captured to disk for
  triage, never stepped into the flow, so the pickup task likely doesn't advance on the bubble for
  this order.
- **To confirm (desk, after capture download):** pull this dash's `UNKNOWN/` captures for the Go
  Puff order and read the X-Ray for the arrival + scan-prompt screens; enumerate the full set of
  Go-Puff-specific screens (arrival card, "scan QR" prompt, the in-app QR/scanner surface itself,
  any "code scanned / confirm" and error states), then decide which are **recognize-only flow
  steps** vs. **document-capture surfaces**. Note for the privacy posture: a **QR/barcode scanner
  camera surface** is plausibly an image-capture surface, but a QR for *order pickup* is not a
  government ID / signature — so unlike the alcohol license-scan, the Go Puff scan prompt is most
  likely a **recognize-only** pickup step, not a blocked sensitive surface. Confirm against the
  actual captured tree before writing rules. Desk call — not a concluded fix.
- **Captures needed:** the dasher will supply the Go-Puff arrival + QR-scan + post-scan screens
  (drop into `snapshots/INBOX/`, run `InboxProcessorTest` for the X-Ray) so the new rules can be
  written against real trees.
- **Triaged 2026-06-15 → [#501](https://github.com/sjtrotter/DashBuddy/issues/501)** (recognize the
  Go Puff / DoorDash-Drive flow; deep-dive over the 06-14 captures/db/log was run — db aggregate
  `session-doordash-1781450940064-21` confirms NO clean `PICKUP_ARRIVED` on the warehouse pickup).
  Recognize-only, not a sensitive surface.

#### 2. Go Puff stacked order — **2-dropoff order logged FOUR drop-offs (doubled)**
The Go Puff order was a **stacked order with two orders / two drop-offs** (not three — the earlier
"three" note was the in-the-moment count, corrected here). The app **logged a total of four
drop-offs** for it — i.e. **each real dropoff was logged twice (2 → 4 doubling)**. That's the
"weird drop-off situation." The dasher's hypothesis: it's likely **specific to the Go Puff order
type** — a **later, ordinary (non-Go-Puff) stacked order on the same dash is so far working fine on
the pickup**, which points to the Go Puff flow (unrecognized QR arrival, #1) as the trigger rather
than stacked-handling in general.

- **Why it matters / hypothesis:** a 2→4 doubling is the **partial-render / unsettled-frame class**
  again (cf. 2026-06-13 #1 premature drop-off card, recurred 2026-06-14 dash #1; #458 frozen-twin;
  #470/#458 double-dropoff). The new wrinkle: it fired on a **Go Puff** order whose **arrival was
  UNKNOWN (#1)**. One possibility is that the unrecognized Go Puff QR/arrival sequence churned the
  flow (UNKNOWN frames between arrival and pickup) such that each dropoff committed twice — i.e. #1
  and #2 may be the **same root** (an unsettled Go-Puff pickup destabilizing the downstream dropoff
  commits), not two independent bugs. Would need the capture replay to confirm.
- **To confirm (desk, after capture download):** replay this session's `captures/` + `app_events`
  for the Go Puff order and count dropoff commit/log events vs. the two real stops — find where the
  extra two come from (re-observation of a dropoff frame? a grace commit firing twice? a task split
  spawning a phantom?). Compare against the later non-Go-Puff stacked order on the same dash (which
  the dasher says is behaving) to isolate whether the Go Puff path is the differentiator. Desk
  call — not a concluded fix.
- **Triaged 2026-06-15 → [#501](https://github.com/sjtrotter/DashBuddy/issues/501) +
  [#503](https://github.com/sjtrotter/DashBuddy/issues/503).** db confirms this is the unrecognized
  Go Puff warehouse pickup + a **multi-drop batch** (one offer → 4 drop subtasks with phantom same-ms
  `DELIVERY_NAV/ARRIVED`, seq 82–84). Recognizing the warehouse-pickup phase (#501) plus the Job
  container owning the drop subtasks (#503) is what removes the doubling — not a generic settle gate.
Strong case study for "what goes right vs. wrong" on stacked-shop + add-on. The order was a double
stack: **Sprouts + PetSmart/Petco**. Sequence the dasher narrated:
1. The app wanted **PetSmart first**, but the dasher would pass **Sprouts** on the way, so they used
   the **timeline screen to manually switch task order** and go to Sprouts first. *(Good: the
   `doordash.screen.timeline` screen — `TimelineFields` task chain, `ParsedFields.kt:225` — is
   recognized, so the reorder surface is at least seen.)*
2. While **shopping at Sprouts**, an **add-on offer** popped. The dasher accepted it.
3. **Bug:** because an offer came in, it **shouldn't have changed the task — but it effectively
   "re-minted" the task** (treated it as a new/restarted task) instead of folding the add-on into
   the active Sprouts shop.

**What it SHOULD have done (dasher):** for a **same-store** add-on it should *not* re-mint — it
should **bump the combined shop counts on the same task**, **add time to the pickup deadline**, and
**update the pickup line** (same store). 

- **Desk context — there's already a test asserting exactly the desired behavior:**
  `TaskLifecycleGuardTest."shopping add-on (or same-store stack) bumps the combined counts on the
  same task"` (`core/state/.../TaskLifecycleGuardTest.kt:327`) asserts `taskId` is **not re-minted**
  and the to-shop count grows. Job-model intent agrees: add-ons **append** to the active job, not
  replace it (`domain/.../state/Job.kt:7,31`; `TransitionDefaults.kt:18`). So the field behavior
  **diverges from the modeled/asserted behavior** — meaning the real path isn't the one the test
  exercises.
- **Likely cause (hypothesis):** the test models the add-on as a same-store `TaskPickupArrived`
  observation bumping counts. The **real** path went through the **offer→accept (new Job) flow**,
  and — per the dasher — the UI may have shown a **pickup-navigation frame first, then realized it
  was already at the store and swapped into the combined/"multi-pickup store" shop interface**. That
  navigation→store re-entry on a freshly-accepted offer is a plausible trigger for the stepper to
  start a fresh task (re-mint) rather than recognizing it as the same-store continuation the guard
  expects. So the guard may simply **not cover the accept-then-renavigate path**.
- **Open question — is the "multi-pickup store interface" its own recognized screen?** Grep shows
  pickup rules (`pickup_arrival`/`pickup_pre_arrival`/`pickup_navigation`) but no explicit combined
  **multi-pickup / two-pickup store** shop screen. If that interface is a distinct tree, it may need
  its own recognition so the add-on/same-store-stack reads as a continuation, not a new arrival.
- **Field UX context — same-store heuristic:** a pop-up add-on *while on another offer* **can** route
  to a different store, but **shopping add-ons frequently route to the same store**. A useful signal
  the dasher noted: **absence of an interstitial pickup arrival/navigation screen** ⇒ likely the
  **same store** (so fold in, don't re-mint). Not deterministic, but a candidate disambiguator.
- **To confirm (desk, after capture download):** replay the Sprouts add-on moment — the
  `offer_popup` + accept event, then the frame sequence (navigation? → combined shop interface?),
  and watch whether `activeTask.taskId` changes (re-mint) or counts bump on the same task. Capture
  the multi-pickup shop tree for a possible new rule. Desk call — not a concluded fix.
- **Triaged 2026-06-15 → [#499](https://github.com/sjtrotter/DashBuddy/issues/499) (blocked-by
  [#503](https://github.com/sjtrotter/DashBuddy/issues/503)).** Direction: don't tune the 10s
  `TASK_RETIRE` grace — re-model the **Job as the offer-accumulating container** so an add-on offer
  accumulates into the active job and the returning shop screen **re-matches the existing subtask via
  the offer's store hint** (the same-store correlation the dasher does by eye). Re-mint then can't
  happen by construction.

---

## 2026-06-14 — DoorDash session (live dash, post-#494 build)

- **Platform tested:** DoorDash
- **Branch under test:** `master` @ `4a81d34` (post the 2026-06-13 batch through #494 — incl. #473
  durable-last-dash, #470/#458 double-dropoff, #466/#467 money-formatter SSOT, #461/#476 shop cards,
  #460/#324 co-hero task cards, #462/#463 recognition + privacy batches).
- **Field conditions:** one live dash, narrated post-dash. Several offers (took some, declined some),
  including at least one stacked Shop & Deliver. This entry both records new findings and folds in the
  field confirmations that cleared/advanced the "Next field test" checklist this dash. Recorded for
  triage — **hypotheses, not concluded fixes.**

### Bugs

#### 1. Shop & Deliver hero **item count shows the # of stacked orders, not the # of items** (#461)
On a **stacked** Shop & Deliver offer the #461 hero item count surfaced the **number of stacked orders**
instead of the **number of items to shop**. On a single order the hero item count looked correct (see
confirmations below). FOUND BROKEN on the stacked case.

- **Likely cause (hypothesis):** the hero is bound to an orders/stops count rather than the shop item
  count, so it only diverges once `orderCount > 1`.
- **To confirm (desk):** pull this dash's stacked-offer `offer_popup` capture + the parsed fields feeding
  the #461 hero, and check what the hero count binds to when there are multiple orders (items vs. order
  count). Desk call — not a concluded fix.

### Research / design (improvement ideas — explore, not yet scoped)

#### 2. Offer badges should use icons; the SHOP badge carries the item count (#461)
Developer **design direction** on #461 — the item count in the co-hero **feels too surfaced** ("almost
too surfaced"), and it should not live in a co-hero slot at all:

- **Icons are the norm for offer badges** (Shop & Deliver, Red Card, etc.) — use the icon, not a text
  label. We already had icons for these somewhere worth revisiting.
- **The Shop & Deliver badge specifically is the shop icon WITH the number of items** — icon + item
  count together, in the badge, **full stop**. Not "icon *or* count," not a separate co-hero slot — the
  shop badge is `[🛒 N]`.
- The **Red Card** likewise gets its own icon badge.

Recorded as the developer's stated design direction for #461, not a fix applied here.

### Verification TODOs (checklist outcomes this session)

Confirmations that cleared or advanced the "Next field test" checklist on this dash:

- ✅ **In-bubble Accept/Decline (#425) — VALIDATED (2/2).** Both Accept and Decline tapped in the
  expanded bubble registered on DoorDash (2nd clean confirmation after the 2026-06-12 Accept-only
  sighting). The separate **notification-shade** buttons remain broken — tracked by **#457** (2026-06-12
  #11). Removed from the checklist.
- ✅ **Receipt grace, once-per-delivery (#431 pt 2, sub-case a) — VALIDATED (2/2).** No double "Saved"
  receipt anywhere this dash (2nd confirmation after 2026-06-12). Sub-cases (b) stacked-split and (c)
  misrecognition-survival are still unverified — kept on the checklist.
- ⏳ **Durable last-dash (#473/#459) — 1/2.** After the dash ended the bubble kept showing the last dash
  (chat + completed cards), not empty.
- ⏳ **"Saved: $X.XX" money format (#456) — 1/2.** The "Saved" bubble shows the `$` and 2-decimal format
  on all of them now. (The "tip added" bubble is the remaining raw-float straggler — 2026-06-13 #3.)
- ⏳ **Deadline caption, no double "by" (#460) — 1/2.** Reads fully fixed / different from before.
- ⏳ **Co-hero pickup card (#460/#324) — 1/2 (pickup).** Pickup co-hero rendered (timer + $/hr), though
  the dasher flagged it "maybe not wired right"; the drop-off `$/hr` still reads nil (broken —
  2026-06-13 #2).
- ⏳ **Session-end attribution + no mid-dash splits (#431/#279) — 1/2.** Dash ended cleanly, summary on
  the right session, no spurious splits or lingering session.
- ⏳ **No stale heads-up after resolving offers (#436 sub-case a) — partial.** None observed this dash.
- ⏳ **Per-offer dedupe (#427) / offer-eval matches screen (#345) — needs desk log check.** Dasher
  believes both are working but couldn't verify in the field; needs a desk pass over this dash's
  `captures/` + event log.
- 🔁 **New-dash-after-ending (#286/#290) — not exercised.** Only one dash today, so the
  end-then-start-fresh path couldn't be tested.
- ⚠️ **Extra drop-off card recurred (2026-06-13 #1, premature-frame class).** Another premature/extra
  drop-off card appeared this dash — now seen on a 2nd separate dash, so upgrade from "stray one-off" to
  a real recurring partial-render bug. Still distinct from the #458 frozen-twin case; grab the dropoff
  frame + `app_events` to confirm the unsettled-frame root (a shared settle/validity gate on recognition
  is the likely direction — desk call).
- ✅ **Watches — no recurrence this dash:** no "ghost offer" / blank-store offer cards (2026-06-13 #1
  ghost watch), and no mid-dash "Done Dashing!" + odometer reset (2026-06-06 #5 watch).

### Recognition screens (need desk verification after capture download)

The #462 recognition batch + #433 (`pickup_picked_up`) + #149 alcohol flow couldn't be verified from
memory in the field — they need a desk pass over this dash's downloaded `captures/` to confirm the
screens classified (not UNKNOWN), the flow stepped correctly, and customer PII was hashed (never raw).
Kept on the checklist at 0/2.

---

## 2026-06-13 — DoorDash session (live evening dash, post-#487 build)

- **Platform tested:** DoorDash
- **Branch under test:** `master` @ `55b93d0` (post the morning batch + #491 field-log). Includes the
  #460/#324 **co-hero task-card redesign** (`39a54a9`) and the #461/#476 Shop & Deliver cards.
- **Field conditions:** live dash, narrated in real time while driving. First HEB offer of the day;
  later declined one offer and took a second (also HEB). Recorded for triage — **hypotheses, not
  concluded fixes.**

### Bugs

#### 1. Premature/duplicate **drop-off card** — recognized before the screen fully loaded (same class as the ghost offer)
On the first delivery (first HEB of the day), the **drop-off card appeared before the screen finished
loading** — it briefly showed "Drop off customer", a **~2-second** timer, and **`$37/hr`** — and then
**a second drop-off card appeared directly after it**. The dasher explicitly tied it to the ghost
offer ("did the same thing as the offer where it recognized the screen before it fully loaded").

- **Context on the new look:** the `$37/hr` is expected — the #460 co-hero redesign now shows a live
  **"Running at $/hr"** on task cards (`FlowCardItem.kt` co-hero, ~`:514-560`). So the `$/hr` itself
  isn't the bug; the **two drop-off cards** are.
- **Likely cause (hypothesis):** same **partial-frame** class as the 2026-06-13 desk-review ghost
  offer (#1, prior entry) — a dropoff-nav/arrival frame recognized **mid-render** opens a Delivery
  card before the real one settles, leaving two. In `FlowCardMapper`, `DELIVERY_NAV_STARTED` opens a
  card keyed by `taskId`; a premature frame with a missing/again-different `taskId` (or an
  arrived-then-replaced frame) would produce a second card. This is **distinct from #470** (which
  fixed the frozen-`completed` + live-`active` overlap of the *same* taskId) — here the symptom is a
  *premature* card from an unsettled frame, not the at-door overlap.
- **To confirm (desk):** pull this delivery's `captures/` + `app_events` — expect a dropoff frame
  recognized very early (short dwell, ~2s) and **two Delivery rows / card ids** for the one stop
  (check whether the taskIds match or one is empty/garbage). If the early frame parsed empty (no
  customer/store), it's the same empty-partial-render root as the ghost offer → argues for a shared
  **settle/validity gate** on recognition (offer **and** task screens), a desk call.
- **Live update — did NOT recur:** on the **second** delivery (the next order's dropoff) **only one
  drop-off card showed**. So this may have been a **stray/transient** one-off rather than a
  reproducible dup. Downgraded to low-confidence; keep an eye out, but the capture above is only worth
  chasing if it happens again.

#### 2. Co-hero "Running at $/hr" goes **nil on the DROP-OFF** (pickup eventually shows it; shop X/total is fine)
Refined live (the dasher clarified — "running total" = the co-hero **"Running at $/hr"**, not the shop count):

- ✅ **Shop `X/total` works** — confirmed on **both** HEB pickups this dash. So the redesign's shop
  progress is fine; **rule out** the earlier candidate (b).
- ⏳ **Pickup `$/hr` populated (eventually).** The second HEB pickup card **eventually showed `$51/hr`**,
  which is **in line with the offer** — so the value is correct, but it may have been **slow to
  appear** ("eventually"), i.e. the blended economics threaded in a beat late.
- ❌ **Drop-off `$/hr` is nil.** On the drop-off the co-hero reads **~nil** ("Running at —").

- **Why this is odd (grounded):** `LiveCardBuilder` feeds **both** the live Pickup and the live
  Delivery the **same** source — `region.activeJob?.blendedNetPay` / `blendedEstMinutes`
  (`LiveCardBuilder.kt:77-78` pickup, `:93-94` delivery) — and `projectedHourly` returns null **only**
  when `netPay`/`estMinutes` is null **or `estMinutes <= 0`** (`FlowCardItem.kt:669-673`). Since the
  pickup showed `$51/hr`, `activeJob.blended*` was populated during pickup — so a **nil drop-off**
  means those values **don't survive into the dropoff phase** (or `blendedEstMinutes` has eroded to
  `0`/null by then).
- **Hypothesis:** the blended offer economics are dropped/zeroed at the **PICKUP→DROPOFF** transition
  (e.g. `blendedEstMinutes` is "remaining estimate" and goes to 0 once picked up, or `activeJob` is
  re-derived for the dropoff leg without re-blending). The "eventually" on pickup also hints the
  blend populates **late**.
- **To confirm (desk):** inspect `region.activeJob.blendedNetPay` / `blendedEstMinutes` (and how
  they're computed) at **dropoff** vs **pickup** for this job — expect one of them null-or-0 at
  dropoff. Decide whether the dropoff co-hero should reuse the **accepted-offer** net/time (fixed,
  like `FlowCardMapper`'s `acceptedNetPay`/`acceptedEstMin`, `FlowCardMapper.kt:161-162/186-213`)
  rather than a "remaining" blend that decays to 0. (Desk call — not a concluded fix.)

#### 3. **CONFIRMED** — the "tip added" bubble message bypasses the money-formatter SSOT (raw float)
The post-delivery **additional-tip** notification that posts to the bubble (e.g. *"Nice! $2.0 tip from
Cheesecake Factory"*) shows a **raw float** — it has a `$` but isn't currency-formatted (`2.0`, not
`2.00`). Same class as the "Saved: $X" fix (#456/#466), but a **separate message that was missed** in
the "route everything through the domain `Formats` SSOT" sweep.

- **Confirmed in code (not a hypothesis):** `TipEffectHandler.kt:23` builds the bubble text as
  `"Nice! \$${effect.amount} tip from ${effect.storeName}"` — `effect.amount` is a `Double`
  interpolated raw. The log line `:21` (`"Tip received: \$${effect.amount} …"`) has the same raw
  interpolation.
- **It's the only straggler:** a sweep of `app`/`core`/`domain` for `$`-prefixed raw money
  interpolations (excluding tests) found **only** `TipEffectHandler` (`:21`, `:23`). The tip line in
  the card breakdown already uses the SSOT (`FlowCardItem.kt:704`, `Formats.money(tip.amount)`).
- **Fix direction (SSOT, the obvious route — desk/follow-up):** route the amount through the
  `:domain` money formatter — `Formats.money(effect.amount)` → `$2.00` — in both the bubble text and
  the log line, exactly as `FlowCardItem`/the "Saved" message now do (#456/#467). A one-line change;
  recorded here per "note + follow-up."

---

## 2026-06-13 — DoorDash session (desk review of post-#487 build)

- **Platform tested:** DoorDash
- **Branch under test:** `master` @ `aaa8d94` (post the 2026-06-13 morning batch: #464–#490 — incl.
  #473 durable-last-dash, #470 double-dropoff, #466 money-formatter SSOT, #461 shop cards,
  #462/#463 recognition + privacy batches). The bubble now shows the last dash again (#473).
- **Field conditions:** desk review of a completed dash in the bubble's last-dash card stack, with a
  developer screenshot. Recorded for triage — **hypothesis, not a concluded fix.**

### Bugs

#### 1. **Ghost offer** — a phantom Offer card with EMPTY parse (no store / no pay / no miles), scored and logged
In the card stack, **between the Mello Mushroom and Pei Wei offers**, there is a ghost Offer card the
dasher doesn't recognize. From the screenshot:

- **Ghost card:** Score **24**, hero **`$-2/hr`**, **Net `-$0.36` · `-$0.36/mi`**, verdict
  **DECLINE / BAD OFFER**, outcome chip **Timed out** — and **no store name, no pay amount, no real
  distance** (the `-$0.36` / `-$0.36/mi` are cost-only artifacts of an empty parse).
- **Pei Wei card (contrast, fully populated):** Score 66, `$18/hr`, Net `$8.26 · 8.4 mi · $0.98/mi`,
  `2 items`, store `Pei Wei & Cold Stone Creamery`, Declined.

- **Likely cause (hypothesis):** a **transient/partial `offer_popup` frame**. The rule's `require`
  (`doordash.json` `doordash.screen.offer_popup`) is satisfied by the popup **chrome** — `"Decline"`
  text + (`"Accept"`/`"Add to route"`) + the `accept_button`/`accept_decline_footer_container` id —
  which renders **early**. But the **content** nodes the `parse` reads (`display_name` for the
  store/orders, the `$` pay text) hadn't rendered yet, so the parse produced **empty `orders`, null
  `payAmount`, and a near-zero/garbage distance**. The evaluator still scored the empty offer (Score
  24, Net `-$0.36` = $0 pay minus ~1 mi of cost), and because the frame was then replaced/expired
  without a real Accept/Decline, it was logged as **`OFFER_TIMEOUT`** → a ghost card. (This is the
  empty-parse cousin of the 06-09 #4 phantom, but **not** self-recognition — that gate is intact;
  this is a real DoorDash popup caught mid-render.)
- **Would the morning's fixes catch it? Probably not.** `#427`/`#436` dedupe the **same** offer by
  hash (a distinct empty parse has its own hash); the #4 self-recognition gate only drops **our**
  overlay. **Nothing currently rejects a real-but-empty partial offer frame** — there is no
  validity/settle gate before an `offer_popup` is presented, scored, and logged.
- **To confirm (desk):** pull the `offer_popup/*.json` capture in the Mello→Pei Wei gap + the
  `OFFER_TIMEOUT` `app_events` row. Expect a **partial tree** (footer/accept chrome present, but no
  `display_name` / no pay `$` node) and an **empty `parsedOffer`**.
- **Possible directions (desk call, NOT a concluded fix):** (a) a **validity gate** — don't
  present/score/log an `offer_popup` whose parse has **empty `orders` AND null `payAmount`**;
  (b) **settle/debounce** the offer frame before eval (the idea dropped in 06-09 #4 — but this empty
  partial-render is exactly its use case); (c) require at least a store **or** a pay value before the
  evaluator runs. Logged to the watch list so a live dash can confirm frequency before any fix.

---

## 2026-06-12 — DoorDash session (offer-copilot field test)

- **Platform tested:** DoorDash
- **Branch under test:** `master` field offer-copilot build (includes the #425 rule-bound
  Accept/Decline path; the freshest watch-list items reference #437/#436/#427). Exact SHA not
  captured this session — developer to correct if needed.
- **Field conditions:** **two dashes this session** (a first dash, then a second later the same day).
  At least one Shop & Deliver order (HEB, 25 items), offers accepted from the bubble, and two
  separate deliveries on the second dash. Observations narrated in real time; everything below is
  **recorded for triage — hypotheses, not concluded fixes.**

### Bugs

#### 9. "Saved: $X" earnings bubble omits the `$` sign (currency not formatted as dollars)
Dasher flagged the post-delivery **"Saved"** bubble's amount "doesn't [render] as a dollar amount"
and asked whether it was already fixed. **It is not.** Desk finding:

- The "Saved" bubble builds its text with a **local** `formatCurrency` in the state layer —
  `EffectMap.kt:704/710` call `formatCurrency(...)`, defined at `EffectMap.kt:870-871` as
  `String.format(locale, "%.2f", amount)`. That format **has no `$`** — so the bubble renders
  `Saved: 5.50`, not `Saved: $5.50`, even though the method's own docstring describes the output as
  `"Saved: $X"` (`EffectMap.kt:666`).
- It **does** zero-fill the cents (`%.2f` → `5.5` becomes `5.50`), so if the dasher saw a missing
  trailing zero that'd point elsewhere — **capture the exact on-screen string** next time to settle
  which symptom it is. Most likely the perceived defect is the **missing `$`**.
- **SSOT smell (#358 family):** this is a **separate, divergent currency formatter** from the
  app-wide `DashFormats.money` (`DashFormats.kt:25`, `"$%.2f"`, which *does* include the `$`). The
  docstring even flags it as a "known wart" pending #366 (move rendered copy out of the state layer).
  **Hypothesis:** the clean fix routes the bubble copy through the one money formatter rather than a
  local `%.2f`, but where the formatter can live is bound up with #366 — a desk call, not a drop-in.

#### 11. **FOUND BROKEN** — the notification-SHADE Accept/Decline buttons don't work (the in-bubble ones do)
**Surface taxonomy (dasher clarified — investigate the right one):**
- ✅ **In-bubble buttons** — the Accept/Decline buttons inside the **expanded bubble** (the in-app
  Compose `OfferActionRow`) **work** (entry #1: bubble Accept clicked DoorDash).
- ❌ **Notification-shade action buttons** — the Accept/Decline buttons on the **heads-up / shade
  notification** are what the dasher tapped, and **neither did anything** on DoorDash. **This is the
  broken surface.** Moves the notification half of the #425 checklist item to broken.
- ℹ️ Side note: the bubble's own (collapsed/conversation) notification "doesn't have buttons on it" —
  so the buttons the dasher pressed were unambiguously the **shade notification's action buttons**,
  not the in-bubble ones.

The two surfaces are NOT the same code path at the click edge even though they share the dispatch:

- **What's the same (so it's not the wire string):** the notification's Accept/Decline send
  `OfferIntent.ACCEPT`/`DECLINE` = `"accept_offer"`/`"decline_offer"`
  (`BubbleManager.kt:216-217`, `OfferIntent.kt:10-11`) — the **identical** strings the bubble
  dispatches — and `OfferActionReceiver` then dispatches the **same** `Observation.UiInput`
  (`OfferActionReceiver.kt:32-39`). So EffectMap's offer-action handling isn't the divergence.
- **What's different (the hypothesis):** the receiver is **manifest-registered**
  (`AndroidManifest.xml:80-81`) and the PendingIntent is an **explicit, same-app broadcast** with
  `FLAG_IMMUTABLE` (`BubbleManager.kt:238-245`), so it *should* reach `onReceive`. The likely break
  is **downstream of dispatch**: acting from the **system notification shade**, DoorDash's offer
  button isn't reachable to the accessibility click the way it is when the **in-app bubble overlays
  the live offer** — the shade (or an advanced/expired offer) is foreground, so
  `UiInteractionHandler`'s all-windows click finds **no live target** and aborts to manual. Same
  `Could not find any live node` *class* as the 06-09 bubble bug, but a different cause (wrong
  foreground window, not wrong-window search). The in-bubble buttons working fits this: the bubble is
  drawn over the live offer, so the target window is present.
- **One log line splits the two hypotheses (desk):** pull logcat around the taps. If
  `OfferActionReceiver: accept_offer` / `decline_offer` is **absent**, the broadcast never landed
  (PendingIntent/registration). If it's **present** but followed by a target-resolution failure /
  `Could not find any live node`, it's the click-target-absent path. Capture which.
- **Field note for next time:** retry the **shade** action **while still on the DoorDash offer
  screen** (shade pulled down over the live offer, offer not yet expired) and see if it behaves
  differently from tapping it after navigating away.

### Verification TODOs (confirmations this session)

#### 1. Bubble **Accept** clicks DoorDash — #425 rule-bound tap CONFIRMED (1/2)
Dasher tapped **Accept** in the bubble and DoorDash registered the Accept (offer accepted). This
is the first clean live confirmation of the `acceptButton`/`declineButton` rule-bound, label-verified
tap path (#425) — the same surface that was **broken on 06-09** (`Could not find any live node`,
wrong-window search). Bumped the checklist item to 1/2; still want a second sighting covering the
**Decline** side and the **notification** surface (not just bubble Accept).

#### 2. Post-delivery earnings **auto-expand** — #425 EXPAND_EARNINGS + #417 gate CONFIRMED (2/2, both retired)
The collapsed pay breakdown **auto-expanded on its own** after a delivery, as before. Confirms the
app-owned EXPAND_EARNINGS action path (#425) AND — implicitly — the live consent gate (#417): a
denied capability would have left it collapsed (`Denied expand_earnings — no granted capability`),
so the asset-rule auto-grant is covering it. **Dash 1:** expand fired + bubble Accept (#1) fired —
both gated taps with no regression. **Dash 2 (2026-06-12):** the post-delivery expand auto-tapped
**again** — second clean sighting. Two independent dashes of gated automation taps firing with zero
`Denied`/throttle → both the **#425 expand-earnings** and **#417 consent-gate** checklist items moved
out as validated (the gate reliably auto-grants bundled-rule capabilities).

#### 3. Bubble re-attaches to the active dash across a restart — #437 CONFIRMED (2/2, retired)
**Dash 1 (2026-06-12):** force-quit + reload **after resetting the accessibility permission** mid-dash
— a harder restart than a plain force-stop, since the accessibility service rebinds from scratch —
reloaded the bubble with the **current dash still active**. **Dash 2 (2026-06-12):** after a restart
the **completed cards repopulated** (not just the dash reading active) — the missing half of the
first sighting. Two clean sightings of the mid-dash active-dash re-attach → #437 moved out as
validated. The bubble's dash id derives from restored state, not the crash-suppressed effect.
**Caveat — does NOT cover the no-active-dash case (see investigation #8):** both #437 sightings were
restarts **while a dash was active**. Separately this session, after a **crash with no active dash**,
the bubble **cleared out** instead of showing the last dash — that's a distinct gap tracked as #8,
not part of #437's validated scope.

#### 4. Shop & Deliver terminal `total/total` — #302 CONFIRMED (2/2, retired)
The pickup/shop card read **`shop 25/25 · 0.6/min`** at end of shop — the terminal `total/total`
frame, no longer the `total−1/total` off-by-one. Second clean sighting → #302 moved out of the
watch list as validated. (The add-on / second-order-mid-shop case is still tracked under #276.)

#### 10. "Saved" fires once per delivery (#431 sub-case a, 1/2) + offers feel instant (#436 partial)
**Dash 2** had two separate deliveries and each produced **exactly one "Saved" bubble** — no
double-fire. Confirms #431 receipt-grace sub-case (a) ("Saved" fires exactly once per delivery);
bumped that checklist item to 1/2. The (b) stacked-order split and (c) misrecognition-survival
sub-cases were not exercised. (The bubble's currency formatting is bug #9 above — separate from the
once-per-delivery behavior, which is correct.) Separately, the dasher reports offer **Accept/Decline
feels fully quick — no perceptible delay** (loosely supports #436 (b) "verdicts land a touch
quicker"); #436 stays 0/2 since its dedupe/restart sub-cases weren't deliberately tested.

### Open questions / investigations

#### 12. 7-Eleven alcohol pickup: initial pickup screen UNKNOWN, but self-corrected to drop-off on nav (cross-refs #149/#433)
On a **7-Eleven alcohol** order (~**19:59 local**), the **initial pickup screen was not recognized**
(UNKNOWN — no pickup classification). The dasher picked the order up anyway and watched whether it
would recover: **as soon as the screen changed to the map (drop-off navigation), the app "automatically
knew" it was on drop-off** and corrected itself.

- **What this tells us:** the **state machine recovered via the nav/flow transition** — even though
  the pickup-confirm screen itself didn't classify, the drop-off-nav frame did, and the platform
  region advanced to DROPOFF. Good resilience signal (the missed pickup frame didn't strand the task).
- **The gap to chase:** the **7-Eleven (alcohol) pickup/confirm screen is an unrecognized variant**.
  Two ties: #433 notes `pickup_picked_up` had **zero corpus** and could never match before its
  mojibake fix — this may be exactly that screen failing to classify on a 7-Eleven layout; and #149
  is the alcohol ID-verify flow. **To confirm:** pull the ~19:59 capture for this task — expect an
  UNKNOWN around the pickup/confirm step; if so it needs a rule/corpus addition for the 7-Eleven
  (and possibly alcohol) pickup variant. **Capture is the unblock here** — this screen has little/no
  corpus.
- **Did NOT observe:** whether the missed pickup screen cost anything downstream (arrival/confirm
  timestamps, the pickup card's store/items) — worth checking the event DB for this task vs a clean
  pickup.

#### 5. Transient **double drop-off card** during the at-door window (cross-refs #297)
On the **Great Greek Mediterranean** delivery the dasher saw **two drop-off cards** while at the
dropoff; **after** completing it and getting the **paid (PostTask) card, only one remained.** No
crash. The dasher's read — "maybe it auto-corrected" — matches a desk hypothesis:

- **Likely cause (hypothesis):** the card stack is `completed` (folded from the event log) + one
  `active` live card. On an **arrival-bearing dropoff** (Great Greek is a hand-it-to-customer
  restaurant order, so `DELIVERY_ARRIVED` fires), `FlowCardMapper` **closes the delivery into
  `completed`** on `DELIVERY_ARRIVED` (`FlowCardMapper.kt:216-239`) — a frozen card with id
  `delivery:<taskId>`. Meanwhile the state machine is still in `TaskDropoffArrived`, so
  `LiveCardBuilder` **also builds an active Delivery card** for the same task
  (`LiveCardBuilder.kt:80-92`), same id `delivery:<taskId>`.
- **Why no crash (and why two cards show):** the active card is keyed `"live:${live.id}"` while the
  completed card is keyed `it.id` (`BubbleScreen.kt:238` vs `:256`). The `live:` prefix means the two
  keys **don't collide** — so #297's fatal duplicate-key crash is avoided (good — #297 holds) — but
  the frozen + active cards for the **same stop render as two visible cards** during the at-door
  window.
- **Why it resolves to one:** on payment, `DELIVERY_COMPLETED` adds the PostTask card and the flow
  moves to `Flow.PostTask`, so `LiveCardBuilder` now emits an **active PostTask** card (not a
  Delivery). The duplicate active Delivery card disappears, leaving the single frozen Delivery card
  — exactly the "only one now" the dasher saw.
- **To confirm:** pull the Jun-12 event DB for the Great Greek task — expect `DELIVERY_ARRIVED`
  **before** `DELIVERY_CONFIRMED`/`DELIVERY_COMPLETED` (arrival-bearing). If confirmed, this is a
  **cosmetic transient** (a frozen+live overlap), distinct from #297's crash. One direction to weigh
  would be suppressing the frozen `completed` card whose id matches the current `active` card's id, or
  not closing the Delivery into `completed` until `DELIVERY_CONFIRMED` — but that's a design call for
  the desk, **not** a concluded fix.

#### 8. Bubble clears instead of showing the last dash — after a crash AND after a normal dash-end (cross-refs #437, #367)
Distinct from #3 (which was a re-attach **while the dash was still active**). This item now has **two
triggers**, and a second sighting makes it look like a **real gap, not just transitional**:
- **8a — after a crash with no active dash (earlier this session):** the bubble **cleared out**
  (empty chat + empty card stack) instead of showing the most-recently-completed dash.
- **8b — after a normal dash END (2026-06-13 ~late):** ending a dash **again cleared the cards**. The
  dasher's read: "we might be in a state where it's just not displaying the last dash … might just be
  we're in transition." Desk finding below suggests it's the **same root cause as 8a**, not a benign
  transition.

- **Likely cause (hypothesis, same for both triggers):** the bubble's `displayedDashId` is a purely
  **in-memory latch** — `bubbleManager.activeDashId.scan(null) { last, current -> current ?: last }`
  (`BubbleViewModel.kt:95-96`). `activeDashId` is null whenever there's no live session
  (`AppState.activeSessionId()` returns a session id only while `session != null`,
  `AppState.kt:48-52`). The `scan` holds the last **non-null** active id — but it gets **reset to
  null** two ways: (8a) **process death** wipes the scan; (8b) the downstream `messages`/`cardStack`
  flows are `SharingStarted.WhileSubscribed(5000)` (`BubbleViewModel.kt:104,128`), so when the bubble
  is collapsed / has no subscribers for >5s and then **re-subscribes after the dash has ended**, the
  `scan` chain is torn down and **restarts from `null`** while `activeSessionId()` is already null.
  Either way `displayedDashId` → null → `messages` empty (`:98-104`) and `cardStack.completed` empty
  (events query returns empty for a null dash id, `:119-122`) — the bubble empties.
- **The code comment claims a fallback that isn't implemented:** `BubbleViewModel.kt:106-107` says the
  card stack uses "the current active dash when one is running, **otherwise the most-recently-completed
  one**" — but there's **no persisted** last-completed-dash-id feeding `displayedDashId`; the only
  "otherwise" is the in-memory `scan` latch. So the documented "review the previous dash until you go
  Online again" behavior holds **only** as long as the scan survives — and it does **not** survive a
  crash (8a) **nor** a post-dash-end bubble re-subscribe (8b). The 8b sighting **contradicts** the
  earlier "survives a normal idle transition" assumption.
- **To confirm / one direction to weigh (desk, not a fix):** for 8b, end a dash, collapse the bubble
  >5s, reopen → check whether cards/chat are gone and whether `activeDashId.value` is null. A durable
  fix sources `displayedDashId`'s fallback from a **persisted** "most-recently-completed dash id"
  (DB/datastore) instead of the in-memory `scan` — overlaps with #367 post-dash HUD persistence.

### Research / design (improvement ideas — explore, not yet scoped)

#### 6. Offer & finished cards under-surface Shop & Deliver (item count + type badge)
Two related gaps the dasher noticed about the #324 card redesign and the completed-card stack:

- **Item count is buried on the offer card.** Today the count renders only as a small footer
  caption — `FlowCardItem.kt:392-401` builds `"$store · $itemCount items"` via `Caption(...)`, and
  only when `itemCount > 1`. For a Shop & Deliver, the dasher wants the item count promoted to the
  **same visual tier as the $/hr hero and the mi / $/mi line** (the offer hero `Row` at
  `FlowCardItem.kt:298-340` is score-ring + `$/hr` hero + a secondary `Net · mi · $/mi` line). The
  data already exists on the snapshot (`FlowCardSnapshot.Offer.itemCount`,
  `FlowCardSnapshot.kt:57`) — **hypothesis:** this is a pure presentation change (elevate item count
  into the hero row), no parser/state work needed. Dasher noted there's **space on the right** of
  the hero row to place it.
- **No Shop & Deliver indicator / badge, and the finished card doesn't show the type at all.**
  The offer card has a badge pill row (`FlowCardItem.kt:379-390` + `badgeMeta` `:421-433`), but
  `badgeMeta` has **no Shop & Deliver / shopping entry**, and the **PostTask (finished) card carries
  no order-type or activity field whatsoever** — `FlowCardSnapshot.PostTask`
  (`FlowCardSnapshot.kt:124-135`) has `storeName`/`totalPay`/`parsedPay` but nothing that says "this
  was a shop." (The `SHOPPING` activity only lives on `FlowCardSnapshot.Pickup.activity`,
  `:94`.) **Hypothesis:** surfacing "Shop & Deliver" on the finished card would need an order-type/
  activity field threaded onto `PostTask` (and possibly `Offer`), i.e. it's **not** purely cosmetic
  — it touches the snapshot model + the mapper (`FlowCardMapper`/`LiveCardBuilder`), not just
  `FlowCardItem`. Would need to confirm where order type is known at PostTask time. A SHOP badge in
  `badgeMeta` for the offer card, by contrast, is cheap **if** a shop badge/flag reaches `Offer.badges`.

#### 7. Pickup card not visually upgraded to the redesign + double-"by" wording
The dasher reads the pickup card as **"still the old style"** next to the redesigned offer card —
it's the line-based `DeadlineBody` (`FlowCardItem.kt:484-582`): a `HeroBig` countdown + caption rows
(`HEB · arrived 16:39 · shop 25/25 · 0.6/min`), with none of the offer card's ring/banner/pill
visual language. Two sub-items:

- **Visual parity.** Pickup (and by extension Delivery, which shares `DeadlineBody`) didn't get a
  comparable redesign pass. **Hypothesis:** this is a deliberate-or-not gap from the #324 redesign
  (which targeted the offer card); worth deciding whether the pickup/delivery cards should adopt the
  same component vocabulary (gauge ring for deadline pressure, etc.) or stay deliberately minimal.
- **Double "by".** The deadline caption renders **`till pickup-by · by 17:10`** — `deadlineLabel =
  "till pickup-by"` (`FlowCardItem.kt:454`) concatenated with `Caption("$deadlineLabel · by
  ${formatTime(deadlineMillis)}")` (`:512`). The two "by"s read awkwardly; trivial wording fix
  (drop one "by", e.g. `"pickup by 17:10"` or `"till pickup · by 17:10"`).

---

## 2026-06-09 — DoorDash session (Stage 2 offer-copilot live test)

- **Platform tested:** DoorDash
- **Branch under test:** `master` after **#327** (Stage 2b: manual Accept/Decline + collapse) — the
  offer-copilot build. Also includes #324 offer-card redesign, #326 auto-expand, #325 screenshot
  settle, #321–#323 brand system / components / job economics.
- **Field conditions:** one **$28 / 12.9 mi H-E-B** offer (looks **stacked** — see #4) at ~12:00:44;
  the dasher tapped the bubble's **Decline**, nothing happened on DoorDash, so they **declined
  manually**. Findings are grounded in the saved `app.log` + `captures/` + event DB at
  `/home/betty/dashbuddy/logs/2026/06/09`. All below are **hypotheses to triage — not concluded fixes.**

### Bugs

#### 1. In-bubble Accept/Decline can't click DoorDash — `performClick` searches the wrong window
The whole chain fired correctly — bubble Decline tap → `UiInput("decline_offer")` → EffectMap
`PerformOfferAction(DECLINE)` → `SideEffectEngine` → `performClick` — and failed only at the click:
```
12:01:16.910 SideEffectEngine: Performing offer action: DECLINE on doordash
12:01:16.912 UiInteractionHandler: Attempting click (Bubble DECLINE)
12:01:17.081 WARN  Could not find any live node for: Bubble DECLINE
             (id=com.doordash.driverapp:id/secondary_action_button_dash_plus, text=null, bounds=(0,0,0,0))
```
The viewId is **correct** — `…:id/secondary_action_button_dash_plus` is exactly what's in the captured
offer tree (`captures/…/offer_popup/…7f6048.json`), so the id mapping isn't the problem.
- **Likely cause:** `UiInteractionHandler` clicks against `AccessibilitySource.getLiveNativeRoot()`,
  which returns `service.rootInActiveWindow` (`AccessibilitySource.kt:34-36`). When the dasher taps
  the **bubble**, the *active* window is the bubble overlay (DashBuddy), **not** DoorDash's offer
  window — so the viewId search runs against the wrong tree and finds nothing. (The dasher then
  declined manually: captures `initial_decline` @12:01:19 → `decline_offer` @12:01:20.)
- **One direction to confirm:** resolve clicks across **all** windows via
  `AccessibilitySource.getWindows()` (already used by `WindowsChangedPipeline`; the service already
  requests `flagRetrieveInteractiveWindows`) instead of only `rootInActiveWindow`. Affects **both**
  Accept and Decline (shared path), so manual actions are fully non-functional until this lands.
- **Status:** Open. (Regression in #327's click path — found on first field test.)

#### 2. Offer bubble does not auto-expand from the background
The evaluation **did** post — `OfferEvaluationEvent` @12:00:44.483 → Chat
`[Good Offer] Recommended: ACCEPT | Score 74 | Net $22.48` @12:00:45.234 (~750ms later, matching
`OFFER_BUBBLE_EXPAND_DELAY_MS`, so the Stage-2a delay itself behaved) — but the bubble stayed collapsed.
- **Likely cause:** `setAutoExpandBubble(true)` has no effect when the posting app isn't in the
  foreground, and DashBuddy is backgrounded while DoorDash is foreground (the documented Android
  restriction flagged before the build). The heads-up notification posts (`IMPORTANCE_HIGH`);
  auto-expand is ignored.
- **Implication:** can't rely on background auto-expand. Options to weigh: (a) heads-up notification +
  "tap to review"; (b) a full-screen-intent surface; (c) keep the bubble but open it on tap. This
  **reshapes Stage 2/3** — the auto-action countdown was meant to anchor on "bubble shown"; if the
  bubble only opens on a tap, the countdown must anchor on the tap (or not auto-fire without an open
  surface). Worth deciding the surface before building 2c/3.
- **Status:** Open.

#### 3. Bubble did not collapse after tapping an action
Tapping Decline did not collapse the bubble to its head.
- **Likely cause (hypothesis):** the collapse bridge does `(context as? android.app.Activity)?.finish()`
  in `BubbleScreen`, but Compose's `LocalContext.current` is usually a `ContextThemeWrapper`, not the
  Activity — so the cast is null and `finish()` never runs. Standard shape: unwrap via
  `ContextWrapper.baseContext` (`findActivity()`).
- Note: collapse is dispatched on the **tap** (independent of whether #1's click succeeds), so it's a
  separate defect from #1 — also unconfirmed is collapse-vs-dismiss behaviour, which we can only test
  once `finish()` actually fires.
- **Status:** Open.

### Open questions / investigations

#### 4. Offer "recognized early" and re-evaluated with diverging results (stacked-offer parse?)
The one offer flip-flopped across three evaluations as the screen settled:
- 12:00:45 `[Good Offer] ACCEPT Score 74 Net $22.48` (TTS "**12.9 miles**")
- 12:00:53 `[Bad Offer] DECLINE Score 6 Net **-$9.62**` (TTS "**22.5 miles**")
- 12:01:01 `[Good Offer] ACCEPT Score 74` (TTS "12.9 miles")

Distance flips **12.9 mi ↔ 22.5 mi**, and there's an `UNKNOWN` window carrying `accept_button` at
12:00:44.029 → classified `offer_popup` at 12:00:44.344 (~300ms later).
- **Hypothesis:** a **stacked** offer (multiple orders) whose screen re-parses inconsistently —
  sometimes a single leg (12.9 mi → good), sometimes the total (22.5 mi → the −$9.62 mis-eval) — as it
  renders. Recognition + evaluation fire **per frame** and are **not** settle-gated like the screenshot
  now is, so a transient/partial frame yields a spurious DECLINE-6. Likely the "recognized it early"
  the dasher noticed.
- **To dig:** diff the two captured `offer_popup` JSONs (`…7f6048.json` @44.374 vs `…225fd4.json`
  @52.489) for the one-leg-vs-total parse divergence; consider debouncing/settling offer eval or
  de-duping re-evals of the same `offerHash`.
- **Root cause CONFIRMED (desk, 2026-06-09 — diffed the two captures):** **self-recognition, not a
  stacked-offer parse and not the loading-bar reject.** Frame `225fd4` @52.489 (the "DECLINE-6"
  frame) is **our own Bubble HUD overlay** — its tree is `…android:id/content →
  androidx.compose.ui.platform.ComposeView` with our text (`"Recommended: ACCEPT | Score: 74 | Net:
  $22.48"`, `"H-E-B · 40 items"`, `"AWESOME OFFER"`), yet it was tagged `platform: doordash`.
  `ContentChanged`/`StateChanged` snapshot `rootInActiveWindow` but labeled it with the **event's**
  package; while our bubble was the active window over DoorDash, our overlay got mislabeled DoorDash
  and matched `offer_popup` (whose `require` was just "Decline" + "Accept" — satisfied by the bubble's
  new #327 Accept/Decline buttons), then parsed to junk ($0.00 / 0.0 mi) → phantom re-eval. (No
  `progress_bar` in our overlay, so the removed loading-bar reject was a red herring.)
- **Fixed (PR pending):** (1) attribute active-window snapshots to the window's **real** package and
  drop non-target windows — our overlay is skipped (`🚫 Skip active window: non-target pkg=…`); (2)
  `offer_popup.require` now also demands the `accept_button` / `accept_decline_footer_container` id,
  which our `content`-only overlay lacks. Dropped the settle/dedupe idea — it would have masked this.
- **Status:** Fixed — needs field re-validation (see checklist).

---

## 2026-06-07 — DoorDash session (desk review of captured data)

- **Platform tested:** DoorDash
- **Branch under test:** `master` at `6649f4f` (post-#307 merge — includes #302
  shopping-itemcount-dedup, #297 duplicate-card crash fix, #286/#290 grace
  refactor).
- **Field conditions:** developer dashed a **full day** (4 dashes: 08:29–11:24,
  11:24–13:51, 15:32–16:49, 19:02–20:12; 7 completed deliveries, $211.53 total
  across the four summaries). This entry is a **desk review** of the Jun 7
  captures + event DB + app logs, not live narration. Six log rotations =
  long session. No crashes all day (`FATAL`/`AndroidRuntime`/recovery markers:
  zero). All findings below are **hypotheses from captured data**, framed for
  the developer to triage — not concluded fixes.

### Bugs

#### 1. Duplicate `DELIVERY_CONFIRMED` / `DELIVERY_ARRIVED` recurred — and it is NOT crash-recovery (refines #300)

- **Data observation (authoritative, from the event DB):** 3 of the 7
  deliveries fired `DELIVERY_CONFIRMED` more than once:
  - job `6f3a4a45` — **3×** confirms (11:14:11, 11:21:56, 11:24:11) **and 2×**
    `DELIVERY_ARRIVED` (11:21:55, 11:21:56)
  - job `879f03b7` — **2×** confirms (13:38:44, 13:50:47)
  - job `365eb1dc` — **2×** confirms (20:03:43, 20:12:25)
  The other 4 deliveries confirmed **exactly once**. `DELIVERY_COMPLETED` fired
  **exactly once per job** (clean) — so completion is fine; the intermediate
  `CONFIRMED`/`ARRIVED` lifecycle events are what duplicate.
- **This rules out the original #300 hypothesis.** #300 was filed as
  "crash-recovery re-emits events on replay." But this session had **zero
  crashes and zero recovery markers** in the logs, and the duplicates are
  **minutes apart** (e.g. 11:14 → 11:21 → 11:24), not the near-instant
  back-to-back a replay would produce. So whatever causes this is happening
  during **normal operation**, not recovery.
- **New, strong correlation (the lead):** the 3 duplicating jobs are **exactly**
  the 3 whose `DELIVERY_NAV_STARTED` payload carried an anomalous **`arrivedAt`
  timestamp**; the 4 clean jobs carried `addressHash` and **no** `arrivedAt`.
  3/3 vs 0/4 — a perfect split. A `DELIVERY_NAV_STARTED` event that already
  knows an arrival time is itself odd (nav-started shouldn't have arrived yet),
  and the first spurious `CONFIRMED` fires only **2–6 s after**
  `DELIVERY_NAV_STARTED` — i.e. **before** the real arrival (which is minutes
  later).
- **Hypothesis (unverified):** these deliveries entered the delivery/dropoff
  region via a state-construction path that **already carried prior arrival
  data** (the `arrivedAt` in the nav-started payload is the tell), and that path
  re-fires the confirm effect on subsequent dropoff-screen window events.
  Would need to confirm by tracing, for one of the three jobs, which observation
  built the `DELIVERY_NAV_STARTED`-with-`arrivedAt` state and what re-triggers
  the confirm effect on the repeat frames. The honest read is that **#300's
  title/root-cause should be rewritten** from "recovery re-emit" to "dropoff
  lifecycle event re-fires on repeated window events (correlates with
  nav-started carrying arrivedAt)."
- **No user-visible crash:** the #297 FlowCard dedup held — duplicate delivery
  cards collapsed by `id`, no `LazyColumn` duplicate-key crash. So this is an
  **event-log-integrity** defect (and a potential double-count risk for anything
  that sums lifecycle events), not a visible bubble break this session.
- **ROOT CAUSE FOUND (deeper capture dive — high confidence).** Cross-referencing
  the duplicate-confirm timestamps against the window captures: **all 3 duplicating
  deliveries used the `dropoff_handoff` ("hand it to customer") screen; all 4 clean
  ones used `dropoff_photo`.** And the `dropoff_handoff` rule
  (`doordash.json:2436`) classifies **`flow: task:dropoff:arrived`** on nothing more
  than the drop-off workflow fragment + the **instruction text** "hand it to
  customer" — which DoorDash *also shows as a preview before you've arrived*. The
  premature `dropoff:arrived` (→ premature `DELIVERY_ARRIVED`/`CONFIRMED`) fires on
  that preview, then fires **again** at the real arrival.
  - **The reliable discriminator is the completion CTA "Mark as delivered."**
    Diffing the false vs real handoff capture for each of the 3 jobs:
    - `6f3a4a45`: 11:14:09 (no CTA, premature) → 11:21:55 (**"Mark as delivered"**)
    - `879f03b7`: 13:38:40 (no CTA) → 13:46:32 (**"Mark as delivered"**)
    - `365eb1dc`: 20:03:37 (no CTA) → 20:10:07 (**"Mark as delivered"**)
    3/3 clean: the early/false handoff lacks `Mark as delivered`; the real arrival
    has it. (`dropoff_photo` doesn't duplicate because the "photo of drop-off"
    screen is only shown at arrival, never as a nav preview.)
  - **Why this matters beyond noise (re: "is it even a bug?"):** the duplicate
    *events* are largely harmless today (display deduped by #297, `COMPLETED`
    clean). The real defect is the **premature/false arrival**: `arrivedAt` gets
    stamped ~7 min early (at the preview), which corrupts every arrival-anchored
    metric — dwell-time-at-customer and the "ahead/late" deadline delta from
    2026-06-06 #2 both read off `arrivedAt`. So this is a low-severity *correctness*
    issue, not purely cosmetic.
  - **This is the same root as the "better arrival indicator" question.** A
    hypothesis worth confirming: gate the handoff (and pin) `dropoff:arrived`
    classification on the **completion CTA** ("Mark as delivered" / "Complete
    Delivery" / `complete_delivery_steps_button`) rather than the instruction text,
    so arrival only fires inside the geofence. That would kill both the premature
    confirm and the duplicate at the source. (`dropoff_geofence_warning`, "far away
    from the customer", is the existing *negative* signal — the CTA is the positive
    one.)
- **Two distinct duplicate-event causes now exist, don't conflate them:**
  (a) #300's crash-recovery replay (real but crash-only, rare post-#297 — keep
  #300 as written); (b) this **non-crash handoff-preview false-arrival** (common on
  every hand-it-to-customer delivery). (b) is better tracked as an arrival-signal
  fix than as a dedup.
- **Status:** Open. Root cause identified (handoff instruction-preview vs
  `Mark as delivered` CTA); #300's recovery framing is for cause (a) only — cause
  (b) is separate and the more frequent one.
- **Full month-wide analysis (per delivery type):** see
  [`docs/capture-analysis/2026-06-dropoff-arrival-signals.md`](../capture-analysis/2026-06-dropoff-arrival-signals.md)
  — 5-day fan-out over all 23 June drop-offs. Confirms hand-to-customer is **6/6**
  premature+duplicated while leave-at-door is **14/14** clean; gives the reliable
  arrival signal per type (photo screen / "Mark as delivered" CTA / PIN screen /
  ID-scan screen), the structural fixes (CTA-gate handoff, per-leg idempotent
  confirm, monotonic arrival latch), and the recognition gaps (cant-reach-customer,
  cash-on-delivery, signature, staff-handoff).
- **Unified nav-exit arrival model (50 tasks, pickup + dropoff):** see
  [`docs/capture-analysis/2026-06-task-arrival-navexit-model.md`](../capture-analysis/2026-06-task-arrival-navexit-model.md)
  — tests the conjecture "arrived = exit of an active nav session." Verdict:
  **viable + unifies pickup/dropoff** and dodges the handoff premature bug 5/5;
  "Arriving at \<addr\>" is real but sparse (enrichment); the literal Exit button
  is unused (0 taps). Recommends layering nav-exit (primary) + the CTA-gate
  (discriminator/fallback), starting with a shadow `navExitGated` instrument
  (no behavior change) + 2 field confirmations before flipping.

#### 2. In-app "Transfer in / balance" screen captured as UNKNOWN, not blocked as SENSITIVE (privacy gap)

- **Data observation:** a DoorDash in-app DasherDirect **"Transfer in"** screen
  showing **"$310.08 available"** + transfer amounts ($10/$25/$50) + "Continue"
  was captured as `classificationName: UNKNOWN` (file
  `2026-06-08_07-26-29-578…window__UNKNOWN`, captured the next morning but in the
  Jun 7 rotated folder). It landed in the capture corpus **unredacted** rather
  than being short-circuited to SENSITIVE.
- **Hypothesis (unverified):** `SensitiveScreenMatcher` runs first and is
  supposed to block banking/balance/transfer screens, but this **transfer-screen
  variant isn't matched**, so it falls through to UNKNOWN and gets captured. A
  balance figure reaching disk is exactly what the edge-PII / sensitive-blocking
  pledge is meant to prevent. Cross-refs the standing "Cashout / transfer screens
  blocked (#275)" checklist item — this is **evidence that item is not fully
  satisfied** for the DasherDirect transfer screen.
- **Second instance, same gap (reinforces it):** a weekly-earnings screen —
  "Earnings · This week · **$575.23** · Paid to your DoorDash Crimson account ·
  Payout details" — was also captured (16:49:54 click capture, `UNKNOWN`) when the
  dash ended. So at least two distinct earnings/banking surfaces (DasherDirect
  transfer + weekly-earnings/Crimson) are slipping past the sensitive block into
  the corpus, both with dollar figures.
- **Status:** Open — would need to confirm which sensitive predicates fire (or
  don't) on these screens' node text.

### Verification TODOs (checklist outcomes this session)

#### 3. Grace-STOP bug (06-06 #5) — did NOT recur, but no scheduled-start path occurred

- **Data observation:** all **4** `DASH_STOP` events this session carried
  `source: "summary_screen"` (authoritative dash-summary end). **Zero**
  `early_offline` stops; no mid-task `SESSION_END` / `pendingDestructive`
  firings in the logs. So the mid-dash premature-end did **not** happen Jun 7.
- **But the discriminating case still didn't occur.** All **4** `DASH_START`
  events were `source: "interaction"` from `WaitingForOffer` — **no
  scheduled-dash start** (`idle_scheduled_dash_ready`) path ran this session. The
  06-06 grace-STOP was traced to an `idle_scheduled_dash_ready` offline-flip; we
  still have **no second data point** on whether any *other* idle-family screen
  can trigger it. **The held fix stays held** (direction A vs C still
  undecided), and the watch-for-recurrence checklist item stays.

#### 4. Grace-RESUME worked mid-dash (2nd confirmation of #286/#290 resume sub-case)

- **Data observation:** at **16:30:59** the log shows `EffectMap: Session grace
  resume: 9072f690…` → `Chat: Session resumed (grace)`, for the 15:32→16:49 dash.
  An app-switch / brief-offline blip mid-dash correctly **resumed the same
  session** (no fresh start, no new sessionId). This is the **second** clean
  sighting of the resume sub-case (first was 06-03).

#### 5. End-and-fresh-start worked on the on-demand path (#286/#290)

- **Data observation:** at **11:24:22** `DASH_STOP` (summary_screen) was followed
  **8 s later** at 11:24:30 by a `DASH_START` with a **new** sessionId
  (`c1894851…`, `source: interaction`, `startScreen: WaitingForOffer`) — a true
  fresh dash, **not** a grace resume. Confirms the "new dash right after ending
  one starts fresh" **on-demand** sub-case. (The **scheduled** start sub-case
  remains unconfirmed — see #3.)

### Field UX context / corpus

#### 6. Unassign / "Unassign with no pay" flow captured live — still UNKNOWN (good #301 corpus)

- The 16:36–16:37 UNKNOWN window cluster (~12 frames) is the pickup-issue →
  unassign flow: "Select an issue" / "Order has long wait time" / "Red Card
  issues" / "Resolution options" / **"Unassign with no pay"** / **"Your
  Completion Rate will drop to 99%"** / "Continue with the current order" /
  "Unassign order" / "Contact support". This is exactly the flow **#301** is
  about, and it's **still UNKNOWN** (unrecognized) — so these frames are clean
  corpus for building the unassign matchers when #301 is picked up.

#### 7. UNKNOWN window volume + two recognizable one-offs

- ~180 UNKNOWN window frames captured this session — expected (UNKNOWN screens
  don't dedup the way recognized ones do; mostly transient/loading frames).
  Two recognizable one-offs worth noting if a matcher is ever wanted: a
  **"Continue dashing"** post-summary prompt (around the 11:24 stop/restart), and
  a screen reading **"Dasher detected this screenshot."** — **open question:** is
  DoorDash surfacing detection of DashBuddy's own screenshot side-effect? Worth a
  closer look at that capture before assuming anything.

---

## 2026-06-06 — DoorDash session (live capture during dash)

- **Platform tested:** DoorDash
- **Branch under test:** `master` at `504dd63` (post-#304 merge — includes the
  #302 shopping-itemcount-dedup fix and #297 duplicate-card crash fix).
- **Field conditions:** developer dashing on DoorDash; entry captured live.
  Started while **paused** but received an order anyway. Shop & Deliver item
  counts observed working. No alcohol order this dash (so #149 remains
  unconfirmed). Observations centered on the pickup/drop-off task card's
  deadline display.

### Bugs

#### 1. Got an offer while **paused** ("I was paused, but I got an order anyway")

- **Field observation:** developer had the dash **paused**, yet an order/offer
  still came through. Unclear yet whether DoorDash itself delivered the offer
  during a pause (platform behavior) or whether DashBuddy mis-read the paused
  state and surfaced/handled the offer as if active.
- **Status:** Open — needs capture to disambiguate platform-vs-DashBuddy.
- **Light desk read (hypothesis, unverified):** two possibilities, and the
  captures should separate them: **(a)** DoorDash genuinely sent an offer during
  a pause (some pause flows still float offers) — in which case the question is
  whether DashBuddy was in `DashPaused`/`PausedOrInterrupted` and correctly
  transitioned to `OfferPresented`, or **(b)** DashBuddy never actually entered
  the paused state (the pause screen wasn't recognized / the region stayed
  `AwaitingOffer`), so from its view nothing unusual happened. To tell them
  apart at the desk: pull the state-region transitions around the offer — was the
  region in a paused state when `OFFER_PRESENTED` fired? If the bubble *showed
  paused* but still took the offer, that's a state-consistency concern; if it
  never showed paused, the pause-screen recognition is the gap. Cross-refs the
  `DashPaused` / `DashPausedMatcher` path.

#### 2. Pickup/drop-off deadline display: redundant "by" while navigating, and the wall-clock disappears *after arrival* ("+24:18 ahead" / "1:34 late" can't be verified)

- **Developer modification (2026-06-06, refining the original report):** two
  distinct sub-issues, scoped by phase:
  - **(a) Navigating (pre-arrival) — redundant "by".** While en route to a pickup
    the card caption reads **"till pickup-by · by H:MM"** — it says **"by"
    twice**. It should read the deadline label **and the time once**, e.g.
    "till pickup-by H:MM" (drop the second "by"). The wall-clock **is** present in
    this phase (good) — it's just doubled-up wording.
  - **(b) After arrival — the wall-clock disappears.** The actual bug the
    developer is reporting is for **after arriving at a pickup or drop-off**: once
    arrived, the wall-clock deadline time **vanishes** from the card, so the
    "+24:18 ahead" / "1:34 late" delta can't be cross-checked against the time
    DoorDash showed. The developer notes this **will likely go away once the
    timers are separated** (Research/design #3) but wanted it on record as the
    current defect.
- **Field observation (original):** the pickup showed **"+24:18 ahead"** (doubted
  accurate), the drop-off **"1:34 late"** (plausible but unverifiable with no time
  shown). Core complaint: after arrival the wall-clock anchor is gone.
- **Update (2026-06-06, later in the same session) — (b) could not reproduce; the
  wall-clock IS showing.** On a subsequent pickup the developer observed the
  "pick up by H:MM" time **present** on the card after all ("it's working now…
  maybe I didn't see it earlier"). So the after-arrival missing-anchor is
  **intermittent or was a misread** — not a confirmed repro. The developer is
  **deliberately not conjecturing** and will let the Android-Studio Claude agent
  inspect the captures at home to settle whether (b) ever actually dropped the
  anchor. Treat (b) as **unconfirmed / pending capture review**; sub-issue (a)
  (the redundant "by") is a separate, still-valid wording nit.
- **Status:** Open. Sub-issue (a) (redundant "by") is **desk-confirmable**;
  sub-issue (b) (wall-clock gone after arrival) is now **unconfirmed — could not
  reproduce**, deferred to capture review. The "+24:18 is wrong" magnitude needs
  captured data.
- **Desk read — (a) redundant "by" (high confidence):** the active caption is
  `Caption("$deadlineLabel · by ${formatTime(deadlineMillis)}")`
  (`FlowCardItem.kt:365`) and `deadlineLabel` is **"till pickup-by"** (`:307`) /
  **"till deliver-by"** (`:328`). So the rendered string is literally
  "till pickup-by · by H:MM" — the label already ends in "-by" and the caption
  adds another "by". Fix is a one-liner: drop the "by " in the caption (→
  "$deadlineLabel · H:MM") or reword the label. The Delivery side has the same
  shape.
- **Desk read — (b) wall-clock gone after arrival (high confidence on the two
  post-arrival states, exact null-path needs data):** after arrival the card is in
  one of two states that **lack** the `by H:MM` caption the navigating card has:
  - **Live "at stop":** the active branch only shows the countdown + wall-clock
    *when `deadlineMillis != null`* (`FlowCardItem.kt:358-365`); the **else** path
    (`:366-369`) renders elapsed time + `Caption("at stop")` with **no
    wall-clock**. So if `deadlineMillis` goes null once arrived (e.g. the "Pick up
    by H:MM" text leaves the screen at the store and the field isn't carried
    forward), the card drops into "at stop" and the anchor is gone. *(Worth
    confirming against data — `PlatformRegionStepper.kt:461` uses `?:` to preserve
    a prior deadline, so whether it actually goes null after arrival needs a
    capture.)*
  - **Frozen card:** the "ahead/late" delta branch renders `Caption("vs
    $deadlineLabel")` (`FlowCardItem.kt:388`) — also **no `by H:MM`**. This is the
    "+24:18 ahead" / "1:34 late" with no time to verify against.
  The #271 wall-clock work only covered the **navigating** active branch; both
  **post-arrival** states (live "at stop" and frozen) never got the anchor. The
  two-timer redesign (#3) would resolve this by making the wall-clock the heading
  and adding the count-up dwell — matching the developer's note that separating
  the timers should make this go away.
- **Desk read — "+24:18 looks wrong" (hypothesis, needs data):** the frozen delta
  is `arrivalRemaining = deadlineMillis - arrivedAt` (`FlowCardItem.kt:379-380`),
  formatted `m:ss` via `formatCountdown` (`:598-603`). "+24:18 ahead" = arrived
  24m18s before the parsed deadline. **Not** the old ~1434-min day-rollover ghost
  (#267) — magnitude is small — so it's either roughly correct or
  `deadlineMillis`/`arrivedAt` is slightly off (deadline parsed from the wrong
  field, or `arrivedAt` stamped at the wrong sub-state). Confirm/refute: pull this
  pickup's `deadlineMillis` + `arrivedAt` vs the "Pick up by H:MM" text DoorDash
  rendered; same for the drop-off's "1:34 late".


#### 5. App-switch mid-pickup → returned to DoorDash → DashBuddy said "done dashing" while the screen still showed pickup (premature dash-end beyond grace)

- **Field observation (~12:01 PM Central, Sat 2026-06-06):** developer was
  **in the middle of a pickup**, switched to a **different app for a little
  while**, then switched back to DoorDash. On return, DashBuddy **acted like the
  dash had ended** — the bubble said (paraphrased) "done dashing" — even though
  the DoorDash screen **still showed the pickup**. Confusing and clearly wrong:
  the dash was still active. Developer will have the Android Studio agent pull the
  logs later to confirm the exact sequence.
- **Status:** Open — **mechanism corrected after developer pushback (see below)**;
  pending log confirmation of which screen carried the offline signal.
- **⚠️ Correction (developer challenge, desk-verified):** the developer pointed
  out — correctly — that **no offline screen ever showed**, so "why would the
  offline grace even arm?" The earlier draft of this item guessed
  "app backgrounded → region reads Offline," and **that guess was wrong**:
  `TransitionPolicy.resolveMode` (`TransitionPolicy.kt:34-51`) **never infers
  Offline from absence of events** — `Idle` resolves to `null` (ambiguous), and a
  region only goes Offline from **(1)** an observation carrying an explicit
  `modeHint: offline`, **(2)** a `Flow.SessionEnded` (the dash summary), or
  **(3)** the `SESSION_PAUSED_SAFETY` timeout *and only while already `Paused`*
  (`PlatformRegionStepper.kt:228-235`). The developer wasn't paused, and no
  summary showed — so an **active offline-tagged screen observation** must have
  flipped it. Absence alone cannot.
- **Sharpened hypothesis — a transient `idle_map` observation on return flipped
  the region Offline (now favored):** the DoorDash **`idle_map`** rule carries
  **`modeHint: offline`** (`doordash.json:2149-2153`, priority 140) — as do
  `idle_scheduled_dash_ready` (`:2124-2128`) and `set_dash_end_time`
  (`:2079-2083`). When you switch **back** to DoorDash mid-pickup, the app
  commonly renders its **home/map screen for a beat before restoring the
  active-delivery overlay**. If DashBuddy observes that momentary `idle_map`
  frame, it emits `modeHint: offline` → the region flips Online→Offline →
  `PlatformRegionStepper.kt:159-169` arms the provisional `SESSION_END` (10s
  grace) → the next observation past the deadline hits lazy-expiry (`:63-67`) →
  `DASH_STOP(EARLY_OFFLINE)` (`EffectMap.kt:280-296`) = "done dashing." The
  developer never consciously "saw an offline screen" because the idle map flashed
  for a frame under the restoring pickup UI. (A non-DoorDash app's screens
  classify with `platformWire = null` and would not match a DoorDash offline rule,
  so the *other* app is unlikely to be the trigger — it's the **DoorDash idle map
  on the way back** that fits.)
- **This is the same root as 2026-05-29 #2 — idle-family screens carry
  offline/idle signals that are valid *while awaiting* but destructive *mid-task*.**
  There, `navigation_generic` emitting `flow: idle` retired the active **task**;
  here, `idle_map` emitting `modeHint: offline` ends the whole **dash**. Same
  broken premise: an idle/home screen seen *during an active task* is treated as
  "the dasher is offline/idle," when it's just a transient view.
- **Developer's design principle (record verbatim intent):** *"we should never
  assume we went offline"* from mere absence or a transient screen. Offline should
  require either **an explicit, authoritative offline/end screen** (the dash
  summary / a real "you're offline" state) **or** a **very long** unobserved
  gap — the developer floated **~30–35 minutes** — before DashBuddy concludes the
  dash ended. A momentary idle map on app-return is neither.
- **Directions surfaced (sketches only, defer to desk):** **(A)** don't let
  `idle_map` (and the other idle-family rules) emit an offline/idle mode signal
  **while a task is active** — gate the offline mode-flip on there being no
  in-progress task, mirroring the 2026-05-29 #2 direction. **(B)** make dash-end
  on a non-authoritative offline require either an authoritative end screen or a
  much longer grace than 10s (the developer's 30–35 min) — a bare 10s grace-expiry
  should fall back to "still dashing," not "ended," especially with a live task.
  **(C)** guard dash-end while `activeTask != null` — never finalize a dash with a
  task mid-flight absent an authoritative signal. A is the most direct fix for the
  observed trigger; C is the robust backstop.
- **What would confirm or refute this at the desk (for the AS agent + logs):**
  pull the observations around 12:01 PM on the **return** to DoorDash. The
  decisive line is **which screen/ruleId carried `modeHint: offline`** right before
  the Online→Offline flip — expect `doordash.screen.idle_map` (or another
  idle-family rule). Then the chain: `pendingDestructive(SESSION_END)` armed with a
  ~10s deadline → lazy-expiry `commitDestructive` once an obs lands past it →
  `DASH_STOP(source = EARLY_OFFLINE)`. Confirm `activeTask` (the pickup) was
  non-null throughout — if so, an active task was discarded by a 10s timeout
  triggered by a transient idle frame, which is the bug.
- **✅ CONFIRMED from the 06-06 data (2026-06-07 desk investigation) — culprit is
  `idle_scheduled_dash_ready`, NOT `idle_map`.** Exact sequence from
  `logs/2026/06/06/db/dashbuddy-v2.db` + `app_log_rotated_20260606_130507.log`:
  - 11:50:13 `OFFER_ACCEPTED` → 11:50:15 `pickup_navigation` (Online, **active pickup task**).
  - **11:50:23 `SCREEN: idle_scheduled_dash_ready`** (`flow=Idle, modeHint=Offline`,
    captured `…__idle_scheduled_dash_ready__96f95d.json`) — a transient
    "Start your scheduled dash" frame **8 s into an active pickup** (the dasher had a
    *next* scheduled dash queued). This flips the region Online→Offline and arms
    `pendingDestructive(SESSION_END)` (deadline ≈ 11:50:33).
  - Dasher app-switches → **8.9-min observation gap** (no DoorDash events).
  - **12:00:56** return → first obs `pickup_navigation/Online` lands far past the
    deadline → lazy-expiry commits **`DASH_STOP(early_offline)`** (seq 18) and, being
    Online with no session, a fresh **`DASH_START(interaction)`** (seq 19) — same second.
  - Bubble: `[Dispatch] Done Dashing!` → `[Navigator] Pickup: H-E-B` →
    `Resetting Session Odometer` → `[Dispatch] Started Dashing!`.
  - **Impact:** not a "resume" — the dash **ended and re-started a new session,
    wiping the session odometer (miles/earnings)** mid-pickup. The pickup itself
    survived into the new session (`PICKUP_ARRIVED` 12:09, `CONFIRMED` 12:53), so the
    delivery completed but the session stats reset.
  - **So the real culprit is the #290 rule I added:** `idle_scheduled_dash_ready`
    carries `modeHint: offline` (correct when *about to start* a dash, wrong when it
    flashes while a delivery is already active and a *next* dash is scheduled).
    `idle_map` / `set_dash_end_time` share the same hazard. Confirms the
    "idle-family screen seen mid-task is destructive" premise — and that a 10s
    `SESSION_END` grace can discard an **active task** with no authoritative end.
- **Proposed direction (validated):** Direction **C** — never commit a
  `SESSION_END` (DASH_STOP) while `activeTask != null` without an authoritative end
  (summary). Robust backstop covering every idle-family offline flip mid-task, and
  matches the developer's "never assume offline from a transient screen" principle.
  Direction A (gate idle-family offline mode-flip while a task is active) is the
  narrower companion. Tracked as a follow-up; not yet fixed.

#### 6. Stacked offer item count parsed as 2 instead of 14 (parseItemCount also matches "order", so it grabs the order count on a multi-order line)

- **Field observation:** received a **stacked/double offer to Target** — two
  orders, both at the same Target store. DashBuddy interpreted the **number of
  items as 2**, but it was really **14 items**. Suspected offer item-count parse
  bug on stacked offers.
- **Status:** Open — **strong desk hypothesis**; needs the captured offer screen
  to confirm the exact `display_name_secondary` text.
- **Desk read (high confidence on the mechanism):** the offer popup parses a per
  `orders` list (`doordash.json:374-428`); each order's **`itemCount`** is read
  from the `display_name_secondary` node and run through the **`parseItemCount`**
  transform (`:422-428`). That transform's regex is
  `\((\d+)\s*(?:item|order|unit)` (`TransformRegistry.kt:280-283`) — it captures
  the first integer that is **immediately followed by `item`, `order`, *or*
  `unit`** inside parens. On a **stacked** offer the secondary line almost
  certainly reads something like **"(2 orders • 14 items)"** (or "(2 orders, 14
  items)"), so the regex matches **"(2 order…" → 2** and never reaches "14
  items." The `order` alternative in the regex is the culprit: it's meant to
  handle "(N units/items)" but on a multi-order string it greedily grabs the
  **order count** instead of the **item count**. (Note `2` = the number of
  stacked orders, which lines up exactly with "both offers at Target.")
- **Why "2" specifically (the tell):** 2 = the stacked-order count, not a random
  misread. That's what makes the "regex matched the `(2 orders` token" reading
  fit so cleanly.
- **Open question on per-order vs total:** the parse is **per order** (inside the
  `orders.each`), so each Target order should get its own `itemCount`. Whether the
  HUD then shows the first order's count, sums them, or shows the stacked total is
  a second thing to check — but the **2** strongly implies the regex is reading
  "2 orders" off a combined secondary line regardless. Need the capture to see
  whether the secondary text is per-order or a combined "2 orders • 14 items".
- **Direction (sketch only, defer to desk):** make `parseItemCount` match
  **`item`/`unit` only** (drop `order` from the alternation), or prefer the
  `item`-tagged number when both `order` and `item` counts are present on the same
  string (e.g. match the *last* `(\d+)\s*items?` rather than the first
  number-before-keyword). Confirm against a captured stacked-offer
  `display_name_secondary` first.
- **What would confirm or refute this at the desk:** pull the captured
  `offer_popup` snapshot for the Target stack and read the literal
  `display_name_secondary` text(s). If it contains "2 orders" before "14 items",
  the regex hypothesis is confirmed. Also a regression candidate: add a snapshot
  test with a stacked-offer secondary line asserting `itemCount == 14`.

#### 7. Completed dash split into a new dash ID after a grace-resume — second half not correlated to the first (possibly pause-related)

- **Field observation:** after **completing** the dash, it "resumed from grace"
  but **created a new dash ID**, so the latter portion was **not correlated to the
  earlier half of the same dash** — the dash got split into two sessions. The
  developer suspects it **might be pause-related**: they **tried to pause the dash
  and got an offer anyway** (cross-refs Bug #1 this session, "paused but got an
  order"), so the pause/resume cycle was in a weird state.
- **Status:** Open — needs the logs to reconstruct the session sequence; several
  threads converge here.
- **Developer clarification (important — corroborates Route A):** the new dash was
  **"started on the pickup"** — i.e. the fresh dash ID was minted **while on the
  pickup screen**, not from a normal dash-start flow. This fits the Route A
  sequence precisely: a transient `idle_map` (`modeHint: offline`) nulls the
  session **mid-pickup**, then the **very next pickup-screen observation** — a
  `TaskPickup*` flow, which `resolveMode` maps to `Mode.Online`
  (`TransitionPolicy.kt:40-45`) — finds `region.session == null`
  (`PlatformRegionStepper.kt:149-157`), mints a new session, and `EffectMap.kt`
  fires `DASH_START` **right there on the pickup**. Tell-tale: the emitted
  `DASH_START` payload hardcodes `startScreen = "WaitingForOffer"`
  (`EffectMap.kt:311`) even though the dasher was actually on a pickup — so a
  `DASH_START` logged with `startScreen = WaitingForOffer` whose surrounding
  observations are pickup screens is the fingerprint of this mid-pickup re-mint.
- **Developer clarification #2 (the key inconsistency — "resumed but didn't really
  resume"):** behaviorally it **looked like a grace resume / continuation** — *not*
  a whole new dash starting from scratch — yet the data ended up with a **new dash
  ID**. So the "resume" **didn't actually resume the old session**; it presented as
  picking the same dash back up while really **severing it into a new session**.
  This is a genuine contradiction in the code, because the two outcomes live on
  **mutually exclusive** branches of the same Offline→Online transition in
  `EffectMap.kt`: the **grace-resume bubble** ("Session resumed (grace)") only
  fires when `prevSession?.sessionId == nextSession.sessionId` (`:316-319`),
  whereas a **new `DASH_START`** only fires when the ids **differ** (`:305-315`).
  You cannot get *both* "resumed (grace)" *and* a new id from a single transition
  — so one of these is true:
  - **(i)** the grace-resume message fired at **one** Online blip (genuine
    same-session resume), and the session was nulled + re-minted at a **separate**
    moment in the same dash (a different idle frame) — two events the dasher
    experienced as one "it resumed but with a new id"; or
  - **(ii)** there's an ordering/state bug where the session is nulled
    (grace/`EndSession`) but the bubble still shows the stale "resumed (grace)"
    text from a prior transition while a fresh id is minted underneath — i.e. the
    **message and the actual session state disagree**.
  The developer's phrasing ("it didn't really resume the old session", "not like a
  whole new dash starting", "didn't create a new one at the end of that drop off")
  points at exactly this **mismatch between what the bubble said and what the
  session store did** — the resume was cosmetic, the continuity was lost.
- **What this sharpens for the logs:** beyond "which signal nulled the session,"
  also check the **ordering** — did "Session resumed (grace)" (`EffectMap.kt:319`)
  and the new-id `DASH_START` (`:313`) come from the **same** Offline→Online
  transition (which would be the (ii) bug) or **different** ones (the (i)
  two-event story)? And confirm whether the dash split at a **clean boundary**
  (end of dropoff) or **mid-flow** — the developer's read is that it did **not**
  cleanly split at the end of the dropoff, which argues against a normal end-of-
  dash boundary and for a mid-flow re-mint.
- **Desk read (hypotheses, need log confirmation):**
  - **A — same root as Bug #5.** A transient `idle_map`/idle-family frame
    (`modeHint: offline`, `doordash.json:2149-2153`) mid-dash flips the region
    Offline → grace → expiry → `EndSession` nulls the session. The next Online
    observation finds `region.session == null` and **mints a fresh session**
    (`PlatformRegionStepper.kt:149-157`), and `EffectMap.kt:305-315` emits a
    `DASH_START` (new id) because `prevSession?.sessionId != nextSession.sessionId`.
    That is exactly "a new dash ID not correlated to the first half." The "resumed
    from grace" the developer recalls may be from a *different* blip in the same
    dash (the genuine same-session grace branch, `EffectMap.kt:316-319`), with the
    **split** happening at a separate idle-frame moment — so both messages can
    appear in one dash.
  - **B — pause interaction (the developer's hunch).** Pausing puts the region in
    `Mode.Paused`. If the `SESSION_PAUSED_SAFETY` timeout fires while still
    `Paused`, `handleTimeout` forces `Mode.Offline` *with grace*
    (`PlatformRegionStepper.kt:228-235`) → same end-then-new-session split. And if
    pausing while an offer arrives left the pause/resume state inconsistent (Bug
    #1), the timer or mode bookkeeping could be off — e.g. a pause-safety timeout
    still pending when the offer pulled the region back online, firing later and
    ending the session mid-dash.
  - These aren't exclusive — both routes end with **session nulled → new id on
    next online**. The decisive question is *which signal* nulled the session.
- **What would confirm or refute this at the desk (for the AS agent + logs):**
  pull the full session/region timeline for this dash. Look for: (1) the
  **two `DASH_START` ids** and whether a `DASH_STOP(EARLY_OFFLINE)` sits between
  them; (2) what triggered the Offline that split it — a `modeHint: offline`
  screen (Bug #5 route) vs a `SESSION_PAUSED_SAFETY` timeout (pause route); (3)
  whether a pause (`Mode.Paused`) and the offer-during-pause (Bug #1) preceded the
  split. If a `DASH_STOP(EARLY_OFFLINE)` split the dash, this is the
  session-continuity face of Bug #5; if a pause-safety timeout did it, it's a
  distinct pause-state defect. Either way the fix family is the same as #5: don't
  end a dash (and don't mint a new id) without an authoritative end signal,
  especially mid-task/mid-pause.

### Research / design

#### 3. Two-timer task card: countdown-to-deadline while navigating, count-up dwell after arrival, wall-clock as the heading

- **Developer's framing (now complete — clarified this report):** the task
  section should have **two timers**, and this is **task-independent** (same shape
  for pickup and delivery):
  - **Left side = the navigation countdown.** While heading to the stop it counts
    **down** toward the pickup/drop-off-by deadline (and keeps going negative if
    you blow it) — i.e. "time until I should be there."
  - **The heading of that timer is the wall-clock time** — "Pick up by H:MM" /
    "Drop off by H:MM" — the actual time the delivery app says to be there.
  - **On arrival the navigation timer stops/freezes** at whatever it reached
    (positive = ahead, negative = late).
  - **Right side = a count-UP dwell timer.** Once you arrive, the **second** timer
    counts **up** until you finish the pickup/delivery — "how long I've been at
    this stop." The card **slides left** on arrival to reveal it.
- **Status:** Open (research/design — captures the developer's preferred card
  shape; not a defect to patch). Supersedes the partial capture in the earlier
  draft of this item — the key clarification is **countdown while navigating →
  freeze on arrival (left), count-up dwell until finish (right)**.
- **Desk read (how this maps onto today's code, hypothesis):** the data already
  exists on `DeadlineBody` — `deadlineMillis` (the wall-clock heading + the
  countdown target), `arrivedAt` (freezes the nav timer **and** starts the dwell
  count-up), and `phaseEndedAt`/`confirmedAt` (stops the dwell). Today the
  **active** branch shows a single countdown hero (`FlowCardItem.kt:359-365`) and
  the **frozen** branch shows the arrival-vs-deadline delta (`:382-388`); the
  tertiary already prints "arrived H:MM · picked up H:MM" (`:404-409`). The
  proposal asks to (i) promote wall-clock from caption to **heading**, (ii) freeze
  the nav timer at `deadlineMillis - arrivedAt` **on arrival** (not just on
  phase-end), and (iii) add a **live count-up dwell** = `now - arrivedAt` (ticking
  via the `rememberNow()` 1-Hz helper) revealed by a slide once arrived. Stays
  within the reactive-UI rules (anchor on state, derive in the composable). Also
  resolves Bug #2 (the missing wall-clock anchor). Defer to desk review for the
  layout/animation.

### Verification TODOs

#### 4. Shop & Deliver item counts working (#302 partial confirmation)

- **Field observation:** "the item counts are working." Read as a positive on the
  #302 shop-dedupe fix (item counts no longer freezing one short). The developer
  didn't explicitly call out the terminal `total/total` frame or the add-on case
  this report, so logging as a partial confirmation pending an explicit
  end-of-shop `total/total` sighting.
- **Status:** Partial confirmation logged against the #302 checklist item
  (Confirmed 1/2). Needs one more clean dash confirming the final count reaches
  `total/total`.

### Research / design

#### 8. Stacked-offer evaluation: make the flat "Min Payout" metric stack-aware (sub-linear multiplier on order count)

- **Developer's question (not a bug — desk-think request):** wonders whether
  stacked offers are evaluated too leniently. Reasoning: if the single-order bar is
  ~$7, a double-stacked order arguably shouldn't pass unless it's meaningfully more
  — not necessarily a strict 2× ($14), but maybe **~1.5–1.75× the single bar per
  added order**, counted by **deliveries / pickups (max of pickups vs drop-offs)**.
  Asked for a viability read, explicitly *not* a fix to apply.
- **Status:** Open (research/design — note for the AS agent / desk).
- **Desk read (how it maps onto the current evaluator):** `OfferEvaluator.evaluate`
  computes all metrics on the **combined stack totals** — `grossPay`, `dist`,
  `items` are the offer aggregate (`OfferEvaluator.kt:11-13`), and it scores each
  enabled `MetricRule` against the user's targets.
  - **The ratio metrics already handle stacking correctly.** `DOLLAR_PER_MILE`
    (`netPay/dist`) and `ACTIVE_HOURLY` (`netPay/estTimeHours`) are scale-invariant
    (`:27-28`, `:199-200`): a $14 double over 2× distance has the *same* $/mi and
    $/hr as a $7 single over 1× — so stacks are evaluated fairly by these with no
    per-order adjustment. These are the real "True Net Profitability" north star.
  - **The flat `PAYOUT` ("Min Payout") metric is the stack-blind one.** It scores
    `netPay / target` (`:198`), so a $10 double clears a $7 floor (10/7 → capped at
    1.0) despite being ~$5/order — the leniency the developer noticed. The fix
    belongs **here**, not in the ratio metrics.
- **Recommendation (hypothesis, defer to desk):** keep the ratio metrics as-is;
  make **only `PAYOUT` stack-aware** by scaling the target sub-linearly with the
  order count. **Refined model (developer follow-up): derive the multiplier from
  the order count via a power law — `effectiveTarget = target × n^p`** — where
  `n = offer.orders.size` and `p` is a single "stacking efficiency" exponent in
  `[0,1]`. This is preferred over the earlier fixed-`k` linear form
  `target × (1 + k·(n−1))` because the developer wants it to (i) **derive from the
  order count with one knob** and (ii) **diminish per added order** — a big
  DashLink-style batch (the developer has seen 3; others get many more) shares more
  overhead, so the marginal order should demand *less*, which a power law does and
  a linear form does not.
  - `p = 1` → strict linear (each order demands a full single bar: 2×, 3×, …,
    n×); `p = 0` → no scaling (any stack clears the single bar); the developer's
    "≈1.5× at a double" pins **`p ≈ 0.585`** (`2^0.585 ≈ 1.5`); "≈1.75×" → `p ≈
    0.81`. `f(1) = 1` falls out for free.
  - At `p ≈ 0.585` against a $7 single bar: n=2 → 1.50× ($10.50, $5.25/order);
    n=3 → 1.93× ($13.50, $4.50/order); n=5 → 2.65× ($18.55, $3.71/order); n=10 →
    3.84× ($26.90, $2.69/order). The **per-order floor decays** as the batch grows
    — the batch-efficiency intuition, built in.
  - Count by **deliveries = `offer.orders.size`** (`OfferEvaluator.kt:30` /
    `ParsedOffer.orders`); the developer's `max(pickups, drop-offs)` proxy
    converges to the same value for typical stacks. Effort/pickups need not enter
    the payout floor — distance/time already price effort into the ratio metrics,
    so adding it here would double-count.
  - **Big-batch cautions (developer raised DashLink / many-order batches):**
    (1) keep **`ACTIVE_HOURLY` as the hard backstop** — power-law payout alone
    could wave through a large batch that's actually a time sink; `$/hr` already
    evaluates stacks correctly and should be allowed to veto. (2) Consider a
    **per-order floor** (e.g. don't let the implied per-order bar decay below ~$2)
    so an enormous `n` can't shrink the threshold to nothing. Expose `p` (and the
    optional floor) as the tunables; default `p ≈ 0.585–0.65`.
- **Hard dependency — Bug #6.** A per-order payout rule needs `offer.orders`
  populated reliably and the per-order item/count parse correct. Bug #6 (the
  `parseItemCount` regex grabbing "2 orders" off a stacked secondary line) is
  prerequisite work; confirm stacked offers parse their `orders` list correctly
  before building stack-aware scoring on top.
- **What would help decide at the desk:** pull a few captured stacked-offer
  evaluations from the DB and look at how `PAYOUT` scored vs how `$/hr`/`$/mi`
  scored — confirm the payout floor is the loose one in practice, then tune k
  against the developer's accept/decline history.

---

## 2026-06-05 — DoorDash session (Shop & Deliver, items/min off-by-one)

- **Platform tested:** DoorDash
- **Branch under test:** `master` (post-#297 merge era). Data archived to
  `logs/2026/06/04/` and `logs/2026/06/05/` (`captures/`, `app.log` + rotations,
  `db/dashbuddy-v2.db`).
- **Field conditions:** multiple Shop & Deliver orders. Developer observation:
  on Shop & Deliver, the **items/min count finished one short** — at the end of
  shopping the HUD showed one less than the full item count even though shopping
  was actually done.

### Bugs

#### 1. Shop & Deliver items/min (and `shop X/total`) caps at `total − 1` — terminal `To shop (0)` frame deduped away

- **Validated against the data — on 06-04/06-05 every shop order ends at `remaining = 1`, never `0`:**
  | Session | Order size | Last *processed* `pickup_shopping` frame |
  |---|---|---|
  | 06-04 ~20:18 | 20 items | `shopped=19 / remaining=1` |
  | 06-05 ~18:32 | 32 items | `shopped=31 / remaining=1` |
  | 06-05 ~20:05 | 15 items | `shopped=14 / remaining=1` |

  (from `db/dashbuddy-v2.db` `observations`, `ruleId = doordash.screen.pickup_shopping`.)
- **But 06-03 DID record the terminal frame** (`remaining = 0`): order #1 hit
  `shopped=21 / remaining=0` at 17:28:39, order #2 hit `shopped=46 / remaining=0`
  at 21:35:26. So this is **intermittent, not an inherent gap** — the `To shop (0)`
  frame *can* be and *was* observed; on 06-04/05 it was dropped. (On 06-05 the log
  even shows `pickup_shopping` frames classified *after* the last recorded one at
  20:05:19 that never reached the `observations` table — i.e. **dropped**, not absent.)
- **Root cause — the post-classification dedup discards count-only changes.**
  `AccessibilityPipeline` suppresses an observation when its identity equals the
  previously-emitted one (`AccessibilityPipeline.kt:134`, `identity == lastIdentity`).
  Identity = `ObservationIdentity("screen", target, parsed.dedupeHash(), modeHint)`
  (`ObservationIdentity.kt:29`), and **`TaskFields.dedupeHash()` excludes
  `itemsRemaining` / `itemsShopped`** (`ParsedFields.kt` — it hashes only
  phase / subFlow / storeName / arrivalConfirmed). So *every* `pickup_shopping`
  frame shares one identity regardless of progress; a frame that differs only by
  item count (including the decisive `To shop (0)` / `Done(total)`) is treated as a
  duplicate and dropped. It's intermittent because an interleaving different-identity
  screen (`shopping_item`) sometimes breaks the dedup chain right before the (0)
  frame (06-03) and sometimes doesn't (06-04/05).
- **Why the metric shows the symptom (code, `FlowCardItem.kt:420-428`):** the
  Shop & Deliver tertiary line renders `shop $shopped/$total` and `%.1f/min` where
  `shopped = itemsShopped`, `total = shopped + itemsRemaining`,
  `perMin = shopped / elapsedMin`. The per-frame parse is correct; because the
  `→ total` frame is deduped, `itemsShopped` freezes at `total − 1` and the pace is
  computed on `total − 1`.
- **Direction (hypothesis):** include the shopping item counts in
  `TaskFields.dedupeHash()` so each shopping-progress state is a distinct identity
  and the dedup never collapses count changes — which makes the terminal frame
  (and every intermediate count) record reliably. Lower-risk than a "finalize on
  completion" heuristic, and fixes the live card too. To re-confirm on a future
  dash: watch that the shop card reaches `total/total` at the end.

---

## 2026-06-03 — DoorDash session (live capture during dash)

- **Platform tested:** DoorDash
- **Branch under test:** `master` at `776b0a8` (post-#272 merge; latest code on
  `master` is the #271 card-polish + #270 nav-generic-idle merges) — inferred,
  developer to correct if the build came from elsewhere.
- **Field conditions:** developer dashing on DoorDash; entry captured live.
  Includes a Shop & Deliver leg (pacing observed), an app-switch grace-resume,
  and a **reproducible crash in the post-delivery phase on both deliveries**
  (around the auto-expand click). Multiple short back-to-back dashes.

### Bugs

#### 1. App crashes in the post-delivery phase, at/around the automated "expand delivery details" click (REPRODUCED — both deliveries this dash)

- **Field observation:** Crashed in the post-delivery phase on **both** deliveries
  this session. First: right after a dropoff, "as soon as the app clicked, or just
  after the click" — the **automated** click that expands the post-delivery pay
  breakdown. Second: started a new dash immediately after the first delivery and it
  crashed again in the same post-delivery phase. Dasher's read: "there is something
  going on in the post-delivery phase," and it's "probably something to do with"
  the **recent state-machine / hooks-and-triggers changes.** No stack trace yet —
  dasher plans to pull captures + logcat.
- **Status:** Open — **reproducible (2/2 deliveries this dash).** The repeat on
  both deliveries rules out the earlier "one-off stale-node race" framing: a
  consistent crash points at a **code path** in the post-delivery phase, not
  timing luck. **Blocked on the crash stack trace** to pin the layer.
- **Recent-change surface (the dasher's "hooks and triggers" hunch, corroborated
  at the desk).** The post-delivery phase has the most recent churn anywhere in
  the state machine, all in the build under test: `4575441` (post-task
  best-effort + dup-skip + UDF click delay + safety screenshots, #266) reworked
  the exact expand/announce flow; `d584060` (DELIVERY_CONFIRMED closes drop-off
  task on transition away) changed how the task is retired here; `5f44413`
  (transition override system) is the trigger plumbing — the post-delivery
  transition fires `triggerOverrideEffects(obs, TASK_COMPLETED)`
  (`EffectMap.kt:206`).
- **Sharpened hypothesis — the new SETTLE_UI deferred-click path (now favored).**
  The #266 work no longer clicks inline; the `delayMs: 500` expand click
  (`doordash.json:670`) is routed through a brand-new round-trip:
  `diffRuleEffects` (`EffectMap.kt:653-662`) sees CLICK with `delayMs > 0` and
  emits `ScheduleTimeout(SETTLE_UI)` carrying a **serialized** click context
  (`serializeClickContext`, `:695-714`); when it fires, `diffSettleUiTimeout`
  (`:673-693`) **reconstructs** a `NodeRef` (`deserializeNodeRef`, `:716-732`)
  and re-dispatches the click against possibly-changed live UI. That serialize →
  defer ~500ms → reconstruct → re-dispatch chain is new surface sitting exactly
  in the crashing phase, and the ~500ms delay matches "just after." It then
  re-enters `UiInteractionHandler.performClick` (see hypothesis (a) below — the
  unguarded `findNodeByBounds` recursion / raw node ops).
- **What the desk pass ruled OUT:** the `effect.delayMs!!` at `EffectMap.kt:659`
  is **guarded** by `(effect.delayMs ?: 0L) > 0L` at `:653` (not the NPE);
  `serializeClickContext` / `deserializeNodeRef` use null-safe casts throughout;
  `parsedFieldsToMap`'s reflection (`:753-758`) is `try`-wrapped. No obvious throw
  site among them — consistent with needing the trace.
- **What fires at that exact moment (the post-task collapsed screen):**
  `doordash.json:659-678` runs **two** effects when the collapsed `post_task`
  screen matches: (1) `click: $expandButton` (gated `isExpanded == false`,
  `dedupeKey: expand_pay_breakdown`, `throttleMs: 1000`, **`delayMs: 500`**),
  then (2) a `screenshot` (`prefix: "Delivery - {totalPay}"`, `throttleMs:
  60000`). So "at/just after the click" overlaps the click dispatch, the
  platform's expand animation, **and** the screenshot capture — three places a
  crash could originate.
- **Hypotheses (desk read, not verified against a trace — all speculative):**
  - **(a) The auto-click dispatch itself.** `SideEffectEngine.kt:113-116` →
    `UiInteractionHandler.performClick` (`UiInteractionHandler.kt:19-64`) →
    `AccNodeUtils.clickNode`. `performClick` re-resolves the target against the
    *live* root via `findAccessibilityNodeInfosByViewId` / `…ByText` / a bounds
    walk (`findNodeByBounds`, `:70-88`). The empty/null paths are guarded
    (returns with a `Timber.w`), but the recursive `findNodeByBounds` and the
    raw `AccessibilityNodeInfo` operations aren't wrapped in try/catch — a stale
    / recycled node mid-expand could throw `IllegalStateException`. Plausible but
    not obviously the most likely.
  - **(b) The screenshot effect that fires right after.** `ScreenShotHandler.kt`
    uses `service.takeScreenshot` → `Bitmap.wrapHardwareBuffer` →
    MediaStore write, then `result.hardwareBuffer.close()` in `onSuccess`
    (`:39-45`). The body is `try/catch(Exception)` wrapped and `saveToGallery`
    catches its own exceptions, so an *app-killing* crash here seems less likely
    — but the `hardwareBuffer.close()` sits *outside* `saveToGallery`'s guard, so
    if `saveToGallery` throws unexpectedly the buffer may leak rather than crash.
    Lower suspicion, but worth ruling out via the trace.
  - **(c) Processing the *expanded* screen the click produced (favored on
    timing).** "Just after the click" is also exactly when the breakdown expands
    and DoorDash emits a burst of accessibility events for the new content, which
    our pipeline then parses (the expanded `post_task` parse that yields
    `parsedPay` / `payLineItems`, c.f. `ParsedFieldsFactory` per 2026-05-19 #4).
    A null/format assumption in that expanded-pay parse would crash *as a result
    of* the click rather than *in* it — which matches the dasher's "just after"
    wording better than the click dispatch itself.
- **Relationship to prior work:** the post-task auto-expand pipeline was last
  touched in **#266** (2026-05-19 bug #4 — first-click race / re-fire). Note the
  rule now carries `delayMs: 500` (an initial delay before the first click),
  which is the #266 timing fix. This crash is a **new** symptom (a hard crash,
  not the previous "click didn't complete" / "bubble re-fired"), so it's either
  a regression introduced alongside that flow or a latent path #266 didn't touch.
- **What would confirm or refute this at the desk:**
  - **Pull the crash stack trace** (logcat / the on-device crash log). The top
    frame immediately disambiguates: `EffectMap.diffSettleUiTimeout` /
    `deserializeNodeRef` or `UiInteractionHandler` / `AccNodeUtils` → the new
    deferred-click path; a parse class → the expanded-screen path (c);
    `ScreenShotHandler` → (b).
  - Look for a `SETTLE_UI` timeout firing right before the crash — its presence
    ties the crash to the deferred-click round-trip. And test whether the crash
    still repros on a delivery where the expand never auto-fires (throttle/dedupe
    suppressed) — if it doesn't, the deferred-click path is the culprit.
  - If a snapshot of the expanded breakdown was captured this dash, run it
    through the parse path that builds `parsedPay`/`payLineItems` and check for
    a null/format assumption that the live expanded screen would violate.
  - Cross-check the screenshot output: a `Pictures/DashBuddy/… Delivery - …png`
    file existing for that delivery means the screenshot effect ran to
    completion (pushes suspicion toward (a)/(c), away from (b)).
- **RESOLVED — root cause found 2026-06-04 from the stack trace (≠ the
  hypotheses above).** The trace is a Compose layout crash, not an effect/parse
  crash: `java.lang.IllegalArgumentException: Key "delivery:<uuid>" was already
  used … provide a unique key for each item`, thrown from
  `LazyListMeasure → SubcomposeLayout` (the bubble card stack), **not** from
  `EffectMap` / `UiInteractionHandler` / a parse class / `ScreenShotHandler`. So
  the SETTLE_UI deferred-click and expanded-parse hypotheses (a)/(b)/(c) were
  red herrings — the auto-expand click only *triggers a recomposition* that
  re-measures the `LazyColumn`, and the completed-card list already contained a
  **duplicate `delivery:<taskId>` card**. `FlowCardMapper.fold` added a delivery
  card on *both* `DELIVERY_ARRIVED` and `DELIVERY_CONFIRMED`, assuming they were
  mutually exclusive; arrival-bearing dropoffs (photo / PIN / hand-it-to-customer,
  and the alcohol ID-scan from #149) fire both. Confirmed in this session's
  `db/dashbuddy-v2.db` (`app_events`): taskId `c0041f37` ARRIVED 17:59:23 →
  CONFIRMED 17:59:33 → crash 17:59:34; taskId `4d62f8ea` ARRIVED 21:56:44 →
  CONFIRMED 22:00:37 (×2) → crash 22:00:37. The `offer:` (05-25) and `posttask:`
  (05-22) crash variants are the same family. **Tracked as #297; fixed** (dedup
  the card list by id + a `distinctBy` backstop at the `LazyColumn` + regression
  tests). Field-validate via the #297 checklist item.

### Verification TODOs

#### 2. Shop & Deliver live pace ticked during the shop (#276 partial confirmation)

- **Field observation:** On a Shop & Deliver leg, the bubble pickup/shop card
  showed the live items/min pace and it **ticked** while shopping — the core
  #276 behavior. The dasher did **not** see the finalization (the frozen card
  after the leg) this dash, so the `total == "Done (x)" + "To shop (y)"`
  cross-check and the add-on-mid-shop case remain unconfirmed.
- **Status:** Partial confirmation logged against the #276 checklist item
  (Confirmed 1/2 — live ticking only). Needs a second dash to confirm
  finalization + add-on before the checklist item is retired.

### Open questions / investigations

#### 3. Switched apps mid-dash, came back to DoorDash, bubble showed "Session resumed (grace)"

- **Field observation:** Started another dash, switched to a different app, and
  on returning to DoorDash the **DashBuddy bubble** showed a message the dasher
  recalled as "recovered (grace)." Dasher wasn't sure why it fired.
- **Status:** Open — but **source now pinned** (see below). The likely-correct
  read is that this is the grace mechanism *working*; the open part is whether an
  app-switch *should* trip it and whether surfacing the message is desirable.
- **Source pinned (desk grep, high confidence):** the bubble string is literally
  **`"Session resumed (grace)"`** — `EffectMap.kt:319`,
  `add(AppEffect.UpdateBubble("Session resumed (grace)"))`. (This **corrects** the
  earlier hypothesis in this entry's first draft that the notice was DoorDash's
  own UI — it is a DashBuddy bubble message. The earlier grep missed it because it
  lives in `:core:state`, not `:app`.)
- **When it fires (`EffectMap.kt:299-320`):** on an **Offline → Online**
  transition where the resumed region's `session.sessionId` **equals** the prior
  session's id (`:316`). That branch is reached only when the session was held
  alive under the **grace window** (`DEFAULT_GRACE_MS = 10_000L`) rather than
  finalized — i.e. DashBuddy briefly saw the region go Offline, then back Online
  within ~10s, and resumed the **same** dash (no new `DASH_START`, no odometer
  restart — `:317` comment: "same session, no start effects needed").
- **What most likely happened (hypothesis):** while DoorDash was backgrounded
  during the app-switch, DashBuddy stopped seeing DoorDash's online/idle screen
  and the region read as **Offline**; returning within the grace window flipped it
  back **Online** with the same session → the grace-resume branch fired and posted
  the bubble. By construction (`:316` checks `prevSession?.sessionId ==
  nextSession.sessionId`) this means it **resumed the same dash**, which is the
  *desired* outcome for a brief mid-dash blip.
- **This is a (partial) positive for #286/#290.** That checklist item's
  regression-watch is exactly "a brief offline blip mid-dash still **resumes the
  same** dash (no spurious new session)." Seeing "Session resumed (grace)" — and
  *not* a fresh-session reset — on an app-switch return is one clean sighting of
  that path holding. Logged as a partial confirmation there.
- **The genuinely open parts (not defects yet — UX / scope questions):**
  - **(a) Should a mere app-switch register as Offline at all?** If DashBuddy is
    just backgrounded (its service alive, simply not receiving DoorDash events),
    treating "I stopped seeing DoorDash" as "the region went Offline" is the same
    class of concern as 2026-05-29 bug #2 ("looking at another screen mustn't
    mutate active-task state"). Here it recovered cleanly via grace, but it's
    worth confirming the *task* (not just the session) also survived intact.
  - **(b) Is surfacing "Session resumed (grace)" to the user desirable?** It reads
    as internal-mechanism jargon (the dasher didn't know what it meant). Even at
    alpha-single-user, it may be noise — candidate to demote to a debug log, or
    reword to something a dasher parses ("Picked your dash back up"). Dasher's
    call; logging as a UX observation, not prescribing.
- **What would confirm or refute this at the desk:**
  - Pull DashBuddy logcat around the app-return: expect a `"Session grace resume:
    <id>"` line (`EffectMap.kt:318`) and an Offline→Online region transition
    within 10s, with the **same** `sessionId` before and after. That confirms the
    grace path (vs a fresh start, which would log `DASH_START` with
    `source = "interaction"`/`"recovery"` at `:310-313`).
  - Confirm the **active task** survived the blip (not just the session): check
    `activeTask` was non-null across the transition and `pendingDestructive`
    (the retire-grace) was cancelled on return, per `TaskLifecycleGuardTest`'s
    "returning to a task cancels the grace" expectation.

---

## 2026-05-29 — DoorDash session (live capture during dash)

- **Platform tested:** DoorDash
- **Branch under test:** `master` at `af54b87` (post-#253 merge — `feature/145-personal-economy-v2`); inferred from the latest merge on `master`, developer to correct if the build came from elsewhere.
- **Field conditions:** developer dashing on DoorDash; entry captured live. Mixed observations — a bubble-HUD card-copy nit and a state-loss bug hit mid-pickup at HEB (~18:56 Central).

### Bugs

#### 2. Navigating to the DoorDash home screen mid-pickup loses the active task ("forgot I was at HEB")

- **Field observation:** At an HEB pickup (~18:56 Central), the dasher tapped over to the DoorDash home screen to check something, then hit "return to dash" to come back. On return, the bubble had **forgotten the HEB pickup** — the active task/where-they-were-delivering was gone. Dasher's framing: "I thought we fixed this bug where, when I open the timeline, it forgot where I was delivering to. Opening the timeline shouldn't affect anything — it should stay in whatever state it's already in." The expectation is that merely *looking* at another screen (timeline, home) is read-only and must not mutate the active-task state.
- **Status:** Open.
- **Hypothesis (from a desk read, not verified against field logs):**
  - Two pieces interact. **(a)** the recognition rule, and **(b)** the stepper's "leaving a task flow" branch.
  - **(a)** `doordash.json`'s `navigation_generic` rule (priority 95) was given `state: { flow: idle, modeHint: online }` in commit `c01b791` ("fix: navigation_generic sets flow:idle/modeHint:online", Bug #5 of 2026-05-19). That change rested on the stated domain assumption that the generic-nav screen "only appears in two scenarios — navigating to a zone, or in-zone navigating to a hotspot. Both mean the dasher is awaiting an offer." The HEB observation looks like a **counterexample**: a home/nav screen reached *while a pickup task is active* also matches (or is close enough to match) that rule, so it now emits `flow: idle` mid-task instead of leaving the in-task flow sticky.
  - **(b)** Once `flow: idle` arrives while the prior flow was a task flow, `PlatformRegionStepper.updateTaskLifecycle` hits the unconditional branch at `PlatformRegionStepper.kt:489-499`: `if (prevFlowVal.isTaskFlow() && !nextFlowVal.isTaskFlow() && nextFlowVal != Flow.PostTask)` → it stamps `completedAt`, moves the task to `recentTasks`, and sets `activeTask = null`. That is the "forgot I was at HEB" — the HEB pickup is treated as *completed* and retired the instant an idle screen is seen, with no PostTask in between. Job survives (only PostTask→non-task completes the job, `:386-388`), but the active task is gone.
  - So the c01b791 fix that returns flow→idle to clear *sticky offer/task chrome while awaiting* doubles as a task-killer when the same idle signal fires *during* a real, in-progress task.
- **On the "timeline" framing:** the bubble timeline is a DashBuddy overlay and shouldn't itself generate DoorDash accessibility events, so opening it should be inert. The likely confound is that by the time the dasher opens the timeline (or returns from home), the *underlying* DoorDash screen has already been re-observed as a generic-nav/home screen → `flow: idle` → task cleared per (b). If a prior fix addressed the timeline-overlay path specifically, it wouldn't have covered this underlying-screen path. Worth confirming whether "I thought we fixed this" points at a distinct earlier fix (search history for the timeline/active-task interaction) so we don't re-fix the wrong layer.
- **What would confirm or refute this at the desk:**
  - For the HEB pickup window (~18:56 Central), pull the flow/observation events. Expected: a `TaskPickup*` flow, then a `navigation_generic`/home observation emitting `flow: idle` (`modeHint: online`), then `activeTask` going null on that transition via `PlatformRegionStepper.kt:490`.
  - Confirm which DoorDash screen the home/"return to dash" view actually matched — `navigation_generic` vs `IdleMap`/`dash_along_the_way`/`waiting_for_offer`. If it's `navigation_generic`, (a) is confirmed and c01b791's assumption is the hole. If it matched a different idle-emitting rule, the trigger is that rule instead but (b) is the same downstream killer either way.
- **Possible directions (sketches only, defer to desk review):**
  - *A — make the stepper's task-clear conditional on an authoritative end signal* rather than any idle observation. `PlatformRegionStepper.kt:489-499` currently treats *any* non-task, non-PostTask flow as "task over." A momentary home/nav glance is not task completion. Could require a stronger signal (PostTask seen, session ended, or an explicit grace window like the session-grace pattern at `:144-149`) before retiring an active task — i.e. don't let a single idle frame retire a pickup.
  - *B — narrow the recognition side.* Revisit whether `navigation_generic` should emit `flow: idle` unconditionally given it can now be reached mid-task; the c01b791 assumption ("only when awaiting an offer") appears to be the broken premise.
  - *C — combination.* B alone is fragile (any other idle-emitting screen reachable mid-task would still trip the killer), so A is the more robust layer; B reduces how often A is exercised. Desk-side call on whether to harden one or both.

### Field UX context

#### 1. Completed Awaiting card body caption still reads "before next offer", which doesn't parse on a closed card

- **Field observation:** When the Awaiting card is **completed/frozen**, the collapsed header reads "Await · Waited 6:24" (paraphrased — "await" label + relative wait duration). Opening the card shows the same 6:24 as the hero, but the caption underneath says **"6:24 before next offer"**, which reads wrong for a card that has already closed. The dasher likes the minutes-and-seconds staying as the hero, but wants the caption on the *completed* card to read something like **"waited before offer"** (or similar past-tense framing) rather than "before next offer".
- **Status:** Open.
- **Hypothesis (from a desk read, not verified against field logs):**
  - The frozen-card body lives in `AwaitingBody` at `FlowCardItem.kt:217-232`. The hero is `formatDuration(elapsed)` in both the active and frozen branches (`:226`), so the 6:24 carries over correctly. The caption is the only thing that differs: `:227-230` renders `"since last offer"` when `isActive`, else `"before next offer"`. That else-branch is the string the dasher is reacting to.
  - Note the header already gets this right: `awaitingSummary` at `FlowCardItem.kt:160-165` switches to past tense for the frozen card — `"Waiting · …"` when active, `"Waited …"` when frozen (`:164`). So the body caption ("before next offer") and the header summary ("Waited …") disagree in tense/framing on the same completed card.
- **What would confirm the read:** open any completed Awaiting card in the HUD and confirm the expanded caption is literally "before next offer" while the header says "Waited …". Purely a renderer-side string; no data dependency.
- **Possible direction (sketch only, defer to desk review):** change the frozen branch of the `Caption` at `FlowCardItem.kt:228-229` to a past-tense phrase — e.g. "waited before offer" / "waited for offer" / "wait before next offer" — to match the past-tense header summary at `:164`. The active branch ("since last offer") seems fine as-is. Exact wording is the dasher's call.

### Research / design

#### 3. End-of-dash: attribute a late-arriving Dash-summary to the just-ended dash via the existing grace window

- **Dasher's framing (verbatim intent):** at the end of a dash, DoorDash can show either the **Dash summary** first or the **normal idle map** first, then the other right after. Idea: let the two "wait for each other," or at least — if the idle map shows first — give a short grace (≈10s) during which an arriving summary is still attributed to the last dash. "We already have, like, a ten-second window in case the app crashed or I backed out by accident and it restarts — maybe during that same window, if a summary comes up, it could be attributed to the last dash." Asked for a viability read, not a fix.
- **Status:** Open (research/design — viability question, not a defect to patch).
- **Desk read of how the two orderings resolve today (hypothesis, not verified against field logs):**
  - **Summary-first (the clean path):** `doordash.json` `dash_summary` (priority 150) emits `flow: session:ended, modeHint: offline` (`doordash.json:2131-2160`). That's the *authoritative* end: `PlatformRegionStepper.kt:141-143` calls `endSession` immediately (no grace), and `EffectMap.kt:280-300` sees `parsed is SessionEndedFields` → emits `DASH_STOP` with `source = "summary_screen"` plus the rich fields (totalEarnings, duration, offers, weekly). Good outcome — dash is recorded with summary data.
  - **Idle-map-first (the lossy path):** the post-dash idle/home screen is a *non-authoritative* offline. `PlatformRegionStepper.kt:144-149` preserves the session under a grace deadline (`obs.timestamp + gracePeriodMs`, `DEFAULT_GRACE_MS = 10_000L` at `TransitionPolicy.kt:23`). **But** `EffectMap.kt:271-311` *eagerly* emits `DASH_STOP` with `source = "early_offline"` and `totalEarnings = prevSession.runningEarnings` (an estimate) — plus an `EndSession` effect — at that same offline transition, before any summary has had a chance to land. So the *entity* is held in grace, while the *event* is already written as the inferior `early_offline` variant.
  - This asymmetry is the crux of the dasher's instinct: the **grace window already exists and already preserves the session entity**; what doesn't yet exist is grace on the *DASH_STOP event* — the stop is committed eagerly rather than deferred/upgradeable.
- **Viability read (what I think — still a hypothesis, defer to desk review):**
  - The **asymmetric** version of the idea ("idle-map-first → grace → a summary within ~10s upgrades the attribution") is a **good fit** with existing infra and the most tractable. It reuses the exact mechanism (`sessionGraceDeadline`) and the exact constant the dasher remembered. Conceptually: don't finalize the stop as `early_offline` until grace expires, and if a `SessionEndedFields` summary arrives first, finalize as `summary_screen` instead.
  - The **symmetric** "both wait for each other" version is **weaker**: at idle-map time we don't know whether a summary is even coming, and a summary can also appear with no preceding idle map. A literal wait-for-both risks never emitting a stop if one side never shows. The grace-with-fallback shape (commit the better signal if it arrives, else fall back to `early_offline` at expiry) captures the same benefit without that failure mode.
  - **Tension to weigh, not resolve here:** (1) deferring the stop to grace-expiry means a hard crash inside the 10s could drop the stop — though crash recovery + the lazy-expiry path at `PlatformRegionStepper.kt:56-63` already exist and could re-emit it. (2) Alternatively keep emitting `early_offline` eagerly and emit a *superseding* `DASH_STOP(summary_screen)` for the same `sessionId` when the late summary lands — simpler on the write side, but then **two** DASH_STOP rows exist for one session and every downstream consumer (session aggregation, earnings rollups) must prefer `summary_screen` and dedupe. Which of "defer" vs "supersede" is cleaner depends on how DASH_STOP is consumed.
- **Open question / investigation:** there may already be a *latent double-stop* on the idle-map-first path. After the eager `early_offline` stop, if the region's session is still non-null (held in grace) when the summary arrives, the summary re-enters `EffectMap.kt:271` (`next.mode == Offline`, `prevSession != null`) and would emit a **second** `DASH_STOP(summary_screen)`. Need to confirm against logs whether the `EndSession` effect has already nulled the session by then (suppressing the second event) or not (already double-emitting today). That answer also decides which of the two directions above is the smaller change.
- **What would confirm or refute this at the desk:** capture the full end-of-dash event sequence in *both* orderings (summary-first and idle-map-first). For idle-map-first, check: how many `DASH_STOP` events fire, with which `source`, and whether `totalEarnings` ends up the estimate (`early_offline`) or the real summary number. That ground-truth tells us whether this is "lossy attribution" (estimate wins), "double-count" (two stops), or already-correct.

---

## 2026-05-19 — DoorDash session (live capture during dash)

- **Platform tested:** DoorDash
- **Branch under test:** `master` at `b282a3d` (post-#263 merge — `fix/pickup-arrival-storename-scope-to-contact-card`; also includes #261 confirm-decline click fix and #262 stacked-pickup task-mint fix)
- **Field conditions:** developer dashing on DoorDash; entry captured live while at the first pickup (Whataburger) at ~17:43 Central. Notes are about the active Pickup card in the bubble HUD.

### Bugs

#### 1. Pickup card still doesn't surface the actual pickup-by wall-clock time (still open from 2026-05-17 #2)

- **Field observation:** En-route-to-pickup HUD continues to show only the relative countdown ("till pickup-by") with no wall-clock anchor anywhere on the active card. Dasher still can't answer "what time do I need to be checked out by?" from the card alone.
- **Status:** Shipped in #271 (2026-05-20).
- **Prior status (at log time):** This is the same gap logged as #2 on 2026-05-17 — no code changes have shipped to the active-card branch of `FlowCardItem.kt:351-356` since then. Re-logging because the field discomfort persists (and it directly compounds bug #2 below — if the wall-clock deadline were on the card, the dasher would have caught the corrupted countdown in #2 instantly).
- **Re-affirms the prior sketch:** add a `"by ${formatTime(deadlineMillis)}"` secondary caption beneath the countdown. Same shape applies to the Delivery card (`FlowCardItem.kt:312-325`).

#### 2. Pickup card hero shows a ~24-hour ghost countdown ("1434:38") once the pickup-by deadline has been passed

- **Repro:** Arrive at a pickup store after the pickup-by deadline has already passed. Look at the bubble's Pickup card hero.
- **Observed:** At Whataburger at ~17:43 Central, with pickup-by actually at 17:38 (≈5 min past deadline), the active Pickup card showed **"1434:38"** under "till pickup-by". 1434 minutes is 23h 54m — almost exactly one day. The dasher reaction: "??? not sure what that's supposed to mean."
- **Status:** Shipped in #267 (2026-05-20).
- **Hypothesis (from a desk read, not verified against field logs):**
  - The arithmetic is suspiciously clean: 24h − 5m 22s = 23h 54m 38s = **1434m 38s**. Treating "1434:38" as minutes-and-seconds (the output shape of `formatCountdown` at `FlowCardItem.kt:555-560`), this is what you'd see if `deadlineMillis` was anchored to **tomorrow 17:38** instead of today's missed 17:38.
  - `TransformRegistry.kt:265-297` (`parseDeadlineMillis` → `parseTimeTextToMillis`) parses the screen text "Pick up by 5:38 PM" into a `LocalTime` and then resolves it to a `Calendar`. Line 295: `if (target.timeInMillis < now.timeInMillis) target.add(Calendar.DAY_OF_YEAR, 1)`. So any deadline that has already passed at parse time gets pushed forward a full day. This logic is correct for offers (where the deadline is always in the future at receive time) but wrong for in-progress pickups where the deadline has been blown.
  - `PlatformRegionStepper.kt:461` re-writes `deadlineMillis = taskFields?.deadline?.time ?: currentTask.deadlineMillis` on **every** same-phase observation. So once the dasher's clock crosses the deadline, the next pickup-screen parse re-resolves "Pick up by 5:38 PM" → tomorrow 17:38, and the freshly parsed value clobbers the previously-correct one on the active `Task`.
  - End result: `FlowCardItem.kt:351-355` reads `deadlineMillis - now` ≈ +86,078,000 ms, feeds it to `formatCountdown` which prints `1434:38`. No "ahead/late" label because this is the active-card branch, not the frozen-card branch.
- **What would confirm or refute this at the desk:**
  - For today's Whataburger pickup, pull the `PICKUP_NAV_STARTED` / `PICKUP_ARRIVED` events around 17:38–17:45. Check the `deadlineMillis` value on each: expected to be ≈ today 17:38 in early events, then flip to ≈ tomorrow 17:38 on the first event re-parsed after 17:38 passes.
  - Confirm the screen text DoorDash was actually rendering at that time matches "Pick up by 5:38 PM" (i.e. the platform kept the original deadline on screen rather than auto-extending it — if DoorDash itself bumped the deadline, the bug is elsewhere).
- **Possible directions (sketches only, defer to desk review):**
  - *A — kill the day-rollover for active tasks.* Let `deadlineMillis` go negative when past; render `Xm late` (red) on the active card. Trivially small parse-side patch; matches the frozen-card branch's existing "X late" handling at `FlowCardItem.kt:362-363`. Risk: an offer received late at night for a tomorrow-morning deadline (does this exist on DoorDash?) would now resolve to "this morning" and read as already-late.
  - *B — clamp the rollover.* Only roll forward if the past-gap is small (e.g. < N minutes), so a 5-min-late deadline stays late but a 23h-stale "5:38 PM" rolls forward. Picks a threshold out of thin air; brittle.
  - *C — pin the deadline at first parse.* Make `PlatformRegionStepper.kt:461` keep the existing `deadlineMillis` instead of overwriting (or only overwrite when the new value is meaningfully different, e.g. > 1 min delta). Treats the deadline as set-once. Risk: if DoorDash legitimately extends a deadline mid-pickup, we'd miss it.
  - The A+C combination is probably the cleanest: parse honestly (no rollover for past times), and only update the stored deadline when the new parse genuinely differs. But that's two decisions, not one — desk-side call.
- **Why this matters now:** combined with #1 (no wall-clock anchor on the card), the dasher has no way to sanity-check the countdown. "1434:38" alone reads as a render bug; "1434:38 by 5:38 PM" would have read instantly as a stale-deadline interpretation problem.

#### 3. Frozen Drop-off card never transitions to a sensible final state — keeps showing "—" / "till deliver-by"

- **Field observation:** When the Drop-off card does freeze (currently end-of-dash, per 2026-05-17 #3), the frozen card body still reads `—` as the hero with caption `till deliver-by` — i.e. the same shape as an active card with no countdown, not a closed/summary shape. Dasher's preferred direction, verbatim: "it should show the time the dropoff started vs completed like the pickup blocks." The Pickup cards' tertiary row reads "arrived 17:43 · picked up 17:51" — that "what happened and when" framing is what's missing on the Drop-off side.
- **Status:** Shipped in #269 (2026-05-20).
- **Hypothesis (from a desk read, not verified against field logs):**
  - `FlowCardItem.kt:357-369` is the frozen-card branch of `DeadlineBody`. It computes the hero from `arrivalRemaining = deadlineMillis - arrivedAt`. If `arrivedAt == null` the whole branch falls to `HeroBig("—") + Caption(deadlineLabel)` at `:367-368`. That's the exact "— till deliver-by" the dasher sees.
  - For most DoorDash drop-offs (especially no-contact), `task.arrivedAt` stays null all the way through completion — same root cause as 2026-05-17 #3: `EffectMap.kt:402-432` only emits `DELIVERY_ARRIVED` when `nextTask.arrivedAt != null && prevTask?.arrivedAt == null`, which never fires if DoorDash rolls nav → completion without a discrete arrival sub-state. So the Delivery card freezes with `arrivedAt = null` and renders the broken "—" hero.
  - The snapshot already carries `phaseStartedAt` (drop-off nav began) and `phaseEndedAt` (card frozen) — see `FlowCardSnapshot.kt:96-107`. Neither is currently read by `DeliveryBody` (`FlowCardItem.kt:312-325`); the body only passes them through to `DeadlineBody` which only uses them on the active-card branch as the elapsed-time fallback.
- **What the dasher's proposed shape implies:**
  - Frozen Drop-off hero could be `formatDuration(phaseEndedAt - phaseStartedAt)` — the total drop-off-leg duration, analogous to "Pickup took 8m" — with a caption like "drop-off duration" or similar.
  - Tertiary row picks up the "started HH:MM · completed HH:MM" framing the dasher asked for, paralleling Pickup's "arrived · picked up" line at `FlowCardItem.kt:380-389`.
  - When `arrivedAt` *is* populated (e.g. dropoffs where the arrival screen was caught), the existing "+Xm ahead / Xm late vs deliver-by" delta is still meaningful — could be preserved as a secondary line instead of replacing it.
- **Relationship to the existing entries:**
  - **2026-05-17 #3** is about *when* the Drop-off card freezes (end-of-dash via DASH_STOP, not at delivery completion).
  - **Today's #3** is about *what the frozen card displays* even after it does freeze. They share the same root cause for the `arrivedAt == null` case (no `DELIVERY_ARRIVED` for no-contact deliveries), but fixing one doesn't automatically fix the other. Freezing earlier without changing the renderer would still produce "—" + "till deliver-by" for any drop-off without an observed arrival.
- **What would confirm or refute this at the desk:** for any frozen Drop-off card from this session, inspect the corresponding `Task` row — expected `arrivedAt == null` and the rendered hero matches the `:367-368` fallback. If a frozen Drop-off card shows up with `arrivedAt != null` and *still* renders "—", the cause is elsewhere (mapper not threading the field through, etc.).
- **Possible direction (sketch only, defer to desk review):** extend `FlowCardSnapshot.Delivery` with `completedAt` (or just lean on `phaseEndedAt` as the de-facto completed-at) and teach `DeliveryBody` to render a frozen-specific layout: duration as hero, "started · completed" as tertiary. Two-piece change; the data already exists, so the patch is renderer-side.

#### 4. Post-task pay-breakdown announcement is flaky on the first delivery; collapse-then-expand may re-fire the bubble; whole pipeline is all-or-nothing instead of best-effort

- **Field observations (end of first dash):**
  - **(a)** First post-task screen: auto-click on the breakdown didn't complete. Dasher didn't observe a successful expansion + bubble announcement for delivery #1.
  - **(b)** Second post-task screen: auto-click worked, bubble announced normally.
  - **(c)** On one of the two (or in general), dasher manually collapsed the expanded breakdown and re-expanded it. Suspicion: a second auto-click may have fired on the re-collapse, and/or the bubble announcement may have re-fired on the second expansion. Not 100% certain — flagged for verification against captures.
- **Status:** Shipped in #266 (2026-05-20).
- **Dasher recall from prior implementation (worth weighing):** "before, when I implemented this, there was a slight delay to allow the screen to load all the way. It may be the case that it's trying to click it too early." The current rule has `throttleMs: 1000` but no explicit initial delay before the first click attempt — so the click can race the screen layout.
- **Hypothesis (from a desk read, not verified against field logs):**
  - **For (a) — first-click race.** `doordash.json:586-597` fires `click: $expandButton` as soon as the collapsed screen matches, gated only on `isExpanded == false`, deduped under the key `expand_pay_breakdown` with a 1-second throttle. `$expandButton` is bound from `hasIdSuffix: "expandable_view" | "expandable_layout"` with `optional: true`, so if the node hasn't materialized yet at first parse, the click target is null and the dispatch becomes a no-op. The throttle then prevents retry for 1 second; if the dasher dismisses the screen before the next collapsed observation re-fires the rule, the breakdown is never captured.
  - **For (c) — bubble re-fire on collapse → re-expand.** `EffectMap.kt:502` gates the announcement on `next.lastPostTaskPayHash != prev.lastPostTaskPayHash`. `PlatformRegionStepper.kt:292-293` sets `lastPostTaskPayHash = parsed.parsedPay?.hashCode()`. The collapsed parse produces `parsedPay = null` (no `payLineItems` to feed `ParsedFieldsFactory.kt:141`), so the sequence is:
    - Expanded #1: `prev.hash = null → next.hash = H` → `null != H` → **bubble fires**
    - Manual collapse: `prev.hash = H → next.hash = null` → hash now back to null (no announcement on this transition)
    - Re-expand: `prev.hash = null → next.hash = H` → `null != H` → **bubble fires AGAIN**
    The hash gate intends to dedupe, but it's transitively non-monotonic because the collapsed observation resets it. A per-task / per-job idempotency gate (e.g. `lastAnnouncedPayForJobId`) would be monotone and survive collapse cycles.
  - **For the broader "all-or-nothing" shape (c.f. dasher direction).** The announcement only fires when `payData != null` (`EffectMap.kt:499`), and `payData` is only populated by the expanded parse. The collapsed parse already captures `totalPay` and `sessionEarnings` (`doordash.json:536-568`) — useful enough to announce on its own — but those fields don't trigger any bubble effect today. Net result: a failed expand-click swallows the whole announcement, including the headline number.
- **Dasher's proposed direction (recorded verbatim for desk review):** "the post delivery stage should be best effort. If it only sees collapse, it should just record the total, and then it should add on the breakdown if it sees it later instead of only firing the bubble if it sees the breakdown. Also, it should not refire if I collapse it and then re-expand it, so there should be some kinda gate to stop it from the same one refiring."
  - That decomposes into three independent changes; each is reasonable on its own:
    - **(i) Announce on first sighting**, whichever shape it's in. Fire the bubble with `totalPay` (+ `sessionEarnings`) as soon as `PostTaskFields` lands, regardless of `parsedPay`. Auto-click still tries to expand; if/when expanded data lands, *enrich* the existing message (or skip, depending on how Earnings persona handles updates) — don't re-fire as a new announcement.
    - **(ii) Per-task idempotency gate** on the announcement. Track `lastAnnouncedForTaskId` (or `lastAnnouncedForJobId`) on `PlatformRegion`; only emit the announcement once per task identity. Replace or complement the existing `lastPostTaskPayHash` check, which is hash-based and breaks on collapsed-screen interleaving.
    - **(iii) Click-timing robustness** for the auto-expand. Options: small initial delay before the first click on the collapsed screen, or rely on the throttle but make it retry several times across observations (currently 1s throttle, but if the screen dismisses before the second tick the retry never lands). Worth verifying first whether the first-click failure is actually a layout race vs the click target being null vs the dispatch landing but the platform ignoring it.
- **What would confirm or refute this at the desk:** for today's first-dash captures, look at the post-task event stream for both deliveries:
  - Delivery #1: expect to see a `click` effect dispatched for `expand_pay_breakdown` but no subsequent expanded `post_task` observation (or the expanded observation arrives after the screen has been dismissed). Check whether the `expandable_view` node was present and clickable at the moment of first dispatch.
  - Delivery #2: expect to see the click land successfully, followed by an expanded `post_task` observation and exactly one `UpdateBubble(receiptText, ChatPersona.Earnings)` effect.
  - If captures show two `UpdateBubble` effects for the same `taskId`, that confirms (c) — the hash-gate is non-monotonic across the collapse cycle. The `lastPostTaskPayHash` value in the region snapshot before each emission would be the smoking gun (null → H twice).

#### 5. Maps nav view while navigating to a zone / hotspot misclassifies — should resolve as "still awaiting offer"

- **Field observation (start of second dash, ~19:19 Central):** Dasher went online, tapped a zone or hotspot to navigate toward it, and was on the Google Maps nav view inside the DoorDash app. The screen classifier didn't treat this as "still awaiting an offer" — the dasher's read is it was being interpreted as in-task (pickup or dropoff navigation), even though no offer had been accepted.
- **Status:** Shipped in #270 (2026-05-20).
- **Dasher's mental model (verbatim direction):** "if I'm navigating to the zone or to a hot spot, that means I'm not on an offer. So I'm awaiting an offer still … we might need to move that normal map view screen … as a branch of the awaiting offer screen."
- **Hypothesis (from a desk read, not verified against field logs):**
  - `doordash.screen.navigation_generic` at `core/pipeline/src/main/assets/rules/doordash.json:1673-1700` is the likely culprit. It requires `min` + `exit` + (`mi` | `ft`) on screen — the standard Google-Maps-in-DoorDash navigation chrome. It rejects only on `accept` / `decline` text (i.e., a live offer popup). **It has no `state` block**, so it matches the same screen text regardless of flow context.
  - `Ruleset.kt:13,23` sorts ascending by `priority` — *lower number wins*. `navigation_generic` is priority **95**, which evaluates before `on_dash_map` (110), `dash_along_the_way` (111), and `idle_map` (140). So if the dasher is online + offerless + navigating-to-zone, `navigation_generic` matches first and the more specific awaiting-offer matchers never get a chance.
  - `pickup_navigation` (`:686`, priority bound to flow `task:pickup:navigation`) and `dropoff_navigation` (`:775`, `task:dropoff:navigation`) are state-gated, so they *shouldn't* match outside a task flow. The way the dasher experiences this as "in-task" is most plausibly via `navigation_generic` swallowing the screen into a no-state-change classification that suppresses the awaiting-offer matchers from setting flow back to `idle/online`.
  - There's already a `dash_along_the_way` rule at `:1832` keyed on a `navigate_button` id — it covers the DoorDash widget *before* the dasher taps Navigate. Once the dasher is in the Maps nav view itself, that rule no longer matches and we fall back to `navigation_generic`.
- **What the dasher's direction translates to architecturally:**
  - Two distinct nav contexts share the same UI shape: (a) navigating to a pickup/dropoff for a *committed* task, (b) navigating to a zone/hotspot to *seek* an offer. They look identical on screen — the only disambiguator is upstream state (is there an `activeTask`?).
  - One shape: gate `navigation_generic` on `flow: idle/online` and surface it as an awaiting-offer variant (e.g., `idle_navigating_to_zone` with priority above `navigation_generic`'s current 95 — actually *lower* number, since lower wins — say 90, with the idle/online state). The existing `navigation_generic` then stays as the task-flow fallback.
  - Alternative shape: leave `navigation_generic` as a state-neutral classification and have the flow stepper interpret a `navigation_generic` match while in idle/online as a still-awaiting variant rather than a flow transition. Smaller blast radius but defers the categorization into the stepper instead of the rule layer.
- **What would confirm or refute this at the desk:**
  - For tonight's ~19:19 zone-nav window, pull the captured `screenIs` value over the period the dasher was on the Maps screen. Expect `doordash.screen.navigation_generic`. If it's something else (e.g., a leftover `pickup_navigation` from a stale flow state), the cause is in the stepper rather than the rule.
  - Cross-reference the `FlowRegion.flow` at the same timestamps — if flow stayed in `idle` but the bubble behaved as if a task were active, that points at the classifier-only path; if flow itself flipped to a task variant, the stepper is involved.
- **Possible direction (sketch only, defer to desk review):** introduce a `doordash.screen.zone_navigation` (or similar) ahead of `navigation_generic` in priority order, gated to `flow: idle, modeHint: online`, surfacing as an awaiting-offer branch. Leave the generic fallback in place for any flow where a task is genuinely active. Cheap rule addition; no state-machine change required if `navigation_generic` is already state-neutral.

#### 6. Dash summary screen didn't get recognized at end of dash (~19:55 Central)

- **Field observation:** Dasher ended the dash around 19:55 Central. The dash-summary screen appeared as expected on DoorDash, but DashBuddy didn't recognize it (no `SESSION_ENDED` ingestion / no summary captured into the bubble or DB-side aggregates).
- **Status:** Open. Blocked on the next field session capturing the actual snapshot so InboxProcessorTest can X-ray which selector drifted.
- **Hypothesis (from a desk read, not verified against field logs — desk should pull the actual snapshot):**
  - The only matcher for this screen is `doordash.screen.dash_summary` at `core/pipeline/src/main/assets/rules/doordash.json:2109-2235`, priority 150. It requires **both** of:
    - A node with `hasText: "Dash summary"` (exact, case-sensitive)
    - A node with `hasIdSuffix: "textView_prism_button_title"` **AND** `hasText: "Done"`
  - Failure modes worth checking against the captured snapshot from ~19:55:
    - **Text drift on the title** — DoorDash redesigned the screen or renamed the header (e.g. "Dash Summary" / "Summary" / a localized variant). `hasText` is exact-match, not contains; any wording change drops the rule.
    - **Button id drift** — `textView_prism_button_title` is a Prism design-system id. If DoorDash shipped a non-Prism CTA or renamed the resource, the second clause fails even with the same visible "Done" label.
    - **Button label drift** — "Done" could now read "Finish", "Close", "OK", "Got it", etc. Same effect.
    - **Priority shadowing** — unlikely but worth a glance. Anything lower-priority than 150 that requires content present on the summary screen would shadow it. `navigation_generic` (95) needs `min` + `exit` + `mi`/`ft` so probably safe; `notifications_view` (96) keys on the word "notifications" — also unlikely to match a summary screen, but worth verifying the snapshot text doesn't accidentally contain it.
    - **SENSITIVE blocker** — `SensitiveScreenMatcher` runs first per `CLAUDE.md`. If the summary screen tripped it (some kind of payout / banking-adjacent text?), nothing downstream gets a shot.
- **What would confirm or refute this at the desk:**
  - Pull the snapshot captured at ~19:55 Central from this branch under test. Run `InboxProcessorTest` against it — if it lands in `INBOX/` unrecognized, the X-Ray report will show what text + ids the screen actually has. Compare to the two clauses above to pinpoint which drift fired.
  - If captures show the snapshot *was* matched but the parse failed (`totalEarnings` / `sessionDurationMillis` / etc. null), the bug is in one of the field selectors (`hasIdSuffix: "header_pay"`, `hasIdSuffix: "name"` sibling pattern), not in the require block.
- **Why this hits harder than a normal screen miss:** the dash-summary parse is the only path that emits `SessionEnded` fields (`session_ended` `parse.as`, `:2138`), which is presumably how runs are reconciled against the platform's authoritative totals. A missed summary = a session that has to be reconstructed from per-task events without ground-truth cross-check.

#### 7. Historical card stack shows only the first Awaiting card — between-delivery awaiting periods missing

- **Field observation (post-session view):** Looking at the bubble's card stack after the dash, only the **first** Awaiting block appears (the one at the start of the session). The dasher had multiple deliveries with awaiting periods between them; each of those between-delivery "waiting for the next offer" stretches should have produced its own Awaiting block in the stack, interspersed between the PostTask of delivery N and the Offer of delivery N+1. None do. Dasher noted uncertainty about whether the **live** HUD showed awaiting blocks between deliveries — only sure the post-session reconstruction is missing them.
- **Status:** Shipped in #268 (2026-05-20).
- **Hypothesis (from a desk read, confident enough to call out the offending line):**
  - The card stack has two producers (`BubbleViewModel.kt:135-139`):
    - `LiveCardBuilder.build(state)` for the **active** card. `LiveCardBuilder.kt:28-37` returns a fresh `FlowCardSnapshot.Awaiting` whenever `flow == Idle && mode == Online`, with `phaseStartedAt = region.idleEnteredAt`. So during the dash, the live HUD presumably did show an Awaiting card between deliveries — the dasher's "not 100% sure about during the dash" suggests they likely saw one, just wasn't tracking it deliberately.
    - `FlowCardMapper.fold(events)` for the **completed** list. **This is where the bug lives.** Awaiting is only opened in one place — the `DASH_START` branch at `FlowCardMapper.kt:44-58`. It is closed on `OFFER_RECEIVED` (`:60-73`) and defensively on `OFFER_ACCEPTED/DECLINED/TIMEOUT` (`:75-86`). After it closes for the first offer, **nothing re-opens it**. The `DELIVERY_COMPLETED` branch at `:226-245` builds a `PostTask` card and resets `lastDeliveryArrivedAt = null`, but doesn't open a new `Awaiting`. Same for `OFFER_DECLINED` / `OFFER_TIMEOUT` (which also represent a return to awaiting from the dasher's POV).
  - End state of fold for a typical N-delivery session: `[Awaiting₀, Offer₀, Pickup₀, Delivery₀, PostTask₀, Offer₁, Pickup₁, Delivery₁, PostTask₁, …]` — no awaiting block ever appears after the first. Live HUD diverges from the historical reconstruction.
- **What would confirm or refute this at the desk:**
  - Pull the `app_events` rows for tonight's dash and walk the event sequence by hand. Confirm there's exactly one path that could create an Awaiting card (`DASH_START`) and that subsequent transitions back to awaiting (`DELIVERY_COMPLETED` → next `OFFER_RECEIVED`) have no Awaiting card spanning the gap.
  - If the historical stack already lines up with the prediction above (single Awaiting at session start, then Offer/Pickup/Delivery/PostTask interleaved), the diagnosis is settled and the fix is mapper-side.
- **Possible direction (sketch only, defer to desk review):**
  - In `FlowCardMapper.fold`, open a fresh `Awaiting` at the same points the live HUD would (i.e., whenever the dasher returns to idle/awaiting):
    - On `DELIVERY_COMPLETED` (right after pushing the PostTask card).
    - On `OFFER_DECLINED` / `OFFER_TIMEOUT` (after pushing the Offer card — dasher returns to awaiting if they decline / let the offer time out).
    - Possibly on `DASH_RESUMED` if such an event exists (otherwise the pause/resume cycle is opaque to the mapper).
  - Each newly-opened Awaiting then naturally closes via the existing `OFFER_RECEIVED` / defensive-`OFFER_*` paths, producing the interspersed shape the dasher expects.
  - Open question worth flagging for the desk: what `phaseStartedAt` to use for between-delivery Awaiting cards. The natural value is the `completedAt` of the prior PostTask (or the `decidedAt` of the prior declined offer) — i.e. the moment the dasher returned to awaiting. Each Awaiting card then represents the literal "I'm hanging in idle, looking for the next ping" period.

---

## 2026-05-17 — DoorDash session (first run on the flow-card bubble)

- **Platform tested:** DoorDash
- **Branch under test:** `master` at `29c9528` (post-#258 bubble-flow-cards merge — first dash on the new flow-card stack HUD)
- **Field conditions:** developer dashed on DoorDash; included at least one shop-for-items pickup at HEB. Multiple dash sessions across the day, all on the same build. Overall reaction to the new bubble: "I really like the new format. It looks good." The notes below are bugs / polish items spotted *within* that overall-positive read.

### Bugs

#### 1. Pickup card hero says "5 min left" while still checking out, but the frozen card claims "+34 min ahead"

- **Repro:** Take a pickup where you arrive at the store with plenty of slack on the pickup-by deadline, but spend a long time inside (e.g. shopping at HEB). Get to the register with the live bubble showing only a few minutes until pickup-by. Complete checkout. Look at the frozen Pickup card after the phase ends.
- **Observed:** Live Pickup card was showing roughly "5:00 till pickup-by" while the dasher was still at the register and hadn't checked out. After the phase ended, the same card froze with a hero of "+34m ahead". The two numbers can't both be true for the same delivery — they describe wildly different states of urgency.
- **Status:** Open. `arrivedAt` vs `confirmedAt` choice still TBD; needs its own PR.
- **Hypothesis (from a desk read, not verified against field logs):**
  - `FlowCardItem.kt:358` computes the frozen-card delta as `arrivalRemaining = deadlineMillis - arrivedAt`. `arrivedAt` is the **store-arrival** timestamp, not the moment the dasher hit "Picked up". So if you arrived 34 min before deadline and then spent 29 min shopping, the frozen card says "+34m ahead" even though the actual checkout happened with 5 min of slack.
  - `Pickup` snapshot already carries `confirmedAt` (the pickup-confirmation timestamp) — `FlowCardSnapshot.kt:81` and `FlowCardMapper.kt:159-183` set it on PICKUP_CONFIRMED. The frozen delta should plausibly key off `confirmedAt` (urgency at the moment you actually finished pickup), not `arrivedAt` (urgency at the moment you walked in the door).
  - Open question: which number does the dasher actually want post-hoc? "How close did I come to being late?" → confirmedAt. "How long was my buffer when I got here?" → arrivedAt. The current code picks arrivedAt; the live countdown picks neither (it's `deadlineMillis - now`), so the two views diverge precisely when shopping takes a long time. The post-task summary that the developer references ("plus thirty four minutes ahead") looks like the same value.
- **What would confirm or refute this:** capture a PICKUP_CONFIRMED event from a shop-for-items pickup and check whether the payload's `confirmedAt` is materially later than `arrivedAt`, and whether the frozen card's hero matches `deadlineMillis - arrivedAt` (current behavior) vs `deadlineMillis - confirmedAt` (proposed).

#### 2. Pickup card never displays the actual pickup-by deadline time

- **Field observation:** Live Pickup card shows the countdown (e.g. "5:00") and the caption "till pickup-by", but the **wall-clock deadline itself** is nowhere on the card. The dasher cannot answer "what time do I need to be checked out by?" — only "how many minutes left" relative to now. That's a problem when the live countdown disagrees with the post-task summary (see #1) and the dasher wants to sanity-check.
- **Status:** Shipped in #271 (2026-05-20).
- **Where this lives:**
  - `FlowCardItem.kt:351-356` — the active-card branch renders `formatCountdown(remaining)` as the hero and `deadlineLabel` ("till pickup-by") as the caption. No use of `formatTime(deadlineMillis)`.
  - `Delivery` card (`FlowCardItem.kt:312-325`) has the same shape and the same gap for the deliver-by deadline.
- **Possible direction (sketch, not a recommendation):** add a secondary caption like `"by ${formatTime(deadlineMillis)}"` under the countdown. Cheap to add; would let the dasher cross-check the countdown against the literal time on the DoorDash UI.

#### 3. No mid-dash freeze of the Drop-off card — it only appears at end-of-dash, flushed by DASH_STOP

- **Repro:** Complete a delivery. Watch the flow-card stack transition from the live Drop-off card to the live PAID/PostTask card. Watch through the rest of the dash, then end the dash and look at the stack.
- **Observed (per the log narrative):** "the drop-off block had the section for the drop off. Whenever that got completed, it got replaced by the paid block." Later follow-up clarification: the frozen Drop-off card **did appear at the end of the dash, after the dash was ended** — not at delivery completion. The dasher wants the Drop-off summary to be frozen and visible in the history at the moment the PAID card appears, not deferred to end-of-session.
- **Status:** Shipped in #264 (2026-05-20).
- **The end-of-dash appearance is strong evidence:** of the two candidates the original entry sketched, this confirms (a) over (b). `FlowCardMapper.kt:247-258` is the only path that flushes a still-open `openDelivery` — and that path runs on `DASH_STOP`. So the Drop-off card never reaches `completed` at delivery time; it sits half-open in `openDelivery` until the session ends.
- **Hypothesis (from a desk read, narrowed by the end-of-dash observation):**
  - `DELIVERY_ARRIVED` isn't being emitted for this delivery style. `EffectMap.kt:402-432` only emits it when `nextTask.arrivedAt != null && prevTask?.arrivedAt == null` — i.e. an explicit arrival sub-state transition. If DoorDash's "no-contact delivery" rolls from nav → completion without DashBuddy ever observing an arrival screen, `nextTask.arrivedAt` never flips non-null and `DELIVERY_ARRIVED` never fires.
  - With no `DELIVERY_ARRIVED`, `FlowCardMapper.kt:201-224` is never invoked for this delivery, so the open Delivery stays in `openDelivery` and the `lastDeliveryArrivedAt` accumulator stays null. `DELIVERY_COMPLETED` at `:226-245` adds a PostTask card but **doesn't** flush `openDelivery` — only DASH_STOP does (`:247-258`).
  - This also leaves `lastDeliveryArrivedAt` null at the moment the PostTask card is built, so the PostTask's `phaseStartedAt` falls back to `payload.phaseStartedAt` (`FlowCardMapper.kt:231`) rather than the actual arrival time. Worth checking whether the PAID card's timing looks off too.
- **Possible direction (sketch only — defer to desk review):**
  - Either teach the platform stepper to mark `task.arrivedAt` whenever a Drop-off transitions to PostTask/Completed (so the existing `DELIVERY_ARRIVED` emission fires naturally), or close `openDelivery` from the `DELIVERY_COMPLETED` branch in `FlowCardMapper.kt:226-245` as a fallback. The mapper-side fix is the smaller patch but defers the data-model question (is there ever a Delivery that completes without arriving?).
- **What would confirm or refute this at the desk:** pull the captures from this session via the Android Studio plugin and check, for any delivery that did **not** see a frozen Drop-off card appear at the moment of completion:
  - whether the `app_events` table contains a `DELIVERY_ARRIVED` row between `DELIVERY_NAV_STARTED` and `DELIVERY_COMPLETED` for that taskId (expected: absent);
  - whether the corresponding `Task` row in the DB shows `arrivedAt == null` despite the delivery completing.

#### 4. "DROP" chip on Drop-off card reads as ambiguous — rename to "DROP OFF"

- **Field observation:** The frozen/live Drop-off card uses a chip labeled `DROP`. The dasher's reaction: "drop doesn't really make sense, even as a card. The three extra characters aren't gonna hurt anything." Rename to `DROP OFF`.
- **Status:** Shipped in #271 (2026-05-20) — final shape was the one-word `DROPOFF` (better chip fit than the two-word original suggestion).
- **Where this lives:** `FlowCardItem.kt:130` — `is FlowCardSnapshot.Delivery -> "DROP" to MaterialTheme.colorScheme.secondary`. Two-line patch (label string + verifying the chip's `Modifier.padding` still fits the wider text).
- **Polish-shape, not a research item.** Logged here so it doesn't get lost; the desk review can fold it into whatever PR addresses #3.

#### 5. HEB offer shows two pickups for the same store

- **Repro (second dash session of 2026-05-17):** Receive a DoorDash offer for a single HEB shop-for-items pickup. Look at the offer card's per-pickup list in the bubble.
- **Observed:** The Offer card lists **two pickups at HEB** for a single-pickup offer. The dasher's wording: "I just got offered a HEB, and it shows two pickups for HEB. I don't know why."
- **Status:** Open. Offer-rule `each` likely double-matching `display_name` nodes in the shop-for-items subtree; needs its own PR with capture-driven repro.
- **Hypothesis (from a desk read, not yet verified against captures):**
  - The Offer card's pickup count comes from `parsedOffer.orders` size, populated by the rule at `core/pipeline/src/main/assets/rules/doordash.json:310-394`. The `each` iterator selects nodes matching `hasIdSuffix: "display_name"` AND `not(Customer dropoff)` AND `not(Business handoff)`, scoped to `ancestor(2)`.
  - For HEB **shop-for-items**, the DoorDash offer UI may render the store name in **two** subtrees — once as the order summary header and once inside the shop-for-items item-list subtree — and both nodes share the `display_name` id suffix. The `each` then yields a duplicate, and the `ancestor(2)` scope can't disambiguate because both ancestors qualify.
  - Static-pickup offers (Best Buy, Chick-fil-A in the 2026-05-16 log) didn't reportedly show this, which is consistent with the duplicate being specific to the shop-for-items UI shape.
  - Worth confirming this isn't actually a real double-stack of two HEB orders (single-merchant stacked pickup): if the offer screen says "1 pickup" / "1 order" anywhere in the chrome, that contradicts the duplicate hypothesis.
- **What would confirm or refute this at the desk:**
  - Pull the offer-screen snapshot for the HEB offer from the captures and inspect the UI tree for `display_name` nodes — count how many qualify under the `each` filter and what their ancestor paths look like.
  - Check `parsedOffer.orders` in the OFFER_RECEIVED payload: do both entries have `storeName: "HEB"` (duplicate) or are they meaningfully distinct (e.g. different `orderType`, different `itemCount`)? If distinct, this might actually be a real stacked HEB-on-HEB offer and only the rendering needs to clarify; if identical, the rule is double-counting.

#### 6. Stacked pickup overwrites the Pickup card on store change — same unfixed bug as 2026-05-16 #1, now visible in the HUD

- **Repro (third dash session of 2026-05-17, stacked order):** Take a stacked offer with two pickup stops at different merchants — first **Costa Pacifica**, then **Chili's Bar and Grill**. Confirm pickup at Costa Pacifica. Watch the live Pickup card.
- **Observed:** The same Pickup card stays live; the store name flips from "Costa Pacifica" to "Chili's Bar and Grill" in place. The dasher's mental model: "the pickup box should end, and then another pickup box should start … the new pickup overwrote [the first one] instead of ending that pickup block and starting a new pickup block." No frozen Costa Pacifica card in the history; the deadline/arrival/items reset to Chili's values on the same card.
- **Status:** Shipped in #262 (2026-05-18).
- **Already-tracked architectural bug, not a new finding.** This is the **same unfixed issue** as 2026-05-16 item #1 — the pickup phase doesn't recognize a new pickup, it just mutates the active one. That entry traced it to `PlatformRegionStepper.kt:401-441`: PICKUP→PICKUP falls into the same-phase `copy()` branch at `:430-441` and rewrites `storeName` on the existing `activeTask`, same `taskId`, no transition boundary. Nothing has shipped for it yet. This dash adds two pieces of confirmation:
  - the new flow-card HUD makes the bug **visible** (was previously a silent odometer-only symptom);
  - the odometer side of the same bug is presumed still active today — dasher's note: "right now, I'm pretty sure my odometer isn't gonna be running."
- **Why the HUD inherits it:** `FlowCardMapper.kt:115-121` takes the in-place-update branch when `current?.taskId == payload.taskId`, instead of closing and opening a card. `EffectMap.kt:460-468` re-emits `PICKUP_NAV_STARTED` with the new store name on a same-task store change, which is what feeds the mapper. So even though the card layer is new, every layer downstream of the stepper inherits the "one task across both stores" data model.
- **Direction the dasher already endorses (just logging it again for emphasis):** the pickup phase needs to **end the current pickup and start a new one** when it sees a different pickup. That's option A from 2026-05-16 — fix it in `PlatformRegionStepper.updateTaskLifecycle`, mint a new `Task` on a same-phase store-name change, and the odometer + flow-card + per-store TNP attribution all fall out for free. A mapper-side workaround that closes the Pickup card on a same-`taskId` storeName change would mask the HUD symptom but leave the odometer broken — not worth doing.
- **What would confirm or refute this at the desk:** for today's Costa Pacifica → Chili's transition, check that `activeTask.taskId` is constant across the two stores in the captures (expected: yes, consistent with 2026-05-16) and that the inter-store leg has no `ResumeOdometer` effect between the Costa Pacifica `PauseOdometer` and the Chili's arrival.

### Research / design

#### 7. PAID card receipt is mis-shaped — "made-up" labels and an awkward base/tip split

- **Field observation, verbatim:** "it says base pay twenty seventy five tip bonus boost. That's not true. It says a dollar. And I think you made up bonus boost. It should say the actual name of that pay, because I think that's actually supposed to be peak pay and record the peak pay that I got for that offer." Specifically on an HEB shop-for-items order.
- **Status:** Partially closed.
  - **HEB `"235"` / `"799"` lines under `customerTips` — Wontfix.** Verified 2026-05-21 against `field-test-2` `delivery_summary_expanded` captures (17:39:42 and 19:17:24): the `pay_line_item_title` TextView literally renders `"235"` / `"799"` (bounds 69px wide, fits 3 chars). DoorDash labels H-E-B tip lines with a bare store number; same session shows McDonald's as `"McDonald's (17572-SAN ANTONIO, MILITARY @ HUEBNER)"` and Chili's as `"Chili's Grill & Bar (001.005.1267)"` — same pattern, different merchant conventions. Parser is faithfully capturing what's on screen. Not a bug.
  - **Bonus Boost mis-categorized into `customerTips` + receipt-shape needs DoorDash-pay / Customer-tips sectioning — Open.** The "contains 'pay'" substring partition at `ParsedFieldsFactory.kt:141-153` is still fragile for any DoorDash-pay component that doesn't include the word "pay" in its label (Bonus Boost, Promo, etc.). Drive the split from the receipt's structure (DoorDash pay vs Customer tips subtree position) rather than line text. Needs its own PR.
- **Developer's mental model for the receipt:** read it like an actual receipt.
  - **Total** at the top (already present — hero is `$%.2f` totalPay).
  - **DoorDash pay** as one section, broken down into **Base pay** + **any other app-pay component DoorDash actually names** (peak pay, promo, etc.), using whatever label DoorDash itself uses on that order's screen.
  - **Customer tips** as a separate section, broken down **per order** in the offer — tip line per store/customer, since one offer can be a stacked multi-tip job.
- **Where this lives:**
  - Parse rule `core/pipeline/src/main/assets/rules/doordash.json:469-489` — extracts `payLineItems` as `{type, amount}` pairs from id-suffix `pay_line_item_title` / `pay_line_item_value`. So whatever text DoorDash renders on the receipt is what lands in `type`.
  - `ParsedFieldsFactory.kt:141-153` then **splits the line-items based on a substring match for "pay"**: items whose `type` contains "pay" (case-insensitive) → `appPayComponents`, everything else → `customerTips`. So:
    - if the actual DoorDash label is "Peak pay" → routed to `appPay` ✓
    - if the actual label is "Bonus" / "Boost" / "Bonus Boost" / "Promo" → routed to `customerTips` ✗ (and then rendered as `"tip · Bonus Boost"` by `FlowCardItem.kt:415-416`)
  - That matches the verbatim observation almost exactly: a $1 line shows up under tips as "tip · Bonus Boost" because the actual DoorDash receipt label doesn't contain the substring "pay". The dasher reads it as wrong twice: wrong category (it's a DoorDash pay, not a tip), wrong label (the dasher expected "Peak pay"; whatever DoorDash literally rendered was different).
- **Two distinct issues bundled here, worth separating before any fix:**
  - **Categorization is fragile.** The "contains 'pay'" partition is a heuristic that breaks the moment DoorDash labels a pay component without the word "pay". The robust shape is to drive the split from the receipt's structure (which section the line lives under — "DoorDash pay" vs "Customer tips" subtrees — rather than the line's text) since the rule already locates both sub-totals separately at `:453-468`.
  - **Display labels are platform-faithful but dasher-unfaithful.** The dasher's mental label for the $1 was "peak pay"; the actual on-screen text was something else. There's a discoverable mismatch between what DoorDash calls things and what dashers call them. Worth keeping the **literal DoorDash label** as the source of truth, since the alternative is a translation table that drifts every time DoorDash renames a program. The actionable miss is the categorization — once a "Bonus Boost" or "Boost" line ends up under **DoorDash pay** rather than under **tips**, the dasher reading "DoorDash pay: Base pay $20.75, Bonus Boost $1.00, total tips $X" can tell at a glance what kind of pay each line is.
- **Receipt-shape proposal (extracted from the verbatim mental model):**
  - Header: total
  - DoorDash pay section (sub-total + per-component lines using DoorDash's labels)
  - Customer tips section (sub-total + per-order lines using store/customer label)
  - The current PostTaskBody (`FlowCardItem.kt:399-424`) already has the per-line rendering; what's missing is (a) the section grouping, (b) sub-totals per section, (c) reliable categorization.
- **What would confirm or refute the hypothesis:** capture the HEB order's PostTask parsed payload (`AppEventEntity` for `DELIVERY_COMPLETED`) and check the literal `type` strings on each `parsedPay` item. If any non-"pay" string sits in `customerTips` despite being on the "DoorDash pay" side of the receipt, the partition heuristic is the cause and (1) above is the fix shape. If categorization is correct and the user is just objecting to the literal label, this is a labels-only conversation.

### Verification TODOs

#### 8. Investigate the decline-button click — 2026-05-17 decline timestamps

- **Field flags:** dasher declined two DoorDash offers during 2026-05-17 specifically to capture ground-truth on the still-open decline question from yesterday's log (#1 in the 2026-05-16 entry — decline reported as `OFFER_TIMEOUT` instead of `OFFER_DECLINED`):
  - **19:18 Central**, second dash session.
  - **~20:29 Central**, third dash session, **Sprouts** offer, declined just before that session ended.
- **Status:** Shipped in #259 + #261 (2026-05-18) — capture dedup + confirm-decline rule fix landed once these field captures gave ground-truth on the second-click intent.
- **What to check at the desk:** open the captures around each timestamp and look for:
  - whether an "unknown click" appears for the final decline button (the **confirm** tap in the are-you-sure dialog, not the initial decline tap);
  - what `intent` the click was tagged with, if any (`initial_decline` vs `decline_offer` vs unmatched);
  - what `screenIs` value the confirm-decline dialog was classified as at the moment of the click (should be `offer_popup_confirm_decline` for the rule at `core/pipeline/src/main/assets/rules/doordash.json:2319-2328` to match);
  - what `PendingOffer.lastClickIntent` carried at the moment the offer resolved.
- **Two data points** — if both declines look identical in the captures, the issue is consistent and the 2026-05-16 hypothesis is testable in one direction; if they diverge (one matches `decline_offer`, one falls through to timeout), the cause is sensitive to a condition that varies between the two offers — worth diffing the offer types / screen states.
- **Why it matters:** this is the data the 2026-05-16 decline hypothesis was specifically waiting on. If the confirm click shows up as `initial_decline` (or unmatched), the hypothesis holds. If it tags as `decline_offer` and the screen matches, the bug is elsewhere (timing race, payload not threaded through, etc.).

---

## 2026-05-16 — DoorDash session (stacked pickups)

- **Platform tested:** DoorDash
- **Branch under test:** `master` at `af54b87` (post-#145 personal-economy-v2 merge — same build as the entry below)
- **Field conditions:** developer dashed on DoorDash and ran a stacked / double static order — two pickup stops at different merchants (first Best Buy, then Chick-fil-A), roughly a mile apart by car odometer. After the run, the in-app odometer read a few tenths of a mile short of the car odometer.

### Bugs

#### 1. Multi-stop pickup: second store doesn't register as a new pickup, and the inter-store drive isn't counted on the odometer

- **Repro:** Take a stacked order with two distinct pickup stops at different merchants. Complete pickup #1 (arrive, mark picked up — note that with a double *static* order you don't fully complete pickup #1 in the DoorDash UI, the platform just rolls you toward the next store while phase stays PICKUP). Drive to store #2.
- **Observed:** On approach to the second store, the bubble's store name updates from "Best Buy" to "Chick-fil-A". The app does **not** treat this as a new pickup — no "Pickup Started" bubble announcement, no new pickup lifecycle event. The drive between the two stores (~1 mi by car odometer) does not get counted; the dash mileage ends up a few tenths short of the car for the day, consistent with the entire inter-store leg being dropped.
- **Status:** Shipped in #262 (2026-05-18) — new `Task` minted on same-phase store change; `ResumeOdometer` fires naturally on the new pickup.
- **Expected:** Each store on a stacked run should be its own pickup — at minimum because the dasher has to navigate to the next store, so the mileage between them is real and unreimbursed if we don't log it. TNP per stop also wants per-store mileage attribution.
- **Framing (per the log narrative):** the missing odometer leg is almost certainly a downstream symptom of the missing pickup-transition event, so this is one entry covering both observations rather than two separate bugs.
- **Hypothesis (from a desk read, not verified against field logs):**
  - `PlatformRegionStepper.kt:401-441` is the smoking gun. The stepper branches on `currentTask.phase != taskPhase`. PICKUP → DROPOFF (or DROPOFF → PICKUP across orders) mints a new `Task` at `:409-425`. PICKUP → PICKUP (second pickup of a stack) falls into the same-phase `copy()` branch at `:430-441` and just mutates `storeName` on the existing `activeTask`. Same `taskId`, no transition boundary.
  - `EffectMap.kt:308-333` is the only path that emits `ResumeOdometer` for a starting pickup, and it's gated on `prevTask == null && nextTask != null` — false for a store-to-store mutation.
  - `EffectMap.kt:380-414` does detect the store-name change (`storeChanged` at `:387-388`) and emits a bubble update + a `PICKUP_NAV_STARTED` log entry titled "Store Name Updated" — but it does **not** emit any odometer effect.
  - Meanwhile `EffectMap.kt:361` fires `PauseOdometer` on first arrival at store #1. So the sequence is: arrive at Best Buy → `PauseOdometer` → drive to Chick-fil-A → storeName mutated in place → bubble & log update but **no `ResumeOdometer`** → odometer stays paused for the entire inter-store leg. That matches the "few tenths short" observation almost exactly.
- **What would confirm or refute this:** capture the state/effect stream across the Best Buy → Chick-fil-A handoff and check:
  - (a) does `activeTask.taskId` change across the two stores, or stay constant?
  - (b) is there a `PauseOdometer` on Best Buy arrival followed by **no** `ResumeOdometer` until Chick-fil-A arrival (or until something further downstream)?
  - (c) does the events table show a `PICKUP_NAV_STARTED` row with payload `{message: "Store Name Updated", previous: "Best Buy", updated: "Chick-fil-A"}` and no associated odometer delta between it and the prior arrival event?
- **Possible directions (sketches for triage, not a recommendation):**
  - *Option A — make the second store a real new pickup.* In `PlatformRegionStepper.updateTaskLifecycle`, treat a same-phase store-name change as a task boundary: complete the current `Task` and mint a new one. Existing `EffectMap.kt:308-333` wiring then resumes the odometer for free, and per-store mileage attribution falls out naturally.
  - *Option B — minimal patch in EffectMap.* Leave the in-place mutation alone, but emit `ResumeOdometer` from the `storeChanged` branch at `EffectMap.kt:380-414`. Smaller blast radius, but the schema still says "one task, multiple store legs," which probably bites later — TNP per stop wants the mileage bound to the *new* pickup, not appended to the previous one.
  - *Trade-off worth flagging:* option A is more invasive in the stepper and may surface latent assumptions in code that reads `region.activeTask` expecting it to be stable across a job. Option B is cheap but defers the data-model problem.
- **Tangentially related, worth checking while in this neighborhood:** does this affect single-pickup jobs that hot-swap store name during the unknown-resolution window (e.g. "Unknown" → real name once the matcher figures it out)? `:387-388` filters `nextName != "Unknown"`, so the *first* resolution probably doesn't trip the storeChanged branch, but a string-rewrite mid-pickup (e.g. "Best Buy" → "Best Buy #1234") would.

---

## 2026-05-16 — DoorDash session

- **Platform tested:** DoorDash
- **Branch under test:** `master` at `af54b87` (post-#145 personal-economy-v2 merge — the build that introduced the cost-breakdown bubble HUD)
- **Field conditions:** developer dashed on DoorDash; mix of accepts and declines.

### Bugs

#### 1. DoorDash decline → bubble says "Offer Timed Out" instead of "Offer Declined"

- **Repro:** Receive a DoorDash offer, tap **Decline**, confirm **Decline offer** in the dialog.
- **Observed:** Bubble shows "Offer Timed Out!" and the event is logged as `OFFER_TIMEOUT`. The decline isn't recognized at all.
- **Expected:** "Offer Declined" / `OFFER_DECLINED`.
- **Acceptance appears to work** correctly in the same session — only decline is broken.
- **Status:** Shipped in #255 + #259 + #261. The #255 attempt to treat `initial_decline` as a decline outcome was reverted (commit `e4dbe26`); the real fix landed in #259 (click-capture screen context dedup) + #261 (confirm-decline rule match-descendant-text), gated on field-test ground truth from item #8 of the 2026-05-17 flow-card session.
- **Hypothesis (from a quick desk read, not verified against field logs):** the decline flow is two clicks. First tap on the offer popup fires intent `initial_decline` and opens an "are you sure?" confirmation dialog; the confirm tap fires `decline_offer`. The confirm rule (`core/pipeline/src/main/assets/rules/doordash.json:2319-2328`) is gated on `screenIs: "offer_popup_confirm_decline"`. If the dialog closes before the click observation is matched against the dialog's screen classification, only `initial_decline` may end up on `PendingOffer.lastClickIntent`. `EffectMap.resolveOfferOutcome` (`core/state/.../EffectMap.kt:563-581`) only recognizes `decline_offer` / `accept_offer`, so any case where `decline_offer` doesn't land would fall through to `OFFER_TIMEOUT`.
- **What would confirm or refute this:** capture the click + screen event stream for a real decline session and check (a) whether the `offer_popup_confirm_decline` screen is being matched at all, and (b) which `lastClickIntent` value `PendingOffer` actually carries at the moment the offer resolves. If `initial_decline` is the value seen, the hypothesis above holds; if `lastClickIntent` is null/something else, the cause is elsewhere (rule text drift, ViewPipeline drop, dialog never matched as a screen, etc.).
- **One possible direction (if hypothesis holds):** treat `initial_decline` as a decline signal in `resolveOfferOutcome`. Worth considering vs. alternatives like making the confirm rule less screen-strict, or matching clicks against the screen at click-time rather than after the screen has changed. Not a recommendation — just a sketch for triage.

### Research / design

#### 2. Bubble HUD live $/hr is inflated right after accept

- **Field observation:** Immediately after accepting an offer and starting navigation, the bubble's order $/hr reads something like "$120/hr" because almost no time has elapsed. It re-anchors to a sensible number only after several minutes, by which time the dasher has already been looking at a misleading number that gives false confidence.
- **Status:** Open. Design call (hide-until-below-projection vs always-show-with-color) not yet made.
- **Why it matters:** every brand-new offer looks like a win at this point — the live rate is meaningless until enough elapsed time has passed for `payAmount / elapsed` to be informative. False optimism is worse than no number.
- **Proposal (developer's first instinct, exploratory — "I'm not sure, though"):** consider suppressing the live $/hr display until it actually drops *below* the offer's originally-projected $/hr. Above projection → silent (you're on or ahead of pace, nothing to act on). Below projection → display starts, because that's where the number becomes actionable ("I'm losing margin the longer this drags").
- **Where the wiring appears to live, for triage:**
  - Live rate is computed in `BubbleScreen.formatDollarsPerHour()` (`app/src/main/java/.../ui/bubble/BubbleScreen.kt:897-906`) as `earnings / hours` with a 60-second "--" gate (gate is on display only, not on signal).
  - `BubbleViewModel` currently captures only `payAmount` into `lastAcceptedOfferPay` on the offer→task-flow transition (`BubbleViewModel.kt:78-97`); the projected $/hr from `OfferEvaluation` isn't carried forward into the task flows, so the bubble doesn't currently have the projection to compare against.
- **Tradeoff to consider:** hide-until-below also hides the metric for the entire expected duration of the order, so if the projection was way off (bad merchant estimate, unexpected traffic), the dasher wouldn't see the problem until late. Alternative shape: always show, but mute/desaturate when above projection and escalate color when below. Not a recommendation — just two shapes to weigh.

---

## 2026-05-09 — Uber session

- **Platform tested:** Uber Driver
- **Branch under test:** `feature/click-rule-overhaul` (commit `90200bc`)
- **Field conditions:** developer dashed on Uber; accepted every offer
  received during the session.

### Bugs

#### 1. Uber: bubble stays "offline" after going online

- **Repro:** Tap "Go online" in Uber Driver.
- **Observed:** Bubble shows offline state. A "started dashing" notification
  appears (note: that notification's UI copy is stale — hasn't been updated
  for the multi-platform world — but that's a separate cosmetic concern).
- **Expected:** Bubble enters online/dashing state.
- **Likely cause:** `uber.click.go_online` intent
  (`app/src/main/assets/rules/uber.json:184-190`) fires the rule, but no
  handler in `state/EffectMap.kt` reacts to `go_online`. DoorDash's
  start-of-session path produces `AppEffect.StartDash`
  (`state/EffectMap.kt:184`); there's no Uber-equivalent wiring.
- **Proposed fix:** unify the intent vocabulary across platforms — rename
  DoorDash's start-dash click intent to `go_online` (or whatever shared
  term fits), rename `AppEffect.StartDash` → `StartSession` (or similar
  platform-neutral term), and remove DoorDash-specific language elsewhere
  in the state machine. Single intent, single handler, both platforms route
  through it.

#### 2. Uber: online/offline screen recognition flaps

- **Observed:** Immediately after going online, the screen matcher appears
  to oscillate between online and offline classifications.
- **Likely contributor to #1** — even with the intent wired up, a flapping
  classifier may immediately clobber the new state.
- **Field UX context (helps explain):** Uber has *two* surfaces from which
  a driver can go online or offline:
  - The **dashboard** (post-splash home screen) has a "start Ubering"
    button.
  - Tapping the map widget opens the **full map screen**, which has its own
    "Go" button.
  - Going offline is symmetrical: end from the map, or back out to the
    dashboard and end from there.
  - So "online" and "offline" each have **two valid screens** with different
    layouts. A matcher keying on a single UI element only present on one
    surface will flip when the user moves between them.
- **Hypothesis:** the current matcher is too strict — keying on a single UI
  telltale that's only present on one of the two surfaces.
- **Action:** capture all four screens (online-dashboard, online-map,
  offline-dashboard, offline-map), find a robust common signal per state,
  relax/rework the matcher.

#### 3. Uber offer TTS reads raw text; offer shape not standardized across platforms

- **Repro:** Receive an Uber offer with TTS announcement enabled.
- **Observed:** TTS reads minutes-as-miles (a field-mapping bug in the
  Uber parser), then continues reading the raw screen string — so the user
  hears the parsed-wrong value *and* the real miles trailing in the verbatim
  text.
- **Expected:** TTS speaks a constructed message built from parsed fields,
  not raw screen text.
- **Underlying problem — offer parse fields aren't normalized between
  platforms:**
  - Uber gives **duration in minutes** directly.
  - DoorDash gives a **deadline timestamp** (due time).
  - TTS / UI should work off a single canonical offer shape, computing
    whichever representation is needed (duration ↔ due time) rather than
    reading screen text verbatim.
- **Proposed fix:** canonical parsed offer schema; TTS announcement built
  from fields, never from raw strings.

#### 4. Uber offer overlay not captured by pipeline

- **Repro:** Receive an offer in Uber while in the field.
- **Observed:** Offer was not evaluated — no parsed data, no bubble update.
- **Hypothesis:** Uber renders some offers as a full-screen notification or
  system-overlay window rather than a normal app window; the current
  accessibility pipeline doesn't catch overlay-style surfaces.
- **Investigation TODO (back at desk):**
  - Check whether *any* data was captured for the missed offer.
  - If the `WindowChanged` pipeline still exists, see if it picked up
    anything. If not, this becomes a case for keeping/restoring it.

#### 6. Uber: persistent "currently online" notification dropped as noise

- **Current behavior:** `uber.notification.online_status`
  (`app/src/main/assets/rules/uber.json:218-225`) is classified
  `shape: "noise"` and dropped entirely. Match condition is
  `titleContains: "currently online"`.
- **Field observation — the body carries live flow state:** while on a
  delivery, the body reads things like "picking up from [store]" during
  the pickup leg, and "going to [customer address]" during the dropoff
  leg. The notification body reflects **which leg of the offer is active**.
- **Field observation — the actions also carry signal:** the active
  notification exposes action buttons that change with leg (e.g., a
  "Contact customer" button is present during dropoff). These are a
  structured, leg-correlated signal.
- **Why it matters:** given Uber's flowy UI (#5) and overlay-style offers
  (#4), this notification may be the most reliable continuous source of
  "what is the driver actually doing right now" on Uber.
- **Broader parser concern:** verify the notification parser is extracting
  **everything** Android exposes — title, text, sub-text, big-text,
  actions/buttons, action labels, action intents — not just title + body.
- **Proposed fix:** re-shape `uber.notification.online_status` from noise
  to parsed; expand parser to surface action buttons; route the parsed
  result as a flow-region/leg signal in the state machine. Likely tightly
  coupled to #1, #2, #5.

### Open questions / investigations

#### 7. How does Uber's slide-to-confirm surface in accessibility?

Uber uses slide-to-confirm widgets for advancing pickups and dropoffs (and
the "Go" button to start dashing may be similar). Three common
implementations:

1. **Slider/SeekBar-backed** — fires `ACTION_SET_PROGRESS` and emits
   `TYPE_VIEW_SCROLLED` accessibility events; we can detect "reached end."
2. **Custom view that dispatches a click on completion** — surfaces as a
   normal click event; a regular click rule keyed on the slider's node id
   catches it.
3. **Pure gesture-only surface with no accessibility action** — hardest;
   we'd have to infer from the screen transition that follows the slide.

Most production apps go with #1 or #2 because TalkBack users need it to
work. Worth confirming by capturing accessibility events while completing
a slide back at the desk.

**Field addition:** slide-to-confirm appears to be the standard
"advance to next leg" affordance on Uber pickups and dropoffs (likely
absent on shop-and-deliver — needs verification). This is the
**leg-transition signal** equivalent to DoorDash's "Arrived at store" /
"Complete delivery" buttons. Capturing it well is high-priority.

#### 9. Uber "match" screen — multiple concurrent offers

- **Observed:** Uber has a screen called the **match screen** that can
  display more than one offer at a time. Saw it in the field with multiple
  offers visible.
- **DoorDash analog:** none — DoorDash offers are presented one at a time.
- **Implication:** the offer evaluator and `OfferMatcher` may need to
  handle a list of offers, not a single-offer assumption.
- **Action:** capture this screen at the desk; design parser + evaluator to
  support N≥1 offers.

### Meta / architecture

#### 5. Uber UI is "flowy" — recognition strategy needs to differ from DoorDash

- DoorDash screens are discrete and separable; Uber screens blend into each
  other (shared chrome, persistent map background, transient sheets and
  overlays).
- Recognizing a screen on Uber is less about "exact tree match" and more
  about "what set of affordances is currently visible."
- **Action:** document the recognition strategy difference somewhere.
  Options considered:
  - This log entry (current home — fine for now).
  - A separate architecture issue.
  - `CLAUDE.md` addition.
  - **Per-rules-file README** — one alongside each `assets/rules/*.json`
    explaining how captures were used to identify screens, what fields were
    extracted, and any platform-specific quirks. (Probably the most
    maintainable; keeps platform-specific reasoning next to the rules it
    governs.)

### Research / design

#### 8. ZIP-derived zones as a first-class signal

- **Question:** is the platform-provided zone name even worth scraping, or
  is it an "extra" at best?
- **Problem with platform zones:** unreliable boundary semantics — dashing
  *in* zone X doesn't mean the pickup or dropoff is *in* zone X. A driver
  can leave the zone mid-offer (e.g., dashing in zone X but the offer's
  pickup is just outside, or the dropoff is several zones away).
- **Proposal:** extract the **ZIP code** from the customer dropoff address
  (and possibly pickup) and treat that as the canonical geo-signal. Hash
  the rest of the address as today, but keep the ZIP as a structured field.
- **Why it matters on both sides:**
  - **Academic federation:** "do tips correlate with ZIP demographics?" is
    a meaningful query and needs ZIP, not platform-zone.
  - **Driver side:** lets a dasher correlate earnings/tips by ZIP
    independent of platform zone definitions, which can change.
- **Open implementation questions:**
  - Pre-hash extraction (extract ZIP, then hash the rest) — needs the
    address parser to handle US format reliably.
  - ZIP → demographic classification — likely already exists (Census tract
    / USPS); confirm before reinventing.
  - Pickup-side ZIP useful too? Probably yes for restaurant-density /
    market context.

### Verification TODOs

#### 10. Accept-button capture consistency for this Uber session

- **Field condition:** developer accepted **every** offer in this session.
- **Action at desk:** cross-reference accept-button click events / sessions
  against the actual offers received during the session window. Any missing
  accepts indicate either matcher gaps, click-classifier gaps, or pipeline
  drops. Good ground-truth opportunity.
- **Related:** while doing this, also verify capture consistency of any
  pickup-confirm and dropoff-complete slide events from #7 — if those
  surface as click events, they should be present alongside the accepts.
