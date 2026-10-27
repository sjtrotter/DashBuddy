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
