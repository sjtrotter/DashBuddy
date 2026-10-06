# Pre-dash field-test checklist

Read §1 must-watch first, then §2. Dated field reports remain in [README.md](README.md).
Extracted on 2026-10-06: 203 items; counters retained (121 at 0/2, 42 at 1/2, 1 at 2/2, 39 without a whole-item counter).
Age uses the original README item's **first line**, via `git blame --date=short -L 49,3130 2ae6a04b3d4d -- docs/field-testing/README.md`; later sub-line notes do not reset it.
The only description correction is #1135's quoted callout, verified against `app/src/main/res/values/strings.xml`, `money_tab_unattributed_callout_format`.

## §0 Policy

2026-10-06 — seeds approved by the dev; 2026-10-07 — the five must-watch picks and the four supersessions confirmed by the dev

1. Prune superseded items when replacement evidence supports the dev's decision; `[superseded?]` below proposes pruning and preserves the original item pending that decision.
2. The pre-dash agent reads a MUST-WATCH list of ≤ 5 money / redaction / Pledge / live-UI tap items first; the agent proposes and the dev picks.
3. Long-tail desk closure is PROPOSED by agents only (fable's seed threshold — 1/2 desk-verified + 3 clean incidental dashes — is the proposed bar, pending the dev's confirmation); agents never record a confirmation they did not witness. Closures are the dev's.
4. An item untouched for 6 weeks at `Confirmed: 0/2` moves to `[review]`, never auto-retired; on 2026-10-06 the inclusive first-line blame cutoff is 2026-08-25.
5. This file is the checklist's home; README holds the dated log and a pointer here.

Vocabulary (one status per item): `active` · `must-watch` · `review` (aged) · `desk-verified` · `field-validated` · `superseded` · `retired-unvalidated`.

The `?` suffix in `[must-watch?]` / `[superseded?]` marks a proposal, not an additional status or a dev decision; no item is newly desk-verified, confirmed or retired here.

## §1 Must-watch (dev-confirmed 2026-10-07 — read these first)

Five items the dev confirmed on 2026-10-07. Each full item appears once, with its original counter.

- [must-watch] **Name the money (#1135 PR 1):** Money tab → the "what you earned" bar has four segments (base pay /
  tips / not itemized / not matched) and NO "Bonuses & other"; its legend dollars, minus any "DashBuddy recorded … more than
  was reported" line beneath it, sum to the Earned figure above it to the cent; a stacked job's un-split receipt shows under "not itemized" with "estimated from
  the offer" named when the row was priced from the offer; a dash whose summary exceeded its deliveries
  shows "reported for a dash but not matched to a recorded delivery". Dash detail → the big figure is
  labelled "Reported — dash summary" / "— in-dash counter" / "— entered by you" / "Recorded by DashBuddy
  (no report)". Export → `sessions.csv` has a `report_source` column. — Confirmed: 0/2
  Why proposed: Money reconciliation: the labels, segment totals and report provenance must agree to the cent.
  Triage source: first-line blame 2026-10-05 (`e1d50318c`, original README L51).

- [must-watch] **Session report correction (#1134):** on session 483 (09-10 17:11, `early_offline`, 0 deliveries)
  tap Correct total → No summary; the Sep 7–13 week's Earned drops from $646.34 to $606.20 and the
  dash shows 'report corrected'; Restore detected value brings $40.14 back. — Confirmed: 0/2
  Why proposed: Money correction: removing and restoring a false session report directly changes weekly earnings.
  Triage source: first-line blame 2026-10-05 (`30a3624a0`, original README L78).

- [must-watch] **Chat compose box never persists raw (#919):** after a dash in which you typed in the DoorDash chat,
  no file under `captures/doordash/accessibility.click/UNKNOWN/` or `accessibility.window/UNKNOWN/`
  carries a `message_input` node or an `android.widget.EditText` node with readable `text` — the field
  reads `[redacted]` — and `app.log` shows `Capture backstop: UNKNOWN click node carried customer PII (… input=edittext-class)` (or `input=editable`);
  a recognized `chat_conversation` envelope masks the box through its rule entry.
  - Confirmed: 0/2
  Why proposed: Pledge: typed chat text must never persist raw in recognized or UNKNOWN captures.
  Triage source: first-line blame 2026-10-05 (`8d8855c9e`, original README L82).

- [must-watch] **🆕 NEW — #1058 — the two dropoff sheets that were shipping addresses and door codes to
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
     (Require a co-present TEXT node: text-free `drop_off_workflow_host_fragment` loading skeletons are a
     known false positive, seen on both the 09-07 and 09-09 pulls.)
  2. In the recognized folders for those two surfaces
     (`captures/doordash/accessibility.window/dropoff_pre_arrival/` and
     `.../dropoff_workflow_sheet/`), every address line, unit/`Apt` value, instruction body, quoted
     note and bare code must read `[redacted]` or `[redacted:<4hex>]` — the codes, unit numbers,
     ZIPs and notes must be **plain** `[redacted]` (no hex), the street lines and any bare
     first-name + last-initial keep the hex.
  3. `grep -c dropoff_workflow_sheet app.log` — a non-zero count on a dash where you took a
     leave-at-door drop is the positive signal that the new rule is live on the device build.
  - Confirmed: 0/2 (desk 09-07 on `aabb56d0`: NOT EXERCISED — neither the alcohol arrival card nor the leave-at-door workflow sheet rendered (`grep -c dropoff_workflow_sheet` = 0). Adjacent positives: ordinary `dropoff_pre_arrival` frames on 8.95.6 are fully masked with the right GRADES — `Deliver to [redacted:7201]` + a hashed street line + PLAIN `[redacted]` for the unit and the customer note. Check 1's grep returns two FALSE positives on text-free `drop_off_workflow_host_fragment` loading skeletons — require a co-present text node. Desk 09-09 on `8028691a`: NOT EXERCISED again — `grep -c dropoff_workflow_sheet` = 0, three more text-free skeleton false positives; adjacent positive holds on 8.96.8: all six `dropoff_pre_arrival` envelopes read `Deliver to [redacted:<hex>]` + hashed street + PLAIN `[redacted]` for unit, ZIP, note and an `Entry code` block.)
    - desk 09-13 (3rd pull): NOT EXERCISED — `grep -c dropoff_workflow_sheet` = 0; check 1's two hits are the documented text-free skeletons; all 12 `dropoff_pre_arrival` envelopes on 8.96.8 mask correctly.
    - desk 09-15 (4th pull): NOT EXERCISED for the workflow sheet. NEW on 8.97.8: the alcohol pre-arrival card DID render (`UNKNOWN/2026-09-14_12-57-05-769…`, `alcohol_dropoff_ic_scan`) and the #1058 arm did NOT claim it — no PII on that render, but the arm needs re-checking against 8.97.8. The `dropoff_pre_arrival` adjacent positive holds on all 34 envelopes.
    - desk 09-20 (5th pull): **LEAK B's workflow sheet EXERCISED for the first time, and CLEAN** — `grep -c dropoff_workflow_sheet` = 2, and the single envelope (09-17 10:59) grades correctly: `[redacted:de0f]` for the name, PLAIN `[redacted]` ×5 for the address lines / unit / note, `Leave it at the door` and the merchant item descriptions kept. All 34 `dropoff_pre_arrival` envelopes still mask correctly. Check 1 returns 7 UNKNOWN hits: 6 are the documented text-free skeletons and the 7th is a NEW unruled handoff-photo instruction page (`2026-09-18_15-36-07-896…0bf650`, no PII). The item stays open for **leak A** (the alcohol arrival card did not render this window).
    - desk 09-21 (6th pull): **PARTIAL PASS WITH A REGRESSION.** The two `dropoff_workflow_sheet`
      envelopes still grade correctly on 8.98.5 (hashed name, PLAIN `[redacted]` for the address
      lines / unit / note). But the adjacent positive that has carried this item for five pulls
      BROKE: one `dropoff_pre_arrival` envelope shipped the bottom-bar customer name **raw**
      (#1123 — the rule's redact does not cover that node, while `dropoff_workflow_sheet`'s does),
      and the same sheet's un-anchored SECOND frame fell to UNKNOWN carrying everything (#1122, the
      #1116/#985 class recurring). Leak A (the alcohol arrival card) still has not rendered.
    - desk 09-26: PARTIAL — the alcohol pre-arrival card (`alcohol_dropoff_ic_scan`) is claimed as
      `dropoff_pre_arrival` 4× with no raw PII (09-21 12:39/12:52, 09-23 09:04/09:19). The workflow sheet was
      recognized and masked once, but its header-less frame leaked again (#1122; fix not on the phone).
    - desk 09-27: NOT EXERCISED for either leak surface — 0 `alcohol_dropoff_ic_scan`, 0 `dropoff_workflow_sheet`.
      Adjacent positive holds: 14 `dropoff_pre_arrival` envelopes grade correctly.
    - desk 09-29: NOT EXERCISED — 0 `alcohol_dropoff_ic_scan`, 0 `dropoff_workflow_sheet`. Adjacent positive: 11
      `dropoff_pre_arrival` grade correctly.
  Why proposed: Redaction: dropoff addresses and door codes must remain masked across the two sheet shapes.
  Triage source: first-line blame 2026-09-05 (`054433e69`, original README L716).

- [must-watch] **🆕 NEW — the receipt's auto-expand tap re-finds the row by its labels, and every tap lands on the
  control that OWNS the click (#1149).** Taps now resolve the clickable owner first (verified, then
  `refresh()`ed right before the click), and an id-less bind is re-found by its exact label
  fingerprint BEFORE its captured bounds — so a receipt sheet still sliding when it was bound no longer
  makes the first tap miss. **No dash needed on the expand path** (any completed delivery's receipt).
  **How to tell it works:**
  1. The settled receipt's `EXPAND_EARNINGS` tap lands on the FIRST admitted settled frame:
     `Performing expand_earnings` → `Single verified candidate` with no `Could not find any live node`
     before it (the #1102 retry should no longer be needed).
  2. No new `Strict click: refusing`, `(N stale)`, `semantic twins` or `semantic re-find inconclusive`
     WARNs on known-good surfaces (the receipt, the heads-up Accept/Decline buttons).
  3. The `bindShortfall{…}` census in `PipelineStats` is unchanged from the previous pull.
  Confirm-decline automation stays DENIED by the dev's choice — it is not a validation target here.
  - Issue: #1149. Confirmed: 1/2 (desk 2026-10-05: 13 expands, 12 clean, 1 stale abort while an offer card covered the receipt — the designed outcome)
  Why proposed: Live tap gate: receipt expansion must resolve the owning control and refuse stale or ambiguous targets.
  Triage source: first-line blame 2026-09-29 (`6becba8f8`, original README L300).

## §2 Active

### 0/2 or 1/2 younger than 6 weeks — newest first

- **#1189 notification census: with debug census enabled, an admitted UNKNOWN push produces one notification skeleton; repeat pushes dedup; a customer lead-in is withheld; an action-only sensitive marker produces a refusal and no upload. Screen and notification uploads share the daily budget. Confirm counters and gated ops output without recording payload text. Confirmed: 0/2.**

- [active] **8.95.6 sheets (#1079):** the alcohol warning sheet ("Scan customer's ID and collect signature…") and the
  'Pick up by / Order includes' going-to-store sheet no longer appear as UNKNOWN captures (they land in
  `dropoff_alcohol_warning_sheet/` and `pickup_going_to_store_sheet/`); a pickup card's Apt/Suite line reads
  `Apt/Suite: [redacted]` in the capture. — Confirmed: 0/2

Ordered by first-line blame date; source order breaks ties. The five §1 proposals are not repeated here.

- [active] **Trip Radar board (#856):** after an Uber dash with Trip Radar open, no file under `captures/uber/accessibility.window/UNKNOWN/` carries a `<Street> & <Street>, <City>` line; the board's frames, including expired-card overlays ("This request is no longer available"), sort as `trip_radar_board` with `[redacted:…]` dropoff lines. Known residual, not a failure: the HOME-screen Trip Radar browse pill with a half-rendered card stays UNKNOWN (#856 / #251). — Confirmed: 0/2

- [active] **Per-order handling (#1113 slice 1):** a two-order DoorDash stack's bubble card $/hr should equal
  `pay ÷ (2.5 × miles + 14) × 60` (two base overheads), not `… + 7`; e.g. $12.15 / 10.7 mi → ≈ $17.9/hr,
  not $21.6. Check one stacked card against that arithmetic (the card shows no duration field; the $/hr is
  the observable). Also: no copy anywhere in Settings or the wizard promises an
  automatic decline or accept (the wizard's threshold footers now say "flagged to decline"); Strategy still shows the Quick declines switch; one
  `Strategy: Purged dead automation keys (#1113)` INFO line on the first launch of this build only. — Confirmed: 0/2
  Triage source: first-line blame 2026-10-05 (`ed9194101`, original README L59).

- [active] **Consent receipts (#170):** after re-allowing `expand_earnings` on a fresh install, Automation & Consent shows 'Allowed · <today> · v<build> · disclosure r1' under the row; Screen events shows its own line. — Confirmed: 0/2
  Triage source: first-line blame 2026-10-05 (`f6766cabe`, original README L65).

- [active] **Arrival re-eval (#823 P2):** on a single-store Shop & Deliver, the pickup card's $/hr changes once
  you are in the store with the item list open and shows 'revised at arrival'; one Dispatcher advisory
  names the observed vs offered count; a stacked job shows no revision; no advisory appears for a job
  whose offer carried no net pay. — Confirmed: 0/2
  Triage source: first-line blame 2026-10-05 (`ca0748bb1`, original README L67).

- [active] **Same-store consecutive offers are two offers (#1069):** after a dash with two H-E-B (same store)
  offers back to back, `app_events` holds TWO `OFFER_RECEIVED` rows with two `offerHash`es, the first
  offer's outcome reads DECLINED/TIMEOUT on its own row (never 'enriched' into the second), and the
  bubble spoke both. — KNOWN one-time effect: an offer restored from a pre-#1069 snapshot (install mid-dash / crash-recovery across the upgrade) keeps its old store key, so its next re-render logs one spurious `OFFER_TIMEOUT "Replaced by new offer"` + re-speak — not a regression.
  - Confirmed: 0/2
  Triage source: first-line blame 2026-10-05 (`5ae8d68ea`, original README L72).

- [active] **Bubble lifecycle is visible in the shareable stream (#916):** after a dash, `grep -h 'Bubble' shareable.log`
  shows session start/end, `bubble post requested … canBubble=true` / `bubble post returned`, and activity
  create/start/stop/destroy; swiping the bubble's NOTIFICATION out of the shade logs `bubble notification
  removed (deleteIntent)` (dragging the chathead to the X does NOT — that is #1226). Use the sequence to
  assess whether a vanished bubble was dismissed, torn down, had `canBubble=false`, or the process died
  (an abrupt end without destroy is a clue, not proof).
  - Confirmed: 0/2
  Triage source: first-line blame 2026-10-05 (`6b8540319`, original README L96).

- [active] **Restaurant pickup wait records the real dwell (#1141):** on a restaurant pickup where you tap
  "Arrived at store" and wait, the pull's `pickup_records` dwell for that stop should reflect the real
  wait (minutes, not 0.0), and `PICKUP_ARRIVED` should appear within a few seconds of the
  "Stay in your car / Merchant notified of your arrival" screen.
  - Confirmed: 0/2
  Triage source: first-line blame 2026-10-05 (`5894e98e5`, original README L104).

- [active] **Resolved offer cards no longer wear a verdict banner (#864):** after an offer times out or is declined, expand
  its card in the HUD — the big ACCEPT/DECLINE advice banner is gone and only the header's outcome chip (Accepted /
  Declined / Timed out) states what happened; a LIVE card still shows the banner. Working = no resolved card can be
  misread as "declined" from the banner alone.
  - Confirmed: 0/2
  Triage source: first-line blame 2026-10-05 (`5ff90c7d0`, original README L110).

- [active] **Desk-first — #1133 (PR #1219): pay-less rows carry their car cost.** After the first launch on this
  build (projector v12 refold), the hub's "where your money went" card should show the gas/wear split
  for a week whose drops all carry a split (the guard no longer trips falsely), "kept" moves down by the
  receipt-less drops' mileage cost, and a receipt-less drop in a session drill-down reads pay `—` with
  the caption "pay not recorded — car cost only" above a negative net. Report a drop showing a negative
  net WITHOUT that caption, or a `$0.00` net on a drop that drove no miles.
  - Confirmed: 0/2
  Triage source: first-line blame 2026-10-05 (`a9959d413`, original README L120).

- [active] **8.99.20 privacy rules (#1127 / #1128 / #1139) + the store-signature block (#1022):** on the next dash, if any of
  these surfaces appears — the "Can't hand order to customer" steps page, the pickup "What do you want to do next?"
  sheet, the stacked-job "Select an Order" picker, or a return-order store-employee signature pad — the pull should
  show the first three as RECOGNIZED envelopes (`dropoff_customer_unavailable`, `pickup_resolution_options`,
  `pickup_order_picker`) with the customer name masked (`Contact [redacted:xxxx]` / `For [redacted:xxxx]`) and NO
  envelope at all for the signature pad (sensitive, dropped at the gate). Any of them landing in `UNKNOWN/` with a raw
  name is the thing to report.
  - Confirmed: 0/2
  Triage source: first-line blame 2026-10-05 (`967946055`, original README L133).

- [active] **Classify & draft a real UNKNOWN cluster (#1188, server v0.11.0):** on the VPN dashboard open a cluster from today's
  dash that has a trusted capture → "Classify & draft" → pick the class, mark two id anchors + the parse fields → Preview
  shows a JSON5 draft (or a refusal naming the weak node) → Save (TOTP) → the detail page shows the class chip and the
  draft link; paste the draft into the platform's surface file and run `AllMatchersSuite` — it should recognise the
  frame. Working = a draft that compiles and recognises its own capture on the first try for an id-anchored screen.
  - Confirmed: 0/2
  Triage source: first-line blame 2026-10-04 (`2e089bd25`, original README L142).

- [active] **Trusted UNKNOWN captures (#1200):** Share UNKNOWN captures ON → after a dash with UNKNOWN screens the status
  reads `Captures shared: <queued> queued · <n> sent last run` and the server's cluster detail page shows a wireframe (0/2).
  UNKNOWN screens get no rule redaction — only the marker backstops — so a capture can still contain customer details,
  and the server operator can read them. Trusted installs are the operator's own device today.
  - Confirmed: 1/2 (device half, the 10-04 dash on `e35dee8f`: `envelopesPaired=70 Unpaired=0`, Held/Spooled/Posted 70/70/70, zero `not_trusted`; server half — a wireframe on a cluster from that dash — still to eyeball)
  Triage source: first-line blame 2026-10-04 (`285ea1c85`, original README L149).

- [active] **Daily recognition health reaches the server (#1197, PR #1198).** Nothing to do on the dash itself;
  the day's RECOGNIZED/UNKNOWN counts per (platform, app version) are ledgered on-device and posted as
  ONE closed-day report by the first census run after UTC midnight. Next morning: the developer census
  status line should read `health_posted 1` (or `spool_empty` on a later run), and the ops dashboard's
  **Fleet health** table (`https://10.8.0.1:8443/ops/` over the tunnel) shows yesterday's UTC day for
  `doordash` with the phone's observed DoorDash version, Admitted ≈ the dash's recognized frames and
  UNKNOWN > 0, and the per-install disclosure lists the phone's 8-char prefix. A row that never appears
  (status `health_rejected n`) is the thing to report, with the rejection reason from the status detail.
  - Confirmed: 1/2 (desk + server 2026-10-05: fleet row 2026-10-04 · doordash · 8.100.12 admitted 544 / unknown 78; device `healthPosted=1`, no health WARN)
  Triage source: first-line blame 2026-10-03 (`72ff4782a`, original README L156).

- [active] **Census developer status line + Reset census identity (#1185).** In Developer settings the census
  block shows `Last run <ago> · <outcome token> · Queued: <n>`; after a dash with UNKNOWN screens it should
  read `uploaded <n>` within ~5 min with Queued back to 0. **Copy install id** puts the full UUID on the
  clipboard (paste it somewhere to confirm). Only if you want to exercise the reset: tap Reset census
  identity → Reset, confirm the prefix line changes to a NEW 8-char prefix within a minute (status
  `reset` → `enrolled`/`spool_empty`), then trust the new install server-side (the old one is orphaned).
  - Confirmed: 1/2 (2026-10-05: dev eyes — status line renders correctly; desk — one enrolment, server shows one trusted unrevoked install last seen 10-05)
  Triage source: first-line blame 2026-10-03 (`a670651fa`, original README L166).

- [active] **🆕 NEW — one consent decision per rule action; the prompt re-collects once (#1167).** The grant
  store's key shape changed (one key per (rule, action), pinned to every branch's binding
  definition), so the v2 migration clears every stored grant AND denial on the first launch of this
  build and the front door asks again. **How to tell it works:**
  1. First foreground after install: the consent prompt shows exactly FOUR rows — Accept offer,
     Decline offer, Confirm decline, Expand earnings — never a duplicate; Accept and Decline each
     carry the line "Covers 2 card layouts". Settings → Automation & Consent shows the same four.
  2. The log's rule-load line reads `reconciled 4 capabilit(ies) from rule load: granted=0 denied=0
     undecided=4 (stale grants=0)` on that first launch, then real counts after your answers; one
     `consent-schema migration ran: cleared stored grants and denials (key shape v2, #1167)` INFO line
     appears exactly once.
  3. Allow Decline once → on the next dash BOTH card layouts' Decline binds are covered: a decline
     automation on the Compose card (8.99.20) fires without a second prompt.
  - Issue: #1167. Confirmed: 1/2 (log half, desk 2026-10-05: 11 notification Decline taps each resolved `OFFER_DECLINED` on the matching hash; `confirm_decline` granted since 10-03 and skipped by the quick-decline setting; the prompt half needs dev eyes)
  Triage source: first-line blame 2026-09-30 (`ee5a9509b`, original README L225).

- [active] **🆕 NEW — chat header and chat preview are masked in captures (PR #1160 / #1145).** Desk check on a
  debug pull; nothing on the dash looks different. **How to tell it works:**
  1. A capture of the live DoorDash chat list (the conversation row with the customer's name and their
     last message) shows the `tvTitle` value as `[redacted:<4hex>]` — the 4 hex equal the first 4 of that
     delivery's `customerNameHash` — and the `tvLastMessage` value as plain `[redacted]`. No customer
     name or message text anywhere in the envelope.
  2. An UNKNOWN capture of any other sheet whose title renders under `tvTitle` ("Pick up order" and the
     like) shows ONLY that title NODE masked — all of its fields (text, description, …) as `[redacted]`;
     the rest of the X-Ray triage text is intact. That single lost node is the accepted cost (ADR-0011
     residual 11, on any platform whose id ends in `tvTitle`); anything more masked is a finding.
  - Confirmed: 0/2
  Triage source: first-line blame 2026-09-30 (`6e0033c7f`, original README L240).

- [active] **🆕 NEW — wide event receipt is an opt-in consent, asked BEFORE the accessibility grant (#1151).**
  The accessibility service receives window-change notices from other apps (what the #1148/#1152
  overlay path needs) ONLY after you allow it, and the question now comes first in the setup
  sheets. **How to tell it works:**
  1. Fresh install: the "Let DashBuddy notice windows from other apps" step appears BEFORE the
     accessibility-service request (Allow / Don't allow, no "Not now"). The accessibility card never
     appears before you answer. On an install where the service was already on, the step still
     appears once.
  2. Allow → the accessibility step follows; once the service is on, exactly one
     `Event receipt: wide=true` INFO line (tag `Pipeline`) at connect, and
     `WINDOWS_CHANGED (coalesced …)` DEBUG lines begin in `app.log`.
  3. Release, Don't allow → the accessibility step still follows; at connect the line reads
     `wide=false` and no `WINDOWS_CHANGED` lines appear.
  4. Debug build, Don't allow → NO accessibility request; the "Wide event receipt is off" notice
     replaces every screen (a bubble deep link or the Sunday plan notification lands on it too). If
     the service was already on, it is turned off (`event receipt declined on a debug build —
     disabling the accessibility service`) and the bubble stops. **Exit** closes the app; **Open
     Automation & Consent** shows that screen inside the notice — turning "Notice windows from other
     apps" on restores the app and the setup sheet asks you to turn the service back on.
  5. Turning the switch off in Settings logs `wide=false` and the `WINDOWS_CHANGED` lines stop (on a
     debug build the service is also turned off).
  6. The phone is Android 13+ (a Pixel 7): no `may not take effect on Android 11` WARN and no caveat on
     the switch. (On an Android 11 device both appear — a known limitation, see the sensor reference.)
  7. Debug build, a bubble deep link that arrives while the notice is up, then a rotation, then
     Allow: the app opens the linked screen exactly once.
  - Issue: #1151. Confirmed: 1/2 (2026-09-30 smoke test on `1fe9243f`: items 1–2 — the step came first and was allowed, `wide=false` → `wide=true`, `WINDOWS_CHANGED` lines began; items 3–7 not exercised)
  Triage source: first-line blame 2026-09-30 (`e8205c8ac`, original README L252).

- [active] **🆕 NEW — Uber offers drawn as an overlay are captured, whatever has focus (#1152).** Uber draws its
  offer as a floating system overlay above other apps; it used to be read only when it had focus.
  Now a large system-layer window from an ENABLED overlay platform is a candidate (size ≥ ¼ of the
  screen, package verified). **Needs Uber enabled and an Uber offer arriving while DoorDash (or
  another app) is on screen.** **How to tell it works:**
  1. An `uber.screen.offer` frame is recognized whether or not the bubble is active and whether or
     not the overlay has focus — look for `overlaySnapshots=` RISING in the `PipelineStats` summary
     (it stays 0 when the overlay had focus: that frame comes through the active-root path).
  2. The small Uber puck is NEVER the frame — `overlayRejected{TOO_SMALL=…}` counts it, and no
     UNKNOWN capture shows the puck's tree.
  3. No DoorDash toast is ever the frame (no DoorDash `TYPE_SYSTEM` capture);
     `overlayRejected{NOT_OVERLAY_PLATFORM=…}` may rise when the notification shade is pulled down.
  4. While an Uber offer is up over DoorDash, DoorDash frames PAUSE — expected (the overlay on top is
     the frame). The app follows Uber while the card is up: anything that happens in DoorDash during
     those seconds is not seen, and a DoorDash pay reading that was still settling is dropped. Once
     the offer is dismissed, DoorDash frames resume and the state should catch up — note any DoorDash
     step that never shows up. (Seeing two platforms at once is the known multiplatform gap, #251 /
     #826.) With Uber DISABLED, an Uber offer must NOT pause DoorDash frames at all.
  5. `overlayScans=` in the summary shows how often the window list is read for this feature.
  - Issue: #1152. Confirmed: 0/2
  Triage source: first-line blame 2026-09-30 (`45b08c27e`, original README L279).

- [active] **🆕 NEW — the dash-controls sheet recognizes as `timeline` on a per-offer dash with no task (#1165).**
  Open the dash controls (the "Current dash" sheet with Pause orders / End dash) on a dash with no
  scheduled end and no active task. **How to tell it works:** the capture lands under
  `accessibility.window/timeline/`, not `UNKNOWN/`; its parse carries `sessionEarnings` equal to the
  "This dash" figure and empty `tasks`; the mid-inflate frame (title alone) stays UNKNOWN; the dash is
  still `Online` while the sheet is up (the rule asserts `modeHint: online`).
  - Issue: #1165. Confirmed: 1/2 (desk 2026-10-05: all 7 dash-controls frames recognized `timeline`, all while Online)
  Triage source: first-line blame 2026-09-30 (`c7510dcb5`, original README L315).

- [active] **🆕 WATCH — the mid-job ADD-ON offer card is not recognized on 8.98.5+ (#1121).** Its money is a delta
  (`+$10.50`) and its route line is a delta (`+1 stop (1.5 mi) • +1 min`), so `offer_popup` misses it and it
  lands in `side_nav_drawer`: no `OFFER_RECEIVED`, no verdict, no bubble, no voice. **On-dash:** if an add-on
  offer appears mid-job, note whether the app said anything at all. **Desk:** any `+$` / `+N stop` text in an
  `offer_popup`, `side_nav_drawer` or UNKNOWN capture is a sighting — record its folder.
  - Issue: #1121. Confirmed: 0/2 (a watch item — it retires when the rule ships and is validated)
    - desk 09-26: UNEXERCISED — no `+$` / `+N stop` text anywhere in the pull (8.98.5 or 8.99.20).
    - desk 09-27: UNEXERCISED — 0 `+$` / `+N stop` text anywhere in the pull.
    - desk 09-29: UNEXERCISED — 0 `+N stop` text; the `+$1`/`+$2` hits are the "Very busy" boost badge and idle-map
      peak-pay chips, not add-on deltas.
  Triage source: first-line blame 2026-09-26 (`e087c3e4a`, original README L357).

- [active] **🆕 NEW — the 8.98.5 drop-off sheet masks on every render that keeps a stable row (#1122 + #1123).** The 09-20 pull
  shipped two Pledge leaks from the same sheet: a raw customer name in the bottom bar of a
  `dropoff_pre_arrival` envelope (the id-less node the rule's id-anchored entry never saw — #1123),
  and three header-less / partial renders of the workflow sheet that fell UNKNOWN with the whole
  address block, the quoted note and the name raw (#1122). Nothing changes on the phone: the sheet
  rule still declares no flow, so an ordinary "leave it at the door" drop-off must narrate, bubble
  and complete exactly as before. Anchors: either prism CTA, any `Apt/Suite` spelling, the
  Call/Message pair, or `Leave it at the door` / `Hand it to recipient` — a render with NONE of
  those still ships raw (extend the vocabulary from the capture's node shape). **Desk, after the pull:**
  1. `grep -rl 'drop_off_workflow_host_fragment' captures/**/UNKNOWN/` must return only the known
     text-free loading skeletons — any hit with a co-present text node is a render the widened anchor
     still misses (attach the frame's node SHAPES to #1122, never the values).
  2. Every `dropoff_pre_arrival` AND `dropoff_workflow_sheet` envelope: the bottom-bar name slot
     (the id-less `TextView` between the `Settings` and `Safety` icons) reads `[redacted:<4hex>]`,
     and the SAME customer carries the same 4 hex on both rules' envelopes.
  3. On the header-less sheet envelopes (no `Deliver to`, no `Continue`): a hashed street line, PLAIN
     `[redacted]` for city/ST/ZIP, the unit and the quoted note; `Call`/`Message`/`Directions`/
     `Leave it at the door` kept raw.
  - Confirmed: 1/2 (desk 2026-10-05: check 1 passes)
    - desk 09-26: UNEXERCISED — build `71bacc8c` predates PR #1125. The #1122 shape recurred once on 8.98.5
      (UNKNOWN 09-23 11:30:13: street, city/ZIP, unit, quoted note + gate code, bare code and the bottom-bar name raw;
      purged). 9 other UNKNOWN host-fragment hits are text-free skeletons. The recognized `dropoff_workflow_sheet`
      envelope (09-23 11:38:20) masks everything incl. a hashed bottom-bar slot; no `dropoff_pre_arrival` rendered the
      bottom bar. The phone is on `1b596f74` now, so the next pull is the first that can measure this.
    - desk 09-27 (first run of PR #1125, `1b596f74`, DoorDash 8.99.20): CLEAN, not a confirmation. Check 1 passes (4
      UNKNOWN host-fragment hits, all text-free loading skeletons 0.3–0.6 s before a recognized `dropoff_pre_arrival`).
      Checks 2–3 UNEXERCISED: on all 14 `dropoff_pre_arrival` + 2 `dropoff_handoff` envelopes the bottom-bar slot
      between Settings and Safety is an empty View (no name rendered); 0 `dropoff_workflow_sheet` frames, no
      header-less render. Every pre-arrival grades correctly (same hex per customer across frames). No
      `Apt/Suite`-only UNKNOWN; 0 `Building Name`. Still 0/2.
    - desk 09-29 (2nd run of PR #1125, `1b596f74`, two dash days): CLEAN, not a confirmation. Check 1: 0 UNKNOWN
      host-fragment hits. Checks 2–3 UNEXERCISED again: on all 11 `dropoff_pre_arrival` + 4 `dropoff_handoff` envelopes
      the Settings→Safety slot is a Button + two text-free Views (no name rendered); 0 `dropoff_workflow_sheet`; no
      header-less render. Every Deliver-to hex equals that drop's customerHash prefix (8 customers). Still 0/2.
  Triage source: first-line blame 2026-09-21 (`81d0f5531`, original README L323).

- [active] **🆕 NEW — the 8.97.8 "Drop off steps" wrapper masks the customer's instruction body (#1107 — PR
  #1110).** The wrapper page 8.97.8 routes leave-at-door through shipped a gate code, cross streets
  and a landmark raw to UNKNOWN capture on 09-13 (that entry's Bug 1). It is now recognized as
  `dropoff_step_instructions` with its `description_text_view` body plain-masked whole (and the id on
  `ID_MARKERS`). **On-dash:** nothing should change — no bubble, no narration, no state move; the rule
  declares no flow. **Desk:** the frames land in `dropoff_step_instructions/`, never `UNKNOWN/`; on an
  order that carries a customer instruction, the `description_text_view` value must read a PLAIN
  `[redacted]` while `step_title` / `step_description` stay raw (DoorDash's own vocabulary).
  - Issue: #1107. Confirmed: 0/2
    - desk 09-20: RECOGNITION HALF CONFIRMED — 5 `dropoff_step_instructions` envelopes (09-19), none in
      `UNKNOWN/`, first sighting of the folder. **Redaction half UNEXERCISED:** none of the five orders
      rendered a `description_text_view` node at all, so the `plainMask` never had anything to mask. It
      needs a drop whose customer actually left an instruction body.
    - desk 09-21: UNEXERCISED — the `dropoff_step_instructions/` folder is absent from the pull; the
      "Drop off steps" wrapper page did not render at all on DoorDash **8.98.5** this dash, so
      neither half was touched. Worth checking whether 8.98.5 still routes leave-at-door through it.
    - desk 09-26: UNEXERCISED — no `dropoff_step_instructions` folder and no "Drop off steps" /
      `description_text_view` node anywhere in the pull, on 8.98.5 or 8.99.20. The wrapper may be gone on these builds.
    - desk 09-27: UNEXERCISED — no `dropoff_step_instructions` folder, 0 `description_text_view` / "Drop off steps"
      anywhere (8.99.20, a whole dash).
    - desk 09-29: UNEXERCISED — 0 `dropoff_step_instructions` / `description_text_view` / "Drop off steps" in 702
      envelopes (8.99.20).
  Triage source: first-line blame 2026-09-20 (`74239b425`, original README L368).

- [active] **🆕 NEW — a return order must record its return pay (#998).** Fielded 09-14: an alcohol delivery
  refused at the door became a return order; DoorDash priced the return leg separately (`Return pay
  $10.93`, its own receipt 10 min later) and the row kept only the original base pay. **On-dash:** if a
  delivery is refused or cancelled at the door and DoorDash asks you to return the order, note the
  `Return pay` figure on its breakdown screen and the total on the return's own receipt. **Desk:** the
  dash's `Σ realizedPay` must reach `reportedEarnings`; a delivery row holding only the original base pay
  while the summary is higher by exactly the return pay is the bug.
  - Issue: #998. Confirmed: 0/2
    - desk 09-20: NOT EXERCISED — no refused or returned delivery in the four-day window.
    - desk 09-21: NOT EXERCISED — no refused or returned delivery on the 09-20 dash either.
    - desk 09-26: NOT EXERCISED — the "Can't hand order to customer" page (with its "Return the order to merchant"
      step) rendered twice (09-23 09:21 on 8.98.5; 09-24 16:22 on 8.99.20 — the latter leaks the name, #1127), but
      both drops completed normally and no return happened.
    - desk 09-27: NOT EXERCISED — no refused or returned delivery; Σ realizedPay = reportedEarnings to the cent.
    - desk 09-29: NOT EXERCISED — no refused/returned delivery; Σ realizedPay = reported to the cent on all 7 summary
      sessions ($241.59).
  Triage source: first-line blame 2026-09-15 (`5e1bf3416`, original README L391).

- [active] **🆕 NEW — a stacked job's two drops must not be charged the same miles twice (#1108).** **Desk:** per
  session, `Σ realizedMiles` must be ≤ `lastOdometer − startOdometer`; and no
  `delivery_records.realizedMinutes` may be negative. Watch specifically for a stacked job where the
  second drop-off card appears without an arrival frame (09-13 session 499: 24.32 mi of legs on an
  18.70 mi span, and a −7.1 min row).
  - Issue: #1108. Confirmed: 0/2
    - desk 09-20: CLEAN, no recurrence (not a confirmation — the causing shape did not repeat):
      `Σ realizedMiles ≤ (lastOdometer − startOdometer)` on all 15 sessions, largest gap 0.03 mi; zero
      negative `realizedMinutes`; the one stacked job (…-594, two drops) shows legs 21.98 vs span 21.98.
    - desk 09-21: CLEAN, still not a confirmation — `Σ realizedMiles ≤ (lastOdometer −
      startOdometer)` on every session (largest gap 0.32 mi), zero negative `realizedMinutes`, and
      the day's one stacked job (…-674) shows legs 7.68 + 6.22 = 13.90 mi, both non-zero. The
      causing shape did not recur. (That job DID lose $28.51 — to #1120, a different mechanism.)
    - desk 09-26: CLEAN, still not a confirmation — `Σ realizedMiles ≤ span` on every session (largest gap 2.44 mi,
      session 707), zero negative `realizedMinutes`, and no stacked job in the window.
    - desk 09-27: CLEAN, still not a confirmation — Σ realizedMiles 75.83 ≤ span 75.85 mi, zero negative
      `realizedMinutes`, no stacked job.
    - desk 09-29: CLEAN, still not a confirmation — Σ realizedMiles ≤ span everywhere; the one stacked job (…-777,
      two stores) has both arrivals and legs 8.91 + 2.13 mi; 0 negative `realizedMinutes`.
  Triage source: first-line blame 2026-09-15 (`5e1bf3416`, original README L408).

- [active] **🆕 NEW — unassigning at the store must not record a pickup (#301).** **On-dash (deliberate):** if you
  ever have to unassign at a store, take the `Help → I have an issue → Unassign order` path. **Desk:**
  one `TASK_UNASSIGNED`, no `PICKUP_CONFIRMED` for that task, no `pickup_records` dwell row, and the
  accepted offer resolved rather than left an orphan (09-15 session 568 recorded a phantom
  `PICKUP_CONFIRMED` with a 17.4-min dwell and an orphan accept).
  - Issue: #301. Confirmed: 0/2
    - desk 09-20: NOT EXERCISED — zero `TASK_UNASSIGNED` in the window.
    - desk 09-21: NEARLY exercised, still not a test — the dev reached `pickup_select_issue` →
      `pickup_resolution_options` at 12:38:53 (the sheet offering "Unassign with no pay" /
      "Contact support") and took **Contact support**. Zero `TASK_UNASSIGNED`, zero phantom
      `PICKUP_CONFIRMED`. Next time the situation arises, take the Unassign path.
    - desk 09-26: NOT the deliberate path, but the same defect class fired twice via SUPPORT-CHAT removal — jobs
      …-733 (09-23 15:14, after a "Missed Delivery" push) and …-737 (09-23 16:03, a new offer at the store) each
      recorded a phantom `PICKUP_CONFIRMED` plus a `pickup_records` dwell row (16.1 / 14.5 min), committed by a 10 s
      `GRACE_COMMIT`, with zero `TASK_UNASSIGNED`; offer d58fc055 left unlinked. The 8.99.20 "What do you want to do
      next?" sheet (`Unassign with no pay` / `Order not found in system`) is UNKNOWN and leaks a name (#1128).
      Comment on #301.
    - desk 09-27: NOT EXERCISED — 0 `TASK_UNASSIGNED`, 7 `PICKUP_CONFIRMED` = 7 real jobs, no support removal.
    - desk 09-29: NOT the deliberate path; the support-CHAT removal fired a 3rd time (09-27 16:10, session 790). 0
      `TASK_UNASSIGNED`; no phantom PICKUP_CONFIRMED this time because the next accept (a different H-E-B) REUSED
      the abandoned task …-793 (#810 shape): PICKUP_ARRIVED re-emitted with the first store's arrival, the real
      16:25:10 arrival lost, dwell 55.5 vs ~39.9 min; offer d3c03872 left ACCEPTED → JOB_ACCEPT_MISMATCH
      inconclusive. New anchor: DoorDash's own "You've been waiting a while — would you like to unassign?" screen
      (UNKNOWN 09-27 11:59:34, `order_self_unassign_*` ids). Comment on #301. 0/2.
  Triage source: first-line blame 2026-09-15 (`5e1bf3416`, original README L428).

- [active] **🆕 NEW — the offer card's Decline target must stay bound on 8.97.8.** **On-dash:** when the heads-up
  notification's **Decline** button does nothing (you have to decline in the DoorDash card yourself),
  note it. **Desk:** `bindShortfall{doordash.screen.offer_popup#declineButton}` should be 0 and
  `No 'declineButton' target bound` should not appear (09-13→15: 3 such WARNs plus a
  `offer_popup_confirm_decline … 'confirm…' resolved no node` ParseHealth line — anchor rot on 8.97.8).
  - Confirmed: 0/2
    - desk 09-20: **BROKEN, worse, and now with a mechanism** —
      `bindShortfall{offer_popup_confirm_decline#confirmDeclineButton=25}` (was 3), an earlier process
      line carrying `offer_popup#declineButton=2`, two anchor-rot `ParseHealth` WARNs, one
      `No 'declineButton' target bound`. The fielded confirm sheet
      (`offer_popup_confirm_decline/2026-09-16_18-40-14-504…83204c.json`) carries **no `Decline offer`
      node at all** — only copy lines. PR #1115 (merged `aab8d960`) re-binds the Compose target, but
      the research note `~/dashbuddy/research/compose-click-capture-2026-09-20.md` finds that a HUMAN
      tap on a Compose control emits no `TYPE_VIEW_CLICKED`, so **no click envelope can arrive to be
      classified** — hypothesis, unconfirmed on a post-#1115 build. **On the next dash this is the
      thing to watch:** does the heads-up **Decline** button work again, and does a hand-decline land
      as `OFFER_DECLINED` or as a timeout? (Detail in the 2026-09-16→19 entry, Bug 3.)
    - desk 09-21 (first pull on a post-#1115 build, DoorDash 8.98.5): **the BIND half is FIXED** —
      `bindShortfall{offer_popup#declineButton}` and
      `{offer_popup_confirm_decline#confirmDeclineButton}` are **both 0** (were 2 and 25), no
      `No 'declineButton' target bound` line, no anchor-rot `ParseHealth` WARN, 8 × `Single verified
      candidate for decline_offer on doordash … clicking it`, and 8.98.5 renders a real `Decline
      offer` node on the confirm sheet again. The **confirm** half could not be measured: the
      confirm-decline capability is un-granted on the install (12 × `Denied confirm_decline … (fail
      closed)`), so every decline needed a manual confirm tap. Still 0/2 until a dash where that
      capability is ALLOWED shows the heads-up Decline completing end-to-end.
    - desk 09-26: the BIND half holds for a second pull — 33 × "Single verified candidate for decline_offer …
      clicking it", 0 "No 'declineButton' target bound", no `offer_popup` bindShortfall. The confirm half is still
      UNMEASURED: 49 × "Denied confirm_decline … (fail closed)" — the capability is still un-granted (and the
      `1b596f74` reinstall reset the store again). 0/2.
    - desk 09-27: BIND half holds (3rd pull) — 6 × "Single verified candidate for decline_offer … clicking it" for 6
      HUD declines, 0 "No 'declineButton' target bound", no bindShortfall at all. Confirm half still UNMEASURED:
      14 × "Denied confirm_decline … (fail closed)" — the capability was not re-Allowed after the `1b596f74`
      reinstall. 0/2.
    - desk 09-29: BIND half holds (4th pull) — 21 HUD declines → 21 "Single verified candidate for decline_offer …
      clicking it", 0 unbound. One benign `bindShortfall{offer_popup_confirm_decline#confirmDeclineButton=1}` (a
      sheet render 1 s before that card's OFFER_EXPIRY). Confirm half still UNMEASURED: 46 × "Denied confirm_decline"
      — the dev has NOT re-Allowed it since the 09-26 reinstall. 0/2.
  Triage source: first-line blame 2026-09-15 (`5e1bf3416`, original README L453).

- [active] **🆕 NEW — a dash you start while the last dash's receipt is still on screen must not inherit its
  money.** Fielded twice on 09-10/09-11: the previous dash's `delivery_summary_collapsed` sheet was
  still up 14 ms (and 251 ms) after `DASH_START`, the settle gate parked its `sessionEarnings` read
  inside the NEW session, and a `SESSION_PAY_SETTLE` three seconds later committed it — session 483
  recorded `reportedEarnings = $40.14` on a two-minute dash with zero deliveries. The task/job
  lifecycle was completely inert (no completion, no re-price, no bubble), so this is a
  session-total-only defect. **On-dash (deliberate):** finish a delivery, leave the receipt sheet on
  screen, tap **Dash Now** without dismissing it, then go Offline within a minute or two without
  taking an order. **Desk:** that session's `reportedEarnings` must be NULL (it had no deliveries) —
  a figure equal to the PREVIOUS dash's total is the bug. Also check for a duplicate
  `Delivery - <amt>.png` / `DeliveryBreakdown - <amt>.png` screenshot pair timestamped inside the new
  dash, and confirm no `DELIVERY_RECEIPT_REPRICE` is attributed to the new session.
  - Issue: #1103. Confirmed: 0/2
    - **fix landed (PR #1111, #1103):** the receipt's wheel is admitted to the settle gate only
      while this session owns the receipt's job (`activeJob` live, or `lastClosedJobReceipt` set —
      both cleared by `endSession`). A refused read prints `settle gate: receipt running-total read
      refused` at DEBUG under the `StateMachine` tag; on the repro above that line IS the fix firing,
      so grep for it alongside the NULL `reportedEarnings`.
    - desk 09-15: no-regression — all three `early_offline` sessions report NULL and exactly one `SESSION_PAY_SETTLE` fired in three days (inside its own session). The causing shape (a receipt sheet up at `DASH_START`) did not recur, so this is a clean negative, not a repro.
    - desk 09-20 (first pull WITH PR #1111 on the device): UNEXERCISED, clean negative — `settle gate: receipt running-total read refused` = 0 hits (the causing shape did not recur, and no FALSE refusal either); all five `early_offline` zero-delivery sessions report NULL, including 646 which started 3 min 3 s after session 630 closed on $156.34; six `SESSION_PAY_SETTLE` timers fired, each inside its own session committing that session's own figure; no duplicate `Delivery - <amt>.png` pair straddles a dash boundary and no `DELIVERY_RECEIPT_REPRICE` is attributed to a following session.
    - desk 09-21: UNEXERCISED, clean negative a second time — `settle gate: receipt running-total
      read refused` = 0 hits (causing shape absent, and no FALSE refusal); both `early_offline`
      zero-delivery sessions (660, 685) report NULL `reportedEarnings`; no `Delivery - <amt>.png` /
      `DeliveryBreakdown - <amt>.png` pair straddles a dash boundary; no `DELIVERY_RECEIPT_REPRICE`
      is attributed to a following session. Only the deliberate on-dash repro can move this.
    - desk 09-26: UNEXERCISED, clean negative (3rd) — 0 "receipt running-total read refused"; all three zero-drop
      sessions (718, 732, 736) report NULL; the 3 `SESSION_PAY_SETTLE` fires were each inside their own session;
      back-to-back starts 707→714 (38 s) and 746→750 (20 s) each carry only their own total.
    - desk 09-27: UNEXERCISED, clean negative (4th) — 0 "receipt running-total read refused"; the dash started 10:43
      from `waiting_for_offer` (no receipt on screen); 5 `SESSION_PAY_SETTLE` fires all inside session 754;
      reportedEarnings = the dash's own $209.22.
    - desk 09-29: UNEXERCISED, clean negative (5th) — 0 "receipt running-total read refused"; the four zero-drop
      sessions (789, 802, 803, 811) report NULL; back-to-back starts 776→785 (26 s after a dash_summary) and 785→789
      carry only their own totals.
  Triage source: first-line blame 2026-09-13 (`bd4c8a71b`, original README L492).

- [active] **🆕 NEW — a decline you confirm fast must still be recorded as a decline, not a timeout.** Five of
  the 09-10/09-11 pull's seven `OFFER_TIMEOUT`s were real declines: the dasher's tap on the confirm
  sheet's **Decline offer** button arrived 62–171 ms before that sheet's own frame was admitted, so
  the click rule (`screenIs: offer_popup_confirm_decline`) did not match, the click fell to UNKNOWN,
  and `OFFER_EXPIRY` resolved the offer as a timeout. **On-dash (deliberate, both ways):** on one
  offer, tap Decline in the heads-up notification and then confirm **as fast as you can**; on the
  next, wait a full second before confirming. **Desk:** both must appear as `OFFER_DECLINED` in
  `app_events`; a `OFFER_TIMEOUT` with a preceding `OfferActionReceiver: decline_offer` line, or with
  a `Denied confirm_decline` WARN in the seconds before it, is the bug. Cross-check the Offers tab's
  accept/decline/timeout bar against what you actually did.
  - Issue: #1104. Confirmed: 1/2 (desk 2026-10-05: one `OFFER_TIMEOUT` beside a `Denied confirm_decline` on 10-01, ~0.3 s before the card's own countdown end — by design; no lost decline on 10-03/10-04)
    - desk 09-15: SECOND SIGHTING, not a confirmation — 9 of 12 `OFFER_TIMEOUT`s were declines (confirm tap 24–229 ms before the confirm sheet's admitted frame); the sheet itself WAS admitted in time every time (the `Denied confirm_decline` WARN proves it), so the race is between the click and window sub-pipelines. Dev reported it independently the same day. Counts on #1104.
    - desk 09-20: IMPROVED, not closed, and the MECHANISM HAS MOVED — 1 `OFFER_TIMEOUT` in 23 offers (09-16/17), against 9-of-12 on the 09-15 pull; the one that fired was still a real decline (offer `0c4fc920`, 09-16 18:40: `initial_decline` click → UNKNOWN confirm click → `offer_popup_confirm_decline` screen → `Offer Timed Out!`). But **zero** clicks classified `confirm_decline` in 1 440 envelopes, so PR #1110's `screenIs`-array widening cannot be shown to have fired at all: on 8.97.8 the confirm sheet renders **no button label**, and a human tap on the Compose card emits no click event at all (see the decline-bound item and the 09-16→19 entry's Bug 3).
    - desk 09-21: UNEXERCISED — `aab8d960` predates PR #1118, so all 22 offers logged
      `OFFER_TIMEOUT` (10 real accepts + 12 real declines, each decline carrying a `Denied
      confirm_decline` WARN or an `OfferActionReceiver: decline_offer` line). Zero clicks classified
      `accept_offer` / `decline_offer` / `confirm_decline` in 125 click envelopes — the Compose
      no-click-event finding holds on 8.98.5, which is exactly why #1118 infers from transitions.
    - desk 09-26: the tap race is MOOT on this build — #1118's sheet inference caught every decline whose exit
      landed within 15 s (44/44), and zero confirm-button click envelopes exist to race. The remaining
      decline→TIMEOUT losses are the flow-less-dwell class (Dash Control), not the tap race. Still 0/2 on the
      deliberate fast/slow repro.
    - desk 09-29: the deliberate fast/slow repro is not identifiable in the pull. 42/42 declines inferred from the
      confirm sheet (1.55–10.59 s); the 2 lost declines are the flow-less `on_dash_map` dwell (20.46 s; 15.12 s ending
      in a replace), not the tap race. 0/2.
  Triage source: first-line blame 2026-09-13 (`bd4c8a71b`, original README L527).

- [active] **🆕 NEW — #1054 — a pause survives a relaunch: the HUD stays PAUSED, and the dash still ends on
  its own when the countdown runs out.** Two behaviours that used to be broken in opposite
  directions. A graced resume is now DROPPED on restore (it is 8 s of *un-contradicted* observation,
  and dead process time is not that), so a relaunch mid-pause must NOT come back Online — a phantom
  resume, or a brand-new dash appearing with no online screen behind it, is the bug. And the
  pause-safety deadline is now real state rather than a coroutine in memory, so it is re-armed on
  restore: before this, a restore into Paused left the dash with no timer of any kind and a pocketed
  phone kept the session alive indefinitely, which the next morning's dash then RESUMED.
  **On-dash:** pause the dash, force-stop DashBuddy while the pause sheet is up, relaunch. The HUD
  must read PAUSED, and stay that way until you actually bring DoorDash back online. Then do it
  again and simply let the DoorDash countdown run out with the app relaunched but idle. **Two
  separate things happen, about ten seconds apart** — the safety net fires ~1 s after the countdown
  reaches zero and takes the dash Paused→Offline, which then arms the ORDINARY 10 s `SESSION_END`
  grace, so the dash actually ends ~11 s after zero. **Desk, after the pull:** a `DASH_STOP` in
  `app_events` whose payload `endedAt` is ≈ countdown + 1 s (the safety transition) while the row's
  own `occurredAt` is ≈ countdown + 11 s (the graced commit) — two assertions, not one; no
  `DASH_START` in the seconds after a recovery; and no `Timer Expired` WARN for a
  `MODE_RESUME_COMMIT` or `SESSION_PAY_SETTLE` after a restart (those pendings are dropped on
  restore, and a replayed arm for them is never executed at all).
  - Confirmed: 0/2
    - desk 09-09: NOT EXERCISED — zero `DASH_PAUSED`/`DASH_RESUMED` on 09-08, no `MODE_RESUME_COMMIT` /
      `SESSION_PAUSED_SAFETY` timer in the pull.
    - desk 09-13 (3rd pull): NOT EXERCISED — zero `DASH_PAUSED`/`DASH_RESUMED`/`MODE_RESUME_COMMIT`/`SESSION_PAUSED_SAFETY`.
    - desk 09-15 (4th pull): NOT EXERCISED — zero pause/resume/safety events; one process for the entire three-day window.
    - desk 09-20 (5th pull): NOT EXERCISED — zero `DASH_PAUSED` / `DASH_RESUMED` / `MODE_RESUME_COMMIT` / `SESSION_PAUSED_SAFETY` in four days, no force-stop mid-pause, no `Recovery re-armed`.
    - desk 09-21 (6th pull): NOT EXERCISED — the day's ONE pause (11:52:50, `remainingText 34:58`)
      was hand-resumed two seconds later and committed by `MODE_RESUME_COMMIT` at 11:53:06 →
      `Session grace resume`: the ordinary path, not the force-stop exercise. Zero
      `SESSION_PAUSED_SAFETY`, no restart mid-pause.
    - desk 09-26: NOT EXERCISED — two `DASH_PAUSED` (09-23 11:40:39, the pause-after-delivery on the last drop, dash
      ended on the summary 17 s later; 09-24 16:24:49, resumed, `MODE_RESUME_COMMIT` fired 16:25:35.946). No restart
      mid-pause, no `SESSION_PAUSED_SAFETY`.
    - desk 09-27: NOT EXERCISED — 0 `DASH_PAUSED` / `MODE_RESUME_COMMIT` / `SESSION_PAUSED_SAFETY`.
    - desk 09-29: NOT EXERCISED — 2 `DASH_PAUSED` (09-28 11:57:19, 11:58:56), both hand-resumed via
      `MODE_RESUME_COMMIT`; no force-stop, no `SESSION_PAUSED_SAFETY`.
  Triage source: first-line blame 2026-09-06 (`5ae03a9eb`, original README L596).

- [active] **🆕 NEW — #1054 — a grace that lapses while nothing is on screen still commits, and survives a
  restart.** Both older grace timers (`GRACE_COMMIT` for a dash end / task retire,
  `MODE_RESUME_COMMIT` for a resume out of Paused) are armed for exactly `deadline − now` but their
  fire is stamped with the wall clock, so the ordinary case lands ON the deadline — which a strict
  `>` expiry ignored, with nothing left to wake the pending. And crash recovery restored those
  pendings without re-arming anything at all. **On-dash:** go offline (or let the dash summary
  come up) and then leave the app backgrounded, untouched, with the phone idle — the dash should
  end on its own about a grace window later (~10 s from an idle/offline screen, ~2.5 s from the
  summary), NOT the next time you happen to open DoorDash. Then the harder half: while a dash-end
  grace is open, force-stop DashBuddy and relaunch it — the pending end must still commit within
  its window rather than hanging around. **Desk, after the pull:**
  1. `grep "Recovery re-armed" *.log` — one INFO line under the `StateMachine` tag per recovery
     that restored a live grace, carrying a count only. Absent on a recovery with no pending
     grace, which is the normal case.
  2. `SELECT eventType, occurredAt, payload FROM app_events WHERE eventType='DASH_STOP'` — the
     backgrounded end's payload `endedAt` is the ARM time (the moment the offline/summary screen
     appeared, `pend.since`, unchanged by #1054), while the row's own `occurredAt` is the COMMIT
     observation's time (#732). So `occurredAt ≈ endedAt` + the grace window (~10 s from an
     idle/offline screen, ~2.5 s from the summary) — **not** hours later when the app was next
     opened, which is what the bug produced.
  3. No dash left with a live `session` in the HUD after the app has been closed on an offline
     screen for minutes.
  - Confirmed: 0/2
    - desk 09-09 (the 09-08 dash on `8028691a`): NOT EXERCISED — both restores were `no tail` with no
      session, no force-stop mid-grace, no backgrounded end. Healthy baseline only: both summary-ended
      `DASH_STOP`s show `occurredAt − endedAt` = 2.508 s / 2.575 s (the ordinary 2.5 s window).
    - desk 09-13 (3rd pull): NOT EXERCISED — no backgrounded end, no force-stop mid-grace; the only restart (09-11 22:34, after the last dash) replayed 4 observations with no session and correctly logged no `Recovery re-armed`. Baseline healthy: six summary ends at 2.501–2.506 s, two early-offline ends at 10.003/10.005 s.
    - desk 09-15 (4th pull): NOT EXERCISED — one process for three days; the single startup replayed 2 observations with no session and correctly logged no `Recovery re-armed`. Baseline healthy: eleven summary ends 2.501–2.508 s, three early-offline ends 10.002–10.003 s. Fourth consecutive pull — only the two on-dash exercises (background an offline screen; force-stop mid-pause) can move this.
    - desk 09-20 (5th pull): NOT EXERCISED — no backgrounded end, no force-stop mid-grace; neither of the two process starts (09-15 23:51, 09-17 23:56) restored a live grace, so `Recovery re-armed` is correctly absent. Baseline healthy: eleven summary ends at 2.502–2.506 s, four early-offline ends at 10.002–10.004 s, no end drifted.
    - desk 09-21 (6th pull): NOT EXERCISED — one process for the whole day, no backgrounded end, no
      force-stop mid-grace, no `Recovery re-armed` (correctly — nothing to restore). Baseline
      healthy: three summary ends at 2.502 / 2.504 / 2.504 s, two early-offline ends at 10.003 s.
    - desk 09-26 (7th pull): NOT EXERCISED — one mid-dash restart (09-23 15:06:29, session 732 Online, coinciding
      with the DoorDash 8.99.20 update) replayed 3 obs with no live grace, so "Recovery re-armed" is correctly
      absent and the session continued. Baseline healthy: 8 summary ends at 2.501–2.513 s, 3 early-offline ends at
      10.002–10.004 s.
    - desk 09-27 (8th pull): NOT EXERCISED — one process; startup replayed 1 obs with no session (no "Recovery
      re-armed", correct). Summary end at 2.501 s.
    - desk 09-29 (9th pull): NOT EXERCISED — one process for the whole window, no recovery; ends healthy (7 summary
      2.501–2.503 s, 4 early-offline 10.003–10.010 s). BUT the item's own symptom appeared by a DIFFERENT mechanism:
      session 802 — End dash tapped 10:59:13, no screen admitted for 31.7 min, the dash ended only when DoorDash was
      reopened (11:30:54 → commit 11:31:04). Nothing was armed, so not the strict-`>` bug → filed as #1140.
  Triage source: first-line blame 2026-09-05 (`31764c1cd`, original README L553).

- [active] **🆕 NEW — #1063 — an offer is recognized from its FIRST frame, before the Decline
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
  - Confirmed: 1/2 (desk 09-07, the 09-06+09-07 dashes on `aabb56d0` — DESK HALF PASS, all three checks: 9 offers → 9 `OFFER_RECEIVED` → 9 resolutions (2 accepted / 7 declined) → 9 TTS utterances → 9 bubble cards, a perfect 1:1; zero "Replaced by new offer" / `OFFER_TIMEOUT`; the three offer-shaped UNKNOWN frames left are store-leg-LESS half-renders (only `accept_button` + an assignment UUID) — the #595 family the check permits. The dev-eyes half (does an offer FEEL earlier, one narration each) is still open. Desk 09-09, the 09-08 dash on `8028691a`: DESK HALF **2/2** — 17 offers → 17 `OFFER_RECEIVED` → 17 utterances → 17 screenshots → 17 resolutions (3 accepted / 14 declined), zero `OFFER_TIMEOUT` / "Replaced by new offer" / "Superseded by direct offer"; the two offer-shaped UNKNOWN frames are store-leg-LESS half-renders. The item stays open ONLY for the dev-eyes half.)
    - desk 09-13: third clean desk pass — 31 offers → 31 `OFFER_RECEIVED` → 31 utterances → 31 screenshots → 31 chat cards → 31 resolutions; zero "Replaced by new offer"/"Superseded by direct offer"; the three offer-shaped UNKNOWN frames are store-leg-less. Dev-eyes half still open. (The 7 `OFFER_TIMEOUT`s are #1104, a different mechanism.)
    - desk 09-15: fourth clean desk pass — 82 offers → 82 `OFFER_RECEIVED` → 82 utterances → 82 screenshots → 82 chat cards → 82 resolutions. Dev-eyes half still open.
    - desk 09-20: fifth clean desk pass, for 09-16/17 only — 23 offers → 23 `OFFER_RECEIVED` → 23 utterances → 23 screenshots → 23 chat cards → 23 resolutions, zero "Replaced by new offer" / "Superseded by direct offer", and `parseShortfall{doordash.screen.offer_popup…}` absent entirely (the #1063 baseline tripper stayed quiet). The 6 offer-shaped UNKNOWN half-renders are the store-leg-less #595 family the check permits. 09-18/19 contribute nothing — no offer was recognised at all (#1114). Dev-eyes half still open.
    - desk 09-21: **sixth** clean desk pass — 22 offers → 22 `OFFER_RECEIVED` → 22 utterances → 22
      screenshots → 22 chat cards → 22 resolutions on DoorDash 8.98.5's Compose card, zero "Replaced
      by new offer" / "Superseded by direct offer", `parseShortfall{offer_popup}` absent. (The 22
      `OFFER_TIMEOUT`s are the pre-#1118 outcome shape, not a recognition failure.) Dev-eyes half
      still open.
    - desk 09-26: DESK HALF CLEAN a 3rd time — 67 cards, one presentation each; zero "Replaced by new
      offer"/"Superseded"; `accept_decline_footer_container` in 0 UNKNOWN frames; all 30 offer-shaped UNKNOWN
      frames are Accept-less half-renders. One adjacent defect of a DIFFERENT class: two distinct offers merged by
      presentationKey (#1069). Dev-eyes half still open.
    - desk 09-27: DESK HALF CLEAN a 4th time — 20 cards → 20 Rule LOG receipts → 20 screenshots → 20 chat cards; 0
      "Replaced by new offer"/"Superseded"; `accept_decline_footer_container` in 0 UNKNOWN frames; the 14
      offer-shaped UNKNOWN frames are Accept-less half-renders. The one card without its own row/voice is the #1069
      merge, a different class. Dev-eyes half still open.
    - desk 09-29: DESK HALF CLEAN a 5th time — 59 cards → 59 Rule LOG → 59 TTS → 59 screenshots → 59 chat cards → 59
      resolutions; `accept_decline_footer_container` in 0 UNKNOWN frames. Two "Replaced by new offer" at +19.5 /
      +19.7 s are real card turnovers, not the ~2 s early-frame class. Dev-eyes half still open.
  Triage source: first-line blame 2026-09-05 (`5a4c54e15`, original README L632).

- [active] **🆕 NEW — #1059 — the Persona verification flow, the Red Card wallet and the passport
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
  - Confirmed: 0/2 (desk 09-07 on `aabb56d0`: NOT EXERCISED — no Persona / Red Card wallet / passport surface visited. Side readings: the pre-existing arms fired heavily (`sensitive.dasher_direct` ×76, `sensitive.balance` ×9, `sensitive.crimson_balance` ×2, `sensitiveDropped=84`), and the OVER-match check passes with force — two full Red-Card-paid shop-and-deliver orders (87 `pickup_shopping` + 26 `shopping_item` frames) produced ZERO `sensitive.red_card` drops. Desk 09-09 on `8028691a`: NOT EXERCISED again — the id grep returns nothing tree-wide and no `sensitive.selfie_verification` / `red_card` / `id_verification` drop; over-match check passes a second time (two `Red Card` shop offers + three shop pickups, 67 `pickup_shopping` + 28 `shopping_item` frames, zero `sensitive.red_card` drops).)
    - desk 09-13 (3rd pull): NOT EXERCISED — zero ids tree-wide, zero sensitive drops of the three new arms; the OVER-match check passes a third time (8 shop orders, 99 `pickup_shopping` + 53 `shopping_item` frames, zero `sensitive.red_card` drops).
    - desk 09-15 (4th pull): NOT EXERCISED; over-match check passes a fourth time (~20 Red-Card shop orders, 211 `pickup_shopping` + 80 `shopping_item`, zero `sensitive.red_card`). Caveat: `sensitive.id_verification` fired 16× on the alcohol licence scanner and the log names only the intent — NOT evidence for the passport alternative.
    - desk 09-20 (5th pull): NOT EXERCISED — the four ids return 0 tree-wide and none of the three new arms fired; over-match check passes a fifth time (269 `pickup_shopping` + 125 `shopping_item` frames, many Red-Card shop orders, zero `sensitive.red_card` drops). Pre-existing arms healthy: `dasher_direct` ×500, `balance` ×25, `crimson_balance` ×11, `catchall` ×34.
    - desk 09-21 (6th pull): NOT EXERCISED — the four ids return **0** tree-wide and none of the
      three new arms fired; the over-match check passes a **sixth** time (175 `pickup_shopping` + 70
      `shopping_item` Red-Card shop frames, zero `sensitive.red_card` drops). Pre-existing arms
      healthy: `dasher_direct` ×73, `catchall` ×7, `balance` ×5, `crimson_balance` ×2.
    - desk 09-26: NOT EXERCISED (the id grep is 0 tree-wide). Adjacent positive: `sensitive.id_verification` dropped
      39 frames across the two alcohol drops (09-21 12:53, 09-23 09:22); no scanner frame reached disk. Over-match
      check passes (Red-Card shop orders, 0 `sensitive.red_card`).
    - desk 09-27: NOT EXERCISED — the four ids are 0 tree-wide; none of the three arms fired. Pre-existing arms:
      `dasher_direct` ×74 (incl. a mid-dash visit at 14:05:41), catchall ×6, `crimson_balance` ×1; 0
      `sensitive.red_card` over 141 `pickup_shopping` + 51 `shopping_item` H-E-B frames (over-match passes a 7th
      time).
    - desk 09-29: NOT EXERCISED — the four ids 0 tree-wide. Pre-existing arms: `dasher_direct` ×388 (incl. a mid-dash
      visit 09-28 08:42), balance ×20, catchall ×19, crimson_balance ×7, savings ×4. 0 `sensitive.red_card` over 105
      `pickup_shopping` + 37 `shopping_item` frames (over-match passes an 8th time). "Open digital Red Card" (click
      09-27 12:23:08) produced NO admitted frame for 24 s — nothing reached disk.
  Triage source: first-line blame 2026-09-05 (`1fa9b552f`, original README L672).

### Counter unspecified — retained active, newest first

These 39 items lack a whole-item `Confirmed: N/2` counter. Some record a desk-half count or narrative sightings; neither establishes a whole-item count. No zero counter or aged-review status is inferred. The dev can resolve these separately.

- [active] **"Dash paused" push is recognized (#1090):** after a dash in which you paused, the pull's
  `captures/doordash/notification/` should hold NO UNKNOWN envelope reading "Your current dash has been
  paused" — the envelope lands under `notification/dash_paused/` instead — and `app.log` carries
  `Rule LOG [NOTIFICATION_RECEIVED]: DASH_PAUSED_PUSH` (a DEBUG line; the `log` verb writes NO
  `app_events` row) a few seconds BEFORE the screen-derived `DASH_PAUSED`, while the dash's mode and
  timers behave exactly as before (the push is informational — it must not pause, resume, or shorten
  the safety net).
  Triage source: first-line blame 2026-10-05 (`0d3dc835e`, original README L89).

- [active] **Census deferral lines name their cause (#1210):** after any dash, `grep -h 'census deferred cause=' shareable.log`
  shows `cause=budget|upload_rate_limit|enrol_rate_limit|health_rate_limit|health_budget seconds=<n>` or
  `cause=stored_deadline remaining=<n>` — never the old bare `census deferred runs=1`. Working = every `deferred`
  status (skeleton, health or enrolment stage) has a matching line naming its cause.
  Triage source: first-line blame 2026-10-05 (`5ff90c7d0`, original README L116).

- [active] **Desk-first — #965 / #1126 (PR #1213):** on the next pull, (a) a `ratings` envelope from the LEGACY ratings hub
  parses `deliveriesLast30Days` as a count (the approved golden now carries 136/72/71/100 on the committed legacy
  frames; a null on a fielded legacy frame is the thing to report), and (b) every recognized dropoff envelope's
  `address_line_2` / numeric `Building Name` slot reads plain `[redacted]` — no `[redacted:<4hex>]` on those two
  slots anywhere in `captures/doordash/`.
  Triage source: first-line blame 2026-10-05 (`46f87c21c`, original README L128).

- [active] **🆕 NEW — #967 / PR #968 — ratings stamp during an Offline browse (the early-return fix).**
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
  - Confirmed: DESK HALF **2/2** — open only for the dev-eyes half (desk 09-09, the 09-08 dash on `8028691a`: `ratings.capturedAt` advanced to 18:57:32 on the second `ratings` frame of an `[DoorDash:Offline]` browse — session 447 had ended 18:55:47. Earlier: desk 08-09, the 08-01→08-08 window: DESK HALF PASS — first pull whose device
    build actually carries #968, and `ratings.capturedAt` advanced on five separate Offline
    browses: 08-07 20:31 plus 08-08 10:40 / 10:41 / 15:09 / 15:10, every one stamped while the
    mode snapshot read `[DoorDash:Offline]` (the two same-minute pairs are two browses each).
    Pre-#968 that combination produced no stamp at all, so this is the fix working. The dev-eyes
    half — the Ratings screen visibly populating during an Offline browse instead of reading
    blank/stale — still wants a look on the phone.)
    - desk 09-15: third corroboration — the 09-15 12:01:31 Offline-browse stamp advanced `ratings.capturedAt`. Dev-eyes half still open.
  Triage source: first-line blame 2026-07-31 (`382458b30`, original README L874).

- [active] **🆕 NEW — identity-less completions now firewalled at PostTask exit (#653 / PR #673): watch a
  no-name drop for a MISSING completion.** The PostTask-exit `DELIVERY_COMPLETED` mint now mirrors
  the close-out path's #498 identity firewall: a dropoff task that never acquired a customer
  name/address hash mints no completion. Field history says identity-less dropoff tasks are
  phantoms, but the known residual is a REAL delivery whose only name-bearing screen is still
  unruled — e.g. a GoPuff last drop via the multi-order-confirm surface (#501 deferred). On any
  GoPuff batch (or other drop where the customer name never appeared on a recognized screen),
  check afterwards that the delivery still shows in the session's deliveries/earnings. How to tell
  it's broken: a real, physically-delivered drop is absent from the completion list / Money tab
  while its siblings are present.
  Triage source: first-line blame 2026-07-05 (`30f2cba80`, original README L1846).

- [active] **🆕 NEW — driving/glance-mode HUD font-scale toggle (#318).** Flip "Driving glance mode" on in
  Settings → General while a dash is running (or the bubble is up). The bubble HUD's text — the
  hero $/hr, task timers, captions, everything — should visibly grow (~12%) immediately, with no
  restart of the app or the dash. Toggle it back off and the text should shrink back immediately.
  Throughout, the **main app window** (Dashboard, Settings, etc. — outside the floating bubble)
  should look completely unchanged at every step. How to tell it's broken: HUD text doesn't
  change size on toggle, only *some* of the HUD text scales (e.g. the hero number but not the
  captions/labels), the toggle needs an app restart to take effect, or the main app window's text
  size changes too.
  Triage source: first-line blame 2026-07-05 (`21897cd88`, original README L1884).

- [active] **🔧 FIX SHIPPED — a stacked job's DELIVERY_COMPLETED rows now carry per-drop realized pay (#528 Slice A). READ THE DB AFTER A DASH.**
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
  Triage source: first-line blame 2026-07-03 (`44ab7a3ff`, original README L2002).

- [active] **🔧 FIX SHIPPED — offer outcome cards now derive from the committed outcome, not the tap (#601).
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
  Triage source: first-line blame 2026-07-03 (`1165556e0`, original README L2049).

- [active] **🔧 FIX SHIPPED — pausing during/after a delivery shows exactly one "Dash Paused!", no phantom "resumed" flap (#605). CONFIRM ON DASH.**
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
  Triage source: first-line blame 2026-07-03 (`127efa96d`, original README L2092).

- [active] **🔧 FIX SHIPPED — one DashSummary screenshot per session end, and offer screenshots are named
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
  Triage source: first-line blame 2026-07-03 (`8a6122464`, original README L2138).

- [active] **🔒 FIX SHIPPED — recognized capture envelopes now redact customer PII at the device edge (#598, incl. audit F1/F2 coverage extension).**
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
  Triage source: first-line blame 2026-07-03 (`98c718b53`, original README L2189).

- [active] **🔧 FIX SHIPPED — automated taps now rank click candidates by evidence instead of a dead exact-bounds check (#600).**
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
  Triage source: first-line blame 2026-07-02 (`13a0ae0a9`, original README L2159).

- [active] **🔧 FIX SHIPPED — a shade-tapped Accept/Decline now retries instead of dying on "No live windows" (#602).**
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
  Triage source: first-line blame 2026-07-02 (`f47c714aa`, original README L2174).

- [active] **🔧 FIX SHIPPED — receipt-skipped deliveries still close + log a completion, and the next offer starts a NEW job (#596).**
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
  Triage source: first-line blame 2026-07-02 (`9bdb490a3`, original README L2216).

- [active] **🔧 FIX SHIPPED — click captures record every rule-matched tap + UNKNOWN cap re-arms after quiet (#597). READ THE PULL AFTER A DASH.**
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
  Triage source: first-line blame 2026-07-02 (`595549711`, original README L2231).

- [active] **🔧 FIX SHIPPED — a committed decline can't be un-declined by a later Accept (#594). CONFIRM ON DASH.**
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
  Triage source: first-line blame 2026-07-02 (`eabf4472e`, original README L2247).

- [active] **🔧 FIX SHIPPED — post-accept teardown frame no longer mints a ghost offer (#595). CONFIRM ON DASH.**
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
  Triage source: first-line blame 2026-07-02 (`edc269a12`, original README L2261).

- [active] **🔒 FIX SHIPPED — Crimson Savings Jar balance notification is now pledge-BLOCKED (#599). READ THE PULL AFTER A DASH.**
  The dasher's Crimson/DasherDirect balance notification was being recognized and captured raw
  (9 files found on the 06-25→30 pull — deleted from the device). The rule is now sensitive
  (priority 0, `parse: sensitive`) — the shared content gate drops it before capture and the
  state machine. **Confirm on next pull: 0/2 —** after a dash where the Savings Jar notification
  arrived: `captures/doordash/notification/` must contain **no** `crimson_balance` (or
  `sensitive.*`) folder/files, and the log shows `Sensitive gate: dropped sensitive.crimson_balance`
  at DEBUG. Broken = any capture file containing "Savings Jar balance". (Note: `earnings_deposit`
  and `transfer_complete` notifications still capture — their sensitive-vs-keep fate is an open
  dev call on #599's siblings.)
  Triage source: first-line blame 2026-07-02 (`c2c4d70a0`, original README L2274).

- [active] **👁 VISUAL-ONLY — rich offer notification looks right: gauge ring, countdown, badges (#578/#583). CONFIRM BY EYE.**
  The mechanical halves are validated (buttons fire from the floating heads-up; the card posts
  with live PendingIntents; zero RemoteViews errors across a full week) — what desk data cannot
  see is the **rendering**. **Confirm on dash: 0/2 —** (a) the score shows as a **filled circular
  gauge ring** with the number centered (not a flat bar, not blank/garbled/missing when the offer
  scored); (b) the **countdown ticks** on the collapsed banner; (c) expanding shows the verdict
  banner + **colored** (not black) badges + store. If anything looks off, screenshot collapsed +
  expanded — that screenshot is the whole test.
  Triage source: first-line blame 2026-07-01 (`deffa9458`, original README L2285).

- [active] **🔧 FIX SHIPPED — dropoff customer reads "\<store\>'s customer", not a 6-char hash (#568, PR #575). CONFIRM ON DASH.**
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
  Triage source: first-line blame 2026-06-22 (`ac3557177`, original README L2294).

- [active] **🔬 FIX SHIPPED (recognize-only) — dropoff-arrived 'Leave it at the door' card now recognized (#549, PR #574). READ THE LOG / WATCH UNKNOWNs.**
  A dropoff-arrival card whose instruction is *not* "Hand it to recipient" (e.g. "Leave it at the
  door", a refined map pin, "Complete delivery steps") was falling to **UNKNOWN** — the state machine
  got no clean dropoff signal and leaned on the grace window. Now recognized (customer name + address
  **hashed**; the gate-code/instruction text is never stored). **Confirm: 0/2 —** after a dash with a
  "leave at door" / gate-code delivery, grep the captures/log: that arrival screen should recognize as
  a dropoff (not pile into UNKNOWN), and INFO/db must show **no** raw address/gate-code (hashes only).
  This is recognize-only — it does **not** yet flip an "arrived" state (deferred, needs a fresh
  capture to decide), so don't expect a behavior change, just cleaner recognition.
  Triage source: first-line blame 2026-06-22 (`5be3a6852`, original README L2306).

- [active] **🔧 FIX SHIPPED — no more double fly-away bubble on a new/stacked pickup (#566, PR #573). CONFIRM ON DASH.**
  When a pickup started (or a stacked pickup handed off), the "Pickup: <store>" heads-up bubble flew
  out **twice** in quick succession (the second often icon-less). Fixed with a per-task dedupe key.
  **Confirm on dash: 0/2 —** on each new pickup (especially the second store of a stack), the
  "Pickup: <store>" notification should fly out **once**, not twice. A genuine change should still
  show: e.g. heading-to-store → start-shopping may update the bubble (that's expected, different
  state), and a later separate trip to the same store should still announce. If you still see an
  immediate identical double, note the store + whether it was the first or a stacked pickup.
  Triage source: first-line blame 2026-06-22 (`a2500d6af`, original README L2316).

- [active] **🔧 FIX SHIPPED — a pickup no longer borrows a customer identity (#548, PR #572). CONFIRM ON DASH.**
  On a **multi-store stack**, a restaurant pickup screen could bleed the *other* drop's customer onto
  the pickup task (latent — no visible bubble effect, but a data-model defect). The pickup screen no
  longer parses a customer at all. **Confirm on dash: 0/2 —** on a stacked/multi-store order, the
  **pickup** bubble should read the **store/merchant** (never a 6-character customer fragment), and no
  customer should bleed onto the first dropoff. Hard to see directly; mainly verified in the db/log —
  but flag anything where a pickup card shows a customer-looking label.
  Triage source: first-line blame 2026-06-22 (`457e68d9a`, original README L2325).

- [active] **🔧 FIX SHIPPED — dropoff customer fills in the card instead of re-minting it (#565, PR #571). CONFIRM ON DASH.**
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
  Triage source: first-line blame 2026-06-22 (`04bb471f6`, original README L2333).

- [active] **🔧 FIX SHIPPED — add-on offer no longer fabricates a $0 "completion" (#564, PR #570). CONFIRM ON DASH.**
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
  Triage source: first-line blame 2026-06-22 (`6fba7dfee`, original README L2345).

- [active] **🔧 MERGED — realistic $/hr + score on Shop & Deliver offers (#556). CONFIRM ON DASH.**
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
  Triage source: first-line blame 2026-06-21 (`a60734205`, original README L2357).

- [active] **🔧 MERGED — dropoff store recognition + pickup-matched resolution (#526/#553). CONFIRM ON DASH.**
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
  Triage source: first-line blame 2026-06-21 (`77deaa149`, original README L2371).

- [active] **🔧 FIX SHIPPED — offer card icon badges + co-icon-text shop badge [cart N] (#461, PR #531). CONFIRM ON DASH.**
  The offer card's badges are now **icons** (red card, alcohol, large order, priority, etc., tinted by
  brand color), and the **Shop & Deliver** badge is the shopping-chat **cart icon + the item count**
  (`[🛒 N]`) — the count moved off the $/hr hero onto the badge. **Confirm on dash: 0/2 —** on a
  **shop** offer (e.g. Sprouts/CVS) the card shows the cart badge with the **true item count** (a
  25-item shop reads **25**, not 26), it shows **while the offer is live** (not just after), and a
  pure-pickup (or pickup stack) shows **no** cart/count. Other badges should render as their icons. If
  a count is off by the number of pickups in a stack, or the shop badge doesn't show live, note the
  offer's order mix.
  Triage source: first-line blame 2026-06-18 (`d412eb3fd`, original README L2386).

- [active] **🔧 FIX IN FLIGHT — phantom + over-minted dropoff tasks cleaned up (#498, PR #521). CONFIRM ON DASH.**
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
  Triage source: first-line blame 2026-06-18 (`44186b8aa`, original README L2415).

- [active] **🔧 FIX IN FLIGHT — a delivery can't be logged/counted twice (#518, PRs #520 + #522). CONFIRM ON DASH.**
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
  Triage source: first-line blame 2026-06-18 (`b02614328`, original README L2431).

- [active] **🔧 FIX IN FLIGHT — multi-drop stack: Job owns ordered dropoffs, routed by customer name (#503 slice 3b, PR #523). CONFIRM ON DASH.**
  The structural multi-drop fix: an offer pre-creates **one dropoff placeholder per order**, and each
  dropoff screen routes to its own customer subtask by the **stable name hash** (addresses drift).
  **Confirm on a STACKED / multi-drop dash: 0/2 —** a 2-order stack should show **exactly two** dropoff
  cards, each resolving to the **right customer** (6-char hash), and the counts/earnings should match
  **two** deliveries (not 4, the 06-17 Jim's-stack symptom; not 1). Returning to an earlier drop (or
  the app re-showing it) should **resume** that drop, not spawn a duplicate. This is the real test of
  the 06-17 dropoff thread end-to-end — needs an actual stacked order (or a GoPuff multi-drop, though
  GoPuff recognition is still #501). If a drop duplicates, mis-resolves, or a phantom appears, capture
  the dropoff frame sequence + `app_state_snapshots` for the stack.
  Triage source: first-line blame 2026-06-18 (`7b1b39eb7`, original README L2446).

- [active] **🔧 FIX SHIPPED — live $/mi on the task card, read off the Job (#503 deliverable 2, PR #525). CONFIRM ON DASH.**
  The live pickup/delivery card's "Running at $X/hr" co-hero now shows a **$Y/mi** sub line (fixed
  efficiency off `Job.blendedDistanceMiles`; "—" when no offer distance is known, never `$X/0mi`).
  **Confirm on dash: 0/2 —** while driving a delivery the card should show a sensible **$/mi** under the
  $/hr (e.g. `$1.85/mi`), it should **persist** through nav→arrival (distance doesn't erode like the
  $/hr does), and on a **stacked/add-on** it should reflect the **summed** job distance (not a single
  offer's). If $/mi shows "—" on a normal offer (that carried a distance), or looks wrong on a stack,
  note the offer's quoted miles + net pay.
  Triage source: first-line blame 2026-06-18 (`cfa4b22ea`, original README L2457).

- [active] **🔧 FIX SHIPPED — GoPuff (Drive) bin-scan mints the pickup arrival (#501, PR #530). CONFIRM ON DASH.**
  The warehouse leg used to be all-UNKNOWN with **no `PICKUP_ARRIVED`** (the machine jumped
  nav→confirmed). Now the GoPuff **bin-scan steps** screen ("Pickup steps" / "Scan barcodes on" /
  "Complete pickup") is recognized as a branch of `pickup_steps` and declares `task:pickup:arrived`;
  the wait-survey + barcode-scan screens are recognized too. **Confirm on a GoPuff dash: 0/2 —** the
  bubble should show the pickup ARRIVING at the warehouse (a Dwell timer at store), exactly **one**
  pickup arrival for the batch (not zero, not two), and the bin-scan/scan-fail screens shouldn't sit
  on a stale card. If the arrival never fires or fires twice, capture the bin-scan frame sequence +
  `app_state_snapshots`. (Still UNKNOWN, deferred to a follow-up: the zone-arrival "Navigate to zone"
  card and the dropoff multi-order-confirm — those are noise, the arrival is the load-bearing fix.)
  Triage source: first-line blame 2026-06-18 (`635294559`, original README L2466).

- [active] **✅ FIX SHIPPED — same-store add-on no longer re-mints the task (#499 / #503 slice 2). CONFIRM ON DASH.**
  The task lifecycle now **resumes a prior subtask** instead of re-minting on a phase switch / after an
  offer interlude (re-match by store for pickups, customer address for dropoffs). **Confirm on dash:
  0/2 —** on a same-store shopping add-on (e.g. an offer accepted mid-shop at the same store), the shop
  task should **keep its identity and fold the add-on in** (combined item count bumps on the *same*
  card), not spawn a second/fresh task. Also watch a stacked order with *different* stops: those must
  still be **distinct** tasks. If a same-store add-on still re-mints, capture the shop→offer→shop frame
  sequence + note the timing.
  Triage source: first-line blame 2026-06-16 (`481cdf324`, original README L2396).

- [active] **✅ FIX SHIPPED — dropoff created from the offer; the premature "Customer" card root (#503 slice 3). CONFIRM ON DASH.**
  The drop-off is now a known subtask created at **offer-accept** (customer TBD), and the dropoff screen
  RESOLVES the real customer onto it — instead of a phantom dropoff minted before its customer is known.
  **Confirm on dash: 0/2 —** a drop-off card should show the **real customer** (a short 6-char hash
  code); an unresolved one shows **"the customer"** (lowercase, briefly, never the name-like "Customer")
  and must not **linger** or appear as a **premature/duplicate** drop-off card. If a phantom/duplicate
  dropoff or a stuck "the customer" card shows, capture the dropoff frame sequence + note the timing.
  (Single-order this build; **multi-drop is slice 3b, not yet shipped** — a stacked/GoPuff multi-drop
  may still mis-handle the extra dropoffs.)
  Triage source: first-line blame 2026-06-16 (`183d03d44`, original README L2405).

- [active] **📸 CAPTURE NEEDED — GoPuff (Drive) screens, to finalize the #501 rules.** The 06-14 deep-dive
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
  Triage source: first-line blame 2026-06-15 (`715060a82`, original README L2477).

- [active] **✅ FIX SHIPPED — "ghost offer" with EMPTY parse logged as a card (#498). CONFIRM ON DASH.**
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
  Triage source: first-line blame 2026-06-15 (`7cc0a01de`, original README L2494).

- [active] **Receipt grace — delivery completion is now deferred ~2.5s (#431 pt 2).**
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
  Triage source: first-line blame 2026-06-12 (`d8dad2ef9`, original README L2700).

- [active] **⚠️ WATCH FOR RECURRENCE — mid-dash "Done Dashing!" + odometer reset (2026-06-06 #5, root cause confirmed, fix NOT yet shipped).** Confirmed once on 06-06: a
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
  Triage source: first-line blame 2026-06-07 (`9e289355b`, original README L2987).

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

- [active] **🆕 NEW — #1034 — a negative dollar reads `-$12`, never `$-12`.** `Formats.money`/`money0`/
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
    - desk 09-09: NOT EXERCISED — `grep -c '\$-' shareable.log` = 0, but no window went negative and the
      worst offer (score 28, `$5.74/hr`) stayed positive.
    - desk 09-20: NOT EXERCISED — `grep -c '\$-' shareable.log` = 0 again; no window went negative.
    - desk 09-21: NOT EXERCISED — `grep -c '\$-' shareable.log` = 0 a third time; no window went
      negative.
  Triage source: first-line blame 2026-08-24 (`9b438b76a`, original README L760).

- [active] **🆕 NEW — #428-B / PR #845 — multi-language TTS (system locale + settings override).**
  **What to watch:** Settings → Voice → Spoken offer language set to Español → the next offer reads
  in Spanish (voice AND words together); System default on an English phone stays English; if the
  es voice pack is missing the read falls back to English (one WARN in the log, never silence).
  **Desk:** grep for the `Tts` tag language-apply lines; no per-utterance WARN spam.
  - Confirmed: 0/2 (desk 07-26: INCONCLUSIVE — 64 post-install utterances all English, zero WARN
    spam, but the Spanish path and the missing-voice-pack fallback were never exercised. CAVEAT:
    the desk grep above has NO corresponding log site in the current code — a no-hit is not
    evidence; needs the Settings→Español toggle actually flipped on a dash. Desk 07-30: same
    null — 14 utterances, all English, zero WARN; the toggle was never flipped.)
    - desk 09-13: seventh mechanism corroboration — the 09-08 expand grant persisted across the 09-11 22:34 restart and fired 11×, while `confirm_decline` was denied fail-closed 31× (13 confirm episodes × 2 + 5 single-denial episodes, exactly accounted). Note: the reconcile line's "(none granted — awaiting consent)" phrase is a CONSTANT in `RuleCapabilityRepository.reconcile`, not a store read.
  Triage source: first-line blame 2026-07-23 (`3745bc073`, original README L1288).

- [active] **🆕 NEW — #859 (H4 + placeholder filenames) — one offer screenshot per presentation, and no
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
    off-by-one worth noting — the 07-29 pull was exactly 12/12. Still needs an Uber dash.
    Desk 09-09, the 09-08 dash: still Uber-gated — DoorDash side 17 `Offer - <pay>.png` for 17
    `OFFER_RECEIVED`, exactly 1:1, plus 2 `DashSummary` / 1 `Delivery` / 1 `DeliveryBreakdown`, zero `{`
    filenames, zero placeholders.)
  Triage source: first-line blame 2026-07-26 (`cc377c2ee`, original README L1263).

- [active] **🆕 NEW — unassign an order AFTER pickup (dropoff phase) also produces NO paid artifact (#752 / PR #757).**
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
  Triage source: first-line blame 2026-07-11 (`31f0f6905`, original README L1608).

## §3 Review (aged ≥ 6 weeks at 0/2)

95 aged zero-confirmation items: 91 `[review]` and 4 `[superseded?]` proposals. Every `desk:` line is a proposal only. A replay/check proposed here has not been run; missing field exposure is not a pass. The four supersession proposals leave the routine active queue but remain here for the dev to decide.

### recognition

- [review] **🆕 NEW — #895 / PR #927 — fused `Apt <n>` masks are plain on all 8 uber+doordash surfaces
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
  desk: desk-verifiable: inspect exercised fused-subpremise envelopes for plain masks and intact name/street hashes, including the recorded #986 residual.
  Triage source: first-line blame 2026-07-30 (`557f25161`, original README L1082).

- [review] **Ratings-hub family no longer falls to UNKNOWN (2026-07-30 corpus pass).** Three recognition
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
  desk: desk-verifiable: inspect closed-drawer ratings, Last 30-day orders and expanded band-table captures for the named classes; keep the documented parse residual separate.
  Triage source: first-line blame 2026-07-30 (`b18fb8c69`, original README L3109).

- [review] **🆕 NEW — #888 — eight DoorDash screens that used to fall to UNKNOWN are now recognized.**
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
  desk: desk-verifiable: census all eight named capture families and correlate their timestamps with absence of new flow/TTS effects; unexercised families remain open.
  Triage source: first-line blame 2026-07-27 (`37f69f70a`, original README L1095).

- [review] **🆕 NEW — #882 / #881 — Uber stacked offers speak the STORE, and a "Match" is recorded as a match.**
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
  desk: needs field: an Uber stack and Match-to-direct hand-off must occur; then compare merchant, trip-time and outcome rows with captures.
  Triage source: first-line blame 2026-07-27 (`1f4c4a940`, original README L1140).

- [review] **🆕 NEW — #858 / PR #876 — expiring Uber cards no longer mint offers.**
  The "This request is no longer available" dying card is UNKNOWN or a recognize-only Trip Radar
  board frame with no `state` block: no state, no offer. **What to watch (Uber):** an offer that expires on screen produces no new offer row, no TTS
  read of the error text, no "(offer replaced)" churn on the live offer. **Desk:** zero
  `merchantName` containing "no longer available" / "Unknown Store"-via-overlay in `offer_records`;
  the dying frames appear as UNKNOWN or `trip_radar_board` captures.
  - Confirmed: 0/2
  desk: desk-verifiable: compare expiring Uber-card captures with offer rows and TTS logs; error overlays must mint no new offer.
  Triage source: first-line blame 2026-07-26 (`a71ad28d8`, original README L1457).

- [review] **🆕 NEW — #861 / PR #868 — Uber selfie ID-verification camera is sensitive-blocked.**
  **Desk only:** next pull has ZERO selfie-flow UNKNOWN envelopes (`facecamera` ids /
  "Fit your face in the guide"); `sensitiveDropped` increments across that window instead.
  - Confirmed: 0/2
  desk: desk-verifiable: correlate an exercised selfie-flow window with sensitiveDropped and absence of facecamera/guide envelopes.
  Triage source: first-line blame 2026-07-26 (`a71ad28d8`, original README L1464).

- [review] **🆕 NEW — #860 / PR #871 — Building Name value masked on both dropoff workflow sheets.**
  **Desk only:** any `dropoff_pre_arrival` / `dropoff_pre_arrival_completion` envelope carrying a
  `Building Name` row shows the value as `[redacted:<4hex>]` (label survives). New engine predicate
  `hasPrecedingSiblingText` is the anchor — a completion-sheet fixture is still wanted (none
  fielded yet; the entry is shape-inherited).
  - Confirmed: 0/2
  desk: desk-verifiable: compare Building Name values on both sheets with newer #1126 / PR #1213 plain-masking of numeric values; preserve the nonnumeric and completion-sheet residuals.
  Triage source: first-line blame 2026-07-26 (`a71ad28d8`, original README L1468).

- [review] **🆕 NEW — #865 / PR #877 — performance-hub residual gaps recognized.**
  Acceptance-rate detail, scrolled last-100 list, customer-rating detail. **Desk only:** those three
  UNKNOWN families → zero in the next pull. Watch item folded in per the review: if "View all
  offers" opens a "Last 100 offers" modal, it will land UNKNOWN (never fielded, deliberately not
  speculatively covered) — grab it if you visit that screen. Compliments-quote residual: a dasher
  WITH compliments renders customer-authored quote text that no redact can scope today — if your
  rating screen shows quotes, flag the pull for a manual check.
  - Confirmed: 0/2
  desk: desk-verifiable: census the three ratings-detail families and inspect any Last 100 modal or compliments quote; absent surfaces are not confirmations.
  Triage source: first-line blame 2026-07-26 (`a71ad28d8`, original README L1481).

- [review] **🆕 NEW — #825 / PR #833 — Uber recognized-surface customer redact wave.** `active_trip`,
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
  desk: desk-verifiable: inspect the four named Uber capture families, including contentDescription-only addresses, for their specified masks.
  Triage source: first-line blame 2026-07-23 (`722aa4c19`, original README L1335).

- [review] **🆕 NEW — #795 / PR #834 — PIN-required delivery confirmation flow + plainMask.** The Enter-PIN
  keypad modal and intro sheet now recognize (`dropoff_pin_keypad`/`dropoff_pin_intro`,
  recognize-only), and the entered PIN masks to a **plain** `[redacted]` (no 4-hex suffix — a
  bounded PIN is brute-recoverable from 4 hex).
  **What to watch:** on the next PIN-required delivery (CVS-class), the delivery completes as
  before. **Desk:** those frames land in their intent folders instead of UNKNOWN; in the keypad
  frames' envelopes the PIN digits appear ONLY as plain `[redacted]` — no `[redacted:hhhh]` token
  on any pure-digit node, no raw PIN anywhere.
  - Confirmed: 0/2 (desk 07-26: no PIN-required delivery occurred; global `pin[\s:#]*\d` grep over
    the pull → zero raw hits.)
  desk: desk-verifiable: inspect an exercised PIN flow for recognized keypad/intro envelopes and plain PIN masks; pair it with completion events.
  Triage source: first-line blame 2026-07-23 (`722aa4c19`, original README L1347).

- [review] **🆕 NEW — #809 / PR #820 + #803 / PR #821 — pickup/dropoff PII redacts (desk-resolvable).** New
  redacts: `pickup_select_issue` (+ its issue-list variant, now recognized) masks the fused
  `For <name> • <store>` header to `For [redacted:<4hex>]`; `dropoff_pin_entry`/`dropoff_handoff` mask
  gate-code/PIN-bearing instruction bodies (incl. `PIN: NNNN` / fused `PinNNNN` variants).
  **Desk-side:** in the next pull, every capture of these four surfaces must show the masked forms — grep
  for `For [A-Z][a-z]+\s+[A-Z]\.` and `pin[\s:#]*\d` over the recognized capture tree → zero raw hits.
  (#885: use `\s+` between the name tokens, never a single literal space — the 07-26 render put a
  DOUBLE space before the last initial and a single-space grep reads clean on a real leak.)
  - Confirmed: 0/2 (desk 07-26: both greps → zero raw hits across the whole pull, but none of the
    four specific surfaces occurred, so this is a clean null, not a confirmation.)
  desk: desk-verifiable: inspect pickup issue and dropoff envelopes for masked headers and plain instruction bodies, including the fused PIN variants.
  Triage source: first-line blame 2026-07-20 (`022633e47`, original README L1399).

- [review] **🆕 NEW — GoPuff / multi-order drop-off confirm card recognized (#501 items 1-2 / PR #743).**
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
  desk: desk-verifiable: inspect multi-order confirm captures for the recognized class and absence of state effects.
  Triage source: first-line blame 2026-07-10 (`7d753a904`, original README L1628).

- [review] **🆕 NEW — GoPuff zone-arrival screens recognized (recognize-only, no state effect) (#501 item 3 / PR #738).**
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
  desk: desk-verifiable: inspect GoPuff zone-arrival captures and confirm recognize-only handling adds no state transition.
  Triage source: first-line blame 2026-07-09 (`602f7ff40`, original README L1674).

- [review] **🔒 PLEDGE FIX — recognized-notification captures now mask customer PII (#620).**
  Send yourself a customer chat ("Message from ‹name›" + a message body) and trigger an order-ready
  push ("‹name›'s order is ready for pickup at ‹store›"), then pull the capture envelopes off-device.
  **Working looks like:** the chat capture shows the sender masked (`Message from [redacted:‹4hex›]`)
  and NO message body; the order-ready capture shows the customer name masked (`[redacted:‹4hex›]`)
  but **keeps the store name** (merchants aren't PII). If any raw customer name/body survives in a
  notification envelope, it's broken.
  - Confirmed: 0/2
  desk: desk-verifiable: inspect chat and order-ready notification envelopes for masked sender/customer slots and absent message bodies.
  Triage source: first-line blame 2026-07-03 (`82cbb53d5`, original README L2019).

- [review] **🔒 PLEDGE FIX — dropoff-reminder screen capture now masks the address (#624).**
  Hit the "Deliver to door of ‹address›" reminder card on a dropoff and capture it. **Working looks
  like:** the envelope shows `Deliver to door of [redacted:‹4hex›]` — the street/apt gone. (This was
  a live raw-address leak the #598 sha256 gate couldn't catch.) A defense-in-depth backstop also
  scrubs any recognized screen that ships a "Deliver to "/"Order for " customer line a rule forgot
  to redact — if you see a `redactBackstopScrubs=` count climb in the PipelineStats log line, note
  which screen tripped it.
  - Confirmed: 0/2
  desk: desk-verifiable: inspect dropoff-reminder envelopes and recognized-screen backstop output for masked addresses and customer lines.
  Triage source: first-line blame 2026-07-03 (`82cbb53d5`, original README L2027).

- [review] **🔧 FIX SHIPPED — rule effects from notifications key per-arrival, not per-install (#604), AND
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
  desk: desk-verifiable: compare distinct notification arrivals with rule-effect/idempotency records; identical content must not suppress a later arrival.
  Triage source: first-line blame 2026-07-03 (`af7177614`, original README L2106).

- [review] **7-Eleven / alcohol "Verify items" pickup screen now recognized (#462, first slice).**
  The store "Verify items for <name>" screen (with "Do not open sealed bags" / "Can't verify
  items" / the item list) classified UNKNOWN — the 7-Eleven alcohol pickup from the 2026-06-12
  dash (field-log #12). It's now a recognized `pickup_verify_items` screen mapped to
  pickup-arrived (no customer-name parsing). On a retail/alcohol pickup: working = the bubble/log
  shows a pickup screen (not UNKNOWN) on the verify-items step and the flow stays on pickup.
  Broken = still UNKNOWN, or it mis-steps the flow. (**#462 stays open** — this is one of ~30
  recognition gaps from that dash; the rest are a larger effort.)
  - Confirmed: 0/2
  desk: desk-verifiable: inspect an exercised pickup_verify_items capture and its pickup-arrived transition without customer-name parsing.
  Triage source: first-line blame 2026-06-13 (`ec3e58886`, original README L2545).

- [review] **Batch-1 recognition gaps from 2026-06-12 now recognized (#462).** Twelve more screens that
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
  desk: desk-verifiable: census the twelve listed capture families and check recognize-only screens create no unintended flow effects.
  Triage source: first-line blame 2026-06-13 (`0ce0ef4fd`, original README L2561).

- [review] **Batch-2 recognition gaps — idle/lifecycle (#462, now CLOSED).** The last UNKNOWN screens are
  recognized: the **"Navigate to zone / We'll look for orders along the way / Spot saved until …"**
  repositioning card (now a recognized idle screen, and the "Spot saved until HH:MM" countdown
  should populate); the **scheduled-dash slot picker** ("Start time / End Time"); the **dropoff
  reminder** ("Deliver to door of … / Got it"); the **pickup QR-confirm** ("Confirm that the code
  was scanned"); and the **help/support menu** ("Get an account checkup / Dashing FAQs"). Working =
  these show recognized (not UNKNOWN); the repositioning card shows a spot-save countdown. Broken =
  any still UNKNOWN, or the navigate-to-zone card mis-reads the idle state.
  - Confirmed: 0/2
  desk: desk-verifiable: inspect the listed idle/lifecycle captures and compare repositioning countdown parses with their source text.
  Triage source: first-line blame 2026-06-13 (`1a2bf6bf3`, original README L2575).

- [review] **Order-ready push notification now recognized (#462).** The "‹name›'s order is ready for pickup
  at ‹store›" push arrives on the `dasher-notification-background` channel (it was UNKNOWN before).
  Working = when DoorDash sends the order-ready notification, the log shows it classified
  (`ORDER_READY`), not UNKNOWN — and the customer name is never stored (the rule logs a constant).
  Broken = still UNKNOWN, or a customer name shows up parsed.
  - Confirmed: 0/2
  desk: desk-verifiable: correlate order-ready notification captures with constant ORDER_READY logs and absence of persisted customer names.
  Triage source: first-line blame 2026-06-13 (`1a2bf6bf3`, original README L2585).

- [review] **Sensitive model corrected — block the DASHER's data + ID/signature IMAGES; HASH customers (#463/#485).**
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
  desk: desk-verifiable: replay the named sensitive and instruction-screen families through the capture gate, checking dropped image surfaces and permitted masked instructions.
  Triage source: first-line blame 2026-06-13 (`1a2bf6bf3`, original README L2649).

- [review] **Engine latency + dedupe pack (#436).**
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
  desk: needs field: fast offer responses and a mid-dash relaunch are needed to assess stale notifications, latency and duplicate session effects.
  Triage source: first-line blame 2026-06-12 (`8a465daa3`, original README L2665).

- [review] **Per-offer dedupe now engages (#427).**
  Offer screenshot/log dedupe keys used to resolve to one shared literal, so a second distinct
  offer within 60s was silently swallowed. Now keyed per-offer via `{parsedHash}`. Watch on a
  busy dash (with Evidence master + offers enabled, see #426 item): two different offers
  arriving close together should BOTH capture; the same offer re-rendering (collapse/expand,
  re-observation) should still capture only once. Broken = missing capture for a distinct
  second offer, or duplicate captures of one offer inside the same minute.
  - Confirmed: 0/2. **2026-06-14 (DoorDash):** dasher believes it's working but couldn't verify in the
    field — needs a desk check of this dash's `captures/` (two distinct offers close together → both
    captured?). Stays 0/2 until the log confirms.
  desk: desk-verifiable: compare screenshots/effect records for two close distinct offers and one re-rendered offer; account for newer presentation identity in #830/#859.
  Triage source: first-line blame 2026-06-12 (`1adca1bb9`, original README L2680).

- [review] **Uber sensitive screens now blocked + UNKNOWN-capture scrub (#432).**
  Uber finally has matcher-layer sensitive rules (wallet / Instant Pay / cash-out / bank /
  identity). On a dash, briefly open Uber's earnings/wallet area: working = the app treats it
  as sensitive (no capture, no state change, log shows the sensitive gate) and normal offer
  recognition is unaffected. Also new: UNKNOWN screens whose text contains sensitive markers
  are no longer captured for triage (PipelineStats logs `unknownScrubbed`), and the pipeline
  drops all frames until rulesets finish loading at startup. Broken = an Uber wallet screen
  shows up in captures/, or offer screens misclassify as sensitive (keywords too broad —
  capture the screen text).
  - Confirmed: 0/2
  desk: desk-verifiable: correlate exercised Uber sensitive-screen captures/markers with gate drops and unchanged normal offer recognition.
  Triage source: first-line blame 2026-06-12 (`0d8456a58`, original README L2716).

- [review] **Timeline storeHint now parses + pickup_picked_up rule newly matchable (#433).**
  Two rule fixes from mojibake literals: (a) timeline task rows should now carry store names
  (watch the dash-controls overlay's task chain — logs/cards referencing timeline tasks should
  name the store, not blank); (b) the `pickup_picked_up` screen rule could NEVER match before
  (its require contained a mangled literal) — on the confirm-pickup/loading screen, watch
  whether it now classifies (bubble/log shows pickup_picked_up instead of UNKNOWN) and
  **capture it** — this intent has zero corpus snapshots.
  - Confirmed: 0/2
  desk: desk-verifiable: inspect timeline storeHint parses and pickup_picked_up recognition on the matching capture shapes.
  Triage source: first-line blame 2026-06-12 (`131d3e562`, original README L2740).

- [review] **Tree ingestion now bounded — confirm no real screen trips the caps (#363, PR #391).**
  Accessibility trees deeper than 60 levels or larger than 4,000 nodes truncate with a loud
  log line. The caps carry 2×/10× margin over the corpus, so a normal dash should NEVER hit
  them. Post-dash: grep the log for "Tree ingestion truncated" — any hit means a real DoorDash
  screen is bigger than the corpus suggested and the caps need raising (file it).
  - Confirmed: 0/2.
  desk: desk-verifiable: inspect a representative pull for Tree ingestion truncated and measure any offending tree against the recorded caps.
  Triage source: first-line blame 2026-06-11 (`851c19591`, original README L2761).

- [review] **UNKNOWN capture volume should drop materially (#360, PR #388).**
  UNKNOWN frames now dedup by content hash in a rolling seen-set (animations/list churn
  capture once instead of per frame), with a 200-per-process cap (logged loudly when hit).
  Post-dash check: count files in the capture INBOX vs a May session of similar length —
  the May baseline was ~66% UNKNOWN; expect a large drop. Also confirm genuinely NEW
  unknown screens (any screen you visited that DashBuddy doesn't know) still produce a
  capture, and grep the log for the cap warning — hitting 200 on a normal dash would mean
  the suppressor is too weak.
  - Confirmed: 0/2.
  desk: desk-verifiable: compare UNKNOWN capture counts and distinct content hashes with the baseline, checking cap hits and genuinely new shapes.
  Triage source: first-line blame 2026-06-11 (`2a0823095`, original README L2767).

- [review] **Captures still write on dev builds (#346).** Capture persistence is now bound per build
  variant (debug → disk, release → none). Your field builds are debug, so nothing should change —
  sanity-check after a dash that the session's `captures/` folder is non-empty. If it's ever
  empty, check logcat for "Capture persistence disabled (release build)" — that means a release
  build got dashed by mistake.
  - Confirmed: 0/2.
  desk: desk-verifiable: confirm the fielded build variant and a nonempty captures folder; inspect the release-disabled log if empty.
  Triage source: first-line blame 2026-06-11 (`1ed6efbaa`, original README L3084).

- [review] **Self-recognition fixed: our own bubble is no longer parsed as a DoorDash offer (#4, PR pending).**
  Root cause of the 2026-06-09 offer flip-flop: when the bubble was the active window over DoorDash,
  our own overlay got snapshotted, mislabeled `doordash`, and matched `offer_popup` → a phantom
  re-eval (the spurious DECLINE-6 / "22.5 mi"). Now active-window snapshots are attributed to the
  window's real package (our overlay is dropped), and the offer rule demands the `accept_button`
  structure our overlay lacks. To test: on an offer, **open the bubble** over the DoorDash offer →
  confirm the verdict / notification / spoken read **stay stable** (no flip to DECLINE, no re-eval)
  while the bubble is up. Watch the log for `🚫 Skip active window: non-target pkg=cloud.trotter.dashbuddy`
  (proof our overlay is being dropped) and **no** second `offer_popup` classification. Real orders.
  - Confirmed: 0/2.
  desk: desk-verifiable: correlate active-window package attribution with offer captures; the app's own overlay must create no DoorDash offer.
  Triage source: first-line blame 2026-06-09 (`27410a24c`, original README L2813).

- [review] **Screenshots settle before capture (PR #325).** Captures saved to `Pictures/DashBuddy` should
  be **clean / fully-rendered** (UI settled), not grabbed mid-transition or half-drawn — there's now
  a 500ms settle before every screenshot. Spot-check the offer + post-task captures after a dash.
  - Confirmed: 0/2.
  desk: desk-verifiable: inspect saved offer and post-task screenshots for settled rendering rather than partial transition frames.
  Triage source: first-line blame 2026-06-09 (`8e17112a5`, original README L2850).

- [review] **`nav_arriving` screen now recognized — confirm it fires + gauge frequency
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
  desk: desk-verifiable: census nav_arriving captures near pickup/dropoff timestamps and estimate frequency only where those surfaces occurred.
  Triage source: first-line blame 2026-06-08 (`d265b8c25`, original README L3012).

- [review] **Try EXITING the map with the Exit button (not the back gesture) — capture the
  click.** The dev currently exits nav with the system **back gesture**, so no
  `exit_button` tap is ever captured (0 in all June). The nav screen *does* have an
  explicit **Exit** button (`id=exit_button`). On a dash, deliberately tap that
  **Exit** button a few times (pickup and dropoff) so we capture the click — it may
  give a cleaner, explicit "left navigation" signal than inferring it from the
  window transition. What to watch/capture: that tapping Exit produces a click
  capture (and note whether back-gesture vs button changes what DoorDash shows next).
  - Confirmed: 0/2.
  desk: needs field: the explicit Exit-button tap has never been captured; a back gesture cannot exercise this target.
  Triage source: first-line blame 2026-06-08 (`d265b8c25`, original README L3023).

- [review] **Alcohol delivery ID-verification flow recognized + arrival timing (#149).**
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
  desk: needs field: an alcohol delivery must exercise ID-check and arrival timing; then inspect recognized instructions and blocked scanner output.
  Triage source: first-line blame 2026-06-03 (`fe0246df3`, original README L2930).

- [review] **Offers behind a loading overlay (#275, merged 2026-06-02).** When an offer
  briefly shows a spinner (on present, or right as you tap), confirm it stays
  recognized as an offer — the bubble shouldn't flicker out of the offer view
  or drop to a blank/idle state mid-offer.
  - Confirmed: 0/2.
  desk: needs field: observe a loading overlay on a live offer for transient card loss; a settled capture alone cannot prove continuity.
  Triage source: first-line blame 2026-06-02 (`33bdb650d`, original README L2884).

- [review] **Cashout / transfer screens blocked (#275, merged 2026-06-02).** Open the
  DasherDirect/Crimson balance, a card-details screen, or initiate an instant
  transfer and confirm the bubble does **nothing** (sensitive → skipped),
  rather than reacting to it.
  - Confirmed: 0/2.
  desk: desk-verifiable: correlate the named cashout/transfer surfaces with sensitive-gate handling and absence of captured data/state effects.
  Triage source: first-line blame 2026-06-02 (`33bdb650d`, original README L2889).

### state machine

- [review] **🆕 NEW — #874 / #875 — the Uber HOME screen stopped guessing "offline", and the nav-less
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
  desk: desk-verifiable: correlate Uber home captures and offline events with positive offline evidence; nav-only frames must not end a session.
  Triage source: first-line blame 2026-07-27 (`81f5eb304`, original README L1199).

- [review] **🆕 NEW — #857 / PR #872 — Uber offline detection is now positive-evidence (idle_map rewrite).**
  A partial render can no longer forge Online→Offline; a genuine offline home still recognizes via
  "You're offline" / "You're ready to go online" / the GO button; deliberate toggles ride the
  go_offline click. Deliberate bias: believe-online (a false offline destroyed sessions; a delayed
  offline costs seconds). **What to watch (Uber, especially multi-apping):** no more spurious
  "Started/Done Ubering!" churn while switching apps or gaming; exactly one session per real
  online/offline pair. **Desk:** `session_records` Uber session count == go_online/go_offline click
  pair count; every Online→Offline edge is ≤4 s from a go_offline click or a settled offline frame;
  known residuals #874 (home_dashboard absence arm) + #875 (the "Ready to go?" variant).
  - Confirmed: 0/2
  desk: desk-verifiable: correlate Uber idle-map captures, explicit offline clicks and session boundaries to exclude phantom offline edges.
  Triage source: first-line blame 2026-07-26 (`a71ad28d8`, original README L1437).

- [review] **🆕 NEW — #786 / PR #869 — Uber decline click rule (the X is recognized).**
  The offer card's textless dismiss-X now latches `declineCommittedAt` → declines record as
  `OFFER_DECLINED` instead of the timeout ignorance-default. **What to watch (Uber):** the bubble's
  Dispatch line says "Offer Declined" on your X taps; **THE CRITICAL GUARD — an Uber dash with a
  REAL accepted, driven offer must produce `OFFER_ACCEPTED` + a costed job and NEVER a false
  `OFFER_DECLINED`** (the mislatch hazard the review closed with the own-text-empty predicate; if
  an accept ever records as declined, pull the build and report — that's the revert condition).
  **Desk:** `offer_records` Uber outcome census gains OFFER_DECLINED rows ≈ witnessed X taps
  (~claimed 4/5 in the 07-25 pull); unwitnessed disappearances stay TIMEOUT pending #251.
  - Confirmed: 0/2
  desk: needs field: obtain a real Uber accepted/driven offer and a dismiss-X decline, then check costed jobs and outcome events for false declines.
  Triage source: first-line blame 2026-07-26 (`a71ad28d8`, original README L1447).

- [review] **🆕 NEW — Uber active-job skeleton: accepts become costed jobs at trip start; honest "ON JOB" badge (#762 D2 / PR #784). NEEDS UBER ENABLED.**
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
  desk: needs field: accept and drive an Uber offer to observe the ON JOB badge and verify a costed job survives the long accept grace.
  Triage source: first-line blame 2026-07-15 (`19991604a`, original README L1500).

- [review] **🆕 NEW — WATCH (accepted residual): coarse-only Uber trip may leave its job open past the receipt (#762 D2 / PR #784).**
  A marker-less `on_job_view` frame between the post-trip receipt and idle walks the flow
  PostTask→TaskActive→Idle and suppresses the receipt-exit job close when NO leg screen ever activated
  a task (coarse-only trip). Chosen fail direction is absorption (job stays open; closes at session end)
  — this item quantifies whether the frame shape actually occurs in the field.
  **How to tell (desk-side):** after an Uber dash, any job whose deliveries completed but whose job-close
  event only arrived at `DASH_STOP`; if seen, capture the post-trip → idle frame sequence.
  - Confirmed: 0/2
  - (2026-07-19: N/A — no job existed)
  desk: needs field: a coarse-only Uber receipt-to-idle sequence must occur to measure whether its job remains open.
  Triage source: first-line blame 2026-07-15 (`19991604a`, original README L1528).

- [review] **🆕 NEW — "Shopping off" declines shop offers at the verdict edge (#762 D12 / PR #778).**
  With `allowShopping` off, a shop-type offer now gets a structural `SHOP_DECLINED` verdict (label
  "Shopping off") from the evaluator itself — full economics still computed and shown, score 0,
  decline recommendation — instead of relying on downstream handling.
  **How to tell it's working (needs the strategy toggle off; watch any shop offer):** the offer
  card/bubble shows the "Shopping off" quality label with the decline recommendation while the pay/
  mileage numbers still render; non-shop offers are completely unaffected. Flip the toggle back on
  and a shop offer scores normally again (the gate reads live strategy prefs).
  - Confirmed: 0/2
  desk: desk-verifiable: replay a shop offer with allowShopping off and compare verdict, score and computed economics; visible advice still needs a device check.
  Triage source: first-line blame 2026-07-15 (`2115f4c59`, original README L1538).

- [review] **🆕 NEW — multi-pickup stack: symmetric pickup placeholders + store re-attribution (#526 / PR).**
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
  desk: desk-verifiable: replay a multi-store stack through both pickups/drops and inspect confirmations, store joins and the recorded grace-retire residual.
  Triage source: first-line blame 2026-07-06 (`fe0e73ccc`, original README L1798).

- [review] **🔧 FIX SHIPPED — dropoff handoff waits for the completion CTA + stacked leg-2 drops get their own nav (#603). CONFIRM ON DASH.**
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
  desk: desk-verifiable: correlate handoff/completion captures with arrival events and verify each stacked second drop gets its own navigation lineage.
  Triage source: first-line blame 2026-07-03 (`ad9b4e6d3`, original README L2071).

- [review] **Event log reworked: domain AppEvent + transactional insert + obs-derived timestamps (#354/#300/#119, PR #382).**
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
  desk: desk-verifiable: compare observation timestamps, app_events and idempotency records on replay; completed-card rendering remains a device check.
  Triage source: first-line blame 2026-06-11 (`04afd29c6`, original README L2792).

- [review] **Paused dash auto-expires when the pause clock runs out (#342).** The pause-safety timer
  used to fire into a void (routed to no platform region) — now it reaches the paused region.
  If you pause mid-dash and deliberately DON'T resume: once the pause duration lapses, the HUD
  should flip out of PAUSED on its own (offline with grace) without needing a DoorDash screen
  change. Also regression-watch the normal path: resuming before expiry must NOT flash offline
  (the timer is cancelled on resume).
  - Confirmed: 0/2.
  desk: needs field: allow a real pause to expire and compare the early-resume path; include #1054's newer restart/safety-timer behavior.
  Triage source: first-line blame 2026-06-11 (`950cef6ff`, original README L3056).

- [review] **App-switch mid-dash → "Session resumed (grace)" → dash + task continuity
  (2026-06-03 #3).** The bubble's `"Session resumed (grace)"` message
  (`EffectMap.kt:319`) fires when a region goes Offline then back Online within
  ~10s on the **same** session. An app-switch return can trip this (DashBuddy
  stops seeing DoorDash → reads Offline → resumes on return). When it appears,
  confirm DashBuddy **kept the same in-progress dash AND the active task** with
  earnings intact — it must **not** start a fresh dash, double-start, or forget
  the task (cross-refs #286/#290 grace and 2026-05-29 #2). Also a UX read: is
  showing this internal-sounding message useful, or should it be reworded/demoted?
  - Confirmed: 0/2.
  desk: needs field: app-switch through the grace window and verify the same session and active task survive.
  Triage source: first-line blame 2026-06-03 (`1edea490a`, original README L2944).

### analytics

- [review] **🆕 NEW — #1024 part 1 (PR #1025) — the Playbook destination.** Open Home → **Playbook** tile.
  Check: (a) *This week's plan* shows `Xh worked in your windows · $Y kept of the $Z you planned
  for` and each window row flips to **Done** after its end hour (leave the screen open across an
  hour boundary — it should re-render without leaving); (b) the heatmap outlines exactly the hours
  the saved plan picked, and the Rate/Hours toggle still works; (c) the store leaderboard matches
  what the old Patterns tab showed; (d) Analytics now has three tabs. With **no** plan saved the
  card says so and offers *Build a plan →*, never an empty plan. The screen's footer disclosure
  includes the plan-projection line (lifetime, not a guarantee).
  - Confirmed: 0/2
  desk: needs field: open Playbook and exercise plan, heatmap and hour-boundary rendering on the device.
  Triage source: first-line blame 2026-08-12 (`69e72d33b`, original README L777).

- [review] **🆕 NEW — #1024 part 2 (PR #1026) — Money tab: one number, one place.** Open Analytics on a
  dashed week. Working: kept appears exactly once (the big number), gross exactly once (`$X came
  in.`), deliveries/miles only in the grey facts line; five bordered cards at most (money story,
  rates+day chart, by platform, needs a look, recent sessions) then ONE `How these numbers work`
  row at the bottom of every tab. Broken: a `TOP STORES` card, a second disclosure mid-scroll, or
  `Stayed with you 63%` in the legend (the percentage fallback — the note went null instead of
  blank). Rates survive a single-day/Lifetime window (`—` where a denominator is missing, chart
  simply absent). Two-platform week (needs an Uber dash): one row per platform with chip, bar,
  `$X kept`, count.
  - Confirmed: 0/2
  desk: needs field: count the Money cards and labels on a dashed week, including the two-platform view.
  Triage source: first-line blame 2026-08-12 (`69e72d33b`, original README L786).

- [review] **🆕 NEW — #983 — Time tab: the two hourly rates, your typical online hour, gap stats
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
  desk: needs field: compare Time-tab bars and paging with the dash; the recorded gap-fold checks cover only the data half.
  Triage source: first-line blame 2026-07-30 (`efa9b14e6`, original README L894).

- [review] **🆕 NEW — #981 — Weekly Plan + the Sunday notification (redesign stage 6).** A whole new screen,
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
  desk: needs field: exercise the Sunday notification, plan editor and saved-plan navigation on the device.
  Triage source: first-line blame 2026-07-30 (`ab625bb0b`, original README L927).

- [review] **🆕 NEW — #979 — Patterns: ALL-TIME badge, heatmap Rate/Hours toggle, store leaderboard
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
  desk: needs field: inspect the leaderboard and heatmap in the newer #1024 part 1 Playbook destination; the old Patterns location changed.
  Triage source: first-line blame 2026-07-30 (`c9fe5ff68`, original README L967).

- [review] **🆕 NEW — #975 — Analytics → Offers tab (redesign stage 3).** Open Analytics. The second tab is
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
  desk: needs field: inspect Offers-tab metrics and paging; #1024 part 1 changed the tab count, not all of these assertions.
  Triage source: first-line blame 2026-07-30 (`4b140112e`, original README L1018).

- [review] **🆕 NEW — #810-B2 / PR #847 — orphan offer resolution (inference + attestation).**
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
  desk: needs field: exercise the orphan review/attestation UI after a mismatch; query offer resolutions and undo afterward.
  Triage source: first-line blame 2026-07-23 (`3745bc073`, original README L1299).

- [review] **🆕 NEW — multi-store-from-one-receipt keying: both stores keyed from a single end-of-job receipt, no downgrade on payout-less close (#159 / PR #739).**
  Narrowed scope: the basic-keying half retired 2/2 (07-17 + 07-18/19 — all deliveries carry real running
  keys, no more empty `…|target|` segments). What's left is the harder multi-store case. **How to tell it's
  working (desk-side, after a multi-store stack — e.g. the Target+Maple case):** BOTH stores should be
  keyed from the single end-of-job receipt, not one keyed + one chain-only. And a payout-less close
  (`DASH_STOP` with no summary) must NOT downgrade an already-keyed store back to chain-only. No UI yet
  (the #315 Patterns tab is the consumer) — verify via the DB / a CSV-adjacent read.
  - Confirmed: 0/2 (multi-store-from-one-receipt case still unfielded)
  desk: desk-verifiable: join a multi-store receipt to both stored place keys and verify a payout-less close does not downgrade either key.
  Triage source: first-line blame 2026-07-19 (`c553384d9`, original README L1641).

- [review] **🆕 NEW — Patterns tab store cards: glanceable face + detail bottom sheet (#765 / PR #799).**
  The store report cards were redesigned: the card **face** now shows only store name + location chip
  and three plain-language numbers (Net / Usual wait / Deliveries) — no "median"/"p95" vocabulary —
  with a `>` chevron affordance. Tapping a card opens a **bottom sheet** with the full detail (pickups,
  gross, the dwell distribution labeled "Usual wait (median)" / "Longest waits (p95)" / "Average wait",
  and first/last-seen). **What to watch:** open Analytics → Patterns; the store cards read at a glance
  (2–3 numbers, no stats jargon on the face); tapping a card opens the sheet with the fuller breakdown;
  swipe/scrim dismisses it; "usual wait" on the face matches the median in the sheet; a location-unknown
  (chain-only) card still shows its "partial" note inside the sheet.
  - Confirmed: 0/2
  desk: needs field: inspect store detail-sheet interactions in the current Playbook destination; later #979/#1024 changed the surrounding store layout.
  Triage source: first-line blame 2026-07-18 (`07b4fd86b`, original README L1490).

- [review] **🆕 NEW — categorize a "(No session)" orphan delivery into its real dash (#660 piece 2).**
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
  desk: needs field: assign an orphan delivery through the session picker, then inspect DELIVERY_SESSION_ASSIGN and re-attribution without pay changes.
  Triage source: first-line blame 2026-07-11 (`7ef8df546`, original README L1590).

- [review] **"(No session)" bucket keeps gross ≥ net when a delivery lands without a session (#660 piece
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
  desk: desk-verifiable: query sessionId IS NULL deliveries and reconcile orphan gross/net/day totals, retaining the documented overlap caveat until assignment.
  Triage source: first-line blame 2026-07-10 (`cf369fc17`, original README L3090).

- [review] **🆕 NEW — edit a delivery directly + cash tips (#688 phase A / PR).** In **Analytics → a dash →
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
  desk: needs field: exercise store/pay/cash-tip edits and inspect the projected rows and preserved events; the dialog and qualifier checks need eyes.
  Triage source: first-line blame 2026-07-06 (`164282ba4`, original README L1749).

- [review] **🆕 NEW — receipt-less shop delivery shows est. offer pay, not $0-unattributed (#691 / PR).**
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
  desk: needs field: inspect the estimate qualifier and an exercised receipt-less stack; #996/#997 now replace pooled attribution for some shapes, so old equal-halves copy needs dev review.
  Triage source: first-line blame 2026-07-06 (`dc9315f2e`, original README L1766).

- [review] **🆕 NEW — Money tab 4-step waterfall: Fuel vs Non-fuel, with a clean fallback on mixed periods
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
  desk: needs field: compare all-split and mixed-period cost cards against #1133 / PR #1219's newer coverage guard; check the actual rendered fallback.
  Triage source: first-line blame 2026-07-05 (`005505088`, original README L1907).

- [review] **🆕 NEW — the analytics read-model projector now folds `app_events` into durable records (#314 PR2). READ THE DB / LOG AFTER THE FIRST DASH POST-UPDATE.**
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
  desk: desk-verifiable: compare app_events with projected delivery/session/offer rows and frozen costs before/after an economy-setting change on a replay copy.
  Triage source: first-line blame 2026-07-03 (`b18cd78d9`, original README L1972).

### HUD

- [review] **🆕 NEW — #1024 part 3 (PR #1027) — Home is four blocks.** One **Today** card (kept big → net/hr
  online · drops · miles · On dash/Online → plan strip), one **This week** card (net + delta +
  sparkline + `Recap →`, the plan row if one is saved, `NEEDS A LOOK` if the week flagged), one row
  of four equal-height tiles, one footer. Working if: net appears once per scope; `Recap →` opens
  the hub already on THIS week; **On dash ticks live while dashing** (starts at `0s`, never `—`,
  on a fresh dash) and reads the settled `Online` total otherwise; the plan strip re-dims across an
  hour boundary; a day with no dash shows `—`, never `$0.00`/`0s`; Settings is reachable even from
  the permissions-missing and first-run states (the small Settings link under those cards).
  - Confirmed: 0/2
  desk: needs field: inspect the four Home blocks, live On dash tick and hour-boundary plan dimming.
  Triage source: first-line blame 2026-08-12 (`69e72d33b`, original README L796).

- [review] **🆕 NEW — #936 / PR #952 — an offer with an unreadable distance says so instead of guessing.**
  If a card's mileage ever fails to parse, the HUD/notification should show `no verdict` +
  `distance didn't parse` (no `$0/hr`, no score gauge) and the voice should say *"No verdict — the
  distance didn't parse"*. Rare in the field; the desk check is `offer_records` rows with
  `quality = 'UNKNOWN'` and a null `distanceMiles` — their `score`/`estDollarsPerHour` columns
  must be **null**, never `0`.
  - Confirmed: 0/2 (desk 07-31, the 07-30 dash: the whole `offer_records` table — 383 rows,
    lifetime — shows 0 null `distanceMiles`, 0 `quality='UNKNOWN'`, 0 `score=0`. The failure mode
    never occurred on this device; a clean null result, not a confirmation of the fallback path.
    Stays at 0/2 pending an offer that actually fails to parse distance.)
  desk: desk-verifiable: inspect UNKNOWN-quality offer rows with null distance for absent score/rate; the no-verdict display and voice still need device observation.
  Triage source: first-line blame 2026-07-30 (`d3f31f580`, original README L1047).

- [review] **🆕 NEW — #942 / PR #953 — UI-edge SSOT batch (four quick eyeball checks, one dash).**
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
  desk: needs field: compare the four named UI surfaces for store joining, verdict copy, duration and percentage rendering.
  Triage source: first-line blame 2026-07-30 (`d3f31f580`, original README L1058).

- [review] **🆕 NEW — #867 write-side / PR #873 — bubble chat messages carry their own session.**
  Offer outcomes, session lifecycle, task lines, and the heads-up summary now file into their
  ORIGINATING session's chat even when the other platform's frames flip the active platform
  mid-write. **Desk (multi-app pull):** `chat_messages.dashId` for offer-outcome lines matches the
  offer's own platform session — zero cross-platform mis-files. (Display-side — what the bubble
  SHOWS with two live sessions — is still open on #867, dev call.)
  - Confirmed: 0/2
  desk: desk-verifiable: join chat_messages.dashId to each originating offer's platform session on a multi-app pull.
  Triage source: first-line blame 2026-07-26 (`a71ad28d8`, original README L1474).

- [review] **🆕 NEW — #801 / PR #817 — bubble session-earnings freshness on 0.230.0.** The collapsed receipt
  no longer refreshes session `runningEarnings` (the 0.230.0 digit-wheel is unparseable); the figure now
  rides the dash-control "This dash" label parse alone. After a delivery on 0.230.0, verify the bubble's
  session-earnings figure still updates post-receipt; watch for a stale figure. Desk-side:
  `sessionEarnings=null` on collapsed parses is EXPECTED on 0.230.0; the dash-control parse lines are the
  live source.
  - Confirmed: 0/2 (desk 07-26: the `sessionEarnings=` grep has NO corresponding log site in current
    code — desk hint is dead, a no-hit proves nothing. Indirect evidence only: `[Earnings]: Saved:
    $12.90` + an exact session reconciliation post-install. Needs dev eyes on the bubble figure.)
  desk: needs field: watch post-receipt bubble earnings on the specified platform UI; parsed running totals alone cannot prove visible freshness.
  Triage source: first-line blame 2026-07-20 (`31a7ab9b7`, original README L1409).

- [review] **🆕 NEW — idle bubble: gas + vehicle are now two separate full-width cards (#728 / PR #767).**
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
  desk: needs field: inspect and operate the idle gas/vehicle cards to assess touch targets and mode transitions.
  Triage source: first-line blame 2026-07-12 (`e880c6457`, original README L1548).



- [review] **No transient double drop-off card at the door (#458).**
  On an arrival-bearing dropoff (hand-it-to-customer / photo / PIN) the same delivery briefly
  rendered as TWO cards during the at-door window (a frozen completed copy + the live one). The
  stack now drops the frozen twin when it shares the active card's id. On a dash: working = at
  the customer's door you see exactly ONE delivery card (then the single frozen card + the live
  "Saved" receipt after you complete). Broken = two identical delivery cards stacked at the door.
  - Confirmed: 0/2. **2026-06-14 (DoorDash):** an extra drop-off card DID appear this dash — but it's the
    **premature/unsettled-frame** class (2026-06-13 #1), *not* the frozen-twin overlap this #458 fix
    targets, so this stays 0/2 (the frozen-twin case wasn't disambiguated). The recurrence is tracked
    under 2026-06-13 #1 / the ghost-frame watch — pull the dropoff capture to tell the two apart.
  desk: needs field: watch the at-door transition for a transient frozen/live duplicate; final DB rows cannot establish card overlap.
  Triage source: first-line blame 2026-06-13 (`09a4a57b8`, original README L2627).

- [review] **Post-dash HUD: frozen summary + consistent chat (#367, PR pending).**
  Two visible fixes after a dash ends: (a) the "Last session" Duration on the idle bubble is
  now FROZEN (it used to keep growing while you sat idle — check it shows the real dash length
  and stays put); (b) the chat ticker and the card stack now both show the finished dash
  (the ticker used to go empty the moment the dash ended while cards stayed). Also: platform
  toggles/screens now stop collecting flows while the app is backgrounded — no user-visible
  change expected, just confirm nothing looks stale when foregrounding.
  - Confirmed: 0/2.
  desk: needs field: watch the finished dash's duration stay frozen and confirm chat/card agreement after session end.
  Triage source: first-line blame 2026-06-11 (`851c19591`, original README L2753).

- [review] **HUD numbers/timers re-plumbed through one shared format/time kit (#358, PR #386).**
  All bubble-card money, distance, countdown, and duration strings now come from
  `:core:designsystem` helpers, and the phase chip switched to brand tokens. On a normal dash,
  glance-check: (a) card money/mi/min strings look exactly as before (en-US should be visually
  unchanged); (b) the offer countdown and elapsed timers still tick per second; (c) the phase
  chip color now MATCHES the card's status colors (OFFER chip = same blue family as the offer
  status badge, PAID = green) instead of the old purple/teal M3 roles.
  - Confirmed: 0/2.
  desk: needs field: observe card formatting, per-second timers and phase colors together on the device.
  Triage source: first-line blame 2026-06-11 (`670a76c5a`, original README L2776).

- [review] **Offer evaluation always matches the offer on screen (#345).** Evaluations are now
  hash-correlated, so a rapidly replaced offer can't inherit the previous offer's verdict —
  the heads-up notification + spoken read should always describe the CURRENT offer's economics.
  Watch for any mismatch between the card's numbers and what's spoken/notified, especially when
  offers arrive back-to-back.
  - Confirmed: 0/2. **2026-06-14 (DoorDash):** dasher believes the eval matched the on-screen offer but
    couldn't verify in the field — needs a desk check of this dash's heads-up/TTS vs. the card numbers.
    Stays 0/2 until the log confirms.
  desk: needs field: compare spoken/notified economics with the current card during rapid offer replacement.
  Triage source: first-line blame 2026-06-11 (`950cef6ff`, original README L3063).

- [review] **Deadline countdowns still correct under the new transform clock (#343).** Time parsing
  (`parseDeadline`/`parseTime`) is now anchored to the observation's instant instead of the
  wall clock at evaluation time (replay determinism; the 05-19 "1434:38 ghost countdown" class).
  Live behavior should be identical — confirm pickup/dropoff "by HH:MM" countdowns on the cards
  match DoorDash's stated times, and no absurd ~24h countdowns appear (especially around
  midnight or just-past deadlines).
  - Confirmed: 0/2.
  desk: needs field: compare live pickup/dropoff countdowns with platform deadlines, including the day-boundary case.
  Triage source: first-line blame 2026-06-11 (`bab3f36e4`, original README L3071).

- [review] **Notification text now formatted (verdict bold/colored/larger, headline bold) (#110, PR pending).**
  The heads-up offer notification's text is now an Android `SpannableString` — verdict word (ACCEPT /
  DECLINE / REVIEW) bold, ~1.2× size, colored good/warn/bad; the `$X/hr net` headline bold. To check:
  on an offer, **look at the heads-up notification** and note what actually renders — (a) is the
  verdict **bold + larger**? (b) is it **colored** (green/amber/red)? `MessagingStyle` on Android 12+
  may re-theme/strip the **color** even when bold survives — so report specifically whether the color
  shows. If color is stripped, the line still reads fine; we'd then weigh a `BigTextStyle` variant
  (more reliable spans, but can't coexist with the bubble's MessagingStyle).
  - Confirmed: 0/2.
  desk: needs field: Android notification rendering determines whether the requested bold, size and color spans are visible.
  Triage source: first-line blame 2026-06-10 (`d9b92e3a8`, original README L2804).

- [review] **Shop-for-items offer card shows ONE pickup (repro watch; #338).** On the next HEB/grocery
  shop-for-items offer, screenshot the bubble offer card: it must list the store once. The
  2026-05-17 #5 duplicate's parse-layer cause was ruled out 2026-06-10 (every captured HEB offer
  frame parses exactly one order), and the card was redesigned in PR #324 — so this watches for
  recurrence. If it shows two pickups again, grab the `offer_popup` capture + a screenshot pair so
  parse vs render can be split.
  - Confirmed: 0/2.
  desk: needs field: pair a grocery offer capture with its visible card to resolve the reported duplicate-pickup presentation.
  Triage source: first-line blame 2026-06-10 (`f2a700a5b`, original README L3043).

- [review] **Screenshots still save + no offer-time jank (#349).** Screenshot saving moved fully off the
  main thread (the PNG compress + MediaStore write used to run on main, right when the offer
  card/notification renders). Confirm: (a) offer + dash-summary screenshots still land in
  `Pictures/DashBuddy`; (b) no visible stutter the moment an offer arrives (should be same or
  smoother than before).
  - Confirmed: 0/2.
  desk: needs field: verify saved screenshots and observe offer-time stutter; successful files alone cannot establish smooth rendering.
  Triage source: first-line blame 2026-06-10 (`07c9c45bd`, original README L3050).

- [review] **Offer heads-up notification with Accept/Decline (#110 surface pivot, PR pending).** Since the
  bubble can't auto-expand from the background, an offer now fires a **heads-up notification** showing
  the condensed card (`ACCEPT · $22/hr net` / `Net $22 · 12.9 mi · $1.74/mi · Score 74 · H-E-B`) with
  **Decline** + **Accept** action buttons. Confirm: (1) the notification **pops as a heads-up** while
  you're in DoorDash (it should, unlike the bubble — it's `IMPORTANCE_HIGH`); (2) the summary numbers
  are right; (3) tapping **Accept**/**Decline** from the notification actually performs it on DoorDash
  (same click path as the bubble, now fixed); (4) it lands **after** the offer screenshot (clean
  frame). The bubble is still there to tap open for the full card. Watch for `OfferActionReceiver: …`
  then `Performing offer action …` in the log. Real orders — watch carefully.
  - Confirmed: 0/2.
  desk: needs field: heads-up presentation and the Accept/Decline action path require live device observation.
  Triage source: first-line blame 2026-06-09 (`9d3a98868`, original README L2823).

- [review] **Offer TTS now speaks the EVALUATION, not the raw offer (#110 step ii, PR pending).** The spoken
  read used to announce the parsed offer (`DoorDash offer. $7.50. <store>. 3.2 miles.`); it now speaks
  the verdict + headline economics: e.g. **"Accept. H-E-B. 22 dollars an hour net. Net 22.48, 12.9
  miles, score 74."** Confirm on an offer: (1) it speaks the **verdict word** (Accept/Decline/Review)
  first; (2) the numbers match the card; (3) it fires **once**, right after the eval (≈ in sync with
  the heads-up notification, after the screenshot settle) — not before the eval, not twice. Watch the
  log for `TTS speaking: Accept. …`. Real orders — listen on a quiet leg.
  - Confirmed: 0/2.
  desk: needs field: listen to an evaluated offer and compare the single spoken verdict and numbers with its visible card.
  Triage source: first-line blame 2026-06-09 (`62aaf29e8`, original README L2833).

- [review] **Bubble Accept/Decline now click DoorDash + collapse (re-test of 2026-06-09 #1 + #3, PR pending).**
  The click was searching the wrong window (`rootInActiveWindow` = the bubble); now it searches **all**
  windows, and the collapse cast was fixed (`findActivity`). To test: open the bubble (tap its head,
  since auto-expand is still off — that's the separate notification work), then on an offer tap
  **Accept** or **Decline** → confirm DashBuddy actually taps DoorDash's button (Accept accepts;
  Decline opens DoorDash's confirm), **and** the bubble **collapses to its head** afterward (vs
  dismiss — note which). Watch the log for `Performing offer action …` *without* a following
  `Could not find any live node`. Real orders — watch carefully.
  - Confirmed: 0/2.
  desk: needs field: tap the bubble's Accept/Decline controls and observe both the DoorDash action and collapse.
  Triage source: first-line blame 2026-06-09 (`fdd3869a7`, original README L2841).

- [review] **Offer card redesign — visuals (#110 Stage 1, PR pending).** When an offer arrives, glance
  at the bubble offer card and confirm the new layout reads at a glance: a **score ring** (green/
  amber/red by score) beside the **net $/hr** hero; a **verdict banner** (ACCEPT / DECLINE /
  MANUAL REVIEW + one-line reason + quality chip, tinted to match); **badge pills** when present
  (e.g. High pay / Red Card / Alcohol); and a **live expiry countdown** ticking in the header with
  a depleting progress bar. (No Accept/Decline buttons yet — Stage 2.) Watch for: missing/garbled
  values, countdown not ticking or width-jittering, ring color not matching the verdict.
  - Confirmed: 0/2.
  desk: needs field: inspect the offer card's ring, hero, banner, badges and ticking expiry on the device.
  Triage source: first-line blame 2026-06-09 (`797b4c59b`, original README L2854).

- [review] **Brand theme — HUD legibility & numerals (#94 / #313, PR pending).** With the
  new fixed brand palette + Hanken/Space Grotesk fonts, glance at the bubble HUD
  while driving: confirm it's **legible at a glance** in the dark, the phase/status
  colors read correctly (WAITING green · OFFER blue · PICKUP/DELIVERING green ·
  PAUSED amber · OFFLINE/DONE grey), and that **live-ticking numbers** (offer
  countdown, task timers, $/hr) render in the tabular-figure font and **don't
  jitter / shift width** as they tick. Colors should now be identical regardless of
  phone wallpaper (dynamic color is gone).
  - Confirmed: 0/2.
  desk: needs field: assess HUD legibility, colors and ticking numerals under the requested lighting conditions.
  Triage source: first-line blame 2026-06-09 (`767233633`, original README L2862).

### census

No items in this age/counter group.

### other

- [review] **🆕 NEW — #944 / PR #957 — permission gate re-hoisted (UDF).** The permission bottom sheet's
  behavior should be unchanged: it opens when permissions are missing, advances card-by-card as
  each is granted, closes cleanly when all are granted — and, the edge the refactor specifically
  defends, RE-OPENS with a fresh (not stale) card queue after revoking a permission from system
  Settings and returning to the app.
  - Confirmed: 0/2 (desk 07-31, the 07-30 dash: un-exercised — this is a UI interaction test that
    needs dev eyes revoking a permission from system Settings mid-session; nothing in a desk pull
    bears on it either way. Stays at 0/2.)
  desk: needs field: revoke and regrant a permission to observe a fresh queue on return to the app.
  Triage source: first-line blame 2026-07-30 (`2b418cf28`, original README L1038).

- [review] **🆕 NEW — #938 / PR #954 — English-locale boundary notice.** Briefly switch the phone's
  language to Spanish (Settings → System → Languages), then toggle DashBuddy's accessibility
  service off and back on. Expect: exactly **one** system notification titled "DashBuddy solo lee
  pantallas en inglés" under a new "Avisos de la aplicación" channel, and a `WARN/LocaleBoundary`
  line naming `'es'` (and **not** a region) in the log. Toggle the service again — the WARN
  repeats, the notification does **not**. Switch back to English and toggle once more — neither
  appears.
  - Confirmed: 0/2 (desk 07-31, the 07-30 dash: un-exercised — the device stayed on English
    throughout; the notice/WARN path needs the language-switch-and-toggle exercise on the phone.)
  desk: needs field: switch language and toggle the service twice to observe the one-notification boundary notice.
  Triage source: first-line blame 2026-07-30 (`d3f31f580`, original README L1072).

- [review] **🆕 NEW — Capability consent surface: honest copy + revoke aborts automation to manual (#422 PR 3).**
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
  desk: needs field: exercise grant/revoke and manual fallback against #1167's newer per-rule-action consent model; the old default-on assertion was replaced by #843, but revoke checks remain.
  Triage source: first-line blame 2026-07-18 (`d27716b7d`, original README L1418).

- [review] **🆕 NEW — PII-safe bug-report log export (#551 P2): Data & Privacy → Export Data → Export log.**
  After a real dash (with at least one recognized offer/delivery so INFO milestones exist), go to
  Settings → Data & Privacy → Export Data, scroll to "Export a bug report", pick a folder, export.
  How to tell it's working: (1) open `dashbuddy-log.txt` and **grep your shop's merchant name — ZERO
  hits** (it's INFO+ milestones only, scrubbed at the sink); any `[scrubbed:<marker>]` lines are the
  gate catching a leak. (2) The success line's auto-scrub count is sane (usually 0, non-zero means an
  upstream site leaked and the sink caught it — worth reporting). (3) The firehose (`app.log` in the
  app's files dir) is still **full fidelity** — raw store names present there, as expected (on-device
  only, never exported). (PR #551-P2)
  - Confirmed: 0/2
  desk: desk-verifiable: export a bug-report log from a representative pull and compare its scrubbed INFO stream with the private DEBUG log and scrub count.
  Triage source: first-line blame 2026-07-05 (`ad091273c`, original README L1814).

- [review] **Evidence Locker settings are now real (#426).**
  Screenshots (offer / delivery / dash-summary PNGs in Pictures/DashBuddy) previously fired
  unconditionally; they are now gated on the Evidence settings, whose master toggle defaults
  OFF. On a dash with settings untouched: working = NO new PNGs appear at all. Then flip
  Master Record + a category on mid-dash: working = only that category's screenshots appear.
  Broken = PNGs appear with master off, or an enabled category stops capturing (look for
  "Evidence capture suppressed" in logs with an unexpected category).
  - Confirmed: 0/2
  desk: needs field: toggle Evidence master/categories mid-session and compare the resulting screenshot files with enabled categories.
  Triage source: first-line blame 2026-06-12 (`80058c9f3`, original README L2691).

- [review] **Platform toggles now take effect live — no app restart (#356, PR #384).**
  All notification/accessibility gating now reads one shared enabled-platforms state. To check:
  mid-session, toggle a platform OFF in DashBuddy settings — its notifications should stop
  reaching the HUD/log immediately (next notification, not next restart); toggle back ON and
  they resume. If convenient, also note whether gating still works after Android kills/rebinds
  the notification listener (e.g. after a long screen-off period) — the old code froze gating
  at the last value when that happened.
  - Confirmed: 0/2.
  desk: needs field: toggle a platform off/on and exercise service rebind to observe immediate gating changes.
  Triage source: first-line blame 2026-06-11 (`33680a6ec`, original README L2784).

- [review] **Economy fields keep the decimal point while typing (#350; desk-verifiable, no dash
  needed).** In Settings → Personal Economy (or the wizard's costs step), type `12.5` into any
  cost field — the `.` must survive (the value round-trip used to reset the text mid-typing and
  eat the separator). Also confirm: the live `$ /mi` footer still updates per keystroke, and the
  field normalizes (e.g. `012.50` → `12.5`) when you tap away.
  - Confirmed: 0/2.
  desk: desk-verifiable: on the device at the desk, type 12.5 and 012.50 in the cost editor, checking keystroke footer updates and blur normalization.
  Triage source: first-line blame 2026-06-11 (`c69ea9bc2`, original README L3078).

## §4 1/2 items older than 6 weeks

32 items retain `[active]`: the aging rule applies only to 0/2. Dates below summarize existing confirmation notes, including partial checks; this extraction adds no evidence and proposes no automatic closures. Groups are ordered newest first by first-line blame.

### recognition

- [active] **🆕 NEW — #796 / PR #837 — DoorDash recognition-gap batch (11 rules).** Dropoff issue
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
  Triage source: first-line blame 2026-07-23 (`722aa4c19`, original README L1357). Recorded first confirmation: desk 2026-07-26 (partial families) (existing evidence only).

### state machine

- [active] **🆕 NEW — #1000 (PR #1013) — a blown-through pickup still links its offer to the job.** Watch
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
  Triage source: first-line blame 2026-08-09 (`f41237874`, original README L830). Recorded first confirmation: desk 2026-08-24 (refold only) (existing evidence only).

- [active] **Drop-off GPS runs while parked at the door (#294 — CONFIRMED at desk 08-09, dev-eyes half
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
  Triage source: first-line blame 2026-08-09 (`f41237874`, original README L3032). Recorded first confirmation: desk 2026-08-09 (mechanism; fix still awaited) (existing evidence only).

- [active] **🆕 NEW — #830 / PR #839 — presentation-scoped offer identity (+ the #826 accept chain).** The
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
  Triage source: first-line blame 2026-07-23 (`f94718bc3`, original README L1316). Recorded first confirmation: desk 2026-07-26 (existing evidence only).

- [active] **🆕 NEW — #810 B1 / PR #818 — JOB_ACCEPT_MISMATCH close tripwire.** A job closing with more accepted
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
    downstream of it. Still stays open solely for the true-positive half.
    Desk 09-09, the 09-08 dash on `8028691a`: **BLIND-IN-PART** — the true-positive shape finally occurred
    (job −444 closed with 1 accepted $21.00 offer / 0 accounted drops, the #1078 recurrence) and the tripwire
    emitted nothing, because `JobCloseEffects.kt:38-45` excludes the `endSession` teardown by design. Filed
    as #1095. The item stays open for the true-positive half on a within-session close.)
  Triage source: first-line blame 2026-07-20 (`022633e47`, original README L1374). Recorded first confirmation: 2026-07-21 dash, desk 2026-07-22 (negative half) (existing evidence only).

- [active] **🆕 NEW — a same-customer double-order job closes at its receipt; the next offer is its OWN job (#749).**
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
  Triage source: first-line blame 2026-07-11 (`c0a5f966f`, original README L1561). Recorded first confirmation: 2026-07-26 dash, desk 2026-07-27 (existing evidence only).

- [active] **🆕 NEW — offer lifecycle unchanged single-platform after the platform-owned-offers move (#438 B3 / PR).**
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
  Triage source: first-line blame 2026-07-06 (`093eac45a`, original README L1723). Recorded first confirmation: 2026-07-07 and 2026-07-08 desk analysis (machine half) (existing evidence only).

- [active] **Session-end grace — summary no longer ends the dash instantly (#431).**
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
  Triage source: first-line blame 2026-06-12 (`298718b52`, original README L2727). Recorded first confirmation: 2026-06-14 (partial) (existing evidence only).

- [active] **New dash right after ending one starts fresh (#286 / #279-B / #290).** End a
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
  Triage source: first-line blame 2026-06-03 (`eb1543cc1`, original README L2902). Recorded first confirmation: 2026-06-03 (partial), with further subcases recorded 2026-06-07 (existing evidence only).

### analytics

- [active] **🆕 NEW — #996/#997 (PR #1012) — per-offer pay attribution on a receipt-less dash.** On any
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
    - desk 09-21: degraded exactly twice, and both degrades are the machine reporting #1119
      honestly — 2 × `#997 offer-pay attribution degraded to JOB_POOLED … 0 offer(s) over 1 drop(s)`
      + 2 × `#691 … denominator=1 of 1 owed, ownOfferPay=null, jobOfferTotal=null`, all four on the
      lost-accept jobs …-668 and …-687. Not an attribution defect: there was no offer to attribute.
      Shapes (b) and (c) still unseen.
  Triage source: first-line blame 2026-08-09 (`f41237874`, original README L805). Recorded first confirmation: desk 2026-08-24 (shape a only) (existing evidence only).

- [active] **🆕 NEW — Analytics → Patterns tab: store report cards + net-$/hr heatmap (#315 H5 / PR).**
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
  Triage source: first-line blame 2026-07-10 (`743db59a5`, original README L1649). Recorded first confirmation: desk 2026-08-09 (data half) (existing evidence only).

- [active] **🆕 NEW — per-dash drill-down (#650 PR A): tap a recent dash on the Money tab.** Analytics → Money
  tab → scroll to RECENT DASHES → tap any dash row. It should open a read-only "Dash detail" screen.
  How to tell it's working: the header figures (date, start–end clock times, duration, gross, miles,
  deliveries) match that dash's summary, and the per-delivery rows list each drop (store, completion
  time, pay, tip, net, miles/min) matching what you actually delivered. When the platform-reported
  total exceeded the captured delivery pay, an "unaccounted on this dash" callout appears. (PR #650-A)
  - Confirmed: 1/2 — 2026-07-05 (DoorDash, 5 deliveries): used live — header + per-delivery rows matched the dash; the unaccounted callout showed the real $39.45 gap.
  Triage source: first-line blame 2026-07-05 (`2a3add132`, original README L1825). Recorded first confirmation: 2026-07-05 (existing evidence only).

- [active] **🆕 NEW — user corrections as events (#650 PR B): add a missed delivery, adjust a pay.** On a dash
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
  Triage source: first-line blame 2026-07-05 (`dd0df7acc`, original README L1833). Recorded first confirmation: 2026-07-05 (existing evidence only).

- [active] **🆕 NEW — CSV data export (#319).** Settings → Data & Privacy → "Export Data (CSV)" → tap
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
  Triage source: first-line blame 2026-07-05 (`b759ac830`, original README L1857). Recorded first confirmation: 2026-07-05 (existing evidence only).

- [active] **🆕 NEW — Analytics tile now opens the Money tab (#315 H1 / Money tab v1).**
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
  Triage source: first-line blame 2026-07-05 (`7534abd51`, original README L1894). Recorded first confirmation: 2026-07-05 (existing evidence only).

- [active] **🆕 NEW — Analytics Decisions tab: offer funnel + value-of-saying-no + score-vs-outcome (#315 H3).**
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
  Triage source: first-line blame 2026-07-05 (`f5cac9467`, original README L1918). Recorded first confirmation: 2026-07-05 (existing evidence only).

- [active] **🆕 NEW — Analytics Time tab: time split, deadhead, on-time gauge, mileage & tax (#315 H4).**
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
  Triage source: first-line blame 2026-07-05 (`9fc69bcb4`, original README L1931). Recorded first confirmation: 2026-07-05 (existing evidence only).

- [active] **🆕 NEW — Analytics Money tab: earnings-by-day chart + header CSV export icon (#315 H6).**
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
  Triage source: first-line blame 2026-07-05 (`2c32e53ce`, original README L1948). Recorded first confirmation: 2026-07-05 (existing evidence only).

### HUD

- [active] **🆕 NEW — #867 (display side) — the bubble says WHICH dash it is showing, and you can switch it.**
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
  Triage source: first-line blame 2026-07-27 (`9a41c99b5`, original README L1172). Recorded first confirmation: 2026-07-29 dash, desk 2026-07-30 (write-side only) (existing evidence only).

- [active] **🆕 NEW — bubble gas quick-edit is mode-adaptive: refresh (AUTO) vs. Resume auto (MANUAL) (#722 / PR).**
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
  Triage source: first-line blame 2026-07-07 (`c61291251`, original README L1703). Recorded first confirmation: 2026-07-12 (existing evidence only).

- [active] **🆕 NEW — bubble post-dash card shows the TRUE last session + gas/vehicle quick actions (#693 / PR).**
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
  Triage source: first-line blame 2026-07-06 (`0329e5d9e`, original README L1687). Recorded first confirmation: 2026-07-07 screenshot (existing evidence only).

- [active] **Offer card surfaces Shop & Deliver: item count in the hero row + a SHOP badge (#461 a/b).**
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
  Triage source: first-line blame 2026-06-13 (`299145d7f`, original README L2533). Recorded first confirmation: 2026-06-14 (single-order only) (existing evidence only).

- [active] **Pickup/Delivery task cards redesigned to the co-hero design (#460/#324).** The task cards
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
  Triage source: first-line blame 2026-06-13 (`39a54a998`, original README L2592). Recorded first confirmation: 2026-06-14 (pickup only) (existing evidence only).

- [active] **Bubble keeps showing the last dash after it ends / after a crash (#459).**
  The bubble's chat + card stack used to go EMPTY after a dash ended (8b: collapse it >5s then
  reopen) or after a crash with no active dash (8a) — the fallback dash id was a volatile
  in-memory latch. It's now sourced durably from the event log (most-recent dash). On a dash:
  end a dash, collapse the bubble for >5s, reopen → working = the chat + completed cards of the
  just-finished dash are still shown (not empty); start the next dash → it switches to the new
  dash. Also force-stop/crash right after a dash and reopen → still shows the last dash. Broken =
  empty chat/cards after dash-end-then-reopen, or the wrong dash shown.
  - Confirmed: 1/2. **2026-06-14 (DoorDash):** after the dash ended the bubble **kept showing the last
    dash** (chat + completed cards), not empty — clean confirmation of the #473 durable fix. One more.
  Triage source: first-line blame 2026-06-13 (`273742fba`, original README L2609). Recorded first confirmation: 2026-06-14 (existing evidence only).

- [active] **Pickup/Delivery card deadline reads cleanly — no double "by" (#460).**
  The deadline caption read `till pickup-by · by 17:10` (two "by"s); now `till pickup · by 17:10`
  / `till deliver · by 17:10`. Desk- or dash-verifiable on any pickup/delivery card. (The
  separate pickup/delivery card visual-parity redesign stays tracked in #460.)
  - Confirmed: 1/2. **2026-06-14 (DoorDash):** caption reads **fully fixed/different from before** — no
    double "by". One more to validate.
  Triage source: first-line blame 2026-06-13 (`92ef59a43`, original README L2620). Recorded first confirmation: 2026-06-14 (existing evidence only).

- [active] **"Saved: $X" bubble shows the dollar sign now (#456).**
  The post-delivery earnings bubble rendered `Saved: 5.50` (no `$`) because the state layer had
  its own money formatter that omitted it. Both local formatters are gone — money now formats
  through one `:domain` `Formats.money` SSOT. On a dash, after a delivery: working = the "Saved"
  bubble reads `Saved: $5.50` (with the `$`); the dash-summary "Session Ended. Total: $X" and
  the offer notification's `$/hr`/`$/mi` should all still read correctly. Broken = a missing or
  doubled `$`, or a wrong decimal.
  - Confirmed: 1/2. **2026-06-14 (DoorDash):** the "Saved" bubble shows `$X.XX` (with the `$`, 2 decimals)
    on **all** of them now — confirmed. (The separate "tip added" bubble is still a raw float — 2026-06-13
    #3.) One more to validate.
  Triage source: first-line blame 2026-06-13 (`c057ba1fa`, original README L2638). Recorded first confirmation: 2026-06-14 (existing evidence only).

- [active] **Bubble HUD no longer crashes on arrival-bearing dropoffs (#297).** Complete a
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
  Triage source: first-line blame 2026-06-04 (`a02fcdbcf`, original README L2955). Recorded first confirmation: desk 2026-06-07 (crash half) (existing evidence only).

- [active] **Shop & Deliver items/min (#276, merged 2026-06-02).** On a real Shop &
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
  Triage source: first-line blame 2026-06-02 (`33bdb650d`, original README L2871). Recorded first confirmation: 2026-06-03 (partial) (existing evidence only).

- [active] **End-of-dash summary attribution (#279).** End a dash and watch the bubble:
  the **dash summary** (total earnings / duration) should land and attribute to
  the just-ended dash — whether the summary shows BEFORE or AFTER the
  idle/offline screen (the after-idle ordering was the bug). It must NOT finalize
  as a thin "early offline" the instant the idle/offline screen appears; the rich
  total should reach the HUD.
  - Confirmed: 1/2. **2026-06-14 (DoorDash):** dash summary landed on the just-ended dash
    (totals/duration correct), not a thin early-offline finalize. One more to validate.
  Triage source: first-line blame 2026-06-02 (`cbd577b77`, original README L2894). Recorded first confirmation: 2026-06-14 (existing evidence only).

### census

No items in this age/counter group.

### other

- [active] **🆕 NEW — #991 (P0) — the spoken offer verdict went silent and stayed silent.** From 08-06
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
    - (desk 09-21: no-regression — 22/22 `speak()` succeeded on the 09-20 dash, zero `-1`, zero
      re-init, zero notice; the ladder was untouched again.)
    - (desk 09-09: no-regression only — 17/17 `speak()` succeeded, zero `-1`, zero re-init, zero notice;
      the ladder was untouched.)
    - (desk 09-05: the recovery seam fired live 08-30 15:44:52 — one `speak()` loss →
      rebuild → engine re-initialized in 417 ms; 34/35 utterances spoke.)
    - (desk 08-24: voice healthy — 28/28 `speak()` succeeded across four dashes — but the
      engine never failed, so the ladder was unexercised; no-regression, not a confirmation.)
  Triage source: first-line blame 2026-08-09 (`2f57a0842`, original README L841). Recorded first confirmation: 2026-08-30 recovery, recorded at desk 2026-09-05; the counter line itself is undated (existing evidence only).

- [active] **🆕 NEW — #843 — prompted per-capability automation consent (no auto-grant).** Automations are
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
  Triage source: first-line blame 2026-07-23 (`d394b8713`, original README L1217). Recorded first confirmation: 2026-07-24 install, desk 2026-07-26 (mechanism only) (existing evidence only).

- [active] **🆕 NEW — Principle-7 logging phase 1: INFO+ log is PII-safe, `SCREEN:` spam gone, real WARNs
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
  Triage source: first-line blame 2026-07-05 (`77ef51f20`, original README L1870). Recorded first confirmation: 2026-07-05/06 pull (existing evidence only).

## §5 Field-validated / retired

- [field-validated] **Shop & Deliver items/min reaches `total/total` at the end (#302).** On a
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
  Triage source: first-line blame 2026-06-06 (`e723d4fe6`, original README L2975).

### Superseded (dev-confirmed 2026-10-07 — the replacing redesign is named on each; the #657 earnings-freshness check is retained in §2)

- [superseded] **🆕 NEW — #977 — Home is now "Today" (redesign stage 4).** Open the app's home screen (not the
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
  desk: superseded by #1024 part 3 / PR #1027, the later “Home is four blocks” item replaces #977's clock/status/section layout; dev to retain any uncovered plan checks before pruning.
  Triage source: first-line blame 2026-07-30 (`ed1a0eded`, original README L989).

- [superseded] **🆕 NEW — the main dashboard is now a REVIEW surface, not a live bubble mirror (#657 / PR #658).**
  Open the app **after a dash** (not while on a task): the **Today** tiles (True Net / Net $/hr /
  Miles) should already reflect the just-completed dash with no manual refresh (the read-model folds
  each delivery as it completes). Tap the **Today / This week / Lifetime** selector and confirm the
  three tiles switch to each window's totals. The old live "This dash" ticking hero is **gone** —
  there should be **no** per-second $/hr counter on this screen. While you're online, a slim
  "🟢 Dashing — tap for the bubble" row appears above the tiles; tapping it should re-show the bubble.
  How to tell it's broken: tiles frozen/stale after a dash, the segmented selector not changing the
  numbers, a live ticking counter still present, or the dashing row showing while offline.
  - Confirmed: 0/2
  desk: superseded by #1024 part 3 / PR #1027, the later “Home is four blocks” item replaces the segmented Today/This week/Lifetime tile layout and explicitly restores a live On dash tick. — RETAIN the post-dash earnings-FRESHNESS check (#657) before pruning: the #1024 part 3 / PR #1027 replacement tests the four-block layout, not that the totals refresh after a dash (Astra review of PR #1246).
  Triage source: first-line blame 2026-07-04 (`2b315e708`, original README L1962).

- [superseded] **🆕 NEW — the home screen's top glance is now REAL "Today" totals from the read model (#314 PR3, completes #314).**
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
  desk: superseded by #1024 part 3 / PR #1027, the later “Home is four blocks” item replaces the three Today tiles plus separate This dash row; dev to carry forward uncovered midnight/frozen-history checks before pruning.
  Triage source: first-line blame 2026-07-04 (`07e3092d4`, original README L1987).

- [superseded] **✨ NEW — the home screen now shows a live "This dash" glance + entry tiles (#320/#316).**
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
  desk: superseded by #1024 part 3 / PR #1027, the later “Home is four blocks” item replaces the live three-tile glance and old 2×2 navigation grid; dev to retain uncovered Ratings checks before pruning.
  Triage source: first-line blame 2026-07-03 (`5ed357c6c`, original README L2035).
