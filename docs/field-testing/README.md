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

- **🆕 NEW — quick-decline auto-confirm + earnings auto-expand no longer "click first" on an ambiguous target (#734 / PR).**
  Two actuation surfaces used to resolve **2 verified candidates** and tap the first-in-tree one (luck, not
  verification): the offer confirm-decline sheet (`CONFIRM_DECLINE`) and the collapsed delivery-summary chevron
  (`EXPAND_EARNINGS`). The rule bindings are now tightened (confirm sheet anchors the exact "Decline offer"
  label; the summary binds the pay/"This offer" expandable, never the "Total online time" stats one), and an
  ambiguous target now **aborts to manual** instead of clicking.
  **How to tell it's working (needs quick-declines enabled; watch a few declines + a couple completions):** the
  quick-decline still auto-confirms the "Are you sure?" sheet (declines take effect), and the post-delivery
  earnings breakdown still auto-expands to the **pay** section (not the online-time stats). **Desk-side:**
  `grep -iE 'refusing to click \(fail closed\)|No decisive match' shareable.log` — a WARN there means the tie
  path fired and correctly aborted (the dev then finishes it by hand); the *count* dropping toward zero vs the
  07-07/07-08 dashes (which saw 4× confirm + 3× expand ties) is the win. No wrong-button taps in the event log.
  - Confirmed: 0/2

- **🆕 NEW — notification-listener rebind rate is now quantified (#731 instrumentation / PR).**
  The NLS connect/disconnect lifecycle now rides `PipelineStats` counters and leveled log lines
  (tag `Pipeline`): the FIRST disconnect per process announces the degradation at WARN, every
  subsequent disconnect and all connects ride INFO — all with running counts — so the
  field-observed 129–240 cycles/day flap is measurable from a pull without drowning the WARN slice.
  **How to tell it's working (desk-side only):** after a dash,
  `grep -i 'notification listener' shareable.log` shows the paired lines with running counts
  (`connects − disconnects ≈ process deaths` per process — a kill never logs its disconnect), and
  the periodic `PipelineStats` summary carries `notifListenerConnects=`/`notifListenerDisconnects=`
  (corroboration only — it emits per 50 forwarded observations, so it's quiet while idle).
  The counts themselves feed the #731 root-cause call (battery-optimization kills vs other) — high
  counts are the *expected* finding, not a failure of this item.
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
  - Confirmed: 0/2

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

- **🆕 NEW — per-leg mileage: stacked drops each carry their own miles (#688 phase B).**
  Delivery rows now fold `milesToStore`/`milesToDropoff` from the lifecycle odometer stamps, and a
  drop's miles become the leg sum (to-store + to-dropoff) instead of the old lump-on-one-row
  partition delta — the 07-05 Bill Miller/Mama Margies shape (6.76 mi on drop A, 0.0 on drop B)
  should be gone, retroactively too (the `PROJECTOR_VERSION` 5→6 refold reworks history on first
  launch after install; expect a one-time refold at startup).
  **How to tell it's working (desk-side, after a dash with a stacked/multi-store job):** in the
  per-dash drill-down (and `delivery_records`), each drop of a stack shows a plausible nonzero
  `X mi` (not one lump + 0.0); **Σ per-drop miles ≤ the session odometer miles** — this is a
  ONE-SIDED invariant: an undershoot is EXPECTED (the arrival→completion dwell/drift, and any store
  legs retired when a drop missed its arrival, land in the deadhead remainder), but Σ must NEVER
  EXCEED the session span (that would be the mixed-basis double-count the #688-review Fix 1
  closes — strictly guaranteed, except in a session with a mid-dash odometer reset, where all
  mileage invariants are void anyway); the CSV export's `miles_to_store`/`miles_to_dropoff` columns
  are populated for drops whose arrival frames fired; and a driver miles edit via the Adjust dialog
  still wins the row total (the leg columns keep the machine estimate — a deliberate mismatch, not a
  bug). A drop with a missed `DELIVERY_ARRIVED` (no arrival frame) legitimately falls back to the old
  partition delta with blank leg columns (and the session's already-closed store legs are retired so
  no later drop re-claims them). An order unassigned mid-dash (#736) contributes NO per-drop leg
  miles — its distance stays session-level only.
  - Confirmed: 0/2

- **🆕 NEW — stacked receipts still split exactly; ±1¢ drift and collapsed-receipt nulls gone (#630).**
  The per-drop receipt split is hardened for mid-stack/multi-receipt shapes: a collapsed PostTask
  re-render can no longer wipe an already-captured itemized receipt (the field-reachable break), the
  split denominator now equals exactly the rows that mint, and the rounding cent is order-invariant.
  **How to tell it's working (desk-side, after a dash with ≥1 stacked job):** in the per-dash
  drill-down, each stacked job's delivery rows sum EXACTLY to its receipt total (no missing-pay row
  where a receipt existed, no ±1¢ mismatch); the Money tab's unattributed callout doesn't grow from
  stacked jobs. A `#630 mid-stack non-final receipt exit` WARN in the exported log (now also tripped
  by a pay-bearing *collapsed* receipt, not only an itemized one) would be a genuine field sighting of
  the mid-stack receipt shape — grab the capture session if you see it. NOTE (PR #754 review): the
  final-shape gate no longer wedges shut on a never-activated placeholder (#749) or an unassigned
  sibling (#736), so a normal single-delivered-drop stacked job must attach its full receipt (not fold
  a $0/NONE row) — watch that a receipted delivery never shows $0 when a placeholder/unassigned
  sibling exists.
  - Confirmed: 0/2

- **🆕 NEW — unassign an order mid-dash produces NO paid/confirmed artifacts (#736).**
  When you unassign an active order via the pickup help / resolution flow ("Unassign order" survey →
  "You've been unassigned from this order" confirmation), the app now recognizes the unassign and
  **inline-abandons** the task instead of fabricating a pickup confirmation. On the previous build a
  mid-shop unassign minted a ghost `PICKUP_CONFIRMED` (the 07-07 H-E-B seq-71 bug).
  **How to tell it's working (on-dash + desk-side):** right after you unassign, expect an
  **"Unassigned: <store>" bubble**, the job/card clears, and the **next offer works normally**.
  Desk-side, the exported log / `app_events` for that dash should show **exactly one
  `TASK_UNASSIGNED`** for that order and **no `PICKUP_CONFIRMED` / `DELIVERY_COMPLETED` /
  `DELIVERY_CONFIRMED`** for it, no fake "$0 PAID" delivery in the Money tab, and no bogus shop-rate
  sample. Try it on a shop order (H-E-B-style) where you've already started shopping.
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

- **🆕 NEW — multi-pickup job lands under a REAL store, no WARN storm (#733 / PR #745).**
  A multi-pickup job (a stacked stack, or a single customer whose order spans two stores like the
  07-08 Willie's + Sonic delivery) previously folded its delivery as "Unknown store" and spammed the
  log with `D6 join miss` WARNs (129–240/dash). The dropoff-store lineage now (a) canonicalizes the
  customer-name hash (`normalizeCustomerName` before `sha256`) so multi-drop joins stay stable
  across the surface FORMS a name renders in, (b) resolves a single-customer multi-store job via the
  constrained multi-match join (earliest-confirmed store, only when unambiguous — two drops sharing
  a hash fall back rather than guess), and (c) fires the join-miss WARN at most **once per task**.
  **How to tell it's working (desk-side, after a dash):** on a multi-pickup delivery, the
  `delivery_records` row (and the per-store view) shows a real store, not "Unknown store"; and the
  exported `shareable.log` has **at most one** `D6 join miss` line per drop (ideally zero), not the
  old ×23 burst. Watch a two-store single-customer job especially. A nickname-vs-legal-name render
  that no normalization can bridge may still fold null (documented residual — fix via Adjust dialog).
  - Confirmed: 0/2
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
- **🆕 NEW — per-platform shop-rate learning; shop offers price sanely after the reset (#588 / PR).**
  The learned shopping pace (items/min) is now keyed per platform, and the old global learned value was
  **dropped** (restart-learning, no migration) — every platform relearns from its 0.8/min seed over ~5 shops.
  **How to tell it's working (on a DoorDash dash):** shop pricing may shift toward the 0.8/min seed
  until ~5 shops relearn the pace on this platform (the discarded value was the dev's own LEARNED mean,
  not necessarily close to 0.8) — a Shop & Deliver offer's handling time / $/hr should still read sane,
  not absurd, in the meantime; after ~5 real shops the learned pace takes back over. **Desk-side after
  the dash:** the shareable INFO log's
  `ShopRate` lines now carry a `[doordash]` platform tag; there should be no cross-platform bleed if a
  second platform (Uber/Instacart) is ever shopped. Watch for any shop offer suddenly reading an absurd
  $/hr (would mean the seed/reset went wrong). The same INFO line now also carries the post-fold
  `→ learned X.XX/min (n=N)` suffix (the desk window into the DataStore-only learned mean — watch `n`
  climb from the reset and the mean converge off the 0.8 seed; `learned ?/min (n=0)` means nothing
  learned yet, not a zeroed mean).
  - Confirmed: 0/2
- **🆕 NEW — store entity resolution keys real stores from live dashes (#159 / PR).**
  The read-model now resolves each job's stores (`stores` + `pickup_records` tables) from captured
  pickup/dropoff/payout surfaces. **How to tell it's working (desk-side, after a dash):** on a job with a
  payout receipt, the `stores` table should hold one keyed row per store (`storeKey` ending in a real
  running key like `…|target|02426`, not an empty `…|target|` segment) and the delivery/pickup rows for
  that job should carry that `storeKey`. **Watch especially the multi-store stack** (e.g. the Target+Maple
  case): BOTH stores should be keyed from the single end-of-job receipt, not one keyed + one chain-only.
  And a payout-less close (`DASH_STOP` with no summary) must NOT downgrade an already-keyed store back to
  chain-only. No UI yet (the #315 Patterns tab is the consumer) — verify via the DB / a CSV-adjacent read.
  - Confirmed: 0/2
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
  - Confirmed: 0/2. **2026-07-12 partial dev sighting (post-#763 build):** the heatmap section (B)
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
- **🆕 NEW — over-attribution review flag on the Money tab + per-dash drill-down (#701).**
  The Money tab's unattributed-pay callout ("$X not attributed…") now has a mirror: when a
  session's **captured delivery pay exceeds** the platform's reported summary total (the
  opposite direction — e.g. a phantom offer-pay estimate or a mixed-receipt over-stamp), a
  second callout appears ("attributed exceeds reported by $X — review estimates/corrections"),
  tinted with the stronger `badBg` (not the softer `warnBg` the unattributed one uses). The
  per-dash drill-down (tap a session in Recent Dashes) shows the same mirror callout when that
  ONE dash is over-attributed. **What to watch:** on a normal dash where reported ≈ delivered,
  neither callout (or only the unattributed one) should appear — the new callout is a **display-only**
  signal and must never move the True-Net waterfall's Net figure or the stat tiles. If you ever see
  a dash where delivered pay looks higher than the platform's own reported total (rare — usually a
  DoorDash shop/Drive job with an estimate involved), confirm the new callout fires with the correct
  dollar amount and that Net/hr, Net/mi, and the waterfall are unaffected.
  - Confirmed: 1/2 (07-07 + 07-08 desk analysis: the negative path — all 5 moneyed sessions
    reconciled EXACTLY (Σ delivered = reported), so no false callout was possible on either dash
    and none was reported. The positive-fire path — a genuinely over-attributed dash — remains
    unobserved; rare by nature.)
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
    clean on BOTH dashes. Second confirmation should be dev-eyes on (1)–(3): card visuals,
    heads-up notification, TTS.)
- **🆕 NEW — heads-up banner acts on the RIGHT offer (#438 B4 / PR).** Each offer's heads-up
  notification now has its own id + its own Accept/Decline intents (distinct per offer), so
  concurrent or fast-replacement offers no longer clobber one another. On a normal DoorDash dash,
  confirm single-offer behavior is unchanged: the banner posts, **Accept/Decline still fire the
  verified click**, and the banner **disappears the moment the offer resolves** (accept / decline /
  timeout) — no stale Accept/Decline banner lingering after the offer is gone. The multi-offer edge
  (best seen if a second offer arrives while the first banner is still up, or an offer is replaced
  same-platform): tapping the current banner acts on the **current** offer, and resolving one offer
  leaves the other's banner untouched — a tap must never act on a **replaced/older** offer (that path
  now WARN-aborts to manual rather than acting on the wrong one). A banner that acts on the wrong
  offer, or a stale banner that survives its offer, is a B4 regression — capture it. **Desk-side:**
  each tap's `OfferActionReceiver:` INFO line now carries `(offer=<hash>)` (the full hash, same
  rendering as OfferEffects) — exact-match it to the resolved `OFFER_ACCEPTED`/`OFFER_DECLINED`
  event's hash; a mismatch is the wrong-offer regression, no eyes needed.
  - Confirmed: 0/2
- **🆕 NEW — odometer arbitration holds single-platform (#438 B5 / PR).** The GPS odometer moved off
  each platform's own diff onto one cross-platform decision (starts once when a dash opens, pauses
  when parked at a stop, resumes on leaving, stops when the dash ends). Single-platform behavior
  must feel **identical**. On a normal DoorDash dash, watch the odometer + session miles: (1) the
  "Odometer Active" notification appears **once** at dash start (not re-created per offer/leg); (2)
  the **session miles** in the bubble HUD climb during drives and hold steady while you're parked at
  a pickup/dropoff, then resume climbing when you leave; (3) at dash end the miles look right for the
  distance you actually drove (compare to your car's trip odometer if handy). One improvement to
  watch for specifically: on a **stacked multi-drop** (two drops back-to-back) and on a **skipped/late
  receipt**, the odometer should now keep counting the drive to the next stop even when the app's
  active-task display lags on the previous drop (today's build wrongly paused there). Miles that look
  far too low (odometer stuck paused through a drive) or a session-miles value that resets to ~0
  mid-dash is a B5 regression — capture it. (#438 B5 / PR)
  - Confirmed: 1/2 (07-07 + 07-08 desk analysis: partition deltas sane on every delivery — 5.9 mi
    single-drop, 24.5 mi session-spanning — no stuck-paused zeros, no mid-dash reset, session
    odometer deltas consistent. The "feels identical" + trip-odometer comparison needs dev eyes.)
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
  - Confirmed (mechanism): 1/2 — 07-08 desk analysis: first live firing. The H-E-B shop delivery
    folded **payBasis OFFER_PAY at $14.25** (not a $0-unattributed row), session reconciled exactly.
    The drill-down's "est. offer pay" qualifier display still needs dev eyes; (2)/(4)/(5) unexercised.
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
- **Drop-off odometer vs "at door" timer disagreement (#294, recheck — survivor of #220).**
  On a drop-off arrival, watch **both surfaces at the same moment**: when the HUD flips to
  **AT DOOR**, does the **odometer stop accruing** (bubble session-miles flat while parked)?
  The label is flow-region-driven while `PauseOdometer` fires on the platform-task
  `arrivedAt` flip, so they *can* disagree — and desk analysis (2026-06-04/05 logs) suggests
  `arrivedAt` often stays **null on no-contact drop-offs**, i.e. the odometer may keep
  counting exactly while the label says AT DOOR. Note the delivery type (hand-to-customer vs
  leave-at-door) for each sighting; capture if seen.
  - Confirmed: 0/2.
- **Post-arrival store name is the real merchant (a698bfa scoping; #337).** After arriving at a
  pickup, glance at the bubble task card (and later the PostTask receipt): the store line should
  read the actual merchant ("Chili's Grill & Bar"), never "Walk into store" / "Parking
  instructions" / an order number. Desk-validated 2026-06-10 against the 05-17 Chili's capture
  (the only `instructions_title` inside `mx_contact_view` is the store name); needs live
  confirmation. Extra credit: a McDonald's-style merchant (order-number-heavy arrival screen) —
  that variant was never captured.
  - Confirmed: 0/2.
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
   - **Status:** Open — issue filed 2026-07-12.
3. **#722 gas control validated (first confirmation).** Dev exercised the idle-card gas control:
   "works fine." Checklist item moved to 1/2.
4. **#728 reconfirmed.** Dev restated the direction: gas control and vehicle control should be
   **separate cards**, and the idle-bubble layout overall "could use some polish." #728 (already
   build-ready) is promoted to the front of the build queue.

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
   - **Status:** Open (filed #732).
3. **#733 — D6 join miss → NULL-store delivery (TOP finding).** The Sonic Drive-In + Willie's
   Grill & Icehouse two-pickup single-customer job ($19.50): the dropoff's customer hash matched
   **0 of 2** pickup-lineage hashes ("possible pickup/dropoff hash-format drift" per the WARN,
   ×23) → `delivery_records.storeName = NULL` → the $19.50 buckets under "Unknown store" in every
   per-store view. Replay captures on disk (`dropoff_pre_arrival` 19:42:44 + both pickups).
   - **Status:** Open (filed #733).
4. **#734 — actuation 2-candidate label ties.** `confirm_decline` (×4) and `expand_earnings` (×3)
   across the two dashes resolved 2 verified candidates and clicked the first. No observed
   misfire, but a tie should abort to manual per the #425 posture.
   - **Status:** Open (filed #734).

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
