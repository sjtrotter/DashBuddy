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
  [`docs/capture-analysis/2026-06-dropoff-arrival-signals.md`](../../capture-analysis/2026-06-dropoff-arrival-signals.md)
  — 5-day fan-out over all 23 June drop-offs. Confirms hand-to-customer is **6/6**
  premature+duplicated while leave-at-door is **14/14** clean; gives the reliable
  arrival signal per type (photo screen / "Mark as delivered" CTA / PIN screen /
  ID-scan screen), the structural fixes (CTA-gate handoff, per-leg idempotent
  confirm, monotonic arrival latch), and the recognition gaps (cant-reach-customer,
  cash-on-delivery, signature, staff-handoff).
- **Unified nav-exit arrival model (50 tasks, pickup + dropoff):** see
  [`docs/capture-analysis/2026-06-task-arrival-navexit-model.md`](../../capture-analysis/2026-06-task-arrival-navexit-model.md)
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
