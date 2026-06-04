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

**Each item needs two independent field confirmations before it's considered
validated** — one dash can pass by luck or miss the edge case. Track progress
with a `- Confirmed: N/2` sub-line (note the date/conditions of each sighting).
On the **second clean** confirmation, move the item into that session's log
entry and delete it here. If an item is found **broken**, move it to the log
immediately (no second pass needed) so it gets triaged.

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
  - Confirmed: 0/2.
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
  - Confirmed: 0/2. **Partial — 2026-06-03 (DoorDash):** the *brief-offline-blip
    resumes same dash* sub-case was seen — an app-switch return fired
    "Session resumed (grace)" (same session, no fresh start). Still unconfirmed:
    that the **active task** survived the blip, and the explicit start-path cases
    (on-demand / scheduled fresh start). See 2026-06-03 log entry #3.

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

---

## Untriaged — carried over from scratch notes

- **Final dash-summary may be unreachable from the idle/offline screen.**
  Hypothesis: the idle-map offline screen shows *before* the dash summary, and
  there may be no way to reach the summary actions once on idle/offline. Needs a
  field repro + capture to confirm.
  - **Status:** Triaged → tracked as #279 (summary attribution fixed in PR; the
    "summary after the idle screen" ordering was the root cause). Field-validate
    via the #279 checklist item above.

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
