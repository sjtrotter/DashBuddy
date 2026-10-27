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
